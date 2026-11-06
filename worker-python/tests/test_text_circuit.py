"""Synthetic circuit state and frozen-policy boundary checks; no live providers."""
import json
import threading
from dataclasses import replace

import httpx
import pytest
from livecut.model_budget import BudgetTransport, ModelBudgetError
from livecut.text_circuit import (
    ROUTED_IDENTITY,
    WIRE_DISPATCHED,
    TextCircuit,
    TextCircuitPolicy,
    atomic_state,
    profile_key,
    read_state,
    stage_state_directory,
)
from livecut.text_resilience import TextRecoveryError, _digest, _Profile
from livecut.worker_client import WorkerClientError
from test_text_resilience import BODY, URL, Ledger, backup, backup_context, setup


def primary():
    return _Profile("primary", URL, "openai", BODY["model"])


def policy_path(context, body=BODY):
    digest = _digest({"endpoint": URL, "body": body})
    return stage_state_directory(context, "text-retry-policies") / (digest + ".json")


@pytest.mark.parametrize("threshold,cooldown", [(0, 60), (11, 60), (True, 60), (2, 0),
                                               (2, 301), (2, 1.5)])
def test_circuit_parameters_are_bounded(threshold, cooldown):
    with pytest.raises(ValueError, match="INVALID_TEXT_CIRCUIT_CONFIGURATION"):
        TextCircuitPolicy(threshold, cooldown)


def test_health_is_persistent_opaque_and_scoped_to_profile_and_stage(tmp_path):
    context = backup_context(tmp_path)
    clock = [1000.0]
    circuit = TextCircuit(context, wall_time=lambda: clock[0])
    key = profile_key(primary(), "Bearer private-primary-credential")
    circuit.record(key, "PROVIDER_UNAVAILABLE")
    assert circuit.route([key]) == ([0], [])
    circuit.record(key, "PROVIDER_TIMEOUT")
    assert circuit.health(key)["open_until"] == 1060
    restarted = TextCircuit(context, wall_time=lambda: clock[0])
    assert restarted.health(key) == circuit.health(key)
    for changed in (replace(primary(), endpoint="https://other.example/chat/completions"),
                    replace(primary(), style="dashscope"), replace(primary(), model="other-model")):
        assert restarted.health(profile_key(changed, "Bearer private-primary-credential"))["open_until"] == 0
    assert restarted.health(profile_key(primary(), "Bearer rotated-credential"))["open_until"] == 0
    other_stage = replace(context, lease=replace(context.lease, stage_id="different-stage"))
    assert TextCircuit(other_stage).health(key)["open_until"] == 0
    stored = "".join(p.read_text() for p in stage_state_directory(context, "text-provider-health").glob("*.json"))
    assert all(secret not in stored for secret in ("private-primary", "https://", "primary-model"))
    clock[0] = 1060
    assert restarted.route([key]) == ([0], [0])
    restarted.record(key)
    assert restarted.route([key]) == ([0], [])
    assert restarted.health(key)["open_until"] == 0


@pytest.mark.parametrize("code", ["PROVIDER_UNAUTHORIZED", "PROVIDER_QUOTA_EXHAUSTED"])
def test_auth_or_quota_open_on_first_wire_outcome(tmp_path, code):
    circuit = TextCircuit(backup_context(tmp_path), wall_time=lambda: 1000)
    key = profile_key(primary(), "synthetic")
    circuit.record(key, code)
    assert circuit.health(key) == {"version": 1, "consecutive_failures": 2, "open_until": 1060}


def test_transient_failures_must_be_consecutive(tmp_path):
    circuit = TextCircuit(backup_context(tmp_path), wall_time=lambda: 1000)
    key = profile_key(primary(), "synthetic")
    circuit.record(key, "PROVIDER_UNAVAILABLE")
    circuit.record(key, "PROVIDER_REQUEST_INVALID")
    circuit.record(key, "PROVIDER_UNAVAILABLE")
    assert circuit.health(key)["open_until"] == 0


