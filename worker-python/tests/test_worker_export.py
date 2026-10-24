"""Frozen export protocol tests and real, explicitly synthetic media checks."""

import dataclasses
import json
import os
import shutil
import subprocess
import threading

import httpx
import pytest
from livecut.model_budget import BudgetTransport, ModelBudgetError
from livecut.worker import (
    AnalysisStageExecutor,
    Lease,
    LeaseGuard,
    StageContext,
    StageWorker,
    WorkerError,
    _native_path,
    _read,
    sha256,
)
from livecut.worker_client import WorkerClientError
from livecut.worker_export import ExportSpec, ExportStageExecutor
from test_worker import FakeClient, claim


def export_config(**overrides):
    return {"export_id": "21e5e338-d0b1-4db2-a5fb-c1cd7eeaf94f",
            "clip_id": "4d0fec5d-8899-4210-b045-784503cd3ace", "approved_revision": 0,
            "snapshot_hash": "b" * 64, "segment_id": "segment-1", "start_ms": 1250,
            "end_ms": 2750, "encoding_profile": "h264-aac-v1", **overrides}


def export_claim(**overrides):
    return claim(**{"stage_type": "EXPORT_CLIP", "input_hash": "c" * 64,
                    "config": {"export": export_config()}, **overrides})


@pytest.mark.parametrize("field,value", [
    ("start_ms", True), ("start_ms", 1.5), ("start_ms", -1), ("end_ms", 1000),
    ("approved_revision", True), ("approved_revision", -1),
    ("snapshot_hash", "not-a-hash"), ("encoding_profile", "arbitrary-codec"),
    ("export_id", "../export"), ("clip_id", "arbitrary-id"), ("segment_id", "path/escape"),
])
def test_export_claim_rejects_unfrozen_or_invalid_fields(field, value):
    with pytest.raises(WorkerError, match="INVALID_EXPORT_INPUT"):
        Lease.parse(export_claim(config={"export": export_config(**{field: value})}))


@pytest.mark.parametrize("segment_id", ["manual:1", "manual.part:2", "x" * 128])
def test_export_accepts_legal_review_segment_identifiers(segment_id):
    lease = Lease.parse(export_claim(config={"export": export_config(segment_id=segment_id)}))
    assert ExportSpec.from_lease(lease).segment_id == segment_id


def test_export_requires_stage_input_hash_and_exact_frozen_config():
    for overrides in ({"input_hash": None}, {"config": {"export": export_config(), "extra": True}},
                      {"config": {"export": {**export_config(), "unexpected": 1}}}):
        with pytest.raises(WorkerError, match="INVALID_EXPORT_INPUT"):
            Lease.parse(export_claim(**overrides))


@pytest.fixture(scope="module")
def tools_and_sources(tmp_path_factory):
    ffmpeg = os.environ.get("LIVECUT_FFMPEG_PATH") or shutil.which("ffmpeg")
    ffprobe = os.environ.get("LIVECUT_FFPROBE_PATH") or shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        pytest.skip("real FFmpeg and ffprobe are required")
    root = tmp_path_factory.mktemp("explicit-synthetic-export-media")
    sources = {}
    for audio in (True, False):
        path = root / ("synthetic-audio.mp4" if audio else "synthetic-silent.mp4")
        command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y"]
        for color in ("red", "blue", "green", "yellow"):
            command += ["-f", "lavfi", "-i", f"color=c={color}:s=64x64:r=25:d=1"]
        if audio:
            command += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4"]
        command += ["-filter_complex", "[0:v][1:v][2:v][3:v]concat=n=4:v=1:a=0[v]", "-map", "[v]"]
        if audio:
            command += ["-map", "4:a:0", "-c:a", "aac"]
        command += ["-c:v", "libx264", "-g", "1000", "-keyint_min", "1000",
                    "-sc_threshold", "0", "-pix_fmt", "yuv420p", "-t", "4", str(path)]
        completed = subprocess.run(command, capture_output=True, timeout=30)
        assert completed.returncode == 0
        sources[audio] = path
    return ffmpeg, ffprobe, sources


def prepare_context(tmp_path, source, *, deep=False, config=None):
    root = tmp_path
    while deep and len(str(root)) < 270:
        root = root / ("deep-export-" + "x" * 25)
    destination = root / "videos/source.mp4"
    _native_path(destination.parent).mkdir(parents=True, exist_ok=True)
    _native_path(destination).write_bytes(source.read_bytes())
    value = export_claim(source_key="videos/source.mp4", source_hash=sha256(destination))
    if config is not None:
        value["config"] = {"export": config}
    lease = Lease.parse(value)
    directory = root / lease.attempt_key
    _native_path(directory).mkdir(parents=True, exist_ok=True)
    return StageContext(lease, root, directory, LeaseGuard(lease, threading.Event()))


