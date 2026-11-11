"""Protocol 4 capability is independent and keeps cached-only protocol 3."""
import json
from uuid import uuid4

import httpx
import pytest
from livecut.worker import Lease, WorkerError
from livecut.worker_client import WorkerClient, WorkerClientError
from test_worker import claim
from test_worker_protocol_versions import ProtocolClient, worker


@pytest.mark.parametrize("replay", [False, True])
@pytest.mark.parametrize("screen", [False, True])
def test_independent_screen_capability_only_adds_version4(replay, screen):
    seen = []
    def receive(request):
        seen.append(request.url.path)
        if request.url.path.endswith("/speech-lookup"):
            return httpx.Response(200, json={"speech_lookup_supported": True, "worker_protocol": 2})
        if request.url.path.endswith("/cached-answer-replay"):
            return httpx.Response(200, json={"cached_answer_replay_supported": True, "worker_protocol": 3}) if replay else httpx.Response(404)
        assert request.url.path.endswith("/screen-search")
        return httpx.Response(200, json={"screen_search_supported": True, "worker_protocol": 4}) if screen else httpx.Response(404)
    client = WorkerClient("http://localhost", "synthetic", transport=httpx.MockTransport(receive))
    try:
        assert client.worker_protocol_versions() == (1, 2, *((3,) if replay else ()), *((4,) if screen else ()))
        assert len(seen) == 3
    finally:
        client.close()


@pytest.mark.parametrize("status,body", [(503, {}), (403, {}), (200, {}),
    (200, {"screen_search_supported": False, "worker_protocol": 4}),
    (200, {"screen_search_supported": True, "worker_protocol": True}),
    (200, {"screen_search_supported": True, "worker_protocol": 4, "extra": True})])
def test_screen_capability_never_downgrades_non404_or_malformed_response(status, body):
    client = WorkerClient("http://localhost", "synthetic", transport=httpx.MockTransport(lambda _: httpx.Response(status, json=body)))
    try:
        with pytest.raises(WorkerClientError):
            client.screen_search_supported()
    finally:
        client.close()


def test_registration_version4_is_valid_and_canonical():
    seen = []
    worker_id, session_id = "worker-1", str(uuid4())
    def receive(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"worker_id": worker_id, "session_id": session_id, "outcome": "REGISTERED",
            "heartbeat_timeout_seconds": 35, "last_seen_at": "2026-11-11T00:00:00Z"})
    client = WorkerClient("http://localhost", "synthetic", transport=httpx.MockTransport(receive))
    try:
        client.register_worker(worker_id, session_id, ["AGENT_EVIDENCE"], supported_protocol_versions=[4, 1, 3, 2])
    finally:
        client.close()
    assert seen[0]["supported_protocol_versions"] == [1, 2, 3, 4]


def test_matching_v4_ready_and_claim_are_executed_in_registered_session(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://synthetic.invalid")
    class Client(ProtocolClient):
        def worker_protocol_versions(self):
            self.bootstraps += 1
            return (1, 2, 3, 4)
        def ready(self, **kwargs):
            result = super().ready(**kwargs)
            if result["items"]:
                result["items"][0]["required_worker_protocol"] = 4
            return result
    client = Client()
    client.reply = claim(stage_type="AGENT_EVIDENCE", required_worker_protocol=4,
        config={"agent_turn": {"evidence_policy": "screen_search_v1", "screen_context": {}}})
    executions = []
    assert worker(tmp_path, client, executions).run_once(stage_type="AGENT_EVIDENCE") == "completed"
    assert executions == ["stage-1"] and client.registrations[0][3]["supported_protocol_versions"] == [1, 2, 3, 4]


@pytest.mark.parametrize("protocol,policy,context", [(2, "screen_search_v1", {}), (3, "screen_search_v1", {}),
    (4, "speech_lookup_v1", {}), (4, "screen_search_v1", None), (1, "screen_search_v1", {})])
def test_policy_protocol_or_missing_private_source_is_rejected(protocol, policy, context):
    value = claim(stage_type="AGENT_EVIDENCE", required_worker_protocol=protocol,
        config={"agent_turn": {"evidence_policy": policy, "screen_context": context}})
    with pytest.raises(WorkerError, match="INVALID_WORKER_PROTOCOL"):
        Lease.parse(value)
