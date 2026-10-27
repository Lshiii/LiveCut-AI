"""Synthetic canonical product references; no recognition score or real model calls."""

from __future__ import annotations

import copy
import json
from pathlib import Path

import httpx
import pytest
from livecut.contracts import validate_analysis_result
from livecut.segmentation import SegmentationError, discover_products


def utterance(uid: str, start: int, end: int | None = None) -> dict:
    return {"utterance_id": uid, "start_ms": start, "end_ms": start + 800 if end is None else end,
            "text": f"synthetic speech {uid}", "source_chunk_id": "synthetic-asr"}


def product(label: str, category: str, canonical: object = None, variant: str = "") -> dict:
    return {"label": label, "category": category, "variant_description": variant,
            "canonical_product_id": canonical}


def assigned(products: list[dict], *rows: tuple[str, list[str], str]) -> dict:
    return {"products": products, "assignments": {
        uid: {"content_kind": kind, "product_labels": labels} for uid, labels, kind in rows}}


def single(uid: str, value: dict) -> dict:
    return assigned([value], (uid, [value["label"]], "product_talk"))


def non_product(uid: str, declarations: list[dict] | None = None) -> dict:
    return assigned(declarations or [], (uid, [], "non_product"))


def mock_client(*responses: dict) -> tuple[httpx.Client, list[dict]]:
    remaining = list(responses)
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "unit.invalid"
        assert request.url.path == "/api/v1/services/aigc/text-generation/generation"
        assert request.headers["authorization"] == "Bearer unit-test-secret"
        requests.append(json.loads(request.content))
        if not remaining:
            pytest.fail("unexpected extra model call")
        raw = {"output": {"choices": [{"finish_reason": "stop", "message": {
            "role": "assistant", "content": json.dumps(remaining.pop(0), ensure_ascii=False)}}]},
            "usage": {"input_tokens": 2, "output_tokens": 1, "total_tokens": 3}}
        return httpx.Response(200, json=raw)

    return httpx.Client(transport=httpx.MockTransport(handler)), requests


def run(items: list[dict], directory: Path, client: httpx.Client, **kwargs) -> dict:
    duration = max(item["end_ms"] for item in items)
    options = {"window_ms": 1000, "context_ms": 0, **kwargs}
    return discover_products(items, duration, directory, client=client,
                             api_key="unit-test-secret", base_url="https://unit.invalid/api/v1",
                             **options)


def payloads(requests: list[dict]) -> list[dict]:
    return [json.loads(request["input"]["messages"][1]["content"]) for request in requests]


def test_synonym_return_reuses_canonical_identity_without_swallowing_other_content(
    tmp_path: Path,
) -> None:
    items = [utterance(f"u{i}", i * 1000) for i in range(5)]
    original = copy.deepcopy(items)
    responses = [single("u0", product("spray", "宠物口腔清新喷雾")),
                 single("u1", product("detergent", "洗碗精")), non_product("u2"),
                 single("u3", product("returned", "口腔清洁喷雾", "product-0001")),
                 single("u4", product("known-alias", " 口腔 清洁喷雾 "))]
    client, requests = mock_client(*responses)
    with client:
        first = run(items, tmp_path, client)
        resumed = run(items, tmp_path, client)
    assert len(requests) == 5 and first == resumed and items == original
    assert first["schema_version"] == "2.0" and first["review_status"] == "DRAFT"
    assert len(first["products"]) == 2
    spray, detergent = first["products"]
    assert spray["name"] == "宠物口腔清新喷雾"
    assert spray["aliases"] == ["口腔清洁喷雾"]
    assert detergent["name"] == "洗碗精" and detergent["aliases"] == []
    assert [segment["audio_evidence_utterance_ids"] for segment in first["segments"]] == [
        ["u0"], ["u1"], ["u2"], ["u3", "u4"]]
    assert first["segments"][0]["identity"]["product_id"] == spray["product_id"]
    assert first["segments"][-1]["identity"]["product_id"] == spray["product_id"]
    assert first["segments"][1]["identity"]["product_id"] == detergent["product_id"]
    assert first["segments"][2]["content_kind"] == "non_product"
    accepted = json.loads((tmp_path / "segmentation/window-0003-accepted.json").read_text(encoding="utf-8"))
    assert accepted["products"][0]["canonical_product_id"] == "product-0001"
    assert all("canonical_product_id" not in value for value in first["products"])
    validate_analysis_result(first)


