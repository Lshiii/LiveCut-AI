"""Black-box startup contracts through the real HTTP client, without providers."""

import json
import threading
from datetime import datetime, timezone
from uuid import UUID

import httpx
import pytest
from livecut.worker import StageWorker, WorkerError
from livecut.worker_client import WorkerClient, WorkerClientError


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class ClockStop:
    """Advance a synthetic clock only when the worker requests a cancellable wait."""

    def __init__(self, clock):
        self.clock = clock
        self.event = threading.Event()
        self.waits = []
        self.on_wait = None

    def is_set(self):
        return self.event.is_set()

    def set(self):
        self.event.set()

    def wait(self, timeout=None):
        assert timeout is not None and 0 < timeout <= 1
        self.waits.append(timeout)
        if self.on_wait is not None:
            self.on_wait()
        self.clock.now += timeout
        return self.is_set()


class RegistrationServer:
    def __init__(self, clock, *, conflicts=0, failure=None, ttl=35, delay=0):
        self.clock = clock
        self.conflicts = conflicts
        self.failure = failure
        self.ttl = ttl
        self.delay = delay
        self.requests = []
        self.successful_session = None
        self.registration_starts = []

    def __call__(self, request):
        payload = json.loads(request.content) if request.content else None
        self.requests.append((request, payload))
        path = request.url.path
        if path == '/internal/workers/register':
            self.registration_starts.append(self.clock.now)
            self.clock.now += self.delay
            if self.failure is not None:
                code, status = self.failure
                return httpx.Response(status, json={'error_code': code})
            if self.conflicts:
                self.conflicts -= 1
                return httpx.Response(409, json={'error_code': 'WORKER_SESSION_CONFLICT'})
            self.successful_session = payload['session_id']
            return self.receipt(payload, 'REGISTERED')
        if path == '/internal/stages/ready':
            assert self.successful_session is not None, 'polling before registration'
            return httpx.Response(200, json={'items': []})
        if path == '/internal/workers/unregister':
            assert payload['session_id'] == self.successful_session
            return self.receipt(payload, 'UNREGISTERED')
        pytest.fail(f'unexpected startup operation: {path}')

    def receipt(self, payload, outcome):
        return httpx.Response(200, json={
            'worker_id': payload['worker_id'], 'session_id': payload['session_id'],
            'outcome': outcome, 'heartbeat_timeout_seconds': self.ttl,
            'last_seen_at': datetime.now(timezone.utc).isoformat(),
        })

    @property
    def registrations(self):
        return [(request, payload) for request, payload in self.requests
                if request.url.path == '/internal/workers/register']


def make_worker(tmp_path, server, stop, *, wait=45, **kwargs):
    client = WorkerClient('http://localhost:8080', 'private-startup-key', timeout_seconds=.8,
                          transport=httpx.MockTransport(server))

    def forbid_computation(context):
        pytest.fail('startup must not invoke an executor without a claimed stage')

    runner = StageWorker(client, tmp_path / 'storage', tmp_path / 'journal',
                         'startup-worker', forbid_computation, stop=stop,
                         registration_wait_seconds=wait, **kwargs)
    runner._monotonic = server.clock
    return runner, client


def test_retry_preserves_identity_and_caps_actual_http_timeout_by_remaining_budget(tmp_path):
    clock = Clock()
    server = RegistrationServer(clock, conflicts=2, delay=.25, ttl=1)
    stop = ClockStop(clock)
    runner, client = make_worker(tmp_path, server, stop, wait=3)
    try:
        assert runner.run_once(stage_type='ASR') == 'idle'
    finally:
        client.close()

    registrations = server.registrations
    assert len(registrations) == 3
    identities = {payload['session_id'] for _, payload in registrations}
    assert len(identities) == 1
    assert str(UUID(next(iter(identities)))) == next(iter(identities))
    for (request, payload), started in zip(registrations, server.registration_starts):
        assert payload == {'worker_id': 'startup-worker', 'session_id': next(iter(identities)),
                           'stage_types': ['ASR'], 'slots': 1}
        assert all(0 < value <= min(.8, 3 - started)
                   for value in request.extensions['timeout'].values())
    assert [request.url.path for request, _ in server.requests] == [
        '/internal/workers/register', '/internal/workers/register',
        '/internal/workers/register', '/internal/stages/ready', '/internal/workers/unregister']
    assert stop.waits == [1, 1]
    # TTL=1 is already past the *first* request start, but still valid from the successful retry.
    assert clock.now > server.registration_starts[0] + server.ttl


