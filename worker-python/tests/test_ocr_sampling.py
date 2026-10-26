"""Synthetic frame sampling/cache regressions, not evidence of actual OCR quality."""

import hashlib
import json
from pathlib import Path

import pytest
from livecut.ocr import observe_video_ocr
from livecut.ocr_sampling import sampling_timestamps
from livecut.vision import VisionError
from test_ocr_cache import (
    artifact,
    change_artifact,
    digest,
    entry,
    json_mutation,
    manifest,
    published,
    save_json,
)
from test_ocr_cache import (
    fixture as fixture,
)
from test_ocr_cache import (
    run as cache_run,
)


def test_thirty_minute_uniform_plan_samples_sixteen_bin_centers_including_the_late_video():
    timestamps = sampling_timestamps(1800000, 45000, 16, sampling_policy="uniform")
    assert timestamps == [56250, 168750, 281250, 393750, 506250, 618750, 731250,
                          843750, 956250, 1068750, 1181250, 1293750, 1406250,
                          1518750, 1631250, 1743750]
    assert timestamps[-1] > 29 * 60000
    assert sampling_timestamps(1800000, 45000, 16) == list(range(0, 720000, 45000))


@pytest.mark.parametrize("duration,interval,maximum,expected", [
    (1, 45000, 16, [0]),
    (1000, 45000, 16, [0]),
    (1800000, 45000, 1, [0]),
    (2, 1, 16, [0, 1]),
    (3, 1, 120, [0, 1, 2]),
    (5, 2, 16, [0, 2, 4]),
    (9, 3, 16, [1, 4, 7]),
    (10, 3, 16, [1, 3, 6, 8]),
    (11, 3, 16, [1, 4, 6, 9]),
    (500, 500, 16, [0]),
    (501, 500, 16, [125, 375]),
    (999, 500, 16, [249, 749]),
    (1000, 500, 16, [250, 750]),
    (1001, 500, 16, [166, 500, 834]),
])
def test_uniform_integer_centers_handle_single_frames_short_media_and_ceil_edges(
    duration, interval, maximum, expected,
):
    assert sampling_timestamps(duration, interval, maximum, sampling_policy="uniform") == expected


