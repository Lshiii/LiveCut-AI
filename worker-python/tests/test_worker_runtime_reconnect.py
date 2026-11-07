"""Runtime recovery tests use local protocol fakes, never provider requests."""

import json
import threading
import time
from uuid import uuid4

import httpx
import pytest
from livecut.worker import Lease, LeaseGuard, StageWorker, WorkerError, _write
from livecut.worker_client import WorkerClient, WorkerClientError
from test_worker import claim, fake_executor
from test_worker_presence import PresenceClient


def runner(tmp_path, client, *, events=None, calls=None, **kwargs):
    return StageWorker(client, tmp_path / 'storage', tmp_path / 'journal', 'worker-1',
                       fake_executor([] if calls is None else calls),
                       lifecycle_observer=None if events is None else events.append, **kwargs)


def start(worker, *, poll=.005, stage='MEDIA'):
    errors = []

    def run():
        try:
            worker.run(poll_seconds=poll, stage_type=stage)
        except Exception as exc:
            errors.append(exc)

    thread = threading.Thread(target=run)
    thread.start()
    return thread, errors


def wait_for(predicate, timeout=2):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.002)
    assert predicate()


@pytest.mark.parametrize('value', [-1, 3600.1, float('nan'), float('inf'), float('-inf'),
                                   True, False, '180', None])
def test_reconnect_configuration_is_finite_bounded_and_typed(tmp_path, value):
    with pytest.raises(WorkerError, match='^INVALID_RECONNECT_WAIT_CONFIGURATION$'):
        runner(tmp_path, PresenceClient(idle=True), reconnect_wait_seconds=value)


@pytest.mark.parametrize('value', [0, .25, 180, 3600])
def test_valid_reconnect_configuration(tmp_path, value):
    assert runner(tmp_path, PresenceClient(), reconnect_wait_seconds=value).reconnect_wait_seconds == value


def test_idle_ready_outage_recovers_with_real_heartbeat_before_old_window_and_keeps_lock(tmp_path):
    client, events = PresenceClient(idle=True), []
    original = client.ready
    attempts = 0

    def ready(**kwargs):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise WorkerClientError('JAVA_UNAVAILABLE', retryable=True)
        return original(**kwargs)

    client.ready = ready
    worker = runner(tmp_path, client, events=events, reconnect_wait_seconds=.35,
                    worker_heartbeat_seconds=10)
    thread, errors = start(worker)
    try:
        wait_for(lambda: any(event['status'] == 'recovered' for event in events))
        with pytest.raises(WorkerError, match='WORKER_BUSY'):
            runner(tmp_path, PresenceClient(idle=True)).run_once(stage_type='MEDIA')
        # Healthy requests after the obsolete recovery deadline remain valid.
        time.sleep(.4)
        assert thread.is_alive() and not errors
        assert len(client.registrations) == 1 and client.presence_heartbeats
        assert len([event for event in events if event['status'] == 'recovered']) == 1
        assert worker._runtime_deadline is None
        assert all(set(event) <= {'status', 'worker_id', 'error_code', 'elapsed_seconds',
                                  'remaining_seconds'} for event in events)
    finally:
        worker.stop.set()
        thread.join(2)
    assert not thread.is_alive() and not errors


def test_permanent_ready_failure_exhausts_one_window_and_releases_lock(tmp_path):
    client, events = PresenceClient(idle=True), []

    def ready(**kwargs):
        raise WorkerClientError('JAVA_UNAVAILABLE', retryable=True)

    client.ready = ready
    worker = runner(tmp_path, client, events=events, reconnect_wait_seconds=.16)
    started = time.monotonic()
    with pytest.raises(WorkerClientError, match='^WORKER_RECONNECT_EXHAUSTED$'):
        worker.run(stage_type='MEDIA')
    assert .12 <= time.monotonic() - started < 1
    assert not worker.stop.is_set()
    assert len([event for event in events if event['status'] == 'reconnect-exhausted']) == 1
    assert not any(event['status'] == 'recovered' for event in events)
    assert runner(tmp_path, PresenceClient(idle=True)).run_once(stage_type='MEDIA') == 'idle'


