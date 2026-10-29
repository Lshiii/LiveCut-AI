"""Synthetic Provider protocol tests, not measured topic recognition accuracy."""
import copy
import json
import threading

import httpx
import pytest
from livecut.contracts import ContractValidationError
from livecut.model_budget import BudgetTransport
from livecut.segmentation import SegmentationError
from livecut.topic_contracts import validate_topic_result
from livecut.topics import _parse, discover_topics
from livecut.worker import Lease, LeaseGuard, StageContext, sha256
from test_worker import claim


def utterances():
    return [{"utterance_id": f"u{i}", "start_ms": i * 1000, "end_ms": i * 1000 + 900,
             "text": text, "source_chunk_id": "c1"} for i, text in enumerate([
                 "先讲缓存穿透", "不存在的数据也会打到数据库", "接下来讲缓存击穿", "热点缓存过期后并发重建"])]


def topic(start, expected_end=None, title="缓存穿透", continues=False):
    """Keep the shared fixture's expected-end argument; v3 never sends an end."""
    return {"start_utterance_id": start, "title": title,
            "summary": "介绍问题和应对办法", "keywords": ["缓存"], "continues_previous": continues}


def raw(value):
    return {"output": {"choices": [{"finish_reason": "stop", "message": {
        "content": json.dumps(value, ensure_ascii=False) if not isinstance(value, str) else value}}]}}


def run(path, responses, **options):
    requests = []
    def handler(request):
        requests.append(json.loads(request.content))
        assert len(requests) <= len(responses), "Unexpected additional provider request"
        return httpx.Response(200, json=raw(responses[len(requests) - 1]))
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = discover_topics(utterances(), 4000, path, client=client, api_key="synthetic-key",
            job_id="synthetic-job", video_id="synthetic-video", **options)
        again = discover_topics(utterances(), 4000, path, client=client, api_key="synthetic-key",
            job_id="synthetic-job", video_id="synthetic-video", **options)
    assert again == result
    return result, requests


def test_topics_use_source_boundaries_and_preserve_different_discussion_points(tmp_path):
    result, requests = run(tmp_path, [{"topics": [topic("u0", "u1"), topic("u2", "u3", "缓存击穿")]}])
    assert [(s["start_ms"], s["end_ms"], s["title"]) for s in result["segments"]] == [
        (0, 1900, "缓存穿透"), (2000, 3900, "缓存击穿")]
    assert result["analysis_mode"] == "topics" and "products" not in result
    assert len(requests) == 1
    assert [(s["start_utterance_id"], s["end_utterance_id"]) for s in result["segments"]] == [
        ("u0", "u1"), ("u2", "u3")]
    saved = json.loads((tmp_path / "topics/window-0000-attempt-0.json").read_text(encoding="utf-8"))
    assert all("end_utterance_id" not in row
               for row in json.loads(saved["output"]["choices"][0]["message"]["content"])["topics"])
    validate_topic_result(result)


@pytest.mark.parametrize("mask", range(1 << 5))
def test_every_valid_start_subset_constructs_complete_source_ordered_coverage(mask):
    core_ids = ["z9", "a1", "k7", "b2", "v8", "m0"]
    starts = [0] + [index for index in range(1, len(core_ids)) if mask & (1 << (index - 1))]
    response = raw({"topics": [topic(core_ids[index]) for index in starts]})
    original = copy.deepcopy(response)
    parsed = _parse(response, core_ids, None)
    assert response == original
    assert [uid for row in parsed for uid in row["ids"]] == core_ids
    assert [row["start_utterance_id"] for row in parsed] == [core_ids[index] for index in starts]
    assert all(row["ids"] and row["ids"][0] == row["start_utterance_id"]
               and row["ids"][-1] == row["end_utterance_id"] for row in parsed)
    assert parsed[-1]["end_utterance_id"] == core_ids[-1]


def test_single_sentence_video_retains_the_whole_source_interval(tmp_path):
    source = [{**utterances()[0], "start_ms": 123, "end_ms": 888}]
    with httpx.Client(transport=httpx.MockTransport(lambda request:
            httpx.Response(200, json=raw({"topics": [topic("u0")]})))) as client:
        result = discover_topics(source, 1000, tmp_path, client=client, api_key="synthetic-key",
                                 job_id="j", video_id="v")
    segment = result["segments"][0]
    assert (segment["start_utterance_id"], segment["end_utterance_id"]) == ("u0", "u0")
    assert (segment["start_ms"], segment["end_ms"]) == (123, 888)
    assert segment["audio_evidence_utterance_ids"] == ["u0"]


