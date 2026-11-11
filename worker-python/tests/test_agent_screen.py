"""1.5 uses frozen synthetic OCR and model responses, never paid providers."""
import copy
import hashlib
import json
from dataclasses import replace
from pathlib import Path

import httpx
import pytest
from livecut.agent_evidence import is_js_blank
from livecut.agent_frames import FrameStore
from livecut.agent_screen import (
    build_screen_index,
    lookup_screen,
    observe_screen,
    screen_arguments,
    validate_screen_answer,
)
from livecut.agent_visual import _decision, build_visual_answer
from livecut.segmentation import SegmentationError
from livecut.text_resilience import TextRetryPolicy
from livecut.video_agent import AgentOutputError
from test_agent_evidence import block, evidence_ledger_client, reply
from test_agent_visual import real_source as _real_source
from test_agent_visual import source_items, tool, topics

real_source = _real_source
FIXTURE = json.loads((Path(__file__).resolve().parents[2] / "fixtures/agent-screen-search.synthetic.json").read_bytes())


def test_independent_cross_language_index_hash_matches_exact_source_rows():
    data = FIXTURE
    index = build_screen_index(data["rows"], source_hash=data["source_hash"], duration_ms=data["duration_ms"],
        segment_manifest_hash=data["screen_source"]["segment_manifest_hash"],
        frames_hash=data["screen_source"]["frames_hash"], observations_hash=data["screen_source"]["observations_hash"])
    assert index["index_hash"] == data["screen_source"]["index_hash"]


@pytest.mark.parametrize("case", FIXTURE["query_cases"], ids=lambda row: row["case_id"])
def test_shared_query_vectors(case):
    if not case["expected_valid"]:
        with pytest.raises(AgentOutputError):
            screen_arguments(case["arguments"])
        return
    result = lookup_screen({"rows": FIXTURE["rows"], "screen_source": FIXTURE["screen_source"]}, case["arguments"])
    assert result["total_match_count"] == case["expected_total_match_count"]
    assert [row["ocr_frame_id"] for row in result["rows"]] == case["expected_frame_ids"]


@pytest.mark.parametrize("case", FIXTURE["cases"], ids=lambda row: row["case_id"])
def test_shared_complete_screen_answer_vectors(case):
    value = copy.deepcopy(case["result"])
    original = copy.deepcopy(value)
    options = dict(expected={"screen_source": case["screen_source"]}, duration_ms=case["duration_ms"],
        screen_index={"rows": case["rows"], "screen_source": case["screen_source"]})
    if case["expected_valid"]:
        assert validate_screen_answer(value, FIXTURE["utterances"], **options) == original
    else:
        with pytest.raises(SegmentationError):
            validate_screen_answer(value, FIXTURE["utterances"], **options)
    assert value == original


def test_public_consumer_rejects_partial_observed_batch_even_with_consistent_citations():
    from livecut.agent_evidence import assemble
    case = next(case for case in FIXTURE["cases"] if case["case_id"] == "two_chronological_hits")
    result = copy.deepcopy(case["result"])
    result["screen_searches"][0]["hits"] = result["screen_searches"][0]["hits"][:1]
    keep = result["screen_searches"][0]["hits"][0]["frame_id"]
    result["tool_calls"][0]["result_frame_ids"] = [keep]
    result["visual_evidence"] = [frame for frame in result["visual_evidence"] if frame["frame_id"] == keep]
    for row in result["answer_blocks"]:
        row["frame_ids"] = [keep] if row["frame_ids"] else []
    result.update(assemble(result["answer_scope"], result["answer_blocks"], FIXTURE["utterances"], set(), result["visual_evidence"], result["tool_calls"]))
    with pytest.raises(SegmentationError):
        validate_screen_answer(result, FIXTURE["utterances"], duration_ms=case["duration_ms"])


def test_public_consumer_rejects_match_count_greater_than_nonblank_text_frames():
    result = copy.deepcopy(FIXTURE["cases"][0]["result"])
    result["screen_source"]["text_frame_count"] = 1
    result["screen_searches"][0]["total_match_count"] = 2
    with pytest.raises(SegmentationError):
        validate_screen_answer(result, FIXTURE["utterances"], duration_ms=result["screen_source"]["duration_ms"])


