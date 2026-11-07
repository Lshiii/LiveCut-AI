"""Validated local MP4 processing with an explicit, shared media timeline.

The first media profile is H.264 MP4 with at most one AAC audio stream. Probe
retains original timestamps; processing deliberately rejects missing/nonzero
start timestamps until an offset-aware normalization profile is implemented.
All processing writes a sibling temporary file and publishes only after checks.
"""

from __future__ import annotations

import json
import math
import os
import subprocess
import tempfile
import time
import wave
from collections.abc import Callable
from dataclasses import dataclass, replace
from decimal import ROUND_CEILING, Decimal, InvalidOperation
from fractions import Fraction
from pathlib import Path
from typing import Any, BinaryIO


class MediaError(Exception):
    """A media failure with a machine-readable code and readable explanation."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class MediaInfo:
    path: Path
    duration_ms: int
    size_bytes: int
    format_name: str
    video_codec: str
    audio_codec: str | None
    width: int
    height: int
    frame_rate: float | None
    rotation_degrees: int
    format_start_time_seconds: float | None
    video_start_time_seconds: float | None
    audio_start_time_seconds: float | None
    audio_sample_rate: int | None
    audio_channels: int | None

    @property
    def media_time_offset_seconds(self) -> float | None:
        """Container timestamp corresponding to the playable start, unmodified."""
        return self.format_start_time_seconds

    @property
    def audio_video_offset_seconds(self) -> float | None:
        if self.audio_start_time_seconds is None or self.video_start_time_seconds is None:
            return None
        return self.audio_start_time_seconds - self.video_start_time_seconds


def _input_file(path: str | Path) -> Path:
    result = Path(path).expanduser().resolve()
    if not result.is_file():
        raise MediaError("INVALID_MEDIA", f"Source is not a regular file: {result}")
    if result.stat().st_size == 0:
        raise MediaError("INVALID_MEDIA", f"Source is empty: {result}")
    return result


def _run_process(
    command: list[str], *, timeout_seconds: float, failure_code: str,
    guard: Callable[[], None] | None = None,
    cwd: Path | None = None, progress: Callable[[], None] | None = None,
) -> subprocess.CompletedProcess[str]:
    if isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float)):
        raise MediaError("INVALID_ARGUMENT", "timeout_seconds must be a positive finite number")
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise MediaError("INVALID_ARGUMENT", "timeout_seconds must be a positive finite number")
    if guard is not None or progress is not None:
        result = _run_guarded_process(command, timeout_seconds=timeout_seconds,
                                      failure_code=failure_code, guard=guard or (lambda: None),
                                      cwd=cwd, progress=progress)
    else:
        result = _run_unchecked_process(command, timeout_seconds=timeout_seconds,
                                        failure_code=failure_code, cwd=cwd)
    if result.returncode != 0:
        detail = (result.stderr or "No diagnostic output").strip()[-2000:]
        raise MediaError(failure_code, f"Media command failed (exit {result.returncode}): {detail}")
    return result


def _run_unchecked_process(
    command: list[str], *, timeout_seconds: float, failure_code: str,
    cwd: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    # Keep the CLI's synchronous path and its subprocess.run error semantics.
    try:
        return subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            shell=False,
            timeout=timeout_seconds,
            check=False,
            **({"cwd": cwd} if cwd is not None else {}),
        )
    except FileNotFoundError as exc:
        raise MediaError("TOOL_NOT_FOUND", f"Executable not found: {command[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise MediaError("PROCESS_TIMEOUT", f"Media command exceeded {timeout_seconds} seconds") from exc
    except OSError as exc:
        raise MediaError(failure_code, f"Unable to execute media command: {exc}") from exc


def _reap_process(process: subprocess.Popen[str]) -> None:
    """Stop only our child, with a bounded grace period and bounded kill wait."""
    failures = []
    for stop in (process.terminate, process.kill):
        if process.poll() is not None:
            return
        try:
            stop()
        except OSError as exc:
            failures.append(type(exc).__name__)
        try:
            process.wait(timeout=0.2)
            return
        except BaseException as exc:
            failures.append(type(exc).__name__)
    if process.poll() is None:
        raise MediaError(
            "PROCESS_CLEANUP_FAILED",
            f"Direct child {process.pid} was not confirmed reaped after bounded "
            f"terminate/kill waits ({', '.join(failures)})",
        )


def _read_process_capture(capture: BinaryIO) -> str:
    # A wrapper's descendant could keep an inherited handle open and append.
    # Read only the size observed when our child exits, never chase later writes.
    size = os.fstat(capture.fileno()).st_size
    capture.seek(0)
    return capture.read(size).decode("utf-8", errors="replace")


def _run_guarded_process(
    command: list[str], *, timeout_seconds: float, failure_code: str,
    guard: Callable[[], None],
    cwd: Path | None = None, progress: Callable[[], None] | None = None,
) -> subprocess.CompletedProcess[str]:
    # Regular-file capture avoids pipe backpressure and Windows communicate
    # reader threads. Inherited handles cannot make cancellation drain or close
    # wait for a wrapper's descendants; we only stop and reap our direct child.
    deadline = time.monotonic() + timeout_seconds

    def check_remaining() -> float:
        guard()
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise MediaError("PROCESS_TIMEOUT", f"Media command exceeded {timeout_seconds} seconds")
        return remaining

    check_remaining()
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        check_remaining()
        try:
            process = subprocess.Popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=stdout,
                stderr=stderr,
                shell=False,
                **({"cwd": cwd} if cwd is not None else {}),
            )
        except FileNotFoundError as exc:
            raise MediaError("TOOL_NOT_FOUND", f"Executable not found: {command[0]}") from exc
        except OSError as exc:
            raise MediaError(failure_code, f"Unable to execute media command: {exc}") from exc
        try:
            while True:
                remaining = check_remaining()
                if progress is not None:
                    progress()
                try:
                    process.wait(timeout=min(0.2, remaining))
                except subprocess.TimeoutExpired:
                    continue
                check_remaining()
                if progress is not None:
                    progress()
                captured_stdout = _read_process_capture(stdout)
                captured_stderr = _read_process_capture(stderr)
                check_remaining()
                return subprocess.CompletedProcess(
                    command, process.returncode, captured_stdout, captured_stderr,
                )
        except BaseException as exc:
            # Keep the original guard failure, lease loss or KeyboardInterrupt.
            # A failure to confirm cleanup stays visible in its traceback notes.
            try:
                _reap_process(process)
            except BaseException as cleanup_error:
                exc.add_note(f"Media subprocess cleanup incomplete: {cleanup_error}")
            raise


def _number(value: Any) -> float | None:
    if value is None or value == "N/A":
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _positive_int(value: Any, name: str) -> int:
    try:
        result = int(value)
    except (TypeError, ValueError, OverflowError) as exc:
        raise MediaError("INVALID_MEDIA", f"Missing or invalid {name}") from exc
    if result <= 0:
        raise MediaError("INVALID_MEDIA", f"Missing or invalid {name}")
    return result


def _duration_ms(value: Any) -> int:
    try:
        seconds = Decimal(str(value))
        if not seconds.is_finite() or seconds <= 0:
            raise InvalidOperation
        return int((seconds * 1000).to_integral_value(rounding=ROUND_CEILING))
    except (InvalidOperation, TypeError, ValueError, OverflowError) as exc:
        raise MediaError("INVALID_MEDIA", "Missing or invalid media duration") from exc


def _frame_rate(stream: dict[str, Any]) -> float | None:
    for name in ("avg_frame_rate", "r_frame_rate"):
        try:
            rate = float(Fraction(str(stream.get(name, "0/0"))))
            if math.isfinite(rate) and rate > 0:
                return rate
        except (ValueError, ZeroDivisionError, OverflowError):
            pass
    return None


def _rotation(stream: dict[str, Any]) -> int:
    for item in stream.get("side_data_list", []):
        if "rotation" in item:
            value = _number(item["rotation"])
            if value is not None:
                return round(value)
    value = _number(stream.get("tags", {}).get("rotate"))
    return 0 if value is None else round(value)


def probe_media(
    path: str | Path,
    *,
    ffprobe_path: str | Path = "ffprobe",
    timeout_seconds: float = 30,
    guard: Callable[[], None] | None = None,
) -> MediaInfo:
    """Validate the initial MP4 profile and retain original stream timestamps.

    Nonzero/missing timestamps can be inspected here, but extract/export reject
    them explicitly. Duration uses ceiling milliseconds to avoid truncating the
    last fraction of a millisecond of the playable input.
    """
    source = _input_file(path)
    if source.suffix.lower() != ".mp4":
        raise MediaError("UNSUPPORTED_MEDIA", "Only .mp4 inputs are supported by the first media profile")
    result = _run_process(
        [str(ffprobe_path), "-v", "error", "-show_format", "-show_streams", "-of", "json", str(source)],
        timeout_seconds=timeout_seconds,
        failure_code="PROBE_FAILED",
        guard=guard,
    )
    try:
        data = json.loads(result.stdout)
        container = data["format"]
        streams = data["streams"]
        if not isinstance(container, dict) or not isinstance(streams, list):
            raise ValueError
        if any(not isinstance(stream, dict) for stream in streams):
            raise ValueError
    except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        raise MediaError("INVALID_MEDIA", "ffprobe returned malformed media metadata") from exc
    format_name = str(container.get("format_name", ""))
    if "mp4" not in format_name.split(","):
        raise MediaError("UNSUPPORTED_MEDIA", "Input extension is .mp4 but its container is not MP4")
    videos = [item for item in streams if item.get("codec_type") == "video"]
    audios = [item for item in streams if item.get("codec_type") == "audio"]
    if len(videos) != 1 or len(audios) > 1:
        raise MediaError("UNSUPPORTED_MEDIA", "Expected one video stream and at most one audio stream")
    video, audio = videos[0], audios[0] if audios else None
    if video.get("codec_name") != "h264" or (audio and audio.get("codec_name") != "aac"):
        raise MediaError("UNSUPPORTED_MEDIA", "The first media profile supports H.264 video with optional AAC audio")
    if video.get("disposition", {}).get("attached_pic"):
        raise MediaError("UNSUPPORTED_MEDIA", "An attached image is not a playable video stream")
    return MediaInfo(
        path=source,
        duration_ms=_duration_ms(container.get("duration")),
        size_bytes=source.stat().st_size,
        format_name=format_name,
        video_codec=str(video["codec_name"]),
        audio_codec=str(audio["codec_name"]) if audio else None,
        width=_positive_int(video.get("width"), "video width"),
        height=_positive_int(video.get("height"), "video height"),
        frame_rate=_frame_rate(video),
        rotation_degrees=_rotation(video),
        format_start_time_seconds=_number(container.get("start_time")),
        video_start_time_seconds=_number(video.get("start_time")),
        audio_start_time_seconds=_number(audio.get("start_time")) if audio else None,
        audio_sample_rate=_positive_int(audio.get("sample_rate"), "audio sample rate") if audio else None,
        audio_channels=_positive_int(audio.get("channels"), "audio channels") if audio else None,
    )


def _require_aligned_timeline(info: MediaInfo) -> None:
    starts = [info.format_start_time_seconds, info.video_start_time_seconds]
    if info.audio_codec is not None:
        starts.append(info.audio_start_time_seconds)
    if any(value is None or abs(value) > 0.000001 for value in starts):
        raise MediaError(
            "UNSUPPORTED_TIMELINE",
            "This media profile requires known zero container/video/audio start times; "
            f"received format={info.format_start_time_seconds}, video={info.video_start_time_seconds}, "
            f"audio={info.audio_start_time_seconds} seconds. Offset-aware normalization is required.",
        )


def _output_file(source: Path, output: str | Path, suffix: str) -> Path:
    target = Path(output).expanduser().resolve()
    if target == source or (target.exists() and os.path.samefile(source, target)):
        raise MediaError("OUTPUT_CONFLICT", "Output must not overwrite or alias the input file")
    if target.suffix.lower() != suffix:
        raise MediaError("INVALID_ARGUMENT", f"Output must have a {suffix} extension")
    if not target.parent.is_dir() or (target.exists() and not target.is_file()):
        raise MediaError("INVALID_ARGUMENT", "Output parent must exist and output must be a regular file")
    return target


def _temporary_output(target: Path) -> Path:
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{target.stem}.", suffix=target.suffix, dir=target.parent, delete=False
        ) as handle:
            return Path(handle.name)
    except OSError as exc:
        raise MediaError("OUTPUT_WRITE_FAILED", f"Cannot create temporary output: {exc}") from exc


def _check_duration(actual_ms: int, expected_ms: int, frame_rate: float | None = None,
                    *, interval_count: int = 1) -> None:
    # AAC packet padding and video frame boundaries prevent arbitrary sub-frame
    # duration guarantees. Large drift is an error, never a successful export.
    if type(interval_count) is not int or not 1 <= interval_count <= 12:
        raise MediaError("INVALID_ARGUMENT", "Invalid edit interval count")
    tolerance_ms = (max(100, math.ceil(2000 / frame_rate)) if frame_rate else 100) * interval_count
    if abs(actual_ms - expected_ms) > tolerance_ms:
        raise MediaError(
            "OUTPUT_VALIDATION_FAILED",
            f"Output duration {actual_ms}ms differs from expected {expected_ms}ms "
            f"by more than {tolerance_ms}ms",
        )


def _publish(
    temporary: Path, target: Path, *, guard: Callable[[], None] | None = None,
) -> None:
    if guard is not None:
        guard()
    try:
        os.replace(temporary, target)
    except OSError as exc:
        raise MediaError("OUTPUT_WRITE_FAILED", f"Cannot publish completed output: {exc}") from exc


def extract_audio(
    source: str | Path,
    output: str | Path,
    *,
    ffmpeg_path: str | Path = "ffmpeg",
    ffprobe_path: str | Path = "ffprobe",
    timeout_seconds: float = 300,
    guard: Callable[[], None] | None = None,
) -> Path:
    """Atomically write verified 16kHz mono PCM16 WAV for ASR; return its path."""
    info = probe_media(source, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds,
                       guard=guard)
    target = _output_file(info.path, output, ".wav")
    _require_aligned_timeline(info)
    if info.audio_codec is None:
        raise MediaError("NO_AUDIO", "Source has no audio stream for ASR")
    temporary = _temporary_output(target)
    try:
        _run_process(
            [str(ffmpeg_path), "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", str(info.path),
             "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(temporary)],
            timeout_seconds=timeout_seconds,
            failure_code="TRANSCODE_FAILED",
            guard=guard,
        )
        try:
            with wave.open(str(temporary), "rb") as audio:
                if (audio.getnchannels(), audio.getframerate(), audio.getsampwidth()) != (1, 16000, 2):
                    raise MediaError("OUTPUT_VALIDATION_FAILED", "Extracted audio is not 16kHz mono PCM16")
                actual_ms = round(audio.getnframes() * 1000 / audio.getframerate())
                _check_duration(actual_ms, info.duration_ms)
        except (wave.Error, EOFError, OSError) as exc:
            raise MediaError("OUTPUT_VALIDATION_FAILED", "Extracted audio is missing or invalid WAV") from exc
        _publish(temporary, target, guard=guard)
        return target
    finally:
        temporary.unlink(missing_ok=True)


def export_clip(
    source: str | Path,
    output: str | Path,
    start_ms: int,
    end_ms: int,
    *,
    ffmpeg_path: str | Path = "ffmpeg",
    ffprobe_path: str | Path = "ffprobe",
    timeout_seconds: float = 300,
    guard: Callable[[], None] | None = None,
) -> MediaInfo:
    """Transcode [start_ms, end_ms) to H.264/AAC MP4 with accurate input seek.

    Millisecond bounds are accepted, while actual video boundaries remain
    constrained by source frames. Returned metadata describes the published
    output, whose duration is checked before replacing any previous output.
    """
    if type(start_ms) is not int or type(end_ms) is not int or not 0 <= start_ms < end_ms:
        raise MediaError("INVALID_INTERVAL", "Expected integer milliseconds satisfying 0 <= start_ms < end_ms")
    info = probe_media(source, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds,
                       guard=guard)
    target = _output_file(info.path, output, ".mp4")
    _require_aligned_timeline(info)
    if end_ms > info.duration_ms:
        raise MediaError("INVALID_INTERVAL", f"end_ms exceeds source duration {info.duration_ms}ms")
    temporary = _temporary_output(target)
    try:
        _run_process(
            [str(ffmpeg_path), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
             "-accurate_seek", "-ss", f"{start_ms / 1000:.3f}", "-i", str(info.path),
             "-t", f"{(end_ms - start_ms) / 1000:.3f}", "-map", "0:v:0", "-map", "0:a:0?",
             "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-movflags", "+faststart", str(temporary)],
            timeout_seconds=timeout_seconds,
            failure_code="TRANSCODE_FAILED",
            guard=guard,
        )
        result = probe_media(temporary, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds,
                             guard=guard)
        _check_duration(result.duration_ms, end_ms - start_ms, result.frame_rate)
        if (result.audio_codec is None) != (info.audio_codec is None):
            raise MediaError("OUTPUT_VALIDATION_FAILED", "Export unexpectedly changed audio stream presence")
        _publish(temporary, target, guard=guard)
        return replace(result, path=target)
    finally:
        temporary.unlink(missing_ok=True)


def export_composed_clip(
    source: str | Path, output: str | Path, parts: list[dict], *,
    ffmpeg_path: str | Path = "ffmpeg", ffprobe_path: str | Path = "ffprobe",
    timeout_seconds: float = 300,
    guard: Callable[[], None] | None = None,
) -> MediaInfo:
    """Accurately seek each ordered source interval and encode one continuous MP4.

    No stream-copy concatenation: audio is encoded once, avoiding an AAC priming
    gap at every edit. Frame rounding can accumulate across the bounded parts.
    """
    if not isinstance(parts, list) or not 1 <= len(parts) <= 12:
        raise MediaError("INVALID_INTERVAL", "Expected 1 to 12 source intervals")
    previous = 0
    for index, part in enumerate(parts):
        if (not isinstance(part, dict) or set(part) != {"start_ms", "end_ms"}
                or type(part["start_ms"]) is not int or type(part["end_ms"]) is not int
                or not 0 <= part["start_ms"] < part["end_ms"]
                or (index and part["start_ms"] < previous)):
            raise MediaError("INVALID_INTERVAL", "Source intervals must be ordered and non-overlapping")
        previous = part["end_ms"]
    info = probe_media(source, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds,
                       guard=guard)
    _require_aligned_timeline(info)
    if parts[-1]["end_ms"] > info.duration_ms:
        raise MediaError("INVALID_INTERVAL", "Interval exceeds the original video duration")
    target = _output_file(info.path, output, ".mp4")
    temporary = _temporary_output(target)
    command = [str(ffmpeg_path), "-hide_banner", "-loglevel", "error", "-nostdin", "-y"]
    filters, inputs = [], []
    for index, part in enumerate(parts):
        duration = (part["end_ms"] - part["start_ms"]) / 1000
        command += ["-threads", "1", "-accurate_seek", "-ss", f'{part["start_ms"] / 1000:.3f}',
                    "-t", f"{duration:.3f}", "-i", str(info.path)]
        filters.append(f"[{index}:v:0]trim=duration={duration:.3f},setpts=PTS-STARTPTS[v{index}]")
        inputs.append(f"[v{index}]")
        if info.audio_codec is not None:
            filters.append(f"[{index}:a:0]atrim=duration={duration:.3f},asetpts=PTS-STARTPTS[a{index}]")
            inputs.append(f"[a{index}]")
    audio = info.audio_codec is not None
    filters.append("".join(inputs) + f"concat=n={len(parts)}:v=1:a={int(audio)}[v]" + ("[a]" if audio else ""))
    command += ["-filter_complex_threads", "1", "-filter_complex", ";".join(filters), "-map", "[v]"]
    if audio:
        command += ["-map", "[a]", "-c:a", "aac"]
    command += ["-c:v", "libx264", "-threads", "2", "-preset", "veryfast", "-crf", "23",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(temporary)]
    try:
        _run_process(command, timeout_seconds=timeout_seconds, failure_code="TRANSCODE_FAILED",
                     guard=guard)
        result = probe_media(temporary, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds,
                             guard=guard)
        expected = sum(part["end_ms"] - part["start_ms"] for part in parts)
        # Reuse the single-part bound while accounting for each edit's rounding.
        _check_duration(result.duration_ms, expected, result.frame_rate, interval_count=len(parts))
        if (result.audio_codec is None) != (info.audio_codec is None):
            raise MediaError("OUTPUT_VALIDATION_FAILED", "Composed export changed audio stream presence")
        _publish(temporary, target, guard=guard)
        return replace(result, path=target)
    finally:
        temporary.unlink(missing_ok=True)


def _portrait_blur_foreground_size(width: int, height: int) -> tuple[int, int]:
    """Contain the whole frame with bounded, nonzero chroma-aligned dimensions."""
    if not 2 <= width <= 4096 or not 2 <= height <= 4096:
        raise MediaError("INVALID_ARGUMENT", "Blur input dimensions must be within the frozen profile")
    if width * 1280 >= height * 720:
        foreground_width, foreground_height = 720, max(2, height * 720 // width // 2 * 2)
    else:
        foreground_width, foreground_height = max(2, width * 1280 // height // 2 * 2), 1280
    return foreground_width, foreground_height


def burn_caption_clip(
    source: str | Path, output: str | Path, working_dir: Path, *,
    width: int, height: int, expected_duration_ms: int, has_audio: bool,
    source_frame: bool = False,
    blur_background: bool = False,
    ffmpeg_path: str | Path = "ffmpeg", ffprobe_path: str | Path = "ffprobe",
    timeout_seconds: float = 300, guard: Callable[[], None],
    on_encoding: Callable[[int], None], on_phase: Callable[[str], None],
) -> MediaInfo:
    """Burn only program-generated relative ASS; never seek or cut the clean clip."""
    if type(blur_background) is not bool or (blur_background and (source_frame or (width, height) != (720, 1280))):
        raise MediaError("INVALID_ARGUMENT", "Blur is available only for the fixed portrait output")
    info = probe_media(source, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds, guard=guard)
    _require_aligned_timeline(info)
    if blur_background:
        foreground_width, foreground_height = _portrait_blur_foreground_size(info.width, info.height)
    target = _output_file(info.path, output, ".mp4")
    progress_path = working_dir / "ffmpeg-progress.txt"
    if progress_path.is_symlink():
        raise MediaError("CAPTION_RENDER_ARTIFACT_CHANGED", "Unsafe progress path")
    progress_path.unlink(missing_ok=True)
    temporary = _temporary_output(target)
    previous = -1

    def progress():
        nonlocal previous
        guard()
        try:
            with progress_path.open("rb") as stream:
                stream.seek(max(0, progress_path.stat().st_size - 65536))
                text = stream.read(65536).decode("ascii", errors="ignore")
            values = [int(line.split("=", 1)[1]) for line in text.splitlines()
                      if line.startswith("out_time_us=") and line.split("=", 1)[1].lstrip("-").isdigit()]
            if values and max(values) > previous:
                previous = max(values)
                on_encoding(previous)
        except FileNotFoundError:
            pass

    filters = (("" if source_frame else
                f"scale={width}:{height}:force_original_aspect_ratio=decrease:force_divisible_by=2,")
               + f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,"
               "ass=filename=captions.ass:fontsdir=fonts")
    if blur_background:
        # Only the decorative background stretches the complete source frame.
        # Both scales have fixed bounds, even for extreme source aspect ratios.
        graph = ("[0:v]split=2[background][foreground];"
                 "[background]scale=360:640,gblur=sigma=20:steps=2,scale=720:1280,"
                 "eq=contrast=0.8:brightness=-0.04:saturation=0.8,setsar=1[blurred];"
                 f"[foreground]scale={foreground_width}:{foreground_height},setsar=1[contained];"
                 "[blurred][contained]overlay=(W-w)/2:(H-h)/2:shortest=1:eof_action=endall,"
                 "setsar=1,ass=filename=captions.ass:fontsdir=fonts[v]")
        video_arguments = ["-filter_complex_threads", "1", "-filter_complex", graph,
                           "-map", "[v]", "-map", "0:a:0?"]
    else:
        video_arguments = ["-map", "0:v:0", "-map", "0:a:0?", "-vf", filters]
    try:
        _run_process([str(ffmpeg_path), "-hide_banner", "-loglevel", "warning", "-nostdin", "-y",
            "-i", str(info.path), *video_arguments,
            "-c:v", "libx264", "-threads", "2", "-preset", "veryfast", "-crf", "23",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", "-nostats",
            "-stats_period", "0.5", "-progress", "ffmpeg-progress.txt", str(temporary)],
            timeout_seconds=timeout_seconds, failure_code="CAPTION_RENDER_ENCODE_FAILED",
            guard=guard, cwd=working_dir, progress=progress)
        on_phase("VALIDATING")
        result = probe_media(temporary, ffprobe_path=ffprobe_path,
                             timeout_seconds=timeout_seconds, guard=guard)
        _check_duration(result.duration_ms, expected_duration_ms, result.frame_rate)
        if (result.video_codec != "h264" or result.width != width or result.height != height
                or result.audio_codec != ("aac" if has_audio else None)):
            raise MediaError("CAPTION_RENDER_OUTPUT_INVALID", "Rendered streams or geometry do not match")
        _run_process([str(ffmpeg_path), "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror",
            "-err_detect", "explode", "-i", str(temporary), "-map", "0:v:0", "-map", "0:a:0?",
            "-f", "null", "-"], timeout_seconds=timeout_seconds,
            failure_code="EXPORT_DECODE_FAILED", guard=guard)
        on_phase("PUBLISHING")
        _publish(temporary, target, guard=guard)
        return replace(result, path=target)
    finally:
        temporary.unlink(missing_ok=True)