@pytest.mark.parametrize("context_ms,expected_context", [
    (0, [([], [2]), ([], []), ([4], [])]),
    (800, [([], [2, 3]), ([1], [5]), ([4], [])]),
])
def test_payload_isolates_complete_ordered_core_and_context_without_changing_source(
        tmp_path, context_ms, expected_context):
    # Nonlexical IDs and whole sentences spanning both edges rule out sorting
    # IDs or reclassifying/clipping sentences using their start/end timestamps.
    source = [{"utterance_id": uid, "start_ms": start, "end_ms": end, "text": text,
               "source_chunk_id": "source-chunk"} for uid, start, end, text in [
        ("s90", 0, 800, " 先说明缓存的基本作用。 "),
        ("z1", 900, 1700, "然后提出缓存穿透的问题。"),
        ("u000", 1800, 2400, "这句话跨窗，完整说明不存在的数据穿透缓存。"),
        ("a8", 2500, 3200, "继续说明处理办法。"),
        ("b2", 3300, 4600, "这句话又跨窗，但仍属于前一核心窗口。"),
        ("n3", 4700, 5800, "接下来独立讲缓存击穿。"),
    ]]
    original, requests = copy.deepcopy(source), []
    core_indices = [[0, 1], [2, 3, 4], [5]]

    def provider(request):
        payload = json.loads(json.loads(request.content)["input"]["messages"][1]["content"])
        indices = core_indices[len(requests)]
        requests.append(payload)
        return httpx.Response(200, json=raw({"topics": [
            topic(source[indices[0]]["utterance_id"], source[indices[-1]]["utterance_id"])]}))

    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        result = discover_topics(source, 6000, tmp_path, client=client, api_key="synthetic-key",
            job_id="j", video_id="v", window_ms=2000, context_ms=context_ms)
    assert source == original and result["utterances"] == original
    assert len(requests) == 3
    for payload, indices, (before, after) in zip(requests, core_indices, expected_context, strict=True):
        expected_core = [original[index] for index in indices]
        assert payload["transcript"] == expected_core
        assert payload["core_utterance_ids"] == [row["utterance_id"] for row in expected_core]
        assert payload["core_first_utterance_id"] == expected_core[0]["utterance_id"]
        assert payload["core_last_utterance_id"] == expected_core[-1]["utterance_id"]
        assert payload["context_before"] == [original[index] for index in before]
        assert payload["context_after"] == [original[index] for index in after]
        groups = [{row["utterance_id"] for row in payload[key]}
                  for key in ("context_before", "transcript", "context_after")]
        assert all(groups[i].isdisjoint(groups[j]) for i in range(3) for j in range(i + 1, 3))
    assert [uid for payload in requests for uid in payload["core_utterance_ids"]] == [
        row["utterance_id"] for row in original]
    assert requests[1]["previous_topic"]["end_utterance_id"] == "z1"


@pytest.mark.parametrize("options,expected_timeout", [({}, 300), ({"timeout_seconds": 45}, 45)])
def test_owned_topic_client_uses_bounded_default_or_explicit_timeout(tmp_path, monkeypatch, options, expected_timeout):
    client = httpx.Client(transport=httpx.MockTransport(lambda request:
        httpx.Response(200, json=raw({"topics": [topic("u0", "u3")]}))))
    settings = []
    def create_client(**kwargs):
        settings.append(kwargs)
        return client
    monkeypatch.setattr("livecut.topics.httpx.Client", create_client)
    discover_topics(utterances(), 4000, tmp_path, api_key="synthetic-key", job_id="j", video_id="v", **options)
    assert settings == [{"timeout": expected_timeout, "follow_redirects": False}]
    assert client.is_closed


@pytest.mark.parametrize("continuation,expected", [(False, 2), (True, 1)])
def test_window_edges_merge_only_on_explicit_model_continuation(tmp_path, continuation, expected):
    result, requests = run(tmp_path, [
        {"topics": [topic("u0", "u1")]},
        {"topics": [topic("u2", "u3", continues=continuation)]}], window_ms=2000)
    assert len(result["segments"]) == expected and len(requests) == 2
    payload = json.loads(requests[1]["input"]["messages"][1]["content"])
    assert payload["previous_topic"]["end_utterance_id"] == "u1"
    assert payload["core_utterance_ids"] == ["u2", "u3"]
    assert [uid for s in result["segments"] for uid in s["audio_evidence_utterance_ids"]] == ["u0", "u1", "u2", "u3"]


