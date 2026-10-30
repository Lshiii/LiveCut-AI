"""Executor presence uses explicit protocol fixtures, never paid providers."""

import json
import threading
from datetime import datetime, timezone
from uuid import uuid4

import httpx
import pytest
from livecut.worker import STAGES, StageWorker, WorkerError, _write
from livecut.worker_client import WorkerClient, WorkerClientError
from test_worker import FakeClient, claim, fake_executor


class PresenceClient(FakeClient):
    def __init__(self, *, idle=False):
        super().__init__()
        self.idle = idle
        self.registrations = []
        self.presence_heartbeats = []
        self.unregistrations = []
        self.sessions = []
        self.ready_calls = []
        self.beat = threading.Event()
        self.presence_failure = None
        self.stop_on_complete = None
        self.claim_outcome = None
        self.unregister_failure = None

    @staticmethod
    def receipt(worker_id, session_id, outcome):
        return {"worker_id": worker_id, "session_id": session_id, "outcome": outcome,
                "heartbeat_timeout_seconds": 35, "last_seen_at": datetime.now(timezone.utc).isoformat()}

    def register_worker(self, worker_id, session_id, capabilities, **kwargs):
        self.registrations.append((worker_id, session_id, capabilities, kwargs))
        return self.receipt(worker_id, session_id, "REGISTERED")

    def heartbeat_worker(self, worker_id, session_id, *, timeout_seconds=None):
        self.presence_heartbeats.append((worker_id, session_id))
        self.beat.set()
        if self.presence_failure is not None:
            raise self.presence_failure
        return self.receipt(worker_id, session_id, "ACCEPTED")

    def unregister_worker(self, worker_id, session_id):
        self.unregistrations.append((worker_id, session_id))
        if self.unregister_failure is not None:
            raise self.unregister_failure
        return self.receipt(worker_id, session_id, "UNREGISTERED")

    def ready(self, **kwargs):
        self.ready_calls.append(kwargs)
        if self.idle or kwargs.get('stage_type') not in {None, self.reply['stage_type']}:
            return {"items": []}
        return {"items": [{"id": "stage-1", "stage_type": self.reply["stage_type"],
                           "job_id": self.reply["job_id"]}]}

    def claim(self, stage_id, worker_id, claim_request_id, *, session_id, job_id=None):
        self.sessions.append(session_id)
        if self.claim_outcome:
            return {"outcome": self.claim_outcome}
        return super().claim(stage_id, worker_id, claim_request_id)

    def callback(self, action, stage_id, payload):
        response = super().callback(action, stage_id, payload)
        if action == "complete" and self.stop_on_complete is not None:
            self.stop_on_complete.set()
        return response


def worker(tmp_path, client, calls, **kwargs):
    return StageWorker(client, tmp_path / "storage", tmp_path / "journal", "worker-1",
                       fake_executor(calls), worker_heartbeat_seconds=.01, **kwargs)


def test_idle_executor_registers_pulses_and_unregisters_under_one_session(tmp_path):
    client, calls = PresenceClient(idle=True), []
    runner = worker(tmp_path, client, calls)
    errors = []

    def run():
        try:
            runner.run(poll_seconds=.005)
        except Exception as exc:
            errors.append(exc)
    thread = threading.Thread(target=run)
    thread.start()
    assert client.beat.wait(2)
    runner.stop.set()
    thread.join(2)
    assert not thread.is_alive() and not errors and not calls
    assert len(client.registrations) == 1
    wid, session, stages, target = client.registrations[0]
    assert stages == list(STAGES) and not target
    assert client.presence_heartbeats and set(client.presence_heartbeats) == {(wid, session)}
    assert client.unregistrations == [(wid, session)]
    assert not client.callbacks


def test_busy_executor_keeps_independent_presence_and_stage_heartbeats(tmp_path):
    client, calls = PresenceClient(), []
    done = threading.Event()

    def execute(context):
        assert client.beat.wait(2)
        assert done.wait(2)
        return fake_executor(calls)(context)

    runner = StageWorker(client, tmp_path / 'storage', tmp_path / 'journal', 'worker-1', execute,
                         heartbeat_seconds=.01, worker_heartbeat_seconds=.01)
    # Set only once the separate stage renewal has happened.
    original = client.callback

    def callback(action, stage_id, payload):
        response = original(action, stage_id, payload)
        if action == 'heartbeat':
            done.set()
        return response
    client.callback = callback
    assert runner.run_once() == 'completed'
    assert client.presence_heartbeats and any(row[0] == 'heartbeat' for row in client.callbacks)
    assert calls == ['stage-1']
    assert len(set(client.sessions)) == 1


