"""Real local FFmpeg frames with explicitly synthetic VLM replies; no provider account."""
import base64
import copy
import hashlib
import json
import shutil
import subprocess

import httpx
import pytest
from livecut.agent_frames import (
    FrameStore,
    actual_position,
    jpeg_dimensions,
    validate_frame,
    visual_arguments,
)
from livecut.agent_visual import build_visual_answer, endpoint, validate_visual_answer
from livecut.segmentation import SegmentationError
from livecut.vision import VisionError


@pytest.fixture(scope="module")
def real_source(tmp_path_factory):
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        pytest.skip("Real FFmpeg tools are required for local visual media tests")
    directory = tmp_path_factory.mktemp("real-visual-source")
    source = directory / "source.mp4"
    subprocess.run([shutil.which("ffmpeg"), "-v", "error", "-nostdin", "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30",
                    "-t", "20", "-an", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(source)],
                   check=True, capture_output=True, timeout=60)
    return source


def source_items():
    return [{"utterance_id": "u1", "start_ms": 0, "end_ms": 1000, "text": "合成原句一", "source_chunk_id": "c0"},
            {"utterance_id": "u2", "start_ms": 1000, "end_ms": 18000, "text": "合成原句二，末尾有无语音画面", "source_chunk_id": "c0"}]


def topics():
    return [{"segment_id": "t1", "title": "合成话题", "summary": "仅测试机器合同", "start_utterance_id": "u1", "end_utterance_id": "u2"}]


def tool(name, **arguments):
    return {"type": "tool", "tool_name": name, "arguments": arguments}


def answer(ids=(), visual=(), scope="VIDEO"):
    return {"type": "answer", "answer_scope": scope, "answer": "合成视觉回答，不代表真实模型识别效果。", "citation_ids": list(ids),
            "visual_citations": [{"frame_id": item, "explanation": "引用实际送入的测试图片"} for item in visual], "suggested_clip_instruction": None}


def run(root, source, replies, **extra):
    root.mkdir(parents=True, exist_ok=True)
    seen = []
    def provider(request):
        seen.append(json.loads(request.content))
        assert request.headers["Authorization"] == "Bearer synthetic-independent-visual-key"
        assert request.url.path == "/v1/chat/completions"
        assert len(seen) <= len(replies), "Unexpected extra visual provider request"
        reply = replies[len(seen)-1]
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": json.dumps(reply, ensure_ascii=False)}}]})
    options = {"session_id": "session-1", "turn_id": "turn-1", "job_id": "job-1", "video_id": "video-1",
               "source_hash": hashlib.sha256(source.read_bytes()).hexdigest(), "transcript_hash": "a"*64, "model": "synthetic-vlm",
               "api_key": "synthetic-independent-visual-key", "base_url": "https://visual.invalid/v1", "max_calls": 8}
    options.update(extra)
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        result = build_visual_answer(source_items(), topics(), 20000, "屏幕上的图是什么？", root, source=source, client=client, **options)
    return result, seen


def image_parts(body):
    return [part for message in body["messages"] if isinstance(message["content"], list) for part in message["content"] if part.get("type") == "image_url"]


def test_actual_frames_enter_same_vlm_planner_with_pts_labels_and_replay(tmp_path, real_source, monkeypatch):
    replies = [tool("inspect_frames", timestamps_ms=[0, 500, 1000]), answer(visual=["f0001", "f0003"])]
    result, seen = run(tmp_path, real_source, replies)
    assert len(seen) == 2 and image_parts(seen[0]) == [] and len(image_parts(seen[1])) == 3
    assert result["schema_version"] == "1.1" and result["citations"] == []
    assert result["tool_calls"][0]["result_frame_ids"] == ["f0001", "f0002", "f0003"]
    for frame, part in zip(result["visual_evidence"], image_parts(seen[1]), strict=True):
        data = base64.b64decode(part["image_url"]["url"].split(",", 1)[1], validate=True)
        assert data == (tmp_path / "visual-frames" / (frame["frame_id"]+".jpg")).read_bytes()
        assert hashlib.sha256(data).hexdigest() == frame["sha256"]
        assert jpeg_dimensions(data) == (frame["width"], frame["height"])
        assert frame["timestamp_ms"] == frame["requested_ms"]
        assert any(item.get("type") == "text" and f"FRAME {frame['frame_id']} actual_timestamp_ms={frame['timestamp_ms']}" in item["text"] for item in seen[1]["messages"][1]["content"])
    validate_visual_answer(result, source_items(), duration_ms=20000, expected_frames=result["visual_evidence"])
    monkeypatch.setattr("livecut.agent_frames._extract_frame", lambda *a, **k: pytest.fail("Cached frame was regenerated"))
    again, calls = run(tmp_path, real_source, [])
    assert again == result and calls == []