def test_continuation_keeps_the_first_title_without_repairing_an_unused_model_proposal(tmp_path):
    title = "HTML文档类型、属性与DOM结构"
    narrowed = "只谈DOM交互的自由模型标题"
    proposed = {**topic("u2", "u3", narrowed, continues=True),
                "summary": "前段介绍文档类型和属性，本窗继续讲DOM关系。"}
    result, requests = run(tmp_path, [
        {"topics": [topic("u0", "u1", title)]},
        {"topics": [proposed]}], window_ms=2000)
    assert len(result["segments"]) == 1 and result["segments"][0]["title"] == title
    assert result["segments"][0]["summary"] == proposed["summary"]
    assert result["segments"][0]["end_ms"] == 3900 and len(requests) == 2
    assert result["segments"][0]["audio_evidence_utterance_ids"] == ["u0", "u1", "u2", "u3"]
    saved_raw = json.loads((tmp_path / "topics/window-0001-attempt-0.json").read_text(encoding="utf-8"))
    assert json.loads(saved_raw["output"]["choices"][0]["message"]["content"])["topics"] == [proposed]
    accepted = json.loads((tmp_path / "topics/window-0001-accepted.json").read_text(encoding="utf-8"))
    assert accepted[0]["title"] == narrowed, "Keep the proposal for audit; only the aggregator selects the title"
    state = json.loads((tmp_path / "topics/state.json").read_text(encoding="utf-8"))
    assert [(c["window_index"], c["attempt"]) for c in state["calls"]] == [(0, 0), (1, 0)]


def test_metadata_repair_may_propose_another_continuation_title_but_cannot_replace_the_first(tmp_path):
    title = "完整讨论标题"
    first_proposal = {**topic("u2", "u3", "缩窄后的标题", continues=True),
                      "keywords": [f"关键词{i}" for i in range(10)]}
    repaired_proposal = topic("u2", "u3", "修复另提的标题", continues=True)
    responses = [
        {"topics": [topic("u0", "u1", title)]},
        {"topics": [first_proposal]}, {"topics": [repaired_proposal]},
    ]
    result, requests = run(tmp_path, responses, window_ms=2000)
    assert len(requests) == 3 and result["segments"][0]["title"] == title
    repair_input = json.loads(requests[2]["input"]["messages"][-1]["content"])
    assert repair_input["validation_feedback"] == {
        "rule": "KEYWORD_LIMIT", "topic_index": 0, "actual_count": 10, "max_count": 8}
    assert repair_input["validated_topic_starts"] == [{
        "start_utterance_id": "u2", "continues_previous": True}]
    for attempt, proposal in enumerate((first_proposal, repaired_proposal)):
        saved = json.loads((tmp_path / f"topics/window-0001-attempt-{attempt}.json").read_text(encoding="utf-8"))
        assert json.loads(saved["output"]["choices"][0]["message"]["content"])["topics"] == [proposal]
    accepted = json.loads((tmp_path / "topics/window-0001-accepted.json").read_text(encoding="utf-8"))
    assert accepted[0]["title"] == repaired_proposal["title"]


@pytest.mark.parametrize("title", [None, " ", "x" * 121])
def test_unused_continuation_title_still_must_meet_field_validation(title):
    with pytest.raises(SegmentationError) as error:
        _parse(raw({"topics": [topic("u0", "u1", title, continues=True)]}),
               ["u0", "u1"], {"title": "保留的首标题"})
    assert error.value.feedback["rule"] == "TITLE_INVALID"


def test_keyword_overflow_feedback_reports_only_the_program_measured_limit(tmp_path):
    unsafe_text = "model-text-that-must-not-enter-repair"
    invalid = {**topic("u0", "u3", unsafe_text),
               "keywords": [f"{unsafe_text}-{index}" for index in range(10)]}
    _, requests = run(tmp_path, [{"topics": [invalid]}, {"topics": [topic("u0", "u3")]}])
    content = requests[1]["input"]["messages"][-1]["content"]
    feedback = json.loads(content)["validation_feedback"]
    assert feedback == {"rule": "KEYWORD_LIMIT", "topic_index": 0, "actual_count": 10, "max_count": 8}
    assert unsafe_text not in content and len(requests) == 2
    state = json.loads((tmp_path / "topics/state.json").read_text(encoding="utf-8"))
    assert state["calls"][0]["validation_feedback"] == feedback
    assert unsafe_text not in json.dumps(state)