@pytest.mark.parametrize('once,window', [(False, 0), (True, 180)])
def test_disabled_recovery_and_one_shot_remain_fail_fast(tmp_path, once, window):
    client, events = PresenceClient(idle=True), []
    client.ready = lambda **kwargs: (_ for _ in ()).throw(
        WorkerClientError('JAVA_UNAVAILABLE', retryable=True))
    worker = runner(tmp_path, client, events=events, reconnect_wait_seconds=window)
    with pytest.raises(WorkerClientError, match='^JAVA_UNAVAILABLE$'):
        worker.run_once(stage_type='MEDIA') if once else worker.run(stage_type='MEDIA')
    assert not any(event['status'] == 'reconnecting' for event in events)


def test_operator_stop_interrupts_backoff_without_clearing_event(tmp_path):
    client, events = PresenceClient(idle=True), []
    client.ready = lambda **kwargs: (_ for _ in ()).throw(
        WorkerClientError('JAVA_UNAVAILABLE', retryable=True))
    worker = runner(tmp_path, client, events=events)
    thread, errors = start(worker)
    wait_for(lambda: any(event['status'] == 'reconnecting' for event in events))
    worker.stop.set()
    thread.join(1)
    assert not thread.is_alive() and not errors and worker.stop.is_set()
    registrations = len(client.registrations)
    worker.run(stage_type='MEDIA')
    assert len(client.registrations) == registrations


def test_heartbeat_only_outage_cannot_reset_window_with_register_and_ready_200(tmp_path):
    client, events = PresenceClient(idle=True), []
    ready_sessions = set()
    original_ready, original_heartbeat = client.ready, client.heartbeat_worker

    def ready(**kwargs):
        ready_sessions.add(client.registrations[-1][1])
        return original_ready(**kwargs)

    def heartbeat(worker_id, session_id, **kwargs):
        # Force a successful ready for each session before its heartbeat
        # failure. Otherwise the eager heartbeat can correctly fail first and
        # the test would never exercise the false-recovery gate it targets.
        wait_for(lambda: session_id in ready_sessions)
        return original_heartbeat(worker_id, session_id, **kwargs)

    client.ready, client.heartbeat_worker = ready, heartbeat
    client.presence_failure = WorkerClientError('JAVA_UNAVAILABLE', retryable=True)
    worker = runner(tmp_path, client, events=events, worker_heartbeat_seconds=.015,
                    reconnect_wait_seconds=.23)
    started = time.monotonic()
    with pytest.raises(WorkerClientError, match='^WORKER_RECONNECT_EXHAUSTED$'):
        worker.run(stage_type='MEDIA', poll_seconds=.002)
    assert time.monotonic() - started < 1
    assert len(client.registrations) >= 2
    assert len(client.ready_calls) >= 2
    assert not any(event['status'] == 'recovered' for event in events)


def test_heartbeat_failure_interrupts_long_idle_poll_with_short_window(tmp_path):
    client = PresenceClient(idle=True)
    client.presence_failure = WorkerClientError('JAVA_UNAVAILABLE', retryable=True)
    worker = runner(tmp_path, client, worker_heartbeat_seconds=.015, reconnect_wait_seconds=.12)
    started = time.monotonic()
    with pytest.raises(WorkerClientError, match='^WORKER_RECONNECT_EXHAUSTED$'):
        worker.run(stage_type='MEDIA', poll_seconds=60)
    assert time.monotonic() - started < 1


def test_uncertain_claim_validated_replay_recovers_before_long_computation_finishes(tmp_path):
    client, events, calls = PresenceClient(), [], []
    client.fail_claim_once = True
    worker = runner(tmp_path, client, events=events, calls=calls, reconnect_wait_seconds=.24,
                    worker_heartbeat_seconds=10)

    def execute(context):
        wait_for(lambda: any(event['status'] == 'recovered' for event in events))
        # This computation crosses the old window after ready was skipped by
        # the pending-claim replay. Its original guard remains healthy.
        time.sleep(.3)
        context.guard.check()
        return fake_executor(calls)(context)

    worker.executor = execute
    client.stop_on_complete = worker.stop
    worker.run(stage_type='MEDIA', poll_seconds=.002)
    assert calls == ['stage-1']
    assert len(client.claims) == 2 and client.claims[0] == client.claims[1]
    assert len(client.registrations) == 1
    assert len(client.ready_calls) == 1
    assert worker._runtime_deadline is None
    assert [row[0] for row in client.callbacks] == ['complete']


