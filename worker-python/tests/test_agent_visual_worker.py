"""Worker/ledger visual integration uses synthetic replies and real local media only."""
import copy
import json
import threading
from dataclasses import asdict, replace

import httpx
import pytest
from livecut.media import probe_media
from livecut.model_budget import BudgetTransport, ModelBudgetError
from livecut.segmentation import SegmentationError
from livecut.text_resilience import ResilientTextTransport, TextRecoveryError
from livecut.worker import (
    AnalysisStageExecutor,
    Lease,
    LeaseGuard,
    StageContext,
    StageWorker,
    WorkerError,
    _native_path,
    sha256,
)
from test_agent_visual import answer, tool
from test_agent_visual import real_source as _real_source
from test_model_budget import setup_transport
from test_text_resilience import Ledger, setup
from test_video_agent_worker import agent_context
from test_worker import FakeClient, claim
from test_worker_execution import predecessor

real_source = _real_source


def visual_context(tmp_path, real_source):
    ctx = agent_context(tmp_path)
    source = _native_path(ctx.storage_root / ctx.lease.source_key)
    source.write_bytes(real_source.read_bytes())
    config = copy.deepcopy(ctx.lease.config)
    config.update(text_model="synthetic-vlm", text_fallback_models=[])
    config["agent_turn"]["visual_mode"] = "frames"
    transcript = json.loads((ctx.storage_root / config["agent_turn"]["source_transcript_key"]).read_text(encoding="utf-8"))
    analysis = json.loads((ctx.storage_root / config["agent_turn"]["source_analysis_key"]).read_text(encoding="utf-8"))
    media = asdict(probe_media(source))
    media.pop("path")
    analysis["duration_ms"] = config["agent_turn"]["duration_ms"] = media["duration_ms"]
    ctx.lease = replace(ctx.lease, stage_type="AGENT_VISUAL", source_hash=sha256(source), artifacts=[], config=config)
    ctx.guard = LeaseGuard(ctx.lease, threading.Event())
    _, transcript_path = predecessor(ctx, "ASR", "transcript", transcript)
    _, analysis_path = predecessor(ctx, "TIMELINE", "analysis_result", analysis)
    predecessor(ctx, "MEDIA", "media_info", media)
    for name, path in (("transcript", transcript_path), ("analysis", analysis_path)):
        config["agent_turn"][f"source_{name}_hash"] = sha256(path)
    return ctx


def mock_executor(monkeypatch, replies, seen):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic-independent-visual-key")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://visual.invalid/v1")
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "must-not-be-used")
    class VisualMock(httpx.MockTransport):
        def visual_text_budget(self):
            return self.max_calls-len(seen)
    def handler(request):
        seen.append(json.loads(request.content))
        assert request.headers["authorization"] == "Bearer synthetic-independent-visual-key"
        reply = replies[len(seen)-1]
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(reply, ensure_ascii=False)}}]})
    def factory(context, operation):
        assert operation == "text"
        transport = VisualMock(handler)
        transport.max_calls = context.lease.config["max_text_calls"]
        return transport
    return AnalysisStageExecutor(transport_factory=factory)


def test_visual_worker_manifest_contains_exact_fenced_real_jpegs_and_replays(tmp_path, real_source, monkeypatch):
    ctx = visual_context(tmp_path, real_source)
    seen = []
    executor = mock_executor(monkeypatch, [tool("inspect_frames", timestamps_ms=[0, 500]), answer(visual=["f0001"])], seen)
    result = executor(ctx)
    assert result["stage_type"] == "AGENT_VISUAL" and result["run_token"] == ctx.lease.run_token
    frames = [file for file in result["artifacts"] if file["kind"] == "agent_visual_frame"]
    assert len(frames) == 2 and all(file["key"].startswith(ctx.directory.relative_to(ctx.storage_root).as_posix()+"/visual-frames/") for file in frames)
    assert all(sha256(ctx.storage_root / file["key"]) == file["sha256"] for file in frames)
    assert executor(ctx) == result and len(seen) == 2
    assert set(file["kind"] for file in result["artifacts"]) == {"agent_answer", "agent_visual_frame"}
    assert not (ctx.directory / "audio.wav").exists()


@pytest.mark.parametrize("field,value", [("video_start_time_seconds", 1), ("format_start_time_seconds", 1), ("rotation_degrees", 10)])
def test_bad_actual_media_is_rejected_before_any_visual_provider(tmp_path, real_source, monkeypatch, field, value):
    ctx = visual_context(tmp_path, real_source)
    actual = probe_media(_native_path(ctx.storage_root / ctx.lease.source_key))
    monkeypatch.setattr("livecut.agent_frames.probe_media", lambda *args, **kwargs: replace(actual, **{field: value}))
    seen = []
    with pytest.raises(SegmentationError):
        mock_executor(monkeypatch, [], seen)(ctx)
    assert seen == []


