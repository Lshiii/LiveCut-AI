"""Synthetic proofreading decisions and source/call boundaries; no real ASR or provider."""

import copy
import json
from pathlib import Path

import httpx
import pytest
from jsonschema import Draft202012Validator
from livecut.caption_proofread import (
    CaptionProofreadError,
    build_caption_proofread,
    validate_caption_context,
    validate_caption_proofread,
)
from livecut.segmentation import SegmentationError
from livecut.worker import WorkerError
from test_video_agent_audit import envelope, payload, source, tool


def caption_context(items=None, transcript_hash="b" * 64):
    items = source() if items is None else items
    cues = [
        {
            "id": f"c{index + 1:06d}",
            "part_index": 0,
            "source_utterance_id": item["utterance_id"],
            "source_start_ms": item["start_ms"],
            "source_end_ms": item["end_ms"],
            "retained_start_ms": item["start_ms"],
            "retained_end_ms": item["end_ms"],
            "start_ms": item["start_ms"],
            "end_ms": item["end_ms"],
            "text": item["text"],
            "source_text": item["text"],
            "boundary_partial": False,
            "reviewed": False,
            "requires_review": False,
        }
        for index, item in enumerate(items[:2])
    ]
    cues[0]["text"] = cues[0]["text"].replace("缓存", "缓村")
    return {
        "schema_version": "1.0",
        "caption_snapshot_hash": "f" * 64,
        "export_id": "export-1",
        "clip_id": "clip-1",
        "revision": 2,
        "content_hash": "e" * 64,
        "offset": 0,
        "count": 2,
        "total_cues": 3,
        "next_offset": 2,
        "source": {
            "snapshot_hash": "c" * 64,
            "clip_sha256": "d" * 64,
            "transcript_hash": transcript_hash,
            "duration_ms": 3000,
            "media_duration_ms": 3017,
            "timing_basis": "SOURCE_EDL_SENTENCE_MS",
            "parts": [{"start_ms": 0, "end_ms": 3000}],
        },
        "cues": cues,
        "context_utterance_ids": [item["utterance_id"] for item in items[:3]],
    }


def decision(*, ids=None, suggestions=None):
    return {
        "type": "answer",
        "reviewed_cue_ids": ["c000001", "c000002"] if ids is None else ids,
        "suggestions": [suggestion()] if suggestions is None else suggestions,
    }


def suggestion(**updates):
    return {
        "cue_id": "c000001",
        "after": source()[0]["text"],
        "decision": "CORRECTION",
        "reason": "原句上下文讨论缓存；这里可能是转写错词，仍请人工回听确认。",
        "evidence_utterance_ids": ["z0"],
        **updates,
    }


def arguments(path, **extra):
    return {
        "utterances": source(),
        "duration_ms": 13000,
        "instruction": "仅检查本批字幕中的明显错词，不改数字。",
        "caption_context": caption_context(),
        "output_dir": path,
        "session_id": "session-1",
        "turn_id": "turn-1",
        "job_id": "job-1",
        "video_id": "video-1",
        "source_hash": "a" * 64,
        "transcript_hash": "b" * 64,
        "api_key": "synthetic-caption-key",
        "base_url": "https://synthetic.invalid",
        **extra,
    }


def run(path, replies, *, repeat=False, **extra):
    requests = []

    def handler(request):
        wire = json.loads(request.content)
        messages = wire.get("messages", wire.get("input", {}).get("messages"))
        requests.append(messages)
        assert len(requests) <= len(replies), "Unexpected extra model request"
        reply = replies[len(requests) - 1]
        body = envelope(reply(messages) if callable(reply) else reply)
        # The JSON decision is model text: preserve escaped invalid UTF-16 here so
        # the domain validator, rather than MockTransport's UTF-8 encoder, sees it.
        message = body["output"]["choices"][0]["message"]
        message["content"] = json.dumps(json.loads(message["content"]), ensure_ascii=True)
        return httpx.Response(200, json=body)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = build_caption_proofread(**arguments(path, client=client, **extra))
        if repeat:
            assert build_caption_proofread(**arguments(path, client=client, **extra)) == result
    return result, requests


