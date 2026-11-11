"""Generic execution protocol negotiation is read-only and fail-closed."""
import copy
import json
import threading
from contextlib import contextmanager
from uuid import uuid4

import httpx
import pytest
from livecut.worker import Lease, WorkerError
from livecut.worker_client import WorkerClient, WorkerClientError
from test_worker import claim
from test_worker_presence import PresenceClient, worker


@pytest.mark.parametrize("supported", [False, True])
def test_bootstrap_controls_exact_legacy_or_versioned_register_and_ready_wire(supported):
    seen = []
    worker_id, session_id = "worker-1", str(uuid4())
    def receive(request):
        seen.append(request)
        if request.url.path == "/api/agent-capabilities/speech-lookup":
            return httpx.Response(200, json={"speech_lookup_supported": True, "worker_protocol": 2}) if supported else httpx.Response(404, text="old server")
        if request.url.path == "/api/agent-capabilities/cached-answer-replay":
            return httpx.Response(404, text="old server")
        if request.url.path == "/api/agent-capabilities/screen-search":
            return httpx.Response(404, text="old server")
        if request.url.path == "/internal/workers/register":
            return httpx.Response(200, json={"worker_id": worker_id, "session_id": session_id, "outcome": "REGISTERED",
                "heartbeat_timeout_seconds": 35, "last_seen_at": "2026-11-11T00:00:00Z"})
        return httpx.Response(200, json={"items": []})
    client = WorkerClient("http://localhost", "synthetic-key", transport=httpx.MockTransport(receive))
    try:
        protocols = client.worker_protocol_versions()
        assert protocols == ((1, 2) if supported else (1,))
        extra = {"supported_protocol_versions": list(protocols)} if supported else {}
        client.register_worker(worker_id, session_id, ["MEDIA"], **extra)
        client.ready(**({"worker_id": worker_id, "session_id": session_id} if supported else {}))
    finally:
        client.close()
    registered = json.loads(next(r for r in seen if r.url.path == "/internal/workers/register").content)
    ready = next(r for r in seen if r.url.path == "/internal/stages/ready")
    assert ("supported_protocol_versions" in registered) == supported
    assert ("worker_id" in ready.url.params) == ("session_id" in ready.url.params) == supported
    if supported:
        assert registered["supported_protocol_versions"] == [1, 2]
    else:
        assert set(registered) == {"worker_id", "session_id", "stage_types", "slots"}
        assert dict(ready.url.params) == {"limit": "10"}


@pytest.mark.parametrize("status,body", [(503, {}), (403, {"error_code": "NOT_FOUND"}),
    (200, {"speech_lookup_supported": True, "worker_protocol": True}),
    (200, {"speech_lookup_supported": True, "worker_protocol": 2, "extra": 1}),
    (200, {"speech_lookup_supported": False, "worker_protocol": 2}), (200, {"outcome": "ACCEPTED"})])
def test_only_actual_http_404_can_select_old_protocol(status, body):
    requests = []
    def receive(request):
        requests.append(request.url.path)
        return httpx.Response(status, json=body)
    client = WorkerClient("http://localhost", "synthetic-key", transport=httpx.MockTransport(receive))
    try:
        with pytest.raises(WorkerClientError):
            client.worker_protocol_versions()
    finally:
        client.close()
    assert requests == ["/api/agent-capabilities/speech-lookup"]


@pytest.mark.parametrize("versions", [[], [True], [1, 1], [5], [None], "1,2"])
def test_invalid_registration_versions_are_rejected_before_dispatch(versions):
    client = WorkerClient("http://localhost", "synthetic-key", transport=httpx.MockTransport(lambda _: pytest.fail("No register dispatch")))
    try:
        with pytest.raises(WorkerClientError, match="INVALID_WORKER_PROTOCOL_VERSIONS"):
            client.register_worker("worker-1", str(uuid4()), ["MEDIA"], supported_protocol_versions=versions)
    finally:
        client.close()


