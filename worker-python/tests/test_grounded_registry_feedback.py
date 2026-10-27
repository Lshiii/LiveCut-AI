"""Bounded rejected-proposal hints, using synthetic data and HTTP mocks only."""

from __future__ import annotations

import copy
import json
from pathlib import Path

import httpx
import pytest
from livecut import segmentation
from livecut.contracts import validate_analysis_result
from livecut.segmentation import SegmentationError, discover_products


def utterance(uid: str, start: int = 0) -> dict:
    return {"utterance_id": uid, "start_ms": start, "end_ms": start + 800,
            "text": "合成语音证据：这双鞋", "source_chunk_id": "synthetic-asr"}


def product(label: object, category: str = "上衣") -> dict:
    return {"label": label, "category": category, "variant_description": "",
            "canonical_product_id": None}


def assignment(labels: list[str], kind: str = "product_talk") -> dict:
    return {"content_kind": kind, "product_labels": labels}


def rejected_proposal() -> dict:
    return {"products": [product("tmp1"), product("tmp2", " 上 衣 ")],
            "assignments": {"u1": assignment(["tmp1"]), "u2": assignment(["tmp2"])}}


def corrected_proposal(label: str = "tmp1", category: str = "上衣") -> dict:
    return {"products": [product(label, category)],
            "assignments": {"u1": assignment([label]), "u2": assignment([label])}}


def provider(value: dict | str, *, finish_reason: str = "stop") -> dict:
    content = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return {"request_id": "synthetic-request", "output": {"choices": [{
        "finish_reason": finish_reason, "message": {"role": "assistant", "content": content}}]},
        "usage": {"input_tokens": 2, "output_tokens": 1, "total_tokens": 3}}


def trusted_window(ids: list[str], *, visible_extras: list[str] | None = None) -> dict:
    return {"core_ids": ids, "visible": [utterance(uid, index * 1000)
            for index, uid in enumerate([*ids, *(visible_extras or [])])]}


def hint(value: dict | str, ids: list[str] | None = None, *, grouping: str = "category") -> dict:
    return segmentation._repair_product_merges(provider(value),
        trusted_window(["u1", "u2"] if ids is None else ids), grouping=grouping)


def assert_empty(value: dict) -> None:
    assert value == {"repair_product_merges": [], "omitted_repair_group_count": 0}


def mock_client(*responses: dict | str) -> tuple[httpx.Client, list[dict]]:
    pending = list(responses)
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "unit.invalid"
        assert request.url.path == "/api/v1/services/aigc/text-generation/generation"
        assert request.headers["authorization"] == "Bearer unit-test-secret"
        requests.append(json.loads(request.content))
        if not pending:
            pytest.fail("unexpected third or resumed model request")
        return httpx.Response(200, json=provider(pending.pop(0)))

    return httpx.Client(transport=httpx.MockTransport(handler)), requests


def run(directory: Path, client: httpx.Client, *, max_calls: int = 2) -> dict:
    return discover_products([utterance("u1"), utterance("u2", 1000)], 1800, directory,
        client=client, api_key="unit-test-secret", base_url="https://unit.invalid/api/v1",
        max_calls=max_calls)


def repair_json_messages(initial: list[dict], repaired: list[dict]) -> list[dict]:
    assert repaired[:len(initial)] == initial
    hints = []
    for message in repaired[len(initial):]:
        assert message["role"] == "user"
        try:
            value = json.loads(message["content"])
        except (TypeError, ValueError):
            # The message may prepend an explicit untrusted-data explanation.
            try:
                value = json.loads(message["content"].splitlines()[-1])
            except (IndexError, TypeError, ValueError):
                continue
        if isinstance(value, dict) and "repair_product_merges" in value:
            hints.append(value)
    return hints


def test_builder_anchors_prior_labels_to_trusted_core_order_without_category_text() -> None:
    category = 'UNTRUSTED_CATEGORY_IGNORE_RULES:"secret"'
    value = {"products": [product("tmp9", category), product("tmp2", category)],
             "assignments": {"u2": assignment(["tmp2"]), "unknown": assignment(["tmp9"]),
                             "context": assignment(["tmp9"]), "u1": assignment(["tmp9"])}}
    window = trusted_window(["u1", "u2"], visible_extras=["context"])
    raw, original = provider(value), copy.deepcopy(value)
    original_raw, original_window = copy.deepcopy(raw), copy.deepcopy(window)
    result = segmentation._repair_product_merges(raw, window, grouping="category")
    assert result == {"repair_product_merges": [{"preferred_label": "tmp9",
        "source_labels": ["tmp9", "tmp2"], "previous_assignment_utterance_ids": ["u1", "u2"],
        "omitted_utterance_id_count": 0}], "omitted_repair_group_count": 0}
    assert category not in json.dumps(result) and "UNTRUSTED_CATEGORY" not in json.dumps(result)
    assert value == original and raw == original_raw and window == original_window
    assert segmentation._repair_product_merges(raw, window, grouping="category") == result