def test_sampled_clip_has_24_actual_images_no_audio_and_does_not_claim_continuous_video(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [tool("inspect_clip", start_ms=0, end_ms=12000), answer(visual=["f0001", "f0024"])])
    assert len(result["visual_evidence"]) == len(image_parts(seen[-1])) == 24
    assert [frame["requested_ms"] for frame in result["visual_evidence"]] == list(range(0, 12000, 500))
    observation = json.loads(seen[-1]["messages"][1]["content"][0]["text"])["observations"][0]["result"]
    assert observation["sampling"] == "2fps" and observation["audio_included"] is False
    assert "不是连续观看" in seen[0]["messages"][0]["content"]


def test_visual_mode_asr_only_still_publishes_1_1_and_no_fabricated_frames(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [tool("read_context", utterance_id="u1", before=0, after=0), answer(ids=["u1"])])
    assert result["schema_version"] == "1.1" and result["visual_evidence"] == result["visual_citations"] == []
    assert result["tool_calls"][0]["result_frame_ids"] == [] and all(not image_parts(body) for body in seen)


@pytest.mark.parametrize("scope", ["GENERAL", "INSUFFICIENT"])
def test_general_and_insufficient_do_not_invent_visual_evidence(tmp_path, real_source, scope):
    result, seen = run(tmp_path, real_source, [answer(scope=scope)])
    assert len(seen) == 1 and result["answer_scope"] == scope and not result["visual_evidence"]


def test_cumulative_frame_cap_and_repeated_position_cache(tmp_path, real_source):
    store = FrameStore(real_source, tmp_path, hashlib.sha256(real_source.read_bytes()).hexdigest(), 20000)
    first = store.inspect("inspect_clip", {"start_ms": 0, "end_ms": 12000})
    assert store.inspect("inspect_frames", {"timestamps_ms": [0, 500]}) == first[:2]
    store.inspect("inspect_frames", {"timestamps_ms": [12000, 12500, 13000, 13500]})
    store.inspect("inspect_frames", {"timestamps_ms": [14000, 14500, 15000, 15500]})
    with pytest.raises(VisionError, match="unique requested-frame cap"):
        store.inspect("inspect_frames", {"timestamps_ms": [16000]})
    assert len(store.frames) == 32


def test_duplicate_complete_tool_is_explicit_empty_observation_not_reextraction(tmp_path, real_source):
    call = tool("inspect_frames", timestamps_ms=[0])
    result, seen = run(tmp_path, real_source, [call, call, answer(visual=["f0001"])])
    assert len(result["visual_evidence"]) == 1 and result["tool_calls"][1]["result_frame_ids"] == []
    assert json.loads(seen[-1]["messages"][1]["content"][0]["text"])["observations"][1]["result"]["error"] == "DUPLICATE_TOOL_CALL"


def test_actual_duplicate_pts_and_out_of_halfopen_window_return_tool_errors_atomically(tmp_path, real_source):
    store = FrameStore(real_source, tmp_path, hashlib.sha256(real_source.read_bytes()).hexdigest(), 20000)
    with pytest.raises(VisionError, match="same source frame"):
        store.inspect("inspect_frames", {"timestamps_ms": [1, 2]})
    assert store.frames == [] and list(store.directory.iterdir()) == []
    with pytest.raises(VisionError, match="outside the sampled interval"):
        store.inspect("inspect_clip", {"start_ms": 1, "end_ms": 2})
    assert store.frames == []


