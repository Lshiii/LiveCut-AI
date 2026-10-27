import copy
import json
import threading

import httpx
import pytest
from livecut.model_budget import BudgetTransport, ModelBudgetError
from livecut.segmentation import discover_products
from livecut.worker import Lease, LeaseGuard, StageContext, WorkerError
from livecut.worker_client import WorkerClientError
from test_worker import claim


class BudgetClient:
    def __init__(self, events):
        self.events = events
        self.status = "RESERVED"
        self.task_id = None
        self.reservations = []
        self.fail_reserve = False
        self.response_key = self.response_hash = None

    def reserve_call(self, payload):
        self.events.append("reserve")
        self.reservations.append(payload)
        if self.fail_reserve:
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
        return {"outcome": "RESERVED", "call": {**payload, "status": self.status,
                                                  "provider_task_id": self.task_id,
                                                  "response_key": self.response_key,
                                                  "response_hash": self.response_hash}}

    def update_call(self, request_id, payload):
        self.events.append(payload["status"])
        self.status = payload["status"]
        self.task_id = payload["provider_task_id"]
        if payload.get("response_key") is not None:
            self.response_key = payload["response_key"]
            self.response_hash = payload["response_hash"]
        return {"outcome": "UPDATED", "call": payload}


def setup_transport(tmp_path, handler, *, stage_type="SEGMENT"):
    events = []
    api = BudgetClient(events)
    lease = Lease.parse(claim(stage_type=stage_type))
    stop = threading.Event()
    context = StageContext(lease, tmp_path, tmp_path / "attempt", LeaseGuard(lease, stop))

    def provider(request):
        events.append("provider")
        return handler(request)

    transport = BudgetTransport(api, context, inner=httpx.MockTransport(provider))
    client = httpx.Client(transport=transport)
    return client, api, events, stop


def test_reserve_and_dispatch_are_durable_before_provider(tmp_path):
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(
        200, json={"output": {"choices": []}, "usage": {"input_tokens": 5}}))
    client.post("https://provider.example/services/aigc/text-generation/generation",
                json={"model": "qwen-plus", "input": {"messages": []}})
    assert events == ["reserve", "DISPATCHING", "provider", "SUCCEEDED"]
    assert api.reservations[0]["kind"] == "TEXT"


def test_unknown_response_blocks_second_paid_submission(tmp_path):
    def fail(request):
        raise httpx.ReadTimeout("do not echo me", request=request)

    client, api, events, _ = setup_transport(tmp_path, fail)
    url = "https://provider.example/services/aigc/text-generation/generation"
    body = {"model": "qwen-plus", "input": {"messages": []}}
    with pytest.raises(httpx.ReadTimeout):
        client.post(url, json=body)
    assert api.status == "UNKNOWN_OUTCOME"
    with pytest.raises(ModelBudgetError, match="MODEL_CALL_REQUIRES_RECOVERY"):
        client.post(url, json=body)
    assert events.count("provider") == 1


@pytest.mark.parametrize("status", ["DISPATCHING", "UNKNOWN_OUTCOME", "SUCCEEDED", "FAILED"])
def test_existing_unsafe_call_is_not_reissued(tmp_path, status):
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={}))
    api.status = status
    with pytest.raises(ModelBudgetError):
        client.post("https://provider.example/services/aigc/text-generation/generation",
                    json={"model": "qwen-plus"})
    assert "provider" not in events


def test_submitted_asr_recovers_recorded_task_without_resubmission(tmp_path):
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={}), stage_type="ASR")
    api.status, api.task_id = "SUBMITTED", "existing-provider-task"
    api.response_key, api.response_hash = client._transport._record("b" * 64, httpx.Response(
        200, json={"output": {"task_id": "existing-provider-task", "task_status": "PENDING"}}))
    response = client.post("https://provider.example/services/audio/asr/transcription", json={
        "model": "paraformer-v2", "input": {"file_urls": ["oss://new-temporary-key"]}})
    assert response.json()["output"]["task_id"] == "existing-provider-task"
    assert "provider" not in events


def test_asr_temporary_url_does_not_change_billable_identity(tmp_path):
    client, api, _, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={
        "output": {"task_id": "task-1"}}), stage_type="ASR")
    for temporary in ("oss://temp-a", "oss://temp-b"):
        client.post("https://provider.example/services/audio/asr/transcription", json={
            "model": "paraformer-v2", "input": {"file_urls": [temporary]}})
    assert api.reservations[0]["request_id"] == api.reservations[1]["request_id"]
    assert "oss://" not in json.dumps(api.reservations)


