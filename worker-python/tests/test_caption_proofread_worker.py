"""Dedicated capability and artifact routing for frozen caption suggestions."""

import copy
import json
from dataclasses import replace

import httpx
import pytest
from livecut.worker import STAGES, AnalysisStageExecutor, WorkerError, _native_path, sha256
from test_caption_proofread import caption_context, decision
from test_video_agent_audit import envelope
from test_video_agent_worker import agent_context
from test_worker_execution import predecessor


def proofread_context(tmp_path):
    ctx = agent_context(tmp_path)
    config = copy.deepcopy(ctx.lease.config)
    config["agent_turn"].update(
        kind="CAPTION",
        caption_context=caption_context(
            transcript_hash=config["agent_turn"]["source_transcript_hash"]
        ),
    )
    ctx.lease = replace(ctx.lease, stage_type="CAPTION_PROOFREAD", config=config)
    return ctx


def test_worker_declares_new_capability_and_publishes_only_caption_artifact(tmp_path, monkeypatch):
    assert "CAPTION_PROOFREAD" in STAGES
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "dashscope")
    monkeypatch.delenv("LIVECUT_INTENT_EMBEDDING_DIR", raising=False)
    monkeypatch.setattr("livecut.worker.transcribe_file", lambda *a, **k: pytest.fail("No ASR"))
    ctx = proofread_context(tmp_path)
    original = copy.deepcopy(ctx.lease.artifacts)
    calls = []

    def factory(context, operation):
        assert operation == "text"

        def handle(request):
            calls.append(json.loads(request.content))
            return httpx.Response(200, json=envelope(decision()))

        return httpx.MockTransport(handle)

    execute = AnalysisStageExecutor(transport_factory=factory)
    manifest = execute(ctx)
    assert execute(ctx) == manifest and len(calls) == 1
    assert manifest["stage_type"] == "CAPTION_PROOFREAD"
    assert {item["kind"] for item in manifest["artifacts"]} == {"caption_proofread"}
    assert not _native_path(ctx.directory / "agent-answer.json").exists()
    assert ctx.lease.artifacts == original
    result = json.loads(
        _native_path(ctx.directory / "caption-proofread.json").read_text(encoding="utf-8")
    )
    assert result["instruction"] == ctx.lease.config["agent_turn"]["question"]


@pytest.mark.parametrize(
    "mutation",
    [
        lambda c: setattr(c, "lease", replace(c.lease, stage_type="SEGMENT")),
        lambda c: c.lease.config["agent_turn"].update(kind="VIDEO"),
        lambda c: c.lease.config["agent_turn"].update(
            history=[{"question": "old", "answer": "old"}]
        ),
        lambda c: c.lease.config["agent_turn"].pop("caption_context"),
        lambda c: c.lease.config.pop("agent_turn"),
        lambda c: c.lease.config.update(max_asr_submissions=1),
    ],
)
def test_caption_kind_cannot_run_as_old_segment_or_with_unsafe_claim(tmp_path, mutation):
    ctx = proofread_context(tmp_path)
    mutation(ctx)
    with pytest.raises(WorkerError):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No provider"))(ctx)


@pytest.mark.parametrize("key", ["source_transcript_hash", "source_analysis_hash"])
def test_caption_sources_remain_pinned_before_any_text_request(tmp_path, key):
    ctx = proofread_context(tmp_path)
    ctx.lease.config["agent_turn"][key] = "f" * 64
    with pytest.raises(WorkerError, match="SOURCE_HASH_MISMATCH"):
        AnalysisStageExecutor(transport_factory=lambda *a: pytest.fail("No provider"))(ctx)


def test_caption_worker_requires_durable_budget_transport(tmp_path):
    with pytest.raises(WorkerError, match="DURABLE_BUDGET_REQUIRED"):
        AnalysisStageExecutor()(proofread_context(tmp_path))