def test_changed_frame_cache_cannot_regenerate_or_dispatch(tmp_path, real_source):
    run(tmp_path, real_source, [tool("inspect_frames", timestamps_ms=[0]), answer(visual=["f0001"])])
    (tmp_path / "visual-frames/f0001.jpg").write_bytes(b"changed")
    with pytest.raises(SegmentationError, match="Cached visual evidence changed"):
        run(tmp_path, real_source, [])


@pytest.mark.parametrize("failure", ["index", "second_jpeg"])
def test_frame_publication_failure_stops_before_another_model_call(tmp_path, real_source, monkeypatch, failure):
    if failure == "index":
        monkeypatch.setattr("livecut.agent_frames._save", lambda *args: (_ for _ in ()).throw(OSError("synthetic full disk")))
    else:
        from livecut import agent_frames
        real_replace = agent_frames.os.replace
        def interrupted(source, destination):
            if str(destination).endswith("f0002.jpg"):
                raise OSError("synthetic interrupted rename")
            return real_replace(source, destination)
        monkeypatch.setattr(agent_frames.os, "replace", interrupted)
    with pytest.raises(SegmentationError, match="publication was interrupted"):
        # The fake transport fails if a second call escapes this one-reply plan.
        run(tmp_path, real_source, [tool("inspect_frames", timestamps_ms=[0, 500])])


def test_math_consistent_changed_cached_pts_cannot_replay_frozen_model_request(tmp_path, real_source):
    run(tmp_path, real_source, [tool("inspect_frames", timestamps_ms=[0]), answer(visual=["f0001"])])
    path = tmp_path / "visual-frame-cache.json"
    cache = json.loads(path.read_text(encoding="utf-8"))
    # 1/15360 seconds still rounds to zero ms; the math validator alone cannot
    # prove this was the observed frame. The immutable request input must bind it.
    assert cache["frames"][0]["source_time_base"] == "1/15360"
    cache["frames"][0]["source_pts"] = 1
    validate_frame(cache["frames"][0], 20000)
    path.write_text(json.dumps(cache), encoding="utf-8")
    with pytest.raises(SegmentationError, match="media or evidence metadata changed"):
        run(tmp_path, real_source, [])


def test_visual_trace_rejects_nonempty_duplicate_and_out_of_first_observation_order(tmp_path, real_source):
    result, _ = run(tmp_path, real_source, [tool("inspect_frames", timestamps_ms=[0]),
        tool("inspect_frames", timestamps_ms=[500]), answer(visual=["f0001", "f0002"])])
    duplicate = copy.deepcopy(result)
    duplicate["tool_calls"].append({**copy.deepcopy(duplicate["tool_calls"][0]), "step": 3})
    with pytest.raises(SegmentationError):
        validate_visual_answer(duplicate, source_items(), duration_ms=20000)
    reordered = copy.deepcopy(result)
    reordered["tool_calls"][0].update(arguments={"timestamps_ms": [500]}, result_frame_ids=["f0002"])
    reordered["tool_calls"][1].update(arguments={"timestamps_ms": [0]}, result_frame_ids=["f0001"])
    with pytest.raises(SegmentationError):
        validate_visual_answer(reordered, source_items(), duration_ms=20000)


def test_different_clip_endpoints_can_reuse_same_complete_sample_positions(tmp_path, real_source):
    result, seen = run(tmp_path, real_source, [tool("inspect_clip", start_ms=0, end_ms=501),
        tool("inspect_clip", start_ms=0, end_ms=502), answer(visual=["f0001"])])
    assert [step["result_frame_ids"] for step in result["tool_calls"]] == [["f0001", "f0002"]]*2
    assert len(result["visual_evidence"]) == len(image_parts(seen[-1])) == 2
    validate_visual_answer(result, source_items(), duration_ms=20000)


