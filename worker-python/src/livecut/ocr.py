"""Explicit local OCR observer; it never substitutes a fake VLM response."""

import math
import os
import re
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any, Callable

from .ocr_sampling import sampling_timestamps
from .vision import VisionError, _extract_frame, _hash_file, _lock, _read_json, _write_json


def _native_path(path: Path) -> Path:
    if os.name != "nt":
        return path
    absolute = os.path.abspath(path)
    if absolute.startswith("\\\\?\\"):
        return Path(absolute)
    if absolute.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + absolute[2:])
    return Path("\\\\?\\" + absolute)


def ocr_model_id() -> str:
    try:
        return "rapidocr-" + version("rapidocr")
    except PackageNotFoundError:
        raise VisionError("OCR_NOT_INSTALLED", "Install local OCR with: uv sync --extra ocr") from None


def observe_video_ocr(
    source: str | Path, output_dir: str | Path, *, duration_ms: int,
    ffmpeg_path: str = "ffmpeg", interval_ms: int = 45000, max_frames: int = 16,
    sampling_policy: str = "prefix",
    engine: Callable[[str], Any] | None = None,
    extractor: Callable[..., dict] | None = None,
    guard: Callable[[], None] | None = None,
) -> list[dict]:
    """Read actual sampled frames. Categories remain unknown until semantic analysis."""
    requested = sampling_timestamps(duration_ms, interval_ms, max_frames, sampling_policy)
    path = Path(source).expanduser().resolve()
    if not path.is_file():
        raise VisionError("INVALID_SOURCE", "OCR source must be a local file")
    model_id = "custom-ocr-engine" if engine is not None else ocr_model_id()
    check = guard or (lambda: None)
    check()
    binding = {"source_sha256": _hash_file(path), "duration_ms": duration_ms,
               "interval_ms": interval_ms, "max_frames": max_frames, "model_id": model_id}
    if sampling_policy == "uniform":
        binding["sampling_policy"] = "uniform"
    directory = Path(output_dir).expanduser().resolve()
    (directory / "frames").mkdir(parents=True, exist_ok=True)
    state_path = directory / "ocr-state.json"
    with _lock(directory):
        state = _read_json(state_path) if state_path.exists() else {
            "input": binding, "frames": [], "observations": [],
            "coverage_truncated": sampling_policy == "prefix" and math.ceil(duration_ms / interval_ms) > max_frames,
        }
        if state.get("input") != binding:
            raise VisionError("CHECKPOINT_INPUT_MISMATCH", "OCR cache belongs to different input/config")
        completed = state["observations"]
        if len(completed) > len(requested) or len(state["frames"]) != len(completed):
            raise VisionError("INVALID_CHECKPOINT", "OCR cache is incomplete or exceeds budget")
        if sampling_policy == "uniform" and state.get("coverage_truncated") is not False:
            raise VisionError("INVALID_CHECKPOINT", "Uniform OCR checkpoint has an incompatible sampling plan")
        if len(completed) < len(requested) and engine is None:
            check()
            try:
                from rapidocr import RapidOCR
            except ImportError:
                raise VisionError("OCR_NOT_INSTALLED", "Install local OCR with: uv sync --extra ocr") from None
            engine = RapidOCR()
        for index, requested_ms in enumerate(requested):
            check()
            frame_id = f"ocr_{index + 1:06d}"
            key = f"frames/{frame_id}.jpg"
            target = directory / key
            if index < len(completed):
                if sampling_policy == "uniform":
                    frame, observation = state["frames"][index], completed[index]
                    if (not isinstance(frame, dict) or not isinstance(observation, dict)
                            or frame.get("requested_timestamp_ms") != requested_ms
                            or type(frame.get("requested_timestamp_ms")) is not int
                            or frame.get("frame_id") != frame_id or observation.get("frame_id") != frame_id
                            or frame.get("object_key") != key or observation.get("object_key") != key
                            or type(frame.get("timestamp_ms")) is not int
                            or type(observation.get("timestamp_ms")) is not int
                            or frame["timestamp_ms"] != observation["timestamp_ms"]
                            or not 0 <= frame["timestamp_ms"] < duration_ms
                            or frame["timestamp_ms"] + 1 < requested_ms
                            or type(frame.get("source_pts")) is not int or frame["source_pts"] < 0
                            or not isinstance(frame.get("source_time_base"), str)
                            or not re.fullmatch(r"[1-9][0-9]*/[1-9][0-9]*", frame["source_time_base"])):
                        raise VisionError("INVALID_CHECKPOINT", "Uniform OCR checkpoint does not match its sampling plan")
                    try:
                        numerator, denominator = (int(part) for part in frame["source_time_base"].split("/"))
                    except ValueError:
                        raise VisionError("INVALID_CHECKPOINT", "Uniform OCR checkpoint has an invalid time base") from None
                    if abs(frame["timestamp_ms"] * denominator - frame["source_pts"] * numerator * 1000) > denominator:
                        raise VisionError("INVALID_CHECKPOINT", "Uniform OCR checkpoint frame time does not match its PTS")
                if _hash_file(target) != state["frames"][index]["sha256"]:
                    raise VisionError("FRAME_CHANGED", "Cached OCR frame has changed")
                continue
            if target.is_symlink() or target.resolve() == path:
                raise VisionError("OUTPUT_CONFLICT", "OCR frames must not overwrite source")
            timing = (extractor or _extract_frame)(
                _native_path(path), _native_path(target), requested_ms, ffmpeg_path=ffmpeg_path)
            check()
            if not 0 <= timing["timestamp_ms"] < duration_ms:
                raise VisionError("INVALID_FRAME_TIME", "OCR frame time is outside video")
            assert engine is not None
            result = engine(str(_native_path(target)))
            check()
            texts = getattr(result, "txts", None)
            scores = getattr(result, "scores", None)
            lines = []
            if texts is not None and scores is not None:
                for text, score in zip(texts, scores, strict=True):
                    if isinstance(text, str) and math.isfinite(float(score)) and float(score) >= 0.45:
                        lines.append(text)
            completed.append({"frame_id": frame_id, "timestamp_ms": timing["timestamp_ms"],
                              "object_key": key, "presented_category": None,
                              "observed_text": "\n".join(lines)[:2000],
                              "visibility": "clear" if lines else "not_visible"})
            state["frames"].append({"frame_id": frame_id, "requested_timestamp_ms": requested_ms,
                                    **timing, "object_key": key, "sha256": _hash_file(target)})
            _write_json(state_path, state)
        check()
        _write_json(directory / "observations.json", completed)
        _write_json(directory / "frames.json", state["frames"])
        return completed
