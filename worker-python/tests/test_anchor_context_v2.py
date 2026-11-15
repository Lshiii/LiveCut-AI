"""Bounded context acquisition is tested separately from semantic model quality."""

import copy
import json
from dataclasses import replace
from pathlib import Path

import pytest
from livecut.anchor_clip import (
    AnchorSourceError,
    _index,
    answer_context_ids,
    validate_answer_source,
)
from livecut.worker import AnalysisStageExecutor, WorkerError
from test_anchor_clip import anchor_context, answer_source, response, run, utterances


def test_shared_java_python_context_and_canonical_hash_fixture():
    fixture = json.loads((Path(__file__).resolve().parents[2] / "fixtures/anchor-context-v2.json").read_text(encoding="utf-8"))
    items, source = fixture["utterances"], fixture["answer_source"]
    validate_answer_source(source, items, source["duration_ms"])
    assert _index(items, source)["index_hash"] == fixture["index_hash"]


def expanded(items=None, ids=None, **updates):
    items = utterances() if items is None else items
    value = answer_source(items=items, ids=ids, schema_version="1.1", **updates)
    value["duration_ms"] = max(value["duration_ms"], items[-1]["end_ms"])
    value["context_utterance_ids"] = answer_context_ids(items, value["utterance_ids"], "1.1")
    return value


def sequence(count=60, step=10000):
    return [{"utterance_id": f"renamed:{(i * 17 + 41):05d}", "start_ms": i * step,
             "end_ms": i * step + step - 1, "text": f"另一主题的完整原句{i}",
             "source_chunk_id": "synthetic"} for i in range(count)]


def test_expansion_sees_definition_six_sentences_before_anchor_and_keeps_same_occurrence(tmp_path):
    items = sequence()
    items[2]["text"] = items[24]["text"] = "同一个术语在不同位置出现。"
    items[18]["text"] = "此处定义讨论对象和输入输出。"
    selected = [items[24]["utterance_id"], items[25]["utterance_id"]]
    source = expanded(items, selected)
    visible = source["context_utterance_ids"]
    assert items[18]["utterance_id"] in visible
    assert items[2]["utterance_id"] not in visible
    assert visible == [row["utterance_id"] for row in items[16:34]]
    legacy = answer_source(items=items, ids=selected)
    assert legacy["context_utterance_ids"] == [row["utterance_id"] for row in items[23:27]]
    assert _index(items, source)["index_hash"] != _index(items, legacy)["index_hash"]
    validate_answer_source(source, items, source["duration_ms"])


def test_v2_plan_reads_more_context_but_does_not_automatically_keep_the_whole_window(tmp_path):
    source = expanded()
    original = copy.deepcopy(source)
    result, requests = run(tmp_path, [response()], repeat=True, answer_source=source)
    assert source == original and len(requests) == 1
    payload = json.loads(requests[0]["input"]["messages"][1]["content"])
    assert len(payload["retrieval"]["answer-source"]["utterances"]) == 12
    assert result["provenance"]["prompt_version"] == "anchor-clips-v2"
    assert result["outputs"][0]["parts"][0]["start_utterance_id"] == "u000007"
    assert result["outputs"][0]["parts"][0]["end_utterance_id"] == "u000009"
    assert not result["outputs"][0]["enabled"]
    assert "必要定义或结论仍落在窗口外" in requests[0]["input"]["messages"][0]["content"]


def test_time_bound_includes_whole_sentence_at_limit_and_stops_beyond_it():
    items = sequence(count=30, step=20000)
    selected = [items[15]["utterance_id"]]
    source = expanded(items, selected)
    assert source["context_utterance_ids"] == [row["utterance_id"] for row in items[9:22]]
    items[9]["start_ms"] -= 1
    source = expanded(items, selected)
    assert source["context_utterance_ids"] == [row["utterance_id"] for row in items[10:22]]


def test_utf16_limit_stops_blocked_direction_without_truncation_or_skipping():
    items = sequence(count=20)
    items[10]["text"] = "😀" * 5998
    items[9]["text"] = "不可截断的长句"
    items[11]["text"] = "短句"
    source = expanded(items, [items[10]["utterance_id"]])
    assert source["context_utterance_ids"] == [items[10]["utterance_id"], items[11]["utterance_id"]]
    assert items[10]["text"] == "😀" * 5998
    assert items[8]["utterance_id"] not in source["context_utterance_ids"]


def test_many_distant_anchors_stay_pinned_and_observed_context_is_capped():
    items = sequence(count=500, step=1000)
    selected = [items[i]["utterance_id"] for i in range(10, 490, 20)]
    source = expanded(items, selected)
    assert len(source["context_utterance_ids"]) == 72
    assert set(selected).issubset(source["context_utterance_ids"])
    index = _index(items, source)
    assert len(index["context_ranges"]) == 24


def test_boundary_insufficiency_remains_review_disabled_instead_of_forced_found(tmp_path):
    result, requests = run(tmp_path, [response(status="NEEDS_REVIEW", reason="定义落在可见窗口之外，无法确认完整解释。")],
                           answer_source=expanded(), max_calls=1)
    assert len(requests) == 1 and result["outputs"][0]["status"] == "NEEDS_REVIEW"
    assert not result["outputs"][0]["enabled"]


def test_legacy_checkpoint_is_not_reused_for_expanded_source(tmp_path):
    run(tmp_path, [response()], repeat=True)
    with pytest.raises(Exception, match="checkpoint"):
        run(tmp_path, [], answer_source=expanded())


@pytest.mark.parametrize("change", ["context", "version"])
def test_version_and_exact_deterministic_context_are_revalidated(change):
    source = expanded()
    if change == "context":
        source["context_utterance_ids"] = source["context_utterance_ids"][1:]
    else:
        source["schema_version"] = "1.0"
    with pytest.raises(AnchorSourceError, match="ANCHOR_CONTEXT"):
        validate_answer_source(source, utterances(), 12000)


def test_unversioned_worker_adapter_rejects_new_context_before_transport(tmp_path):
    context = anchor_context(tmp_path)
    context.lease.config["clip_request"]["answer_source"] = expanded(
        source_hash=context.lease.source_hash,
        transcript_hash=context.lease.config["clip_request"]["source_transcript_hash"],
    )
    with pytest.raises(WorkerError, match="INVALID_ANCHOR_CLIP_INPUT"):
        AnalysisStageExecutor(transport_factory=lambda *args: pytest.fail("No dispatch"))(context)
    context.lease = replace(context.lease, required_worker_protocol=5)
    with pytest.raises(WorkerError, match="DURABLE_BUDGET_REQUIRED"):
        AnalysisStageExecutor()(context)
