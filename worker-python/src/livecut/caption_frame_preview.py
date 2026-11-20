"""Render one displayed video frame from an already confirmed clean clip and caption spec."""
from __future__ import annotations

import json
import os
import sys
from fractions import Fraction
from pathlib import Path

from .media import MediaError, _run_process, caption_video_filters
from .worker import WorkerError
from .worker_caption_render import integer, validate_spec, verified_caption_inputs


def frame_at(source, at_ms, ffprobe):
    # Seek before the requested instant, then compare original PTS. In particular, the
    # final milliseconds of a clip display the last frame, even on VFR sources.
    start = max(0, at_ms - 1000) / 1000
    end = at_ms / 1000 + 1
    result = _run_process([
        ffprobe, "-v", "error", "-select_streams", "v:0", "-read_intervals", f"{start:.3f}%{end:.3f}",
        "-show_entries", "stream=time_base:frame=best_effort_timestamp", "-of", "json", str(source),
    ], timeout_seconds=15, failure_code="CAPTION_PREVIEW_FRAME_UNAVAILABLE")
    report = json.loads(result.stdout)
    time_base = Fraction(report["streams"][0]["time_base"])
    if time_base <= 0:
        raise ValueError("invalid time base")
    ticks = [frame["best_effort_timestamp"] for frame in report.get("frames", [])
             if type(frame.get("best_effort_timestamp")) is int]
    eligible = [tick for tick in ticks if 0 <= tick * time_base * 1000 <= at_ms]
    if not eligible:
        raise MediaError("CAPTION_PREVIEW_FRAME_UNAVAILABLE", "No video frame at the requested instant")
    pts = max(eligible)
    return pts, max(0, float(pts * time_base) - 1)


def preview_frame(storage_root, spec, expected_hash, at_ms, directory, *, ffmpeg="ffmpeg", ffprobe="ffprobe"):
    validate_spec(spec, expected_hash)
    media = spec["input_media"]
    integer(at_ms, 0, media["duration_ms"] - 1)
    source, font, ass = verified_caption_inputs(storage_root, spec, lambda: None)
    pts, seek = frame_at(source, at_ms, ffprobe)
    (directory / "fonts").mkdir()
    (directory / "fonts" / "font.otf").write_bytes(font)
    (directory / "captions.ass").write_bytes(ass)
    filters = caption_video_filters(
        spec["output"]["width"], spec["output"]["height"], spec["frame_preset"] == "SOURCE",
        spec["frame_preset"] == "PORTRAIT_BLUR_720", media["width"], media["height"],
        prefix=f"trim=start_pts={pts},",
    )
    _run_process([
        ffmpeg, "-v", "error", "-nostdin", "-copyts", "-ss", f"{seek:.6f}", "-noaccurate_seek",
        "-i", str(source), *filters, "-an", "-frames:v", "1", "-fps_mode", "passthrough",
        "-threads", "1", "-q:v", "3", "-update", "1", "preview.jpg",
    ], cwd=directory, timeout_seconds=25, failure_code="CAPTION_PREVIEW_FRAME_UNAVAILABLE")


def main():
    try:
        job = json.load(sys.stdin.buffer)
        preview_frame(Path(job["storage_root"]), job["spec"], job["spec_hash"], job["at_ms"], Path.cwd(),
                      ffmpeg=os.getenv("LIVECUT_FFMPEG_PATH") or "ffmpeg",
                      ffprobe=os.getenv("LIVECUT_FFPROBE_PATH") or "ffprobe")
    except (MediaError, WorkerError) as exc:
        print(exc.code)
        return 1
    except (ValueError, KeyError, IndexError, OSError, TypeError):
        print("CAPTION_PREVIEW_FRAME_UNAVAILABLE")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