def test_metadata_repair_locks_the_whole_valid_partition_without_echoing_model_text(tmp_path):
    first = {**topic("u0", "u1", "untrusted-original-title"),
             "summary": "untrusted-original-summary",
             "keywords": [f"untrusted-keyword-{i}" for i in range(10)]}
    second = topic("u2", "u3", "untrusted-second-title")
    repaired_first = {**topic("u0", "u1", "修复后的缓存穿透标题"), "keywords": ["模型重新选择的关键词"]}
    repaired_second = topic("u2", "u3", "修复后的缓存击穿标题")
    result, requests = run(tmp_path, [
        {"topics": [first, second]}, {"topics": [repaired_first, repaired_second]}])
    repair = json.loads(requests[1]["input"]["messages"][-1]["content"])
    assert repair["core_first_utterance_id"] == "u0" and repair["core_last_utterance_id"] == "u3"
    assert repair["core_utterance_ids"] == ["u0", "u1", "u2", "u3"]
    assert repair["validated_topic_starts"] == [
        {"start_utterance_id": "u0", "continues_previous": False},
        {"start_utterance_id": "u2", "continues_previous": False},
    ]
    assert "untrusted-" not in requests[1]["input"]["messages"][-1]["content"]
    assert [s["keywords"] for s in result["segments"]] == [["模型重新选择的关键词"], ["缓存"]]
    saved = json.loads((tmp_path / "topics/window-0000-attempt-0.json").read_text(encoding="utf-8"))
    assert json.loads(saved["output"]["choices"][0]["message"]["content"])["topics"][0] == first


@pytest.mark.parametrize("changed", [
    [topic("u0", "u3")],
    [topic("u0", "u0"), topic("u1", "u3")],
    [topic("u0", "u0"), topic("u1", "u1"), topic("u2", "u3")],
])
def test_repair_cannot_delete_move_or_add_validated_starts(tmp_path, changed):
    responses = [{"topics": [{**topic("u0", "u1"), "keywords": [f"关键词{i}" for i in range(10)]},
                             topic("u2", "u3")]}, {"topics": changed}]
    requests = []
    def provider(request):
        requests.append(request)
        assert len(requests) <= 2, "A start-changing repair must not trigger another repair"
        return httpx.Response(200, json=raw(responses[len(requests) - 1]))
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        with pytest.raises(SegmentationError) as error:
            discover_topics(utterances(), 4000, tmp_path, client=client, api_key="fixture",
                            job_id="j", video_id="v", max_calls=8)
    assert error.value.feedback == {"rule": "REPAIR_STARTS_CHANGED"}
    assert error.value.diagnostic["format_repair"] == "FAILED_VALIDATION"
    assert len(requests) == 2 and not (tmp_path / "analysis-result.json").exists()
    state = json.loads((tmp_path / "topics/state.json").read_text(encoding="utf-8"))
    assert [call["validation_feedback"]["rule"] for call in state["calls"]] == [
        "KEYWORD_LIMIT", "REPAIR_STARTS_CHANGED"]


def test_repair_cannot_change_a_validated_continuation_flag(tmp_path):
    responses = [
        {"topics": [topic("u0", "u1")]},
        {"topics": [{**topic("u2", "u3", continues=True), "keywords": [f"关键词{i}" for i in range(10)]}]},
        {"topics": [topic("u2", "u3", continues=False)]},
    ]
    requests = []
    def provider(request):
        requests.append(request)
        assert len(requests) <= 3
        return httpx.Response(200, json=raw(responses[len(requests) - 1]))
    with httpx.Client(transport=httpx.MockTransport(provider)) as client:
        with pytest.raises(SegmentationError) as error:
            discover_topics(utterances(), 4000, tmp_path, client=client, api_key="fixture",
                            job_id="j", video_id="v", window_ms=2000, max_calls=8)
    assert error.value.feedback == {"rule": "REPAIR_STARTS_CHANGED"}
    repair = json.loads(json.loads(requests[2].content)["input"]["messages"][-1]["content"])
    assert repair["validated_topic_starts"][0]["continues_previous"] is True
    assert len(requests) == 3 and not (tmp_path / "analysis-result.json").exists()


