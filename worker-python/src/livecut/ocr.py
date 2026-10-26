"""Explicit local OCR observer; it never substitutes a fake VLM response."""

import math
import os
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any, Callable

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
    engine: Callable[[str], Any] | None = None,
    extractor: Callable[..., dict] | None = None,
    guard: Callable[[], None] | None = None,
) -> list[dict]:
    """Read actual sampled frames. Categories remain unknown until semantic analysis."""
    if any(type(x) is not int or x <= 0 for x in (duration_ms, interval_ms, max_frames)):
        raise VisionError("INVALID_CONFIGURATION", "OCR sampling values must be positive integers")
    if max_frames > 120:
        raise VisionError("INVALID_CONFIGURATION", "OCR frame budget cannot exceed 120")
    path = Path(source).expanduser().resolve()
    if not path.is_file():
        raise VisionError("INVALID_SOURCE", "OCR source must be a local file")
    model_id = "custom-ocr-engine" if engine is not None else ocr_model_id()
    check = guard or (lambda: None)
    check()
    binding = {"source_sha256": _hash_file(path), "duration_ms": duration_ms,
               "interval_ms": interval_ms, "max_frames": max_frames, "model_id": model_id}
    directory = Path(output_dir).expanduser().resolve()
    (directory / "frames").mkdir(parents=True, exist_ok=True)
    state_path = directory / "ocr-state.json"
    with _lock(directory):
        state = _read_json(state_path) if state_path.exists() else {
            "input": binding, "frames": [], "observations": [],
            "coverage_truncated": math.ceil(duration_ms / interval_ms) > max_frames,
        }
        if state.get("input") != binding:
            raise VisionError("CHECKPOINT_INPUT_MISMATCH", "OCR cache belongs to different input/config")
        completed = state["observations"]
        requested = list(range(0, min(duration_ms, interval_ms * max_frames), interval_ms))
        if len(completed) > len(requested) or len(state["frames"]) != len(completed):
            raise VisionError("INVALID_CHECKPOINT", "OCR cache is incomplete or exceeds budget")
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
