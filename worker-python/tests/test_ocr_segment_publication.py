"""Complete synthetic OCR/semantic execution; no external provider or OCR model.

The actual cache, topic parser, optional budget/retry transport and stage executor
run against generated marker JPEGs and explicitly synthetic model responses.
These tests establish publish shape and integrity, never recognition quality.
"""

import json
from dataclasses import replace
from pathlib import Path

import httpx
import pytest
from livecut.evaluation_inputs import _manifest_binding
from livecut.model_budget import BudgetTransport
from livecut.ocr_cache import cached_observe_video_ocr
from livecut.text_resilience import ResilientTextTransport, TextRetryPolicy
from livecut.topic_contracts import validate_topic_result
from livecut.worker import AnalysisStageExecutor, sha256
from test_ocr_cache import SyntheticEngine, SyntheticExtractor
from test_text_resilience import Ledger
from test_topics import raw, topic
from test_worker_execution import context, predecessor


@pytest.mark.parametrize("frame_count", [0, 120])
@pytest.mark.parametrize("with_trace", [False, True])
def test_executor_complete_120_frame_ocr_and_plain_manifest(tmp_path, monkeypatch, frame_count, with_trace):
    ctx = context(tmp_path, "SEGMENT")
    ctx.lease = replace(ctx.lease, run_token="11111111-1111-4111-8111-111111111111")
    ctx.directory = tmp_path / ctx.lease.attempt_key
    ctx.directory.mkdir(parents=True)
    duration = 5400000
    ctx.lease.config.update(analysis_mode="topics", visual_provider="ocr" if frame_count else "none",
                            ocr_sampling="uniform", ocr_max_frames=120, max_text_calls=2)
    predecessor(ctx, "MEDIA", "media_info", {"duration_ms": duration})
    predecessor(ctx, "ASR", "transcript", [{"utterance_id": "u0", "start_ms": 0,
                "end_ms": 900, "text": "合成解析内容", "source_chunk_id": "synthetic-chunk"}])
    ffmpeg = tmp_path / "synthetic-ffmpeg.exe"
    ffmpeg.write_bytes(b"synthetic-ffmpeg-identity-not-an-executable")
    engine, extractor = SyntheticEngine(), SyntheticExtractor()
    cache_results, requests, ledger = [], [], Ledger(limit=2)

    def cached(*args, **kwargs):
        assert frame_count == 120, "No-OCR mode must not open or build the OCR cache"
        result = cached_observe_video_ocr(*args, **kwargs, engine=engine,
            engine_identity="synthetic-engine-v1", extractor=extractor,
            extractor_identity="synthetic-extractor-v1")
        cache_results.append(result)
        return result

    def semantic(request):
        assert request.url.host == "synthetic.invalid"
        body = json.loads(request.content)
        payload = json.loads(body["input"]["messages"][1]["content"])
        assert payload["core_utterance_ids"] == ["u0"]
        requests.append(body)
        return httpx.Response(200, json=raw({"topics": [topic("u0", title="合成话题")]}))

    def transport(stage_context, kind):
        assert stage_context is ctx and kind == "text"
        inner = httpx.MockTransport(semantic)
        if with_trace:
            return ResilientTextTransport(BudgetTransport(ledger, ctx, inner=inner), ctx,
                                          policy=TextRetryPolicy(max_attempts=1))
        return inner

    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "synthetic-key")
    monkeypatch.setenv("LIVECUT_TEXT_BASE_URL", "https://synthetic.invalid")
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "dashscope")
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", "[]")
    monkeypatch.setattr("livecut.worker.cached_observe_video_ocr", cached)
    monkeypatch.setattr("livecut.worker.ocr_model_id", lambda: "custom-ocr-engine")
    manifest = AnalysisStageExecutor(transport_factory=transport, ffmpeg_path=str(ffmpeg))(ctx)
    assert len(requests) == 1
    assert len(ledger.calls) == int(with_trace)
    assert all(call["status"] == "SUCCEEDED" for call in ledger.calls.values())
    assert len(manifest["artifacts"]) == (125 if frame_count else 1) + int(with_trace)
    for artifact in manifest["artifacts"]:
        assert artifact["key"].startswith(ctx.lease.attempt_key + "/")
        assert sha256(tmp_path / artifact["key"]) == artifact["sha256"]
    artifact = manifest["artifacts"][0]
    result = json.loads((tmp_path / artifact["key"]).read_bytes())
    validate_topic_result(result)
    assert len(result["frames"]) == frame_count
    _manifest_binding(manifest, result, ctx.lease.source_hash, artifact["sha256"])
    if frame_count:
        assert engine.calls == len(extractor.calls) == 120
        assert len(cache_results) == 1 and cache_results[0].provenance["cache_hit"] is False
        assert cache_results[0].provenance["frame_count"] == 120
        assert cache_results[0].provenance["coverage_truncated"] is False
        assert extractor.calls[-1] > duration - 45000
        frame_kinds = {row["kind"] for row in manifest["artifacts"] if row["kind"].startswith("ocr_frame_")}
        assert frame_kinds == {f"ocr_frame_{index:06d}" for index in range(1, 121)}
        shared = json.loads((Path(__file__).resolve().parents[2]
            / "fixtures/ocr-segment-artifact-capacity.synthetic.json").read_bytes())
        case_id = "ocr_120_with_trace_126" if with_trace else "ocr_120_without_trace_125"
        expected = next(case["manifest"] for case in shared["cases"] if case["case_id"] == case_id)
        expected_prefix = (f"jobs/{expected['job_id']}/stages/{expected['stage_id']}/attempts/"
                           f"{expected['attempt']}/{expected['run_token']}/")
        assert {row["kind"]: row["key"].removeprefix(ctx.lease.attempt_key + "/")
                for row in manifest["artifacts"]} == {
                    row["kind"]: row["key"].removeprefix(expected_prefix) for row in expected["artifacts"]}
    else:
        assert engine.calls == 0 and extractor.calls == [] and cache_results == []
        assert {row["kind"] for row in manifest["artifacts"]} == (
            {"analysis_result", "text_execution_trace"} if with_trace else {"analysis_result"})
