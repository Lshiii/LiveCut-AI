"""Bounded startup waiting never replaces another executor or dispatches work early."""

import json
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest
from livecut.text_resilience import DeadlineHTTPTransport, TextRecoveryError
from livecut.worker import StageWorker, WorkerError
from livecut.worker_client import WorkerClient, WorkerClientError


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class AdvancingStop:
    def __init__(self, clock, *, cancel_on_wait=False):
        self.clock = clock
        self.cancel_on_wait = cancel_on_wait
        self.stopped = False
        self.waits = []

    def is_set(self):
        return self.stopped

    def set(self):
        self.stopped = True

    def wait(self, seconds):
        if self.stopped:
            return True
        self.waits.append(seconds)
        if self.cancel_on_wait:
            self.set()
        else:
            self.clock.now += seconds
        return self.stopped


class RegistrationClient:
    def __init__(self, clock, *, conflicts=0, request_seconds=0, ttl=35):
        self.clock, self.conflicts = clock, conflicts
        self.request_seconds, self.ttl = request_seconds, ttl
        self.registrations, self.unregistrations, self.ready_calls = [], [], []
        self.error = None
        self.on_ready = None

    def register_worker(self, worker_id, session_id, capabilities, **kwargs):
        self.registrations.append((worker_id, session_id, capabilities, kwargs))
        self.clock.now += self.request_seconds
        if self.error is not None:
            raise self.error
        if self.conflicts:
            self.conflicts -= 1
            raise WorkerClientError('WORKER_SESSION_CONFLICT')
        return {'outcome': 'REGISTERED', 'worker_id': worker_id, 'session_id': session_id,
                'heartbeat_timeout_seconds': self.ttl,
                'last_seen_at': datetime.now(timezone.utc).isoformat()}

    def unregister_worker(self, worker_id, session_id):
        self.unregistrations.append((worker_id, session_id))

    def ready(self, **kwargs):
        self.ready_calls.append(kwargs)
        if self.on_ready is not None:
            self.on_ready()
        return {'items': []}

    def claim(self, *args, **kwargs):
        pytest.fail('an empty startup fixture must never claim work')


def runner(tmp_path, client, clock, *, events=None, stop=None, **kwargs):
    def never_compute(context):
        pytest.fail('registration waiting must never execute a stage')

    value = StageWorker(client, tmp_path / 'storage', tmp_path / 'journal', 'worker-1',
                        never_compute, stop=stop or AdvancingStop(clock),
                        lifecycle_observer=events.append if events is not None else None, **kwargs)
    value._monotonic = clock
    return value


@pytest.mark.parametrize('invalid', [-1, 3600.1, float('nan'), float('inf'),
                                     float('-inf'), True, False, '45', None])
def test_registration_wait_rejects_invalid_configuration(tmp_path, invalid):
    clock = Clock()
    with pytest.raises(WorkerError, match='^INVALID_REGISTRATION_WAIT_CONFIGURATION$'):
        runner(tmp_path, RegistrationClient(clock), clock, registration_wait_seconds=invalid)


@pytest.mark.parametrize('seconds', [0, .25, 45, 3600])
def test_registration_wait_accepts_finite_bounded_configuration(tmp_path, seconds):
    clock = Clock()
    worker = runner(tmp_path, RegistrationClient(clock), clock, registration_wait_seconds=seconds)
    assert worker.registration_wait_seconds == seconds


@pytest.mark.parametrize('continuous', [False, True])
def test_retry_uses_one_session_and_only_dispatches_after_registered_receipt(tmp_path, continuous):
    clock, events = Clock(), []
    client = RegistrationClient(clock, conflicts=2)
    worker = runner(tmp_path, client, clock, events=events)
    if continuous:
        client.on_ready = worker.stop.set
        worker.run(stage_type='TIMELINE')
    else:
        assert worker.run_once(stage_type='TIMELINE') == 'idle'
    sessions = [entry[1] for entry in client.registrations]
    assert len(sessions) == 3 and len(set(sessions)) == 1
    assert [row[3]['timeout_seconds'] for row in client.registrations] == [45, 44, 43]
    assert events == [
        {'status': 'registration-wait', 'worker_id': 'worker-1',
         'elapsed_seconds': 0, 'remaining_seconds': 45},
        {'status': 'registration-wait', 'worker_id': 'worker-1',
         'elapsed_seconds': 1, 'remaining_seconds': 44},
        {'status': 'registered', 'worker_id': 'worker-1'},
    ]
    assert len(client.ready_calls) == 1
    assert client.unregistrations == [('worker-1', sessions[0])]
    assert worker.session_id is None