def runtime_index(source, *, rows=None):
    rows = rows or [{"ocr_frame_id": "ocr_000001", "requested_ms": 0, "ocr_timestamp_ms": 0,
                    "source_pts": 0, "source_time_base": "1/10240", "observed_text": "UNSPOKEN_SCREEN_TOKEN Cache",
                    "ocr_sha256": "a"*64}]
    index = build_screen_index(rows, source_hash=hashlib.sha256(source.read_bytes()).hexdigest(), duration_ms=20000,
        segment_manifest_hash="b"*64, frames_hash="c"*64, observations_hash="d"*64)
    index["screen_source"] = {"segment_stage_id": "11111111-1111-4111-8111-111111111111", "segment_manifest_hash": "b"*64,
        "frames_hash": "c"*64, "observations_hash": "d"*64, "index_version": "screen-text-literal-v1",
        "index_hash": index["index_hash"], "duration_ms": 20000, "sampling_policy": "uniform", "interval_ms": 1000,
        "frame_count": len(rows), "text_frame_count": sum(not is_js_blank(row["observed_text"]) for row in rows), "coverage_truncated": False}
    return index


def run(root, source, responses, *, index=None, focus=None, budget=None, calls=4, max_attempts=3):
    index, seen = index or runtime_index(source), []
    def model(request):
        seen.append(json.loads(request.content))
        assert len(seen) <= len(responses), "Unplanned synthetic model call"
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(responses[len(seen)-1], ensure_ascii=False)}}]})
    kwargs = {"focus_timestamp_ms": focus} if focus is not None else {}
    with httpx.Client(transport=httpx.MockTransport(model)) as client:
        result = build_visual_answer(source_items(), topics(), 20000, "找到屏幕上的独有词", root, source=source,
            session_id="session-1", turn_id="turn-1", job_id="job-1", video_id="video-1",
            source_hash=hashlib.sha256(source.read_bytes()).hexdigest(), transcript_hash="a"*64,
            model="synthetic-vlm", api_key="synthetic-key", base_url="https://visual.invalid/v1", client=client,
            max_calls=calls, max_request_attempts=max_attempts, budget_remaining=budget or (lambda: calls-len(seen)),
            evidence_mode="structured", evidence_policy="screen_search_v1", screen_index=index, **kwargs)
    return result, seen


def test_compound_tool_finds_non_asr_word_and_sends_actual_image_with_default_budget(tmp_path, real_source):
    result, requests = run(tmp_path, real_source, [tool("search_screen_text", query="unspoken_screen_token", top_k=3),
                                                 reply(block(images=["f0001"]))])
    assert len(requests) == 2 and result["schema_version"] == "1.5"
    assert result["tool_calls"][0]["origin"] == "MODEL" and result["citations"] == []
    assert result["screen_searches"][0]["status"] == "OBSERVED"
    first = json.loads(requests[0]["messages"][1]["content"][0]["text"])
    assert first["remaining_tool_steps"] == 1 and first["observations"] == []
    second = requests[1]["messages"][1]["content"]
    assert any(item["type"] == "image_url" and item["image_url"]["url"].startswith("data:image/jpeg;base64,") for item in second)
    assert json.loads(second[0]["text"])["remaining_tool_steps"] == 0
    recovered, seen = run(tmp_path, real_source, [], budget=lambda: 0)
    assert recovered == result and seen == []


def test_expected_pts_mismatch_is_atomic_and_not_published_as_observed(tmp_path, real_source):
    index = runtime_index(real_source)
    index["rows"][0]["source_pts"] = 1
    result, requests = run(tmp_path, real_source, [tool("search_screen_text", query="Cache", top_k=1),
        reply(block("INSUFFICIENT", text="实际帧与定位证据不一致。"), scope="INSUFFICIENT")], index=index)
    assert len(requests) == 2 and result["visual_evidence"] == []
    assert result["screen_searches"][0]["error_code"] == "FRAME_POSITION_MISMATCH"
    assert list((tmp_path / "visual-frames").glob("*.jpg")) == []
    assert not (tmp_path / "visual-frame-cache.json").exists()


def two_rows(source):
    first = runtime_index(source)["rows"][0]
    second = dict(first, ocr_frame_id="ocr_000002", requested_ms=500, ocr_timestamp_ms=500, source_pts=5120)
    return [first, second]