def test_direct_suggestions_receive_all_batch_and_initial_context_replay_and_match_shared_schema(
    tmp_path,
):
    context = caption_context()
    original = copy.deepcopy(context)
    result, requests = run(tmp_path, [decision()], repeat=True, caption_context=context)
    assert len(requests) == 1 and context == original
    assert payload(requests[0])["caption_batch"] == context
    assert payload(requests[0])["initial_utterances"] == source()[:3]
    assert result["suggestions"][0]["before"] == context["cues"][0]["text"]
    assert result["suggestions"][0]["after"] == source()[0]["text"]
    assert result["coverage"] == {
        "offset": 0,
        "count": 2,
        "total_cues": 3,
        "next_offset": 2,
        "cue_ids": ["c000001", "c000002"],
    }
    assert result["tool_calls"] == [] and result["provenance"]["retrieval_mode"] == "none"
    assert not (tmp_path / "agent-answer.json").exists()
    schema = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/caption-proofread.schema.json").read_text(
            encoding="utf-8"
        )
    )
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(result)
    assert validate_caption_proofread(result, source(), context) == result


def test_uncertain_word_has_no_suggested_text_and_no_automatic_confirmation(tmp_path):
    result, _ = run(
        tmp_path, [decision(suggestions=[suggestion(after=None, decision="UNCERTAIN")])]
    )
    assert result["suggestions"][0]["after"] is None
    assert "reviewed" not in result["suggestions"][0] and "confirmed" not in result


def test_model_can_search_then_read_context_with_observed_evidence_only(tmp_path):
    result, requests = run(
        tmp_path,
        [
            tool("search_transcript", query="独立末句", top_k=1),
            tool("read_context", utterance_id="last12", before=0, after=0),
            decision(suggestions=[suggestion(evidence_utterance_ids=["last12", "z0"])]),
        ],
    )
    assert len(requests) == 3
    assert result["provenance"]["retrieval_mode"] == "bm25"
    assert result["tool_calls"][1]["result_utterance_ids"] == ["last12"]
    assert result["suggestions"][0]["evidence_utterance_ids"] == ["z0", "last12"]