def test_all_open_chooses_one_earliest_probe_and_preserves_priority_ties(tmp_path):
    circuit = TextCircuit(backup_context(tmp_path), wall_time=lambda: 1000)
    keys = [str(i) * 64 for i in (1, 2, 3)]
    directory = stage_state_directory(circuit.context, "text-provider-health")
    for key, until in zip(keys, (1040, 1020, 1020)):
        atomic_state(directory / (key + ".json"),
                     {"version": 1, "consecutive_failures": 2, "open_until": until})
    assert circuit.route(keys) == ([1], [1])


@pytest.mark.parametrize("raw", ["{", "[]", "null", '{"version":1}',
    '{"version":1,"consecutive_failures":true,"open_until":1000}',
    '{"version":1,"consecutive_failures":11,"open_until":1000}',
    '{"version":1,"consecutive_failures":2,"open_until":NaN}',
    '{"version":1,"consecutive_failures":2,"open_until":-1}', "x" * 65537],
    ids=["invalid-json", "list", "null", "missing", "bool", "too-many", "nan", "negative", "oversize"])
def test_corrupt_advisory_health_is_ignored(tmp_path, raw):
    circuit = TextCircuit(backup_context(tmp_path), wall_time=lambda: 1000)
    key = profile_key(primary(), "synthetic")
    path = stage_state_directory(circuit.context, "text-provider-health") / (key + ".json")
    path.write_text(raw)
    assert circuit.route([key]) == ([0], [])


def test_unwritable_advisory_health_cannot_fail_a_paid_success(tmp_path, monkeypatch):
    def unavailable(*args, **kwargs):
        raise OSError("synthetic advisory storage failure")
    monkeypatch.setattr("livecut.text_circuit.atomic_state", unavailable)
    client, ledger, _ = setup(tmp_path, lambda request: httpx.Response(200, json={"choices": []}))
    assert client.post(URL, json=BODY).status_code == 200
    assert len(ledger.calls) == 1


def test_partial_or_malformed_frozen_policy_fails_closed(tmp_path):
    context = backup_context(tmp_path)
    policy_path(context).write_text('{"policy":')
    hits = []
    client, ledger, _ = setup(tmp_path, lambda request: hits.append(request), context=context)
    with pytest.raises(TextRecoveryError, match="INVALID_TEXT_RETRY_STATE"):
        client.post(URL, json=BODY)
    assert not ledger.calls and not hits


@pytest.mark.parametrize("mutation", [
    lambda p: p.update(version=True),
    lambda p: p.update(route_profile_indices=[]),
    lambda p: p.update(route_profile_indices=[0, 0]),
    lambda p: p.update(route_profile_indices=[True]),
    lambda p: p.update(route_profile_indices=[2]),
    lambda p: p.update(probe_profile_indices=[1]),
    lambda p: p.update(circuit={"failure_threshold": 0, "cooldown_seconds": 60}),
    lambda p: p.update(extra="unrecognized"),
    lambda p: p["profiles"][0].update(endpoint="https://secret:credential@example.com/chat/completions"),
    lambda p: p["profiles"][0].update(model=[]),
])
def test_malformed_v2_policy_never_reinterprets_as_new_window(tmp_path, mutation):
    client, ledger, context = setup(tmp_path, lambda request: httpx.Response(200, json={"choices": []}))
    client.post(URL, json=BODY)
    path = policy_path(context)
    frozen = read_state(path)
    mutation(frozen["policy"])
    atomic_state(path, frozen)
    with pytest.raises(TextRecoveryError, match="INVALID_TEXT_RETRY_STATE"):
        client.post(URL, json=BODY)
    assert len(ledger.calls) == 1


