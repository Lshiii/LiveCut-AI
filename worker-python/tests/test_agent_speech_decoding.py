"""A missing optional hint is decoded only for 1.4; evidence stays strict."""
import copy
import hashlib
import json

import pytest
from livecut.agent_speech_lookup import normalize_speech_answer_decision
from livecut.agent_visual import _decision
from livecut.video_agent import AgentOutputError
from test_agent_evidence import block, reply
from test_agent_speech_lookup import real_source as _real_source
from test_agent_speech_lookup import run
from test_agent_visual import answer, source_items, tool

real_source = _real_source


def omitted_hint(*blocks, scope="VIDEO"):
    value = reply(*blocks, scope=scope)
    value.pop("suggested_clip_instruction")
    return value


def decide(value, *, speech=True, structured=True, visible=None, frames=None, trace=None):
    raw = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(value)}}]}
    return _decision(raw, {item["utterance_id"]: item for item in source_items()},
        {"u1"} if visible is None else visible, frames or [], 1, 20000,
        evidence_mode="structured" if structured else None, tool_calls=trace or [], speech_policy=speech)


def test_only_the_missing_hint_is_defaulted_in_a_copy_without_mutating_model_data():
    value = omitted_hint(block("SPEECH", speech=["u1"]))
    before = copy.deepcopy(value)
    decoded = normalize_speech_answer_decision(value)
    assert value == before and "suggested_clip_instruction" not in value
    assert decoded is not value and decoded == {**value, "suggested_clip_instruction": None}
    action = decide(value)
    assert action["suggested_clip_instruction"] is None and action["citation_ids"] == ["u1"]
    assert value == before


@pytest.mark.parametrize("hint", [None, "请剪出这些原句作为待审核候选"])
def test_explicit_valid_hint_is_preserved_without_defaulting(hint):
    value = reply(block("SPEECH", speech=["u1"]))
    value["suggested_clip_instruction"] = hint
    assert normalize_speech_answer_decision(value) is value
    assert decide(value)["suggested_clip_instruction"] == hint


@pytest.mark.parametrize("hint", [False, 0, [], {}, " "])
def test_explicit_invalid_hint_is_not_replaced_with_null(hint):
    value = reply(block("SPEECH", speech=["u1"]))
    value["suggested_clip_instruction"] = hint
    before = copy.deepcopy(value)
    assert normalize_speech_answer_decision(value) is value
    with pytest.raises(AgentOutputError, match="CLIP_SUGGESTION"):
        decide(value)
    assert value == before


@pytest.mark.parametrize("mutate", [
    lambda value: value.pop("answer_scope"),
    lambda value: value.pop("answer_blocks"),
    lambda value: value.pop("type"),
    lambda value: value.update(type="unknown"),
    lambda value: value.update(extra="not permitted"),
    lambda value: value.update(citation_ids=["u1"]),
])
def test_other_missing_or_extra_fields_are_not_normalized(mutate):
    value = omitted_hint(block("SPEECH", speech=["u1"]))
    mutate(value)
    before = copy.deepcopy(value)
    assert normalize_speech_answer_decision(value) is value
    with pytest.raises(AgentOutputError, match="EVIDENCE_ANSWER_FIELDS"):
        decide(value)
    assert value == before


def test_tool_decisions_are_not_given_answer_fields_or_other_defaults():
    value = tool("read_context", utterance_id="u1", before=0, after=0)
    assert normalize_speech_answer_decision(value) is value and decide(value) == value
    value["extra"] = None
    with pytest.raises(AgentOutputError, match="VISUAL_TOOL_LIMIT_OR_NAME"):
        decide(value)


@pytest.mark.parametrize("structured,rule", [(False, "VISUAL_ANSWER"), (True, "EVIDENCE_ANSWER_FIELDS")])
def test_old_visual_and_evidence_decoders_still_require_explicit_hint(structured, rule):
    value = omitted_hint(block("SPEECH", speech=["u1"])) if structured else answer(ids=["u1"])
    value.pop("suggested_clip_instruction", None)
    with pytest.raises(AgentOutputError, match=rule):
        decide(value, speech=False, structured=structured)


@pytest.mark.parametrize("selected,visible,frames,rule", [
    (block("SPEECH", speech=["u2"]), {"u1"}, [], "EVIDENCE_UNSEEN_OR_UNORDERED_IDS"),
    (block("SPEECH", speech=["not-in-source"]), {"not-in-source"}, [], "EVIDENCE_UNSEEN_OR_UNORDERED_IDS"),
    (block("FRAME", speech=["u1"], images=["f0001"]), {"u1"}, [{"frame_id": "f0001"}], "EVIDENCE_MODALITY"),
    (block("INFERENCE", speech=["u1"]), {"u1"}, [], "EVIDENCE_VIDEO_REQUIRES_OBSERVATION"),
    (block("SAMPLED_CHANGE", images=["f0001", "f0002"]), set(),
     [{"frame_id": "f0001", "source_pts": 1, "source_time_base": "1/1000"},
      {"frame_id": "f0002", "source_pts": 2, "source_time_base": "1/1000"}],
     "EVIDENCE_CHANGE_REQUIRES_ORDERED_SAME_CLIP_PAIR"),
])
def test_missing_hint_never_bypasses_source_modality_or_same_clip_evidence(selected, visible, frames, rule):
    with pytest.raises(AgentOutputError, match=rule):
        decide(omitted_hint(selected), visible=visible, frames=frames)


@pytest.mark.parametrize("focus", [None, 0])
def test_cached_missing_hint_replays_with_zero_http_and_original_payload_response_hashes(tmp_path, real_source, focus):
    response = omitted_hint(block("SPEECH", speech=["u1"]))
    result, calls = run(tmp_path, real_source, [response], focus=focus, limit=1)
    assert len(calls) == 1 and result["schema_version"] == "1.4" and result["suggested_clip_instruction"] is None
    directory = tmp_path / "agent-evidence-speech"
    plans = (directory / "request-plans.json").read_bytes()
    state = json.loads((directory / "state.json").read_text("utf-8"))
    call = state["calls"][0]
    cached_path = directory / call["response_file"]
    cached = cached_path.read_bytes()
    assert hashlib.sha256(cached).hexdigest() == call["response_sha256"]
    cached_content = json.loads(json.loads(cached)["choices"][0]["message"]["content"])
    assert "suggested_clip_instruction" not in cached_content
    replayed, extra_calls = run(tmp_path, real_source, [], focus=focus, limit=1, budget=lambda: 0)
    assert replayed == result and extra_calls == []
    assert cached_path.read_bytes() == cached and (directory / "request-plans.json").read_bytes() == plans
    replay_state = json.loads((directory / "state.json").read_text("utf-8"))
    assert replay_state["calls"] == state["calls"] and replay_state["config_hash"] == state["config_hash"]
