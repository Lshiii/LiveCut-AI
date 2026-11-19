"""Synthetic providers check source acquisition/control, never semantic accuracy."""
import copy
import json
from pathlib import Path

import httpx
import pytest
from jsonschema import Draft202012Validator
from livecut.segmentation import SegmentationError, _json
from livecut.video_agent import (
    COVERAGE_PROMPT_VERSION,
    PROMPT_VERSION,
    AgentOutputError,
    build_video_answer,
    validate_agent_answer,
)
from livecut.video_agent_coverage import expand_search_context, question_parts
from test_video_agent_audit import envelope, payload, tool


def source():
    texts = [f"其他独立话题{i}。" for i in range(40)]
    texts[6] = "甲方法输入单条指令，输出结构向量；乙方法输入指令和候选记录的文本对，输出匹配分值。"
    texts[7] = "档案库可以预先建立索引。"
    texts[8] = "先从索引召回候选条目。"
    texts[9] = "再对每一候选逐一打分并排序。"
    texts[12] = "因为它每对都要推断，开销更高。"
    texts[13] = "所以速度较低而精度较好。"
    return [{"utterance_id": f"sentence-{i:03}", "source_chunk_id": "original-audio",
             "start_ms": i * 12000, "end_ms": i * 12000 + 11000, "text": text}
            for i, text in enumerate(texts)]


def arguments(path, **extra):
    return {"utterances": source(), "topics": [], "duration_ms": 480000,
        "question": "两种方法分别喂什么、产出什么；处理先后和省时原因是什么？",
        "output_dir": path, "session_id": "session-1", "turn_id": "turn-1", "job_id": "job-1",
        "video_id": "video-1", "source_hash": "a" * 64, "transcript_hash": "b" * 64,
        "api_key": "synthetic-key", "base_url": "https://synthetic.invalid", "qa_policy": "coverage_v1", **extra}


def answer(messages, *, ids=("sentence-006",), status="SUPPORTED", text="合成测试回答。", scope="VIDEO"):
    return {"type": "answer", "answer_scope": scope, "coverage": [
        {"part_id": p["part_id"], "status": status, "text": text, "citation_ids": list(ids)}
        for p in payload(messages)["question_parts"]], "suggested_clip_instruction": None}


def run(path, replies, *, repeat=False, **extra):
    requests = []
    def handler(request):
        wire = json.loads(request.content)
        messages = wire.get("messages", wire.get("input", {}).get("messages"))
        requests.append(messages)
        assert len(requests) <= len(replies), "Unexpected extra provider call"
        response = replies[len(requests) - 1]
        return httpx.Response(200, json=envelope(response(messages) if callable(response) else response))
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = build_video_answer(**arguments(path, client=client, **extra))
        if repeat:
            assert build_video_answer(**arguments(path, client=client, **extra)) == result
    return result, requests


def test_new_search_acquires_definitions_before_a_causal_hit_and_projects_old_wire(tmp_path):
    original = copy.deepcopy(source())
    def final(messages):
        observation = payload(messages)["observations"][0]["result"]
        by_id = {u["utterance_id"]: u for u in observation["utterances"]}
        assert by_id["sentence-006"] == original[6]
        assert observation["context_expansion"]["origin"] == "PROGRAM_CONTEXT"
        assert payload(messages)["question_parts"] == question_parts(arguments(tmp_path)["question"])
        return answer(messages)
    result, requests = run(tmp_path, [tool("search_transcript", query="速度 开销 精度", top_k=2), final], repeat=True)
    assert len(requests) == 2
    assert source() == original
    assert result["schema_version"] == "1.0"
    assert result["provenance"]["prompt_version"] == COVERAGE_PROMPT_VERSION
    assert "coverage" not in result and "question_parts" not in result
    assert result["tool_calls"][0]["arguments"] == {"query": "速度 开销 精度", "top_k": 2}
    assert "sentence-006" in result["tool_calls"][0]["result_utterance_ids"]
    assert result["citations"][0] == {k: original[6][k] for k in ("utterance_id", "start_ms", "end_ms", "text")}
    schema = json.loads((Path(__file__).resolve().parents[2] / "contracts/agent-answer.schema.json").read_text("utf-8"))
    Draft202012Validator(schema).validate(result)
    validate_agent_answer(result, original, expected_prompt=COVERAGE_PROMPT_VERSION)
    with pytest.raises(AgentOutputError, match="RESULT_PROVENANCE"):
        validate_agent_answer(result, original, expected_prompt=PROMPT_VERSION)


