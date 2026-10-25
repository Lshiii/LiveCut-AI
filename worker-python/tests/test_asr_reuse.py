"""Synthetic hashed source artifacts prove cache fencing, never model quality."""

import hashlib
import json
import os
import shutil
import subprocess
import threading
from uuid import uuid4

import pytest
from livecut.asr import map_transcript
from livecut.worker import (
    AnalysisStageExecutor,
    Lease,
    LeaseGuard,
    StageContext,
    WorkerError,
    _write,
    sha256,
)
from test_worker import claim


def fixture(tmp_path, monkeypatch, *, empty=False):
    monkeypatch.setenv("LIVECUT_ASR_BASE_URL", "https://provider.invalid/api/v1")
    video = tmp_path / "videos/video.mp4"
    video.parent.mkdir()
    video.write_bytes(b"explicit synthetic source video")
    lease = Lease.parse(claim(job_id=str(uuid4()), stage_id=str(uuid4()), stage_type="ASR",
                              source_hash=sha256(video), config={"asr_model": "paraformer-v2"}))
    directory = tmp_path / lease.attempt_key
    directory.mkdir(parents=True)
    ctx = StageContext(lease, tmp_path, directory, LeaseGuard(lease, threading.Event()))
    source = {"id": str(uuid4()), "video_id": lease.video_id, "generation": 1,
              "status": "FAILED", "config": {"asr_model": "paraformer-v2"}, "stages": []}

    def publish(job_id, stage_type, files):
        stage = {"id": str(uuid4()), "stage_type": stage_type, "status": "SUCCEEDED", "attempt": 1}
        prefix = f"jobs/{job_id}/stages/{stage['id']}/attempts/1/token-source"
        path = tmp_path / prefix
        path.mkdir(parents=True)
        artifacts = []
        for kind, (relative, data) in files.items():
            file = path / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)
            artifacts.append({"kind": kind, "key": prefix + "/" + relative, "sha256": sha256(file)})
        manifest = {"schema_version": 1, "job_id": job_id, "video_id": lease.video_id,
                    "analysis_generation": 1, "stage_id": stage["id"], "stage_type": stage_type,
                    "attempt": 1, "run_token": "token-source", "input_sha256": lease.source_hash,
                    "data_kind": "model_result", "review_status": "DRAFT", "artifacts": artifacts}
        _write(path / "manifest.json", manifest)
        stage["manifest_hash"] = sha256(path / "manifest.json")
        return stage, path

    media_files = {"audio": ("audio.wav", b"explicit synthetic audio bytes"),
                   "media_info": ("media-info.json", b'{"duration_ms":1000}')}
    media, media_dir = publish(source["id"], "MEDIA", media_files)
    current_media, current_media_dir = publish(lease.job_id, "MEDIA", media_files)
    lease.artifacts.append({"stage_id": current_media["id"], "stage_type": "MEDIA",
                            "manifest_key": (current_media_dir / "manifest.json").relative_to(tmp_path).as_posix(),
                            "manifest_hash": current_media["manifest_hash"]})
    raw = {"transcripts": [] if empty else [{"channel_id": 0, "sentences": [
        {"begin_time": 0, "end_time": 1000, "text": "这是垃圾袋。"}]}]}
    transcript_bytes = (json.dumps(map_transcript(raw), ensure_ascii=False, indent=3) + "\n").encode()
    asr, asr_dir = publish(source["id"], "ASR", {"transcript": ("asr/transcript.json", transcript_bytes)})
    _write(asr_dir / "asr/raw-result.json", raw)
    _write(asr_dir / "asr/checkpoint.json", {
        "version": 1, "state": "SUCCEEDED", "provider_task_id": "synthetic-task",
        "file_url": "https://private.invalid/signed?secret=not-public",
        "input": {"audio_sha256": sha256(media_dir / "audio.wav"), "model": "paraformer-v2",
                  "base_url": "https://provider.invalid/api/v1", "source_chunk_id": "c_001",
                  "chunk_start_ms": 0, "adapter_version": 1}})
    source["stages"] = [media, asr]
    return ctx, source, media_dir, asr_dir, current_media_dir, transcript_bytes


def never_provider(*args, **kwargs):
    pytest.fail("reuse must not create Provider transport or call transcribe_file")


def execute(ctx, source, monkeypatch):
    monkeypatch.setattr("livecut.worker.transcribe_file", never_provider)
    return AnalysisStageExecutor(transport_factory=never_provider, asr_reuse_source=source)(ctx)