def test_conflict_budget_ends_without_unregistering_an_owner_and_releases_journal(tmp_path):
    clock, events = Clock(), []
    client = RegistrationClient(clock, conflicts=100)
    worker = runner(tmp_path, client, clock, events=events, registration_wait_seconds=2.5)
    with pytest.raises(WorkerClientError, match='^WORKER_SESSION_CONFLICT$'):
        worker.run_once()
    assert clock.now == 2.5
    assert worker.stop.waits == [1, 1, .5]
    assert [row[3]['timeout_seconds'] for row in client.registrations] == [2.5, 1.5, .5]
    assert not client.unregistrations and not client.ready_calls and worker.session_id is None
    replacement_client = RegistrationClient(clock)
    assert runner(tmp_path, replacement_client, clock).run_once(stage_type='TIMELINE') == 'idle'
    assert len(replacement_client.registrations) == 1


def test_zero_budget_preserves_one_attempt_without_waiting(tmp_path):
    clock = Clock()
    client = RegistrationClient(clock, conflicts=1)
    worker = runner(tmp_path, client, clock, registration_wait_seconds=0)
    with pytest.raises(WorkerClientError, match='^WORKER_SESSION_CONFLICT$'):
        worker.run_once()
    assert len(client.registrations) == 1
    assert 'timeout_seconds' not in client.registrations[0][3]
    assert not worker.stop.waits and not client.unregistrations and not client.ready_calls


@pytest.mark.parametrize('continuous', [False, True])
def test_cancel_during_wait_returns_promptly_without_registering_or_unregistering(tmp_path, continuous):
    clock = Clock()
    stop = AdvancingStop(clock, cancel_on_wait=True)
    client = RegistrationClient(clock, conflicts=100)
    worker = runner(tmp_path, client, clock, stop=stop)
    if continuous:
        assert worker.run() is None
    else:
        assert worker.run_once() == 'stopped'
    assert len(client.registrations) == 1 and stop.waits == [1]
    assert not client.ready_calls and not client.unregistrations
    assert worker.session_id is None


def test_waiting_holds_journal_lock_before_advertising_or_polling(tmp_path):
    clock = Clock()
    client = RegistrationClient(clock, conflicts=1)
    second_client = RegistrationClient(clock)
    second = runner(tmp_path, second_client, clock)
    events = []

    def observe(event):
        events.append(event)
        if event['status'] == 'registration-wait':
            with pytest.raises(WorkerError, match='^WORKER_BUSY$'):
                second.run_once()
            assert not client.ready_calls and not second_client.registrations

    worker = runner(tmp_path, client, clock)
    worker._lifecycle_observer = observe
    assert worker.run_once(stage_type='TIMELINE') == 'idle'
    assert [event['status'] for event in events] == ['registration-wait', 'registered']


@pytest.mark.parametrize('code', ['WORKER_UNAUTHORIZED', 'WORKER_REGISTRATION_CONFLICT',
                                  'WORKER_SESSION_EXPIRED', 'INVALID_WORKER_RESPONSE',
                                  'JAVA_UNAVAILABLE', 'STALE_ATTEMPT'])
def test_only_exact_session_conflicts_are_retried(tmp_path, code):
    clock, events = Clock(), []
    client = RegistrationClient(clock)
    client.error = WorkerClientError(code, retryable=True)
    worker = runner(tmp_path, client, clock, events=events)
    with pytest.raises(WorkerClientError, match='^' + code + '$') as captured:
        worker.run_once()
    assert captured.value is client.error
    assert len(client.registrations) == 1
    assert not worker.stop.waits and not events and not client.unregistrations


def test_presence_deadline_uses_the_successful_request_start_after_long_conflict_wait(tmp_path):
    clock, events = Clock(), []
    client = RegistrationClient(clock, conflicts=1, request_seconds=2, ttl=5)
    worker = runner(tmp_path, client, clock, events=events)

    def inspect(stage_type=None):
        worker._check_presence()
        assert clock.now == 5
        assert worker._presence_deadline == 8
        return 'idle'

    worker._run_once = inspect
    assert worker.run_once() == 'idle'
    assert [event['status'] for event in events] == ['registration-wait', 'registered']


