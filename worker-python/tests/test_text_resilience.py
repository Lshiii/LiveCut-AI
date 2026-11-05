"""Synthetic providers verify recovery behavior, not provider availability."""
import json
import threading
import time
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest
from livecut.model_budget import BudgetTransport
from livecut.text_resilience import (
    DeadlineHTTPTransport,
    ResilientTextTransport,
    TextRecoveryError,
    TextRetryPolicy,
)
from livecut.worker import Lease, LeaseGuard, StageContext
from livecut.worker_client import WorkerClientError
from test_worker import claim

URL = "https://primary.example/chat/completions"
BODY = {"model": "primary-model", "messages": [{"role": "user", "content": "json"}]}


class Ledger:
    def __init__(self, limit=24):
        self.calls, self.events, self.limit = {}, [], limit
        self.fail = False

    def reserve_call(self, payload):
        if self.fail:
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
        rid = payload["request_id"]
        if rid not in self.calls:
            if len(self.calls) >= self.limit:
                raise WorkerClientError("MODEL_BUDGET_EXHAUSTED")
            self.calls[rid] = {**payload, "status": "RESERVED"}
        return {"outcome": "EXISTING", "call": dict(self.calls[rid])}

    def update_call(self, rid, payload):
        self.events.append(payload["status"])
        self.calls[rid].update(payload)
        return {"outcome": "UPDATED", "call": dict(self.calls[rid])}

    def lookup_call(self, payload):
        rid = payload["request_id"]
        if rid not in self.calls:
            return {"outcome": "NOT_FOUND", "call": None}
        return {"outcome": "EXISTING", "call": dict(self.calls[rid])}


def setup(tmp_path, handler, *, ledger=None, policy=None, context=None, progress=None, **kwargs):
    ledger = ledger or Ledger()
    if context is None:
        lease = Lease.parse(claim(stage_type="SEGMENT"))
        context = StageContext(lease, tmp_path, tmp_path / "attempt", LeaseGuard(lease, threading.Event()))
    budget = BudgetTransport(ledger, context, inner=httpx.MockTransport(handler))
    outer = ResilientTextTransport(budget, context,
        policy=policy or TextRetryPolicy(backoff_seconds=0), progress=progress, **kwargs)
    return httpx.Client(transport=outer), ledger, context


@pytest.mark.parametrize("first", [408, 429, 500, 502, 503, 504])
def test_transient_response_then_success_counts_both_calls(tmp_path, first):
    hits = []
    def provider(request):
        hits.append(request)
        return httpx.Response(first if len(hits) == 1 else 200, json={"choices": []})
    progress = []
    client, ledger, ctx = setup(tmp_path, provider, progress=progress.append)
    assert client.post(URL, json=BODY).status_code == 200
    assert len(hits) == len(ledger.calls) == 2
    assert [p["state"] for p in progress] == ["REQUESTING", "RETRY_WAIT", "REQUESTING", "SUCCEEDED"]
    assert next(iter(ledger.calls.values()))["status"] in {"FAILED", "UNKNOWN_OUTCOME"}
    assert (ctx.directory / "text-execution-trace.json").exists()


@pytest.mark.parametrize("exception", [httpx.ConnectTimeout, httpx.ConnectError, httpx.ReadTimeout,
                                        httpx.WriteTimeout, httpx.RemoteProtocolError])
def test_unknown_outcome_is_preserved_and_new_slot_is_bounded(tmp_path, exception):
    hits = []
    def provider(request):
        hits.append(request)
        if len(hits) < 3:
            raise exception("sensitive provider detail", request=request)
        return httpx.Response(200, json={"choices": []})
    client, ledger, _ = setup(tmp_path, provider)
    assert client.post(URL, json=BODY).status_code == 200
    assert len(hits) == len(ledger.calls) == 3
    assert [c["status"] for c in ledger.calls.values()] == ["UNKNOWN_OUTCOME", "UNKNOWN_OUTCOME", "SUCCEEDED"]
    assert len({c["request_id"] for c in ledger.calls.values()}) == 3