def test_once_advertises_only_its_stage_and_job_and_always_unregisters(tmp_path):
    target = str(uuid4())
    client, calls = PresenceClient(), []
    client.reply = claim(stage_type='ASR', job_id=target)
    runner = worker(tmp_path, client, calls, target_job_id=target)
    assert runner.run_once(stage_type='ASR') == 'completed'
    wid, session, stages, options = client.registrations[0]
    assert stages == ['ASR'] and options == {'target_job_id': target}
    assert client.ready_calls == [{'stage_type': 'ASR', 'limit': 20, 'job_id': target}]
    assert client.sessions == [session]
    assert client.unregistrations == [(wid, session)]


@pytest.mark.parametrize('outcome', ['NO_CAPACITY', 'NO_WORK', 'EXPIRED'])
def test_capacity_wait_does_not_compute_or_publish_a_failure(tmp_path, outcome):
    client, calls = PresenceClient(), []
    client.claim_outcome = outcome
    assert worker(tmp_path, client, calls).run_once() == 'unavailable'
    assert not calls and not client.callbacks
    assert not (tmp_path / 'journal/pending-claim.json').exists()
    assert client.unregistrations


def test_uncertain_claim_replays_same_id_and_session_while_process_is_alive(tmp_path):
    client, calls = PresenceClient(), []
    client.fail_claim_once = True
    runner = worker(tmp_path, client, calls)
    client.stop_on_complete = runner.stop
    runner.run(poll_seconds=.001)
    assert len(client.claims) == 2 and client.claims[0] == client.claims[1]
    assert client.sessions[0] == client.sessions[1] == client.registrations[0][1]
    assert calls == ['stage-1'] and len(client.unregistrations) == 1


def test_restarted_process_does_not_execute_old_session_claim(tmp_path):
    old_session = str(uuid4())
    _write(tmp_path / 'journal/pending-claim.json', {
        'stage_id': 'stage-1', 'worker_id': 'worker-1', 'claim_request_id': 'old-claim',
        'session_id': old_session,
    })
    client, calls = PresenceClient(), []
    client.claim_outcome = 'EXPIRED'  # Java binds the existing receipt to its old session.
    assert worker(tmp_path, client, calls).run_once() == 'unavailable'
    assert client.sessions[0] != old_session
    assert not calls and not client.callbacks


def test_completed_old_result_is_replayed_before_registering_a_new_session(tmp_path):
    client, calls = PresenceClient(), []
    attempt_key = 'jobs/job-1/stages/stage-1/attempts/1/token-1'
    _write(tmp_path / 'journal/pending-claim.json', {
        'stage_id': 'stage-1', 'worker_id': 'worker-1', 'claim_request_id': 'old-claim',
        'session_id': str(uuid4()), 'attempt_key': attempt_key,
    })
    callback = {'action': 'complete', 'stage_id': 'stage-1',
                'payload': {'attempt': 1, 'run_token': 'token-1', 'manifest_hash': 'a' * 64}}
    _write(tmp_path / 'storage' / attempt_key / 'worker-result.json', callback)
    assert worker(tmp_path, client, calls).run_once() == 'callback_replayed'
    assert client.callbacks == [('complete', 'stage-1', callback['payload'])]
    assert not client.registrations and not client.claims and not calls


@pytest.mark.parametrize('error_code', ['WORKER_SESSION_EXPIRED', 'JAVA_UNAVAILABLE'])
def test_uncertain_presence_stops_new_dispatch_and_invalidates_busy_guard(tmp_path, error_code):
    client = PresenceClient()

    def execute(context):
        # Inject the failure only once computation actually began. A slow CI
        # filesystem may otherwise expire presence before the stage was claimed.
        client.presence_failure = WorkerClientError(error_code, retryable=error_code == 'JAVA_UNAVAILABLE')
        assert runner.stop.wait(2)
        context.guard.check()
        pytest.fail('uncertain executor must not continue computation')

    runner = StageWorker(client, tmp_path / 'storage', tmp_path / 'journal', 'worker-1', execute,
                         worker_heartbeat_seconds=.01)
    with pytest.raises(WorkerClientError, match=error_code):
        runner.run_once()
    assert runner.stop.is_set()
    assert len(client.ready_calls) == 1
    assert [entry[0] for entry in client.callbacks] == ['fail']
    assert client.callbacks[0][2]['error_code'] == 'WORKER_STOPPED'
    assert client.unregistrations