@pytest.mark.parametrize("question", [
    "先讲输入输出，再说过程和为什么省时", "分别喂什么、产出什么；处理先后和省时原因", "原因？条件？例外？来源？还有什么限制？",
])
def test_question_parts_keep_generic_requests_without_source_specific_vocabulary(question):
    parts = question_parts(question)
    assert 1 <= len(parts) <= 4
    assert [p["part_id"] for p in parts] == [f"p{i}" for i in range(1, len(parts) + 1)]
    for fragment in question.replace("？", "；").replace("、", "；").split("；"):
        if fragment:
            assert fragment in "；".join(p["question"] for p in parts)


def test_available_budget_requests_one_real_supplement_then_allows_explicit_local_gap(tmp_path):
    def insufficient(m):
        return answer(m, ids=(), status="INSUFFICIENT", text="本轮原句还不能支持这部分。", scope="INSUFFICIENT")
    result, requests = run(tmp_path, [tool("search_transcript", query="notpresentzzzz", top_k=1),
        insufficient, tool("search_transcript", query="notpresentxxxx", top_k=1), insufficient])
    assert len(requests) == 4
    assert json.loads(requests[2][-1]["content"])["validation_rule"] == "QUESTION_COVERAGE_NEEDS_CHECK"
    assert len(result["tool_calls"]) == 2 and not result["citations"]
    assert all(not row["result_utterance_ids"] for row in result["tool_calls"])
    assert "仅表示本轮已查看证据不足" in result["answer"]
    assert payload(requests[-1])["supplement_checked"] is True


def test_budget_one_never_adds_hidden_query_or_repair(tmp_path):
    def final(messages):
        assert payload(messages)["remaining_tool_steps"] == 0
        return answer(messages, ids=(), status="INSUFFICIENT", text="本轮没有可用于回答的原句。", scope="INSUFFICIENT")
    result, requests = run(tmp_path, [final], max_calls=1)
    assert len(requests) == 1 and not result["tool_calls"]


@pytest.mark.parametrize("damage,rule", [
    (lambda a: a["coverage"].pop(), "QUESTION_COVERAGE_STRUCTURE"),
    (lambda a: a["coverage"][0].update(citation_ids=[{}]), "QUESTION_COVERAGE_EVIDENCE"),
    (lambda a: a["coverage"][0].update(citation_ids=[["sentence-006"]]), "QUESTION_COVERAGE_EVIDENCE"),
    (lambda a: a["coverage"][0].update(citation_ids=["unseen-other-source"]), "QUESTION_COVERAGE_EVIDENCE"),
    (lambda a: a["coverage"][0].update(text="视频没有逐字说明这些内容。"), "UNSUPPORTED_GLOBAL_ABSENCE"),
])
def test_invalid_internal_coverage_gets_only_existing_repair_opportunity(tmp_path, damage, rule):
    def bad(messages):
        value = answer(messages)
        damage(value)
        return value
    result, requests = run(tmp_path, [tool("read_context", utterance_id="sentence-006", before=0, after=0), bad, answer])
    assert len(requests) == 3
    assert json.loads(requests[-1][-1]["content"])["validation_rule"] == rule
    assert result["citations"][0]["utterance_id"] == "sentence-006"


def test_long_first_cluster_cannot_consume_every_later_cluster_and_no_partial_sentence_is_exposed():
    items = source()
    items[0]["text"] = "长" * 4000
    candidates = [{"utterance_ids": [items[i]["utterance_id"]]} for i in (0, 25)]
    result = expand_search_context(items, candidates, allowance=9000, remaining_context=9000)
    ids = {u["utterance_id"] for u in result["utterances"]}
    assert items[0]["utterance_id"] in ids and items[25]["utterance_id"] in ids
    assert len(_json(result)) <= 9000
    actual = {u["utterance_id"]: u for u in items}
    assert all(u == actual[u["utterance_id"]] for u in result["utterances"])
    assert set(result["context_expansion"]["seed_utterance_ids"]) <= ids