def test_bad_frozen_media_is_rejected_before_any_visual_provider(tmp_path, real_source, monkeypatch):
    ctx = visual_context(tmp_path, real_source)
    media = asdict(probe_media(_native_path(ctx.storage_root / ctx.lease.source_key)))
    media.pop("path")
    media["width"] += 1
    ctx.lease.artifacts[:] = [item for item in ctx.lease.artifacts if item["stage_type"] != "MEDIA"]
    predecessor(ctx, "MEDIA", "media_info", media)
    seen = []
    with pytest.raises(SegmentationError, match="timeline and dimensions"):
        mock_executor(monkeypatch, [], seen)(ctx)
    assert seen == []


def test_owner_duration_must_equal_real_frozen_media_before_any_provider(tmp_path, real_source, monkeypatch):
    ctx = visual_context(tmp_path, real_source)
    config = ctx.lease.config["agent_turn"]
    config["duration_ms"] = 13000
    analysis = json.loads((ctx.storage_root / config["source_analysis_key"]).read_text(encoding="utf-8"))
    analysis["duration_ms"] = 13000
    ctx.lease.artifacts[:] = [item for item in ctx.lease.artifacts if item["stage_type"] != "TIMELINE"]
    _, path = predecessor(ctx, "TIMELINE", "analysis_result", analysis)
    config["source_analysis_hash"] = sha256(path)
    seen = []
    with pytest.raises(SegmentationError, match="timeline and dimensions"):
        mock_executor(monkeypatch, [], seen)(ctx)
    assert seen == []


def test_visual_mode_cannot_sneak_into_legacy_text_stage(tmp_path, monkeypatch):
    ctx = agent_context(tmp_path)
    ctx.lease.config["agent_turn"]["visual_mode"] = "frames"
    with pytest.raises(WorkerError, match="INVALID_AGENT_VISUAL_INPUT"):
        AnalysisStageExecutor(transport_factory=lambda *args: pytest.fail("No provider"))(ctx)


def test_unconfigured_default_worker_does_not_advertise_or_poll_visual(tmp_path, monkeypatch):
    monkeypatch.delenv("LIVECUT_AGENT_VISUAL_API_KEY", raising=False)
    monkeypatch.delenv("LIVECUT_AGENT_VISUAL_BASE_URL", raising=False)
    client = FakeClient()
    worker = StageWorker(client, tmp_path, tmp_path / "journal", "synthetic-worker", lambda ctx: None, registry_enabled=False)
    assert worker._available_stages(None) == ["MEDIA", "ASR", "SEGMENT", "CAPTION_PROOFREAD", "ANCHOR_CLIP", "TIMELINE", "EXPORT_CLIP"]
    with pytest.raises(WorkerError, match="VISUAL_NOT_CONFIGURED"):
        worker.run_once(stage_type="AGENT_VISUAL")
    assert client.claims == []