def test_restarting_does_not_reset_attempts_or_resend_unknown_ordinals(tmp_path):
    hits = []
    def provider(request):
        hits.append(request)
        raise httpx.ReadTimeout("unknown", request=request)
    client, ledger, ctx = setup(tmp_path, provider)
    with pytest.raises(TextRecoveryError, match="TEXT_RETRIES_EXHAUSTED"):
        client.post(URL, json=BODY)
    client.close()
    resumed_lease = replace(ctx.lease, attempt=ctx.lease.attempt + 1)
    resumed_context = replace(ctx, lease=resumed_lease, directory=tmp_path / "second-attempt")
    resumed, _, _ = setup(tmp_path, provider, ledger=ledger, context=resumed_context)
    with pytest.raises(TextRecoveryError, match="TEXT_RETRIES_EXHAUSTED"):
        resumed.post(URL, json=BODY)
    assert len(hits) == len(ledger.calls) == 3


def test_restarting_replays_prior_success_without_provider(tmp_path):
    hits = []
    def provider(request):
        hits.append(request)
        return httpx.Response(503 if len(hits) == 1 else 200, json={"choices": [{"message": "paid"}]})
    first, ledger, ctx = setup(tmp_path, provider)
    expected = first.post(URL, json=BODY).json()
    first.close()
    resumed, _, _ = setup(tmp_path, provider, ledger=ledger,
        context=replace(ctx, directory=tmp_path / "second-attempt"))
    assert resumed.post(URL, json=BODY).json() == expected
    assert len(hits) == len(ledger.calls) == 2


@pytest.mark.parametrize("status", [400, 401, 403, 404, 422])
def test_nontransient_errors_do_not_repeat_without_backup(tmp_path, status):
    hits = []
    def provider(request):
        hits.append(request)
        return httpx.Response(status, json={"error": {"code": "bad_request"}})
    client, ledger, _ = setup(tmp_path, provider)
    assert client.post(URL, json=BODY).status_code == status
    assert len(hits) == len(ledger.calls) == 1


def backup(monkeypatch, *, missing_key=False, model="backup-model"):
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", json.dumps([{
        "name": "backup", "base_url": "https://backup.example/v1", "api_style": "openai",
        "model": model, "key_env": "LIVECUT_BACKUP_TEXT_KEY"}]))
    if not missing_key:
        monkeypatch.setenv("LIVECUT_BACKUP_TEXT_KEY", "synthetic-backup-key")


def backup_context(tmp_path):
    lease = replace(Lease.parse(claim(stage_type="SEGMENT")), config={"text_fallback_models": ["backup-model"]})
    return StageContext(lease, tmp_path, tmp_path / "attempt", LeaseGuard(lease, threading.Event()))


def test_auth_error_switches_immediately_with_fresh_credentials(tmp_path, monkeypatch):
    backup(monkeypatch)
    hits, progress = [], []
    def provider(request):
        hits.append(request)
        return httpx.Response(401 if request.url.host == "primary.example" else 200, json={"choices": []})
    client, ledger, _ = setup(tmp_path, provider, context=backup_context(tmp_path), progress=progress.append)
    assert client.post(URL, json=BODY, headers={"Authorization": "Bearer synthetic-primary-key"}).status_code == 200
    assert len(ledger.calls) == 2
    assert hits[1].url == "https://backup.example/v1/chat/completions"
    assert hits[1].headers["Authorization"] == "Bearer synthetic-backup-key"
    assert json.loads(hits[1].content)["model"] == "backup-model"
    assert any(p["state"] == "SWITCHING_PROVIDER" for p in progress)
    assert not any("key" in json.dumps(p) or "https://" in json.dumps(p) for p in progress)


def test_dashscope_backup_reencodes_messages_without_leaking_primary_headers(tmp_path, monkeypatch):
    backup(monkeypatch)
    raw = json.loads(__import__('os').environ["LIVECUT_TEXT_FALLBACKS_JSON"])
    raw[0]["api_style"] = "dashscope"
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", json.dumps(raw))
    hits = []
    def provider(request):
        hits.append(request)
        return httpx.Response(503 if len(hits) < 3 else 200, json={"output": {"choices": []}})
    client, _, _ = setup(tmp_path, provider, context=backup_context(tmp_path))
    assert client.post(URL, json=BODY).status_code == 200
    payload = json.loads(hits[2].content)
    assert payload["input"]["messages"] == BODY["messages"]
    assert "messages" not in payload
    assert hits[2].url.path == "/v1/api/v1/services/aigc/text-generation/generation"


