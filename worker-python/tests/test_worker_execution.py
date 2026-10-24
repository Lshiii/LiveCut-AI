"""Artifact validation and stage adapters, with explicit local fixtures."""

import json
import os
import shutil
import subprocess
import threading
import wave
from dataclasses import dataclass

import pytest
from livecut.worker import (
    AnalysisStageExecutor,
    Lease,
    LeaseGuard,
    StageContext,
    WorkerError,
    _native_path,
    _write,
    sha256,
)
from test_worker import claim


def context(tmp_path, stage_type="MEDIA", artifacts=None):
    source = tmp_path / "videos/video.mp4"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_bytes(b"synthetic media fixture")
    lease = Lease.parse(claim(stage_type=stage_type, source_hash=sha256(source),
                              artifacts=artifacts or []))
    directory = tmp_path / lease.attempt_key
    directory.mkdir(parents=True, exist_ok=True)
    return StageContext(lease, tmp_path, directory, LeaseGuard(lease, threading.Event()))


def predecessor(ctx, stage_type, kind, content):
    stage_id = "previous-" + stage_type
    prefix = f"jobs/{ctx.lease.job_id}/stages/{stage_id}/attempts/1/token-prev"
    directory = ctx.storage_root / prefix
    directory.mkdir(parents=True, exist_ok=True)
    file = directory / "artifact.json"
    file.write_text(json.dumps(content), encoding="utf-8")
    manifest = {"job_id": ctx.lease.job_id, "stage_id": stage_id,
                "video_id": ctx.lease.video_id, "analysis_generation": ctx.lease.generation,
                "stage_type": stage_type, "input_sha256": ctx.lease.source_hash,
                "artifacts": [{"kind": kind, "key": prefix + "/artifact.json", "sha256": sha256(file)}]}
    path = directory / "manifest.json"
    _write(path, manifest)
    ctx.lease.artifacts.append({"stage_id": stage_id, "stage_type": stage_type,
                                "manifest_key": prefix + "/manifest.json", "manifest_hash": sha256(path)})
    return path, file


def test_predecessor_rejects_changed_content(tmp_path):
    ctx = context(tmp_path, "ASR")
    _, file = predecessor(ctx, "MEDIA", "audio", {"fixture": True})
    file.write_text("changed", encoding="utf-8")
    with pytest.raises(WorkerError, match="ARTIFACT_HASH_MISMATCH"):
        ctx.predecessor("MEDIA", "audio")


def test_predecessor_rejects_manifest_for_other_job(tmp_path):
    ctx = context(tmp_path, "ASR")
    path, _ = predecessor(ctx, "MEDIA", "audio", {"fixture": True})
    data = json.loads(path.read_text(encoding="utf-8"))
    data["job_id"] = "another-job"
    _write(path, data)
    ctx.lease.artifacts[0]["manifest_hash"] = sha256(path)
    with pytest.raises(WorkerError, match="ARTIFACT_INPUT_MISMATCH"):
        ctx.predecessor("MEDIA", "audio")


def test_predecessor_rejects_symlink_into_other_attempt_even_inside_storage_root(tmp_path):
    ctx = context(tmp_path, "ASR")
    manifest_path, file = predecessor(ctx, "MEDIA", "audio", {"fixture": True})
    other_attempt = manifest_path.parent.parent.parent / "2" / "token-other"
    other_attempt.mkdir(parents=True)
    (other_attempt / "artifact.json").write_bytes(file.read_bytes())
    link = manifest_path.parent / "linked"
    try:
        link.symlink_to(other_attempt, target_is_directory=True)
    except OSError:
        if os.name != "nt":
            pytest.skip("directory symlinks unavailable on this filesystem")
        # Junctions work on Windows without the symlink creation privilege.
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
             "New-Item -ItemType Junction -Path $env:LIVECUT_TEST_LINK "
             "-Target $env:LIVECUT_TEST_TARGET -ErrorAction Stop | Out-Null"],
            env={**os.environ, "LIVECUT_TEST_LINK": str(link),
                 "LIVECUT_TEST_TARGET": str(other_attempt)},
            capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW,
        )
        if result.returncode:
            pytest.skip("neither directory symlinks nor Windows junctions are available")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    prefix = manifest_path.parent.relative_to(ctx.storage_root).as_posix()
    data["artifacts"][0]["key"] = prefix + "/linked/artifact.json"
    _write(manifest_path, data)
    ctx.lease.artifacts[0]["manifest_hash"] = sha256(manifest_path)
    # Both the textual prefix and content hash are valid; only realpath
    # confinement to the producing attempt distinguishes this unsafe reference.
    with pytest.raises(WorkerError, match="UNSAFE_STORAGE_KEY"):
        ctx.predecessor("MEDIA", "audio")