def test_known_registry_exposes_identity_and_aliases_but_new_category_stays_independent(
    tmp_path: Path,
) -> None:
    items = [utterance(f"u{i}", i * 1000) for i in range(3)]
    client, requests = mock_client(
        single("u0", product("a", "洗碗精")),
        single("u1", product("alias", "洗碗清洁剂", "product-0001")),
        single("u2", product("new", "洗碗巾")),
    )
    with client:
        result = run(items, tmp_path, client)
    prompts = payloads(requests)
    assert prompts[0]["known_products"] == []
    assert prompts[1]["known_products"] == [{
        "product_id": "product-0001", "category": "洗碗精", "variant_description": "",
        "aliases": [], "identity_granularity": "category"}]
    assert prompts[2]["known_products"][0]["aliases"] == ["洗碗清洁剂"]
    assert [value["name"] for value in result["products"]] == ["洗碗精", "洗碗巾"]
    assert result["segments"][-1]["identity"]["product_id"] == "product-0002"


def test_null_canonical_normalized_category_reuses_existing_identity(tmp_path: Path) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    client, requests = mock_client(single("u0", product("first", "PET SPRAY")),
                                   single("u1", product("second", " ＰＥＴ　ＳＰＲＡＹ ")))
    with client:
        result = run(items, tmp_path, client)
    assert len(requests) == 2 and len(result["products"]) == 1
    assert result["products"][0]["name"] == "PET SPRAY"
    assert result["products"][0]["aliases"] == []


def test_independent_discovery_does_not_receive_canonical_registry(tmp_path: Path) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    client, requests = mock_client(
        {"products": [{"category": "宠物口腔喷雾", "evidence_utterance_ids": ["u0"]}]},
        single("u0", product("first", "宠物口腔喷雾")),
        {"products": [{"category": "口腔清洁喷雾", "evidence_utterance_ids": ["u1"]}]},
        single("u1", product("alias", "口腔清洁喷雾", "product-0001")),
    )
    with client:
        result = run(items, tmp_path, client, two_pass=True, max_calls=4)
        assert run(items, tmp_path, client, two_pass=True, max_calls=4) == result
    prompts = payloads(requests)
    assert len(prompts) == 4
    assert "known_products" not in prompts[0] and "known_products" not in prompts[2]
    assert prompts[3]["known_products"][0]["product_id"] == "product-0001"
    assert len(result["products"]) == 1
    assert result["products"][0]["aliases"] == ["口腔清洁喷雾"]


@pytest.mark.parametrize("canonical", ["", "   ", 3, True, [], {}, "product-9999"])
def test_invalid_canonical_reference_is_not_coerced_or_repaired_when_disabled(
    tmp_path: Path, canonical: object,
) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    client, requests = mock_client(single("u0", product("first", "洗碗精")),
                                   single("u1", product("bad", "洗碗清洁剂", canonical)))
    with client, pytest.raises(SegmentationError) as caught:
        run(items, tmp_path, client, allow_format_repair=False)
    assert caught.value.code == "INVALID_MODEL_OUTPUT" and len(requests) == 2
    assert not (tmp_path / "analysis-result.json").exists()
    assert not (tmp_path / "segmentation/window-0001-accepted.json").exists()


def test_canonical_field_is_required_even_for_new_products(tmp_path: Path) -> None:
    declaration = product("new", "猫砂")
    del declaration["canonical_product_id"]
    client, requests = mock_client(single("u0", declaration))
    with client, pytest.raises(SegmentationError) as caught:
        run([utterance("u0", 0)], tmp_path, client, allow_format_repair=False)
    assert caught.value.code == "INVALID_MODEL_OUTPUT" and len(requests) == 1


def test_product_id_must_have_been_provided_not_merely_look_like_next_id(tmp_path: Path) -> None:
    client, requests = mock_client(single("u0", product("invented", "猫砂", "product-0001")))
    with client, pytest.raises(SegmentationError) as caught:
        run([utterance("u0", 0)], tmp_path, client, allow_format_repair=False)
    assert caught.value.code == "INVALID_MODEL_OUTPUT" and len(requests) == 1


def test_two_temporary_labels_cannot_reference_one_canonical_product(tmp_path: Path) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    duplicate = assigned([product("a", "口腔清洁喷雾", "product-0001"),
                          product("b", "宠物口腔喷雾", "product-0001")],
                         ("u1", ["a", "b"], "product_talk"))
    client, requests = mock_client(single("u0", product("first", "宠物口腔清新喷雾")), duplicate)
    with client, pytest.raises(SegmentationError) as caught:
        run(items, tmp_path, client, allow_format_repair=False)
    assert caught.value.code == "INVALID_MODEL_OUTPUT" and len(requests) == 2