@pytest.mark.parametrize(
    "bad",
    [
        decision(ids=[]),
        decision(ids=["c000002", "c000001"]),
        decision(ids=["c000001", "c000001"]),
        decision(ids=["c000001", "foreign"]),
        decision(suggestions=[suggestion(cue_id="foreign")]),
        decision(suggestions=[suggestion(), suggestion()]),
        decision(suggestions=[suggestion(after=" ")]),
        decision(suggestions=[suggestion(after="合法字后含非法控制\x01")]),
        decision(suggestions=[suggestion(after="😀" * 1001)]),
        decision(suggestions=[suggestion(after=source()[0]["text"], decision="UNCERTAIN")]),
        decision(suggestions=[suggestion(after=None)]),
        decision(suggestions=[suggestion(reason="😀" * 251)]),
        decision(suggestions=[suggestion(evidence_utterance_ids=["last12"])]),
        decision(suggestions=[suggestion(evidence_utterance_ids=["invented-word"])]),
        decision(suggestions=[suggestion(evidence_utterance_ids=[])]),
        decision(suggestions=[suggestion(before="invented before")]),
        tool("list_topics", offset=0, limit=1),
        tool("shell", command="whoami"),
        {"type": "tool", "tool_name": [], "arguments": {}},
        tool("search_transcript", query="😀" * 151, top_k=1),
        tool("read_context", utterance_id="foreign", before=0, after=0),
    ],
)
def test_invalid_decision_repairs_once_and_cannot_publish(tmp_path, bad):
    with pytest.raises(CaptionProofreadError):
        run(tmp_path, [bad, bad])
    state = json.loads((tmp_path / "caption-proofread/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 2 and not (tmp_path / "caption-proofread.json").exists()


def test_duplicate_tool_returns_empty_observation_and_step_budget_stays_bounded(tmp_path):
    repeated = tool("read_context", utterance_id="last12", before=0, after=0)
    result, requests = run(tmp_path, [repeated, repeated, decision()])
    assert payload(requests[-1])["observations"][1]["result"]["error"] == "DUPLICATE_TOOL_CALL"
    assert result["tool_calls"][1]["result_utterance_ids"] == []


def test_step_and_total_call_budget_do_not_expand_on_bad_output(tmp_path):
    with pytest.raises(CaptionProofreadError):
        run(
            tmp_path,
            [tool("read_context", utterance_id="a1", before=0, after=0)] * 3,
            max_tool_steps=1,
        )
    assert not (tmp_path / "caption-proofread.json").exists()
    other = tmp_path / "budget"
    with pytest.raises(SegmentationError) as failure:
        run(other, [tool("read_context", utterance_id="a1", before=0, after=0)], max_calls=1)
    assert failure.value.code == "BUDGET_EXHAUSTED"
    assert (
        len(
            json.loads((other / "caption-proofread/state.json").read_text(encoding="utf-8"))[
                "calls"
            ]
        )
        == 1
    )


@pytest.mark.parametrize(
    "change",
    [
        lambda c: c.update(caption_snapshot_hash="bad"),
        lambda c: c.update(count=1),
        lambda c: c.update(offset=True),
        lambda c: c.update(next_offset=True),
        lambda c: c.update(next_offset=None),
        lambda c: c["source"].update(transcript_hash="d" * 64),
        lambda c: c["source"]["parts"][0].update(end_ms=2999),
        lambda c: c["cues"][0].update(source_text="forged source"),
        lambda c: c["cues"][0].update(source_start_ms=1),
        lambda c: c["cues"][0].update(retained_start_ms=1),
        lambda c: c["cues"][0].update(boundary_partial=True),
        lambda c: c["cues"][0].update(requires_review=True),
        lambda c: c["cues"][0].update(text="😀" * 1001),
        lambda c: c["cues"][0].update(source_utterance_id="missing"),
        lambda c: c.update(context_utterance_ids=["z0"]),
        lambda c: c.update(context_utterance_ids=["a1", "z0"]),
        lambda c: c.update(context_utterance_ids=["z0", "a1", "last12"]),
        lambda c: c["cues"].reverse(),
    ],
)
def test_source_identity_and_coverage_are_checked_before_any_provider(tmp_path, change):
    context = caption_context()
    change(context)
    with httpx.Client(
        transport=httpx.MockTransport(lambda request: pytest.fail("No provider"))
    ) as client:
        with pytest.raises(CaptionProofreadError):
            build_caption_proofread(**arguments(tmp_path, caption_context=context, client=client))


def test_edited_text_times_review_and_partial_source_are_permitted_without_changing_source(
    tmp_path,
):
    context = caption_context()
    context["source"]["parts"][0]["start_ms"] = 100
    context["source"]["duration_ms"] = 2900
    cue = context["cues"][0]
    cue.update(
        retained_start_ms=100,
        boundary_partial=True,
        reviewed=True,
        requires_review=False,
        start_ms=50,
        end_ms=850,
    )
    original = copy.deepcopy(context)
    result, _ = run(tmp_path, [decision()], caption_context=context)
    assert context == original and result["suggestions"][0]["before"] == cue["text"]


def test_final_batch_next_offset_null_and_long_initial_neighbor_can_be_omitted(tmp_path):
    items = source()
    items[2]["text"] = "邻" * 12001
    context = caption_context(items)
    context.update(total_cues=2, next_offset=None)
    with pytest.raises(CaptionProofreadError, match="CAPTION_INITIAL_CONTEXT"):
        validate_caption_context(context, items, 13000, "b" * 64)
    context["context_utterance_ids"] = ["z0", "a1"]
    result, requests = run(tmp_path, [decision()], utterances=items, caption_context=context)
    assert result["coverage"]["next_offset"] is None
    assert len(payload(requests[0])["initial_utterances"]) == 2


def test_utf16_batch_and_instruction_limits_apply_before_requests(tmp_path):
    items = source()
    items[0]["text"] = "甲" * 1600
    items[1]["text"] = "乙" * 1600
    with pytest.raises(CaptionProofreadError, match="CAPTION_BATCH_LIMIT"):
        run(tmp_path, [], utterances=items, caption_context=caption_context(items))
    with pytest.raises(CaptionProofreadError, match="INSTRUCTION"):
        run(tmp_path, [], instruction="😀" * 501)


def test_changed_context_or_cached_response_cannot_replay_old_suggestions(tmp_path):
    run(tmp_path, [decision()])
    context = caption_context()
    context["cues"][0]["text"] = "另一个合法编辑"
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [], caption_context=context)
    assert failure.value.code == "INVALID_CAPTION_PROOFREAD_CHECKPOINT"
    cached = tmp_path / "caption-proofread/window-0000-attempt-0.json"
    cached.write_text(json.dumps(envelope(decision(suggestions=[]))), encoding="utf-8")
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [])
    assert failure.value.code == "INVALID_CAPTION_PROOFREAD_CHECKPOINT"


