"""Synthetic equivalents of a correction plus an invalid UNCERTAIN original text."""

import copy
import json

import pytest
from livecut.caption_proofread import CaptionProofreadError
from livecut.segmentation import SegmentationError
from test_caption_proofread import caption_context, decision, run, suggestion
from test_video_agent_audit import payload


def mixed_answer(after):
    return decision(
        suggestions=[
            suggestion(),
            suggestion(
                cue_id="c000002",
                after=after,
                decision="UNCERTAIN",
                reason="仅凭提供的文本无法判断，需人工回听，不提供确定改写。",
                evidence_utterance_ids=["a1"],
            ),
        ]
    )


def test_mixed_real_failure_style_repairs_uncertain_to_null_and_keeps_valid_correction(tmp_path):
    original = caption_context()["cues"][1]["text"]
    bad = mixed_answer(original)
    supplied = copy.deepcopy(bad)

    def fix(messages):
        repair = json.loads(messages[-1]["content"])
        assert repair["validation_rule"] == "UNCERTAIN_TEXT"
        assert "JSON 字面量 null" in repair["field_repair"]
        assert all(
            text in repair["field_repair"]
            for text in ["原文", "空字符串", '字符串 "null"', "不要删除"]
        )
        assert '"decision":"UNCERTAIN"' in messages[0]["content"]
        assert '"after":null' in messages[0]["content"]
        fixed = copy.deepcopy(bad)
        fixed["suggestions"][1]["after"] = None
        return fixed

    result, requests = run(tmp_path, [bad, fix], max_calls=2, repeat=True)
    assert len(requests) == 2 and bad == supplied
    assert [payload(messages)["remaining_tool_steps"] for messages in requests] == [1, 0]
    assert [item["decision"] for item in result["suggestions"]] == ["CORRECTION", "UNCERTAIN"]
    assert result["suggestions"][0]["after"] == supplied["suggestions"][0]["after"]
    assert result["suggestions"][1]["after"] is None
    assert result["suggestions"][1]["before"] == original


@pytest.mark.parametrize("after", ["保留原文的合成文字", "", "null"])
def test_repeated_illegal_uncertain_text_still_fails_after_exactly_one_repair(tmp_path, after):
    with pytest.raises(CaptionProofreadError, match="UNCERTAIN_TEXT"):
        run(tmp_path, [mixed_answer(after), mixed_answer(after)], max_calls=2)
    state = json.loads((tmp_path / "caption-proofread/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 2
    assert not (tmp_path / "caption-proofread.json").exists()


@pytest.mark.parametrize(
    "bad,rule,field",
    [
        (decision(ids=["c000001"]), "ANSWER_COVERAGE", "reviewed_cue_ids"),
        (
            decision(suggestions=[suggestion(evidence_utterance_ids=["last12"])]),
            "SUGGESTION_EVIDENCE",
            "evidence_utterance_ids",
        ),
    ],
)
def test_other_field_rules_receive_readable_specific_repair_instructions(
    tmp_path, bad, rule, field
):
    def fix(messages):
        repair = json.loads(messages[-1]["content"])
        assert repair["validation_rule"] == rule and field in repair["field_repair"]
        return decision()

    result, requests = run(tmp_path, [bad, fix])
    assert len(requests) == 2 and result["suggestions"]


def test_prompt_revision_change_never_redispatches_a_previous_frozen_request(tmp_path, monkeypatch):
    from livecut import caption_proofread

    run(tmp_path, [decision()])
    state = tmp_path / "caption-proofread/state.json"
    previous = state.read_bytes()
    monkeypatch.setattr(caption_proofread, "PROMPT_REVISION", "synthetic-next-prompt-revision")
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [])
    assert failure.value.code == "INVALID_CAPTION_PROOFREAD_CHECKPOINT"
    assert state.read_bytes() == previous
