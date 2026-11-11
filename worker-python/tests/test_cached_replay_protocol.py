"""Protocol-3 sessions never infer paid capability or configure provider keys."""
from contextlib import closing
from dataclasses import replace

import httpx
import pytest
from livecut.cli import main
from livecut.model_budget import ModelBudgetError
from livecut.worker import Lease, WorkerError
from livecut.worker_client import WorkerClient, WorkerClientError
from test_model_budget import setup_transport
from test_worker import claim
from test_worker_presence import PresenceClient, worker
from test_worker_protocol_versions import ProtocolClient


@pytest.mark.parametrize("status,body,expected", [
    (404, {}, (1, 2)),
    (200, {"cached_answer_replay_supported": True, "worker_protocol": 3}, (1, 2, 3)),
])
def test_independent_replay_cap_does_not_change_original_speech_endpoint(status, body, expected):
    seen = []
    def receive(request):
        seen.append(request.url.path)
        if request.url.path == "/api/agent-capabilities/speech-lookup":
            return httpx.Response(200, json={"speech_lookup_supported": True, "worker_protocol": 2})
        if request.url.path == "/api/agent-capabilities/screen-search":
            return httpx.Response(404, json={})
        assert request.url.path == "/api/agent-capabilities/cached-answer-replay"
        return httpx.Response(status, json=body)
    with closing(WorkerClient("http://localhost", "synthetic-key", transport=httpx.MockTransport(receive))) as client:
        assert client.worker_protocol_versions() == expected
    assert seen == ["/api/agent-capabilities/speech-lookup", "/api/agent-capabilities/cached-answer-replay",
                    "/api/agent-capabilities/screen-search"]


@pytest.mark.parametrize("status,body", [(503, {}), (403, {"error_code": "NOT_FOUND"}),
    (200, {"cached_answer_replay_supported": True, "worker_protocol": True}),
    (200, {"cached_answer_replay_supported": True, "worker_protocol": 3, "extra": 1}),
    (200, {"cached_answer_replay_supported": False, "worker_protocol": 3})])
def test_replay_cap_never_silently_downgrades_an_unavailable_or_invalid_response(status, body):
    with closing(WorkerClient("http://localhost", "synthetic-key", transport=httpx.MockTransport(
            lambda _: httpx.Response(status, json=body)))) as client:
        with pytest.raises(WorkerClientError):
            client.cached_answer_replay_supported()


def test_replay_only_session_never_reads_visual_keys_and_advertises_only_three(tmp_path, monkeypatch):
    original = type(__import__("os").environ).get
    def guarded(environment, key, *args):
        assert key not in {"LIVECUT_AGENT_VISUAL_API_KEY", "LIVECUT_AGENT_VISUAL_BASE_URL"}
        return original(environment, key, *args)
    monkeypatch.setattr(type(__import__("os").environ), "get", guarded)
    client = PresenceClient(idle=True)
    probes = []
    client.cached_answer_replay_supported = lambda: probes.append(3) or True
    client.worker_protocol_versions = lambda: pytest.fail("A replay-only session must not probe paid speech capability")
    runner = worker(tmp_path, client, [], cached_answer_replay_only=True)
    assert runner.run_once() == "idle"
    assert probes == [3]
    assert client.registrations[0][2] == ["AGENT_EVIDENCE"]
    assert client.registrations[0][3] == {"supported_protocol_versions": [3]}
    assert client.ready_calls and all(call["worker_id"] == "worker-1" and "session_id" in call for call in client.ready_calls)
    assert client.claims == []


@pytest.mark.parametrize("stage", ["MEDIA", "ASR", "AGENT_VISUAL"])
def test_replay_only_stage_conflicts_fail_before_capability_or_registration(tmp_path, stage):
    client = PresenceClient(idle=True)
    client.cached_answer_replay_supported = lambda: pytest.fail("No probe for conflicting mode")
    with pytest.raises(WorkerError, match="INVALID_CACHED_REPLAY_CONFIGURATION"):
        worker(tmp_path, client, [], cached_answer_replay_only=True).run_once(stage_type=stage)
    assert client.registrations == []


def test_missing_replay_cap_is_not_a_paid_fallback(tmp_path):
    client = PresenceClient(idle=True)
    client.cached_answer_replay_supported = lambda: False
    with pytest.raises(WorkerClientError, match="CACHED_REPLAY_NOT_SUPPORTED"):
        worker(tmp_path, client, [], cached_answer_replay_only=True).run_once()
    assert client.registrations == client.ready_calls == []


