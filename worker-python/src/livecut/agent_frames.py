"""Bounded real JPEG evidence for an explicitly opted-in VLM turn, never OCR."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import tempfile
from decimal import ROUND_HALF_UP, Decimal
from fractions import Fraction
from pathlib import Path

from .media import MediaError, _require_aligned_timeline, probe_media
from .segmentation import SegmentationError, _save
from .vision import VisionError, _extract_frame, _hash_file

MAX_SAFE = 2**53 - 1
FRAME_FIELDS = {"frame_id", "requested_ms", "timestamp_ms", "source_pts", "source_time_base", "sha256", "bytes", "width", "height"}
PROFILE = "agent-visual-copyts-showinfo-max1280-no-upscale-jpeg-q3-v1"


def integer(value, low=0, high=MAX_SAFE):
    return type(value) is int and low <= value <= high


def jpeg_dimensions(data: bytes) -> tuple[int, int]:
    """Read a bounded JPEG SOF without importing an OCR/image-model runtime."""
    if len(data) > 1024 * 1024 or not data.startswith(b"\xff\xd8") or not data.endswith(b"\xff\xd9"):
        raise ValueError("Invalid bounded JPEG")
    position = 2
    while position + 4 <= len(data):
        if data[position] != 0xff:
            raise ValueError("Invalid JPEG marker")
        while position < len(data) and data[position] == 0xff:
            position += 1
        if position >= len(data):
            break
        marker = data[position]
        position += 1
        if marker in {0x01, *range(0xd0, 0xd9)}:
            continue
        if marker in {0xda, 0xd9}:
            break
        size = int.from_bytes(data[position:position+2], "big")
        if size < 2 or position + size > len(data):
            raise ValueError("Invalid JPEG segment")
        if marker in {0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf}:
            if size < 8:
                raise ValueError("Invalid JPEG SOF")
            height = int.from_bytes(data[position+3:position+5], "big")
            width = int.from_bytes(data[position+5:position+7], "big")
            if not 1 <= width <= 1280 or not 1 <= height <= 1280:
                raise ValueError("JPEG dimensions exceeded profile")
            return width, height
        position += size
    raise ValueError("JPEG has no valid dimensions")


def validate_frame(frame: dict, duration_ms: int) -> None:
    if (not isinstance(frame, dict) or set(frame) != FRAME_FIELDS
            or not isinstance(frame["frame_id"], str) or not re.fullmatch(r"f00(?:0[1-9]|[12][0-9]|3[0-2])", frame["frame_id"])
            or not integer(frame["requested_ms"], 0, duration_ms-1)
            or not integer(frame["timestamp_ms"], frame["requested_ms"], min(duration_ms-1, frame["requested_ms"]+1000))
            or not integer(frame["source_pts"]) or not isinstance(frame["source_time_base"], str)
            or not re.fullmatch(r"[1-9][0-9]{0,9}/[1-9][0-9]{0,9}", frame["source_time_base"])
            or not isinstance(frame["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", frame["sha256"])
            or not integer(frame["bytes"], 4, 1024*1024) or not integer(frame["width"], 1, 1280)
            or not integer(frame["height"], 1, 1280)):
        raise ValueError("Invalid visual frame identity")
    numerator, denominator = map(int, frame["source_time_base"].split("/"))
    exact_ms = Fraction(frame["source_pts"] * numerator * 1000, denominator)
    milliseconds = int((Decimal(frame["source_pts"]) * numerator * 1000 / denominator).to_integral_value(rounding=ROUND_HALF_UP))
    if milliseconds != frame["timestamp_ms"] or not frame["requested_ms"] <= exact_ms <= frame["requested_ms"]+1000 or exact_ms >= duration_ms:
        raise ValueError("Visual PTS does not match source milliseconds")


def visual_arguments(name: str, arguments: object, duration_ms: int) -> dict:
    if not isinstance(arguments, dict):
        raise ValueError("Invalid visual tool arguments")
    if name == "inspect_frames":
        times = arguments.get("timestamps_ms")
        if (set(arguments) != {"timestamps_ms"} or not isinstance(times, list) or not 1 <= len(times) <= 4
                or any(not integer(item, 0, duration_ms-1) for item in times) or times != sorted(set(times))):
            raise ValueError("Invalid keyframe times")
        return {"timestamps_ms": list(times)}
    if (name != "inspect_clip" or set(arguments) != {"start_ms", "end_ms"}
            or not integer(arguments["start_ms"], 0, duration_ms-1)
            or not integer(arguments["end_ms"], arguments["start_ms"]+1, min(duration_ms, arguments["start_ms"]+12000))):
        raise ValueError("Invalid sampled clip interval")
    return dict(arguments)


def requested_times(name: str, arguments: dict) -> list[int]:
    return (arguments["timestamps_ms"] if name == "inspect_frames"
            else list(range(arguments["start_ms"], arguments["end_ms"], 500)))


def actual_position(frame):
    return frame["source_pts"] * Fraction(frame["source_time_base"])


class FrameStore:
    def __init__(self, source: Path, output: Path, source_hash: str, duration_ms: int, *, ffmpeg_path="ffmpeg", ffprobe_path="ffprobe", media_info=None, guard=lambda: None):
        self.source, self.root, self.guard = Path(source), Path(output), guard
        self.source_hash, self.duration, self.ffmpeg = source_hash, duration_ms, ffmpeg_path
        self.directory, self.path = self.root / "visual-frames", self.root / "visual-frame-cache.json"
        self.directory.mkdir(parents=True, exist_ok=True)
        if self.source.is_symlink() or self.directory.is_symlink() or self.path.is_symlink():
            raise SegmentationError("INVALID_VISUAL_CACHE", "Visual evidence cannot use symbolic links")
        if _hash_file(self.source, guard=guard) != source_hash:
            raise SegmentationError("SOURCE_HASH_MISMATCH", "Visual source bytes changed")
        try:
            info = probe_media(self.source, ffprobe_path=ffprobe_path, guard=guard)
            _require_aligned_timeline(info)
            if info.rotation_degrees % 90 != 0 or duration_ms != info.duration_ms:
                raise MediaError("UNSUPPORTED_TIMELINE", "Visual source duration or rotation is unsupported")
            self.source_width, self.source_height = ((info.height, info.width) if info.rotation_degrees % 180 else (info.width, info.height))
            if media_info is not None:
                for field in ("duration_ms", "width", "height", "rotation_degrees", "format_start_time_seconds", "video_start_time_seconds", "audio_start_time_seconds", "audio_codec"):
                    if media_info.get(field) != getattr(info, field):
                        raise MediaError("MEDIA_SOURCE_CHANGED", "Frozen media metadata differs from actual visual source")
        except MediaError as error:
            raise SegmentationError(error.code, "Visual media must pass source timeline and dimensions checks before provider input") from None
        executable = shutil.which(str(ffmpeg_path))
        runtime = _hash_file(Path(executable), guard=guard) if executable else "unavailable"
        self.binding = {"source_hash": source_hash, "duration_ms": duration_ms, "profile": PROFILE, "ffmpeg_hash": runtime,
                        "source_width": self.source_width, "source_height": self.source_height, "rotation": info.rotation_degrees % 360}
        self.frames = []
        if self.path.exists():
            try:
                saved = json.loads(self.path.read_text(encoding="utf-8"))
                if set(saved) != {"binding", "frames"} or saved["binding"] != self.binding or not isinstance(saved["frames"], list):
                    raise ValueError
                self.frames = saved["frames"]
                if len(self.frames) > 32 or len({f["requested_ms"] for f in self.frames}) != len(self.frames):
                    raise ValueError
                for index, frame in enumerate(self.frames, 1):
                    if frame["frame_id"] != f"f{index:04d}":
                        raise ValueError
                    self.read(frame)
                if sum(f["bytes"] for f in self.frames) > 16*1024*1024 or len({actual_position(f) for f in self.frames}) != len(self.frames):
                    raise ValueError
            except (OSError, ValueError, KeyError, TypeError):
                raise SegmentationError("INVALID_VISUAL_CACHE", "Cached visual evidence changed; it was not regenerated") from None
        if {p.name for p in self.directory.iterdir()} != {f["frame_id"]+".jpg" for f in self.frames}:
            raise SegmentationError("INVALID_VISUAL_CACHE", "Unindexed or extra visual files")

    def read(self, frame: dict) -> bytes:
        self.guard()
        validate_frame(frame, self.duration)
        if frame["width"] > self.source_width or frame["height"] > self.source_height:
            raise ValueError("Visual evidence unexpectedly upscaled the source")
        path = self.directory / (frame["frame_id"] + ".jpg")
        if path.is_symlink():
            raise ValueError("Linked visual evidence")
        with path.open("rb") as handle:
            data = handle.read(1024*1024+1)
        if len(data) != frame["bytes"] or hashlib.sha256(data).hexdigest() != frame["sha256"] or jpeg_dimensions(data) != (frame["width"], frame["height"]):
            raise ValueError("Cached frame bytes changed")
        self.guard()
        return data

    def inspect(self, name: str, arguments: dict, *, expected_positions=None) -> list[dict]:
        times = requested_times(name, arguments)
        if expected_positions is not None and set(expected_positions) != set(times):
            raise SegmentationError("INVALID_SCREEN_FRAME_REQUEST", "Screen frame positions do not match their sampling request")
        known = {frame["requested_ms"]: frame for frame in self.frames}
        if len(set(known) | set(times)) > 32:
            raise VisionError("VISUAL_FRAME_LIMIT", "The turn's unique requested-frame cap was reached")
        created, temporary, publishing = [], [], False
        try:
            for requested in times:
                self.guard()
                if requested in known:
                    frame = known[requested]
                    try:
                        self.read(frame)
                    except (OSError, ValueError):
                        if expected_positions is not None:
                            raise SegmentationError("INVALID_VISUAL_CACHE", "Previously observed screen image bytes changed") from None
                        raise
                else:
                    with tempfile.NamedTemporaryFile(dir=self.root, suffix=".jpg", delete=False) as handle:
                        target = Path(handle.name)
                    temporary.append(target)
                    actual = _extract_frame(self.source, target, requested, ffmpeg_path=self.ffmpeg, guard=self.guard, max_edge=1280)
                    data = target.read_bytes()
                    width, height = jpeg_dimensions(data)
                    frame = {"frame_id": f"f{len(self.frames)+len(created)+1:04d}", "requested_ms": requested, **actual,
                             "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data), "width": width, "height": height}
                    validate_frame(frame, self.duration)
                    if width > self.source_width or height > self.source_height:
                        raise VisionError("VISUAL_FRAME_UPSCALED", "Visual extraction cannot upscale source pixels")
                    if any(actual_position(other) == actual_position(frame) for other in [*self.frames, *(f for f, _ in created)]):
                        raise VisionError("DUPLICATE_VISUAL_PTS", "Different requested positions resolved to the same source frame")
                    created.append((frame, target))
                if expected_positions is not None and actual_position(frame) != expected_positions[requested]:
                    raise VisionError("SCREEN_FRAME_POSITION_MISMATCH", "The observed source position differs from the published OCR locator")
                if name == "inspect_clip" and not arguments["start_ms"] <= actual_position(frame)*1000 < arguments["end_ms"]:
                    raise VisionError("VISUAL_FRAME_OUTSIDE_CLIP", "An actual frame falls outside the sampled interval")
            if sum(frame["bytes"] for frame in self.frames) + sum(frame["bytes"] for frame, _ in created) > 16*1024*1024:
                raise VisionError("VISUAL_BYTES_LIMIT", "The turn's JPEG byte cap was reached")
            if _hash_file(self.source, guard=self.guard) != self.source_hash:
                raise SegmentationError("SOURCE_HASH_MISMATCH", "Visual source changed during sampling")
            self.guard()
            # Once publication begins, a disk error may have left JPEG files
            # ahead of the index. Do not hide this as an ordinary tool miss and
            # spend another model call over an incomplete evidence checkpoint.
            publishing = True
            for frame, target in created:
                destination = self.directory / (frame["frame_id"] + ".jpg")
                if destination.exists():
                    raise SegmentationError("INVALID_VISUAL_CACHE", "Refused to overwrite a visual frame")
                os.replace(target, destination)
                self.frames.append(frame)
                known[frame["requested_ms"]] = frame
            self.guard()
            _save(self.path, {"binding": self.binding, "frames": self.frames})
            return [dict(known[timestamp]) for timestamp in times]
        except OSError:
            if publishing:
                raise SegmentationError("VISUAL_CACHE_PUBLICATION_FAILED", "Visual evidence publication was interrupted; no further model call was made") from None
            raise VisionError("INVALID_VISUAL_FRAME", "Real frame evidence could not be verified") from None
        except ValueError:
            raise VisionError("INVALID_VISUAL_FRAME", "Real frame evidence could not be verified") from None
        finally:
            for target in temporary:
                target.unlink(missing_ok=True)

    def images(self, frames: list[dict]) -> list[dict]:
        content = []
        try:
            for frame in frames:
                content.extend([{"type": "text", "text": f"FRAME {frame['frame_id']} actual_timestamp_ms={frame['timestamp_ms']} requested_ms={frame['requested_ms']}"},
                                {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(self.read(frame)).decode("ascii")}}])
        except (OSError, ValueError):
            raise SegmentationError("INVALID_VISUAL_CACHE", "Image bytes changed before model input") from None
        return content