@pytest.mark.parametrize("empty", [False, True])
def test_verified_reuse_copies_original_bytes_without_source_writes_or_provider(tmp_path, monkeypatch, empty):
    ctx, source, media_dir, asr_dir, _, expected = fixture(tmp_path, monkeypatch, empty=empty)
    original = {file: sha256(file) for directory in (media_dir, asr_dir)
                for file in directory.rglob("*") if file.is_file()}
    result = execute(ctx, source, monkeypatch)
    assert result["job_id"] == ctx.lease.job_id and result["run_token"] == ctx.lease.run_token
    assert {item["kind"] for item in result["artifacts"]} == {"transcript", "cache_provenance"}
    assert (ctx.directory / "asr/transcript.json").read_bytes() == expected
    assert {path: sha256(path) for path in original} == original
    provenance_bytes = (ctx.directory / "asr/cache-provenance.json").read_bytes()
    provenance = json.loads(provenance_bytes)
    assert provenance["provider_submissions"] == 0
    assert provenance["source_asr_manifest_sha256"] == source["stages"][1]["manifest_hash"]
    assert provenance["source_transcript_sha256"] == hashlib.sha256(expected).hexdigest()
    for private in (b"private.invalid", b"provider.invalid", b"secret", b"token-source", str(tmp_path).encode()):
        assert private not in provenance_bytes


def change_json(path, mutate):
    value = json.loads(path.read_bytes())
    mutate(value)
    _write(path, value)


@pytest.mark.parametrize("mutation,code", [
    ("model", "MODEL_MISMATCH"), ("video", "INPUT_MISMATCH"),
    ("media_pending", "SOURCE_NOT_SUCCEEDED"), ("asr_pending", "SOURCE_NOT_SUCCEEDED"),
    ("attempt_bool", "SOURCE_NOT_SUCCEEDED"), ("duplicate_stage", "SOURCE_NOT_SUCCEEDED"),
    ("manifest_hash", "MANIFEST_MISMATCH"), ("manifest_identity", "MANIFEST_MISMATCH"),
    ("manifest_bool", "MANIFEST_MISMATCH"), ("transcript", "ARTIFACT_MISMATCH"),
    ("raw", "TRANSCRIPT_MISMATCH"), ("checkpoint_state", "CHECKPOINT_MISMATCH"),
    ("checkpoint_model", "CHECKPOINT_MISMATCH"), ("checkpoint_endpoint", "CHECKPOINT_MISMATCH"),
    ("checkpoint_audio", "CHECKPOINT_MISMATCH"), ("checkpoint_chunk", "CHECKPOINT_MISMATCH"),
    ("checkpoint_offset", "CHECKPOINT_MISMATCH"), ("checkpoint_adapter", "CHECKPOINT_MISMATCH"),
    ("checkpoint_version", "CHECKPOINT_MISMATCH"), ("current_audio", "AUDIO_MISMATCH"),
    ("audio", "ARTIFACT_MISMATCH"), ("extra_artifact", "ARTIFACT_MISMATCH"),
    ("duplicate_artifact", "MANIFEST_MISMATCH"), ("outside_path", "MANIFEST_MISMATCH"),
])
def test_invalid_source_fails_before_provider_without_output(tmp_path, monkeypatch, mutation, code):
    ctx, source, media_dir, asr_dir, current_media, _ = fixture(tmp_path, monkeypatch)
    asr_stage = source["stages"][1]
    manifest = asr_dir / "manifest.json"
    checkpoint = asr_dir / "asr/checkpoint.json"
    if mutation == "model":
        source["config"]["asr_model"] = "other-model"
    elif mutation == "video":
        source["video_id"] = "other-video"
    elif mutation in {"media_pending", "asr_pending"}:
        source["stages"][mutation == "asr_pending"]["status"] = "RUNNING"
    elif mutation == "attempt_bool":
        asr_stage["attempt"] = True
    elif mutation == "duplicate_stage":
        source["stages"].append(dict(asr_stage))
    elif mutation == "manifest_hash":
        asr_stage["manifest_hash"] = "b" * 64
    elif mutation in {"manifest_identity", "manifest_bool", "duplicate_artifact", "outside_path", "extra_artifact"}:
        def alter(value):
            if mutation == "manifest_identity":
                value["job_id"] = str(uuid4())
            elif mutation == "manifest_bool":
                value["schema_version"] = True
            elif mutation == "duplicate_artifact":
                value["artifacts"].append(dict(value["artifacts"][0]))
            elif mutation == "outside_path":
                value["artifacts"][0]["key"] = "videos/video.mp4"
            else:
                extra = asr_dir / "extra.txt"
                extra.write_bytes(b"tampered extra artifact")
                value["artifacts"].append({"kind": "extra", "key": extra.relative_to(tmp_path).as_posix(),
                                           "sha256": "b" * 64})
        change_json(manifest, alter)
        asr_stage["manifest_hash"] = sha256(manifest)
    elif mutation in {"transcript", "audio"}:
        path = asr_dir / "asr/transcript.json" if mutation == "transcript" else media_dir / "audio.wav"
        path.write_bytes(b"tampered")
    elif mutation == "raw":
        change_json(asr_dir / "asr/raw-result.json", lambda value: value["transcripts"][0]["sentences"][0].update(text="面膜"))
    elif mutation.startswith("checkpoint_"):
        def alter(value):
            field = mutation.removeprefix("checkpoint_")
            if field in {"state", "version"}:
                value[field] = "POLLING" if field == "state" else True
            else:
                key, wrong = {"model": ("model", "other"), "endpoint": ("base_url", "https://elsewhere.invalid"),
                              "audio": ("audio_sha256", "b" * 64), "chunk": ("source_chunk_id", "c_002"),
                              "offset": ("chunk_start_ms", False), "adapter": ("adapter_version", True)}[field]
                value["input"][key] = wrong
        change_json(checkpoint, alter)
    elif mutation == "current_audio":
        (current_media / "audio.wav").write_bytes(b"different but published current audio")
        current_manifest = current_media / "manifest.json"
        change_json(current_manifest, lambda value: value["artifacts"][0].update(sha256=sha256(current_media / "audio.wav")))
        ctx.lease.artifacts[0]["manifest_hash"] = sha256(current_manifest)
    with pytest.raises(WorkerError, match="ASR_REUSE_" + code):
        execute(ctx, source, monkeypatch)
    assert not (ctx.directory / "asr/transcript.json").exists()