def test_registration_failure_fails_closed_before_ready_or_computation(tmp_path):
    client, calls = PresenceClient(), []

    def refuse(*args, **kwargs):
        raise WorkerClientError('WORKER_SESSION_CONFLICT')
    client.register_worker = refuse
    with pytest.raises(WorkerClientError, match='WORKER_SESSION_CONFLICT'):
        worker(tmp_path, client, calls).run_once()
    assert not client.ready_calls and not client.claims and not calls


def test_unregister_failure_does_not_replace_original_execution_error(tmp_path):
    client = PresenceClient()
    client.unregister_failure = WorkerClientError('JAVA_UNAVAILABLE', retryable=True)
    client.fail_claim_once = True
    with pytest.raises(WorkerClientError, match='JAVA_UNAVAILABLE'):
        worker(tmp_path, client, []).run_once()
    assert client.unregistrations


def test_journal_lock_is_held_for_entire_registered_lifetime(tmp_path):
    client = PresenceClient(idle=True)
    first, second = worker(tmp_path, client, []), worker(tmp_path, client, [])
    thread = threading.Thread(target=lambda: first.run(poll_seconds=.005))
    thread.start()
    try:
        assert client.beat.wait(2)
        with pytest.raises(WorkerError, match='WORKER_BUSY'):
            second.run_once()
        assert len(client.registrations) == 1
    finally:
        first.stop.set()
        thread.join(2)


def test_client_registration_heartbeat_unregistration_and_session_bound_claim_routes():
    session, target, requests = str(uuid4()), str(uuid4()), []

    def receive(request):
        requests.append(request)
        assert request.headers['X-Worker-Key'] == 'private-test-key'
        if request.url.path == '/internal/stages/claim':
            return httpx.Response(200, json={'outcome': 'NO_CAPACITY'})
        outcome = {'register': 'REGISTERED', 'heartbeat': 'ACCEPTED',
                   'unregister': 'UNREGISTERED'}[request.url.path.rsplit('/', 1)[1]]
        return httpx.Response(200, json=PresenceClient.receipt('worker-1', session, outcome))
    with_client = WorkerClient('http://localhost', 'private-test-key', transport=httpx.MockTransport(receive))
    try:
        with_client.register_worker('worker-1', session, ['ASR'], target_job_id=target)
        with_client.heartbeat_worker('worker-1', session)
        with_client.claim('stage-1', 'worker-1', 'claim-1', job_id=target, session_id=session)
        with_client.unregister_worker('worker-1', session)
    finally:
        with_client.close()
    payloads = [json.loads(request.content) for request in requests]
    assert payloads[0] == {'worker_id': 'worker-1', 'session_id': session, 'stage_types': ['ASR'],
                           'slots': 1, 'target_job_id': target}
    assert payloads[2]['session_id'] == session and payloads[2]['job_id'] == target
    assert payloads[1] == payloads[3] == {'worker_id': 'worker-1', 'session_id': session}


@pytest.mark.parametrize('overrides', [
    {'session_id': str(uuid4())}, {'worker_id': 'other-worker'}, {'outcome': 'ACCEPTED'},
    {'heartbeat_timeout_seconds': True}, {'last_seen_at': '2026-10-30T12:00:00'},
])
def test_client_rejects_malformed_or_other_session_registration_receipts(overrides):
    session = str(uuid4())
    receipt = PresenceClient.receipt('worker-1', session, 'REGISTERED') | overrides
    client = WorkerClient('http://localhost', 'secret', transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=receipt)))
    try:
        with pytest.raises(WorkerClientError, match='INVALID_WORKER_RESPONSE'):
            client.register_worker('worker-1', session, ['MEDIA'])
    finally:
        client.close()