def test_context_expansion_respects_real_time_gap_and_sentence_count_bounds():
    items = source()
    for item in items[6:]:
        item["start_ms"] += 180000
        item["end_ms"] += 180000
    result = expand_search_context(items, [{"utterance_ids": ["sentence-013"]}],
                                   allowance=18000, remaining_context=18000)
    ids = {u["utterance_id"] for u in result["utterances"]}
    assert "sentence-005" not in ids  # Eight sentences away, but beyond 120s.
    assert "sentence-004" not in ids and "sentence-018" not in ids
    assert "sentence-006" in ids and "sentence-017" in ids


def test_unknown_provider_call_is_never_reissued_without_ledger_backed_transport(tmp_path):
    hits = []
    def fail(request):
        hits.append(1)
        raise httpx.ReadTimeout("synthetic unknown", request=request)
    with httpx.Client(transport=httpx.MockTransport(fail)) as client:
        with pytest.raises(SegmentationError) as first:
            build_video_answer(**arguments(tmp_path, client=client))
        assert first.value.code == "PROVIDER_ERROR"
        with pytest.raises(SegmentationError) as second:
            build_video_answer(**arguments(tmp_path, client=client))
        assert second.value.code == "CALL_STATE_UNCERTAIN"
    assert hits == [1]
    state = json.loads((tmp_path / "agent-coverage-v1/state.json").read_text())
    assert len(state["calls"]) == 1 and state["calls"][0]["status"] == "uncertain"


@pytest.mark.parametrize("damage", ["response", "plan", "state_missing", "question_changed"])
def test_changed_or_orphaned_coverage_cache_never_starts_new_model_work(tmp_path, damage):
    run(tmp_path, [tool("read_context", utterance_id="sentence-006", before=0, after=0), answer])
    directory = tmp_path / "agent-coverage-v1"
    extra = {}
    if damage == "response":
        (directory / "window-0000-attempt-0.json").write_text("{}")
    elif damage == "plan":
        (directory / "request-plans.json").unlink()
    elif damage == "state_missing":
        (directory / "state.json").unlink()
    else:
        extra["question"] = "另一个问题"
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [], **extra)
    assert failure.value.code == "INVALID_AGENT_CHECKPOINT"


def test_success_cache_replays_without_current_budget_read_or_new_model_call(tmp_path):
    result, _ = run(tmp_path, [tool("read_context", utterance_id="sentence-006", before=0, after=0), answer],
                    budget_remaining=lambda: 8)
    def unavailable():
        pytest.fail("Complete frozen requests must replay without new budget reads")
    replayed, requests = run(tmp_path, [], budget_remaining=unavailable)
    assert replayed == result and not requests


def test_coverage_policy_unknown_budget_rejects_before_provider(tmp_path):
    with pytest.raises(SegmentationError) as failure:
        run(tmp_path, [], budget_remaining=lambda: None)
    assert failure.value.code == "COVERAGE_BUDGET_UNAVAILABLE"


def test_new_policy_uses_independent_directory_without_rewriting_legacy_cache(tmp_path):
    legacy = tmp_path / "agent"
    legacy.mkdir()
    (legacy / "state.json").write_text("legacy sentinel", encoding="utf-8")
    before = (legacy / "state.json").read_bytes()
    run(tmp_path, [tool("read_context", utterance_id="sentence-006", before=0, after=0), answer])
    assert (legacy / "state.json").read_bytes() == before
    assert (tmp_path / "agent-coverage-v1/state.json").exists()