class ProtocolClient(PresenceClient):
    def __init__(self, *, versioned=True, mismatch=False):
        super().__init__()
        self.versioned, self.mismatch, self.bootstraps = versioned, mismatch, 0
        if versioned:
            self.reply = claim(stage_type="AGENT_EVIDENCE", required_worker_protocol=2,
                               config={"agent_turn": {"evidence_policy": "speech_lookup_v1"}})

    def worker_protocol_versions(self):
        self.bootstraps += 1
        return (1, 2) if self.versioned else (1,)

    def ready(self, **kwargs):
        result = super().ready(**kwargs)
        if self.versioned and result["items"]:
            result["items"][0]["required_worker_protocol"] = 2
        return result

    def claim(self, *args, **kwargs):
        result = super().claim(*args, **kwargs)
        if self.mismatch:
            result = copy.deepcopy(result)
            result.pop("required_worker_protocol", None)
        return result


@pytest.mark.parametrize("versioned", [False, True])
def test_stage_worker_uses_one_immutable_bootstrap_and_matching_ready_session(tmp_path, monkeypatch, versioned):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://visual.invalid/v1")
    client, executions = ProtocolClient(versioned=versioned), []
    runner = worker(tmp_path, client, executions)
    assert runner.run_once() == "completed"
    assert client.bootstraps == 1 and executions == ["stage-1"]
    registration = client.registrations[0]
    assert ("supported_protocol_versions" in registration[3]) == versioned
    for options in client.ready_calls:
        assert ("worker_id" in options) == ("session_id" in options) == versioned
        if versioned:
            assert options["worker_id"] == "worker-1" and options["session_id"] == registration[1]


def test_versioned_claim_cannot_lose_the_ready_protocol_echo(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://visual.invalid/v1")
    client, executions = ProtocolClient(mismatch=True), []
    with pytest.raises(WorkerError, match="CLAIM_PROTOCOL_CHANGED"):
        worker(tmp_path, client, executions).run_once()
    assert executions == [] and client.callbacks == []


def test_bootstrap_uses_cancellation_guard_before_any_registration(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://visual.invalid/v1")
    stop = threading.Event()
    class CancelClient(ProtocolClient):
        @contextmanager
        def request_scope(self, *, deadline_at, request_guard):
            self.control_guard = request_guard
            yield

        def worker_protocol_versions(self):
            stop.set()
            self.control_guard()
            pytest.fail("A cancelled bootstrap cannot continue")
    client = CancelClient()
    assert worker(tmp_path, client, [], stop=stop).run_once(stage_type="AGENT_EVIDENCE") == "stopped"
    assert client.registrations == client.ready_calls == client.claims == []


@pytest.mark.parametrize("stage", ["MEDIA", "ASR", "SEGMENT", "CAPTION_PROOFREAD", "ANCHOR_CLIP", "AGENT_VISUAL", "AGENT_VISUAL_FOCUS", "TIMELINE", "EXPORT_CLIP"])
def test_non_evidence_sessions_never_depend_on_speech_capability(tmp_path, monkeypatch, stage):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://visual.invalid/v1")
    client = ProtocolClient()
    client.worker_protocol_versions = lambda: pytest.fail("An unrelated v1 session must not probe the speech endpoint")
    runner = worker(tmp_path, client, [])
    with runner._registered(stage) as registered:
        assert registered and runner._protocol_versions == (1,)
    assert "supported_protocol_versions" not in client.registrations[0][3]


def test_one_process_can_switch_declared_stage_sessions_without_reusing_the_wrong_protocol(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://visual.invalid/v1")
    client = ProtocolClient()
    runner = worker(tmp_path, client, [])
    for stage, expected in (("MEDIA", (1,)), ("AGENT_EVIDENCE", (1, 2)), ("MEDIA", (1,)), ("AGENT_EVIDENCE", (1, 2))):
        with runner._registered(stage) as registered:
            assert registered and runner._protocol_versions == expected
        assert ("supported_protocol_versions" in client.registrations[-1][3]) == (2 in expected)
    assert client.bootstraps == 1


@pytest.mark.parametrize("protocol,stage,policy", [(True, "AGENT_EVIDENCE", "speech_lookup_v1"),
    (3, "AGENT_EVIDENCE", "speech_lookup_v1"), (2, "AGENT_EVIDENCE", None),
    (None, "AGENT_EVIDENCE", "speech_lookup_v1"), (2, "MEDIA", "speech_lookup_v1")])
def test_lease_cannot_admit_policy_and_protocol_mismatch(protocol, stage, policy):
    value = claim(stage_type=stage, config={"agent_turn": {"evidence_policy": policy}})
    if protocol is not None:
        value["required_worker_protocol"] = protocol
    with pytest.raises(WorkerError, match="INVALID_WORKER_PROTOCOL"):
        Lease.parse(value)
