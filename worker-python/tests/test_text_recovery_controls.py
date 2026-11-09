"""Synthetic wire faults and a local silent server exercise recovery controls."""
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest
from livecut.model_budget import BudgetTransport
from livecut.text_resilience import DeadlineHTTPTransport, ResilientTextTransport, _provider_failure
from livecut.worker import WorkerError
from test_text_resilience import BODY, URL, Ledger, backup, backup_context, setup


@pytest.mark.parametrize("body", [b"", b" ", b"<html>gateway</html>", b"null", b"[]", b"{}"])
def test_incomplete_http_200_response_recovers_without_success_progress(tmp_path, body):
    hits, progress = [], []

    def provider(request):
        hits.append(request)
        return httpx.Response(200, content=body) if len(hits) == 1 else httpx.Response(200, json={"choices": []})

    client, ledger, _ = setup(tmp_path, provider, progress=progress.append)
    with client:
        assert client.post(URL, json=BODY).json() == {"choices": []}
    assert len(hits) == len(ledger.calls) == 2
    assert [call["status"] for call in ledger.calls.values()] == ["UNKNOWN_OUTCOME", "SUCCEEDED"]
    assert [value["attempt_number"] for value in progress if value["state"] == "SUCCEEDED"] == [2]


@pytest.mark.parametrize("error", [{"code": "Throttling.RateQuota"}, {"code": "InternalError"},
    {"error": {"type": "rate_limit_error"}}, {"error": {"code": "service_unavailable"}}])
def test_known_http_200_transient_error_uses_a_new_budgeted_attempt(tmp_path, error):
    hits = []

    def provider(request):
        hits.append(request)
        return httpx.Response(200, json=error if len(hits) == 1 else {"choices": []})

    client, ledger, _ = setup(tmp_path, provider)
    with client:
        assert client.post(URL, json=BODY).status_code == 200
    assert len(hits) == len(ledger.calls) == 2


@pytest.mark.parametrize("status,error", [(200, {"code": "InvalidParameter"}),
    (200, {"code": "InternalError.Algo.InvalidParameter"}), (400, {"error": {"code": "invalid_parameter"}})])
def test_parameter_faults_are_not_retried_as_internal_errors(tmp_path, status, error):
    hits = []

    def provider(request):
        hits.append(request)
        return httpx.Response(status, json=error)

    client, ledger, _ = setup(tmp_path, provider)
    with client:
        assert client.post(URL, json=BODY).json() == error
    assert len(hits) == len(ledger.calls) == 1


@pytest.mark.parametrize("status,error", [(200, {"code": "Throttling.AllocationQuota"}),
    (429, {"error": {"code": "insufficient_quota"}}), (402, {"error": "balance"}),
    (403, {"error": {"code": "AllocationQuota.FreeTierOnly"}}),
    (403, {"error": {"type": "AllocationQuota.FreeTierOnly"}}),
    (200, {"error": {"code": "invalid_api_key"}})])
def test_quota_or_auth_fault_switches_without_retrying_same_credentials(tmp_path, monkeypatch, status, error):
    backup(monkeypatch)
    hits = []

    def provider(request):
        hits.append(request)
        return httpx.Response(status, json=error) if len(hits) == 1 else httpx.Response(200, json={"choices": []})

    client, ledger, _ = setup(tmp_path, provider, context=backup_context(tmp_path))
    with client:
        assert client.post(URL, json=BODY).status_code == 200
    assert len(hits) == len(ledger.calls) == 2
    assert hits[1].url.host == "backup.example"


@pytest.mark.parametrize("status,error", [
    (403, {"code": "AllocationQuota.FreeTierOnly"}),
    (403, {"error": {"code": "AllocationQuota.FreeTierOnly", "type": "AllocationQuota.FreeTierOnly"}}),
    (403, {"error": {"type": "allocationquota.freetieronly"}}),
    (200, {"error": {"code": "AllocationQuota.FreeTierOnly"}}),
])
def test_free_tier_exhaustion_is_quota_and_does_not_repeat_without_backup(tmp_path, status, error):
    hits, progress = [], []

    def provider(request):
        hits.append(request)
        return httpx.Response(status, json=error)

    client, ledger, context = setup(tmp_path, provider, progress=progress.append)
    with client:
        assert client.post(URL, json=BODY).json() == error
        # Re-reading the same original operation replays its recorded refusal.
        assert client.post(URL, json=BODY).json() == error
    assert len(hits) == len(ledger.calls) == 1
    assert next(iter(ledger.calls.values()))["status"] == ("FAILED" if status >= 300 else "UNKNOWN_OUTCOME")
    terminal = [value for value in progress if value["state"] == "EXHAUSTED"]
    assert terminal and all(value["error_code"] == "PROVIDER_QUOTA_EXHAUSTED" for value in terminal)
    assert "PROVIDER_UNAUTHORIZED" not in (context.directory / "text-execution-trace.json").read_text()


def test_generic_403_and_auth_codes_do_not_become_quota_from_arbitrary_messages():
    assert _provider_failure(403, {"error": {"code": "permission_denied", "message": "Free quota exhausted"}}, False) == ("PROVIDER_UNAUTHORIZED", True)
    assert _provider_failure(403, {"message": "AllocationQuota.FreeTierOnly"}, False) == ("PROVIDER_UNAUTHORIZED", True)
    assert _provider_failure(401, {"error": {"code": "invalid_api_key"}}, False) == ("PROVIDER_UNAUTHORIZED", True)


def test_fenced_lease_interrupts_silent_read_and_does_not_dispatch_retry(tmp_path):
    ledger, stopped_at, release = Ledger(), [], threading.Event()
    ctx = backup_context(tmp_path)

    def cancel():
        stopped_at.append(time.monotonic())
        ctx.guard.lose()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", 0)))
            self.send_response(200)
            self.send_header("Content-Length", "1024")
            self.end_headers()
            timer = threading.Timer(.05, cancel)
            timer.start()
            release.wait(3)
            timer.cancel()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .02}, daemon=True)
    thread.start()
    budget = BudgetTransport(ledger, ctx, inner=DeadlineHTTPTransport())
    try:
        with httpx.Client(transport=ResilientTextTransport(budget, ctx)) as client:
            with pytest.raises(WorkerError, match="LEASE_LOST"):
                client.post(f"http://127.0.0.1:{server.server_port}/chat/completions", json=BODY)
        assert stopped_at and time.monotonic() - stopped_at[0] < 1.5
        assert len(ledger.calls) == 1
        assert next(iter(ledger.calls.values()))["status"] == "UNKNOWN_OUTCOME"
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join(1)
