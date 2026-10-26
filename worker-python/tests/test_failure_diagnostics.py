"""Synthetic model failures verify bounded public diagnostics, never recognition quality."""

import copy
import json

import httpx
import pytest
from livecut.diagnostics import failure_diagnostic
from livecut.segmentation import SegmentationError
from livecut.worker import Lease, StageWorker, WorkerError
from livecut.worker_client import WorkerClientError
from test_segmentation import assignment, mock_client, product, run, utterance
from test_worker import FakeClient, claim

COUNTS = {"missing_core_id_count", "extra_context_id_count", "unknown_key_count",
          "non_core_key_count", "value_error_count"}
FIELDS = COUNTS | {"reason_code", "window_index", "window_count", "format_repair"}
PRIVATE = ("private-core-A", "private-core-B", "private-core-C", "private-context-ID",
           "private-unknown-key", "private-transcript-text", "private-provider-token",
           "private-prompt-body", "https://private.invalid/signed", "private-storage-path")


def assert_public(value):
    assert isinstance(value, dict) and value
    assert set(value) <= FIELDS
    if "window_index" in value or "window_count" in value:
        assert type(value["window_index"]) is int and type(value["window_count"]) is int
        assert 0 <= value["window_index"] < value["window_count"] <= 10000
    for field in COUNTS & value.keys():
        assert type(value[field]) is int and 0 <= value[field] <= 1000000
    if {"non_core_key_count", "extra_context_id_count", "unknown_key_count"} <= value.keys():
        assert value["non_core_key_count"] == value["extra_context_id_count"] + value["unknown_key_count"]
    if "format_repair" in value:
        assert value["format_repair"] in {"NOT_ATTEMPTED", "FAILED_VALIDATION", "INTERRUPTED"}
    text = json.dumps(value, ensure_ascii=False, allow_nan=False)
    assert len(text.encode()) <= 4096
    for secret in PRIVATE:
        assert secret not in text


def valid_classification(uid):
    return {"products": [], "assignments": {uid: assignment(kind="non_product")}}


def two_windows():
    return [utterance("private-core-A", 0, 500, "private-transcript-text"),
            utterance("private-core-B", 1100, 1500, "private-transcript-text")]


def test_real_classifier_reports_all_five_counts_without_sending_utterance_ids(tmp_path):
    items = [utterance("private-core-A", 0, 100, "private-transcript-text"),
             utterance("private-core-B", 110, 200, "private-transcript-text"),
             utterance("private-core-C", 210, 300, "private-transcript-text"),
             utterance("private-context-ID", 1100, 1200, "private-transcript-text")]
    invalid = {"products": [product("bag", "垃圾袋"), product("mask", "面膜")],
               "assignments": {
                   "private-core-A": assignment(["bag"], kind="mixed"),
                   "private-core-B": {**assignment(["mask"]), "raw": "private-provider-token"},
                   "private-context-ID": assignment(kind="non_product"),
                   "private-unknown-key": {"prompt": "private-prompt-body"},
               }}
    client, requests = mock_client(invalid)
    with client, pytest.raises(SegmentationError) as failure:
        run(items, 2000, tmp_path, client, window_ms=1000, context_ms=500,
            allow_format_repair=False)
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
    diagnostic = failure.value.diagnostic
    assert diagnostic["reason_code"] == "ASSIGNMENT_KEYS_MISMATCH"
    assert {field: diagnostic[field] for field in COUNTS} == {
        "missing_core_id_count": 1, "extra_context_id_count": 1,
        "unknown_key_count": 1, "non_core_key_count": 2, "value_error_count": 2,
    }
    assert diagnostic["window_index"] == 0 and diagnostic["window_count"] == 2
    assert diagnostic["format_repair"] == "NOT_ATTEMPTED"
    assert_public(diagnostic)
    assert failure_diagnostic(failure.value, failure.value.code) == diagnostic
    assert len(requests) == 1 and not (tmp_path / "analysis-result.json").exists()


def test_real_classifier_distinguishes_invalid_values_from_assignment_key_mismatch(tmp_path):
    invalid = {"products": [product("mask", "面膜")],
               "assignments": {"private-core-A": assignment(["mask"], kind="mixed")}}
    client, requests = mock_client(invalid)
    with client, pytest.raises(SegmentationError) as failure:
        run([utterance("private-core-A", 0, 1000)], 1000, tmp_path, client,
            allow_format_repair=False)
    diagnostic = failure.value.diagnostic
    assert diagnostic["reason_code"] == "CLASSIFICATION_VALUES_INVALID"
    assert diagnostic["value_error_count"] == 1
    assert all(diagnostic[field] == 0 for field in COUNTS - {"value_error_count"})
    assert diagnostic["format_repair"] == "NOT_ATTEMPTED"
    assert_public(diagnostic)
    assert len(requests) == 1