@pytest.mark.parametrize("proposed_name", ["洗碗精", " 洗 碗 精 "])
def test_name_owned_by_other_entity_cannot_be_attached_to_different_id(
    tmp_path: Path, proposed_name: str,
) -> None:
    items = [utterance("spray", 0, 400), utterance("detergent", 400, 800),
             utterance("conflict", 1000)]
    first = assigned([product("spray", "宠物口腔喷雾"), product("detergent", "洗碗精")],
                     ("spray", ["spray"], "product_talk"),
                     ("detergent", ["detergent"], "product_talk"))
    client, requests = mock_client(first, single("conflict", product("bad", proposed_name, "product-0001")))
    with client, pytest.raises(SegmentationError) as caught:
        run(items, tmp_path, client, allow_format_repair=False)
    assert caught.value.code == "INVALID_MODEL_OUTPUT" and len(requests) == 2


def test_accepted_alias_cannot_later_be_reassigned_to_other_entity(tmp_path: Path) -> None:
    items = [utterance(f"u{i}", i * 1000) for i in range(4)]
    client, requests = mock_client(
        single("u0", product("spray", "宠物口腔喷雾")),
        single("u1", product("alias", "口腔清洁喷雾", "product-0001")),
        single("u2", product("detergent", "洗碗精")),
        single("u3", product("bad", "口腔清洁喷雾", "product-0002")),
    )
    with client, pytest.raises(SegmentationError) as caught:
        run(items, tmp_path, client, allow_format_repair=False)
    assert caught.value.code == "INVALID_MODEL_OUTPUT" and len(requests) == 4


