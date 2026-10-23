"""Mock orchestration, resume and preview semantics; no paid provider calls."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from livecut import pipeline
from livecut.asr import ASRError
from livecut.contracts import ContractValidationError, validate_analysis_result
from livecut.media import MediaError


def _prediction(utterances: list[dict], **kwargs) -> dict:
    products = [
        {"product_id": "p-bag", "name": "垃圾袋", "aliases": ["抽绳袋"],
         "attributes": {"category": "垃圾袋"}, "identity_granularity": "category"},
        {"product_id": "p-mask", "name": "面膜", "aliases": ["保湿片"],
         "attributes": {"category": "面膜"}, "identity_granularity": "category"},
    ]
    segments = []
    for index, (kind, status, pid) in enumerate([
        ("product_talk", "identified", "p-bag"),
        ("product_talk", "identified", "p-mask"),
        ("product_talk", "identified", "p-bag"),
        ("product_talk", "ambiguous", None),
        ("non_product", "not_applicable", None),
    ]):
        utterance = utterances[index]
        segments.append({
            "segment_id": f"segment-{index}", "start_ms": utterance["start_ms"],
            "end_ms": utterance["end_ms"], "start_utterance_id": utterance["utterance_id"],
            "end_utterance_id": utterance["utterance_id"], "content_kind": kind,
            "identity": {"status": status, "product_id": pid,
                         "candidate_product_ids": [pid] if pid else (
                             ["p-bag", "p-mask"] if status == "ambiguous" else [])},
            "audio_evidence_utterance_ids": [utterance["utterance_id"]],
            "visual_status": "not_checked", "visual_observations": [], "risk_score": 0.5,
            "review_required": status == "ambiguous",
            "review_reasons": ["IDENTITY_AMBIGUOUS"] if status == "ambiguous" else [],
        })
    return {
        "schema_version": "2.0", "data_kind": "model_result", "job_id": kwargs["job_id"],
        "video_id": kwargs["video_id"], "analysis_generation": 1, "duration_ms": 6000,
        "transcript_version": "asr-v1", "pipeline_version": "catalog-free-text-v1",
        "review_status": "DRAFT", "product_discovery_version": pipeline.PROMPT_VERSION,
        "provenance": {"asr_model_id": kwargs["asr_model_id"],
                       "text_model_id": kwargs["model"], "vision_model_id": None,
                       "prompt_version": pipeline.PROMPT_VERSION, "config_hash": "unit-config"},
        "products": products, "utterances": utterances, "frames": [], "segments": segments,
    }


@pytest.fixture
def harness(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    source = tmp_path / "source.mp4"
    source.write_bytes(b"not real video: mocked media only")
    state = SimpleNamespace(source=source, output=tmp_path / "run", extracted=[],
                            asr_calls=[], discovery_calls=[], exports=[], prediction=None,
                            transform=None, export_failures=set())
    utterances = [{"utterance_id": f"u-{index}", "start_ms": index * 1000,
                   "end_ms": index * 1000 + 900, "text": "未预设商品清单的原始讲解",
                   "source_chunk_id": "c_001"} for index in range(5)]

    def probe(path, **kwargs):
        return SimpleNamespace(path=Path(path).resolve(), duration_ms=6000)

    def extract(source_path, target, **kwargs):
        state.extracted.append((source_path, target, kwargs))
        Path(target).write_bytes(b"mock verified WAV")
        return Path(target)

    def transcribe(audio_path, output, **kwargs):
        state.asr_calls.append((audio_path, output, kwargs))
        return copy.deepcopy(utterances)

    def discover(items, duration, output, **kwargs):
        state.discovery_calls.append((copy.deepcopy(items), duration, output, kwargs))
        prediction = _prediction(items, **kwargs)
        if state.transform:
            state.transform(prediction)
        validate_analysis_result(prediction)
        state.prediction = copy.deepcopy(prediction)
        # Distinct formatting verifies pipeline preserves the exact original.
        (Path(output) / "analysis-result.json").write_text(
            json.dumps(prediction, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        return prediction

    def export(source_path, target, start, end, **kwargs):
        state.exports.append((source_path, target, start, end, kwargs))
        if start in state.export_failures:
            raise MediaError("TRANSCODE_FAILED", "private signed-url?secret=never-copy-this")
        Path(target).write_bytes(b"mock draft MP4")
        return SimpleNamespace(duration_ms=end - start)

    monkeypatch.setattr(pipeline, "probe_media", probe)
    monkeypatch.setattr(pipeline, "extract_audio", extract)
    monkeypatch.setattr(pipeline, "transcribe_file", transcribe)
    monkeypatch.setattr(pipeline, "discover_products", discover)
    monkeypatch.setattr(pipeline, "export_clip", export)
    return state


def test_end_to_end_orchestration_preserves_original_and_marks_draft(harness) -> None:
    report = pipeline.analyze_video(
        harness.source, harness.output, ffmpeg_path="my-ffmpeg", ffprobe_path="my-ffprobe",
        asr_base_url="https://asr.example/api/v1", text_base_url="https://text.example/api/v1",
        max_model_calls=7,
    )
    assert report["status"] == "ok"
    assert report["review_status"] == "DRAFT"
    assert report["human_approved"] is False and report["official_export"] is False
    assert len(report["preview_clips"]) == 3
    assert [(entry[2], entry[3]) for entry in harness.exports] == [(0, 900), (1000, 1900), (2000, 2900)]
    assert harness.extracted[0][2] == {"ffmpeg_path": "my-ffmpeg", "ffprobe_path": "my-ffprobe"}
    assert harness.asr_calls[0][1] == harness.output / "asr"
    assert harness.asr_calls[0][2] == {"base_url": "https://asr.example/api/v1", "model": "paraformer-v2"}
    kwargs = harness.discovery_calls[0][3]
    short_hash = hashlib.sha256(harness.source.read_bytes()).hexdigest()[:16]
    assert kwargs["video_id"] == "video-" + short_hash
    assert kwargs["job_id"] == "job-" + short_hash
    assert kwargs["asr_model_id"] == "paraformer-v2"
    assert kwargs["model"] == "qwen-plus" and kwargs["max_calls"] == 7
    assert "catalog" not in kwargs and "sku" not in kwargs
    assert all(clip["human_approved"] is False for clip in report["preview_clips"])
    raw_text = (harness.output / "analysis-result.json").read_text(encoding="utf-8")
    assert raw_text == json.dumps(harness.prediction, ensure_ascii=False, separators=(",", ":"))
    assert "preview_clips" not in harness.prediction
    manifest = json.loads((harness.output / "run-manifest.json").read_text(encoding="utf-8"))
    assert manifest["result"]["sha256"] == hashlib.sha256(raw_text.encode()).hexdigest()
    assert manifest["outputs"]["preview_succeeded"] == 3
    assert "https://" not in json.dumps(manifest)


@pytest.mark.parametrize("query,expected", [("垃圾袋", 2), (" 保 湿 片 ", 1), ("眼影", 0)])
def test_query_uses_discovered_names_and_aliases_only(harness, query, expected) -> None:
    report = pipeline.analyze_video(harness.source, harness.output, product_query=query)
    assert len(report["preview_clips"]) == expected
    assert report["status"] == ("ok" if expected else "no_match")
    assert report["statistics"]["matched_segments"] == expected


def test_preview_limit_reports_unexported_matches(harness) -> None:
    report = pipeline.analyze_video(harness.source, harness.output, max_preview_clips=1)
    assert len(report["preview_clips"]) == 1
    assert report["statistics"]["matched_segments"] == 3
    assert report["statistics"]["preview_limit_skipped"] == 2


def test_resume_preserves_prediction_without_new_provider_calls(harness) -> None:
    pipeline.analyze_video(harness.source, harness.output)
    original = (harness.output / "analysis-result.json").read_bytes()
    second = pipeline.analyze_video(harness.source, harness.output,
                                    product_query="面膜", max_preview_clips=0)
    assert second["status"] == "preview_disabled"
    assert len(harness.extracted) == len(harness.asr_calls) == len(harness.discovery_calls) == 1
    assert (harness.output / "analysis-result.json").read_bytes() == original


def _frame_observation(category: str = "面膜") -> dict:
    return {"frame_id": "frame-001", "timestamp_ms": 500,
            "object_key": "frames/frame-001.jpg", "presented_category": category,
            "observed_text": "主播举起包装，画面字幕可见", "visibility": "clear"}


def test_visual_evidence_is_forwarded_without_source_metadata(harness) -> None:
    observations = [_frame_observation()]
    original = copy.deepcopy(observations)
    pipeline.analyze_video(harness.source, harness.output, visual_observations=observations)
    forwarded = harness.discovery_calls[0][3]["visual_observations"]
    assert forwarded == observations == original
    assert forwarded is not observations and forwarded[0] is not observations[0]
    manifest = json.loads((harness.output / "run-manifest.json").read_text(encoding="utf-8"))
    canonical = json.dumps(observations, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    assert manifest["input"]["visual_observations_sha256"] == hashlib.sha256(canonical.encode()).hexdigest()
    assert manifest["input"]["text_prompt_version"] == pipeline.PROMPT_VERSION
    assert "observed_text" not in json.dumps(manifest)
    assert "source_offset" not in harness.discovery_calls[0][3]
    assert "publisher" not in harness.discovery_calls[0][3]


def test_changed_visual_evidence_or_prompt_cannot_reuse_old_predictions(harness, monkeypatch) -> None:
    pipeline.analyze_video(harness.source, harness.output,
                           visual_observations=[_frame_observation()])
    with pytest.raises(pipeline.PipelineError) as changed_visual:
        pipeline.analyze_video(harness.source, harness.output,
                               visual_observations=[_frame_observation("保湿霜")])
    assert changed_visual.value.code == "RUN_INPUT_MISMATCH"
    monkeypatch.setattr(pipeline, "PROMPT_VERSION", "new-prompt-revision")
    with pytest.raises(pipeline.PipelineError) as changed_prompt:
        pipeline.analyze_video(harness.source, harness.output,
                               visual_observations=[_frame_observation()])
    assert changed_prompt.value.code == "RUN_INPUT_MISMATCH"
    assert len(harness.asr_calls) == len(harness.discovery_calls) == 1


def test_explicit_asr_cache_is_reused_across_separate_visual_runs(harness, monkeypatch) -> None:
    shared = harness.output.parent / "existing-asr-cache"
    shared.mkdir()
    checkpoint = shared / "checkpoint.json"
    checkpoint.write_text('{"provider_task_id":"existing-paid-task"}', encoding="utf-8")
    original_transcribe = pipeline.transcribe_file
    audio_hashes = []

    def cached(audio, output, **kwargs):
        assert output == shared
        assert json.loads(checkpoint.read_text(encoding="utf-8"))["provider_task_id"] == "existing-paid-task"
        audio_hashes.append(hashlib.sha256(Path(audio).read_bytes()).hexdigest())
        return original_transcribe(audio, output, **kwargs)

    monkeypatch.setattr(pipeline, "transcribe_file", cached)
    pipeline.analyze_video(harness.source, harness.output, asr_output_dir=shared)
    alternate_run = harness.output.parent / "visual-run"
    pipeline.analyze_video(harness.source, alternate_run, asr_output_dir=shared,
                           visual_observations=[_frame_observation()])
    assert audio_hashes[0] == audio_hashes[1]
    assert all(call[1] == shared for call in harness.asr_calls)
    manifest = json.loads((alternate_run / "run-manifest.json").read_text(encoding="utf-8"))
    assert manifest["asr_cache"] == {"path": str(shared), "explicit_shared_cache": True}
    assert checkpoint.read_text(encoding="utf-8") == '{"provider_task_id":"existing-paid-task"}'


def test_mismatched_explicit_asr_cache_fails_without_text_model(harness, monkeypatch) -> None:
    shared = harness.output.parent / "other-cache"

    def mismatched(audio, output, **kwargs):
        assert output == shared
        raise ASRError("CHECKPOINT_INPUT_MISMATCH", "Cache belongs to another audio/model input")

    monkeypatch.setattr(pipeline, "transcribe_file", mismatched)
    with pytest.raises(ASRError) as error:
        pipeline.analyze_video(harness.source, harness.output, asr_output_dir=shared)
    assert error.value.code == "CHECKPOINT_INPUT_MISMATCH"
    assert harness.discovery_calls == [] and harness.exports == []


def test_provider_task_resume_reuses_audio_and_same_asr_directory(harness, monkeypatch) -> None:
    original_transcribe = pipeline.transcribe_file
    first = True

    def resumable(audio, output, **kwargs):
        nonlocal first
        checkpoint = Path(output) / "checkpoint.json"
        if first:
            first = False
            Path(output).mkdir()
            checkpoint.write_text('{"provider_task_id":"already-submitted-task"}', encoding="utf-8")
            raise ASRError("POLL_TIMEOUT", "Existing task still running", retryable=True)
        assert json.loads(checkpoint.read_text(encoding="utf-8"))["provider_task_id"] == "already-submitted-task"
        return original_transcribe(audio, output, **kwargs)

    monkeypatch.setattr(pipeline, "transcribe_file", resumable)
    with pytest.raises(ASRError, match="Existing task"):
        pipeline.analyze_video(harness.source, harness.output)
    report = pipeline.analyze_video(harness.source, harness.output)
    assert report["status"] == "ok" and len(harness.extracted) == 1
    assert harness.asr_calls[0][1] == harness.output / "asr"


def test_other_input_or_model_cannot_reuse_run(harness) -> None:
    pipeline.analyze_video(harness.source, harness.output)
    with pytest.raises(pipeline.PipelineError) as changed_model:
        pipeline.analyze_video(harness.source, harness.output, text_model="another-model")
    assert changed_model.value.code == "RUN_INPUT_MISMATCH"
    harness.source.write_bytes(b"a different input in the same path")
    with pytest.raises(pipeline.PipelineError) as changed_input:
        pipeline.analyze_video(harness.source, harness.output)
    assert changed_input.value.code == "RUN_INPUT_MISMATCH"
    assert len(harness.asr_calls) == len(harness.discovery_calls) == 1


def test_unclaimed_output_cannot_supply_arbitrary_cached_audio(harness) -> None:
    harness.output.mkdir()
    (harness.output / "audio.wav").write_bytes(b"unknown origin")
    with pytest.raises(pipeline.PipelineError) as error:
        pipeline.analyze_video(harness.source, harness.output)
    assert error.value.code == "UNCLAIMED_OUTPUT"
    assert not harness.asr_calls


def test_modified_audio_is_not_submitted_on_resume(harness, monkeypatch) -> None:
    def timeout(*args, **kwargs):
        raise ASRError("POLL_TIMEOUT", "provider task still running", retryable=True)

    monkeypatch.setattr(pipeline, "transcribe_file", timeout)
    with pytest.raises(ASRError):
        pipeline.analyze_video(harness.source, harness.output)
    (harness.output / "audio.wav").write_bytes(b"tampered audio")
    with pytest.raises(pipeline.PipelineError) as error:
        pipeline.analyze_video(harness.source, harness.output)
    assert error.value.code == "AUDIO_CHANGED" and len(harness.extracted) == 1


@pytest.mark.parametrize("mutation,code", [("modify", "PREDICTION_CHANGED"),
                                         ("delete", "PREDICTION_MISSING")])
def test_prediction_is_immutable_across_resume(harness, mutation, code) -> None:
    pipeline.analyze_video(harness.source, harness.output)
    path = harness.output / "analysis-result.json"
    if mutation == "modify":
        data = json.loads(path.read_text(encoding="utf-8"))
        data["products"][0]["name"] = "manually edited product"
        path.write_text(json.dumps(data), encoding="utf-8")
    else:
        path.unlink()
    with pytest.raises(pipeline.PipelineError) as error:
        pipeline.analyze_video(harness.source, harness.output)
    assert error.value.code == code and len(harness.discovery_calls) == 1


def test_partial_preview_failures_are_explicit_and_prediction_unchanged(harness) -> None:
    harness.export_failures = {1000}
    report = pipeline.analyze_video(harness.source, harness.output)
    assert report["status"] == "partial"
    assert len(report["preview_clips"]) == 2 and len(report["preview_failures"]) == 1
    assert report["preview_failures"][0]["error_code"] == "TRANSCODE_FAILED"
    assert "never-copy-this" not in json.dumps(report)
    assert json.loads((harness.output / "analysis-result.json").read_text(encoding="utf-8")) == harness.prediction


def test_all_preview_failures_do_not_claim_clips_exist(harness) -> None:
    harness.export_failures = {0, 1000, 2000}
    report = pipeline.analyze_video(harness.source, harness.output)
    assert report["status"] == "preview_failed" and report["preview_clips"] == []
    assert report["statistics"]["preview_succeeded"] == 0


def test_unresolved_segments_are_reported_without_creating_previews(harness) -> None:
    def transform(prediction):
        for segment in prediction["segments"]:
            if segment["identity"]["status"] == "identified":
                segment["identity"] = {"status": "unknown", "product_id": None,
                                       "candidate_product_ids": []}
                segment["review_required"] = True
                segment["review_reasons"] = ["UNKNOWN_PRODUCT"]

    harness.transform = transform
    report = pipeline.analyze_video(harness.source, harness.output)
    assert report["status"] == "no_identified_segments"
    assert report["preview_clips"] == [] and harness.exports == []


def test_invalid_manifest_references_do_not_trigger_resubmission(harness) -> None:
    pipeline.analyze_video(harness.source, harness.output)
    path = harness.output / "run-manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["result"] = {}
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(pipeline.PipelineError) as error:
        pipeline.analyze_video(harness.source, harness.output)
    assert error.value.code == "INVALID_CHECKPOINT"
    assert len(harness.asr_calls) == len(harness.discovery_calls) == 1


def test_unsafe_model_names_cannot_control_output_paths(harness) -> None:
    def transform(prediction):
        prediction["products"][0]["name"] = "../../outside/CON:fake"
        prediction["segments"][0]["segment_id"] = "../../same/segment"
        prediction["segments"][2]["segment_id"] = "..\\..\\same\\segment"

    harness.transform = transform
    report = pipeline.analyze_video(harness.source, harness.output)
    paths = [Path(clip["path"]) for clip in report["preview_clips"]]
    assert len(set(paths)) == 3
    assert all(path.parent == harness.output / "preview-clips" for path in paths)
    assert all("outside" not in path.name and ":" not in path.name for path in paths)


def test_invalid_prediction_never_creates_previews(harness, monkeypatch) -> None:
    def invalid(*args, **kwargs):
        return {"data_kind": "synthetic_fixture"}

    monkeypatch.setattr(pipeline, "discover_products", invalid)
    with pytest.raises(ContractValidationError):
        pipeline.analyze_video(harness.source, harness.output)
    assert harness.exports == []


@pytest.mark.parametrize("kwargs", [{"max_model_calls": 0}, {"max_model_calls": True},
                                    {"max_preview_clips": -1}, {"max_preview_clips": False},
                                    {"product_query": " "}, {"product_query": 123},
                                    {"visual_observations": {"frame_id": "frame-001"}},
                                    {"visual_observations": ["not an observation"]},
                                    {"visual_observations": [{"timestamp_ms": float("nan")}]}])
def test_bad_arguments_fail_before_providers(harness, kwargs) -> None:
    with pytest.raises(pipeline.PipelineError) as error:
        pipeline.analyze_video(harness.source, harness.output, **kwargs)
    assert error.value.code == "INVALID_ARGUMENT" and not harness.asr_calls
