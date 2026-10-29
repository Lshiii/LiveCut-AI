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


def topic(start, end, title="缓存穿透", continues=False):
    return {"start_utterance_id": start, "end_utterance_id": end, "title": title,
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
    validate_topic_result(result)


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


@pytest.mark.parametrize("bad", [
    {"topics": []}, {"topics": [topic("u0", "u2")]},
    {"topics": [topic("context", "u3")]},
    {"topics": [topic("u0", "u2"), topic("u2", "u3")]},
    {"topics": [topic("u0", "u3", continues=True)]},
    {"topics": [{**topic("u0", "u3"), "start_ms": 0}]},
    {"topics": [{**topic("u0", "u3"), "title": " "}]},
    {"topics": [{**topic("u0", "u3"), "keywords": ["重复", "重复"]}]},
    '{"topics":[],"topics":[]}',
])
def test_invalid_ranges_and_injected_fields_are_rejected(bad):
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


def test_topic_requests_replay_verified_durable_budget_across_worker_attempts(tmp_path):
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
        return httpx.Response(200, json=raw({"topics": [topic("u0", "u3")]}))

    results = []
    for attempt in (1, 2):
        lease = Lease.parse(claim(stage_type="SEGMENT", attempt=attempt, run_token=f"token-{attempt}"))
        ctx = StageContext(lease, tmp_path, tmp_path / f"attempt-{attempt}",
                           LeaseGuard(lease, threading.Event()))
        with httpx.Client(transport=BudgetTransport(ledger, ctx, inner=httpx.MockTransport(provider))) as client:
            results.append(discover_topics(utterances(), 4000, ctx.directory, client=client,
                api_key="synthetic-secret-not-for-ledger", job_id=lease.job_id, video_id=lease.video_id))
    assert results[0] == results[1] and len(external) == len(ledger.calls) == 1
    assert ledger.events == ["reserve", "DISPATCHING", "provider", "SUCCEEDED", "reserve"]
    call = next(iter(ledger.calls.values()))
    assert call["kind"] == "TEXT" and call["status"] == "SUCCEEDED"
    assert sha256(tmp_path / call["response_key"]) == call["response_hash"]
    assert "synthetic-secret" not in json.dumps(ledger.calls)


def test_validate_result_cli_selects_independent_topic_contract(tmp_path, capsys):
    from livecut.cli import main
    run(tmp_path, [{"topics": [topic("u0", "u3")]}])
    assert main(["validate-result", str(tmp_path / "analysis-result.json")]) == 0
    assert json.loads(capsys.readouterr().out) == {"status": "valid", "schema_version": "1.0",
        "data_kind": "model_result", "segments": 1}
