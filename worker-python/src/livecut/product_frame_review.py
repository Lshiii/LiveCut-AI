"""Read-only local image/text ranking of ASR-bound candidate ranges. No provider calls."""
from __future__ import annotations

import base64
import hashlib
import json
import math
import os
import shutil
import sys
import tempfile
from fractions import Fraction
from pathlib import Path

from .agent_frames import jpeg_dimensions, validate_frame
from .ocr_cache import _locked
from .vision import VisionError, _extract_frame, _hash_file


def sample_ranges(parts):
    """Cover short combinations; sample first, middle and last for longer combinations."""
    if len(parts) > 3:
        parts = [parts[0], parts[len(parts) // 2], parts[-1]]
    points = []
    for start, end in parts:
        points.append((start + (end - start) // 2, start, end))
    start, end = max(parts, key=lambda p: p[1] - p[0])
    if end - start >= 4000:
        for numerator in (1, 3):
            if len(points) == 3:
                break
            points.append((start + (end - start) * numerator // 4, start, end))
    return sorted(set(points))


def _checked_sample(target, requested, duration):
    image = target / "frame.jpg"
    meta = json.loads((target / "frame.json").read_text(encoding="utf-8"))
    validate_frame(meta, duration)
    with image.open("rb") as stream:
        data = stream.read(1024 * 1024 + 1)
    if (meta["requested_ms"] != requested or len(data) != meta["bytes"]
            or hashlib.sha256(data).hexdigest() != meta["sha256"]
            or jpeg_dimensions(data) != (meta["width"], meta["height"])):
        raise ValueError("invalid visual frame")
    return meta, image


def cached_sample(source, cache, requested, duration, ffmpeg):
    target = cache / str(requested)
    target.mkdir(exist_ok=True)
    with _locked(target, timeout=15, guard=lambda: None):
        if target.is_symlink() or any((target / f).is_symlink() for f in ("frame.jpg", "frame.json")):
            raise ValueError("linked visual frame")
        if (target / "frame.json").exists():
            try:
                return _checked_sample(target, requested, duration)
            except (ValueError, KeyError, TypeError, FileNotFoundError):
                # This cache is disposable. Only repair by decoding the verified
                # source, never by blessing new bytes with an old timestamp.
                pass
        with tempfile.TemporaryDirectory(dir=target) as temp:
            staged_image = Path(temp) / "frame.jpg"
            actual = _extract_frame(source, staged_image, requested, ffmpeg_path=ffmpeg, max_edge=384)
            data = staged_image.read_bytes()
            width, height = jpeg_dimensions(data)
            meta = dict(frame_id="f0001", requested_ms=requested, **actual, width=width, height=height,
                        bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
            validate_frame(meta, duration)
            staged_meta = Path(temp) / "frame.json"
            staged_meta.write_text(json.dumps(meta), encoding="utf-8")
            staged_image.replace(target / "frame.jpg")
            staged_meta.replace(target / "frame.json")
        return _checked_sample(target, requested, duration)


def review(request, score, model_id):
    source = Path(request["source"])
    source_hash = request["source_hash"]
    if source.is_symlink() or _hash_file(source) != source_hash:
        raise ValueError("source hash changed")
    ffmpeg = shutil.which(os.environ.get("LIVECUT_FFMPEG_PATH", "ffmpeg"))
    if not ffmpeg:
        raise FileNotFoundError("FFmpeg")
    duration = request["duration_ms"]
    if type(duration) is not int or duration < 1:
        raise ValueError("invalid duration")
    cache = Path(request["cache_root"]) / source_hash / ("jpeg384-v1-" + _hash_file(Path(ffmpeg))[:16])
    cache.mkdir(parents=True, exist_ok=True)
    candidates = request["candidates"]
    if not 1 <= len(candidates) <= 12:
        raise ValueError("invalid candidate count")
    groups = []
    indexed = {}
    for candidate in candidates:
        parts = candidate["parts"]
        if not 1 <= len(parts) <= 12 or any(type(a) is not int or type(b) is not int
                                            or not 0 <= a < b <= duration for a, b in parts):
            raise ValueError("invalid candidate range")
        times = []
        for wanted, start, end in sample_ranges(parts):
            if wanted not in indexed:
                try:
                    indexed[wanted] = cached_sample(source, cache, wanted, duration, ffmpeg)
                except VisionError as error:
                    if error.code != "FRAME_TIMESTAMP_MISSING":
                        raise
                    # A narrow range at the end may extend past the last
                    # displayable frame. Other candidates can still be ranked.
                    indexed[wanted] = None
            if indexed[wanted] is None:
                continue
            meta, _ = indexed[wanted]
            actual = meta["source_pts"] * Fraction(meta["source_time_base"]) * 1000
            if start <= actual < end and start <= meta["timestamp_ms"] < end:
                times.append(wanted)
        groups.append((candidate["id"], times))
    keys = sorted({t for _, times in groups for t in times})
    values = list(score(request["label"], [indexed[t][1] for t in keys])) if keys else []
    if len(keys) != len(values) or any(not math.isfinite(v) or abs(v) > 1.001 for v in values):
        raise ValueError("invalid model output")
    scores = dict(zip(keys, values))
    rows = []
    for candidate_id, times in groups:
        if not times:
            rows.append(dict(id=candidate_id, sample_count=0, score=None, frame=None))
            continue
        best = max(times, key=scores.__getitem__)
        meta, image = indexed[best]
        frame = {k: v for k, v in meta.items() if k != "frame_id"}
        frame["jpeg_base64"] = base64.b64encode(image.read_bytes()).decode("ascii")
        rows.append(dict(id=candidate_id, sample_count=len(times), score=float(scores[best]), frame=frame))
    rows.sort(key=lambda row: row["score"] if row["score"] is not None else -2, reverse=True)
    return {"model_id": model_id, "rows": rows}


def main():
    from .product_frame_model import MODEL_ID, ProductFrameModelUnavailable, rank_product_frames
    try:
        request = json.loads(sys.stdin.buffer.read(65536).decode("utf-8"))
        response = review(request, rank_product_frames, MODEL_ID)
    except ProductFrameModelUnavailable:
        response = {"error_code": "PRODUCT_FRAME_MODEL_UNAVAILABLE"}
    except (OSError, ValueError, RuntimeError, VisionError):
        response = {"error_code": "PRODUCT_FRAME_REVIEW_FAILED"}
    sys.stdout.buffer.write(json.dumps(response, ensure_ascii=True, allow_nan=False).encode("utf-8"))


if __name__ == "__main__":
    main()