def test_offer_check_finds_amount_without_repeating_product_name_and_preserves_price_type(tmp_path):
    from livecut.agent_offers import price_context, price_query, product_candidates

    items = source()
    items[1]["text"] = "这款新商品叫桐花蜜，也有人叫它春蜜。"
    items[3]["text"] = "今天活动价是两瓶68元，下单选两瓶装。"
    items[26]["text"] = "另外还有晴山红茶，卖点是花香。"
    items[27]["text"] = "赠送小袋的建议零售价是8元一袋。"
    items[29]["text"] = "折算下来每罐20元。"
    query = "核价：春蜜|桐花蜜@两瓶；晴山红茶"
    candidates = product_candidates(items, price_query(query), 6)
    fetched = expand_search_context(items, candidates, allowance=18000, remaining_context=18000)
    by_id = {row["utterance_id"]: row for row in fetched["utterances"]}
    assert all(items[i] == by_id[f"sentence-{i:03}"] for i in (1, 3, 26, 27, 29))
    assert price_context(fetched["utterances"]) == [
        {"utterance_id": "sentence-027", "cue": "参考定价措辞"},
        {"utterance_id": "sentence-029", "cue": "算术或折算措辞"}]

    def early(messages):
        first = payload(messages)["observations"][0]["result"]["utterances"][0]["utterance_id"]
        return answer(messages, ids=[first])

    def focused(messages):
        assert json.loads(messages[-1]["content"])["validation_rule"] == "PRODUCT_OFFER_LOOKUP_REQUIRED"
        return tool("search_transcript", query=query, top_k=6)

    def checked(messages):
        result = payload(messages)["observations"][-1]["result"]
        assert result["price_context"] == price_context(result["utterances"])
        return answer(messages, ids=["sentence-003", "sentence-027", "sentence-029"])

    result, reqs = run(tmp_path, [tool("search_transcript", query="在售商品", top_k=2), early, focused, checked],
        utterances=items, question="在售商品有哪些，具体报价是多少？", repeat=True, max_calls=5)
    assert [step["arguments"]["query"] for step in result["tool_calls"]] == ["在售商品", query]
    assert len(reqs) == 4 and len(result["citations"]) == 3


def test_pre_upgrade_price_turn_replays_frozen_ledger(tmp_path, monkeypatch):
    import livecut.video_agent_coverage as coverage

    with monkeypatch.context() as patch:
        patch.setattr(coverage, "wants_product_prices", lambda *_: False)
        legacy, _ = run(tmp_path, [tool("read_context", utterance_id="sentence-006", before=0, after=0), answer],
                        question="商品的价格？")
    replay, reqs = run(tmp_path, [], question="商品的价格？")
    assert replay == legacy and not reqs


def test_amount_and_pack_across_same_chapter_precede_capacity_and_other_product():
    from livecut.agent_offers import product_candidates

    items = source()
    items[1]["text"] = "这款是山花蜜，两瓶58元。"
    items[10]["text"] = "现在68是两罐，双罐套装。"
    items[24]["text"] = "春茶一盒是3到5包。这是新茶。"
    groups = [(["春茶"], "盒")]
    topics = [{"start_utterance_id": "sentence-005", "end_utterance_id": "sentence-029"},
              {"start_utterance_id": "sentence-000", "end_utterance_id": "sentence-004"}]
    hits = product_candidates(items, groups, 1, topics=topics)
    assert "sentence-010" in hits[0]["utterance_ids"] and "sentence-024" in hits[0]["utterance_ids"]
    assert "sentence-001" not in hits[0]["utterance_ids"]


def test_only_source_attested_short_name_can_recover_a_later_price():
    from livecut.agent_offers import product_candidates

    items = source()
    items[1]["text"] = "我们的云谷青梅低糖汁已经上架了。"
    items[2]["text"] = "这个青梅低糖汁新品是青色玻璃瓶，这个青梅汁挺清爽的。"
    items[27]["text"] = "刚才说的青梅汁，一瓶活动价28元。"
    groups = [(["云谷青梅低糖汁"], "")]
    assert "sentence-027" in product_candidates(items, groups, 1)[0]["utterance_ids"]
    items[2]["text"] = "没有加糖。"
    assert "sentence-027" not in product_candidates(items, groups, 1)[0]["utterance_ids"]


def test_explicit_packaging_question_keeps_source_weight_and_gift_next_to_quote():
    from livecut.agent_offers import product_candidates

    items = source()
    items[6]["text"] = "这款是盐焗杏仁，一袋是200克。"
    items[8]["text"] = "买两袋58元到手。"
    items[13]["text"] = "四袋还送两包果干。"
    hits = product_candidates(items, [(["盐焗杏仁"], "")], 1, question="有哪几种规格和买赠条件？")
    assert {"sentence-006", "sentence-008", "sentence-013"} <= set(hits[0]["utterance_ids"])


def test_price_question_with_one_call_respects_original_budget(tmp_path):
    def final(messages):
        assert not payload(messages)["remaining_tool_steps"]
        return answer(messages, scope="INSUFFICIENT", status="INSUFFICIENT", ids=())
    result, reqs = run(tmp_path, [final], question="直播商品的价格？", max_calls=1)
    assert len(reqs) == 1 and not result["tool_calls"]
