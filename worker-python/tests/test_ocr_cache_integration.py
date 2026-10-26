"""CLI/Worker integration fixtures verify evidence flow, not recognition quality."""

import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest
from livecut.cli import main
from livecut.vision import VisionError
from livecut.worker import AnalysisStageExecutor, _native_path, _write, sha256
from test_worker_execution import context, predecessor


def materialize(source, output_dir, **kwargs):
    directory = Path(output_dir)
    (directory / "frames").mkdir(parents=True, exist_ok=True)
    (directory / "frames/ocr_000001.jpg").write_bytes(b"\xff\xd8synthetic-ocr-frame\xff\xd9")
    observations = [{"frame_id": "ocr_000001", "timestamp_ms": 3,
                     "object_key": "frames/ocr_000001.jpg", "presented_category": None,
                     "observed_text": "合成验证文字", "visibility": "clear"}]
    for name, data in (("observations.json", observations), ("frames.json", []),
                       ("ocr-state.json", {"synthetic_fixture": True})):
        _write(directory / name, data)
    return SimpleNamespace(observations=observations,
                           provenance={"schema_version": 1, "cache_hit": True,
                                       "cache_key": "a" * 64})


def test_ocr_cli_prepares_evidence_without_asr_or_text_calls(tmp_path, monkeypatch):
    observed = Mock(side_effect=materialize)
    monkeypatch.setattr("livecut.cli.cached_observe_video_ocr", observed)
    monkeypatch.setattr("livecut.cli.probe_media", lambda *args, **kwargs: SimpleNamespace(duration_ms=1000))
    for name in ("transcribe_file", "discover_products", "analyze_video"):
        monkeypatch.setattr("livecut.cli." + name, Mock(side_effect=AssertionError("Provider path forbidden")))
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        assert main(["ocr", str(tmp_path / "source.mp4"), "--output-dir", str(tmp_path / "attempt"),
                     "--cache-root", str(tmp_path / "shared")]) == 0
    result = json.loads(output.getvalue())
    assert result["frames"] == 1 and result["cache"]["cache_hit"] is True
    assert observed.call_args.kwargs["cache_root"] == tmp_path / "shared"
    assert observed.call_args.kwargs["duration_ms"] == 1000
    assert observed.call_args.kwargs["sampling_policy"] == "uniform"
    assert result["sampling_audit"]["observation_count"] == 1
    assert json.loads((tmp_path / "attempt/cache-provenance.json").read_text())["cache_hit"] is True


def test_analyze_cli_maps_cached_evidence_to_this_output_directory(tmp_path, monkeypatch):
    cached = Mock(side_effect=materialize)
    monkeypatch.setattr("livecut.cli.cached_observe_video_ocr", cached)
    monkeypatch.setattr("livecut.cli.probe_media", lambda *args, **kwargs: SimpleNamespace(duration_ms=1000))
    monkeypatch.setattr("livecut.cli.ocr_model_id", lambda: "rapidocr-synthetic")
    analyze = Mock(return_value={"status": "preview_disabled"})
    monkeypatch.setattr("livecut.cli.analyze_video", analyze)
    with contextlib.redirect_stdout(io.StringIO()):
        assert main(["analyze", str(tmp_path / "source.mp4"), "--output-dir", str(tmp_path / "analysis"),
                     "--ocr-cache-root", str(tmp_path / "shared")]) == 0
    supplied = analyze.call_args.kwargs["visual_observations"]
    assert supplied[0]["object_key"] == str((tmp_path / "analysis-ocr/frames/ocr_000001.jpg").resolve())
    assert supplied[0]["observed_text"] == "合成验证文字"
    assert cached.call_args.kwargs["sampling_policy"] == "uniform"


@pytest.mark.parametrize("strategy", ["prefix", "uniform"])
def test_ocr_cli_records_explicit_local_sampling_strategy(tmp_path, monkeypatch, strategy):
    cached = Mock(side_effect=materialize)
    monkeypatch.setattr("livecut.cli.cached_observe_video_ocr", cached)
    monkeypatch.setattr("livecut.cli.probe_media", lambda *args, **kwargs: SimpleNamespace(duration_ms=1000))
    with contextlib.redirect_stdout(io.StringIO()):
        assert main(["ocr", str(tmp_path / "source.mp4"), "--output-dir", str(tmp_path / "out"),
                     "--cache-root", str(tmp_path / "cache"), "--ocr-sampling", strategy]) == 0
    assert cached.call_args.kwargs["sampling_policy"] == strategy