def test_context_id_start_gets_core_bounds_without_any_validated_start_anchor(tmp_path):
    result, requests = run(tmp_path, [
        {"topics": [topic("u0", "u1"), topic("u2", "u2", "untrusted-outside-core-title")]},
        {"topics": [topic("u0", "u1")]},
        {"topics": [topic("u2", "u3")]},
    ], window_ms=2000)
    initial = json.loads(requests[0]["input"]["messages"][1]["content"])
    assert [u["utterance_id"] for u in initial["transcript"]] == ["u0", "u1"]
    assert "u2" in {u["utterance_id"] for u in initial["context_after"]}, "u2 remains read-only context"
    repair = json.loads(requests[1]["input"]["messages"][-1]["content"])
    assert repair["core_first_utterance_id"] == "u0" and repair["core_last_utterance_id"] == "u1"
    assert repair["core_utterance_ids"] == ["u0", "u1"]
    assert "validated_topic_starts" not in repair
    assert repair["validation_feedback"]["rule"] == "UNKNOWN_CORE_ID"
    assert "untrusted-outside-core-title" not in requests[1]["input"]["messages"][-1]["content"]
    assert len(result["segments"]) == 2 and len(requests) == 3


def test_any_invalid_later_start_prevents_partial_anchors_for_a_metadata_failure(tmp_path):
    result, requests = run(tmp_path, [
        {"topics": [{**topic("u0", "u1"), "keywords": [f"关键词{i}" for i in range(10)]},
                    topic("u0", "u3")]},
        {"topics": [topic("u0", "u3")]},
    ])
    repair = json.loads(requests[1]["input"]["messages"][-1]["content"])
    assert "validated_topic_starts" not in repair and repair["validation_feedback"]["rule"] == "CORE_ORDER"
    assert len(result["segments"]) == 1


@pytest.mark.parametrize("mutation,rule", [
    ({"start_utterance_id": "unknown-model-free-text"}, "UNKNOWN_CORE_ID"),
    ({"summary": "x" * 1001}, "SUMMARY_INVALID"),
    ({"keywords": ["相同", "相同"]}, "KEYWORD_DUPLICATE"),
])
def test_validation_feedback_never_echoes_invalid_model_content(mutation, rule):
    with pytest.raises(SegmentationError) as error:
        _parse(raw({"topics": [{**topic("u0", "u3"), **mutation}]}), ["u0", "u1", "u2", "u3"], None)
    assert error.value.feedback["rule"] == rule and error.value.feedback["topic_index"] == 0
    assert set(error.value.feedback) <= {"rule", "topic_index", "actual_count", "max_count"}
    assert "unknown-model-free-text" not in json.dumps(error.value.feedback)
    assert "x" * 10 not in json.dumps(error.value.feedback)


@pytest.mark.parametrize("bad", [
    {"topics": []}, {"topics": [topic("u1")]},
    {"topics": [topic("context", "u3")]},
    {"topics": [topic("u0"), topic("u0")]},
    {"topics": [topic("u0"), topic("u3"), topic("u2")]},
    {"topics": [topic("u0", "u3", continues=True)]},
    {"topics": [{**topic("u0"), "continues_previous": 1}]},
    {"topics": [topic("u0"), topic("u2", continues=True)]},
    {"topics": [{**topic("u0"), "end_utterance_id": "u3"}]},
    {"topics": [{**topic("u0", "u3"), "start_ms": 0}]},
    {"topics": [{**topic("u0", "u3"), "title": " "}]},
    {"topics": [{**topic("u0", "u3"), "keywords": ["重复", "重复"]}]},
    '{"topics":[],"topics":[]}',
])
def test_invalid_starts_and_injected_fields_are_rejected(bad):
    with pytest.raises(SegmentationError) as error:
        _parse(raw(bad), ["u0", "u1", "u2", "u3"], None)
    assert error.value.code == "INVALID_MODEL_OUTPUT"


def test_only_one_format_repair_is_permitted_and_keeps_original_input(tmp_path):
    result, requests = run(tmp_path, [{"topics": []}, {"topics": [topic("u0", "u3")]}])
    assert requests[1]["input"]["messages"][:-1] == requests[0]["input"]["messages"]
    assert len(result["segments"]) == 1 and len(requests) == 2
    count = []
    def invalid(request):
        count.append(1)
        return httpx.Response(200, json=raw({"topics": []}))
    with httpx.Client(transport=httpx.MockTransport(invalid)) as client, pytest.raises(SegmentationError):
        discover_topics(utterances(), 4000, tmp_path / "invalid", client=client, api_key="fixture",
                        job_id="j", video_id="v", max_calls=3)
    assert len(count) == 2 and not (tmp_path / "invalid/analysis-result.json").exists()