@pytest.mark.parametrize('old_receipt_exists', [False, True])
def test_old_unknown_claim_is_archived_without_replaying_under_new_session(tmp_path, old_receipt_exists):
    old = {'stage_id': 'stage-1', 'worker_id': 'worker-1',
           'claim_request_id': '../../untrusted-old-id', 'session_id': str(uuid4()),
           'diagnostic': {'receipt_existed': old_receipt_exists}}
    _write(tmp_path / 'journal/pending-claim.json', old)
    client, calls = PresenceClient(), []
    original = client.claim

    def capture(stage_id, worker_id, claim_request_id, **kwargs):
        # Java's missing-receipt branch would create a NEW claim if sent this
        # old ID with the new session. The Worker must avoid both branches.
        assert claim_request_id != old['claim_request_id']
        assert kwargs['session_id'] != old['session_id']
        return original(stage_id, worker_id, claim_request_id, **kwargs)

    client.claim = capture
    assert runner(tmp_path, client, calls=calls).run_once(stage_type='MEDIA') == 'completed'
    archives = list((tmp_path / 'journal/session-abandoned').glob('*.json'))
    assert len(archives) == 1 and len(archives[0].stem) == 64
    assert json.loads(archives[0].read_text()) == old
    assert calls == ['stage-1']


def test_abandoned_claim_archive_failure_preserves_pending_before_any_registration(tmp_path, monkeypatch):
    pending = tmp_path / 'journal/pending-claim.json'
    _write(pending, {'stage_id': 'stage-1', 'worker_id': 'worker-1',
                     'claim_request_id': 'unknown', 'session_id': str(uuid4())})
    before = pending.read_bytes()
    client = PresenceClient(idle=True)

    def failed_archive(*args, **kwargs):
        raise OSError('synthetic durable archive failure')

    monkeypatch.setattr('livecut.worker._write', failed_archive)
    with pytest.raises(OSError):
        runner(tmp_path, client).run_once(stage_type='MEDIA')
    assert pending.read_bytes() == before and not client.registrations


def test_registration_unknown_ack_reuses_uuid_until_explicit_expiry(tmp_path):
    client, events = PresenceClient(idle=True), []
    original = client.register_worker
    sessions = []

    def register(worker_id, session_id, capabilities, **kwargs):
        sessions.append(session_id)
        if len(sessions) == 1:
            raise WorkerClientError('JAVA_UNAVAILABLE', retryable=True)
        if len(sessions) == 2:
            raise WorkerClientError('WORKER_SESSION_EXPIRED')
        return original(worker_id, session_id, capabilities, **kwargs)

    client.register_worker = register
    worker = runner(tmp_path, client, events=events, reconnect_wait_seconds=.6,
                    worker_heartbeat_seconds=10)
    thread, errors = start(worker)
    try:
        wait_for(lambda: any(event['status'] == 'recovered' for event in events))
        assert sessions[0] == sessions[1] and sessions[2] != sessions[1]
        assert client.presence_heartbeats
    finally:
        worker.stop.set()
        thread.join(2)
    assert not errors


def test_zero_registration_wait_conflict_does_not_borrow_runtime_budget(tmp_path):
    client, events = PresenceClient(idle=True), []
    client.register_worker = lambda *args, **kwargs: (_ for _ in ()).throw(
        WorkerClientError('WORKER_SESSION_CONFLICT'))
    worker = runner(tmp_path, client, events=events, registration_wait_seconds=0)
    with pytest.raises(WorkerClientError, match='^WORKER_SESSION_CONFLICT$'):
        worker.run(stage_type='MEDIA')
    assert not events


@pytest.mark.parametrize('kind', ['_presence_thread', '_stage_thread'])
def test_any_old_heartbeat_thread_still_alive_blocks_new_registration(tmp_path, kind):
    class Unjoined:
        def is_alive(self):
            return True

    client = PresenceClient(idle=True)
    worker = runner(tmp_path, client)
    setattr(worker, kind, Unjoined())
    with pytest.raises(WorkerError, match='^WORKER_HEARTBEAT_NOT_STOPPED$'):
        worker.run_once(stage_type='MEDIA')
    assert not client.registrations


