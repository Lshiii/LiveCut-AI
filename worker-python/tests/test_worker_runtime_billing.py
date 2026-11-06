"""Runtime recovery with a synthetic durable ledger; no real Java or paid provider is used."""

import copy
import json
import threading
from datetime import datetime, timezone

import httpx
import pytest
from livecut.model_budget import BudgetTransport
from livecut.worker import Lease, LeaseGuard, StageContext, StageWorker, sha256
from livecut.worker_client import WorkerClientError
from test_worker import claim, fake_executor

SUBMIT = "https://asr.example/services/audio/asr/transcription"
TASK_ID = "synthetic-runtime-asr-task"
AUDIO_HASH = "b" * 64
STAGE_INPUT_HASH = "c" * 64


class RuntimeLedgerClient:
    """Persists reservation identities across registry outages and later stage attempts."""

    def __init__(self, stop, *, lose_unknown_settlement=False):
        self.stop = stop
        self.lose_unknown_settlement = lose_unknown_settlement
        self.max_asr_submissions = 1
        self.asr_calls_reserved = 0
        self.calls = {}
        self.reserve_payloads = []
        self.registrations = []
        self.unregistrations = []
        self.ready_calls = 0
        self.claims = []
        self.callbacks = []
        self.available = True
        self.reply = claim(stage_type="ASR", attempt=2, run_token="runtime-token-2",
                           input_hash=STAGE_INPUT_HASH,
                           config={"asr_model": "paraformer-v2", "max_asr_submissions": 1})

    @staticmethod
    def receipt(worker, session, outcome):
        return {"worker_id": worker, "session_id": session, "outcome": outcome,
                "heartbeat_timeout_seconds": 35,
                "last_seen_at": datetime.now(timezone.utc).isoformat()}

    def register_worker(self, worker_id, session_id, capabilities, **kwargs):
        self.registrations.append((worker_id, session_id, capabilities))
        return self.receipt(worker_id, session_id, "REGISTERED")

    def heartbeat_worker(self, worker_id, session_id, **kwargs):
        return self.receipt(worker_id, session_id, "ACCEPTED")

    def unregister_worker(self, worker_id, session_id, **kwargs):
        self.unregistrations.append((worker_id, session_id))
        return self.receipt(worker_id, session_id, "UNREGISTERED")

    def ready(self, **kwargs):
        self.ready_calls += 1
        if self.ready_calls == 1:
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
        return {"items": [{"id": self.reply["stage_id"], "job_id": self.reply["job_id"],
                           "stage_type": "ASR"}] if self.available else []}

    def claim(self, stage_id, worker_id, claim_request_id, **kwargs):
        self.claims.append((stage_id, worker_id, claim_request_id, kwargs))
        self.available = False
        return copy.deepcopy(self.reply)

    def callback(self, action, stage_id, payload, **kwargs):
        self.callbacks.append((action, stage_id, copy.deepcopy(payload)))
        if action in {"complete", "fail"}:
            self.stop.set()
        return {"outcome": "ACCEPTED", "stage": copy.deepcopy(self.reply)}

    def reserve_call(self, payload):
        self.reserve_payloads.append(copy.deepcopy(payload))
        request_id = payload["request_id"]
        if request_id not in self.calls:
            if self.asr_calls_reserved >= self.max_asr_submissions:
                raise WorkerClientError("MODEL_BUDGET_EXHAUSTED")
            self.asr_calls_reserved += 1
            self.calls[request_id] = {**copy.deepcopy(payload), "status": "RESERVED",
                                      "provider_task_id": None, "response_key": None,
                                      "response_hash": None}
            outcome = "RESERVED"
        else:
            existing = self.calls[request_id]
            for field in ("stage_id", "input_hash", "operation_key", "model_id", "kind"):
                assert existing[field] == payload[field], "Runtime recovery changed billable identity"
            outcome = "EXISTING"
        return {"outcome": outcome, "call": copy.deepcopy(self.calls[request_id])}

    def update_call(self, request_id, payload):
        if payload["status"] == "UNKNOWN_OUTCOME" and self.lose_unknown_settlement:
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
        existing = self.calls[request_id]
        if payload["status"] == "DISPATCHING":
            assert existing["status"] == "RESERVED", "Dispatch permission must be granted only once"
        existing["status"] = payload["status"]
        if payload.get("provider_task_id") is not None:
            existing["provider_task_id"] = payload["provider_task_id"]
        for field in ("response_key", "response_hash"):
            if payload.get(field) is not None:
                existing[field] = payload[field]
        return {"outcome": "UPDATED", "call": copy.deepcopy(existing)}


def context(tmp_path, attempt):
    lease = Lease.parse(claim(stage_type="ASR", attempt=attempt,
                              run_token=f"runtime-token-{attempt}", input_hash=STAGE_INPUT_HASH,
                              config={"asr_model": "paraformer-v2", "max_asr_submissions": 1}))
    return StageContext(lease, tmp_path / "storage", tmp_path / "storage" / lease.attempt_key,
                        LeaseGuard(lease, threading.Event()), provider_input_hash=AUDIO_HASH)


def submission_body(temporary="before-runtime-outage"):
    return {"model": "paraformer-v2", "input": {"file_urls": [f"oss://{temporary}"]}}