def test_expired_registration_receipt_never_emits_registered_or_starts_work(tmp_path):
    clock, events = Clock(), []
    client = RegistrationClient(clock, request_seconds=6, ttl=5)
    worker = runner(tmp_path, client, clock, events=events)
    with pytest.raises(WorkerClientError, match='^WORKER_SESSION_EXPIRED$'):
        worker.run_once()
    assert not events and not client.ready_calls
    assert len(client.unregistrations) == 1


@pytest.mark.parametrize(('requested', 'client_limit'), [(2.5, 10), (45, 3)])
@pytest.mark.parametrize('preparation_seconds', [0, .125])
def test_registration_http_timeout_is_capped_by_budget_and_client_limit(
        monkeypatch, requested, client_limit, preparation_seconds):
    requests = []
    session = str(uuid4())
    clock = Clock()

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={
            'outcome': 'REGISTERED', 'worker_id': 'worker-1', 'session_id': session,
            'heartbeat_timeout_seconds': 35, 'last_seen_at': '2026-11-06T00:00:00Z'})

    client = WorkerClient('http://127.0.0.1:8080', 'private-key', timeout_seconds=client_limit,
                          transport=httpx.MockTransport(handle))
    # Replace only this module's clock, leaving HTTPX/thread scheduling clocks
    # untouched. Preparation occurs after register fixed its absolute deadline
    # and before the real _request calculates all HTTP phase timeouts.
    monkeypatch.setattr('livecut.worker_client.time', SimpleNamespace(monotonic=clock))
    actual_request = client._request

    def prepare_request(*args, **kwargs):
        clock.now += preparation_seconds
        return actual_request(*args, **kwargs)

    monkeypatch.setattr(client, '_request', prepare_request)
    started = clock.now
    try:
        client.register_worker('worker-1', session, ['TIMELINE'], timeout_seconds=requested)
    finally:
        client.close()
    cap = min(requested, client_limit)
    elapsed = clock.now - started
    assert elapsed == preparation_seconds
    assert requests[0].extensions['livecut_deadline_at'] == started + cap
    assert requests[0].extensions['timeout'] == {
        phase: cap - elapsed for phase in ('connect', 'read', 'write', 'pool')}
    assert all(0 < timeout <= cap for timeout in requests[0].extensions['timeout'].values())


@pytest.mark.parametrize('invalid', [0, -1, float('nan'), float('inf'), True, '1'])
def test_registration_http_timeout_rejects_invalid_values_without_sending(invalid):
    def reject(request):
        pytest.fail('an invalid timeout must fail before HTTP dispatch')

    client = WorkerClient('http://127.0.0.1:8080', 'private-key',
                          transport=httpx.MockTransport(reject))
    try:
        with pytest.raises(WorkerClientError, match='^INVALID_WORKER_REGISTRATION_TIMEOUT$'):
            client.register_worker('worker-1', str(uuid4()), ['TIMELINE'], timeout_seconds=invalid)
    finally:
        client.close()


@contextmanager
def slow_registration_server(mode, *, cancel=None):
    """A real local response that detects client disconnect without paid APIs."""
    disconnected, release = threading.Event(), threading.Event()
    paths, timers = [], []

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def do_POST(self):
            self.rfile.read(int(self.headers.get('Content-Length', 0)))
            paths.append(self.path)
            self.send_response(200)
            if mode == 'drip':
                self.send_header('Transfer-Encoding', 'chunked')
            else:
                self.send_header('Content-Length', '1024')
            self.end_headers()
            self.wfile.flush()
            if cancel is not None:
                timer = threading.Timer(.05, cancel)
                timers.append(timer)
                timer.start()
            try:
                if mode == 'drip':
                    while not release.is_set():
                        self.wfile.write(b'1\r\n \r\n')
                        self.wfile.flush()
                        release.wait(.01)
                else:
                    # No body bytes arrive. The server observes EOF when the
                    # caller's synchronous deadline/guard closes the socket.
                    self.connection.settimeout(1.5)
                    if self.connection.recv(1) == b'':
                        disconnected.set()
            except (ConnectionError, OSError):
                disconnected.set()

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}', disconnected, paths
    finally:
        release.set()
        for timer in timers:
            timer.cancel()
            timer.join(1)
        server.shutdown()
        server.server_close()
        thread.join(1)