def test_v1_policy_uses_exact_old_hash_and_route_even_after_expiry(tmp_path, monkeypatch):
    backup(monkeypatch)
    clock = [1000.0]
    monkeypatch.setattr("livecut.text_resilience.time.time", lambda: clock[0])
    context = backup_context(tmp_path)
    hits = []
    def provider(request):
        hits.append(request.url.host)
        return httpx.Response(503 if request.url.host == "primary.example" else 200,
                              json={"choices": ["original"]})
    client, ledger, _ = setup(tmp_path, provider, context=context)
    outer = client._transport
    v1 = {"version": 1, "max_attempts": 3, "attempt_timeout_seconds": 90.0,
          "total_timeout_seconds": 240.0, "backoff_seconds": 0.0,
          "profiles": [p.public() for p in [primary(), *outer.fallbacks]]}
    atomic_state(policy_path(context), {"policy": v1, "started_at_ms": 1000000})
    expected = client.post(URL, json=BODY).json()
    assert hits == ["primary.example", "primary.example", "backup.example"]
    calls = list(ledger.calls.values())
    assert len(calls) == 3
    first_policy = read_state(policy_path(context))
    assert first_policy["policy"] == v1
    clock[0] += 241
    resumed, _, _ = setup(tmp_path, provider, context=replace(context, directory=tmp_path / "resumed"),
                          ledger=ledger)
    assert resumed.post(URL, json=BODY).json() == expected
    assert len(hits) == len(ledger.calls) == 3
    assert read_state(policy_path(context)) == first_policy


@pytest.mark.parametrize("status", ["SUCCEEDED", "RESERVED", "DISPATCHING", "UNKNOWN_OUTCOME", "FAILED"])
def test_legacy_ledger_without_policy_freezes_v1_before_any_reservation(tmp_path, monkeypatch, status):
    backup(monkeypatch)
    context = backup_context(tmp_path)
    hits = []
    def provider(request):
        hits.append(request.url.host)
        return httpx.Response(503 if status == "FAILED" and len(hits) == 1 else 200,
                              json={"choices": []})
    client, ledger, _ = setup(tmp_path, provider, context=context)
    budget = client._transport.inner
    budget.handle_request(httpx.Request("POST", URL, json=BODY)).close()
    call = next(iter(ledger.calls.values()))
    call["status"] = status
    if status not in {"SUCCEEDED", "FAILED"}:
        (context.storage_root / call["response_key"]).unlink()
        call.pop("response_key", None)
        call.pop("response_hash", None)
    key = profile_key(primary(), "")
    client._transport.circuit.record(key, "PROVIDER_UNAVAILABLE")
    client._transport.circuit.record(key, "PROVIDER_UNAVAILABLE")
    assert not policy_path(context).exists()
    reservations, lookups = [], []
    original_reserve, original_lookup = ledger.reserve_call, ledger.lookup_call
    def reserve(payload):
        assert read_state(policy_path(context))["policy"]["version"] == 1
        reservations.append(payload)
        return original_reserve(payload)
    def lookup(payload):
        lookups.append(payload)
        return original_lookup(payload)
    ledger.reserve_call, ledger.lookup_call = reserve, lookup
    assert client.post(URL, json=BODY).status_code == 200
    assert len(lookups) == 1 and reservations
    assert read_state(policy_path(context))["policy"]["version"] == 1
    assert set(hits) == {"primary.example"}
    expected_calls = 1 if status in {"SUCCEEDED", "RESERVED"} else 2
    assert len(ledger.calls) == expected_calls


@pytest.mark.parametrize("response_kind", ["empty", "non-json"])
def test_http_200_invalid_wire_responses_open_primary_for_new_windows(tmp_path, monkeypatch, response_kind):
    backup(monkeypatch)
    hits = []
    def provider(request):
        hits.append(request.url.host)
        if request.url.host == "primary.example":
            return httpx.Response(200, json={}) if response_kind == "empty" else httpx.Response(200, text="bad gateway")
        return httpx.Response(200, json={"choices": []})
    client, ledger, _ = setup(tmp_path, provider, context=backup_context(tmp_path))
    assert client.post(URL, json=BODY).status_code == 200
    assert hits == ["primary.example", "primary.example", "backup.example"]
    other = {**BODY, "messages": [{"role": "user", "content": "another window"}]}
    assert client.post(URL, json=other).status_code == 200
    assert hits == ["primary.example", "primary.example", "backup.example", "backup.example"]
    assert len(ledger.calls) == 4