@pytest.mark.parametrize("response", ["private-prompt-body not JSON",
                                      {"products": [], "assignments": []}])
def test_real_classifier_uses_schema_reason_without_inventing_unavailable_counts(tmp_path, response):
    client, requests = mock_client(response)
    with client, pytest.raises(SegmentationError) as failure:
        run([utterance("private-core-A", 0, 1000)], 1000, tmp_path, client,
            allow_format_repair=False)
    assert failure.value.diagnostic["reason_code"] == "CLASSIFICATION_SCHEMA_INVALID"
    assert not COUNTS & failure.value.diagnostic.keys()
    assert failure.value.diagnostic["format_repair"] == "NOT_ATTEMPTED"
    assert_public(failure.value.diagnostic)
    assert len(requests) == 1


def test_second_failed_validation_reports_its_own_counts_window_and_repair_state(tmp_path):
    first_bad = {"products": [], "assignments": {}}
    second_bad = {"products": [], "assignments": {
        "private-core-B": {**assignment(kind="non_product"), "raw": "private-provider-token"}}}
    client, requests = mock_client(valid_classification("private-core-A"), first_bad, second_bad)
    summaries = []
    with client:
        for _ in range(2):
            with pytest.raises(SegmentationError) as failure:
                run(two_windows(), 2000, tmp_path, client, window_ms=1000, context_ms=0)
            assert failure.value.code == "INVALID_MODEL_OUTPUT"
            diagnostic = failure.value.diagnostic
            assert diagnostic["window_index"] == 1 and diagnostic["window_count"] == 2
            assert diagnostic["format_repair"] == "FAILED_VALIDATION"
            assert diagnostic["reason_code"] == "CLASSIFICATION_VALUES_INVALID"
            assert diagnostic["missing_core_id_count"] == 0
            assert diagnostic["value_error_count"] == 1
            assert_public(diagnostic)
            summaries.append(copy.deepcopy(diagnostic))
    assert summaries[0] == summaries[1]
    assert len(requests) == 3, "Replaying saved invalid responses must not buy another repair"
    state = json.loads((tmp_path / "segmentation/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == 3
    # Existing private call records keep both errors. No new state-field names
    # are required for this public diagnostic contract.
    assert all(isinstance(call.get("validation_error"), str) and call["validation_error"]
               for call in state["calls"][-2:])
    assert state["calls"][-2]["validation_error"] != state["calls"][-1]["validation_error"]
    assert not (tmp_path / "analysis-result.json").exists()


@pytest.mark.parametrize("interruption", ["budget", "network"])
def test_repair_interruption_preserves_window_context_and_never_sends_an_unbudgeted_call(
    tmp_path, interruption,
):
    initial = {"products": [], "assignments": {}}
    if interruption == "budget":
        client, requests = mock_client(initial)
        maximum, expected_code, expected_calls = 1, "BUDGET_EXHAUSTED", 1
    else:
        client, requests = mock_client(initial, httpx.ReadTimeout("https://private.invalid/signed"))
        maximum, expected_code, expected_calls = 2, "PROVIDER_ERROR", 2
    with client, pytest.raises(SegmentationError) as failure:
        run([utterance("private-core-A", 0, 1000)], 1000, tmp_path, client, max_calls=maximum)
    assert failure.value.code == expected_code
    diagnostic = failure_diagnostic(failure.value, expected_code)
    assert diagnostic["window_index"] == 0 and diagnostic["window_count"] == 1
    assert diagnostic["format_repair"] == "INTERRUPTED"
    if interruption == "budget":
        assert diagnostic["reason_code"] == "REQUEST_BUDGET_EXHAUSTED"
    else:
        assert "reason_code" not in diagnostic, "A model-format reason cannot be mislabeled as a provider reason"
    assert_public(diagnostic)
    assert len(requests) == expected_calls
    state = json.loads((tmp_path / "segmentation/state.json").read_text(encoding="utf-8"))
    assert len(state["calls"]) == expected_calls
    assert state["calls"][0]["validation_error"]


def test_invalid_discovery_output_has_the_correct_two_pass_reason_and_window(tmp_path):
    invalid = {"products": [{"category": "面膜", "evidence_utterance_ids": ["private-unknown-key"]}]}
    client, requests = mock_client(invalid)
    with client, pytest.raises(SegmentationError) as failure:
        run([utterance("private-core-A", 0, 1000)], 1000, tmp_path, client,
            two_pass=True, max_calls=2)
    assert failure.value.code == "INVALID_DISCOVERY_OUTPUT"
    diagnostic = failure_diagnostic(failure.value, failure.value.code)
    assert diagnostic["reason_code"] == "DISCOVERY_SCHEMA_INVALID"
    assert diagnostic["window_index"] == 0 and diagnostic["window_count"] == 1
    assert diagnostic["format_repair"] == "NOT_ATTEMPTED"
    assert_public(diagnostic)
    assert len(requests) == 1


@pytest.mark.parametrize("two_pass", [False, True])
def test_zero_request_budget_is_legal_and_reports_exhaustion_before_any_request(tmp_path, two_pass):
    client, requests = mock_client()
    with client, pytest.raises(SegmentationError) as failure:
        run([utterance("private-core-A", 0, 1000)], 1000, tmp_path, client,
            max_calls=0, two_pass=two_pass)
    assert failure.value.code == "BUDGET_EXHAUSTED"
    assert failure.value.diagnostic["reason_code"] == "REQUEST_BUDGET_EXHAUSTED"
    assert_public(failure.value.diagnostic)
    assert requests == []
    assert not (tmp_path / "analysis-result.json").exists()


def test_window_limit_is_distinct_from_request_budget_and_fails_before_any_request(tmp_path):
    client, requests = mock_client()
    with client, pytest.raises(SegmentationError) as failure:
        run(two_windows(), 2000, tmp_path, client, window_ms=1000, max_windows=1, max_calls=80)
    assert failure.value.code == "BUDGET_EXHAUSTED"
    assert failure.value.diagnostic["reason_code"] == "WINDOW_LIMIT_EXCEEDED"
    assert_public(failure.value.diagnostic)
    assert requests == []


class SecretObject:
    def __str__(self):
        raise AssertionError("Diagnostic filtering must not stringify sensitive objects")

    def __repr__(self):
        return "<sensitive-test-object>"


class SensitiveFailure(Exception):
    code = "INVALID_MODEL_OUTPUT"

    def __init__(self, diagnostic):
        super().__init__("private-prompt-body private-provider-token")
        self.diagnostic = diagnostic

    def __str__(self):
        raise AssertionError("Public diagnostics must not be parsed from exception text")


def safe_context():
    return {"reason_code": "ASSIGNMENT_KEYS_MISMATCH", "window_index": 1, "window_count": 2,
            "missing_core_id_count": 2, "extra_context_id_count": 1,
            "unknown_key_count": 1, "non_core_key_count": 2, "value_error_count": 3,
            "format_repair": "FAILED_VALIDATION"}


def sensitive_context():
    return {**safe_context(), "raw_response": {"token": "private-provider-token"},
            "prompt": "private-prompt-body", "provider_object": SecretObject(),
            "source_path": "private-storage-path", "unknown_ids": ["private-unknown-key"],
            "stage_id": "foreign-stage", "attempt": 999, "run_token": "forged-token"}


def test_public_filter_preserves_only_allowed_fields_without_reading_exception_text():
    exception = SensitiveFailure(sensitive_context())
    diagnostic = failure_diagnostic(exception, exception.code)
    assert diagnostic == safe_context()
    assert_public(diagnostic)


@pytest.mark.parametrize("value", [None, [], "{\"reason_code\":\"ASSIGNMENT_KEYS_MISMATCH\"}",
                                    {"prompt": "private-prompt-body"},
                                    {"window_index": True, "window_count": 2},
                                    {"window_index": 2, "window_count": 2},
                                    {"reason_code": "private-provider-token"}])
def test_absent_or_unusable_context_returns_none_without_parsing_text_or_json(value):
    assert failure_diagnostic(SensitiveFailure(value), "INVALID_MODEL_OUTPUT") is None


def test_no_context_is_reconstructed_from_exception_cause_prompt_or_response():
    exception = RuntimeError(json.dumps(safe_context()))
    exception.__cause__ = SensitiveFailure(safe_context())
    exception.prompt = safe_context()
    exception.raw_response = safe_context()
    assert failure_diagnostic(exception, "INVALID_MODEL_OUTPUT") is None


@pytest.mark.parametrize("field,value", [("missing_core_id_count", True),
                                         ("value_error_count", 1.0),
                                         ("unknown_key_count", "2"),
                                         ("extra_context_id_count", -1),
                                         ("non_core_key_count", 1000001),
                                         ("value_error_count", SecretObject())])
def test_invalid_optional_counts_are_never_coerced_into_public_numbers(field, value):
    diagnostic = failure_diagnostic(SensitiveFailure({field: value}), "INVALID_MODEL_OUTPUT")
    assert diagnostic is None


def test_filter_enforces_cross_field_constraints_and_error_specific_reason_codes():
    exception = SensitiveFailure({**safe_context(), "non_core_key_count": 99})
    diagnostic = failure_diagnostic(exception, "PROVIDER_ERROR")
    assert "reason_code" not in diagnostic
    assert "format_repair" not in diagnostic
    assert_public(diagnostic)
    assert not {"non_core_key_count", "extra_context_id_count", "unknown_key_count"} <= diagnostic.keys()


def test_worker_binds_filtered_diagnostic_to_its_own_credentials_and_durable_fail_payload(tmp_path):
    client = FakeClient()
    client.reply = claim(stage_type="SEGMENT", attempt=3, run_token="owned-attempt-token")
    calls = []

    def execute(context):
        calls.append(context.lease.stage_id)
        raise SensitiveFailure(sensitive_context())

    storage, journal = tmp_path / "storage", tmp_path / "journal"
    runner = StageWorker(client, storage, journal, "diagnostic-worker", execute)
    assert runner.run_once() == "failed"
    failures = [entry for entry in client.callbacks if entry[0] == "fail"]
    assert len(failures) == 1 and calls == [client.reply["stage_id"]]
    action, stage_id, payload = failures[0]
    assert action == "fail" and stage_id == client.reply["stage_id"]
    assert payload == {"attempt": 3, "run_token": "owned-attempt-token",
                       "error_code": "INVALID_MODEL_OUTPUT", "retryable": False,
                       "diagnostic": safe_context()}
    assert_public(payload["diagnostic"])
    result_path = storage / Lease.parse(client.reply).attempt_key / "worker-result.json"
    callback = json.loads(result_path.read_text(encoding="utf-8"))
    assert callback == {"action": "fail", "stage_id": stage_id, "payload": payload}
    assert not (journal / "pending-callback.json").exists()
    assert not any(entry[0] == "complete" for entry in client.callbacks)


def test_lost_failure_ack_replays_the_exact_diagnostic_without_recomputing_or_reparsing(tmp_path):
    class LostFailureAck(FakeClient):
        lose_ack = True

        def callback(self, action, stage_id, payload):
            response = super().callback(action, stage_id, payload)
            if action == "fail" and self.lose_ack:
                self.lose_ack = False
                raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
            return response

    client = LostFailureAck()
    original = sensitive_context()
    calls = []

    def execute(context):
        calls.append(context.lease.stage_id)
        raise SensitiveFailure(original)

    storage, journal = tmp_path / "storage", tmp_path / "journal"
    with pytest.raises(WorkerClientError, match="JAVA_UNAVAILABLE"):
        StageWorker(client, storage, journal, "diagnostic-worker", execute).run_once()
    pending = (journal / "pending-callback.json").read_bytes()
    saved = json.loads(pending)
    assert saved["payload"]["diagnostic"] == safe_context()
    original.update(reason_code="private-provider-token", window_index=9999)

    def never_execute(context):
        pytest.fail("Lost callback acknowledgement must not rerun classification or model calls")

    assert StageWorker(client, storage, journal, "diagnostic-worker", never_execute).run_once() == "callback_replayed"
    failures = [entry for entry in client.callbacks if entry[0] == "fail"]
    assert len(failures) == 2 and failures[0] == failures[1]
    assert failures[1][2] == saved["payload"]
    assert len(client.claims) == 1 and calls == [client.reply["stage_id"]]
    assert not (journal / "pending-callback.json").exists()
    assert_public(failures[1][2]["diagnostic"])


def test_worker_without_context_keeps_the_legacy_failure_payload_shape(tmp_path):
    client = FakeClient()

    def execute(context):
        raise WorkerError("OCR_CACHE_CORRUPT")

    assert StageWorker(client, tmp_path / "storage", tmp_path / "journal",
                       "legacy-worker", execute).run_once() == "failed"
    failures = [entry for entry in client.callbacks if entry[0] == "fail"]
    assert len(failures) == 1
    assert failures[0][2] == {"attempt": 1, "run_token": "token-1",
                              "error_code": "OCR_CACHE_CORRUPT", "retryable": False}
    assert "diagnostic" not in failures[0][2]