def link_directory(link, target):
    try:
        link.symlink_to(target, target_is_directory=True)
    except OSError:
        if os.name != "nt":
            pytest.skip("directory links unavailable")
        process = subprocess.run([
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "New-Item -ItemType Junction -Path $env:LIVECUT_TEST_LINK -Target $env:LIVECUT_TEST_TARGET -ErrorAction Stop | Out-Null"],
            env={**os.environ, "LIVECUT_TEST_LINK": str(link), "LIVECUT_TEST_TARGET": str(target)},
            capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
        if process.returncode:
            pytest.skip("directory links unavailable")


@pytest.mark.parametrize("kind", ["artifact", "checkpoint", "output"])
def test_links_cannot_read_another_attempt_or_write_source(tmp_path, monkeypatch, kind):
    ctx, source, _, asr_dir, _, _ = fixture(tmp_path, monkeypatch)
    other = tmp_path / "outside-attempt"
    if kind == "output":
        link_directory(ctx.directory / "asr", asr_dir / "asr")
    elif kind == "checkpoint":
        shutil.copytree(asr_dir / "asr", other)
        # Rename retains all source fixture contents for before/after inspection.
        (asr_dir / "asr").rename(asr_dir / "original-asr")
        link_directory(asr_dir / "asr", other)
    else:
        other.mkdir()
        shutil.copyfile(asr_dir / "asr/transcript.json", other / "transcript.json")
        link_directory(asr_dir / "linked", other)
        change_json(asr_dir / "manifest.json", lambda value: value["artifacts"][0].update(
            key=(asr_dir / "linked/transcript.json").relative_to(tmp_path).as_posix()))
        source["stages"][1]["manifest_hash"] = sha256(asr_dir / "manifest.json")
    with pytest.raises(WorkerError, match="ASR_REUSE_UNSAFE_PATH"):
        execute(ctx, source, monkeypatch)


def test_duplicate_json_keys_rejected_without_provider(tmp_path, monkeypatch):
    ctx, source, _, asr_dir, _, _ = fixture(tmp_path, monkeypatch)
    (asr_dir / "asr/raw-result.json").write_bytes(b'{"transcripts":[],"transcripts":[]}')
    with pytest.raises(WorkerError, match="ASR_REUSE_INVALID_JSON"):
        execute(ctx, source, monkeypatch)


def test_worker_endpoint_override_takes_priority_over_dashscope_fallbacks(tmp_path, monkeypatch):
    ctx, source, _, _, _, _ = fixture(tmp_path, monkeypatch)
    monkeypatch.setenv("DASHSCOPE_HTTP_BASE_URL", "https://fallback-one.invalid/api/v1")
    monkeypatch.setenv("DASHSCOPE_BASE_URL", "https://fallback-two.invalid/api/v1")
    result = execute(ctx, source, monkeypatch)
    assert result["artifacts"][0]["kind"] == "transcript"


def test_same_manifest_digest_in_two_attempt_directories_is_ambiguous(tmp_path, monkeypatch):
    ctx, source, _, asr_dir, _, _ = fixture(tmp_path, monkeypatch)
    shutil.copytree(asr_dir, asr_dir.with_name("second-token"))
    with pytest.raises(WorkerError, match="ASR_REUSE_MANIFEST_MISMATCH"):
        execute(ctx, source, monkeypatch)
