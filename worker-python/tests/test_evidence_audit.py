"""Frame timestamps describe density, never continuous coverage or accuracy."""

import pytest
from livecut.evidence_audit import audit_timestamps


def test_duplicate_timestamps_and_short_tail_are_not_extra_covered_windows():
    report = audit_timestamps(360001, [0, 0, 180000])
    assert report["observation_count"] == 3 and report["unique_timestamp_count"] == 2
    assert report["window_count"] == 3 and report["windows_without_observations"] == 1
    assert report["full_windows_without_observations"] == 0 and report["empty_bin_duration_ms"] == 1
    assert report["maximum_gap_including_edges_ms"] == 180001
    assert [item["observation_count"] for item in report["windows"]] == [1, 1, 0]


@pytest.mark.parametrize("times", [[True], [-1], [1000], [1.5], ["5"], None])
def test_invalid_times_cannot_be_reported_as_observed_evidence(times):
    with pytest.raises(ValueError):
        audit_timestamps(1000, times)