def test_configured_visual_worker_advertises_new_capability(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_AGENT_VISUAL_BASE_URL", "https://visual.invalid/v1")
    worker = StageWorker(FakeClient(), tmp_path, tmp_path / "journal", "synthetic-worker", lambda ctx: None, registry_enabled=False)
    assert "AGENT_VISUAL" in worker._available_stages(None)


def test_visual_native_poc_endpoint_is_never_forwarded_unbudgeted(tmp_path):
    client, ledger, events, _ = setup_transport(tmp_path, lambda request: pytest.fail("No provider"), stage_type="AGENT_VISUAL")
    with client, pytest.raises(ModelBudgetError, match="MODEL_OPERATION_NOT_ALLOWED"):
        client.post("https://visual.invalid/api/v1/services/aigc/multimodal-generation/generation", json={"model": "synthetic-vlm"})
    assert ledger.reservations == [] and events == []


def test_visual_chat_reserves_text_ledger_and_unknown_original_is_never_reissued(tmp_path):
    client, ledger, events, _ = setup_transport(tmp_path, lambda request: (_ for _ in ()).throw(httpx.ReadTimeout("synthetic", request=request)), stage_type="AGENT_VISUAL")
    body = {"model": "synthetic-vlm", "messages": [{"role": "user", "content": []}]}
    with client:
        with pytest.raises(httpx.ReadTimeout):
            client.post("https://visual.invalid/v1/chat/completions", json=body)
        with pytest.raises(ModelBudgetError, match="MODEL_CALL_REQUIRES_RECOVERY"):
            client.post("https://visual.invalid/v1/chat/completions", json=body)
    assert ledger.status == "UNKNOWN_OUTCOME" and events.count("provider") == 1
    assert all(item["kind"] == "TEXT" for item in ledger.reservations)


def test_visual_physical_retries_are_counted_and_never_route_text_fallback(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", "not even valid text fallback JSON")
    lease = Lease.parse(claim(stage_type="AGENT_VISUAL", config={"text_fallback_models": []}))
    ctx = StageContext(lease, tmp_path, tmp_path / "attempt", LeaseGuard(lease, threading.Event()))
    hits = []
    def provider(request):
        hits.append(request.url.host)
        return httpx.Response(500 if len(hits) == 1 else 200, json={"choices": []})
    client, ledger, _ = setup(tmp_path, provider, ledger=Ledger(), context=ctx)
    with client:
        assert client.post("https://visual.invalid/chat/completions", json={"model": "synthetic-vlm", "messages": []}).status_code == 200
    assert len(ledger.calls) == 2 and hits == ["visual.invalid", "visual.invalid"]
    assert list(ledger.calls.values())[0]["status"] == "UNKNOWN_OUTCOME"
    bad = replace(lease, config={"text_fallback_models": ["text-only-model"]})
    with pytest.raises(TextRecoveryError, match="INVALID_VISUAL_FALLBACK_CONFIGURATION"):
        ResilientTextTransport(BudgetTransport(ledger, ctx), replace(ctx, lease=bad))


def visual_budget_transport(tmp_path):
    _, api, events, _ = setup_transport(tmp_path, lambda request: pytest.fail("Budget reads must not call a provider"), stage_type="AGENT_VISUAL")
    lease = Lease.parse(claim(stage_type="AGENT_VISUAL", config={"max_text_calls": 4,
        "agent_turn": {"turn_id": "turn-1", "session_id": "session-1", "visual_mode": "frames"}}))
    context = StageContext(lease, tmp_path, tmp_path / "budget-attempt", LeaseGuard(lease, threading.Event()))
    snapshot = {"id": "turn-1", "session_id": "session-1", "job_id": lease.job_id, "visual_mode": "frames", "status": "RUNNING",
        "stage": {"id": lease.stage_id, "stage_type": "AGENT_VISUAL", "status": "RUNNING", "attempt": lease.attempt},
        "budget": {"max_text_calls": 4, "text_calls_reserved": 1}}
    reads = []
    api.agent_turn = lambda turn: (reads.append(turn), copy.deepcopy(snapshot))[1]
    return BudgetTransport(api, context, inner=httpx.MockTransport(lambda request: pytest.fail("No provider"))), snapshot, reads, events


def test_visual_budget_get_uses_exact_owner_and_never_opens_tools_on_budget_rollback(tmp_path):
    transport, snapshot, reads, events = visual_budget_transport(tmp_path)
    assert transport.visual_text_budget() == 3
    snapshot["budget"]["text_calls_reserved"] = 3
    assert transport.visual_text_budget() == 1
    snapshot["budget"]["text_calls_reserved"] = 2
    assert transport.visual_text_budget() is None
    assert reads == ["turn-1"]*3 and events == []


@pytest.mark.parametrize("mutate", [
    lambda value: value.update(id="other-turn"),
    lambda value: value.update(session_id="other-session"),
    lambda value: value.update(job_id="other-job"),
    lambda value: value.update(visual_mode="none"),
    lambda value: value["stage"].update(id="other-stage"),
    lambda value: value["stage"].update(stage_type="SEGMENT"),
    lambda value: value["stage"].update(attempt=True),
    lambda value: value["budget"].update(max_text_calls=True),
    lambda value: value["budget"].update(text_calls_reserved=True),
])
def test_visual_budget_foreign_or_malformed_snapshots_fail_closed(tmp_path, mutate):
    transport, snapshot, _, events = visual_budget_transport(tmp_path)
    mutate(snapshot)
    assert transport.visual_text_budget() is None and events == []


@pytest.mark.parametrize("mutate", [lambda value: value.update(status="CANCELLED"),
    lambda value: value["stage"].update(status="FAILED"), lambda value: value["stage"].update(attempt=2)])
def test_visual_budget_stale_or_terminal_owner_loses_lease(tmp_path, mutate):
    transport, snapshot, _, events = visual_budget_transport(tmp_path)
    mutate(snapshot)
    with pytest.raises(WorkerError, match="LEASE_LOST"):
        transport.visual_text_budget()
    assert events == []