@pytest.mark.parametrize('code,status', [
    ('JAVA_UNAVAILABLE', 503), ('WORKER_UNAUTHORIZED', 401),
    ('WORKER_FORBIDDEN', 403), ('WORKER_REGISTRATION_CONFLICT', 409),
    ('WORKER_SESSION_EXPIRED', 409), ('STALE_ATTEMPT', 409),
])
def test_only_the_exact_session_conflict_can_retry_startup(tmp_path, code, status):
    clock = Clock()
    server = RegistrationServer(clock, failure=(code, status))
    stop = ClockStop(clock)
    runner, client = make_worker(tmp_path, server, stop)
    try:
        with pytest.raises(WorkerClientError, match=f'^{code}$'):
            runner.run_once(stage_type='ASR')
    finally:
        client.close()
    assert len(server.requests) == 1
    assert not stop.waits


def test_zero_budget_retains_one_immediate_attempt_and_original_error(tmp_path):
    clock = Clock()
    server = RegistrationServer(clock, conflicts=10)
    stop = ClockStop(clock)
    runner, client = make_worker(tmp_path, server, stop, wait=0)
    try:
        with pytest.raises(WorkerClientError, match='^WORKER_SESSION_CONFLICT$'):
            runner.run_once(stage_type='ASR')
    finally:
        client.close()
    assert len(server.requests) == 1
    assert not stop.waits
    assert all(value == .8 for value in server.requests[0][0].extensions['timeout'].values())


def test_exhaustion_never_polls_or_unregisters_and_releases_the_local_journal(tmp_path):
    clock = Clock()
    server = RegistrationServer(clock, conflicts=100, delay=.1)
    stop = ClockStop(clock)
    runner, client = make_worker(tmp_path, server, stop, wait=2.2)
    try:
        with pytest.raises(WorkerClientError, match='^WORKER_SESSION_CONFLICT$'):
            runner.run_once(stage_type='ASR')
        assert clock.now == pytest.approx(2.2)
        assert len(server.requests) == 2
        # An unrelated process with the same journal must be able to start once this owner exits.
        server.conflicts = 0
        second, second_client = make_worker(tmp_path, server, ClockStop(clock), wait=0)
        try:
            assert second.run_once(stage_type='ASR') == 'idle'
        finally:
            second_client.close()
    finally:
        client.close()


def test_stop_interrupts_registration_wait_without_touching_the_previous_owner(tmp_path):
    clock = Clock()
    server = RegistrationServer(clock, conflicts=100)
    stop = ClockStop(clock)
    stop.on_wait = stop.set
    runner, client = make_worker(tmp_path, server, stop)
    try:
        assert runner.run_once(stage_type='ASR') == 'stopped'
    finally:
        client.close()
    assert len(server.requests) == 1
    assert stop.waits == [1]


def test_conflict_wait_holds_the_journal_before_any_registration_succeeds(tmp_path):
    clock = Clock()
    server = RegistrationServer(clock, conflicts=1)
    stop = ClockStop(clock)
    first, first_client = make_worker(tmp_path, server, stop)
    second, second_client = make_worker(tmp_path, server, ClockStop(clock), wait=0)

    def attempt_second_start():
        with pytest.raises(WorkerError, match='^WORKER_BUSY$'):
            second.run_once(stage_type='ASR')

    stop.on_wait = attempt_second_start
    try:
        assert first.run_once(stage_type='ASR') == 'idle'
    finally:
        first_client.close()
        second_client.close()
    assert len(server.registrations) == 2


def test_lifecycle_events_are_safe_and_ordered_before_polling(tmp_path):
    clock = Clock()
    server = RegistrationServer(clock, conflicts=1)
    stop = ClockStop(clock)
    observations = []

    def observe(event):
        paths_so_far = [request.url.path for request, _ in server.requests]
        assert '/internal/stages/ready' not in paths_so_far
        observations.append(dict(event))

    runner, client = make_worker(tmp_path, server, stop, lifecycle_observer=observe)
    try:
        assert runner.run_once(stage_type='ASR') == 'idle'
    finally:
        client.close()
    assert [event['status'] for event in observations] == ['registration-wait', 'registered']
    assert observations[0] == {'status': 'registration-wait', 'worker_id': 'startup-worker',
                               'elapsed_seconds': 0, 'remaining_seconds': 45}
    assert observations[1] == {'status': 'registered', 'worker_id': 'startup-worker'}
    serialized = json.dumps(observations)
    assert server.successful_session not in serialized
    assert all(secret not in serialized for secret in ('private-startup-key', 'localhost', '8080'))


def test_late_success_cannot_start_work_or_emit_registered_after_receipt_ttl(tmp_path):
    clock = Clock()
    server = RegistrationServer(clock, ttl=1, delay=2)
    stop = ClockStop(clock)
    observations = []
    runner, client = make_worker(tmp_path, server, stop, lifecycle_observer=observations.append)
    try:
        with pytest.raises(WorkerClientError, match='^WORKER_SESSION_EXPIRED$'):
            runner.run_once(stage_type='ASR')
    finally:
        client.close()
    assert [request.url.path for request, _ in server.requests] == [
        '/internal/workers/register', '/internal/workers/unregister']
    assert not observations
    assert stop.is_set()