def test_old_guard_and_late_heartbeat_cannot_touch_new_epoch(tmp_path):
    client, entered, release = PresenceClient(idle=True), threading.Event(), threading.Event()
    worker = runner(tmp_path, client)
    worker.session_id, worker._presence_epoch = 'old-session', 1
    worker._presence_deadline = time.monotonic() + 10
    worker._continuous = True
    guard = LeaseGuard(Lease.parse(claim()), worker.stop,
                       presence_check=lambda: worker._check_presence(1))

    def heartbeat(*args, **kwargs):
        entered.set()
        assert release.wait(1)
        return {'heartbeat_timeout_seconds': 35}

    client.heartbeat_worker = heartbeat
    finished = threading.Event()
    thread = threading.Thread(target=worker._worker_heartbeat,
                              args=(finished, .001, 'old-session', 1))
    thread.start()
    assert entered.wait(1)
    with worker._presence_lock:
        worker._presence_epoch = 2
        worker.session_id = 'new-session'
        worker._presence_deadline = expected = time.monotonic() + 11
        worker._presence_error = None
    release.set()
    thread.join(1)
    assert not thread.is_alive()
    assert worker._presence_deadline == expected and worker._presence_error is None
    assert worker._presence_renewals == 0
    with pytest.raises(WorkerError, match='WORKER_PRESENCE_LOST'):
        guard.check()


def pending_callback(tmp_path):
    value = {'action': 'complete', 'stage_id': 'stage-1',
             'payload': {'attempt': 1, 'run_token': 'old-token', 'manifest_hash': 'a' * 64}}
    path = tmp_path / 'journal/pending-callback.json'
    _write(path, value)
    return path, path.read_bytes(), value


@pytest.mark.parametrize('status,body,expected', [
    (401, {'error_code': 'STALE_ATTEMPT'}, 'WORKER_UNAUTHORIZED'),
    (403, {'error_code': 'STALE_ATTEMPT'}, 'WORKER_FORBIDDEN'),
    (409, {}, 'JAVA_HTTP_ERROR'),
])
def test_auth_or_unknown_conflict_cannot_delete_uncertain_callback(tmp_path, status, body, expected):
    path, original, _ = pending_callback(tmp_path)
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(status, json=body)

    client = WorkerClient('http://localhost', 'private-key', transport=httpx.MockTransport(handle))
    try:
        with pytest.raises(WorkerClientError, match=f'^{expected}$'):
            runner(tmp_path, client).run(stage_type='MEDIA')
    finally:
        client.close()
    assert len(requests) == 1 and path.read_bytes() == original
    assert not (tmp_path / 'journal/fenced-callbacks').exists()


def test_5xx_body_fence_remains_retryable_and_bounded_without_losing_callback(tmp_path):
    path, original, _ = pending_callback(tmp_path)
    count = 0

    def handle(request):
        nonlocal count
        count += 1
        return httpx.Response(500, json={'error_code': 'STALE_ATTEMPT'})

    client = WorkerClient('http://localhost', 'private-key', transport=httpx.MockTransport(handle))
    try:
        with pytest.raises(WorkerClientError, match='^WORKER_RECONNECT_EXHAUSTED$'):
            runner(tmp_path, client, reconnect_wait_seconds=.12).run(stage_type='MEDIA')
    finally:
        client.close()
    assert 2 <= count <= 5 and path.read_bytes() == original
    assert not (tmp_path / 'journal/fenced-callbacks').exists()


def test_only_explicit_409_fence_archives_original_callback(tmp_path):
    path, original, value = pending_callback(tmp_path)
    client = WorkerClient('http://localhost', 'private-key', transport=httpx.MockTransport(
        lambda request: httpx.Response(409, json={'error_code': 'STALE_ATTEMPT'})))
    try:
        with pytest.raises(WorkerClientError, match='^STALE_ATTEMPT$'):
            runner(tmp_path, client).run_once(stage_type='MEDIA')
    finally:
        client.close()
    assert not path.exists()
    archives = list((tmp_path / 'journal/fenced-callbacks').glob('*.json'))
    assert len(archives) == 1 and archives[0].read_bytes() == original
    assert json.loads(archives[0].read_text()) == value


