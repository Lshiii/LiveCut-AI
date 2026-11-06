"""Public adapter cancellation checks using real, synthetic local child processes.

The dispatched children simulate tool output only. These tests neither decode
real media nor call a model or service.
"""

import hashlib
import json
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

import pytest
from livecut import media
from livecut.ocr import observe_video_ocr
from livecut.worker import AnalysisStageExecutor, StageWorker, WorkerError
from test_worker import FakeClient, claim


def _metadata():
    return {
        "format": {
            "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
            "duration": "4.000000",
            "start_time": "0.000000",
        },
        "streams": [
            {"codec_type": "video", "codec_name": "h264", "width": 160,
             "height": 120, "avg_frame_rate": "25/1", "start_time": "0.000000"},
            {"codec_type": "audio", "codec_name": "aac", "sample_rate": "48000",
             "channels": 1, "start_time": "0.000000"},
        ],
    }


@pytest.fixture
def child_tools(monkeypatch):
    """Replace executable dispatch while retaining real process ownership/I/O."""
    original_popen = subprocess.Popen
    children = []

    def install(script_for_command):
        def launch(command, **kwargs):
            # uv's Windows venv executable is a launcher which creates another
            # interpreter process. Use the actual executable to match direct
            # ffmpeg/ffprobe ownership rather than leaving a wrapper descendant.
            interpreter = getattr(sys, "_base_executable", sys.executable)
            child = original_popen(
                [interpreter, "-u", "-c", script_for_command(command)], **kwargs
            )
            children.append(child)
            return child

        monkeypatch.setattr(subprocess, "Popen", launch)
        return children

    yield install
    # If an assertion fails, clean up only processes created by this fixture.
    for child in children:
        if child.poll() is None:
            child.kill()
        child.communicate(timeout=5)


def _invoke(adapter, source, target, guard):
    options = {"ffprobe_path": "synthetic-ffprobe", "timeout_seconds": 10, "guard": guard}
    if adapter == "probe":
        return media.probe_media(source, **options)
    options["ffmpeg_path"] = "synthetic-ffmpeg"
    if adapter == "audio":
        return media.extract_audio(source, target, **options)
    if adapter == "clip":
        return media.export_clip(source, target, 1000, 2000, **options)
    return media.export_composed_clip(
        source, target, [{"start_ms": 0, "end_ms": 1000},
                         {"start_ms": 2000, "end_ms": 3000}], **options
    )


@pytest.mark.parametrize("adapter", ["probe", "audio", "clip", "composed"])
@pytest.mark.parametrize("reason", ["WORKER_STOPPED", "LEASE_LOST", "HARD_DEADLINE_EXCEEDED"])
def test_public_adapter_cancellation_reaps_child_and_keeps_previous_output(
    tmp_path, child_tools, adapter, reason
):
    source = tmp_path / "synthetic source.mp4"
    source.write_bytes(b"explicitly synthetic source placeholder")
    target = tmp_path / ("output.wav" if adapter == "audio" else "output.mp4")
    target.write_bytes(b"previous verified output")
    marker = tmp_path / "owned-child-started"
    sentinel = WorkerError(reason)

    def script_for_command(command):
        if adapter != "probe" and command[0] == "synthetic-ffprobe":
            return "print(" + repr(json.dumps(_metadata())) + ")"
        partial = "" if adapter == "probe" else (
            "Path(" + repr(str(command[-1])) + ").write_bytes(b'unfinished artifact')\n"
        )
        return (
            "import os, time\nfrom pathlib import Path\n" + partial
            + "Path(" + repr(str(marker)) + ").write_text(str(os.getpid()))\n"
            + "time.sleep(30)\n"
        )

    children = child_tools(script_for_command)

    def guard():
        if marker.exists():
            raise sentinel

    started = time.monotonic()
    with pytest.raises(WorkerError) as raised:
        _invoke(adapter, source, target, guard)
    assert raised.value is sentinel
    assert time.monotonic() - started < 4
    assert marker.exists(), "cancellation must interrupt an actual running child"
    assert int(marker.read_text()) == children[-1].pid
    assert all(child.poll() is not None for child in children)
    assert children[-1].wait(timeout=0.2) is not None
    assert target.read_bytes() == b"previous verified output"
    assert not list(tmp_path.glob(".output.*"))


@pytest.mark.parametrize("adapter", ["probe", "audio", "clip", "composed"])
def test_public_adapter_checks_guard_before_any_process_launch(tmp_path, child_tools, adapter):
    source = tmp_path / "synthetic.mp4"
    source.write_bytes(b"explicitly synthetic placeholder")
    children = child_tools(lambda command: pytest.fail("canceled work must not launch a process"))
    sentinel = WorkerError("LEASE_LOST")

    def guard():
        raise sentinel

    with pytest.raises(WorkerError) as raised:
        _invoke(adapter, source, tmp_path / "output.mp4", guard)
    assert raised.value is sentinel
    assert children == []