def test_late_focus_reuse_keeps_requested_order_with_nonascending_frame_ids(tmp_path, real_source):
    index = runtime_index(real_source, rows=two_rows(real_source))
    result, requests = run(tmp_path, real_source, [tool("search_screen_text", query="Cache", top_k=3),
        reply(block(images=["f0001", "f0002"]))], index=index, focus=500)
    assert len(requests) == 2
    assert result["tool_calls"][-1]["result_frame_ids"] == ["f0002", "f0001"]
    assert [frame["requested_ms"] for frame in result["visual_evidence"]] == [500, 0]
    assert [hit["requested_ms"] for hit in result["screen_searches"][0]["hits"]] == [0, 500]


def test_second_mismatched_frame_leaves_neither_first_nor_second_published(tmp_path, real_source):
    rows = two_rows(real_source)
    rows[1]["source_pts"] += 1
    store = FrameStore(real_source, tmp_path, hashlib.sha256(real_source.read_bytes()).hexdigest(), 20000)
    _, frames, receipt = observe_screen(runtime_index(real_source, rows=rows), {"query": "Cache", "top_k": 3}, store, tool_step=1, allowance=12000)
    assert frames == [] and receipt["error_code"] == "FRAME_POSITION_MISMATCH"
    assert store.frames == [] and list((tmp_path / "visual-frames").glob("*.jpg")) == []
    assert not (tmp_path / "visual-frame-cache.json").exists()


@pytest.mark.parametrize("second_query,expected", [("cache", "OBSERVED"), ("Cache", "TOOL_ERROR")])
def test_queries_reusing_current_image_do_not_claim_new_frame(tmp_path, real_source, second_query, expected):
    result, requests = run(tmp_path, real_source, [tool("search_screen_text", query="Cache", top_k=1),
        tool("search_screen_text", query=second_query, top_k=1), reply(block(images=["f0001"]))], calls=8, max_attempts=1)
    assert len(requests) == 3 and len(result["visual_evidence"]) == 1
    assert result["screen_searches"][1]["status"] == expected
    if expected == "TOOL_ERROR":
        assert result["screen_searches"][1]["error_code"] == "DUPLICATE_TOOL_CALL"
    else:
        assert result["tool_calls"][1]["result_frame_ids"] == ["f0001"]


@pytest.mark.parametrize("text,expected", [("\ufeff\u00a0", "EMPTY"), ("some other slide", "NO_MATCH")])
def test_empty_index_text_and_no_match_are_distinct_and_never_extract_images(tmp_path, real_source, text, expected):
    index = runtime_index(real_source)
    index["rows"][0]["observed_text"] = text
    index["screen_source"]["text_frame_count"] = int(not is_js_blank(text))
    result, _ = run(tmp_path, real_source, [tool("search_screen_text", query="Cache", top_k=1),
        reply(block("INSUFFICIENT", text="有界索引未提供相关画面。"), scope="INSUFFICIENT")], index=index)
    assert result["screen_searches"][0]["status"] == expected and result["visual_evidence"] == []


def test_context_limit_reports_full_match_count_without_extracting(tmp_path, real_source):
    class NoFrames:
        def inspect(self, *args, **kwargs):
            pytest.fail("Context capacity must be checked before frame extraction")
    _, frames, receipt = observe_screen(runtime_index(real_source), {"query": "Cache", "top_k": 1}, NoFrames(), tool_step=1, allowance=0)
    assert frames == [] and receipt == {"tool_step": 1, "status": "TOOL_ERROR", "error_code": "CONTEXT_LIMIT", "total_match_count": 1, "hits": []}


def test_compound_tool_respects_original32frame_cap_without_cache_mutation(tmp_path, real_source):
    store = FrameStore(real_source, tmp_path, hashlib.sha256(real_source.read_bytes()).hexdigest(), 20000)
    store.inspect("inspect_clip", {"start_ms": 0, "end_ms": 12000})
    store.inspect("inspect_frames", {"timestamps_ms": [12000, 12500, 13000, 13500]})
    store.inspect("inspect_frames", {"timestamps_ms": [14000, 14500, 15000, 15500]})
    original = (tmp_path / "visual-frame-cache.json").read_bytes()
    row = dict(runtime_index(real_source)["rows"][0], requested_ms=16000, ocr_timestamp_ms=16000, source_pts=163840)
    _, frames, receipt = observe_screen(runtime_index(real_source, rows=[row]), {"query": "Cache", "top_k": 1}, store, tool_step=4, allowance=12000)
    assert frames == [] and receipt["error_code"] == "FRAME_UNAVAILABLE"
    assert len(store.frames) == 32 and (tmp_path / "visual-frame-cache.json").read_bytes() == original