def test_successful_replay_sends_exact_original_old_attempt_bytes(tmp_path):
    path, original, value = pending_callback(tmp_path)
    requests = []
    wire_bodies = []

    def handle(request):
        requests.append(json.loads(request.content))
        wire_bodies.append(request.content)
        return httpx.Response(503 if len(requests) == 1 else 200,
                              json={} if len(requests) == 1 else {'outcome': 'ALREADY_APPLIED'})

    client = WorkerClient('http://localhost', 'private-key', transport=httpx.MockTransport(handle))
    worker = runner(tmp_path, client)
    try:
        with pytest.raises(WorkerClientError, match='JAVA_HTTP_ERROR'):
            worker.run_once(stage_type='MEDIA')
        assert path.read_bytes() == original
        assert worker.run_once(stage_type='MEDIA') == 'callback_replayed'
    finally:
        client.close()
    assert requests == [value['payload'], value['payload']] and not path.exists()
    assert wire_bodies[0] == wire_bodies[1]


def test_native_client_stop_still_unregisters_with_bounded_cleanup_scope(tmp_path):
    requests = []
    stop = threading.Event()

    def handle(request):
        requests.append(request)
        if request.url.path.endswith('/ready'):
            stop.set()
            return httpx.Response(200, json={'items': []})
        payload = json.loads(request.content)
        outcome = 'REGISTERED' if request.url.path.endswith('/register') else 'UNREGISTERED'
        return httpx.Response(200, json=PresenceClient.receipt(payload['worker_id'],
                                                              payload['session_id'], outcome))

    client = WorkerClient('http://localhost', 'private-key', transport=httpx.MockTransport(handle))
    try:
        worker = runner(tmp_path, client, stop=stop)
        worker.run(stage_type='MEDIA')
    finally:
        client.close()
    assert [request.url.path.rsplit('/', 1)[-1] for request in requests] == [
        'register', 'ready', 'unregister']
    assert requests[-1].extensions['timeout']['pool'] <= 2
    assert stop.is_set()


def test_dynamic_deadline_clearing_unfreezes_future_request_and_bounds_pool():
    current = time.monotonic() + .08
    requests = []
    client = WorkerClient('http://localhost', 'private-key', timeout_seconds=10,
                          transport=httpx.MockTransport(lambda request: (
                              requests.append(request) or httpx.Response(200, json={'items': []}))))
    try:
        with client.request_scope(deadline_at=lambda: current):
            client.ready()
            first_deadline = current
            current = None
            time.sleep(.1)
            client.ready()
        assert 0 < requests[0].extensions['timeout']['pool'] <= .080001
        assert requests[1].extensions['livecut_deadline_at'] > first_deadline + 1
        assert requests[1].extensions['timeout']['pool'] > 1
    finally:
        client.close()


def test_recovery_cleared_during_clock_read_cannot_compare_none_or_expire_old_window(tmp_path):
    worker = runner(tmp_path, PresenceClient(idle=True))
    worker._reconnect_started = 0
    worker._runtime_deadline = 100

    def recovered_clock():
        # Deterministically reproduce the heartbeat publishing recovery between
        # the previous implementation's first field read and its comparison.
        worker._reconnect_started = None
        worker._runtime_deadline = None
        return 101

    worker._monotonic = recovered_clock
    worker._request_guard()


def test_guard_serializes_deadline_comparison_with_heartbeat_recovery(tmp_path):
    worker = runner(tmp_path, PresenceClient(idle=True))
    worker._reconnect_started, worker._runtime_deadline = 0, 100
    reading, release, recovered = threading.Event(), threading.Event(), threading.Event()
    failures = []

    def clock():
        reading.set()
        assert release.wait(1)
        return 99

    worker._monotonic = clock

    def check():
        try:
            worker._request_guard()
        except Exception as exc:
            failures.append(exc)

    def recover():
        with worker._presence_lock:
            worker._reconnect_started = worker._runtime_deadline = None
            recovered.set()

    checker = threading.Thread(target=check)
    checker.start()
    assert reading.wait(1)
    publisher = threading.Thread(target=recover)
    publisher.start()
    assert not recovered.wait(.02)
    release.set()
    checker.join(1)
    publisher.join(1)
    assert not checker.is_alive() and not publisher.is_alive() and not failures
    assert recovered.is_set()
    worker._monotonic = lambda: 101
    worker._request_guard()