def test_budget_preflight_never_dispatches_and_changed_input_cannot_resume(tmp_path):
    client = httpx.Client(transport=httpx.MockTransport(lambda r: pytest.fail("No dispatch allowed")))
    with client, pytest.raises(SegmentationError) as error:
        discover_topics(utterances(), 4000, tmp_path, client=client, job_id="j", video_id="v", max_calls=0)
    assert error.value.code == "BUDGET_EXHAUSTED"
    run(tmp_path, [{"topics": [topic("u0", "u3")]}])
    with pytest.raises(SegmentationError) as changed:
        discover_topics(utterances(), 4000, tmp_path, job_id="different", video_id="synthetic-video")
    assert changed.value.code == "INVALID_CHECKPOINT"


def test_result_validator_rejects_fabricated_times_and_missing_evidence(tmp_path):
    result, _ = run(tmp_path, [{"topics": [topic("u0", "u3")]}])
    for mutation in ("time", "missing"):
        invalid = copy.deepcopy(result)
        if mutation == "time":
            invalid["segments"][0]["start_ms"] = 1
        else:
            invalid["segments"][0]["audio_evidence_utterance_ids"].pop(1)
        with pytest.raises(ContractValidationError):
            validate_topic_result(invalid)


@pytest.mark.parametrize("mutation", ["overlap", "empty_text", "invalid_id", "duplicate_keyword"])
def test_topic_contract_matches_source_and_normalized_content_rules(tmp_path, mutation):
    result, _ = run(tmp_path, [{"topics": [topic("u0", "u3")]}])
    if mutation == "overlap":
        result["utterances"][1]["start_ms"] = 1
    elif mutation == "empty_text":
        result["utterances"][0]["text"] = " \n "
    elif mutation == "invalid_id":
        result["job_id"] = "invalid id"
    else:
        result["segments"][0]["keywords"] = ["缓存", " 缓存 "]
    with pytest.raises(ContractValidationError):
        validate_topic_result(result)


@pytest.mark.parametrize("mutation", ["bad_call", "duplicate", "path", "status", "window", "completed"])
def test_damaged_topic_checkpoint_fails_before_any_dispatch(tmp_path, mutation):
    run(tmp_path, [{"topics": [topic("u0", "u3")]}])
    path = tmp_path / "topics/state.json"
    state = json.loads(path.read_text(encoding="utf-8"))
    if mutation == "bad_call":
        state["calls"] = [None]
    elif mutation == "duplicate":
        state["calls"].append(state["calls"][0])
    elif mutation == "path":
        state["calls"][0]["response_file"] = "../outside.json"
    elif mutation == "status":
        state["calls"][0]["status"] = []
    elif mutation == "window":
        state["calls"][0]["window_index"] = True
    else:
        state["completed_windows"] = [0, 0]
    path.write_text(json.dumps(state), encoding="utf-8")
    with httpx.Client(transport=httpx.MockTransport(lambda request: pytest.fail("No request allowed"))) as client:
        with pytest.raises(SegmentationError) as error:
            discover_topics(utterances(), 4000, tmp_path, client=client, api_key="synthetic-key",
                job_id="synthetic-job", video_id="synthetic-video")
    assert error.value.code == "INVALID_CHECKPOINT"


def test_topic_failure_records_window_and_single_repair_without_repeating_requests(tmp_path):
    requests = []
    def invalid(request):
        requests.append(request)
        return httpx.Response(200, json=raw({"topics": []}))
    with httpx.Client(transport=httpx.MockTransport(invalid)) as client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as error:
                discover_topics(utterances(), 4000, tmp_path, client=client, api_key="synthetic-key",
                    job_id="j", video_id="v", max_calls=3)
            assert error.value.diagnostic == {"reason_code": "CLASSIFICATION_SCHEMA_INVALID",
                "window_index": 0, "window_count": 1, "format_repair": "FAILED_VALIDATION"}
    assert len(requests) == 2
    state = json.loads((tmp_path / "topics/state.json").read_text(encoding="utf-8"))
    assert all("validation_error" in call for call in state["calls"])
    assert not (tmp_path / "analysis-result.json").exists()


