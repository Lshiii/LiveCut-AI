"""Real loopback sockets verify control-plane deadlines; no Java or providers are used."""

import json
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from livecut.worker_client import WorkerClient, WorkerClientError

SESSION = "11111111-1111-4111-8111-111111111111"


@contextmanager
def loopback_control_plane(mode):
    release, entered, both = (threading.Event() for _ in range(3))
    condition = threading.Condition()
    state = {"hits": [], "finished": 0}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            assert self.path.startswith("/internal/stages/ready")
            self.respond({"items": []})

        def do_POST(self):
            assert self.path == "/internal/workers/heartbeat"
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            self.respond({"outcome": "ACCEPTED", "worker_id": request["worker_id"],
                          "session_id": request["session_id"], "heartbeat_timeout_seconds": 35,
                          "last_seen_at": datetime.now(timezone.utc).isoformat()})

        def respond(self, response):
            with condition:
                state["hits"].append(self.path)
                entered.set()
                if len(state["hits"]) == 2:
                    both.set()
            payload = json.dumps(response).encode()
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload) if mode == "silent" else 1_000_000))
                self.end_headers()
                if mode == "silent":
                    release.wait(3)
                    self.wfile.write(payload)
                    self.wfile.flush()
                else:
                    # Bytes arrive much faster than the idle timeout. Only a
                    # cumulative deadline or explicit guard can end this body.
                    while not release.wait(.02):
                        self.wfile.write(b" ")
                        self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                # The tested client deliberately closes incomplete responses.
                pass
            finally:
                with condition:
                    state["finished"] += 1
                    condition.notify_all()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever,
                              kwargs={"poll_interval": .02}, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", state, release, entered, both
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join(2)
        assert not thread.is_alive()
        with condition:
            assert condition.wait_for(lambda: state["finished"] == len(state["hits"]), timeout=2)


def call_route(client, route):
    if route == "ready":
        return client.ready(stage_type="ASR", limit=1)
    return client.heartbeat_worker("socket-worker", SESSION, timeout_seconds=2)


@pytest.mark.parametrize("route", ["ready", "heartbeat"])
@pytest.mark.parametrize("mode", ["silent", "trickle"])
def test_control_plane_socket_body_obeys_cumulative_deadline(route, mode):
    with loopback_control_plane(mode) as (url, state, release, _, _):
        client = WorkerClient(url, "synthetic-loopback-key", timeout_seconds=2)
        try:
            started = time.monotonic()
            with client.request_scope(deadline_at=started + .18):
                with pytest.raises(WorkerClientError, match="^JAVA_UNAVAILABLE$") as caught:
                    call_route(client, route)
            elapsed = time.monotonic() - started
            assert .12 <= elapsed < 1
            assert caught.value.retryable
            assert len(state["hits"]) == 1
        finally:
            release.set()
            client.close()


@pytest.mark.parametrize("route", ["ready", "heartbeat"])
@pytest.mark.parametrize("mode", ["silent", "trickle"])
def test_operator_stop_interrupts_control_plane_body_without_waiting_for_idle_timeout(route, mode):
    with loopback_control_plane(mode) as (url, state, release, entered, _):
        client = WorkerClient(url, "synthetic-loopback-key", timeout_seconds=2)
        stop, errors = threading.Event(), []

        def guard():
            if stop.is_set():
                raise WorkerClientError("WORKER_STOPPED")

        def request():
            try:
                with client.request_scope(deadline_at=time.monotonic() + 3, request_guard=guard):
                    call_route(client, route)
            except Exception as error:
                errors.append(error)

        thread = threading.Thread(target=request, daemon=True)
        thread.start()
        try:
            assert entered.wait(2)
            started = time.monotonic()
            stop.set()
            thread.join(1)
            assert not thread.is_alive()
            assert time.monotonic() - started < 1
            assert len(errors) == 1 and isinstance(errors[0], WorkerClientError)
            assert errors[0].code == "WORKER_STOPPED" and not errors[0].retryable
            assert len(state["hits"]) == 1
        finally:
            stop.set()
            release.set()
            thread.join(2)
            client.close()
            assert not thread.is_alive()


def test_control_plane_pool_wait_cannot_outlive_third_requests_small_remaining_window():
    with loopback_control_plane("silent") as (url, state, release, _, both):
        client = WorkerClient(url, "synthetic-loopback-key", timeout_seconds=2)
        stop = threading.Event()

        def guard():
            if stop.is_set():
                raise WorkerClientError("WORKER_STOPPED")

        def occupy(route):
            try:
                with client.request_scope(deadline_at=time.monotonic() + 3, request_guard=guard):
                    call_route(client, route)
            except WorkerClientError:
                pass

        threads = [threading.Thread(target=occupy, args=(route,), daemon=True)
                   for route in ("ready", "heartbeat")]
        for thread in threads:
            thread.start()
        try:
            assert both.wait(2), "Both real control-plane connection slots must be occupied"
            assert all(thread.is_alive() for thread in threads)
            started = time.monotonic()
            with client.request_scope(deadline_at=started + .18):
                with pytest.raises(WorkerClientError, match="^JAVA_UNAVAILABLE$") as caught:
                    client.ready(stage_type="ASR", limit=1)
            assert .12 <= time.monotonic() - started < 1
            assert caught.value.retryable
            assert len(state["hits"]) == 2, "A timed-out pool waiter must never reach the server"
        finally:
            stop.set()
            release.set()
            for thread in threads:
                thread.join(2)
            client.close()
            assert all(not thread.is_alive() for thread in threads)