def test_missing_registration_endpoint_never_falls_back_to_unregistered_claims(tmp_path):
    requests = []

    def receive(request):
        requests.append(request)
        return httpx.Response(404, json={'error_code': 'NOT_FOUND'})
    client = WorkerClient('http://localhost', 'secret', transport=httpx.MockTransport(receive))
    try:
        with pytest.raises(WorkerClientError, match='NOT_FOUND'):
            worker(tmp_path, client, []).run_once()
    finally:
        client.close()
    assert [request.url.path for request in requests] == ['/internal/workers/register']


def test_each_gracefully_stopped_once_uses_a_fresh_registration_session(tmp_path):
    client, calls = PresenceClient(idle=True), []
    runner = worker(tmp_path, client, calls)
    assert runner.run_once() == runner.run_once() == 'idle'
    sessions = [item[1] for item in client.registrations]
    assert len(sessions) == len(set(sessions)) == 2
    assert client.unregistrations == [('worker-1', session) for session in sessions]


def test_busy_first_resource_does_not_block_a_later_compatible_stage(tmp_path):
    client, calls, attempts = PresenceClient(), [], []

    def ready(**kwargs):
        candidates = [{'id': 'stage-1', 'stage_type': 'MEDIA'},
                      {'id': 'stage-2', 'stage_type': 'SEGMENT'}]
        return {'items': [item for item in candidates if item['stage_type'] == kwargs['stage_type']]}
    client.ready = ready

    def acquire(stage_id, worker_id, claim_request_id, *, session_id):
        pending = json.loads((tmp_path / 'journal/pending-claim.json').read_bytes())
        assert pending['stage_id'] == stage_id and pending['claim_request_id'] == claim_request_id
        assert pending['session_id'] == session_id
        attempts.append(pending)
        if stage_id == 'stage-1':
            return {'outcome': 'NO_CAPACITY'}
        return claim(stage_id=stage_id, stage_type='SEGMENT')
    client.claim = acquire
    assert worker(tmp_path, client, calls).run_once() == 'completed'
    assert calls == ['stage-2']
    assert len({item['claim_request_id'] for item in attempts}) == 2
    assert len({item['session_id'] for item in attempts}) == 1
    assert not any(action == 'fail' for action, _, _ in client.callbacks)


def test_capacity_candidates_are_bounded_and_each_has_a_durable_identity(tmp_path):
    client, calls, attempts = PresenceClient(), [], []
    client.ready = lambda **kwargs: {'items': [
        {'id': f'{kwargs["stage_type"]}-{index}', 'stage_type': kwargs['stage_type']}
        for index in range(kwargs['limit'])]}

    def refuse(stage_id, worker_id, claim_request_id, *, session_id):
        attempts.append((stage_id, claim_request_id, session_id))
        return {'outcome': 'NO_CAPACITY'}
    client.claim = refuse
    assert worker(tmp_path, client, calls).run_once() == 'unavailable'
    assert len(attempts) == len({row[1] for row in attempts}) == 20
    assert len({row[2] for row in attempts}) == 1
    assert not calls and not client.callbacks


def test_stage_filtered_registration_rejects_wrong_ready_capability_before_claim(tmp_path):
    client = PresenceClient()
    client.ready = lambda **kwargs: {'items': [{'id': 'stage-1', 'stage_type': 'MEDIA'}]}
    with pytest.raises(WorkerError, match='CLAIM_STAGE_TYPE_CHANGED'):
        worker(tmp_path, client, []).run_once(stage_type='ASR')
    assert not client.claims


def test_type_queries_reach_segment_beyond_twenty_blocked_media_stages(tmp_path):
    client, calls, queries = PresenceClient(), [], []

    def ready(**kwargs):
        queries.append(kwargs)
        if kwargs['stage_type'] == 'MEDIA':
            return {'items': [{'id': f'media-{index}', 'stage_type': 'MEDIA'}
                              for index in range(kwargs['limit'])]}
        if kwargs['stage_type'] == 'SEGMENT':
            return {'items': [{'id': 'segment-after-20-media', 'stage_type': 'SEGMENT'}]}
        return {'items': []}

    def acquire(stage_id, worker_id, claim_request_id, *, session_id):
        if stage_id.startswith('media-'):
            return {'outcome': 'NO_CAPACITY'}
        return claim(stage_id=stage_id, stage_type='SEGMENT')
    client.ready, client.claim = ready, acquire
    assert worker(tmp_path, client, calls).run_once() == 'completed'
    assert calls == ['segment-after-20-media']
    assert [query['stage_type'] for query in queries] == ['MEDIA', 'ASR', 'SEGMENT']
    assert all(query['limit'] == 4 for query in queries)


