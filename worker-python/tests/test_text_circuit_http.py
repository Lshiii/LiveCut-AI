"""Synthetic loopback platforms exercise circuit routing without paid APIs."""

import json
import threading
from contextlib import contextmanager
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
from livecut.model_budget import BudgetTransport
from livecut.text_resilience import DeadlineHTTPTransport, ResilientTextTransport, TextRetryPolicy
from test_text_resilience import BODY, URL, Ledger, backup, backup_context


@contextmanager
def local_platforms():
    state = {"primary_available": False, "hits": []}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            platform = self.server.platform
            state["hits"].append((platform, body["messages"][0]["content"]))
            healthy = platform == "backup" or state["primary_available"]
            raw = ({"choices": [{"message": {"content": "synthetic recorded response"}}]}
                   if healthy else {"error": {"code": "service_unavailable"}})
            payload = json.dumps(raw).encode()
            self.send_response(200 if healthy else 503)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    servers, threads = {}, []
    try:
        for name in ("primary", "backup"):
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.platform = name
            servers[name] = server
            thread = threading.Thread(target=server.serve_forever,
                                      kwargs={"poll_interval": .05}, daemon=True)
            thread.start()
            threads.append(thread)
        yield servers, state
    finally:
        for server in servers.values():
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(5)
            assert not thread.is_alive()


class LoopbackTransport(httpx.BaseTransport):
    """Keep synthetic profile identities, send bytes only to local test servers."""

    def __init__(self, servers):
        self.servers = servers
        self.inner = DeadlineHTTPTransport()

    def handle_request(self, request):
        assert request.url.host in {"primary.example", "backup.example"}
        platform = request.url.host.split(".")[0]
        port = self.servers[platform].server_port
        mapped = httpx.Request(request.method, f"http://127.0.0.1:{port}{request.url.path}",
            headers={"Content-Type": "application/json"}, content=request.read(),
            extensions=request.extensions)
        return self.inner.handle_request(mapped)

    def close(self):
        self.inner.close()


def test_real_local_http_switches_windows_probes_and_replays_after_budget_expiry(tmp_path, monkeypatch):
    backup(monkeypatch)
    clock = [1000.0]
    monkeypatch.setattr("livecut.text_resilience.time.time", lambda: clock[0])
    context = backup_context(tmp_path)
    ledger = Ledger(limit=8)
    policy = TextRetryPolicy(attempt_timeout_seconds=1, total_timeout_seconds=3,
                             backoff_seconds=0)

    def window(name):
        return {**BODY, "messages": [{"role": "user", "content": name}]}

    with local_platforms() as (servers, state):
        def client_for(current_context):
            budget = BudgetTransport(ledger, current_context, inner=LoopbackTransport(servers))
            return httpx.Client(transport=ResilientTextTransport(budget, current_context, policy=policy))

        with client_for(context) as first:
            recorded = first.post(URL, json=window("first-window")).json()
        assert [name for name, _ in state["hits"]] == ["primary", "primary", "backup"]

        restarted = replace(context, directory=tmp_path / "new-attempt",
                            lease=replace(context.lease, attempt=context.lease.attempt + 1))
        with client_for(restarted) as active:
            assert active.post(URL, json=window("second-window")).status_code == 200
            assert [name for name, _ in state["hits"]] == ["primary", "primary", "backup", "backup"]
            assert active.post(URL, json=window("first-window")).json() == recorded
            assert len(state["hits"]) == len(ledger.calls) == 4

            clock[0] += 61  # Production default cooldown, without a wall-clock sleep.
            assert active.post(URL, json=window("failed-probe")).status_code == 200
            assert [name for name, _ in state["hits"]][-2:] == ["primary", "backup"]
            assert len(state["hits"]) == len(ledger.calls) == 6

            clock[0] += 61
            state["primary_available"] = True
            assert active.post(URL, json=window("recovered-probe")).status_code == 200
            assert active.post(URL, json=window("healthy-primary")).status_code == 200
            assert [name for name, _ in state["hits"]][-2:] == ["primary", "primary"]
            assert len(state["hits"]) == len(ledger.calls) == 8

            clock[0] += 300
            def no_new_reservations(*args):
                raise AssertionError("Expired recovery must use lookup, even when the budget is full")
            ledger.reserve_call = no_new_reservations
            assert active.post(URL, json=window("first-window")).json() == recorded
            assert len(state["hits"]) == len(ledger.calls) == 8

        assert {call["kind"] for call in ledger.calls.values()} == {"TEXT"}
        assert all(call["status"] in {"UNKNOWN_OUTCOME", "SUCCEEDED"}
                   for call in ledger.calls.values())
