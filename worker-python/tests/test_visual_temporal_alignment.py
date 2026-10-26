"""Temporal evidence routing and mocked request contracts, not recognition quality."""

from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from pathlib import Path

import httpx
import pytest
from livecut import segmentation
from livecut.contracts import validate_analysis_result


def utterance(uid: str, start: int, end: int) -> dict:
    return {"utterance_id": uid, "start_ms": start, "end_ms": end,
            "text": f"synthetic speech {uid}", "source_chunk_id": "synthetic-asr"}


def frame(fid: str, timestamp: int) -> dict:
    return {"frame_id": fid, "timestamp_ms": timestamp,
            "object_key": f"private-frames/{fid}.jpg", "presented_category": None,
            "observed_text": f"synthetic OCR {fid}", "visibility": "clear"}


def window_for(items: list[dict], duration: int = 20_000) -> dict:
    windows = segmentation._windows(items, duration, duration, 0)
    assert len(windows) == 1
    return windows[0]


def classified(*ids: str) -> dict:
    return {"products": [], "assignments": {
        uid: {"content_kind": "non_product", "product_labels": []} for uid in ids}}


def mock_client(*responses: dict) -> tuple[httpx.Client, list[dict]]:
    pending = list(responses)
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "unit.invalid"
        assert request.headers["authorization"] == "Bearer unit-test-secret"
        requests.append(json.loads(request.content))
        if not pending:
            pytest.fail("unexpected additional model request")
        response = {"output": {"choices": [{"finish_reason": "stop", "message": {
            "role": "assistant", "content": json.dumps(pending.pop(0))}}]},
            "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}
        return httpx.Response(200, json=response)

    return httpx.Client(transport=httpx.MockTransport(handler)), requests


def run(items: list[dict], duration: int, directory: Path, client: httpx.Client, **kwargs) -> dict:
    return segmentation.discover_products(
        items, duration, directory, client=client, api_key="unit-test-secret",
        base_url="https://unit.invalid/api/v1", **kwargs)


def user_payloads(requests: list[dict]) -> list[dict]:
    return [json.loads(request["input"]["messages"][1]["content"]) for request in requests]


def test_alignment_policy_is_explicit_and_bounded() -> None:
    assert segmentation.VISUAL_ALIGNMENT_VERSION == "window-time-and-utterance-gap-v1"
    assert segmentation.VISUAL_MAX_GAP_MS == 5000


def test_skipped_empty_cores_keep_nominal_real_time_and_do_not_route_zero_frame() -> None:
    items = [utterance("late", 600_000, 601_000)]
    windows = segmentation._windows(items, 900_000, 180_000, 30_000)
    assert len(windows) == 1
    assert {key: windows[0][key] for key in (
        "core_start_ms", "core_end_ms", "context_start_ms", "context_end_ms")} == {
            "core_start_ms": 540_000, "core_end_ms": 720_000,
            "context_start_ms": 510_000, "context_end_ms": 750_000}
    assert windows[0]["core_ids"] == ["late"]
    assert segmentation._visual_payload([frame("zero", 0)], windows[0]) == []


def test_window_context_clamps_to_source_and_midpoint_ownership_stays_once() -> None:
    items = [utterance("early", 1000, 2000), utterance("cross", 170_000, 190_000),
             utterance("last", 299_000, 300_000)]
    windows = segmentation._windows(items, 300_000, 180_000, 30_000)
    assert [window["core_ids"] for window in windows] == [["early"], ["cross", "last"]]
    assert [(window["core_start_ms"], window["core_end_ms"],
             window["context_start_ms"], window["context_end_ms"]) for window in windows] == [
                 (0, 180_000, 0, 210_000), (180_000, 300_000, 150_000, 300_000)]


def test_long_visible_utterance_cannot_pull_frames_outside_half_open_context() -> None:
    items = [utterance("long-crossing", 450_000, 800_000)]
    windows = segmentation._windows(items, 900_000, 180_000, 30_000)
    assert len(windows) == 1
    observations = [frame("before", 509_999), frame("start", 510_000),
                    frame("last-inside", 749_999), frame("end", 750_000)]
    original = deepcopy((items, observations, windows))
    payload = segmentation._visual_payload(observations, windows[0])
    assert [row["frame_id"] for row in payload] == ["start", "last-inside"]
    assert [row["timestamp_ms"] for row in payload] == [510_000, 749_999]
    assert all(row["near_utterance_id"] == "long-crossing" for row in payload)
    assert all(row["near_utterance_gap_ms"] == 0 for row in payload)
    assert (items, observations, windows) == original


def test_frame_inside_long_sentence_is_local_despite_distant_midpoint() -> None:
    window = window_for([utterance("long", 0, 300_000)], 360_000)
    payload = segmentation._visual_payload([frame("near-start", 1000)], window)
    assert len(payload) == 1
    assert payload[0]["near_utterance_id"] == "long"
    assert payload[0]["near_utterance_gap_ms"] == 0


@pytest.mark.parametrize("timestamp,expected_gap", [
    (4999, None), (5000, 5000), (5001, 4999),
    (10_500, 0), (16_000, 5000), (16_001, None),
])
def test_nearby_speech_gap_limit_includes_exactly_five_seconds(
    timestamp: int, expected_gap: int | None,
) -> None:
    payload = segmentation._visual_payload(
        [frame("sample", timestamp)], window_for([utterance("speech", 10_000, 11_000)]))
    if expected_gap is None:
        assert payload == []
    else:
        assert len(payload) == 1
        assert payload[0]["near_utterance_gap_ms"] == expected_gap


def test_nearest_speech_is_selected_from_this_windows_visible_transcript() -> None:
    items = [utterance("outside", 149_000, 150_000),
             utterance("visible", 154_000, 155_000), utterance("core", 185_000, 186_000)]
    window = next(window for window in segmentation._windows(items, 400_000, 180_000, 30_000)
                  if window["core_start_ms"] == 180_000)
    assert "outside" not in [row["utterance_id"] for row in window["visible"]]
    payload = segmentation._visual_payload([frame("context-start", 150_000)], window)
    assert payload[0]["near_utterance_id"] == "visible"
    assert payload[0]["near_utterance_gap_ms"] == 4000


def test_shared_sentence_boundary_prefers_sentence_containing_frame() -> None:
    window = window_for([utterance("a-previous", 0, 10_000),
                         utterance("z-current", 10_000, 20_000)])
    payload = segmentation._visual_payload([frame("switch", 10_000)], window)
    assert payload[0]["near_utterance_id"] == "z-current"
    assert payload[0]["near_utterance_gap_ms"] == 0


def test_equal_gaps_prefer_closer_midpoint_before_utterance_id() -> None:
    window = window_for([utterance("a-earlier", 0, 2000),
                         utterance("z-later", 4000, 4500)])
    payload = segmentation._visual_payload([frame("between", 3000)], window)
    assert payload[0]["near_utterance_id"] == "z-later"
    assert payload[0]["near_utterance_gap_ms"] == 1000


def test_final_tie_is_stable_by_id_even_if_visible_order_changes() -> None:
    window = window_for([utterance("z-earlier", 1000, 2000),
                         utterance("a-later", 4000, 5000)])
    before = deepcopy(window)
    first = segmentation._visual_payload([frame("between", 3000)], window)
    assert window == before
    reversed_window = deepcopy(window)
    reversed_window["visible"].reverse()
    assert segmentation._visual_payload([frame("between", 3000)], reversed_window) == first
    assert first[0]["near_utterance_id"] == "a-later"


def test_empty_asr_and_empty_visible_window_have_no_model_request(tmp_path: Path) -> None:
    observations = [frame("unattached", 0)]
    window = {"visible": [], "core_ids": [], "core_start_ms": 0, "core_end_ms": 1000,
              "context_start_ms": 0, "context_end_ms": 1000}
    assert segmentation._windows([], 1000, 180_000, 30_000) == []
    assert segmentation._visual_payload(observations, window) == []
    client, requests = mock_client()
    with client:
        result = run([], 1000, tmp_path, client, visual_observations=observations, max_calls=0)
    assert requests == [] and result["segments"] == []
    assert result["frames"] == [{key: observations[0][key]
                                 for key in ("frame_id", "timestamp_ms", "object_key")}]
    validate_analysis_result(result)


def test_both_model_steps_receive_actual_times_and_only_local_frames_and_resume(
    tmp_path: Path,
) -> None:
    items = [utterance("late", 600_000, 601_000)]
    observations = [frame("early", 0), frame("local", 600_500), frame("distant", 620_000)]
    before = deepcopy((items, observations))
    client, requests = mock_client({"products": []}, classified("late"))
    with client:
        first = run(items, 900_000, tmp_path, client, visual_observations=observations,
                    two_pass=True, max_calls=2)
        assert run(items, 900_000, tmp_path, client, visual_observations=observations,
                   two_pass=True, max_calls=2) == first
    assert len(requests) == 2
    payloads = user_payloads(requests)
    expected_context = {"core_start_ms": 540_000, "core_end_ms": 720_000,
                        "context_start_ms": 510_000, "context_end_ms": 750_000}
    for payload in payloads:
        assert payload["time_context"] == expected_context
        assert payload["transcript"] == [{"utterance_id": "late", "start_ms": 600_000,
                                          "end_ms": 601_000, "text": items[0]["text"]}]
        assert payload["visual_observations"] == [{
            "frame_id": "local", "timestamp_ms": 600_500, "presented_category": None,
            "observed_text": "synthetic OCR local", "visibility": "clear",
            "near_utterance_id": "late", "near_utterance_gap_ms": 0}]
        assert "object_key" not in json.dumps(payload)
        assert "private-frames/" not in json.dumps(payload)
    assert payloads[0]["visual_observations"] == payloads[1]["visual_observations"]
    assert [row["frame_id"] for row in first["frames"]] == ["early", "local", "distant"]
    assert (items, observations) == before
    validate_analysis_result(first)


def test_skipped_core_windows_do_not_shift_model_time_context(tmp_path: Path) -> None:
    items = [utterance("first", 1000, 2000), utterance("late", 600_000, 601_000)]
    observations = [frame("first-frame", 1500), frame("late-frame", 600_500)]
    client, requests = mock_client({"products": []}, classified("first"),
                                   {"products": []}, classified("late"))
    with client:
        run(items, 900_000, tmp_path, client, visual_observations=observations,
            two_pass=True, max_calls=4)
    payloads = user_payloads(requests)
    assert [payload["time_context"]["core_start_ms"] for payload in payloads] == [
        0, 0, 540_000, 540_000]
    assert [payload["visual_observations"][0]["frame_id"] for payload in payloads] == [
        "first-frame", "first-frame", "late-frame", "late-frame"]


@pytest.mark.parametrize("field,value", [
    ("VISUAL_ALIGNMENT_VERSION", "future-alignment-test-fixture"),
    ("VISUAL_MAX_GAP_MS", 5001),
])
def test_alignment_rules_are_frozen_in_checkpoint_identity(
    tmp_path: Path, monkeypatch, field: str, value: str | int,
) -> None:
    items = [utterance("u", 0, 1000)]
    observations = [frame("local", 500)]
    client, requests = mock_client(classified("u"))
    with client:
        run(items, 1000, tmp_path, client, visual_observations=observations)
    state_path = tmp_path / "segmentation/state.json"
    old_state = state_path.read_bytes()
    old_raw = (tmp_path / "segmentation/window-0000-attempt-0.json").read_bytes()
    monkeypatch.setattr(segmentation, field, value)
    no_call, unexpected = mock_client()
    with no_call, pytest.raises(segmentation.SegmentationError) as caught:
        run(items, 1000, tmp_path, no_call, visual_observations=observations)
    assert caught.value.code == "INVALID_CHECKPOINT"
    assert len(requests) == 1 and unexpected == []
    assert state_path.read_bytes() == old_state
    assert (tmp_path / "segmentation/window-0000-attempt-0.json").read_bytes() == old_raw


def test_genuine_v11_1_checkpoint_without_alignment_rules_is_refused_without_request(
    tmp_path: Path,
) -> None:
    items = [utterance("u", 0, 1000)]
    # Frozen legacy configuration shape from v11.1, before time alignment existed.
    legacy_config = {
        "duration_ms": 1000, "utterances": items,
        "endpoint": "https://unit.invalid/api/v1/services/aigc/text-generation/generation",
        "model": "qwen-plus", "window_ms": 180_000, "context_ms": 30_000,
        "merge_gap_ms": 10_000, "prompt_version": "catalog-free-v11.1-context-feedback",
        "video_id": "video-local", "job_id": "job-local",
        "output_protocol": "keyed-classifications-v2", "asr_model_id": "unknown-asr",
        "transcript_version": "asr-v1", "analysis_generation": 1, "grouping": "category",
        "visual_observations": [], "visual_model_id": None, "two_pass": False,
    }
    # Exact _json serializer from a27aa16 (default spaces are part of its hash).
    legacy_serialized = json.dumps(
        legacy_config, ensure_ascii=False, sort_keys=True, allow_nan=False)
    legacy_hash = hashlib.sha256(legacy_serialized.encode()).hexdigest()
    # Verified against a27aa16's actual config writer with an HTTP MockTransport;
    # this is a synthetic compatibility vector, not a legacy model replay.
    assert legacy_hash == "d83d8796dc9d37793d39f13e40ce2a7accf21daf19a87f9131fe272e0c78370d"
    directory = tmp_path / "segmentation"
    directory.mkdir()
    state_path = directory / "state.json"
    old_state = json.dumps({"config_hash": legacy_hash, "calls": [], "completed_windows": []})
    state_path.write_text(old_state, encoding="utf-8")
    raw_path = directory / "window-0000-attempt-0.json"
    raw_path.write_text('{"synthetic_legacy_raw":true}', encoding="utf-8")
    old_raw = raw_path.read_bytes()
    client, requests = mock_client()
    with client, pytest.raises(segmentation.SegmentationError) as caught:
        run(items, 1000, tmp_path, client)
    assert caught.value.code == "INVALID_CHECKPOINT" and requests == []
    assert state_path.read_text(encoding="utf-8") == old_state
    assert raw_path.read_bytes() == old_raw
    assert not (tmp_path / "analysis-result.json").exists()