def first_rgb(ffmpeg, path, at_seconds):
    result = subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", str(at_seconds),
                             "-i", str(_native_path(path)), "-frames:v", "1", "-f", "rawvideo",
                             "-pix_fmt", "rgb24", "-"], capture_output=True, timeout=30)
    assert result.returncode == 0 and len(result.stdout) == 64 * 64 * 3
    return tuple(sum(result.stdout[channel::3]) / (64 * 64) for channel in range(3))


@pytest.mark.parametrize("audio", [True, False])
def test_real_export_precise_cut_full_decode_and_optional_audio(tmp_path, tools_and_sources, audio):
    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[audio])
    manifest = AnalysisStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)(context)
    assert manifest["data_kind"] == "reviewed_export" and manifest["review_status"] == "CONFIRMED"
    assert manifest["stage_input_hash"] == context.lease.input_hash
    assert manifest["approved_revision"] == 0
    assert {item["kind"] for item in manifest["artifacts"]} == {"clip", "media_info"}
    metadata = _read(context.directory / "media-info.json")
    assert abs(metadata["duration_ms"] - 1500) <= 80
    assert metadata["video_codec"] == "h264" and metadata["audio_codec"] == ("aac" if audio else None)
    first = first_rgb(ffmpeg, context.directory / "clip.mp4", 0)
    last = first_rgb(ffmpeg, context.directory / "clip.mp4", 1.44)
    assert first[2] > 200 and first[0] < 40
    assert last[1] > 80 and last[0] < 40
    stream_probe = subprocess.run([
        ffprobe, "-v", "error", "-show_entries", "stream=codec_type,start_time,duration",
        "-of", "json", str(_native_path(context.directory / "clip.mp4")),
    ], capture_output=True, timeout=30)
    assert stream_probe.returncode == 0
    streams = {item["codec_type"]: item for item in json.loads(stream_probe.stdout)["streams"]}
    for item in streams.values():
        assert abs(float(item["start_time"])) <= 0.04
        assert abs(float(item["duration"]) - 1.5) <= 0.1
    if audio:
        assert abs(float(streams["video"]["duration"]) - float(streams["audio"]["duration"])) <= 0.1
    for item in manifest["artifacts"]:
        assert item["sha256"] == sha256(context.storage_root / item["key"])


def test_same_attempt_and_published_clip_recovery_never_reencode(tmp_path, tools_and_sources, monkeypatch):
    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[True])
    executor = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)
    original = executor(context)
    monkeypatch.setattr("livecut.worker_export.export_clip", lambda *args, **kwargs: pytest.fail("must not reencode"))
    assert executor(context) == original
    _native_path(context.directory / "export-checkpoint.json").unlink()
    assert executor(context) == original


def test_existing_export_does_not_accept_changed_binding_or_bytes(tmp_path, tools_and_sources, monkeypatch):
    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[True])
    executor = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)
    executor(context)
    monkeypatch.setattr("livecut.worker_export.export_clip", lambda *args, **kwargs: pytest.fail("must not reencode"))
    changed = dataclasses.replace(context.lease, config={"export": export_config(approved_revision=1)})
    with pytest.raises(WorkerError, match="EXPORT_INPUT_CHANGED"):
        executor(dataclasses.replace(context, lease=changed))
    _native_path(context.directory / "clip.mp4").write_bytes(b"changed")
    with pytest.raises(WorkerError, match="EXPORT_ARTIFACT_CHANGED"):
        executor(context)


def test_source_hash_and_bounds_fail_before_any_transcode(tmp_path, tools_and_sources, monkeypatch):
    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[True], config=export_config(end_ms=5000))
    monkeypatch.setattr("livecut.worker_export.export_clip", lambda *args, **kwargs: pytest.fail("must not encode"))
    executor = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)
    with pytest.raises(WorkerError, match="INVALID_EXPORT_INTERVAL"):
        executor(context)
    changed = dataclasses.replace(context.lease, source_hash="d" * 64)
    with pytest.raises(WorkerError, match="SOURCE_HASH_MISMATCH"):
        executor(dataclasses.replace(context, lease=changed))