def test_frozen_v2_circuit_parameters_and_profiles_reject_config_drift(tmp_path, monkeypatch):
    backup(monkeypatch)
    context = backup_context(tmp_path)
    client, ledger, _ = setup(tmp_path, lambda request: httpx.Response(200, json={"choices": []}),
                              context=context)
    client.post(URL, json=BODY)
    changed, _, _ = setup(tmp_path, lambda request: None, ledger=ledger, context=context,
                         circuit_policy=TextCircuitPolicy(3, 60))
    with pytest.raises(TextRecoveryError, match="TEXT_RETRY_POLICY_CONFLICT"):
        changed.post(URL, json=BODY)
    rows = json.loads(__import__("os").environ["LIVECUT_TEXT_FALLBACKS_JSON"])
    rows[0]["base_url"] = "https://changed.example/v1"
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", json.dumps(rows))
    changed, _, _ = setup(tmp_path, lambda request: None, ledger=ledger, context=context)
    with pytest.raises(TextRecoveryError, match="TEXT_RETRY_POLICY_CONFLICT"):
        changed.post(URL, json=BODY)
    assert len(ledger.calls) == 1


@pytest.mark.parametrize("failure", ["java", "dispatch", "storage", "lease"])
def test_nonwire_failures_never_change_health_or_dispatch(tmp_path, monkeypatch, failure):
    context = backup_context(tmp_path)
    ledger = Ledger()
    hits = []
    client, _, _ = setup(tmp_path, lambda request: hits.append(request), context=context, ledger=ledger)
    budget = client._transport.inner
    if failure == "java":
        ledger.fail = True
    elif failure == "dispatch":
        ledger.update_call = lambda rid, payload: {"outcome": "ALREADY_APPLIED"}
    elif failure == "storage":
        def failed_probe(request_id):
            raise ModelBudgetError("MODEL_RESPONSE_STORAGE_UNAVAILABLE")
        monkeypatch.setattr(budget, "_preflight_recording", failed_probe)
    else:
        check = context.guard.check
        counter = [0]
        def fail_after_reservation():
            counter[0] += 1
            if counter[0] >= 6:
                raise WorkerClientError("STALE_ATTEMPT")
            check()
        monkeypatch.setattr(context.guard, "check", fail_after_reservation)
    with pytest.raises((ModelBudgetError, WorkerClientError)):
        client.post(URL, json=BODY)
    assert not hits
    directory = stage_state_directory(context, "text-provider-health")
    assert not list(directory.glob("*.json"))


def test_dispatch_marker_is_reset_before_guard_and_set_only_for_real_dispatch(tmp_path):
    context = backup_context(tmp_path)
    ledger = Ledger()
    requests = []
    budget = BudgetTransport(ledger, context, inner=httpx.MockTransport(
        lambda request: (requests.append(request), httpx.Response(200, json={"choices": []}))[1]))
    request = httpx.Request("POST", URL, json=BODY, extensions={WIRE_DISPATCHED: True})
    ledger.fail = True
    with pytest.raises(WorkerClientError):
        budget.handle_request(request)
    assert request.extensions[WIRE_DISPATCHED] is False and not requests
    ledger.fail = False
    budget.handle_request(request)
    assert request.extensions[WIRE_DISPATCHED] is True and len(requests) == 1
    request.extensions[WIRE_DISPATCHED] = True
    budget.handle_request(request)
    assert request.extensions[WIRE_DISPATCHED] is False and len(requests) == 1


@pytest.mark.parametrize("after_wire", [False, True])
def test_transport_shaped_java_errors_are_not_provider_failures(tmp_path, after_wire):
    context = backup_context(tmp_path)
    hits = []
    client, ledger, _ = setup(tmp_path, lambda request: (
        hits.append(request), httpx.Response(200, json={"choices": []}))[1], context=context)
    def unavailable(*args):
        raise httpx.ConnectError("synthetic Java transport failure")
    if after_wire:
        original = ledger.update_call
        def fail_after_provider(rid, payload):
            if payload["status"] != "DISPATCHING":
                return unavailable()
            return original(rid, payload)
        ledger.update_call = fail_after_provider
    else:
        ledger.reserve_call = unavailable
    with pytest.raises(httpx.ConnectError):
        client.post(URL, json=BODY)
    assert len(hits) == int(after_wire)
    assert not list(stage_state_directory(context, "text-provider-health").glob("*.json"))