@pytest.mark.parametrize('operation', ['register_worker', 'ready', 'claim'])
@pytest.mark.parametrize('existing_outage', [False, True])
@pytest.mark.parametrize('misleading_retryable', [False, True])
def test_noncallback_stale_is_terminal_even_inside_recovery_window(
        tmp_path, operation, existing_outage, misleading_retryable):
    client, events, invoked = PresenceClient(), [], []

    def invalid_fence(*args, **kwargs):
        invoked.append((args, kwargs))
        if existing_outage and len(invoked) == 1:
            raise WorkerClientError('JAVA_UNAVAILABLE', retryable=True)
        raise WorkerClientError('STALE_ATTEMPT', retryable=misleading_retryable)

    setattr(client, operation, invalid_fence)
    worker = runner(tmp_path, client, events=events, reconnect_wait_seconds=.3,
                    worker_heartbeat_seconds=.01)
    thread, errors = start(worker)
    thread.join(2)
    try:
        assert not thread.is_alive(), 'A noncallback fence must exit without a busy retry loop'
    finally:
        worker.stop.set()
        thread.join(2)
    assert len(errors) == 1 and isinstance(errors[0], WorkerClientError)
    assert errors[0].code == 'STALE_ATTEMPT'
    assert len(invoked) == (2 if existing_outage else 1)
    assert not any(event['status'] == 'reconnect-exhausted' for event in events)
    assert len([event for event in events if event['status'] == 'reconnecting']) == int(existing_outage)
    assert not (tmp_path / 'journal/fenced-callbacks').exists()
    if operation == 'claim':
        # A protocol rejection does not prove whether this request was taken.
        pending = json.loads((tmp_path / 'journal/pending-claim.json').read_text())
        assert pending['claim_request_id'] == invoked[-1][0][2]
        assert pending['session_id'] == invoked[-1][1]['session_id']


@pytest.mark.parametrize('when', ['startup', 'completion'])
def test_only_successfully_archived_callback_fence_allows_continuous_worker_to_continue(tmp_path, when):
    client, calls = PresenceClient(), []
    client.stale_complete = True
    original_ready = client.ready
    worker = runner(tmp_path, client, calls=calls, reconnect_wait_seconds=.3)
    if when == 'startup':
        pending_callback(tmp_path)

    def ready(**kwargs):
        if client.callbacks:
            # Reaching ready after the fenced callback proves that the loop
            # continued and the callback has already been durably retired.
            assert not (tmp_path / 'journal/pending-callback.json').exists()
            assert len(list((tmp_path / 'journal/fenced-callbacks').glob('*.json'))) == 1
            worker.stop.set()
            return {'items': []}
        return original_ready(**kwargs)

    client.ready = ready
    thread, errors = start(worker)
    thread.join(2)
    try:
        assert not thread.is_alive()
    finally:
        worker.stop.set()
        thread.join(2)
    assert not errors
    assert len(client.callbacks) == 1 and client.callbacks[0][0] == 'complete'
    assert calls == ([] if when == 'startup' else ['stage-1'])
    assert len(client.registrations) == 1
    assert (tmp_path / 'journal/rejected-callback.json').exists()


def test_failed_callback_fence_archive_does_not_signal_retirement_or_delete_pending(tmp_path, monkeypatch):
    path, original, _ = pending_callback(tmp_path)
    client = PresenceClient()
    client.stale_complete = True
    original_write = _write

    def fail_archive(target, value, **kwargs):
        if target.parent.name == 'fenced-callbacks':
            raise OSError('synthetic durable fence archive failure')
        return original_write(target, value, **kwargs)

    monkeypatch.setattr('livecut.worker._write', fail_archive)
    with pytest.raises(OSError):
        runner(tmp_path, client).run(stage_type='MEDIA')
    assert path.read_bytes() == original
    assert not client.registrations
    assert not (tmp_path / 'journal/fenced-callbacks').exists()