@pytest.mark.parametrize(('mode', 'budget'), [('drip', .15), ('silent', .3)])
def test_real_registration_wall_deadline_closes_slow_response(tmp_path, mode, budget):
    events = []
    with slow_registration_server(mode) as (url, disconnected, paths):
        client = WorkerClient(url, 'private-key', timeout_seconds=2)
        worker = StageWorker(client, tmp_path / 'storage', tmp_path / 'journal', 'worker-1',
                             lambda context: pytest.fail('no computation during registration'),
                             registration_wait_seconds=budget, lifecycle_observer=events.append)
        try:
            started = time.monotonic()
            with pytest.raises(WorkerClientError, match='^JAVA_UNAVAILABLE$'):
                worker.run_once(stage_type='TIMELINE')
            elapsed = time.monotonic() - started
            assert budget * .7 <= elapsed < .8
            assert disconnected.wait(.7), 'the expired synchronous request must close its socket'
            assert paths == ['/internal/workers/register']
            assert not events and worker.session_id is None
        finally:
            client.close()


def test_real_registration_stop_guard_interrupts_silent_read_and_closes_socket(tmp_path):
    stop, events, cancelled_at = threading.Event(), [], []

    def cancel():
        cancelled_at.append(time.monotonic())
        stop.set()

    with slow_registration_server('silent', cancel=cancel) as (url, disconnected, paths):
        client = WorkerClient(url, 'private-key', timeout_seconds=5)
        worker = StageWorker(client, tmp_path / 'storage', tmp_path / 'journal', 'worker-1',
                             lambda context: pytest.fail('no computation after cancellation'),
                             stop=stop, registration_wait_seconds=5, lifecycle_observer=events.append)
        try:
            assert worker.run_once(stage_type='TIMELINE') == 'stopped'
            assert cancelled_at and time.monotonic() - cancelled_at[0] < .7
            assert disconnected.wait(.7), 'cancellation must not leave a background request'
            assert paths == ['/internal/workers/register']
            assert not events and worker.session_id is None
        finally:
            client.close()


def test_all_control_plane_requests_have_bounded_response_and_pool_waits():
    requests = []
    session = str(uuid4())

    def handle(request):
        requests.append(request)
        if request.url.path.endswith('/register'):
            payload = json.loads(request.content)
            return httpx.Response(200, json={**payload, 'outcome': 'REGISTERED',
                'heartbeat_timeout_seconds': 35, 'last_seen_at': '2026-11-06T00:00:00Z'})
        return httpx.Response(200, json={'items': []})

    client = WorkerClient('http://127.0.0.1:8080', 'private-key', timeout_seconds=2,
                          transport=httpx.MockTransport(handle))
    try:
        started = time.monotonic()
        client.register_worker('worker-1', session, ['TIMELINE'])
        client.ready(stage_type='TIMELINE')
    finally:
        client.close()
    assert started + 1.5 < requests[0].extensions['livecut_deadline_at'] <= time.monotonic() + 2
    assert started + 1.5 < requests[1].extensions['livecut_deadline_at'] <= time.monotonic() + 2
    assert callable(requests[1].extensions['livecut_guard'])
    assert 0 < requests[1].extensions['timeout']['pool'] <= 2


def test_default_registration_transport_keeps_verified_tls_and_ignores_env(monkeypatch):
    monkeypatch.setenv('SSL_CERT_FILE', 'nonexistent-worker-certificate.pem')
    monkeypatch.setenv('SSL_CERT_DIR', 'nonexistent-worker-certificate-dir')
    monkeypatch.setenv('HTTPS_PROXY', 'http://invalid-proxy.example:1')
    client = WorkerClient('https://worker.example', 'private-key')
    try:
        assert isinstance(client._client._transport, DeadlineHTTPTransport)
        context = client._client._transport.pool._ssl_context
        assert context.check_hostname
        import ssl
        assert context.verify_mode == ssl.CERT_REQUIRED
    finally:
        client.close()


def test_registration_oversize_response_is_mapped_to_sanitized_java_error():
    def handle(request):
        raise TextRecoveryError('MODEL_RESPONSE_TOO_LARGE')

    client = WorkerClient('http://127.0.0.1:8080', 'private-key',
                          transport=httpx.MockTransport(handle))
    try:
        with pytest.raises(WorkerClientError, match='^INVALID_JAVA_RESPONSE$') as error:
            client.register_worker('worker-1', str(uuid4()), ['TIMELINE'])
        assert error.value.retryable
        assert 'MODEL' not in str(error.value)
    finally:
        client.close()