def test_audio_canceled_after_validation_never_publishes_verified_temporary(
    tmp_path, monkeypatch, child_tools
):
    source = tmp_path / "synthetic.mp4"
    source.write_bytes(b"explicitly synthetic placeholder")
    target = tmp_path / "output.wav"
    target.write_bytes(b"previous verified output")
    validation_finished = []
    sentinel = WorkerError("LEASE_LOST")
    original_wave_open = media.wave.open

    @contextmanager
    def validation_open(*args, **kwargs):
        with original_wave_open(*args, **kwargs) as audio:
            yield audio
        validation_finished.append(True)

    monkeypatch.setattr(media.wave, "open", validation_open)

    def script_for_command(command):
        if command[0] == "synthetic-ffprobe":
            return "print(" + repr(json.dumps(_metadata())) + ")"
        return (
            "import wave\n"
            + "with wave.open(" + repr(str(command[-1])) + ", 'wb') as audio:\n"
            + " audio.setnchannels(1)\n audio.setframerate(16000)\n"
            + " audio.setsampwidth(2)\n audio.writeframes(b'\\x00\\x00' * 64000)\n"
        )

    children = child_tools(script_for_command)

    def guard():
        if validation_finished:
            raise sentinel

    with pytest.raises(WorkerError) as raised:
        _invoke("audio", source, target, guard)
    assert raised.value is sentinel
    assert validation_finished == [True]
    assert all(child.poll() is not None for child in children)
    assert target.read_bytes() == b"previous verified output"
    assert not list(tmp_path.glob(".output.*"))


def test_default_ocr_extractor_reaps_child_without_publishing_partial_frame(
    tmp_path, child_tools
):
    source = tmp_path / "synthetic.mp4"
    source.write_bytes(b"explicitly synthetic placeholder")
    output = tmp_path / "ocr"
    frames = output / "frames"
    frames.mkdir(parents=True)
    target = frames / "ocr_000001.jpg"
    target.write_bytes(b"previous verified frame")
    marker = tmp_path / "owned-frame-child-started"
    sentinel = WorkerError("LEASE_LOST")

    def script_for_command(command):
        return (
            "import os, time\nfrom pathlib import Path\n"
            + "Path(" + repr(str(command[-1])) + ").write_bytes(b'partial JPEG')\n"
            + "Path(" + repr(str(marker)) + ").write_text(str(os.getpid()))\n"
            + "time.sleep(30)\n"
        )

    children = child_tools(script_for_command)

    def guard():
        if marker.exists():
            raise sentinel

    with pytest.raises(WorkerError) as raised:
        observe_video_ocr(source, output, duration_ms=4000, max_frames=1,
                          ffmpeg_path="synthetic-ffmpeg", engine=lambda image: None, guard=guard)
    assert raised.value is sentinel
    assert len(children) == 1
    assert int(marker.read_text()) == children[0].pid
    assert children[0].poll() is not None
    assert children[0].wait(timeout=0.2) is not None
    assert target.read_bytes() == b"previous verified frame"
    assert list(frames.iterdir()) == [target]
    assert not (output / "ocr-state.json").exists()
    assert not (output / "observations.json").exists()


def test_lease_loss_during_atomic_clip_publication_preserves_file_but_never_completes(
    tmp_path, monkeypatch, child_tools
):
    """A replacement race leaves an isolated file, never a successful result."""
    storage = tmp_path / "storage"
    source = storage / "videos/video.mp4"
    source.parent.mkdir(parents=True)
    source.write_bytes(b"explicitly synthetic source placeholder")
    client = FakeClient()
    client.reply = claim(
        stage_type="EXPORT_CLIP", input_hash="c" * 64,
        source_hash=hashlib.sha256(source.read_bytes()).hexdigest(),
        config={"export": {
            "export_id": "21e5e338-d0b1-4db2-a5fb-c1cd7eeaf94f",
            "clip_id": "4d0fec5d-8899-4210-b045-784503cd3ace",
            "approved_revision": 0, "snapshot_hash": "b" * 64,
            "segment_id": "segment-1", "start_ms": 1000, "end_ms": 2000,
            "encoding_profile": "h264-aac-v1",
        }},
    )
    captured = []
    actual_replace = media.os.replace

    def replace_then_lose(temporary, destination):
        actual_replace(temporary, destination)
        if Path(destination).name == "clip.mp4":
            captured[0].guard.lose()

    monkeypatch.setattr(media.os, "replace", replace_then_lose)

    def script_for_command(command):
        if command[0] == "synthetic-ffprobe":
            metadata = _metadata()
            if Path(command[-1]).name != source.name:
                metadata["format"]["duration"] = "1.000000"
            return "print(" + repr(json.dumps(metadata)) + ")"
        if command[-1] == "-":
            return "pass"  # Synthetic decode output, if verification gets this far.
        return (
            "from pathlib import Path\nPath(" + repr(str(command[-1]))
            + ").write_bytes(b'complete synthetic encoded artifact')"
        )

    children = child_tools(script_for_command)
    executor = AnalysisStageExecutor(ffmpeg_path="synthetic-ffmpeg", ffprobe_path="synthetic-ffprobe")

    def execute(context):
        captured.append(context)
        return executor(context)

    runner = StageWorker(client, storage, tmp_path / "journal", "worker-1", execute,
                         registry_enabled=False)
    assert runner.run_once(stage_type="EXPORT_CLIP") == "failed"
    assert all(child.poll() is not None for child in children)
    directory = captured[0].directory
    assert (directory / "clip.mp4").read_bytes() == b"complete synthetic encoded artifact"
    assert not (directory / "manifest.json").exists()
    assert not any(action == "complete" for action, _, _ in client.callbacks)
    assert client.callbacks[-1][2]["error_code"] == "LEASE_LOST"