def test_reservation_failure_prevents_provider_call(tmp_path):
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={}))
    api.fail_reserve = True
    with pytest.raises(WorkerClientError):
        client.post("https://provider.example/services/aigc/text-generation/generation",
                    json={"model": "qwen-plus"})
    assert events == ["reserve"]


def test_dispatch_already_applied_never_grants_second_network_submission(tmp_path):
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={}))
    api.update_call = lambda request_id, payload: {
        "outcome": "ALREADY_APPLIED", "call": {"status": "DISPATCHING"},
    }
    with pytest.raises(ModelBudgetError, match="MODEL_DISPATCH_NOT_GRANTED"):
        client.post("https://provider.example/services/aigc/text-generation/generation",
                    json={"model": "qwen-plus"})
    assert events == ["reserve"]


def test_asr_poll_settles_recorded_submission_and_reopens_recovery_state(tmp_path):
    def provider(request):
        if "/tasks/" in request.url.path:
            return httpx.Response(200, json={"output": {"task_status": "SUCCEEDED"}})
        return httpx.Response(200, json={"output": {"task_id": "task-1"}})

    client, api, events, _ = setup_transport(tmp_path, provider, stage_type="ASR")
    client.post("https://provider.example/services/audio/asr/transcription", json={
        "model": "paraformer-v2", "input": {"file_urls": ["oss://temp-a"]}})
    assert api.status == "SUBMITTED"
    context = client._transport.context
    client.close()
    resumed = httpx.Client(transport=BudgetTransport(api, context, inner=httpx.MockTransport(provider)))
    resumed.post("https://provider.example/tasks/task-1")
    assert api.status == "SUCCEEDED"
    assert events[-1] == "SUCCEEDED"
    resumed.close()


def test_stop_guard_blocks_even_nonbillable_poll(tmp_path):
    client, _, events, stop = setup_transport(tmp_path, lambda request: httpx.Response(200, json={}))
    stop.set()
    with pytest.raises(WorkerError, match="WORKER_STOPPED"):
        client.post("https://provider.example/tasks/task-1")
    assert events == []


def test_recorded_text_response_replays_in_new_attempt_without_provider(tmp_path):
    raw = {"output": {"choices": [{"message": {"content": "original model result"}}]},
           "usage": {"input_tokens": 8, "output_tokens": 4}}
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json=raw))
    url = "https://provider.example/services/aigc/text-generation/generation"
    body = {"model": "qwen-plus", "input": {"messages": [{"role": "user", "content": "test"}]}}
    assert client.post(url, json=body).json() == raw
    lease = Lease.parse(claim(stage_type="SEGMENT", attempt=2, run_token="token-new"))
    context = StageContext(lease, tmp_path, tmp_path / "new-attempt", LeaseGuard(lease, threading.Event()))
    replay_calls = []
    resumed = httpx.Client(transport=BudgetTransport(api, context, inner=httpx.MockTransport(
        lambda request: replay_calls.append(request))))
    assert resumed.post(url, json=body).json() == raw
    assert replay_calls == []
    assert events.count("provider") == 1
    resumed.close()


def test_recorded_response_hash_mismatch_prevents_replay(tmp_path):
    client, api, _, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={"output": {}}))
    url = "https://provider.example/services/aigc/text-generation/generation"
    client.post(url, json={"model": "qwen-plus"})
    (tmp_path / api.response_key).write_text("modified", encoding="utf-8")
    with pytest.raises(ModelBudgetError, match="MODEL_RESPONSE_HASH_MISMATCH"):
        client.post(url, json={"model": "qwen-plus"})