def test_media_executor_emits_relative_hashed_artifacts(tmp_path, monkeypatch):
    @dataclass
    class Info:
        path: object
        duration_ms: int = 4000

    ctx = context(tmp_path)
    monkeypatch.setattr("livecut.worker.probe_media", lambda path, **kwargs: Info(path))

    def extract(source, output, **kwargs):
        output.write_bytes(b"fixture-wave")
        return output

    monkeypatch.setattr("livecut.worker.extract_audio", extract)
    manifest = AnalysisStageExecutor()(ctx)
    assert manifest["run_token"] == ctx.lease.run_token
    assert manifest["input_sha256"] == ctx.lease.source_hash
    assert {item["kind"] for item in manifest["artifacts"]} == {"media_info", "audio"}
    for item in manifest["artifacts"]:
        assert not item["key"].startswith("/")
        assert item["sha256"] == sha256(tmp_path / item["key"])


def test_provider_stage_cannot_bypass_durable_budget(tmp_path):
    ctx = context(tmp_path, "ASR")
    predecessor(ctx, "MEDIA", "audio", {"fixture": True})
    with pytest.raises(WorkerError, match="DURABLE_BUDGET_REQUIRED"):
        AnalysisStageExecutor()(ctx)


def test_source_hash_mismatch_fails_before_stage(tmp_path):
    ctx = context(tmp_path)
    (tmp_path / ctx.lease.source_key).write_bytes(b"modified")
    with pytest.raises(WorkerError, match="SOURCE_HASH_MISMATCH"):
        AnalysisStageExecutor()(ctx)


@pytest.mark.parametrize("field,value", [("video_id", "video-other"), ("analysis_generation", 2)])
def test_timeline_rejects_result_for_wrong_video_or_generation(tmp_path, field, value):
    ctx = context(tmp_path, "TIMELINE")
    # Simulated adapter output, never evidence of a real model execution.
    result = {"schema_version": "2.0", "data_kind": "model_result", "job_id": ctx.lease.job_id,
              "video_id": ctx.lease.video_id, "analysis_generation": ctx.lease.generation,
              "duration_ms": 1000, "transcript_version": "mock", "pipeline_version": "mock",
              "product_discovery_version": "mock", "review_status": "DRAFT",
              "provenance": {"asr_model_id": "mock", "text_model_id": "mock",
                             "vision_model_id": None, "prompt_version": "mock", "config_hash": "mock"},
              "products": [], "utterances": [], "frames": [], "segments": []}
    result[field] = value
    predecessor(ctx, "SEGMENT", "analysis_result", result)
    with pytest.raises(WorkerError, match="ARTIFACT_INPUT_MISMATCH"):
        AnalysisStageExecutor()(ctx)


def test_real_ffmpeg_worker_media_supports_deep_attempt_directory(tmp_path):
    ffmpeg = os.environ.get("LIVECUT_FFMPEG_PATH") or shutil.which("ffmpeg")
    ffprobe = os.environ.get("LIVECUT_FFPROBE_PATH") or shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        pytest.skip("real FFmpeg and ffprobe are required for the deep-path integration check")
    root = tmp_path
    while len(str(root)) < 270:
        root = root / ("deep-fixture-" + "x" * 25)
    source = root / "videos/synthetic-1s.mp4"
    _native_path(source.parent).mkdir(parents=True, exist_ok=True)
    generated = subprocess.run([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
        "color=c=red:s=64x64:d=1:r=10", "-f", "lavfi", "-i",
        "sine=frequency=440:sample_rate=16000:duration=1", "-c:v", "libx264",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(_native_path(source)),
    ], capture_output=True, timeout=30)
    assert generated.returncode == 0
    lease = Lease.parse(claim(source_key="videos/synthetic-1s.mp4", source_hash=sha256(source)))
    directory = root / lease.attempt_key
    _native_path(directory).mkdir(parents=True, exist_ok=True)
    ctx = StageContext(lease, root, directory, LeaseGuard(lease, threading.Event()))
    manifest = AnalysisStageExecutor(ffmpeg_path=ffmpeg, ffprobe_path=ffprobe)(ctx)
    assert len(str(directory)) > 300
    for artifact in manifest["artifacts"]:
        assert artifact["sha256"] == sha256(root / artifact["key"])
    with wave.open(str(_native_path(directory / "audio.wav")), "rb") as audio:
        assert audio.getframerate() == 16000 and audio.getnchannels() == 1
        assert audio.getnframes() == 16000