def test_http_200_error_stream_is_closed_before_backup_attempt(tmp_path, monkeypatch):
    backup(monkeypatch)
    closed = []
    class Stream(httpx.SyncByteStream):
        def __iter__(self):
            yield b'{"error":{"code":"internal_server_error"}}'
        def close(self):
            closed.append(True)
    hits = []
    def provider(request):
        hits.append(request.url.host)
        if request.url.host == "primary.example":
            return httpx.Response(200, stream=Stream())
        assert len(closed) == 2
        return httpx.Response(200, json={"choices": []})
    client, ledger, _ = setup(tmp_path, provider, context=backup_context(tmp_path))
    assert client.post(URL, json=BODY).status_code == 200
    assert len(hits) == len(ledger.calls) == 3 and len(closed) == 2


@pytest.mark.parametrize("routed", [True, "true", 1, None])
def test_routed_identity_extension_requires_valid_recovery(tmp_path, routed):
    client, ledger, _ = setup(tmp_path, lambda request: None)
    request = httpx.Request("POST", URL, json=BODY, extensions={ROUTED_IDENTITY: routed})
    with pytest.raises(ModelBudgetError, match="INVALID_TEXT_RETRY_STATE"):
        client._transport.inner.handle_request(request)
    assert not ledger.calls


def test_create_only_publish_has_one_complete_winner(tmp_path):
    target = tmp_path / "atomic.json"
    barrier = threading.Barrier(2)
    winners, losers = [], []
    def publish(value):
        barrier.wait()
        try:
            atomic_state(target, value, create_only=True)
            winners.append(value)
        except FileExistsError:
            losers.append(value)
    values = [{"policy": "a" * 20000}, {"policy": "b" * 20000}]
    threads = [threading.Thread(target=publish, args=(value,)) for value in values]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(2)
    assert len(winners) == len(losers) == 1
    assert read_state(target) == winners[0]
    assert list(tmp_path.iterdir()) == [target]


def test_failed_atomic_publish_leaves_no_partial_policy_and_no_dispatch(tmp_path, monkeypatch):
    context = backup_context(tmp_path)
    def crash_before_link(*args):
        raise OSError("synthetic publication failure")
    monkeypatch.setattr("livecut.text_circuit.os.link", crash_before_link)
    client, ledger, _ = setup(tmp_path, lambda request: None, context=context)
    with pytest.raises(TextRecoveryError, match="INVALID_TEXT_RETRY_STATE"):
        client.post(URL, json=BODY)
    assert not policy_path(context).exists() and not ledger.calls
    assert not list(policy_path(context).parent.iterdir())


def test_unreadable_frozen_policy_fails_closed_before_reserving(tmp_path, monkeypatch):
    context = backup_context(tmp_path)
    def denied(*args):
        raise PermissionError("synthetic frozen policy read denial")
    monkeypatch.setattr("livecut.text_resilience.read_state", denied)
    client, ledger, _ = setup(tmp_path, lambda request: None, context=context)
    with pytest.raises(TextRecoveryError, match="INVALID_TEXT_RETRY_STATE"):
        client.post(URL, json=BODY)
    assert not ledger.calls


def test_state_parent_symlink_is_rejected_before_writing(tmp_path):
    context = backup_context(tmp_path)
    stage = stage_state_directory(context, "text-retry-policies").parent
    target = stage / "text-retry-policies"
    target.rmdir()
    outside = tmp_path / "outside-directory"
    outside.mkdir()
    try:
        target.symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("This Windows account cannot create symbolic links")
    client, ledger, _ = setup(tmp_path, lambda request: None, context=context)
    with pytest.raises(TextRecoveryError, match="INVALID_TEXT_RETRY_STATE"):
        client.post(URL, json=BODY)
    assert not list(outside.iterdir()) and not ledger.calls


def test_symlink_state_is_never_followed_or_replaced(tmp_path):
    context = backup_context(tmp_path)
    outside = tmp_path / "outside.json"
    outside.write_text('{"private":"untouched"}')
    target = policy_path(context)
    try:
        target.symlink_to(outside)
    except OSError:
        pytest.skip("This Windows account cannot create symbolic links")
    client, ledger, _ = setup(tmp_path, lambda request: None, context=context)
    with pytest.raises(TextRecoveryError, match="INVALID_TEXT_RETRY_STATE"):
        client.post(URL, json=BODY)
    assert not ledger.calls and json.loads(outside.read_text()) == {"private": "untouched"}
