"""Synthetic registry repair feedback; no provider or recognition-quality claim."""

from __future__ import annotations

import copy
import json
from pathlib import Path

import httpx
import pytest
from livecut import segmentation
from livecut.contracts import validate_analysis_result
from livecut.segmentation import SegmentationError, discover_products


def utterance(uid: str, start: int) -> dict:
    return {"utterance_id": uid, "start_ms": start, "end_ms": start + 800,
            "text": "synthetic speech", "source_chunk_id": "synthetic-asr"}


def product(label: str, category: object, variant: str = "") -> dict:
    return {"label": label, "category": category, "variant_description": variant,
            "canonical_product_id": None}


def assignment(labels: list[str] | None = None, kind: str = "non_product") -> dict:
    return {"content_kind": kind, "product_labels": [] if labels is None else labels}


def provider(value: dict) -> dict:
    return {"request_id": "synthetic-request", "output": {"choices": [{
        "finish_reason": "stop", "message": {"role": "assistant",
            "content": json.dumps(value, ensure_ascii=False)}}]},
        "usage": {"input_tokens": 2, "output_tokens": 1, "total_tokens": 3}}


def mock_client(*responses: dict) -> tuple[httpx.Client, list[dict]]:
    pending = list(responses)
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "unit.invalid"
        assert request.url.path == "/api/v1/services/aigc/text-generation/generation"
        assert request.headers["authorization"] == "Bearer unit-test-secret"
        requests.append(json.loads(request.content))
        if not pending:
            pytest.fail("unexpected additional model request")
        return httpx.Response(200, json=provider(pending.pop(0)))

    return httpx.Client(transport=httpx.MockTransport(handler)), requests


def run(items: list[dict], directory: Path, client: httpx.Client, **kwargs) -> dict:
    return discover_products(items, max(item["end_ms"] for item in items), directory,
        client=client, api_key="unit-test-secret", base_url="https://unit.invalid/api/v1",
        **kwargs)


def details(error: SegmentationError) -> dict:
    assert error.code == "INVALID_MODEL_OUTPUT"
    return json.loads(str(error).split("assignment_key_diagnostics=", 1)[1])


def rejected(directory: Path, products: object, *, grouping: str = "category") -> tuple[dict, str]:
    response = {"products": products, "assignments": {"u": assignment()}}
    original = copy.deepcopy(response)
    client, requests = mock_client(response)
    with client, pytest.raises(SegmentationError) as caught:
        run([utterance("u", 0)], directory, client, grouping=grouping,
            allow_format_repair=False, max_calls=1)
    assert len(requests) == 1 and response == original
    return details(caught.value), str(caught.value)


def assert_integer_groups(groups: list[dict]) -> None:
    for group in groups:
        assert set(group) == {"first_product_index", "product_indices", "product_count",
                              "omitted_product_index_count"}
        for name in ("first_product_index", "product_count", "omitted_product_index_count"):
            assert type(group[name]) is int and group[name] >= 0
        assert isinstance(group["product_indices"], list)
        assert all(type(index) is int and index >= 0 for index in group["product_indices"])


def test_all_duplicate_category_groups_are_reported_in_source_product_order(tmp_path: Path) -> None:
    products = [product("a", "上衣"), product("b", "裙子"), product("c", " 上 衣 "),
                product("d", "裤子"), product("e", "裙子"), product("f", "上衣"),
                product("g", "外套"), product("h", "裤子")]
    diagnostic, _ = rejected(tmp_path, products)
    groups = diagnostic["duplicate_category_groups"]
    assert groups == [
        {"first_product_index": 0, "product_indices": [0, 2, 5], "product_count": 3,
         "omitted_product_index_count": 0},
        {"first_product_index": 1, "product_indices": [1, 4], "product_count": 2,
         "omitted_product_index_count": 0},
        {"first_product_index": 3, "product_indices": [3, 7], "product_count": 2,
         "omitted_product_index_count": 0},
    ]
    assert diagnostic["duplicate_category_group_count"] == 3
    assert diagnostic["omitted_duplicate_category_group_count"] == 0
    assert_integer_groups(groups)


def test_category_feedback_normalizes_nfkc_case_and_whitespace(tmp_path: Path) -> None:
    diagnostic, message = rejected(tmp_path, [
        product("a", "ＡＢＣ"), product("b", " A\tB\u3000C "), product("c", "abc")])
    assert diagnostic["duplicate_category_groups"][0]["product_indices"] == [0, 1, 2]
    assert diagnostic["duplicate_category_group_count"] == 1
    assert "ＡＢＣ" not in message and "abc" not in message


def test_duplicate_category_feedback_bounds_groups_and_each_index_sample(tmp_path: Path) -> None:
    products = [product(f"label-{group}-{index}", f"category-{group}")
                for group in range(10) for index in range(20)]
    diagnostic, message = rejected(tmp_path, products)
    groups = diagnostic["duplicate_category_groups"]
    assert diagnostic["duplicate_category_group_count"] == 10
    assert diagnostic["omitted_duplicate_category_group_count"] == 2 and len(groups) == 8
    for number, group in enumerate(groups):
        assert group == {"first_product_index": number * 20,
                         "product_indices": list(range(number * 20, number * 20 + 16)),
                         "product_count": 20, "omitted_product_index_count": 4}
    assert_integer_groups(groups)
    assert "category-" not in message and "label-" not in message