def test_cancellation_after_response_does_not_publish_result(tmp_path):
    cancelled = False

    def response(messages):
        nonlocal cancelled
        cancelled = True
        return decision()

    def guard():
        if cancelled:
            raise WorkerError("STAGE_STOPPED")

    with pytest.raises(WorkerError, match="STAGE_STOPPED"):
        run(tmp_path, [response], guard=guard)
    assert not (tmp_path / "caption-proofread.json").exists()


@pytest.mark.parametrize(
    "field,value",
    [
        ("caption_snapshot_hash", "1" * 64),
        ("caption_revision", 3),
        ("caption_revision", True),
        ("caption_content_hash", "1" * 64),
        ("clip_id", "other"),
        ("session_id", "other"),
    ],
)
def test_result_must_match_caption_and_expected_turn_identity(tmp_path, field, value):
    result, _ = run(tmp_path, [decision()])
    result[field] = value
    with pytest.raises(CaptionProofreadError):
        validate_caption_proofread(
            result, source(), caption_context(), expected={"session_id": "session-1"}
        )


def test_result_cannot_change_before_coverage_trace_or_provenance(tmp_path):
    result, _ = run(tmp_path, [decision()])
    for mutate in [
        lambda r: r["suggestions"][0].update(before="invented"),
        lambda r: r["coverage"].update(cue_ids=["c000002", "c000001"]),
        lambda r: r["coverage"].update(offset=False),
        lambda r: r["suggestions"][0].update(evidence_utterance_ids=["last12"]),
        lambda r: r["provenance"].update(retrieval_mode="bm25"),
        lambda r: r["provenance"].update(retrieval_mode=[]),
        lambda r: r["tool_calls"].append(
            {"step": 1, "tool_name": "list_topics", "arguments": {}, "result_utterance_ids": []}
        ),
    ]:
        bad = copy.deepcopy(result)
        mutate(bad)
        with pytest.raises(CaptionProofreadError):
            validate_caption_proofread(bad, source(), caption_context())


