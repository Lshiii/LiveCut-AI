"""Resource and compatibility checks, never recognition-quality measurements."""

from collections import Counter

import pytest
from livecut.ocr_sampling import sampling_timestamps
from livecut.vision import VisionError
from test_ocr_cache import digest, entry
from test_ocr_cache import fixture as fixture
from test_ocr_cache import run as cache_run


@pytest.mark.parametrize("duration,per_window", [(5400000, 4), (7200000, 3)])
def test_long_uniform_budget_covers_every_complete_core_window(duration, per_window):
    requested = sampling_timestamps(duration, 45000, 120, "uniform")
    assert len(requested) == 120
    assert Counter(timestamp // 180000 for timestamp in requested) == {
        index: per_window for index in range(duration // 180000)}


def test_frame_budget_does_not_claim_partial_tail_window_coverage():
    requested = sampling_timestamps(5400001, 45000, 120, "uniform")
    assert len(requested) == 120
    assert all(timestamp < 5400000 for timestamp in requested)


def test_full_frame_budget_fits_cache_and_isolates_legacy_evidence(fixture):
    old = cache_run(fixture, "old-16", duration_ms=5400000, interval_ms=45000,
                    max_frames=16, sampling_policy="uniform")
    original = {path.relative_to(fixture.root): digest(path)
                for folder in (entry(fixture, old), fixture.root / "old-16")
                for path in folder.rglob("*") if path.is_file()}
    fresh = cache_run(fixture, "new-120", duration_ms=5400000, interval_ms=45000,
                      max_frames=120, sampling_policy="uniform")
    assert fresh.provenance["cache_key"] != old.provenance["cache_key"]
    assert len(fresh.observations) == 120 and fixture.engine.calls == 136
    calls = list(fixture.extractor.calls)
    hit = cache_run(fixture, "new-120-restored", duration_ms=5400000, interval_ms=45000,
                    max_frames=120, sampling_policy="uniform")
    assert hit.observations == fresh.observations
    assert fixture.engine.calls == 136 and fixture.extractor.calls == calls
    assert all(digest(fixture.root / path) == expected for path, expected in original.items())


def test_high_frame_budget_reserves_space_beside_prior_cache_occupancy(fixture):
    # A prior/crash-left bundle must count toward the quota even when it is not
    # the requested key. Sparse test files exercise disk accounting, not images.
    occupied = fixture.cache / ("f" * 64) / "evidence" / "frames"
    occupied.mkdir(parents=True)
    for index in range(8):
        with (occupied / f"frame-{index}.jpg").open("wb") as handle:
            handle.truncate(4 * 1024 * 1024)
    with pytest.raises(VisionError) as caught:
        cache_run(fixture, "explicit-old-cap", duration_ms=5400000, max_frames=120,
                  interval_ms=45000, sampling_policy="uniform", max_cache_bytes=512 * 1024 * 1024)
    assert caught.value.code == "OCR_CACHE_CAPACITY_EXCEEDED" and fixture.engine.calls == 0
    result = cache_run(fixture, "default-cap", duration_ms=5400000, max_frames=120,
                       interval_ms=45000, sampling_policy="uniform")
    assert len(result.observations) == 120
    assert len(list(occupied.iterdir())) == 8