def test_unapproved_fallback_is_ignored_but_allowed_fallback_requires_own_key(tmp_path, monkeypatch):
    backup(monkeypatch)
    client, ledger, _ = setup(tmp_path, lambda req: httpx.Response(200, json={"choices": []}))
    assert client.post(URL, json=BODY).status_code == 200
    assert len(ledger.calls) == 1
    monkeypatch.delenv("LIVECUT_BACKUP_TEXT_KEY")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "must-not-be-used")
    with pytest.raises(TextRecoveryError, match="INVALID_TEXT_RETRY_CONFIGURATION"):
        setup(tmp_path, lambda req: None, context=backup_context(tmp_path))


def test_retry_after_is_bounded_and_survives_failed_response_replay(tmp_path):
    hits, waits = [], []
    clock = [0.0]
    def provider(request):
        hits.append(request)
        return httpx.Response(429 if len(hits) == 1 else 200,
            json={"choices": []}, headers={"Retry-After": "9999"})
    def wait(seconds):
        waits.append(seconds)
        clock[0] += seconds
    client, ledger, ctx = setup(tmp_path, provider, monotonic=lambda: clock[0], sleep=wait)
    assert client.post(URL, json=BODY).status_code == 200
    assert sum(waits) == pytest.approx(30)
    wrapper = json.loads((tmp_path / next(iter(ledger.calls.values()))["response_key"]).read_text())
    assert wrapper["headers"]["retry-after"] == "30"
    waits.clear()
    resumed, _, _ = setup(tmp_path, provider, ledger=ledger, context=ctx,
        monotonic=lambda: clock[0], sleep=wait)
    assert resumed.post(URL, json=BODY).status_code == 200
    assert len(hits) == len(ledger.calls) == 2
    assert sum(waits) == pytest.approx(30)


def test_restart_keeps_global_wall_budget_and_cannot_dispatch_after_deadline(tmp_path, monkeypatch):
    clock = [1000.0]
    monkeypatch.setattr('livecut.text_resilience.time.time', lambda: clock[0])
    hits = []
    def provider(request):
        hits.append(request)
        raise httpx.ReadTimeout("unknown", request=request)
    client, ledger, ctx = setup(tmp_path, provider)
    with pytest.raises(TextRecoveryError, match="TEXT_RETRIES_EXHAUSTED"):
        client.post(URL, json=BODY)
    clock[0] += 241
    progress = []
    resumed, _, _ = setup(tmp_path, provider, ledger=ledger, context=ctx, progress=progress.append)
    with pytest.raises(TextRecoveryError, match="TEXT_RETRY_DEADLINE_EXCEEDED"):
        resumed.post(URL, json=BODY)
    assert len(hits) == len(ledger.calls) == 3
    assert progress[-1]["elapsed_seconds"] >= 241


def test_clock_rollback_cannot_extend_paid_recovery_budget(tmp_path, monkeypatch):
    clock = [1000.0]
    monkeypatch.setattr('livecut.text_resilience.time.time', lambda: clock[0])
    client, ledger, ctx = setup(tmp_path, lambda req: httpx.Response(200, json={"choices": []}))
    client.post(URL, json=BODY)
    clock[0] -= 2
    resumed, _, _ = setup(tmp_path, lambda req: None, ledger=ledger, context=ctx)
    with pytest.raises(TextRecoveryError, match="TEXT_RETRY_CLOCK_CONFLICT"):
        resumed.post(URL, json=BODY)
    assert len(ledger.calls) == 1


def test_expired_recovery_still_replays_paid_success_without_reserving_more(tmp_path, monkeypatch):
    clock = [1000.0]
    monkeypatch.setattr('livecut.text_resilience.time.time', lambda: clock[0])
    hits = []
    def provider(request):
        hits.append(request)
        return httpx.Response(503 if len(hits) == 1 else 200, json={"choices": ["paid success"]})
    client, ledger, ctx = setup(tmp_path, provider)
    expected = client.post(URL, json=BODY).json()
    clock[0] += 241
    resumed, _, _ = setup(tmp_path, provider, ledger=ledger,
        context=replace(ctx, directory=tmp_path / "after-expiry"))
    assert resumed.post(URL, json=BODY).json() == expected
    assert len(hits) == len(ledger.calls) == 2


