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
import wave
from dataclasses import dataclass, replace
from decimal import ROUND_CEILING, Decimal, InvalidOperation
from fractions import Fraction
from pathlib import Path
from typing import Any


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
    command: list[str], *, timeout_seconds: float, failure_code: str
) -> subprocess.CompletedProcess[str]:
    if isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float)):
        raise MediaError("INVALID_ARGUMENT", "timeout_seconds must be a positive finite number")
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise MediaError("INVALID_ARGUMENT", "timeout_seconds must be a positive finite number")
    try:
        result = subprocess.run(
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
        )
    except FileNotFoundError as exc:
        raise MediaError("TOOL_NOT_FOUND", f"Executable not found: {command[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise MediaError("PROCESS_TIMEOUT", f"Media command exceeded {timeout_seconds} seconds") from exc
    except OSError as exc:
        raise MediaError(failure_code, f"Unable to execute media command: {exc}") from exc
    if result.returncode != 0:
        detail = (result.stderr or "No diagnostic output").strip()[-2000:]
        raise MediaError(failure_code, f"Media command failed (exit {result.returncode}): {detail}")
    return result


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


def _check_duration(actual_ms: int, expected_ms: int, frame_rate: float | None = None) -> None:
    # AAC packet padding and video frame boundaries prevent arbitrary sub-frame
    # duration guarantees. Large drift is an error, never a successful export.
    tolerance_ms = max(100, math.ceil(2000 / frame_rate)) if frame_rate else 100
    if abs(actual_ms - expected_ms) > tolerance_ms:
        raise MediaError(
            "OUTPUT_VALIDATION_FAILED",
            f"Output duration {actual_ms}ms differs from expected {expected_ms}ms "
            f"by more than {tolerance_ms}ms",
        )


def _publish(temporary: Path, target: Path) -> None:
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
) -> Path:
    """Atomically write verified 16kHz mono PCM16 WAV for ASR; return its path."""
    info = probe_media(source, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds)
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
        )
        try:
            with wave.open(str(temporary), "rb") as audio:
                if (audio.getnchannels(), audio.getframerate(), audio.getsampwidth()) != (1, 16000, 2):
                    raise MediaError("OUTPUT_VALIDATION_FAILED", "Extracted audio is not 16kHz mono PCM16")
                actual_ms = round(audio.getnframes() * 1000 / audio.getframerate())
                _check_duration(actual_ms, info.duration_ms)
        except (wave.Error, EOFError, OSError) as exc:
            raise MediaError("OUTPUT_VALIDATION_FAILED", "Extracted audio is missing or invalid WAV") from exc
        _publish(temporary, target)
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
) -> MediaInfo:
    """Transcode [start_ms, end_ms) to H.264/AAC MP4 with accurate input seek.

    Millisecond bounds are accepted, while actual video boundaries remain
    constrained by source frames. Returned metadata describes the published
    output, whose duration is checked before replacing any previous output.
    """
    if type(start_ms) is not int or type(end_ms) is not int or not 0 <= start_ms < end_ms:
        raise MediaError("INVALID_INTERVAL", "Expected integer milliseconds satisfying 0 <= start_ms < end_ms")
    info = probe_media(source, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds)
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
        )
        result = probe_media(temporary, ffprobe_path=ffprobe_path, timeout_seconds=timeout_seconds)
        _check_duration(result.duration_ms, end_ms - start_ms, result.frame_rate)
        if (result.audio_codec is None) != (info.audio_codec is None):
            raise MediaError("OUTPUT_VALIDATION_FAILED", "Export unexpectedly changed audio stream presence")
        _publish(temporary, target)
        return replace(result, path=target)
    finally:
        temporary.unlink(missing_ok=True)