def test_one_canonical_reference_repair_is_recorded_and_resumes_without_new_requests(
    tmp_path: Path,
) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    bad = single("u1", product("alias", "洗碗清洁剂", "unknown-untrusted-reference"))
    fixed = single("u1", product("alias", "洗碗清洁剂", "product-0001"))
    client, requests = mock_client(single("u0", product("first", "洗碗精")), bad, fixed)
    with client:
        result = run(items, tmp_path, client, max_calls=3)
        assert run(items, tmp_path, client, max_calls=3) == result
    assert len(requests) == 3
    assert len(requests[2]["input"]["messages"]) == 3
    assert len(result["products"]) == 1 and result["products"][0]["aliases"] == ["洗碗清洁剂"]
    directory = tmp_path / "segmentation"
    initial = json.loads((directory / "window-0001-attempt-0.json").read_text(encoding="utf-8"))
    assert json.loads(initial["output"]["choices"][0]["message"]["content"]) == bad
    accepted = json.loads((directory / "window-0001-accepted.json").read_text(encoding="utf-8"))
    assert accepted["products"][0]["canonical_product_id"] == "product-0001"
    state = json.loads((directory / "state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 3 and state["calls"][1]["validation_error"]
    validate_analysis_result(result)


def test_reference_repair_cannot_exceed_total_call_budget(tmp_path: Path) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    client, requests = mock_client(single("u0", product("first", "洗碗精")),
                                   single("u1", product("bad", "洗碗清洁剂", "unknown")))
    with client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as caught:
                run(items, tmp_path, client, max_calls=2)
            assert caught.value.code == "BUDGET_EXHAUSTED"
    assert len(requests) == 2
    assert not (tmp_path / "segmentation/window-0001-attempt-1.json").exists()


def test_invalid_reference_after_one_repair_stays_failure_on_resume(tmp_path: Path) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    bad = single("u1", product("bad", "洗碗清洁剂", "unknown"))
    client, requests = mock_client(single("u0", product("first", "洗碗精")), bad, bad)
    with client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as caught:
                run(items, tmp_path, client, max_calls=3)
            assert caught.value.code == "INVALID_MODEL_OUTPUT"
    assert len(requests) == 3
    assert not (tmp_path / "segmentation/window-0001-accepted.json").exists()
    assert not (tmp_path / "analysis-result.json").exists()


def test_variant_reference_requires_same_normalized_category_and_variant(tmp_path: Path) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    client, requests = mock_client(
        single("u0", product("first", "垃圾袋", variant="抽绳款")),
        single("u1", product("same", " 垃圾 袋 ", "product-0001", " 抽 绳款 ")),
    )
    with client:
        result = run(items, tmp_path, client, grouping="observable_variant")
    assert len(requests) == 2 and len(result["products"]) == 1
    assert result["products"][0]["identity_granularity"] == "observable_variant"
    assert result["products"][0]["attributes"]["variant_description"] == "抽绳款"


@pytest.mark.parametrize("first_variant,category,variant", [
    ("抽绳款", "垃圾袋", "平口款"),
    ("抽绳款", "保鲜袋", "抽绳款"),
    ("", "垃圾袋", "抽绳款"),
    ("抽绳款", "垃圾袋", ""),
])
def test_variant_or_granularity_mismatch_cannot_merge_by_canonical_reference(
    tmp_path: Path, first_variant: str, category: str, variant: str,
) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    client, requests = mock_client(
        single("u0", product("first", "垃圾袋", variant=first_variant)),
        single("u1", product("bad", category, "product-0001", variant)),
    )
    with client, pytest.raises(SegmentationError) as caught:
        run(items, tmp_path, client, grouping="observable_variant", allow_format_repair=False)
    assert caught.value.code == "INVALID_MODEL_OUTPUT" and len(requests) == 2


def test_different_variants_with_null_reference_remain_separate_products(tmp_path: Path) -> None:
    items = [utterance("u0", 0), utterance("u1", 1000)]
    client, _ = mock_client(single("u0", product("first", "垃圾袋", variant="抽绳款")),
                            single("u1", product("second", "垃圾袋", variant="平口款")))
    with client:
        result = run(items, tmp_path, client, grouping="observable_variant")
    assert len(result["products"]) == 2
    assert result["segments"][0]["identity"]["product_id"] != result["segments"][1]["identity"]["product_id"]


def test_unused_declarations_do_not_create_products_or_teach_aliases(tmp_path: Path) -> None:
    items = [utterance(f"u{i}", i * 1000) for i in range(3)]
    declarations = [product("unused-alias", "口腔清洁喷雾", "product-0001"),
                    product("unused-new", "合成未讲商品")]
    client, requests = mock_client(single("u0", product("first", "宠物口腔喷雾")),
                                   non_product("u1", declarations),
                                   single("u2", product("genuinely-new", "口腔清洁喷雾")))
    with client:
        result = run(items, tmp_path, client)
    assert payloads(requests)[2]["known_products"] == [{
        "product_id": "product-0001", "category": "宠物口腔喷雾", "variant_description": "",
        "aliases": [], "identity_granularity": "category"}]
    assert [value["name"] for value in result["products"]] == ["宠物口腔喷雾", "口腔清洁喷雾"]
    assert all(value["aliases"] == [] for value in result["products"])


def test_known_product_alias_prompt_is_bounded_but_result_keeps_accepted_aliases(
    tmp_path: Path,
) -> None:
    items = [utterance(f"u{i}", i * 1000) for i in range(12)]
    names = [f"合成喷雾别名{i}" for i in range(10)]
    responses = [single("u0", product("first", "宠物口腔喷雾"))]
    responses.extend(single(f"u{i + 1}", product(f"alias{i}", name, "product-0001"))
                     for i, name in enumerate(names))
    responses.append(single("u11", product("canonical-name", "宠物口腔喷雾", "product-0001")))
    client, requests = mock_client(*responses)
    with client:
        result = run(items, tmp_path, client)
        assert run(items, tmp_path, client) == result
    assert len(requests) == 12
    assert payloads(requests)[-1]["known_products"][0]["aliases"] == names[-8:]
    assert result["products"][0]["aliases"] == names


@pytest.mark.parametrize("canonical", [None, "product-0001"])
def test_registry_prompt_uses_latest_64_but_null_exact_lookup_can_reuse_older_entity(
    tmp_path: Path, canonical: str | None,
) -> None:
    items = [utterance(f"u{i}", i * 1000) for i in range(66)]
    responses = [single(f"u{i}", product(f"p{i}", f"合成类别{i}")) for i in range(65)]
    responses.append(single("u65", product("old-return", "合成类别0", canonical)))
    client, requests = mock_client(*responses)
    with client:
        if canonical is not None:
            with pytest.raises(SegmentationError) as caught:
                run(items, tmp_path, client, max_windows=70, max_calls=70, allow_format_repair=False)
            assert caught.value.code == "INVALID_MODEL_OUTPUT"
        else:
            result = run(items, tmp_path, client, max_windows=70, max_calls=70, allow_format_repair=False)
            assert len(result["products"]) == 65
            assert result["segments"][-1]["identity"]["product_id"] == "product-0001"
    assert len(requests) == 66
    latest_known = payloads(requests)[-1]["known_products"]
    assert len(latest_known) == 64
    assert [value["product_id"] for value in latest_known] == [f"product-{i:04d}" for i in range(2, 66)]
