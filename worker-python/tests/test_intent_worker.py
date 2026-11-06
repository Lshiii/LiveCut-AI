"""Worker integration uses explicit synthetic model replies and frozen source artifacts."""
import json

import httpx
import pytest
from livecut.worker import AnalysisStageExecutor, WorkerError, _native_path, sha256
from test_intent import demand, plan, raw, utterances
from test_worker_execution import context, predecessor


def intent_context(tmp_path):
    ctx = context(tmp_path, "SEGMENT")
    predecessor(ctx, "MEDIA", "media_info", {"duration_ms": 9000})
    _, transcript = predecessor(ctx, "ASR", "transcript", utterances())
    ctx.lease.config.update({"clip_request": {"request_id": "request-1", "source_job_id": ctx.lease.job_id,
        "instruction": "分别剪缓存失效原因和解决方法，跳过关注引导。", "duration_ms": 9000,
        "max_text_calls": 6, "text_model_id": "qwen-plus", "source_transcript_hash": sha256(transcript),
        "source_transcript_key": transcript.relative_to(ctx.storage_root).as_posix()}})
    return ctx


def test_intent_worker_reuses_hashed_asr_and_generates_independent_artifacts(tmp_path, monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "synthetic-key")
    monkeypatch.delenv("LIVECUT_INTENT_EMBEDDING_DIR", raising=False)
    ctx = intent_context(tmp_path)
    calls, original = [], list(ctx.lease.artifacts)
    replies = [demand(), plan()]
    def handler(request):
        calls.append(json.loads(request.content))
        assert request.url.path.endswith('/services/aigc/text-generation/generation')
        return httpx.Response(200, json=raw(replies[len(calls) - 1]))
    executor = AnalysisStageExecutor(transport_factory=lambda context, operation: httpx.MockTransport(handler))
    result = executor(ctx)
    assert len(calls) == 2 and ctx.lease.artifacts == original
    assert result["stage_type"] == "SEGMENT" and result["stage_id"] == ctx.lease.stage_id
    assert {a["kind"] for a in result["artifacts"]} == {"clip_plan", "retrieval_index", "retrieval_trace"}
    proposal = json.loads(_native_path(ctx.directory / "clip-plan.json").read_text())
    assert len(proposal["outputs"]) == 2 and all(not o["enabled"] for o in proposal["outputs"])
    assert len(proposal["outputs"][0]["parts"]) == 2
    assert not _native_path(ctx.directory / 'audio.wav').exists()


def test_intent_worker_cannot_bypass_budget_transport(tmp_path):
    with pytest.raises(WorkerError, match="DURABLE_BUDGET_REQUIRED"):
        AnalysisStageExecutor()(intent_context(tmp_path))


def test_intent_worker_checks_frozen_transcript_hash_before_any_provider_call(tmp_path):
    ctx = intent_context(tmp_path)
    ctx.lease.config['clip_request']['source_transcript_hash'] = 'f' * 64
    with pytest.raises(WorkerError, match='SOURCE_HASH_MISMATCH'):
        AnalysisStageExecutor(transport_factory=lambda *args: pytest.fail('must not call provider'))(ctx)


def test_intent_worker_checks_actual_video_bytes_before_any_provider_call(tmp_path):
    ctx = intent_context(tmp_path)
    _native_path(ctx.storage_root / ctx.lease.source_key).write_bytes(b"changed source video")
    with pytest.raises(WorkerError, match='SOURCE_HASH_MISMATCH'):
        AnalysisStageExecutor(transport_factory=lambda *args: pytest.fail('must not call provider'))(ctx)