def run_after_control_plane_outage(tmp_path, ledger, executor):
    lifecycle, errors = [], []
    runner = StageWorker(ledger, tmp_path / "storage", tmp_path / "journal", "billing-worker",
                         executor, stop=ledger.stop, reconnect_wait_seconds=3,
                         worker_heartbeat_seconds=.05, lifecycle_observer=lifecycle.append)

    def execute():
        try:
            runner.run(poll_seconds=.001, stage_type="ASR")
        except Exception as error:
            errors.append(error)

    thread = threading.Thread(target=execute, daemon=True)
    thread.start()
    try:
        thread.join(5)
        assert not thread.is_alive(), "Synthetic runtime recovery did not finish within its bound"
    finally:
        ledger.stop.set()
        thread.join(2)
    assert not errors, [getattr(error, "code", type(error).__name__) for error in errors]
    assert ledger.ready_calls >= 2 and ledger.claims, "The runtime must recover after JAVA_UNAVAILABLE"
    assert ledger.registrations and ledger.unregistrations
    return lifecycle


@pytest.mark.parametrize("settlement_lost", [False, True], ids=["unknown-outcome", "dispatching"])
def test_runtime_reconnect_keeps_unknown_asr_budget_and_never_submits_or_polls_again(
        tmp_path, settlement_lost):
    ledger = RuntimeLedgerClient(threading.Event(), lose_unknown_settlement=settlement_lost)
    external = []

    def uncertain_provider(request):
        external.append((request.method, request.url.path))
        raise httpx.ReadTimeout("Synthetic uncertain ASR acceptance", request=request)

    seed = context(tmp_path, 1)
    with httpx.Client(transport=BudgetTransport(ledger, seed,
                     inner=httpx.MockTransport(uncertain_provider))) as client:
        with pytest.raises(httpx.ReadTimeout):
            client.post(SUBMIT, json=submission_body())
    old = copy.deepcopy(next(iter(ledger.calls.values())))
    assert old["status"] == ("DISPATCHING" if settlement_lost else "UNKNOWN_OUTCOME")
    assert old["provider_task_id"] is None and ledger.asr_calls_reserved == 1

    def execute(stage):
        stage.provider_input_hash = AUDIO_HASH
        with httpx.Client(transport=BudgetTransport(ledger, stage,
                         inner=httpx.MockTransport(uncertain_provider))) as client:
            client.post(SUBMIT, json=submission_body("after-runtime-outage"))
        pytest.fail("An ASR with unknown acceptance must not become a successful new submission")

    run_after_control_plane_outage(tmp_path, ledger, execute)
    assert ledger.max_asr_submissions == ledger.asr_calls_reserved == 1
    assert list(ledger.calls.values()) == [old]
    assert len(ledger.reserve_payloads) == 2
    assert ledger.reserve_payloads[0]["request_id"] == ledger.reserve_payloads[1]["request_id"]
    assert [request for request in external if request[1].endswith("/transcription")] == [
        ("POST", "/services/audio/asr/transcription")]
    assert not any("/tasks/" in path for _, path in external)
    assert ledger.callbacks[-1][0] == "fail"
    assert ledger.callbacks[-1][2]["error_code"] == "MODEL_CALL_REQUIRES_RECOVERY"


def test_runtime_reconnect_replays_submitted_asr_and_queries_its_original_task(tmp_path):
    ledger = RuntimeLedgerClient(threading.Event())
    external = []

    def provider(request):
        external.append((request.method, request.url.path))
        if request.url.path.endswith("/transcription"):
            return httpx.Response(200, json={"output": {"task_id": TASK_ID, "task_status": "PENDING"}})
        assert request.url.path == f"/tasks/{TASK_ID}"
        return httpx.Response(200, json={"output": {"task_id": TASK_ID,
                                                   "task_status": "SUCCEEDED"},
                                        "usage": {"duration": 1}})

    seed = context(tmp_path, 1)
    with httpx.Client(transport=BudgetTransport(ledger, seed,
                     inner=httpx.MockTransport(provider))) as client:
        client.post(SUBMIT, json=submission_body())
    old = copy.deepcopy(next(iter(ledger.calls.values())))
    recorded = tmp_path / "storage" / old["response_key"]
    recorded_bytes = recorded.read_bytes()
    assert old["status"] == "SUBMITTED" and old["provider_task_id"] == TASK_ID
    assert sha256(recorded) == old["response_hash"]

    def execute(stage):
        stage.provider_input_hash = AUDIO_HASH
        with httpx.Client(transport=BudgetTransport(ledger, stage,
                         inner=httpx.MockTransport(provider))) as client:
            replayed = client.post(SUBMIT, json=submission_body("new-temporary-upload-url"))
            assert replayed.json()["output"]["task_id"] == TASK_ID
            polled = client.post(f"https://asr.example/tasks/{TASK_ID}")
            assert polled.json()["output"]["task_status"] == "SUCCEEDED"
        return fake_executor([])(stage)

    run_after_control_plane_outage(tmp_path, ledger, execute)
    assert ledger.max_asr_submissions == ledger.asr_calls_reserved == 1
    assert len(ledger.calls) == 1
    settled = next(iter(ledger.calls.values()))
    assert settled["status"] == "SUCCEEDED" and settled["provider_task_id"] == TASK_ID
    for field in ("request_id", "operation_key", "input_hash", "attempt", "run_token",
                  "response_key", "response_hash"):
        assert settled[field] == old[field]
    assert recorded.read_bytes() == recorded_bytes
    assert json.loads(recorded_bytes)["body"]["output"]["task_id"] == TASK_ID
    assert external == [("POST", "/services/audio/asr/transcription"), ("POST", f"/tasks/{TASK_ID}")]
    assert ledger.callbacks[-1][0] == "complete"