def test_real_segmentation_adapter_reads_recorded_raw_response_after_attempt_change(tmp_path):
    prediction = {"products": [{"label": "mask", "category": "面膜", "variant_description": "",
                                "canonical_product_id": None}],
                  "assignments": {"u1": {"content_kind": "product_talk",
                                "product_labels": ["mask"]}}}
    raw = {"output": {"choices": [{"finish_reason": "stop", "message": {
        "role": "assistant", "content": json.dumps(prediction, ensure_ascii=False)}}]},
        "usage": {"input_tokens": 9, "output_tokens": 4}}
    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json=raw))
    items = [{"utterance_id": "u1", "start_ms": 0, "end_ms": 1000,
              "text": "这一款是面膜", "source_chunk_id": "c1"}]
    first = discover_products(items, 1000, tmp_path / "original", client=client,
                              api_key="unit-test-only", job_id="job-1")
    lease = Lease.parse(claim(stage_type="SEGMENT", attempt=2, run_token="token-new"))
    context = StageContext(lease, tmp_path, tmp_path / "new-attempt", LeaseGuard(lease, threading.Event()))
    provider_calls = []
    resumed = httpx.Client(transport=BudgetTransport(api, context, inner=httpx.MockTransport(
        lambda request: provider_calls.append(request))))
    second = discover_products(items, 1000, tmp_path / "restored", client=resumed,
                               api_key="unit-test-only", job_id="job-1")
    assert first == second
    assert second["products"][0]["name"] == "面膜"
    assert provider_calls == []
    assert events.count("provider") == 1
    assert "unit-test-only" not in (tmp_path / api.response_key).read_text(encoding="utf-8")
    resumed.close()


@pytest.mark.parametrize("legacy_protocol", ["v8-ranges", "v9-arrays", "v10-three-fields"])
def test_keyed_protocol_body_does_not_replay_legacy_response(tmp_path, legacy_protocol):
    # A mocked durable ledger exercises real body hashing and stored-response
    # verification. This is protocol recovery, not a live Provider experiment.
    class Ledger:
        def __init__(self):
            self.calls = {}

        def reserve_call(self, payload):
            request_id = payload["request_id"]
            existing = request_id in self.calls
            self.calls.setdefault(request_id, {**payload, "status": "RESERVED"})
            return {"outcome": "EXISTING" if existing else "RESERVED",
                    "call": copy.deepcopy(self.calls[request_id])}

        def update_call(self, request_id, payload):
            self.calls[request_id].update({key: value for key, value in payload.items()
                                           if value is not None})
            return {"outcome": "UPDATED", "call": copy.deepcopy(self.calls[request_id])}

    legacy = {"products": [], "segments": [{"start_utterance_id": "u1",
               "end_utterance_id": "u1", "content_kind": "non_product",
               "identity_status": "not_applicable", "product_labels": []}]}
    if legacy_protocol == "v9-arrays":
        legacy = {"products": [], "assignments": [{"content_kind": "non_product",
                   "identity_status": "not_applicable", "product_labels": []}]}
    if legacy_protocol == "v10-three-fields":
        legacy = {"products": [], "assignments": {"u1": {"content_kind": "non_product",
                   "identity_status": "not_applicable", "product_labels": []}}}
    dense = {"products": [], "assignments": {"u1": {"content_kind": "non_product",
              "product_labels": []}}}
    external = []
    pending = [legacy, dense]
    ledger = Ledger()

    def upstream(request):
        external.append(json.loads(request.content))
        assert pending, "Identical dense body must replay its original raw response"
        return httpx.Response(200, json={"output": {"choices": [{"finish_reason": "stop",
            "message": {"content": json.dumps(pending.pop(0))}}]}, "usage": {"input_tokens": 3}})

    def client_for(attempt):
        lease = Lease.parse(claim(stage_type="SEGMENT", attempt=attempt,
                                  run_token=f"token-{attempt}"))
        context = StageContext(lease, tmp_path, tmp_path / f"attempt-{attempt}",
                               LeaseGuard(lease, threading.Event()))
        return httpx.Client(transport=BudgetTransport(ledger, context,
                            inner=httpx.MockTransport(upstream)))

    url = "https://provider.example/api/v1/services/aigc/text-generation/generation"
    with client_for(1) as client:
        assert json.loads(client.post(url, json={"model": "qwen-plus", "input": {"messages": [
            {"role": "system", "content": legacy_protocol + " synthetic fixture"}]}}).json()[
                "output"]["choices"][0]["message"]["content"]) == legacy
    old_call = copy.deepcopy(next(iter(ledger.calls.values())))
    items = [{"utterance_id": "u1", "start_ms": 0, "end_ms": 1000,
              "text": "合成闲聊", "source_chunk_id": "c1"}]
    with client_for(2) as client:
        first = discover_products(items, 1000, tmp_path / "dense", client=client,
            api_key="unit-test-only", job_id="job-1", base_url="https://provider.example/api/v1")
    with client_for(3) as client:
        replay = discover_products(items, 1000, tmp_path / "replay", client=client,
            api_key="unit-test-only", job_id="job-1", base_url="https://provider.example/api/v1")
    assert first == replay and len(external) == len(ledger.calls) == 2
    assert ledger.calls[old_call["request_id"]] == old_call
    dense_call = next(value for key, value in ledger.calls.items() if key != old_call["request_id"])
    assert dense_call["input_hash"] != old_call["input_hash"]
    assert all(value["status"] == "SUCCEEDED" for value in ledger.calls.values())
    from livecut.worker import sha256
    assert all(sha256(tmp_path / value["response_key"]) == value["response_hash"]
               for value in ledger.calls.values())