def test_uniform_policy_never_changes_paid_vision_sampling(tmp_path, monkeypatch):
    observed = Mock(side_effect=AssertionError("Uniform is local OCR only"))
    monkeypatch.setattr("livecut.cli.observe_video", observed)
    monkeypatch.setattr("livecut.cli.probe_media", Mock(side_effect=AssertionError("Fail before any processing")))
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        assert main(["analyze", str(tmp_path / "source.mp4"), "--output-dir", str(tmp_path / "out"),
                     "--visual-provider", "api", "--ocr-sampling", "uniform"]) == 2
    assert json.loads(output.getvalue())["error_code"] == "INVALID_CONFIGURATION"
    observed.assert_not_called()


def test_api_vision_omitted_local_strategy_retains_legacy_arguments(tmp_path, monkeypatch):
    observed = Mock(return_value=[])
    monkeypatch.setattr("livecut.cli.observe_video", observed)
    monkeypatch.setattr("livecut.cli.probe_media", lambda *args, **kwargs: SimpleNamespace(duration_ms=1000))
    monkeypatch.setattr("livecut.cli.analyze_video", Mock(return_value={"status": "preview_disabled"}))
    with contextlib.redirect_stdout(io.StringIO()):
        assert main(["analyze", str(tmp_path / "source.mp4"), "--output-dir", str(tmp_path / "out"),
                     "--visual-provider", "api"]) == 0
    assert "sampling_policy" not in observed.call_args.kwargs


def segment_context(tmp_path, visual="ocr"):
    ctx = context(tmp_path, "SEGMENT")
    ctx.lease.config.update(visual_provider=visual, max_text_calls=2)
    predecessor(ctx, "MEDIA", "media_info", {"duration_ms": 1000})
    predecessor(ctx, "ASR", "transcript", [])
    return ctx


def test_worker_anchors_copied_ocr_and_provenance_in_current_attempt_manifest(tmp_path, monkeypatch):
    ctx = segment_context(tmp_path)
    cached = Mock(side_effect=materialize)
    monkeypatch.setattr("livecut.worker.cached_observe_video_ocr", cached)
    monkeypatch.setattr("livecut.worker.ocr_model_id", lambda: "rapidocr-synthetic")
    supplied = []
    def discover(*args, **kwargs):
        supplied.extend(kwargs["visual_observations"])
        _write(ctx.directory / "analysis-result.json", {"synthetic_fixture": True})
    monkeypatch.setattr("livecut.worker.discover_products", discover)
    manifest = AnalysisStageExecutor(transport_factory=lambda *args: httpx.MockTransport(
        lambda request: (_ for _ in ()).throw(AssertionError("Provider path forbidden"))))(ctx)
    kinds = {artifact["kind"] for artifact in manifest["artifacts"]}
    assert kinds == {"analysis_result", "ocr_cache_provenance", "ocr_observations", "ocr_frames",
                     "ocr_state", "ocr_frame_000001"}
    for artifact in manifest["artifacts"]:
        assert artifact["key"].startswith(ctx.lease.attempt_key + "/")
        assert sha256(ctx.storage_root / artifact["key"]) == artifact["sha256"]
    assert supplied[0]["object_key"] == ctx.lease.attempt_key + "/ocr/frames/ocr_000001.jpg"
    assert cached.call_args.kwargs["cache_root"] == _native_path(ctx.storage_root / "cache/ocr-v1")
    assert callable(cached.call_args.kwargs["guard"])
    assert cached.call_args.kwargs["sampling_policy"] == "prefix"