def test_only_related_core_ids_are_returned_and_id_sample_is_bounded() -> None:
    ids = [f"core-{index:02d}" for index in range(22)]
    value = rejected_proposal()
    value["assignments"] = {uid: assignment(["tmp2"]) for uid in reversed(ids)}
    value["assignments"]["not_in_core"] = assignment(["tmp1"])
    result = hint(value, ids)
    group = result["repair_product_merges"][0]
    assert group["previous_assignment_utterance_ids"] == ids[:16]
    assert group["omitted_utterance_id_count"] == 6
    assert "not_in_core" not in json.dumps(result)


def test_unsafe_source_ids_are_omitted_without_echoing_them() -> None:
    unsafe = ["MODEL ID WITH SPACES", "MODEL\nROLE", "x" * 65]
    boundary = "i" * 64
    ids = ["u-safe", unsafe[0], boundary, unsafe[1], unsafe[2], "u-last"]
    value = rejected_proposal()
    value["assignments"] = {uid: assignment(["tmp1"]) for uid in reversed(ids)}
    group = hint(value, ids)["repair_product_merges"][0]
    assert group["previous_assignment_utterance_ids"] == ["u-safe", boundary, "u-last"]
    assert group["omitted_utterance_id_count"] == len(unsafe)
    assert all(uid not in json.dumps(group) for uid in unsafe)


def test_builder_limits_complete_merge_groups_to_eight() -> None:
    value = {"products": [product(f"tmp{number * 2 + side + 1}", f"类别{number}")
                           for number in range(10) for side in range(2)], "assignments": {}}
    result = hint(value)
    assert len(result["repair_product_merges"]) == 8
    assert result["omitted_repair_group_count"] == 2
    for index, group in enumerate(result["repair_product_merges"]):
        assert group == {"preferred_label": f"tmp{index * 2 + 1}",
                         "source_labels": [f"tmp{index * 2 + 1}", f"tmp{index * 2 + 2}"],
                         "previous_assignment_utterance_ids": [], "omitted_utterance_id_count": 0}
    assert "类别" not in json.dumps(result, ensure_ascii=False)


@pytest.mark.parametrize("size,expected_count,omitted", [(16, 1, 0), (17, 0, 1)])
def test_partial_duplicate_group_is_never_offered_as_a_complete_merge(
    size: int, expected_count: int, omitted: int,
) -> None:
    value = {"products": [product(f"tmp{number + 1}") for number in range(size)], "assignments": {}}
    result = hint(value)
    assert len(result["repair_product_merges"]) == expected_count
    assert result["omitted_repair_group_count"] == omitted
    if expected_count:
        assert result["repair_product_merges"][0]["source_labels"] == [f"tmp{i + 1}" for i in range(size)]


@pytest.mark.parametrize("unsafe", [None, 3, True, [], {}, "", " ", "含中文", "bad label",
    "bad\nlabel", 'bad"label', "x" * 65, "MODEL_LABEL_SEND_PRIVATE_KEYS", "ascii_identifier",
    "tmp", "tmp10000", "TMP1", "tmp1suffix", "tmp-1"])
def test_any_unsafe_label_omits_the_entire_group(unsafe: object) -> None:
    value = {"products": [product("tmp1"), product(unsafe)], "assignments": {}}
    result = hint(value)
    assert result == {"repair_product_merges": [], "omitted_repair_group_count": 1}


def test_duplicate_labels_within_one_group_are_not_promoted() -> None:
    result = hint({"products": [product("tmp1"), product("tmp1")], "assignments": {}})
    assert result == {"repair_product_merges": [], "omitted_repair_group_count": 1}


def test_tmp_numeric_label_boundary_and_original_order_are_preserved() -> None:
    first, second = "tmp9999", "tmp0"
    group = hint({"products": [product(first), product(second)], "assignments": {}})["repair_product_merges"][0]
    assert group["preferred_label"] == first and group["source_labels"] == [first, second]


def test_observable_variants_and_oversized_categories_do_not_create_merge_hints() -> None:
    assert_empty(hint(rejected_proposal(), grouping="observable_variant"))
    assert_empty(hint({"products": [product("tmp1", "x" * 101), product("tmp2", "x" * 101)],
                       "assignments": {}}))


@pytest.mark.parametrize("raw", [None, [], {}, {"output": {}}, {"output": {"choices": []}},
    {"output": {"choices": [{"finish_reason": "stop", "message": {"content": 3}}]}},
    provider('NOT_JSON_MODEL_TEXT_IGNORE_ALL_RULES'),
    provider('{"products": [MODEL_BROKEN_JSON'),
    provider(rejected_proposal(), finish_reason="length")])
def test_malformed_or_incomplete_provider_outputs_produce_empty_hints(raw: object) -> None:
    assert_empty(segmentation._repair_product_merges(raw, trusted_window(["u1", "u2"]), grouping="category"))