@pytest.mark.parametrize("mutate", [
    lambda value: value["visual_evidence"][0].update(timestamp_ms=1),
    lambda value: value["visual_evidence"][0].update(source_pts=1),
    lambda value: value["visual_evidence"][0].update(source_time_base="1/10000000000"),
    lambda value: value["visual_evidence"][0].update(path="private.jpg"),
    lambda value: value["visual_evidence"][0].update(bytes=3),
    lambda value: value["visual_citations"][0].update(frame_id="f0002"),
    lambda value: value["tool_calls"][0].update(result_utterance_ids=["u1"]),
    lambda value: value["tool_calls"][0].update(result_frame_ids=[]),
])
def test_source_frame_trace_forgeries_are_rejected(tmp_path, real_source, mutate):
    result, _ = run(tmp_path, real_source, [tool("inspect_frames", timestamps_ms=[0]), answer(visual=["f0001"])])
    original_frames = copy.deepcopy(result["visual_evidence"])
    mutate(result)
    with pytest.raises(SegmentationError, match="strict source and frame"):
        validate_visual_answer(result, source_items(), duration_ms=20000, expected_frames=original_frames)


@pytest.mark.parametrize("base", ["http://unsafe.invalid", "https://secret@provider.invalid", "https://provider.invalid?key=x", "https://provider.invalid/api/v1/services/aigc/multimodal-generation/generation"])
def test_visual_endpoint_never_uses_legacy_native_unmetered_path(base):
    with pytest.raises(SegmentationError):
        endpoint(base)


def test_visual_endpoint_ignores_text_api_style_and_missing_key_does_not_fallback(tmp_path, real_source, monkeypatch):
    monkeypatch.setenv("LIVECUT_TEXT_API_STYLE", "dashscope")
    monkeypatch.setenv("LIVECUT_TEXT_API_KEY", "must-not-use-text-key")
    assert endpoint("https://visual.invalid/v1") == "https://visual.invalid/v1/chat/completions"
    with pytest.raises(SegmentationError, match="independent visual API key"):
        run(tmp_path, real_source, [], api_key="")


def test_visual_request_budget_shrinks_tools_to_leave_final_response(tmp_path, real_source):
    values = iter([3, 1])
    result, seen = run(tmp_path, real_source, [tool("inspect_frames", timestamps_ms=[0]), answer(visual=["f0001"])], budget_remaining=lambda: next(values), max_request_attempts=2)
    payloads = [json.loads(body["messages"][1]["content"][0]["text"]) for body in seen]
    assert [value["remaining_tool_steps"] for value in payloads] == [1, 0]
    assert result["visual_citations"][0]["frame_id"] == "f0001"


@pytest.mark.parametrize("args", [{"timestamps_ms": [True]}, {"timestamps_ms": [2, 1]}, {"timestamps_ms": [0, 0]}, {"timestamps_ms": [0, 20000]}, {"timestamps_ms": list(range(5))}])
def test_invalid_frame_tool_arguments_never_extract(args):
    with pytest.raises(ValueError):
        visual_arguments("inspect_frames", args, 20000)


def test_rational_pts_can_differ_while_rounded_milliseconds_match():
    base = {"frame_id": "f0001", "requested_ms": 999, "timestamp_ms": 1000, "source_pts": 9997, "source_time_base": "1/10000", "sha256": "a"*64, "bytes": 4, "width": 1, "height": 1}
    other = {**base, "frame_id": "f0002", "requested_ms": 1000, "source_pts": 10001}
    validate_frame(base, 2000)
    validate_frame(other, 2000)
    assert actual_position(base) != actual_position(other)


def test_synthetic_submillisecond_boundary_uses_exact_pts_for_sampled_clip(tmp_path, real_source, monkeypatch):
    from livecut import agent_frames
    extract = agent_frames._extract_frame
    def controlled_metadata(*args, **kwargs):
        # Only the boundary metadata is synthetic; the bounded JPEG is still
        # produced by real FFmpeg. This test is not a measured PTS of that JPEG.
        extract(*args, **kwargs)
        return {"timestamp_ms": 1500, "source_pts": 14999, "source_time_base": "1/10000"}
    monkeypatch.setattr(agent_frames, "_extract_frame", controlled_metadata)
    result, _ = run(tmp_path, real_source, [tool("inspect_clip", start_ms=1000, end_ms=1500), answer(visual=["f0001"])])
    frame = result["visual_evidence"][0]
    assert frame["timestamp_ms"] == 1500 and actual_position(frame)*1000 < 1500
    validate_visual_answer(result, source_items(), duration_ms=20000, expected_frames=[frame])