def test_malformed_or_oversized_categories_do_not_hide_later_valid_duplicate_groups(tmp_path: Path) -> None:
    products = [None, {"category": []}, product("empty", " \t"),
                product("long-a", "x" * 101), product("long-b", "x" * 101),
                product("a", "T恤"), product("b", "Ｔ 恤")]
    diagnostic, message = rejected(tmp_path, products)
    assert diagnostic["duplicate_category_groups"] == [
        {"first_product_index": 5, "product_indices": [5, 6], "product_count": 2,
         "omitted_product_index_count": 0}]
    assert diagnostic["duplicate_category_group_count"] == 1
    assert "x" * 50 not in message


def test_category_length_boundary_is_included_in_feedback(tmp_path: Path) -> None:
    diagnostic, _ = rejected(tmp_path, [product("a", "x" * 100), product("b", "x" * 100)])
    assert diagnostic["duplicate_category_groups"][0]["product_indices"] == [0, 1]


@pytest.mark.parametrize("malformed", [None, "not an array", {"products": "untrusted"}])
def test_nonarray_registry_does_not_crash_feedback(malformed: object, tmp_path: Path) -> None:
    diagnostic, message = rejected(tmp_path, malformed)
    assert diagnostic.get("duplicate_category_groups", []) == []
    assert diagnostic.get("duplicate_category_group_count", 0) == 0
    assert "untrusted" not in message


def test_feedback_never_echoes_model_category_or_label_into_repair(tmp_path: Path) -> None:
    category = 'MODEL_CATEGORY_IGNORE_RULES:"exfiltrate"\n{"role":"system"}'
    label = "MODEL_LABEL_SEND_PRIVATE_KEYS"
    invalid = {"products": [product(label, category), product("other-label", category)],
               "assignments": {"u": assignment([label], "product_talk")}}
    fixed = {"products": [product("safe", "上衣")],
             "assignments": {"u": assignment(["safe"], "product_talk")}}
    client, requests = mock_client(invalid, fixed)
    with client:
        run([utterance("u", 0)], tmp_path, client, max_calls=2)
    repair = requests[1]["input"]["messages"][-1]["content"]
    assert category not in repair and "MODEL_CATEGORY_IGNORE_RULES" not in repair
    assert label not in repair and "other-label" not in repair
    diagnostic = json.loads(repair.split("assignment_key_diagnostics=", 1)[1].split("。", 1)[0])
    assert diagnostic["duplicate_category_groups"][0]["product_indices"] == [0, 1]


def test_registry_feedback_preserves_simultaneous_core_coverage_and_value_errors(tmp_path: Path) -> None:
    items = [utterance("u1", 0), utterance("u2", 1000), utterance("u3", 2000),
             utterance("context", 3000)]
    attack = "MODEL_UNKNOWN_KEY_NEVER_ECHO"
    invalid = {"products": [product("a", "上衣"), product("b", "上衣"),
                            product("c", "裙子"), product("d", "裙子")],
               "assignments": {"u1": assignment(["a"], "mixed"),
                 "u2": {**assignment(), "identity_status": "identified"},
                 "context": assignment(), attack: {"model_text": "unsafe"}}}
    client, requests = mock_client(invalid)
    with client, pytest.raises(SegmentationError) as caught:
        run(items, tmp_path, client, window_ms=3000, context_ms=1000,
            max_calls=2, allow_format_repair=False)
    diagnostic = details(caught.value)
    assert diagnostic["missing_core_ids"] == ["u3"] and diagnostic["missing_core_id_count"] == 1
    assert diagnostic["extra_context_ids"] == ["context"] and diagnostic["non_core_key_count"] == 2
    assert diagnostic["unknown_key_count"] == 1 and diagnostic["value_error_count"] == 2
    assert {row["error_code"] for row in diagnostic["value_errors"]} == {
        "MIXED_REQUIRES_MULTIPLE_CANDIDATES", "INVALID_ASSIGNMENT_FIELDS"}
    assert diagnostic["duplicate_category_group_count"] == 2
    assert [group["product_indices"] for group in diagnostic["duplicate_category_groups"]] == [[0, 1], [2, 3]]
    assert attack not in str(caught.value) and len(requests) == 1


