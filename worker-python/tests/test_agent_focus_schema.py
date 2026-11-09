"""Synthetic wire checks distinguish a user position from verified frame evidence."""

import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "contracts/agent-answer-visual-focus.schema.json").read_text("utf-8"))
FIXTURE = json.loads((ROOT / "fixtures/agent-visual-focus.synthetic.json").read_text("utf-8"))


def test_focus_contract_is_explicitly_synthetic_and_versioned():
    Draft202012Validator.check_schema(SCHEMA)
    assert FIXTURE["data_kind"] == "synthetic_fixture"
    Draft202012Validator(SCHEMA).validate(FIXTURE["result"])


@pytest.mark.parametrize("kind", ["missing_focus", "null_focus", "bool_focus", "negative_focus", "old_prompt", "old_version", "no_frame", "no_initial_tool", "model_initial", "second_user_focus", "two_initial_frames"])
def test_user_position_cannot_replace_a_real_initial_observation(kind):
    result = copy.deepcopy(FIXTURE["result"])
    if kind == "missing_focus":
        del result["focus_timestamp_ms"]
    elif kind == "null_focus":
        result["focus_timestamp_ms"] = None
    elif kind == "bool_focus":
        result["focus_timestamp_ms"] = True
    elif kind == "negative_focus":
        result["focus_timestamp_ms"] = -1
    elif kind == "old_prompt":
        result["provenance"]["prompt_version"] = "video-agent-visual-v1"
    elif kind == "old_version":
        result["schema_version"] = "1.1"
    elif kind == "no_frame":
        result["visual_evidence"] = []
    elif kind == "no_initial_tool":
        result["tool_calls"] = []
    elif kind == "model_initial":
        result["tool_calls"][0]["origin"] = "MODEL"
    elif kind == "second_user_focus":
        result["tool_calls"][1]["origin"] = "USER_FOCUS"
    else:
        result["tool_calls"][0]["arguments"]["timestamps_ms"] = [1000, 1500]
    assert not Draft202012Validator(SCHEMA).is_valid(result)


def test_focus_zero_is_a_valid_position_and_does_not_mean_absent():
    result = copy.deepcopy(FIXTURE["result"])
    result["focus_timestamp_ms"] = 0
    result["tool_calls"][0]["arguments"]["timestamps_ms"] = [0]
    frame = result["visual_evidence"][0]
    frame.update(requested_ms=0, timestamp_ms=0, source_pts=0)
    Draft202012Validator(SCHEMA).validate(result)


def test_old_visual_contract_does_not_accept_unversioned_focus_fields():
    legacy = json.loads((ROOT / "contracts/agent-answer-visual.schema.json").read_text("utf-8"))
    assert not Draft202012Validator(legacy).is_valid(FIXTURE["result"])