@pytest.mark.parametrize("change", ["unknown", "response_hash", "source_index", "image"])
def test_unknown_or_changed_cached_evidence_cannot_issue_another_http(tmp_path, real_source, change):
    responses = [tool("search_screen_text", query="Cache", top_k=1), reply(block(images=["f0001"]))]
    run(tmp_path, real_source, responses)
    directory = tmp_path / "agent-evidence-screen"
    index = runtime_index(real_source)
    if change == "unknown":
        state = json.loads((directory / "state.json").read_bytes())
        state["calls"][0]["status"] = "uncertain"
        (directory / "state.json").write_text(json.dumps(state), encoding="utf-8")
    elif change == "response_hash":
        (directory / "window-0000-attempt-0.json").write_text("{}", encoding="utf-8")
    elif change == "source_index":
        index["screen_source"]["index_hash"] = "0"*64
    else:
        (tmp_path / "visual-frames/f0001.jpg").write_bytes(b"changed")
    with pytest.raises(SegmentationError):
        run(tmp_path, real_source, [], index=index, budget=lambda: 0)


def ledger_client(tmp_path, source, provider, *, limit=4):
    client, ledger, ctx, options = evidence_ledger_client(tmp_path, source, provider, limit=limit,
        focus=False, policy=TextRetryPolicy(max_attempts=3, backoff_seconds=0))
    index = runtime_index(source)
    config = copy.deepcopy(ctx.lease.config)
    config["agent_turn"].update(evidence_policy="screen_search_v1", screen_context={"screen_source": index["screen_source"]})
    ctx.lease = replace(ctx.lease, config=config, required_worker_protocol=4)
    prior = ledger.agent_turn
    def turn(turn_id):
        value = prior(turn_id)
        value.update(evidence_policy="screen_search_v1", screen_source=copy.deepcopy(index["screen_source"]))
        return value
    ledger.agent_turn = turn
    options.update(evidence_policy="screen_search_v1", screen_index=index)
    return client, ledger, ctx, options


def test_real_budget_transport_retry_tool_and_final_answer_stay_within_original_four_calls(tmp_path, real_source):
    requests = []
    def provider(request):
        requests.append(json.loads(request.content))
        if len(requests) == 1:
            return httpx.Response(500, json={"error": {"code": "server_error"}})
        response = tool("search_screen_text", query="Cache", top_k=1) if len(requests) == 2 else reply(block(images=["f0001"]))
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(response)}}]})
    client, ledger, ctx, options = ledger_client(tmp_path, real_source, provider)
    with client:
        result = build_visual_answer(source_items(), topics(), 20000, "查画面文字", ctx.directory, **options)
        before = copy.deepcopy(ledger.calls)
        recovered = build_visual_answer(source_items(), topics(), 20000, "查画面文字", ctx.directory, **options)
    assert recovered == result and len(requests) == len(ledger.calls) == 3 <= 4 and ledger.calls == before
    assert result["screen_searches"][0]["status"] == "OBSERVED"


def test_budget_source_mismatch_prevents_first_provider_request(tmp_path, real_source):
    client, ledger, ctx, options = ledger_client(tmp_path, real_source, lambda _: pytest.fail("Budget source mismatch cannot dispatch"))
    prior = ledger.agent_turn
    def changed(turn_id):
        value = prior(turn_id)
        value["screen_source"]["index_hash"] = "0"*64
        return value
    ledger.agent_turn = changed
    with client, pytest.raises(SegmentationError, match="budget"):
        build_visual_answer(source_items(), topics(), 20000, "查画面文字", ctx.directory, **options)
    assert ledger.calls == {}


@pytest.mark.parametrize("stage", ["segment-1", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "../stage", None])
def test_new_public_stage_identity_requires_lowercase_canonical_uuid(stage):
    result = copy.deepcopy(FIXTURE["cases"][0]["result"])
    result["screen_source"]["segment_stage_id"] = stage
    with pytest.raises(SegmentationError):
        validate_screen_answer(result, FIXTURE["utterances"], duration_ms=result["screen_source"]["duration_ms"])


def decision_envelope(value):
    return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(value, ensure_ascii=False)}}]}