def test_new_attempt_never_reuses_unsubmitted_old_attempt_clip(tmp_path, tools_and_sources, monkeypatch):
    from livecut.worker_export import export_clip as actual_export

    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[True])
    executor = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)
    executor(context)
    new_lease = dataclasses.replace(context.lease, attempt=2, run_token="new-attempt-token")
    new_directory = context.storage_root / new_lease.attempt_key
    calls = []

    def record(*args, **kwargs):
        calls.append(1)
        return actual_export(*args, **kwargs)

    monkeypatch.setattr("livecut.worker_export.export_clip", record)
    second = executor(dataclasses.replace(context, lease=new_lease, directory=new_directory))
    assert calls == [1]
    assert second["attempt"] == 2
    assert second["artifacts"][0]["key"].startswith(new_lease.attempt_key + "/")
    assert _native_path(context.directory / "clip.mp4").is_file()


def test_complete_response_loss_replays_only_callback(tmp_path, tools_and_sources):
    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[True])
    client = FakeClient()
    client.reply = {"outcome": "CLAIMED", **dataclasses.asdict(context.lease)}
    client.fail_complete_once = True
    actual, calls = AnalysisStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe), []

    def execute(ctx):
        calls.append(ctx.lease.stage_id)
        return actual(ctx)

    worker = StageWorker(client, context.storage_root, tmp_path / "journal", "worker-1", execute)
    with pytest.raises(WorkerClientError):
        worker.run_once(stage_type="EXPORT_CLIP")
    assert worker.run_once(stage_type="EXPORT_CLIP") == "callback_replayed"
    assert calls == [context.lease.stage_id]


def test_lost_lease_after_encoding_never_submits_completion(tmp_path, tools_and_sources, monkeypatch):
    from livecut.worker_export import export_clip as actual_export

    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[True])
    client = FakeClient()
    client.reply = {"outcome": "CLAIMED", **dataclasses.asdict(context.lease)}

    def execute(ctx):
        def export_then_lose(*args, **kwargs):
            info = actual_export(*args, **kwargs)
            ctx.guard.lose()
            return info
        monkeypatch.setattr("livecut.worker_export.export_clip", export_then_lose)
        return AnalysisStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)(ctx)

    worker = StageWorker(client, context.storage_root, tmp_path / "journal", "worker-1", execute)
    assert worker.run_once(stage_type="EXPORT_CLIP") == "failed"
    assert not any(action == "complete" for action, _, _ in client.callbacks)
    assert client.callbacks[-1][2]["error_code"] == "LEASE_LOST"


def test_real_export_supports_deep_windows_attempt_path(tmp_path, tools_and_sources):
    ffmpeg, ffprobe, sources = tools_and_sources
    context = prepare_context(tmp_path, sources[False], deep=True)
    manifest = ExportStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)(context)
    assert len(str(context.directory)) > 300
    assert sha256(context.directory / "clip.mp4") == manifest["artifacts"][0]["sha256"]


def test_export_stage_cannot_reserve_or_issue_paid_model_requests(tmp_path):
    class RejectBudget:
        def reserve_call(self, *args):
            pytest.fail("EXPORT must not reserve model budget")

    lease = Lease.parse(export_claim())
    context = StageContext(lease, tmp_path, tmp_path, LeaseGuard(lease, threading.Event()))
    with httpx.Client(transport=BudgetTransport(RejectBudget(), context, inner=httpx.MockTransport(
        lambda request: pytest.fail("EXPORT must not call a provider")
    ))) as client:
        for path in ("/services/audio/asr/transcription", "/services/aigc/text-generation/generation",
                     "/compatible-mode/v1/chat/completions", "/unrecognized/provider-endpoint"):
            with pytest.raises(ModelBudgetError, match="MODEL_OPERATION_NOT_ALLOWED"):
                client.post("https://provider.example" + path, json={"model": "irrelevant"})


def test_invalid_frozen_export_reports_failure_without_sticking_claim_or_computing(tmp_path):
    client = FakeClient()
    client.reply = export_claim(config={"export": export_config(start_ms=True)})
    worker = StageWorker(client, tmp_path / "storage", tmp_path / "journal", "worker-1",
                         lambda context: pytest.fail("invalid export must not compute"))
    assert worker.run_once(stage_type="EXPORT_CLIP") == "failed"
    assert client.callbacks[-1][0] == "fail"
    assert client.callbacks[-1][2]["error_code"] == "INVALID_EXPORT_INPUT"
    assert not any(action == "complete" for action, _, _ in client.callbacks)
    assert not (tmp_path / "journal/pending-claim.json").exists()
