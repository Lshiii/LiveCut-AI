"""Bounded requested OCR timestamps; these are not observed frame times."""

from .vision import VisionError


def sampling_timestamps(
    duration_ms: int, interval_ms: int, max_frames: int, sampling_policy: str = "prefix",
) -> list[int]:
    """Keep historical prefix sampling or distribute the same cap across time.

    Uniform bin centers are sparse evidence, not continuous visual coverage.
    With one sample, t=0 avoids requesting a short clip's undecodable tail.
    """
    if any(type(value) is not int or value <= 0 for value in (duration_ms, interval_ms, max_frames)):
        raise VisionError("INVALID_CONFIGURATION", "OCR sampling values must be positive integers")
    if max_frames > 120:
        raise VisionError("INVALID_CONFIGURATION", "OCR frame budget cannot exceed 120")
    if not isinstance(sampling_policy, str) or sampling_policy not in {"prefix", "uniform"}:
        raise VisionError("INVALID_CONFIGURATION", "OCR sampling policy must be prefix or uniform")
    if sampling_policy == "prefix":
        return list(range(0, min(duration_ms, interval_ms * max_frames), interval_ms))
    count = min(max_frames, (duration_ms + interval_ms - 1) // interval_ms, duration_ms)
    if count == 1:
        return [0]
    return [(2 * index + 1) * duration_ms // (2 * count) for index in range(count)]