def test_uniform_plans_stay_unique_sorted_in_bounds_and_under_every_budget():
    for duration in (1, 2, 3, 5, 120, 121, 999, 1000, 1001, 3000, 1800000):
        for interval in (1, 2, 7, 500, 45000):
            for maximum in (1, 2, 3, 16, 120):
                timestamps = sampling_timestamps(duration, interval, maximum, sampling_policy="uniform")
                expected_count = min(maximum, (duration + interval - 1) // interval, duration)
                assert len(timestamps) == expected_count
                assert timestamps == sorted(set(timestamps))
                assert all(type(value) is int and 0 <= value < duration for value in timestamps)
                if expected_count == 1:
                    assert timestamps == [0]
                else:
                    # Independently check that each selected millisecond is the
                    # integer containing that bin's rational center.
                    for index, timestamp in enumerate(timestamps):
                        center_numerator = (2 * index + 1) * duration
                        assert 2 * expected_count * timestamp <= center_numerator
                        assert center_numerator < 2 * expected_count * (timestamp + 1)


@pytest.mark.parametrize("duration,interval,maximum", [
    (1, 1, 1), (5, 2, 120), (999, 500, 16), (1000, 500, 16),
    (1001, 500, 16), (1800000, 45000, 16), (1800000, 45000, 120),
])
def test_prefix_default_and_explicit_policy_preserve_the_legacy_range_exactly(
    duration, interval, maximum,
):
    expected = list(range(0, min(duration, interval * maximum), interval))
    assert sampling_timestamps(duration, interval, maximum) == expected
    assert sampling_timestamps(duration, interval, maximum, sampling_policy="prefix") == expected


@pytest.mark.parametrize("duration,interval,maximum,policy", [
    (True, 500, 16, "uniform"), (1000.0, 500, 16, "uniform"), (0, 500, 16, "uniform"),
    (-1, 500, 16, "uniform"), (1000, False, 16, "uniform"), (1000, 1.5, 16, "uniform"),
    (1000, 0, 16, "uniform"), (1000, 500, True, "uniform"), (1000, 500, 16.0, "uniform"),
    (1000, 500, 0, "uniform"), (1000, 500, 121, "uniform"), (1000, 500, 16, None),
    (1000, 500, 16, True), (1000, 500, 16, ""), (1000, 500, 16, "UNIFORM"),
    (1000, 500, 16, "uniform "), (1000, 500, 16, "api"), (1000, 500, 16, ["uniform"]),
])
def test_invalid_types_budgets_or_sampling_policies_fail_with_a_safe_configuration_error(
    duration, interval, maximum, policy,
):
    with pytest.raises(VisionError) as failure:
        sampling_timestamps(duration, interval, maximum, sampling_policy=policy)
    assert failure.value.code == "INVALID_CONFIGURATION"


def test_invalid_policy_fails_before_optional_runtime_or_media_access(tmp_path):
    with pytest.raises(VisionError) as failure:
        observe_video_ocr(Path("missing-synthetic-source.mp4"), tmp_path / "invalid",
                          duration_ms=1000, sampling_policy="unknown")
    assert failure.value.code == "INVALID_CONFIGURATION"


def test_uniform_ocr_preserves_requested_times_and_reports_actual_frame_pts_separately(fixture):
    directory = fixture.root / "uniform-observer"
    observations = observe_video_ocr(
        fixture.source, directory, duration_ms=1200, interval_ms=500, max_frames=3,
        ffmpeg_path=str(fixture.ffmpeg), engine=fixture.engine, extractor=fixture.extractor,
        sampling_policy="uniform")
    assert fixture.extractor.calls == [200, 600, 1000]
    assert fixture.engine.calls == 3
    assert [item["timestamp_ms"] for item in observations] == [203, 603, 1003]
    frames = json.loads((directory / "frames.json").read_bytes())
    assert [item["requested_timestamp_ms"] for item in frames] == [200, 600, 1000]
    assert [item["timestamp_ms"] for item in frames] == [203, 603, 1003]
    assert [item["source_pts"] for item in frames] == [203, 603, 1003]
    assert all(item["source_time_base"] == "1/1000" for item in frames)
    state = json.loads((directory / "ocr-state.json").read_bytes())
    assert state["coverage_truncated"] is False
    assert state["input"] == {"source_sha256": digest(fixture.source), "duration_ms": 1200,
                               "interval_ms": 500, "max_frames": 3,
                               "model_id": "custom-ocr-engine", "sampling_policy": "uniform"}
    assert state["frames"] == frames and state["observations"] == observations


def test_uniform_plan_is_not_prefix_truncated_but_still_has_only_sixteen_observations(fixture):
    result = cache_run(fixture, "long-uniform", duration_ms=1800000, interval_ms=45000,
                       max_frames=16, sampling_policy="uniform")
    assert result.provenance["coverage_truncated"] is False
    assert result.provenance["sampling_policy"] == "uniform"
    assert result.provenance["frame_count"] == 16
    assert len(result.observations) == 16 and fixture.engine.calls == 16
    assert fixture.extractor.calls[-1] == 1743750
    assert result.observations[-1]["timestamp_ms"] == 1743753


@pytest.mark.parametrize("first,second", [("prefix", "uniform"), ("uniform", "prefix")])
def test_attempt_local_ocr_refuses_to_resume_a_different_sampling_policy(fixture, first, second):
    directory = fixture.root / "one-attempt"
    options = {"duration_ms": 1200, "interval_ms": 500, "max_frames": 3,
               "ffmpeg_path": str(fixture.ffmpeg), "engine": fixture.engine,
               "extractor": fixture.extractor}
    observe_video_ocr(fixture.source, directory, sampling_policy=first, **options)
    before = {path: path.read_bytes() for path in directory.rglob("*") if path.is_file()}
    requested = list(fixture.extractor.calls)
    with pytest.raises(VisionError) as failure:
        observe_video_ocr(fixture.source, directory, sampling_policy=second, **options)
    assert failure.value.code == "CHECKPOINT_INPUT_MISMATCH"
    assert fixture.engine.calls == 3 and fixture.extractor.calls == requested
    assert {path: path.read_bytes() for path in before} == before


def test_prefix_retains_the_previous_binding_state_provenance_shapes_and_cache_key(fixture):
    result = cache_run(fixture, "old-prefix")
    legacy = {"source_sha256": digest(fixture.source), "duration_ms": 1200, "interval_ms": 500,
              "max_frames": 3, "sampling": "prefix-at-fixed-interval-v1",
              "observer": "rapidocr-prefix-observer-v1-score045-text2000",
              "ffmpeg": {"sha256": digest(fixture.ffmpeg), "size_bytes": fixture.ffmpeg.stat().st_size,
                         "profile": "copyts-showinfo-v1-scale-max360x640-no-upscale"},
              "extractor_identity": "synthetic-extractor-v1",
              "runtime": {"kind": "custom", "model_id": "custom-ocr-engine",
                          "identity": "synthetic-engine-weights-v1"}}
    expected_key = hashlib.sha256(json.dumps(legacy, ensure_ascii=False, sort_keys=True,
                                              separators=(",", ":"), allow_nan=False).encode()).hexdigest()
    assert result.provenance["cache_key"] == expected_key
    assert manifest(fixture, result)["binding"] == legacy
    state = json.loads((fixture.root / "old-prefix/ocr-state.json").read_bytes())
    assert set(state) == {"input", "frames", "observations", "coverage_truncated"}
    assert state["input"] == {"source_sha256": digest(fixture.source), "duration_ms": 1200,
                               "interval_ms": 500, "max_frames": 3, "model_id": "custom-ocr-engine"}
    assert set(result.provenance) == {"schema_version", "cache_key", "cache_hit", "binding_sha256",
                                      "bundle_manifest_sha256", "frame_count", "coverage_truncated",
                                      "observer_id"}
    explicit = cache_run(fixture, "explicit-prefix", sampling_policy="prefix")
    assert explicit.provenance["cache_hit"] is True
    assert explicit.provenance["cache_key"] == expected_key
    assert explicit.observations == result.observations and fixture.engine.calls == 3


@pytest.mark.parametrize("duration,interval,maximum", [(1200, 500, 3), (100, 500, 16)])
def test_policies_have_distinct_shared_keys_even_when_their_single_timestamp_is_equal(
    fixture, duration, interval, maximum,
):
    options = {"duration_ms": duration, "interval_ms": interval, "max_frames": maximum}
    prefix = cache_run(fixture, "prefix", sampling_policy="prefix", **options)
    uniform = cache_run(fixture, "uniform", sampling_policy="uniform", **options)
    assert prefix.provenance["cache_key"] != uniform.provenance["cache_key"]
    assert len(published(fixture)) == 2
    assert "sampling_policy" not in prefix.provenance
    assert uniform.provenance["sampling_policy"] == "uniform"
    before = manifest(fixture, prefix)["binding"]
    after = manifest(fixture, uniform)["binding"]
    assert after == {**before, "sampling": "uniform-bin-centers-v1",
                     "observer": "rapidocr-uniform-observer-v1-score045-text2000"}
    assert set(uniform.provenance) == set(prefix.provenance) | {"sampling_policy"}


def test_uniform_hit_in_a_new_attempt_skips_engine_and_extraction_and_preserves_both_attempts(fixture):
    first = cache_run(fixture, "uniform-first", sampling_policy="uniform")
    files = {path: path.read_bytes() for path in entry(fixture, first).rglob("*") if path.is_file()}
    calls = list(fixture.extractor.calls)
    fixture.engine.fail_at = fixture.engine.calls + 1
    second = cache_run(fixture, "uniform-second", sampling_policy="uniform")
    assert second.provenance["cache_hit"] is True
    assert first.provenance["cache_key"] == second.provenance["cache_key"]
    assert second.observations == first.observations
    assert fixture.engine.calls == 3 and fixture.extractor.calls == calls
    assert {path: path.read_bytes() for path in files} == files
    for name in ("ocr-state.json", "frames.json", "observations.json"):
        assert (fixture.root / "uniform-first" / name).read_bytes() == (fixture.root / "uniform-second" / name).read_bytes()


@pytest.mark.parametrize("mutation", ["binding_sampling", "binding_observer", "state_policy",
                                          "missing_state_policy", "requested_timestamp"])
def test_uniform_sampling_or_requested_time_tampering_is_rejected_after_hash_resealing(fixture, mutation):
    result = cache_run(fixture, "uniform-seed", sampling_policy="uniform")
    if mutation in {"binding_sampling", "binding_observer"}:
        document = manifest(fixture, result)
        field = "sampling" if mutation == "binding_sampling" else "observer"
        document["binding"][field] = ("prefix-at-fixed-interval-v1" if field == "sampling"
                                       else "rapidocr-prefix-observer-v1-score045-text2000")
        save_json(entry(fixture, result) / "manifest.json", document)
    elif mutation == "state_policy":
        change_artifact(fixture, result, "/ocr-state.json", json_mutation(
            lambda state: state["input"].update(sampling_policy="prefix")))
    elif mutation == "missing_state_policy":
        change_artifact(fixture, result, "/ocr-state.json", json_mutation(
            lambda state: state["input"].pop("sampling_policy")))
    else:
        change_artifact(fixture, result, "/frames.json", json_mutation(
            lambda frames: frames[0].update(requested_timestamp_ms=0)))
        frames = json.loads(artifact(fixture, result, "/frames.json").read_bytes())
        change_artifact(fixture, result, "/ocr-state.json", json_mutation(
            lambda state: state.update(frames=frames)))
    with pytest.raises(VisionError) as failure:
        cache_run(fixture, "must-not-regenerate", sampling_policy="uniform")
    assert failure.value.code == "OCR_CACHE_CORRUPT"
    assert fixture.engine.calls == 3 and fixture.extractor.calls == [200, 600, 1000]


def test_prefix_cache_cannot_be_reinterpreted_by_inserting_a_uniform_state_marker(fixture):
    result = cache_run(fixture, "prefix-seed")
    change_artifact(fixture, result, "/ocr-state.json", json_mutation(
        lambda state: state["input"].update(sampling_policy="uniform")))
    with pytest.raises(VisionError) as failure:
        cache_run(fixture, "reject-inserted-strategy")
    assert failure.value.code == "OCR_CACHE_CORRUPT"
    assert fixture.engine.calls == 3


@pytest.mark.parametrize("changed_timestamp", [True, -1, 1200, 206],
                         ids=["boolean", "negative", "past-video-end", "pts-drift-over-one-ms"])
def test_uniform_attempt_checkpoint_rejects_equal_but_invalid_actual_timestamps(
    fixture, changed_timestamp,
):
    directory = fixture.root / "uniform-local-checkpoint"
    options = {"duration_ms": 1200, "interval_ms": 500, "max_frames": 3,
               "ffmpeg_path": str(fixture.ffmpeg), "engine": fixture.engine,
               "extractor": fixture.extractor, "sampling_policy": "uniform"}
    observe_video_ocr(fixture.source, directory, **options)
    state_path = directory / "ocr-state.json"
    state = json.loads(state_path.read_bytes())
    assert state["frames"][0]["source_pts"] == 203
    assert state["frames"][0]["source_time_base"] == "1/1000"
    # Corrupt both copies identically: comparing them to each other is not a
    # substitute for checking type/range and the independent source PTS.
    state["frames"][0]["timestamp_ms"] = changed_timestamp
    state["observations"][0]["timestamp_ms"] = changed_timestamp
    save_json(state_path, state)
    save_json(directory / "frames.json", state["frames"])
    save_json(directory / "observations.json", state["observations"])
    before = {path: path.read_bytes() for path in directory.rglob("*") if path.is_file()}

    with pytest.raises(VisionError) as failure:
        observe_video_ocr(fixture.source, directory, **options)
    assert failure.value.code == "INVALID_CHECKPOINT"
    assert fixture.engine.calls == 3 and fixture.extractor.calls == [200, 600, 1000]
    assert {path: path.read_bytes() for path in before} == before