@pytest.mark.parametrize("hint", [None, "保留用户已请求的片段", False, 0, [], {}])
def test_screen_hint_adapter_preserves_explicit_values_and_rejects_invalid_values(hint):
    decision = reply(block("GENERAL"), scope="GENERAL")
    decision["suggested_clip_instruction"] = hint
    raw = decision_envelope(decision)
    before = copy.deepcopy(raw)
    if hint is None or isinstance(hint, str):
        action = _decision(raw, {}, set(), [], 0, 20000, evidence_mode="structured", screen_policy=True)
        assert action["suggested_clip_instruction"] == hint
    else:
        with pytest.raises(AgentOutputError):
            _decision(raw, {}, set(), [], 0, 20000, evidence_mode="structured", screen_policy=True)
    assert raw == before


@pytest.mark.parametrize("mutation", ["missing_scope", "missing_blocks", "extra", "tool", "unseen_frame"])
def test_screen_missing_hint_adapter_cannot_repair_other_fields_or_forge_evidence(mutation):
    decision = reply(block("GENERAL"), scope="GENERAL")
    decision.pop("suggested_clip_instruction")
    if mutation == "missing_scope":
        decision.pop("answer_scope")
    elif mutation == "missing_blocks":
        decision.pop("answer_blocks")
    elif mutation == "extra":
        decision["unexpected"] = True
    elif mutation == "tool":
        decision["type"] = "tool"
    else:
        decision.update(answer_scope="VIDEO", answer_blocks=[block(images=["f0001"])])
    raw = decision_envelope(decision)
    before = copy.deepcopy(raw)
    with pytest.raises(AgentOutputError):
        _decision(raw, {}, set(), [], 0, 20000, evidence_mode="structured", screen_policy=True)
    assert raw == before


def test_original_13_still_requires_explicit_hint():
    decision = reply(block("GENERAL"), scope="GENERAL")
    decision.pop("suggested_clip_instruction")
    with pytest.raises(AgentOutputError, match="EVIDENCE_ANSWER_FIELDS"):
        _decision(decision_envelope(decision), {}, set(), [], 0, 20000, evidence_mode="structured")


def test_missing_screen_hint_succeeds_without_repair_and_zero_budget_replays_original_cache(tmp_path, real_source):
    from livecut.agent_screen import SYSTEM

    answer = reply(block(images=["f0001"]))
    answer.pop("suggested_clip_instruction")
    responses = [tool("search_screen_text", query="Cache", top_k=1), answer]
    requests = []
    def provider(request):
        requests.append(json.loads(request.content))
        assert len(requests) <= 2, "Omitted nullable hint must not spend a repair request"
        return httpx.Response(200, json=decision_envelope(responses[len(requests)-1]))
    client, ledger, ctx, options = ledger_client(tmp_path, real_source, provider)
    with client:
        original = build_visual_answer(source_items(), topics(), 20000, "查画面文字", ctx.directory, **options)
        directory = ctx.directory / "agent-evidence-screen"
        raw_path = directory / "window-0001-attempt-0.json"
        plans = directory / "request-plans.json"
        state = json.loads((directory / "state.json").read_bytes())
        assert [(row["window_index"], row["attempt"]) for row in state["calls"]] == [(0, 0), (1, 0)]
        raw_decision = json.loads(json.loads(raw_path.read_bytes())["choices"][0]["message"]["content"])
        assert raw_decision == answer and "suggested_clip_instruction" not in raw_decision
        cache_before = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in directory.glob("window-*-attempt-*.json")}
        plan_hash = hashlib.sha256(plans.read_bytes()).hexdigest()
        system_hash = hashlib.sha256(SYSTEM.encode("utf-8")).hexdigest()
        ledger_before = copy.deepcopy(ledger.calls)
        wrappers_before = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in (ctx.directory / "provider-responses").glob("*.json")}
        replay_options = dict(options, budget_remaining=lambda: 0)
        replay = build_visual_answer(source_items(), topics(), 20000, "查画面文字", ctx.directory, **replay_options)
    assert original["suggested_clip_instruction"] is None and replay == original
    assert len(requests) == len(ledger.calls) == 2 and ledger.calls == ledger_before
    assert all(row["status"] == "SUCCEEDED" for row in ledger.calls.values())
    assert cache_before == {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in directory.glob("window-*-attempt-*.json")}
    assert hashlib.sha256(plans.read_bytes()).hexdigest() == plan_hash
    assert json.loads((directory / "state.json").read_bytes())["config_hash"] == state["config_hash"]
    assert hashlib.sha256(SYSTEM.encode("utf-8")).hexdigest() == system_hash
    assert wrappers_before == {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in (ctx.directory / "provider-responses").glob("*.json")}
