"""Observed time-point distribution, without inferring recognition quality."""

import math


def audit_timestamps(duration_ms: int, timestamps: list[int]) -> dict:
    if (type(duration_ms) is not int or duration_ms <= 0 or not isinstance(timestamps, list)
            or len(timestamps) > 10000 or any(type(value) is not int or not 0 <= value < duration_ms for value in timestamps)):
        raise ValueError("Evidence audit requires bounded integer source timestamps")
    window_ms = 180000
    count = math.ceil(duration_ms / window_ms)
    if count > 10000:
        raise ValueError("Evidence audit exceeds 10000 time bins")
    unique = sorted(set(timestamps))
    windows = [{"start_ms": index * window_ms, "end_ms": min(duration_ms, (index + 1) * window_ms),
                "observation_count": 0} for index in range(count)]
    for timestamp in unique:
        windows[timestamp // window_ms]["observation_count"] += 1
    anchors = [0, *unique, duration_ms]
    return {"observation_count": len(timestamps), "unique_timestamp_count": len(unique),
            "first_timestamp_ms": unique[0] if unique else None,
            "last_timestamp_ms": unique[-1] if unique else None,
            "maximum_gap_including_edges_ms": max(b - a for a, b in zip(anchors, anchors[1:])),
            "window_ms": window_ms, "window_count": len(windows),
            "windows_without_observations": sum(not item["observation_count"] for item in windows),
            "full_windows_without_observations": sum(not item["observation_count"]
                and item["end_ms"] - item["start_ms"] == window_ms for item in windows),
            "empty_bin_duration_ms": sum(item["end_ms"] - item["start_ms"]
                for item in windows if not item["observation_count"]),
            "windows": windows,
            "interpretation": "Timestamp density only; isolated sampled frames do not prove continuous visual coverage."}