def test_uncertain_topic_request_never_automatically_spends_again(tmp_path):
    requests = []
    def lost(request):
        requests.append(request)
        raise httpx.ReadTimeout("synthetic failure", request=request)
    with httpx.Client(transport=httpx.MockTransport(lost)) as client:
        for expected in ("PROVIDER_ERROR", "CALL_STATE_UNCERTAIN"):
            with pytest.raises(SegmentationError) as error:
                discover_topics(utterances(), 4000, tmp_path, client=client, api_key="synthetic-key",
                    job_id="j", video_id="v")
            assert error.value.code == expected
    assert len(requests) == 1


def test_uncertain_repair_never_repeats_the_initial_or_repair_provider_request(tmp_path):
    requests = []
    def lost_repair(request):
        requests.append(request)
        if len(requests) == 1:
            return httpx.Response(200, json=raw({"topics": [{**topic("u0", "u3"),
                "keywords": [f"关键词{i}" for i in range(10)]}]}))
        raise httpx.ReadTimeout("synthetic repair failure", request=request)
    with httpx.Client(transport=httpx.MockTransport(lost_repair)) as client:
        for expected in ("PROVIDER_ERROR", "CALL_STATE_UNCERTAIN"):
            with pytest.raises(SegmentationError) as error:
                discover_topics(utterances(), 4000, tmp_path, client=client, api_key="synthetic-key",
                                job_id="j", video_id="v", max_calls=8)
            assert error.value.code == expected
    assert len(requests) == 2


@pytest.mark.parametrize("needs_repair", [False, True])
def test_topic_requests_replay_verified_durable_budget_across_worker_attempts(tmp_path, needs_repair):
    class Ledger:
        def __init__(self):
            self.calls, self.events = {}, []

        def reserve_call(self, payload):
            self.events.append("reserve")
            existing = payload["request_id"] in self.calls
            self.calls.setdefault(payload["request_id"], {**payload, "status": "RESERVED"})
            return {"outcome": "EXISTING" if existing else "RESERVED",
                    "call": copy.deepcopy(self.calls[payload["request_id"]])}

        def update_call(self, request_id, payload):
            self.events.append(payload["status"])
            self.calls[request_id].update({key: value for key, value in payload.items() if value is not None})
            return {"outcome": "UPDATED", "call": copy.deepcopy(self.calls[request_id])}

    ledger, external = Ledger(), []
    def provider(request):
        external.append(request)
        ledger.events.append("provider")
        if needs_repair and len(external) == 1:
            return httpx.Response(200, json=raw({"topics": [{**topic("u0", "u3"),
                "keywords": [f"关键词{i}" for i in range(10)]}]}))
        return httpx.Response(200, json=raw({"topics": [topic("u0", "u3")]}))

    results = []
    for attempt in (1, 2):
        lease = Lease.parse(claim(stage_type="SEGMENT", attempt=attempt, run_token=f"token-{attempt}"))
        ctx = StageContext(lease, tmp_path, tmp_path / f"attempt-{attempt}",
                           LeaseGuard(lease, threading.Event()))
        with httpx.Client(transport=BudgetTransport(ledger, ctx, inner=httpx.MockTransport(provider))) as client:
            results.append(discover_topics(utterances(), 4000, ctx.directory, client=client,
                api_key="synthetic-secret-not-for-ledger", job_id=lease.job_id, video_id=lease.video_id))
    expected_calls = 2 if needs_repair else 1
    assert results[0] == results[1] and len(external) == len(ledger.calls) == expected_calls
    assert ledger.events == ["reserve", "DISPATCHING", "provider", "SUCCEEDED"] * expected_calls + ["reserve"] * expected_calls
    for call in ledger.calls.values():
        assert call["kind"] == "TEXT" and call["status"] == "SUCCEEDED"
        assert sha256(tmp_path / call["response_key"]) == call["response_hash"]
    assert "synthetic-secret" not in json.dumps(ledger.calls)


def test_validate_result_cli_selects_independent_topic_contract(tmp_path, capsys):
    from livecut.cli import main
    run(tmp_path, [{"topics": [topic("u0", "u3")]}])
    assert main(["validate-result", str(tmp_path / "analysis-result.json")]) == 0
    assert json.loads(capsys.readouterr().out) == {"status": "valid", "schema_version": "1.0",
        "data_kind": "model_result", "segments": 1}
