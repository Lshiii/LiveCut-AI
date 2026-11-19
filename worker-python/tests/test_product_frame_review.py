import base64
import hashlib
import shutil
import subprocess
from concurrent.futures import ThreadPoolExecutor

import pytest
from livecut import product_frame_review as review


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="FFmpeg is required to exercise real frame extraction")
def test_source_bound_sampling_and_cache(tmp_path, monkeypatch):
    source = tmp_path / "input.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=96x72:rate=10",
                    "-t", "6", "-c:v", "mpeg4", str(source)], check=True)
    request = {"source": str(source), "source_hash": hashlib.sha256(source.read_bytes()).hexdigest(),
               "cache_root": str(tmp_path / "cache"), "duration_ms": 6000, "label": "synthetic",
               "candidates": [{"id": "a", "parts": [[0, 2000]]}, {"id": "b", "parts": [[2000, 4000]]}]}
    def score(label, images):
        assert label == request["label"] and len(images) == 2
        return [-.2, .6]
    extract = review._extract_frame
    decoded = []
    def counted(source, image, timestamp, **opts):
        decoded.append(timestamp)
        return extract(source, image, timestamp, **opts)
    monkeypatch.setattr(review, "_extract_frame", counted)
    with ThreadPoolExecutor(max_workers=2) as pool:
        actual, concurrent = list(pool.map(lambda _: review.review(request, score, "synthetic-scorer"), range(2)))
    assert actual == concurrent and sorted(decoded) == [1000, 3000]
    assert [r["id"] for r in actual["rows"]] == ["b", "a"]
    frame = actual["rows"][0]["frame"]
    assert frame["requested_ms"] <= frame["timestamp_ms"] < 4000 and frame["timestamp_ms"] >= 2000
    assert hashlib.sha256(base64.b64decode(frame["jpeg_base64"])).hexdigest() == frame["sha256"]
    monkeypatch.setattr(review, "_extract_frame", lambda *a, **kw: pytest.fail("should reuse source-matched frame"))
    request["label"] = "second visual query"
    assert review.review(request, score, "synthetic-scorer") == actual
    # Corrupt cache entries cannot be attributed to the source.
    next((tmp_path / "cache").rglob("frame.jpg")).write_bytes(b"wrong")
    # The second request must repair from the source, not publish stale metadata.
    monkeypatch.setattr(review, "_extract_frame", extract)
    assert review.review(request, score, "synthetic-scorer") == actual
    request["source_hash"] = "f" * 64
    with pytest.raises(ValueError, match="source hash"):
        review.review(request, score, "synthetic-scorer")


def test_samples_cover_each_part_and_remain_bounded():
    parts = [[200, 300], [1231, 58000]]
    samples = review.sample_ranges(parts)
    assert len(samples) == 3
    assert {(start, end) for _, start, end in samples} == {tuple(p) for p in parts}
    assert all(a <= timestamp < b for timestamp, a, b in samples)
