"""An explicit user location primes one real frame before any VLM request."""
from __future__ import annotations

import json
import re

from .segmentation import SegmentationError, _save
from .vision import VisionError

PROMPT_VERSION = "video-agent-visual-focus-v1"
MISSING = object()


def _pairs(values):
    result = {}
    for key, value in values:
        if key in result:
            raise ValueError("Duplicate focus checkpoint key")
        result[key] = value
    return result


def _write(path, value, guard):
    guard()
    if path.is_symlink() or path.with_suffix(path.suffix + ".tmp").is_symlink():
        raise SegmentationError("INVALID_AGENT_FOCUS_CHECKPOINT", "Focus observation cannot follow a linked checkpoint")
    try:
        _save(path, value)
    except OSError:
        raise SegmentationError("FOCUS_OBSERVATION_STORAGE_FAILED", "The initial focus observation could not be persisted; no model request was made") from None
    guard()


def prime_focus(store, directory, fingerprint, focus_timestamp_ms):
    """Replay an immutable initial observation; never retry an ambiguous sample."""
    path = directory / "focus-observation.json"
    base = {"config_hash": fingerprint, "focus_timestamp_ms": focus_timestamp_ms,
            "status": "PREPARING", "frame": None, "error_code": None}
    store.guard()
    if path.exists() or path.is_symlink():
        try:
            if path.is_symlink() or path.stat().st_size > 65536:
                raise ValueError
            saved = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_pairs)
            if (not isinstance(saved, dict) or set(saved) != set(base)
                    or saved["config_hash"] != fingerprint or type(saved["focus_timestamp_ms"]) is not int
                    or saved["focus_timestamp_ms"] != focus_timestamp_ms
                    or saved["status"] not in {"PREPARING", "READY", "FAILED"}):
                raise ValueError
            if saved["status"] == "READY":
                frame = saved["frame"]
                if (saved["error_code"] is not None or not store.frames or frame != store.frames[0]
                        or frame["frame_id"] != "f0001" or frame["requested_ms"] != focus_timestamp_ms):
                    raise ValueError
                store.read(frame)
                return dict(frame)
            if saved["frame"] is not None or (saved["status"] == "PREPARING" and saved["error_code"] is not None):
                raise ValueError
            if saved["status"] == "PREPARING":
                saved.update(status="FAILED", error_code="FOCUS_OBSERVATION_INCOMPLETE")
                _write(path, saved, store.guard)
            elif not isinstance(saved["error_code"], str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", saved["error_code"]):
                raise ValueError
        except (OSError, ValueError, KeyError, TypeError):
            raise SegmentationError("INVALID_AGENT_FOCUS_CHECKPOINT", "The initial focus observation changed; it was not regenerated") from None
        raise SegmentationError("FOCUS_FRAME_UNAVAILABLE", "The original initial focus observation failed; no new frame or model request was attempted")
    if store.frames:
        raise SegmentationError("INVALID_AGENT_FOCUS_CHECKPOINT", "Focus frame evidence lacks its original observation receipt")
    _write(path, base, store.guard)
    try:
        frames = store.inspect("inspect_frames", {"timestamps_ms": [focus_timestamp_ms]})
        if len(frames) != 1 or frames[0]["frame_id"] != "f0001" or frames[0]["requested_ms"] != focus_timestamp_ms:
            raise SegmentationError("INVALID_AGENT_FOCUS_CHECKPOINT", "The initial focus frame does not match the explicit position")
        store.read(frames[0])
    except (VisionError, SegmentationError) as error:
        code = getattr(error, "code", "FOCUS_FRAME_UNAVAILABLE")
        if not isinstance(code, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code):
            code = "FOCUS_FRAME_UNAVAILABLE"
        _write(path, {**base, "status": "FAILED", "error_code": code}, store.guard)
        if isinstance(error, SegmentationError):
            raise
        raise SegmentationError("FOCUS_FRAME_UNAVAILABLE", "The attached position has no verified frame; no model request was made") from None
    _write(path, {**base, "status": "READY", "frame": frames[0]}, store.guard)
    return dict(frames[0])