@pytest.mark.parametrize("strategy", ["prefix", "uniform"])
def test_worker_uses_frozen_job_sampling_config(tmp_path, monkeypatch, strategy):
    ctx = segment_context(tmp_path)
    ctx.lease.config["ocr_sampling"] = strategy
    cached = Mock(side_effect=materialize)
    monkeypatch.setattr("livecut.worker.cached_observe_video_ocr", cached)
    monkeypatch.setattr("livecut.worker.ocr_model_id", lambda: "rapidocr-synthetic")
    monkeypatch.setattr("livecut.worker.discover_products", lambda *args, **kwargs:
                        _write(ctx.directory / "analysis-result.json", {"synthetic_fixture": True}))
    AnalysisStageExecutor(transport_factory=lambda *args: httpx.MockTransport(
        lambda request: (_ for _ in ()).throw(AssertionError("Provider path forbidden"))))(ctx)
    assert cached.call_args.kwargs["sampling_policy"] == strategy


def test_worker_corrupt_cache_stops_before_any_model_transport(tmp_path, monkeypatch):
    ctx = segment_context(tmp_path)
    monkeypatch.setattr("livecut.worker.cached_observe_video_ocr", Mock(
        side_effect=VisionError("OCR_CACHE_CORRUPT", "Synthetic corruption fixture")))
    factory = Mock(side_effect=AssertionError("Provider path forbidden"))
    with pytest.raises(VisionError, match="Synthetic corruption"):
        AnalysisStageExecutor(transport_factory=factory)(ctx)
    factory.assert_not_called()


def test_voice_only_worker_does_not_touch_ocr_cache(tmp_path, monkeypatch):
    ctx = segment_context(tmp_path, visual="none")
    monkeypatch.setattr("livecut.worker.cached_observe_video_ocr", Mock(
        side_effect=AssertionError("Voice-only jobs must not touch OCR")))
    monkeypatch.setattr("livecut.worker.discover_products", lambda *args, **kwargs:
                        _write(ctx.directory / "analysis-result.json", {"synthetic_fixture": True}))
    manifest = AnalysisStageExecutor(transport_factory=lambda *args: httpx.MockTransport(
        lambda request: (_ for _ in ()).throw(AssertionError("Provider path forbidden"))))(ctx)
    assert [artifact["kind"] for artifact in manifest["artifacts"]] == ["analysis_result"]


@pytest.mark.parametrize("code,retryable", [
    ("OCR_CACHE_BUSY", True),
    ("OCR_CACHE_CORRUPT", False),
    ("OCR_CACHE_CAPACITY_EXCEEDED", False),
    ("OCR_CACHE_UNSAFE_PATH", False),
])
def test_stage_worker_persists_only_cache_contention_as_a_retryable_failure(
    tmp_path, monkeypatch, code, retryable,
):
    from livecut.ocr_cache import _error
    from livecut.worker import StageWorker
    from test_worker import FakeClient, claim

    ctx = segment_context(tmp_path)
    client = FakeClient()
    client.reply = claim(stage_type="SEGMENT", source_hash=ctx.lease.source_hash,
                         config=ctx.lease.config, artifacts=ctx.lease.artifacts)
    client.ready = lambda **_: {"items": [{"id": ctx.lease.stage_id, "stage_type": "SEGMENT"}]}
    # Use the production error factory, rather than manufacturing an exception
    # carrying the desired flag. The actual Worker must preserve that policy.
    cached = Mock(side_effect=_error(code))
    monkeypatch.setattr("livecut.worker.cached_observe_video_ocr", cached)
    transport = Mock(side_effect=AssertionError("Cache failure must stop before model transport"))
    journal = tmp_path / "cache-failure-journal"
    runner = StageWorker(client, ctx.storage_root, journal, "cache-fixture-worker",
                         AnalysisStageExecutor(transport_factory=transport))

    assert runner.run_once(stage_type="SEGMENT") == "failed"
    failures = [callback for callback in client.callbacks if callback[0] == "fail"]
    assert len(failures) == 1
    action, stage_id, payload = failures[0]
    assert action == "fail" and stage_id == ctx.lease.stage_id
    assert payload == {"attempt": ctx.lease.attempt, "run_token": ctx.lease.run_token,
                       "error_code": code, "retryable": retryable}
    stored = json.loads(_native_path(ctx.directory / "worker-result.json").read_text(encoding="utf-8"))
    assert stored == {"action": "fail", "stage_id": ctx.lease.stage_id, "payload": payload}
    assert not _native_path(journal / "pending-callback.json").exists()
    assert not any(callback[0] == "complete" for callback in client.callbacks)
    cached.assert_called_once()
    transport.assert_not_called()