@pytest.mark.parametrize("count", [20001, 100000, 100001])
def test_source_limit_matches_java_while_model_input_remains_a_bounded_caption_batch(
    tmp_path, count
):
    items = source()
    items.extend(
        {
            "utterance_id": f"extra-{index}",
            "start_ms": index * 1000,
            "end_ms": index * 1000 + 900,
            "text": "额外原句。",
            "source_chunk_id": "audio-0",
        }
        for index in range(len(items), count)
    )
    options = {"utterances": items, "duration_ms": count * 1000}
    if count > 100000:
        with httpx.Client(
            transport=httpx.MockTransport(
                lambda _: pytest.fail("over-limit source requested a model")
            )
        ) as client:
            with pytest.raises(SegmentationError) as raised:
                build_caption_proofread(**arguments(tmp_path, client=client, **options))
        assert raised.value.code == "INVALID_CAPTION_PROOFREAD_INPUT"
    else:
        result, requests = run(tmp_path, [decision()], **options)
        assert result["coverage"]["count"] == 2
        sent = json.loads(requests[0][-1]["content"])
        assert sent["video_info"]["utterance_count"] == count
        assert len(sent["caption_batch"]["cues"]) == 2
        assert len(sent["initial_utterances"]) == 3
        assert len(requests[0][-1]["content"]) < 20000


@pytest.mark.parametrize("declared_steps", [0, 4])
def test_single_call_budget_allows_an_initial_context_answer_and_zero_tools(
    tmp_path, declared_steps
):
    result, requests = run(
        tmp_path, [decision()], max_calls=1, max_tool_steps=declared_steps, repeat=True
    )
    assert len(requests) == 1 and payload(requests[0])["remaining_tool_steps"] == 0
    assert result["tool_calls"] == []
    # Effective zero-step allowances share the same bounded replay identity.
    replayed, replay_requests = run(tmp_path, [], max_calls=1, max_tool_steps=0)
    assert replayed == result and replay_requests == []


def test_four_call_budget_permits_three_tools_then_reserves_the_fourth_call_for_answer(tmp_path):
    replies = [
        tool("read_context", utterance_id=uid, before=0, after=0) for uid in ["a1", "b3", "last12"]
    ]
    replies.append(decision(suggestions=[suggestion(evidence_utterance_ids=["last12", "z0"])]))
    result, requests = run(tmp_path, replies, max_calls=4, max_tool_steps=4, repeat=True)
    assert len(requests) == 4
    assert [payload(messages)["remaining_tool_steps"] for messages in requests] == [3, 2, 1, 0]
    assert len(result["tool_calls"]) == 3


def test_four_call_budget_does_not_pay_for_repair_after_a_forbidden_fourth_tool(tmp_path):
    replies = [
        tool("read_context", utterance_id=uid, before=0, after=0)
        for uid in ["a1", "b3", "last12", "g7"]
    ]
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, replies, max_calls=4, max_tool_steps=4)
    assert failure.value.code == "BUDGET_EXHAUSTED"
    state = json.loads((tmp_path / "caption-proofread/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 4 and not (tmp_path / "caption-proofread.json").exists()


@pytest.mark.parametrize(
    "blank", ["\ufeff", "\ufeff \u00a0\ufeff \ufeff\u00a0", "\u00a0\ufeff\u00a0"]
)
def test_bom_and_nbsp_only_after_are_blank_across_java_javascript_and_python(tmp_path, blank):
    with pytest.raises(CaptionProofreadError, match="SUGGESTION_TEXT"):
        run(tmp_path, [decision(suggestions=[suggestion(after=blank)])] * 2)


def test_surrogate_is_sanitized_and_normal_emoji_and_original_spacing_survive(tmp_path):
    with pytest.raises(CaptionProofreadError, match="TEXT_UNICODE"):
        run(tmp_path / "surrogate", [decision(suggestions=[suggestion(after="x\ud800")])] * 2)
    after = "  😀 保留可见文字与空白 \u00a0\ufeff"
    result, _ = run(tmp_path / "emoji", [decision(suggestions=[suggestion(after=after)])])
    assert result["suggestions"][0]["after"] == after