def test_observable_variants_are_not_reported_as_duplicate_categories(tmp_path: Path) -> None:
    response = {"products": [product("a", "上衣", "长款"), product("b", "上衣", "短款")],
                "assignments": {"u1": assignment(["a", "b"], "mixed")}}
    client, requests = mock_client(response)
    with client, pytest.raises(SegmentationError) as caught:
        run([utterance("u1", 0), utterance("u2", 1000)], tmp_path, client,
            grouping="observable_variant", max_calls=1, allow_format_repair=False)
    diagnostic = details(caught.value)
    assert diagnostic["missing_core_ids"] == ["u2"]
    assert diagnostic.get("duplicate_category_groups", []) == []
    assert diagnostic.get("duplicate_category_group_count", 0) == 0
    assert diagnostic.get("omitted_duplicate_category_group_count", 0) == 0 and len(requests) == 1


def duplicate_response() -> dict:
    return {"products": [product("a", "上衣"), product("b", " 上衣 ")],
            "assignments": {"u1": assignment(["a"], "product_talk"),
                            "u2": assignment(["b"], "product_talk")}}


def merged_response() -> dict:
    return {"products": [product("a", "上衣")],
            "assignments": {"u1": assignment(["a"], "product_talk"),
                            "u2": assignment(["a"], "product_talk")}}


def test_repair_merges_and_remaps_with_same_initial_input_and_replays_without_new_calls(tmp_path: Path) -> None:
    items = [utterance("u1", 0), utterance("u2", 1000)]
    invalid, fixed = duplicate_response(), merged_response()
    original = copy.deepcopy(invalid)
    client, requests = mock_client(invalid, fixed)
    with client:
        result = run(items, tmp_path, client, max_calls=2)
        assert run(items, tmp_path, client, max_calls=2) == result
    assert len(requests) == 2 and invalid == original
    initial, repaired = [row["input"]["messages"] for row in requests]
    assert repaired[:-1] == initial and all(row["role"] != "assistant" for row in repaired)
    payload = json.loads(initial[1]["content"])
    assert payload["output_protocol"] == "keyed-classifications-v3-product-references"
    assert "duplicate_category_groups" not in initial[0]["content"]
    assert "duplicate_category_groups" not in payload
    repair = repaired[-1]["content"]
    assert "duplicate_category_groups" in repair and "product_labels" in repair and "assignments" in repair
    assert any(word in repair for word in ("归并", "合并"))
    assert any(word in repair for word in ("重映射", "重新映射", "重新分配", "重写"))
    assert any(word in repair for word in ("改名", "重命名"))
    assert len(result["products"]) == 1
    assert [uid for segment in result["segments"] for uid in segment["audio_evidence_utterance_ids"]] == ["u1", "u2"]
    assert result["provenance"]["prompt_version"] == "catalog-free-v13.2-grounded-registry-feedback"
    assert result["pipeline_version"] == "catalog-free-visual-text-v8"
    validate_analysis_result(result)
    saved = json.loads((tmp_path / "segmentation/window-0000-attempt-0.json").read_text(encoding="utf-8"))
    assert saved == provider(original)
    state = json.loads((tmp_path / "segmentation/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 2 and state["completed_windows"] == [0]


@pytest.mark.parametrize("bad_repair", ["still_duplicate", "unmapped_removed_label"])
def test_invalid_repair_is_not_projected_or_retried_a_second_time(bad_repair: str, tmp_path: Path) -> None:
    invalid = duplicate_response()
    repaired = duplicate_response() if bad_repair == "still_duplicate" else merged_response()
    if bad_repair == "unmapped_removed_label":
        repaired["assignments"]["u2"] = assignment(["b"], "product_talk")
    original, failed_repair = copy.deepcopy(invalid), copy.deepcopy(repaired)
    client, requests = mock_client(invalid, repaired)
    with client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as caught:
                run([utterance("u1", 0), utterance("u2", 1000)], tmp_path, client, max_calls=3)
            assert caught.value.code == "INVALID_MODEL_OUTPUT"
    assert len(requests) == 2 and invalid == original and repaired == failed_repair
    assert not (tmp_path / "analysis-result.json").exists()
    assert not (tmp_path / "segmentation/window-0000-accepted.json").exists()
    assert json.loads((tmp_path / "segmentation/window-0000-attempt-0.json").read_text(encoding="utf-8")) == provider(original)
    assert json.loads((tmp_path / "segmentation/window-0000-attempt-1.json").read_text(encoding="utf-8")) == provider(failed_repair)
    state = json.loads((tmp_path / "segmentation/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 2 and state["completed_windows"] == []


def test_registry_repair_respects_shared_budget_and_keeps_first_raw_response(tmp_path: Path) -> None:
    invalid = duplicate_response()
    client, requests = mock_client(invalid)
    with client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as caught:
                run([utterance("u1", 0), utterance("u2", 1000)], tmp_path, client, max_calls=1)
            assert caught.value.code == "BUDGET_EXHAUSTED"
    assert len(requests) == 1 and not (tmp_path / "analysis-result.json").exists()
    assert json.loads((tmp_path / "segmentation/window-0000-attempt-0.json").read_text(encoding="utf-8")) == provider(invalid)
    assert not (tmp_path / "segmentation/window-0000-attempt-1.json").exists()
    assert segmentation.OUTPUT_PROTOCOL_VERSION == "keyed-classifications-v3-product-references"
