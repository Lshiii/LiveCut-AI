"""Synthetic model replies through the real Worker adapter; no ASR or real provider."""
import copy
import json
from pathlib import Path

import httpx
import pytest
from jsonschema import Draft202012Validator
from livecut.worker import AnalysisStageExecutor, WorkerError, _native_path, sha256
from test_video_agent_audit import answer, envelope, source, tool, topics
from test_worker_execution import context, predecessor


def agent_context(tmp_path):
    ctx = context(tmp_path, "SEGMENT")
    items = source()
    segments = []
    positions = {u["utterance_id"]: i for i, u in enumerate(items)}
    for topic in topics():
        first, last = positions[topic["start_utterance_id"]], positions[topic["end_utterance_id"]]
        segments.append({**topic, "start_ms": items[first]["start_ms"], "end_ms": items[last]["end_ms"],
                         "keywords": ["缓存"], "audio_evidence_utterance_ids": [u["utterance_id"] for u in items[first:last + 1]]})
    result = {"schema_version": "1.0", "analysis_mode": "topics", "data_kind": "model_result",
              "job_id": ctx.lease.job_id, "video_id": ctx.lease.video_id,
              "analysis_generation": ctx.lease.generation, "duration_ms": 13000,
              "transcript_version": "synthetic", "pipeline_version": "topics-text-v1", "review_status": "DRAFT",
              "utterances": items, "frames": [], "segments": segments,
              "provenance": {"asr_model_id": "paraformer-v2", "text_model_id": "qwen-plus",
                             "prompt_version": "topic-chapters-v3", "config_hash": "a" * 64}}
    _, transcript = predecessor(ctx, "ASR", "transcript", items)
    _, analysis = predecessor(ctx, "TIMELINE", "analysis_result", result)
    ctx.lease.config.update({"text_model": "qwen-plus", "max_text_calls": 4, "max_asr_submissions": 0,
        "agent_turn": {"turn_id": "turn-1", "session_id": "session-1", "question": " 视频里缓存为什么失效？ ",
            "source_job_id": ctx.lease.job_id, "source_transcript_key": transcript.relative_to(ctx.storage_root).as_posix(),
            "source_transcript_hash": sha256(transcript), "source_analysis_key": analysis.relative_to(ctx.storage_root).as_posix(),
            "source_analysis_hash": sha256(analysis), "duration_ms": 13000, "history": [], "max_tool_steps": 4}})
    return ctx


def test_worker_agent_uses_frozen_sources_and_replays(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "dashscope")
    monkeypatch.delenv("LIVECUT_INTENT_EMBEDDING_DIR", raising=False)
    monkeypatch.setattr("livecut.worker.transcribe_file", lambda *a, **k: pytest.fail("Agent cannot run ASR"))
    ctx = agent_context(tmp_path)
    original = copy.deepcopy(ctx.lease.artifacts)
    calls = []
    replies = [tool("read_context", utterance_id="a1", before=1, after=1), answer(["z0", "a1"])]

    def handler(request):
        calls.append(json.loads(request.content))
        assert len(calls) <= 2
        return httpx.Response(200, json=envelope(replies[len(calls) - 1]))

    def factory(context, operation):
        assert operation == "text"
        return httpx.MockTransport(handler)

    executor = AnalysisStageExecutor(transport_factory=factory)
    manifest = executor(ctx)
    assert executor(ctx) == manifest and len(calls) == 2
    assert ctx.lease.artifacts == original
    assert {row["kind"] for row in manifest["artifacts"]} == {"agent_answer"}
    path = _native_path(ctx.directory / "agent-answer.json")
    result = json.loads(path.read_text(encoding="utf-8"))
    assert result["question"] == ctx.lease.config["agent_turn"]["question"]
    assert result["citations"][0]["text"] == source()[0]["text"]
    assert not _native_path(ctx.directory / "audio.wav").exists()
    schema = json.loads((Path(__file__).resolve().parents[2] / "contracts/agent-answer.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(result)


@pytest.mark.parametrize("field", ["source_transcript_hash", "source_analysis_hash"])
def test_worker_checks_agent_pins_before_provider(tmp_path, field):
    ctx = agent_context(tmp_path)
    ctx.lease.config["agent_turn"][field] = "f" * 64
    with pytest.raises(WorkerError, match="SOURCE_HASH_MISMATCH"):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No provider"))(ctx)


@pytest.mark.parametrize("mutation", [
    lambda c: c.lease.config.update(max_asr_submissions=1),
    lambda c: c.lease.config.update(max_asr_submissions=False),
    lambda c: c.lease.config["agent_turn"].update(source_job_id="other-job"),
    lambda c: c.lease.config["agent_turn"].update(max_tool_steps=5),
    lambda c: c.lease.config.update(clip_request={}),
])
def test_worker_rejects_unsafe_agent_config(tmp_path, mutation):
    ctx = agent_context(tmp_path)
    mutation(ctx)
    with pytest.raises(WorkerError, match="INVALID_AGENT_INPUT"):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No provider"))(ctx)


def test_agent_requires_durable_transport(tmp_path):
    with pytest.raises(WorkerError, match="DURABLE_BUDGET_REQUIRED"):
        AnalysisStageExecutor()(agent_context(tmp_path))


def test_agent_rejects_changed_video(tmp_path):
    ctx = agent_context(tmp_path)
    _native_path(ctx.storage_root / ctx.lease.source_key).write_bytes(b"changed video")
    with pytest.raises(WorkerError, match="SOURCE_HASH_MISMATCH"):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No provider"))(ctx)
