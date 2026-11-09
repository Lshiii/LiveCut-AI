"""Shared wire boundary checks, deliberately synthetic and without model/media claims."""

import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "contracts/agent-answer-visual.schema.json").read_text("utf-8"))
FIXTURE = json.loads((ROOT / "fixtures/agent-visual.synthetic.json").read_text("utf-8"))


def test_shared_visual_contract_is_valid_and_explicitly_synthetic():
    Draft202012Validator.check_schema(SCHEMA)
    assert FIXTURE["data_kind"] == "synthetic_fixture"
    Draft202012Validator(SCHEMA).validate(FIXTURE["result"])


def test_pure_visual_video_answer_does_not_need_fabricated_speech():
    result = copy.deepcopy(FIXTURE["result"])
    result["citations"] = []
    Draft202012Validator(SCHEMA).validate(result)
    result["visual_citations"] = []
    assert not Draft202012Validator(SCHEMA).is_valid(result)


@pytest.mark.parametrize("mode", ["path", "url", "fake_id", "wrong_tool", "asr_in_visual", "frames_in_text", "old_version", "old_prompt", "oversize", "missing_field"])
def test_visual_wire_rejects_mixed_or_unbounded_evidence(mode):
    result = copy.deepcopy(FIXTURE["result"])
    frame = result["visual_evidence"][0]
    if mode == "path":
        frame["object_key"] = "private/other.jpg"
    elif mode == "url":
        frame["url"] = "https://example.com/unverified.jpg"
    elif mode == "fake_id":
        frame["frame_id"] = "f0033"
    elif mode == "wrong_tool":
        result["tool_calls"][1]["tool_name"] = "open_file"
    elif mode == "asr_in_visual":
        result["tool_calls"][1]["result_utterance_ids"] = ["u1"]
    elif mode == "frames_in_text":
        result["tool_calls"][0]["result_frame_ids"] = ["f0001"]
    elif mode == "old_version":
        result["schema_version"] = "1.0"
    elif mode == "old_prompt":
        result["provenance"]["prompt_version"] = "video-agent-v1"
    elif mode == "oversize":
        frame["bytes"] = 1048577
    else:
        del frame["sha256"]
    assert not Draft202012Validator(SCHEMA).is_valid(result)


def test_legacy_schema_does_not_silently_accept_visual_results():
    legacy = json.loads((ROOT / "contracts/agent-answer.schema.json").read_text("utf-8"))
    assert not Draft202012Validator(legacy).is_valid(FIXTURE["result"])