def test_budget_transport_defensively_rejects_protocol_three_before_any_reservation(tmp_path):
    client, api, events, _ = setup_transport(tmp_path, lambda _: pytest.fail("No provider dispatch"))
    transport = client._transport
    transport.context.lease = replace(transport.context.lease, required_worker_protocol=3)
    with client, pytest.raises(ModelBudgetError, match="MODEL_OPERATION_NOT_ALLOWED"):
        client.post("https://synthetic.invalid/chat/completions", json={"model": "synthetic"})
    assert events == [] and api.reservations == []


@pytest.mark.parametrize("extra", [["--stage-type", "MEDIA"], ["--stage-type", "ASR", "--reuse-asr-job", "old-job", "--job-id", "old-job"]])
def test_cli_replay_mode_rejects_conflicting_arguments_before_control_or_provider_client(tmp_path, monkeypatch, capsys, extra):
    monkeypatch.setattr("livecut.worker_client.WorkerClient", lambda *a, **k: pytest.fail("No client for invalid CLI mode"))
    args = ["worker", "--storage-root", str(tmp_path / "storage"), "--journal-dir", str(tmp_path / "journal"),
            "--once", "--cached-answer-replay-only", *extra]
    assert main(args) == 2
    assert "INVALID_CACHED_REPLAY_CONFIGURATION" in capsys.readouterr().out


def test_cli_replay_mode_does_not_construct_a_provider_transport(tmp_path, monkeypatch):
    seen = []
    class ControlClient:
        def __init__(self, *args):
            pass
        def close(self):
            pass
    class Runner:
        def __init__(self, *args, **kwargs):
            assert args[4].transport_factory is None
            assert kwargs["cached_answer_replay_only"] is True
        def run_once(self, **kwargs):
            seen.append(kwargs)
            return "idle"
    monkeypatch.setattr("livecut.worker_client.WorkerClient", ControlClient)
    monkeypatch.setattr("livecut.worker.StageWorker", Runner)
    monkeypatch.setattr("livecut.model_budget.transport_factory", lambda *a: pytest.fail("No provider factory in replay CLI"))
    assert main(["worker", "--storage-root", str(tmp_path / "storage"), "--journal-dir", str(tmp_path / "journal"),
                 "--once", "--cached-answer-replay-only"]) == 0
    assert seen == [{"stage_type": None}]


def test_replay_only_worker_rejects_a_paid_protocol_two_ready_before_claim(tmp_path):
    client = ProtocolClient()
    client.cached_answer_replay_supported = lambda: True
    with pytest.raises(WorkerError, match="INVALID_WORKER_PROTOCOL"):
        worker(tmp_path, client, [], cached_answer_replay_only=True).run_once()
    assert client.claims == [] and client.callbacks == []


def test_protocol_two_session_cannot_claim_replay_ready_three(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://synthetic.invalid")
    client = ProtocolClient()
    ready = client.ready
    def newer(**kwargs):
        result = ready(**kwargs)
        for row in result["items"]:
            row["required_worker_protocol"] = 3
        return result
    client.ready = newer
    with pytest.raises(WorkerError, match="INVALID_WORKER_PROTOCOL"):
        worker(tmp_path, client, []).run_once()
    assert client.claims == [] and client.callbacks == []


@pytest.mark.parametrize("maximum", [1, False, None])
def test_protocol_three_lease_never_admits_a_paid_or_ambiguous_budget(maximum):
    value = claim(stage_type="AGENT_EVIDENCE", required_worker_protocol=3,
        config={"max_text_calls": maximum, "agent_turn": {"evidence_policy": "speech_lookup_v1", "cache_replay": {}}})
    with pytest.raises(WorkerError, match="INVALID_WORKER_PROTOCOL"):
        Lease.parse(value)


def test_replay_ready_and_claim_echo_three_reach_only_the_matching_session(tmp_path):
    client, executions = ProtocolClient(), []
    client.reply = claim(stage_type="AGENT_EVIDENCE", required_worker_protocol=3,
        config={"max_text_calls": 0, "agent_turn": {"evidence_policy": "speech_lookup_v1", "cache_replay": {}}})
    client.cached_answer_replay_supported = lambda: True
    ready = client.ready
    def replay(**kwargs):
        result = ready(**kwargs)
        for row in result["items"]:
            row["required_worker_protocol"] = 3
        return result
    client.ready = replay
    assert worker(tmp_path, client, executions, cached_answer_replay_only=True).run_once() == "completed"
    assert executions == ["stage-1"] and client.registrations[0][3] == {"supported_protocol_versions": [3]}
