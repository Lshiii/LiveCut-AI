"""Worker cancellation uses real owned child processes and no external provider."""

import json
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest
from livecut.media import MediaInfo
from livecut.worker import AnalysisStageExecutor, Lease, StageWorker, WorkerError, sha256
from test_composed_export import config as composed_config
from test_worker import FakeClient, claim
from test_worker_execution import context
from test_worker_export import export_claim


def probe_json(duration_ms):
    return json.dumps({
        "format": {"format_name": "mov,mp4", "duration": str(duration_ms / 1000), "start_time": "0"},
        "streams": [
            {"codec_type": "video", "codec_name": "h264", "width": 64, "height": 64,
             "avg_frame_rate": "25/1", "start_time": "0"},
            {"codec_type": "audio", "codec_name": "aac", "sample_rate": "16000",
             "channels": 1, "start_time": "0"},
        ],
    })


@pytest.mark.parametrize("stage,target,reason", [
    ("MEDIA", "source_probe", "WORKER_STOPPED"),
    ("MEDIA", "audio_probe", "LEASE_LOST"),
    ("MEDIA", "audio", "WORKER_STOPPED"),
    ("MEDIA", "audio", "WORKER_PRESENCE_LOST"),
    ("EXPORT_CLIP", "source_probe", "HARD_DEADLINE_EXCEEDED"),
    ("EXPORT_CLIP", "encode", "LEASE_LOST"),
    ("EXPORT_CLIP", "output_probe", "WORKER_STOPPED"),
    ("EXPORT_CLIP", "verify_probe", "LEASE_LOST"),
    ("EXPORT_CLIP", "decode", "HARD_DEADLINE_EXCEEDED"),
    ("COMPOSED", "encode", "WORKER_STOPPED"),
])
def test_worker_cancels_media_child_without_publishing_success(tmp_path, monkeypatch, stage, target, reason):
    # Model the FFmpeg protocol with bounded local Python children. Only the
    # executable payload is replaced: production adapters, Popen polling,
    # atomic media publication and the Worker callback boundary remain real.
    source = tmp_path / "videos/video.mp4"
    source.parent.mkdir()
    source.write_bytes(b"explicit synthetic source; not real media")
    values = {"source_hash": sha256(source)}
    if stage == "COMPOSED":
        value = export_claim(config={"export": composed_config()}, **values)
        export_duration_ms = 2000
    elif stage == "EXPORT_CLIP":
        value = export_claim(**values)
        export_duration_ms = 1500
    else:
        value = claim(**values)
        export_duration_ms = 4000
    lease = Lease.parse(value)
    directory = tmp_path / lease.attempt_key
    started = tmp_path / "child-started"
    children, observed, cancelled = [], [], threading.Event()
    actual_popen = subprocess.Popen
    source_probes = 0

    def launch(command, *args, **kwargs):
        nonlocal source_probes
        assert command[0] in {"synthetic-ffprobe", "synthetic-ffmpeg"}
        output = None
        if command[0] == "synthetic-ffprobe":
            path = Path(command[-1])
            is_source = path.name == source.name
            if is_source:
                source_probes += 1
                label = "source_probe" if source_probes == 1 else "audio_probe"
            else:
                label = "verify_probe" if path.name == "clip.mp4" else "output_probe"
            payload = "print(" + repr(probe_json(4000 if is_source else export_duration_ms)) + ")"
        elif command[-1] == "-":
            label, payload = "decode", "pass"
        else:
            output = Path(command[-1])
            label = "audio" if output.suffix == ".wav" else "encode"
            payload = "from pathlib import Path; Path(" + repr(str(output)) + ").write_bytes(b'partial child output')"
        observed.append(label)
        if label == target:
            payload += "; from pathlib import Path; import time; Path(" + repr(str(started)) + ").touch(); time.sleep(6)"
        executable = getattr(sys, "_base_executable", sys.executable)
        process = actual_popen([executable, "-c", payload], *args, **kwargs)
        children.append(process)
        return process

    monkeypatch.setattr("livecut.media.subprocess.Popen", launch)

    def execute(context):
        def interrupt():
            deadline = time.monotonic() + 5
            while not started.is_file() and time.monotonic() < deadline:
                time.sleep(.01)
            if not started.is_file():
                return
            if reason == "WORKER_STOPPED":
                context.guard._stop.set()
            elif reason == "LEASE_LOST":
                context.guard.lose()
            elif reason == "WORKER_PRESENCE_LOST":
                def lost_presence():
                    raise WorkerError("WORKER_PRESENCE_LOST")
                context.guard._presence_check = lost_presence
            else:
                with context.guard._lock:
                    context.guard._deadline = 0
            cancelled.set()

        interrupter = threading.Thread(target=interrupt, daemon=True)
        interrupter.start()
        try:
            return AnalysisStageExecutor(ffmpeg_path="synthetic-ffmpeg", ffprobe_path="synthetic-ffprobe")(context)
        finally:
            interrupter.join(timeout=6)

    client = FakeClient()
    client.reply = value
    worker = StageWorker(client, tmp_path, tmp_path / "journal", "worker-1", execute,
                         registry_enabled=False)
    began = time.monotonic()
    try:
        assert worker.run_once(stage_type=lease.stage_type) == "failed"
        elapsed = time.monotonic() - began
        assert cancelled.is_set() and target in observed
        assert elapsed < 3, f"guard did not interrupt the six-second child: {elapsed:.2f}s"
        assert children and all(child.returncode is not None for child in children)
        assert not any(action == "complete" for action, _, _ in client.callbacks)
        assert client.callbacks[-1][2]["error_code"] == reason
        assert not (directory / "manifest.json").exists()
        assert not (directory / "media-info.json").exists()
        assert not (directory / "export-checkpoint.json").exists()
        assert not list(directory.glob(".audio.*.wav"))
        assert not list(directory.glob(".clip.*.mp4"))
        assert not (directory / "audio.wav").exists()
        if target not in {"verify_probe", "decode"}:
            assert not (directory / "clip.mp4").exists()
        assert source.read_bytes() == b"explicit synthetic source; not real media"
    finally:
        # Even a regression must clean up precisely the children this test owns.
        for child in children:
            if child.poll() is None:
                child.kill()
            child.communicate(timeout=3)


def test_media_executor_rejects_cancellation_after_adapter_returns(tmp_path, monkeypatch):
    ctx = context(tmp_path)
    info = MediaInfo(tmp_path / ctx.lease.source_key, 4000, 4, "mp4", "h264", "aac", 64, 64,
                     25, 0, 0, 0, 0, 16000, 1)
    monkeypatch.setattr("livecut.worker.probe_media", lambda path, **kwargs: info)

    def extract(source, output, *, guard, **kwargs):
        assert guard == ctx.guard.check
        output.write_bytes(b"adapter completed immediately before cancellation")
        ctx.guard.lose()

    monkeypatch.setattr("livecut.worker.extract_audio", extract)
    with pytest.raises(WorkerError, match="LEASE_LOST"):
        AnalysisStageExecutor()(ctx)
    assert not (ctx.directory / "media-info.json").exists()