def test_heartbeat_http_timeout_is_tightened_to_short_server_ttl():
    session, requests = str(uuid4()), []

    def receive(request):
        requests.append(request)
        return httpx.Response(200, json=PresenceClient.receipt('worker-1', session, 'ACCEPTED'))
    client = WorkerClient('http://localhost', 'secret', timeout_seconds=10,
                          transport=httpx.MockTransport(receive))
    try:
        client.heartbeat_worker('worker-1', session, timeout_seconds=5 / 3)
    finally:
        client.close()
    assert requests[0].extensions['timeout'] == {
        phase: 5 / 3 for phase in ('connect', 'read', 'write', 'pool')}


def short_ttl_client(client, clock):
    original = client.register_worker

    def register(*args, **kwargs):
        return original(*args, **kwargs) | {'heartbeat_timeout_seconds': 5}
    client.register_worker = register
    return lambda: clock['now']


def test_late_registration_receipt_cannot_start_work_after_its_conservative_ttl(tmp_path):
    client, calls, clock = PresenceClient(), [], {'now': 0}
    original = client.register_worker

    def delayed(*args, **kwargs):
        clock['now'] = 6
        return original(*args, **kwargs) | {'heartbeat_timeout_seconds': 5}
    client.register_worker = delayed
    runner = worker(tmp_path, client, calls)
    runner._monotonic = lambda: clock['now']
    with pytest.raises(WorkerClientError, match='WORKER_SESSION_EXPIRED'):
        runner.run_once()
    assert not client.ready_calls and not client.claims and not calls
    assert client.unregistrations and runner.stop.is_set()


def test_presence_expiring_during_ready_prevents_claiming_or_model_dispatch(tmp_path):
    client, calls, clock = PresenceClient(), [], {'now': 0}
    runner = worker(tmp_path, client, calls)
    runner._monotonic = short_ttl_client(client, clock)

    def delayed_ready(**kwargs):
        clock['now'] = 6
        return {'items': [{'id': 'stage-1', 'stage_type': 'MEDIA'}]}
    client.ready = delayed_ready
    with pytest.raises(WorkerClientError, match='WORKER_SESSION_EXPIRED'):
        runner.run_once()
    assert not client.claims and not calls
    assert client.unregistrations and runner.stop.is_set()


def test_hung_heartbeat_cannot_extend_local_guard_or_be_revived_by_a_late_receipt(tmp_path):
    client, clock, errors = PresenceClient(), {'now': 0}, []
    entered, release, check_guard, failed = (threading.Event() for _ in range(4))

    def hung(worker_id, session_id, *, timeout_seconds=None):
        assert timeout_seconds <= 5 / 3
        entered.set()
        assert release.wait(2)
        return client.receipt(worker_id, session_id, 'ACCEPTED') | {'heartbeat_timeout_seconds': 5}

    def execute(context):
        assert check_guard.wait(2)
        context.guard.check()
        pytest.fail('the expired presence must stop the next dispatch even while its HTTP thread hangs')

    original_callback = client.callback

    def callback(action, stage_id, payload):
        value = original_callback(action, stage_id, payload)
        if action == 'fail':
            failed.set()
        return value
    client.heartbeat_worker, client.callback = hung, callback
    runner = StageWorker(client, tmp_path / 'storage', tmp_path / 'journal', 'worker-1', execute,
                         worker_heartbeat_seconds=.01)
    runner._monotonic = short_ttl_client(client, clock)

    def run():
        try:
            runner.run_once()
        except Exception as exc:
            errors.append(exc)
    thread = threading.Thread(target=run)
    thread.start()
    try:
        assert entered.wait(2)
        clock['now'] = 6
        check_guard.set()
        assert failed.wait(2)
        assert runner.stop.is_set()
    finally:
        release.set()
        thread.join(2)
    assert not thread.is_alive()
    assert len(errors) == 1 and isinstance(errors[0], WorkerClientError)
    assert errors[0].code == 'WORKER_SESSION_EXPIRED'
    assert [action for action, _, _ in client.callbacks] == ['fail']
    assert client.callbacks[0][2]['error_code'] == 'WORKER_PRESENCE_LOST'
    assert runner.stop.is_set() and not client.presence_heartbeats