def test_expired_unstarted_slots_do_not_allocate_reservations(tmp_path, monkeypatch):
    clock = [1000.0]
    monkeypatch.setattr('livecut.text_resilience.time.time', lambda: clock[0])
    hits = []
    def provider(request):
        hits.append(request)
        return httpx.Response(200, json={"choices": []})
    client, ledger, ctx = setup(tmp_path, provider)
    client.post(URL, json=BODY)
    ledger.calls.clear()  # Simulates policy written before the first reservation.
    clock[0] += 241
    resumed, _, _ = setup(tmp_path, provider, ledger=ledger, context=ctx)
    with pytest.raises(TextRecoveryError, match="TEXT_RETRY_DEADLINE_EXCEEDED"):
        resumed.post(URL, json=BODY)
    assert len(hits) == 1 and not ledger.calls


def test_nonretryable_response_emits_terminal_progress(tmp_path):
    progress = []
    client, ledger, _ = setup(tmp_path, lambda req: httpx.Response(400, json={"error": "bad request"}), progress=progress.append)
    assert client.post(URL, json=BODY).status_code == 400
    assert progress[-1]["state"] == "EXHAUSTED"
    assert progress[-1]["error_code"] == "PROVIDER_REQUEST_INVALID"
    assert len(ledger.calls) == 1


def test_budget_exhaustion_stops_recovery_without_third_dispatch(tmp_path):
    hits = []
    def provider(request):
        hits.append(request)
        return httpx.Response(429, json={"error": "rate_limited"})
    client, ledger, _ = setup(tmp_path, provider, ledger=Ledger(limit=2))
    with pytest.raises(WorkerClientError, match="MODEL_BUDGET_EXHAUSTED"):
        client.post(URL, json=BODY)
    assert len(hits) == len(ledger.calls) == 2


def test_progress_persistence_failure_cannot_dispatch_provider(tmp_path):
    hits = []
    def progress(value):
        raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
    client, ledger, _ = setup(tmp_path, lambda req: hits.append(req), progress=progress)
    with pytest.raises(WorkerClientError, match="JAVA_UNAVAILABLE"):
        client.post(URL, json=BODY)
    assert not hits and not ledger.calls


def test_changed_policy_cannot_reset_recovery_budget(tmp_path):
    client, ledger, ctx = setup(tmp_path, lambda req: httpx.Response(200, json={"choices": []}))
    client.post(URL, json=BODY)
    resumed, _, _ = setup(tmp_path, lambda req: None, ledger=ledger, context=ctx,
        policy=TextRetryPolicy(attempt_timeout_seconds=100, backoff_seconds=0))
    with pytest.raises(TextRecoveryError, match="TEXT_RETRY_POLICY_CONFLICT"):
        resumed.post(URL, json=BODY)
    assert len(ledger.calls) == 1


def test_stop_during_backoff_prevents_another_paid_request(tmp_path):
    hits = []
    lease = Lease.parse(claim(stage_type="SEGMENT"))
    stop = threading.Event()
    ctx = StageContext(lease, tmp_path, tmp_path / "attempt", LeaseGuard(lease, stop))
    def provider(request):
        hits.append(request)
        return httpx.Response(503, json={"error": "busy"})
    def wait(seconds):
        stop.set()
    client, ledger, _ = setup(tmp_path, provider, context=ctx,
        policy=TextRetryPolicy(backoff_seconds=1), sleep=wait)
    with pytest.raises(Exception, match="WORKER_STOPPED"):
        client.post(URL, json=BODY)
    assert len(hits) == len(ledger.calls) == 1


def test_real_drip_response_hits_wall_deadline_and_closes_connection():
    disconnected = threading.Event()
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def log_message(self, *args):
            pass
        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", 0)))
            self.send_response(200)
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            try:
                for _ in range(100):
                    self.wfile.write(b"1\r\n \r\n")
                    self.wfile.flush()
                    time.sleep(.02)
                self.wfile.write(b"0\r\n\r\n")
            except (ConnectionError, OSError):
                disconnected.set()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .02}, daemon=True)
    thread.start()
    try:
        with httpx.Client(transport=DeadlineHTTPTransport()) as client:
            started = time.monotonic()
            request = client.build_request("POST", f"http://127.0.0.1:{server.server_port}/chat/completions", json=BODY)
            request.extensions["livecut_deadline_at"] = started + .15
            with pytest.raises(httpx.ReadTimeout):
                client.send(request)
            assert time.monotonic() - started < .7
        assert disconnected.wait(1)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(1)