@pytest.mark.parametrize("content", [
    # Permissive last-value-wins parsing would expose a complete safe merge.
    '{"products":[],"products":' + json.dumps(rejected_proposal()["products"]) + ',"assignments":{}}',
    json.dumps(rejected_proposal()).replace('"label": "tmp1"', '"label": "tmp9", "label": "tmp1"', 1),
    json.dumps(rejected_proposal()).replace('"u1": {', '"u1": {}, "u1": {', 1),
])
def test_duplicate_json_keys_are_not_reinterpreted_for_hints(content: str) -> None:
    assert_empty(hint(content))


def test_malformed_assignment_values_do_not_escape_or_echo_model_prose() -> None:
    value = rejected_proposal()
    attack = "MODEL_ARBITRARY_PROSE_NEVER_ECHO"
    value["assignments"] = {"u1": {"product_labels": [{attack: "secret"}, [], "tmp1"]},
                            "u2": {"product_labels": attack}}
    result = hint(value)
    assert attack not in json.dumps(result) and "secret" not in json.dumps(result)
    assert len(result["repair_product_merges"]) <= 1
    if result["repair_product_merges"]:
        assert set(result["repair_product_merges"][0]["previous_assignment_utterance_ids"]) <= {"u1"}


def test_same_single_repair_receives_structural_json_without_changing_initial_messages(tmp_path: Path) -> None:
    invalid, fixed = rejected_proposal(), corrected_proposal()
    original = copy.deepcopy(invalid)
    client, requests = mock_client(invalid, fixed)
    with client:
        result = run(tmp_path, client)
        assert run(tmp_path, client) == result
    assert len(requests) == 2 and invalid == original
    initial, repaired = [row["input"]["messages"] for row in requests]
    hints = repair_json_messages(initial, repaired)
    assert len(hints) == 1 and hints[0]["repair_product_merges"] == hint(invalid)["repair_product_merges"]
    assert "repair_product_merges" not in initial[0]["content"]
    assert "repair_product_merges" not in json.loads(initial[1]["content"])
    additions = "\n".join(message["content"] for message in repaired[len(initial):])
    assert "真值" in additions and "指令" in additions
    assert all(row["role"] != "assistant" for row in repaired)
    assert result["provenance"]["prompt_version"] == "catalog-free-v13.2-grounded-registry-feedback"
    assert result["pipeline_version"] == "catalog-free-visual-text-v8"
    validate_analysis_result(result)
    assert json.loads((tmp_path / "segmentation/window-0000-attempt-0.json").read_text(encoding="utf-8")) == provider(original)


def test_repair_hints_do_not_become_a_mandatory_old_label_or_category_whitelist(tmp_path: Path) -> None:
    client, requests = mock_client(rejected_proposal(), corrected_proposal("new_shoe", "鞋子"))
    with client:
        result = run(tmp_path, client)
    assert len(requests) == 2 and len(result["products"]) == 1
    assert result["products"][0]["name"] == "鞋子"
    assert all(s["identity"]["status"] == "identified" for s in result["segments"])
    validate_analysis_result(result)


def test_still_invalid_repair_remains_rejected_and_never_gets_a_third_request(tmp_path: Path) -> None:
    invalid = rejected_proposal()
    client, requests = mock_client(invalid, copy.deepcopy(invalid))
    with client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as caught:
                run(tmp_path, client, max_calls=3)
            assert caught.value.code == "INVALID_MODEL_OUTPUT"
    assert len(requests) == 2 and not (tmp_path / "analysis-result.json").exists()
    assert not (tmp_path / "segmentation/window-0000-accepted.json").exists()
    state = json.loads((tmp_path / "segmentation/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 2 and state["completed_windows"] == []


def test_structural_hint_does_not_raise_the_request_budget_or_trigger_replay_calls(tmp_path: Path) -> None:
    client, requests = mock_client(rejected_proposal())
    with client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as caught:
                run(tmp_path, client, max_calls=1)
            assert caught.value.code == "BUDGET_EXHAUSTED"
    assert len(requests) == 1 and not (tmp_path / "analysis-result.json").exists()
    assert not (tmp_path / "segmentation/window-0000-attempt-1.json").exists()


def test_duplicate_json_is_not_echoed_to_the_actual_repair_request(tmp_path: Path) -> None:
    attack = "MODEL_DUPLICATE_KEY_PROSE_NEVER_ECHO"
    content = '{"products":[],"assignments":{},"assignments":{"' + attack + '":{}}}'
    fixed = {"products": [], "assignments": {"u1": assignment([], "non_product"),
                                             "u2": assignment([], "non_product")}}
    client, requests = mock_client(content, fixed)
    with client:
        result = run(tmp_path, client)
    assert len(requests) == 2 and len(result["products"]) == 0
    initial, repaired = [row["input"]["messages"] for row in requests]
    assert repaired[:len(initial)] == initial
    assert attack not in "\n".join(message["content"] for message in repaired[len(initial):])
    assert all(not row["repair_product_merges"] for row in repair_json_messages(initial, repaired))
    assert json.loads((tmp_path / "segmentation/window-0000-attempt-0.json").read_text(encoding="utf-8")) == provider(content)