def test_endpoint_and_actual_audio_hash_bind_asr_identity_without_url_secret(tmp_path):
    client, api, _, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={
        "output": {"task_id": "task-1"}}), stage_type="ASR")
    body = {"model": "paraformer-v2", "input": {"file_urls": ["oss://private-temporary-url"]}}
    client._transport.context.provider_input_hash = "b" * 64
    client.post("https://one.example/services/audio/asr/transcription?secret=do-not-record", json=body)
    first = api.reservations[-1]["request_id"]
    api.status = "RESERVED"
    client.post("https://two.example/services/audio/asr/transcription", json=body)
    assert api.reservations[-1]["request_id"] != first
    api.status = "RESERVED"
    client._transport.context.provider_input_hash = "c" * 64
    client.post("https://one.example/services/audio/asr/transcription", json=body)
    assert api.reservations[-1]["request_id"] != first
    assert "do-not-record" not in json.dumps(api.reservations)


def test_provider_response_persistence_supports_long_windows_storage_path(tmp_path):
    # This exceeds MAX_PATH without relying on machine-wide registry settings.
    long_root = tmp_path
    while len(str(long_root)) < 280:
        long_root = long_root / ("nested-" + "x" * 35)
    client, api, events, _ = setup_transport(long_root, lambda request: httpx.Response(
        200, json={"output": {"choices": []}, "usage": {"input_tokens": 5}}))
    request = {"model": "qwen-plus"}
    url = "https://provider.example/services/aigc/text-generation/generation"
    assert client.post(url, json=request).status_code == 200
    assert client.post(url, json=request).status_code == 200
    assert api.status == "SUCCEEDED"
    assert events.count("provider") == 1


def test_failed_recording_preflight_prevents_dispatch_and_provider(tmp_path, monkeypatch):
    client, _, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(200, json={}), stage_type="ASR")

    def fail(*args, **kwargs):
        raise FileNotFoundError("simulated filesystem failure")

    monkeypatch.setattr("livecut.worker._write", fail)
    with pytest.raises(ModelBudgetError, match="MODEL_RESPONSE_STORAGE_UNAVAILABLE"):
        client.post("https://provider.example/services/audio/asr/transcription",
                    json={"model": "paraformer-v2"})
    assert events == ["reserve"]


def test_asr_ack_recording_failure_preserves_real_task_and_blocks_resubmission(tmp_path, monkeypatch):
    from livecut.worker import _read

    client, api, events, _ = setup_transport(tmp_path, lambda request: httpx.Response(
        200, json={"output": {"task_id": "genuine-test-task", "task_status": "PENDING"}}), stage_type="ASR")

    def fail(*args):
        raise FileNotFoundError("simulated publish failure after actual HTTP response")

    monkeypatch.setattr(client._transport, "_record", fail)
    url = "https://provider.example/services/audio/asr/transcription"
    with pytest.raises(ModelBudgetError, match="MODEL_RESPONSE_PERSISTENCE_FAILED"):
        client.post(url, json={"model": "paraformer-v2"})
    emergency = _read(client._transport.context.directory / "provider-response-emergency.json")
    assert emergency["response"]["body"]["output"]["task_id"] == "genuine-test-task"
    assert api.status == "SUBMITTED" and api.task_id == "genuine-test-task"
    with pytest.raises(ModelBudgetError, match="MODEL_CALL_REQUIRES_RECOVERY"):
        client.post(url, json={"model": "paraformer-v2"})
    assert events.count("provider") == 1