def test_caption_worker_accepts_verified_historical_product_analysis(tmp_path, monkeypatch):
    from livecut.contracts import validate_analysis_result
    from test_video_agent_audit import source

    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.delenv("LIVECUT_INTENT_EMBEDDING_DIR", raising=False)
    ctx = proofread_context(tmp_path)
    product_analysis = {
        "schema_version": "2.0",
        "data_kind": "model_result",
        "job_id": ctx.lease.job_id,
        "video_id": ctx.lease.video_id,
        "analysis_generation": ctx.lease.generation,
        "duration_ms": 13000,
        "transcript_version": "synthetic",
        "pipeline_version": "catalog-free-v1",
        "review_status": "DRAFT",
        "provenance": {
            "asr_model_id": "paraformer-v2",
            "text_model_id": "qwen-plus",
            "vision_model_id": None,
            "prompt_version": "catalog-free-v1",
            "config_hash": "a" * 64,
        },
        "product_discovery_version": "synthetic",
        "products": [],
        "utterances": source(),
        "frames": [],
        "segments": [],
    }
    validate_analysis_result(product_analysis)
    ctx.lease.artifacts[:] = [
        item for item in ctx.lease.artifacts if item["stage_type"] != "TIMELINE"
    ]
    _, path = predecessor(ctx, "TIMELINE", "analysis_result", product_analysis)
    ctx.lease.config["agent_turn"]["source_analysis_hash"] = sha256(path)
    manifest = AnalysisStageExecutor(
        transport_factory=lambda *_: httpx.MockTransport(
            lambda request: httpx.Response(200, json=envelope(decision()))
        )
    )(ctx)
    assert manifest["artifacts"][0]["kind"] == "caption_proofread"


def test_new_caption_stage_filter_is_preserved_by_cli(tmp_path, monkeypatch, capsys):
    from livecut.cli import main

    seen = []

    class Client:
        def __init__(self, *args):
            pass

        def close(self):
            seen.append("closed")

    class Runner:
        def __init__(self, *args, **kwargs):
            pass

        def run_once(self, **kwargs):
            seen.append(kwargs)
            return "idle"

    monkeypatch.setenv("LIVECUT_WORKER_API_KEY", "synthetic-key")
    monkeypatch.setattr("livecut.worker_client.WorkerClient", Client)
    monkeypatch.setattr("livecut.worker.StageWorker", Runner)
    assert (
        main(
            [
                "worker",
                "--once",
                "--stage-type",
                "CAPTION_PROOFREAD",
                "--storage-root",
                str(tmp_path),
                "--journal-dir",
                str(tmp_path / "journal"),
            ]
        )
        == 0
    )
    assert seen == [{"stage_type": "CAPTION_PROOFREAD"}, "closed"]
    assert "synthetic-key" not in capsys.readouterr().out


@pytest.mark.parametrize("declared_steps", [0, 4])
def test_caption_worker_accepts_zero_steps_and_clamps_tools_to_remaining_turn_budget(
    tmp_path, monkeypatch, declared_steps
):
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.delenv("LIVECUT_INTENT_EMBEDDING_DIR", raising=False)
    ctx = proofread_context(tmp_path)
    ctx.lease.config["agent_turn"]["max_tool_steps"] = declared_steps
    ctx.lease.config["max_text_calls"] = 1
    requests = []

    def respond(request):
        wire = json.loads(request.content)
        messages = wire.get("messages", wire.get("input", {}).get("messages"))
        requests.append(json.loads(messages[1]["content"]))
        return httpx.Response(200, json=envelope(decision()))

    manifest = AnalysisStageExecutor(transport_factory=lambda *_: httpx.MockTransport(respond))(ctx)
    assert manifest["artifacts"][0]["kind"] == "caption_proofread"
    assert len(requests) == 1 and requests[0]["remaining_tool_steps"] == 0


def test_video_worker_still_requires_at_least_one_declared_tool_step(tmp_path):
    ctx = agent_context(tmp_path)
    ctx.lease.config["agent_turn"]["max_tool_steps"] = 0
    with pytest.raises(WorkerError, match="INVALID_AGENT_INPUT"):
        AnalysisStageExecutor(transport_factory=lambda *_: pytest.fail("No provider"))(ctx)
