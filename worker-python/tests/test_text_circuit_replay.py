"""Black-box synthetic circuit recovery; every provider call uses MockTransport.

The in-memory Ledger counts model reservations. These cases establish recovery
and identity behavior, not real provider availability or model quality.
"""
import json
from dataclasses import replace

import httpx
from test_text_resilience import BODY, URL, Ledger, backup, backup_context, setup


def window(name, **parameters):
    return {**BODY, "messages": [{"role": "user", "content": name}], **parameters}


def request_window(request):
    body = json.loads(request.content)
    return body["messages"][0]["content"]


def paid_response(value):
    return httpx.Response(200, json={"choices": [{"message": {"content": value}}]})


def restarted_context(context, tmp_path):
    return replace(context, directory=tmp_path / "restarted-attempt", lease=replace(
        context.lease, attempt=context.lease.attempt + 1, run_token="token-restarted"))


def test_new_window_uses_backup_after_two_primary_failures(tmp_path, monkeypatch):
    backup(monkeypatch)
    hits = []

    def provider(request):
        hits.append(request)
        if request.url.host == "primary.example":
            return httpx.Response(503, json={"error": {"code": "service_unavailable"}})
        return paid_response(request_window(request))

    client, ledger, _ = setup(tmp_path, provider, ledger=Ledger(), context=backup_context(tmp_path))
    with client:
        assert client.post(URL, json=window("first-window")).status_code == 200
        assert client.post(URL, json=window("next-window")).status_code == 200

    assert [request.url.host for request in hits] == [
        "primary.example", "primary.example", "backup.example", "backup.example"]
    assert len(ledger.calls) == len(hits) == 4


def test_old_success_replays_without_clearing_new_primary_circuit(tmp_path, monkeypatch):
    backup(monkeypatch)
    hits = []

    def provider(request):
        hits.append(request)
        if request_window(request) == "open-primary" and request.url.host == "primary.example":
            return httpx.Response(503, json={"error": {"code": "service_unavailable"}})
        return paid_response(f"{request.url.host}:{request_window(request)}")

    client, ledger, _ = setup(tmp_path, provider, context=backup_context(tmp_path))
    with client:
        expected = client.post(URL, json=window("old-success")).json()
        client.post(URL, json=window("open-primary"))
        before = len(hits), len(ledger.calls)
        assert client.post(URL, json=window("old-success")).json() == expected
        assert (len(hits), len(ledger.calls)) == before
        client.post(URL, json=window("after-old-success-replay"))

    assert hits[-1].url.host == "backup.example"
    assert len(hits) == len(ledger.calls) == 5


def test_failed_response_replay_does_not_extend_primary_cooldown(tmp_path, monkeypatch):
    backup(monkeypatch)
    clock = [1000.0]
    monkeypatch.setattr("livecut.text_resilience.time.time", lambda: clock[0])
    hits = []

    def provider(request):
        hits.append(request)
        if request_window(request) == "old-failures" and request.url.host == "primary.example":
            return httpx.Response(503, json={"error": {"code": "service_unavailable"}})
        return paid_response(f"{request.url.host}:{request_window(request)}")

    first, ledger, context = setup(tmp_path, provider, context=backup_context(tmp_path))
    with first:
        expected = first.post(URL, json=window("old-failures")).json()
    clock[0] += 40
    replay, _, _ = setup(tmp_path, provider, ledger=ledger,
        context=restarted_context(context, tmp_path))
    with replay:
        assert replay.post(URL, json=window("old-failures")).json() == expected
        assert len(hits) == len(ledger.calls) == 3
        clock[0] += 21
        replay.post(URL, json=window("after-original-cooldown"))

    assert hits[-1].url.host == "primary.example"
    assert len(hits) == len(ledger.calls) == 4


def test_unknown_ordinals_replay_without_new_dispatch_after_restart(tmp_path, monkeypatch):
    backup(monkeypatch)
    hits = []

    def provider(request):
        hits.append(request)
        if request.url.host == "primary.example":
            raise httpx.ReadTimeout("synthetic unknown outcome", request=request)
        return paid_response(request_window(request))

    first, ledger, context = setup(tmp_path, provider, context=backup_context(tmp_path))
    with first:
        expected = first.post(URL, json=window("unknown-primary")).json()
    original_calls = {key: dict(value) for key, value in ledger.calls.items()}
    assert [value["status"] for value in original_calls.values()] == [
        "UNKNOWN_OUTCOME", "UNKNOWN_OUTCOME", "SUCCEEDED"]

    replay, _, _ = setup(tmp_path, provider, ledger=ledger,
        context=restarted_context(context, tmp_path))
    with replay:
        assert replay.post(URL, json=window("unknown-primary")).json() == expected
        assert len(hits) == len(ledger.calls) == 3
        assert ledger.calls == original_calls
        replay.post(URL, json=window("new-window-after-restart"))

    assert hits[-1].url.host == "backup.example"
    assert len(hits) == len(ledger.calls) == 4
    assert all(ledger.calls[key] == value for key, value in original_calls.items())


def test_primary_circuit_survives_new_worker_attempt_for_same_stage(tmp_path, monkeypatch):
    backup(monkeypatch)
    hits = []

    def provider(request):
        hits.append(request)
        if request.url.host == "primary.example":
            return httpx.Response(503, json={"error": {"code": "service_unavailable"}})
        return paid_response(request_window(request))

    first, ledger, context = setup(tmp_path, provider, context=backup_context(tmp_path))
    with first:
        first.post(URL, json=window("before-restart"))
    resumed, _, _ = setup(tmp_path, provider, ledger=ledger,
        context=restarted_context(context, tmp_path))
    with resumed:
        resumed.post(URL, json=window("after-restart"))

    assert [request.url.host for request in hits] == [
        "primary.example", "primary.example", "backup.example", "backup.example"]
    assert len(ledger.calls) == len(hits) == 4


def test_primary_circuit_does_not_leak_into_another_stage(tmp_path, monkeypatch):
    backup(monkeypatch)
    hits = []

    def provider(request):
        hits.append(request)
        if request_window(request) == "first-stage" and request.url.host == "primary.example":
            return httpx.Response(503, json={"error": {"code": "service_unavailable"}})
        return paid_response(f"{request.url.host}:{request_window(request)}")

    first, ledger, context = setup(tmp_path, provider, context=backup_context(tmp_path))
    with first:
        first.post(URL, json=window("first-stage"))
    other_context = replace(context, directory=tmp_path / "other-stage-attempt",
        lease=replace(context.lease, stage_id="stage-other", run_token="token-other"))
    other, _, _ = setup(tmp_path, provider, ledger=ledger, context=other_context)
    with other:
        other.post(URL, json=window("other-stage"))

    assert hits[-1].url.host == "primary.example"
    assert len(hits) == len(ledger.calls) == 4
    assert {value["stage_id"] for value in ledger.calls.values()} == {"stage-1", "stage-other"}


def test_backup_slot_zero_keeps_distinct_logical_inputs_after_reencoding(tmp_path, monkeypatch):
    backup(monkeypatch)
    hits = []

    def provider(request):
        hits.append(request)
        if request.url.host == "primary.example":
            return httpx.Response(503, json={"error": {"code": "service_unavailable"}})
        return paid_response(f"paid-call-{len(hits)}")

    client, ledger, _ = setup(tmp_path, provider, context=backup_context(tmp_path))
    first_body = window("same-visible-prompt", max_tokens=100)
    second_body = window("same-visible-prompt", max_tokens=200)
    with client:
        client.post(URL, json=window("open-primary"))
        first = client.post(URL, json=first_body).json()
        second = client.post(URL, json=second_body).json()
        before = len(hits), len(ledger.calls)
        assert client.post(URL, json=first_body).json() == first
        assert (len(hits), len(ledger.calls)) == before

    assert first != second
    assert len(hits) == len(ledger.calls) == 5
    assert hits[-2].url.host == hits[-1].url.host == "backup.example"
    assert json.loads(hits[-2].content) == json.loads(hits[-1].content)
    calls = list(ledger.calls.values())
    assert calls[-2]["request_id"] != calls[-1]["request_id"]
    assert calls[-2]["input_hash"] != calls[-1]["input_hash"]


def test_legacy_paid_success_without_policy_replays_while_primary_is_open(tmp_path, monkeypatch):
    backup(monkeypatch)
    hits = []

    def provider(request):
        hits.append(request)
        if request_window(request) == "open-primary" and request.url.host == "primary.example":
            return httpx.Response(503, json={"error": {"code": "service_unavailable"}})
        return paid_response(f"{request.url.host}:{request_window(request)}")

    first, ledger, context = setup(tmp_path, provider, context=backup_context(tmp_path))
    with first:
        expected = first.post(URL, json=window("legacy-paid-success")).json()
        # Simulate a genuine pre-recovery ledger: the response and SUCCEEDED
        # reservation existed before text retry policy files were introduced.
        # Remove only this synthetic policy, leaving its recorded response and
        # paid invocation unchanged. This is not a damaged-policy fallback.
        policy_directory = (tmp_path / "jobs" / context.lease.job_id / "stages"
            / context.lease.stage_id / "text-retry-policies")
        old_policies = list(policy_directory.glob("*.json"))
        assert len(old_policies) == 1
        old_policies[0].unlink()
        first.post(URL, json=window("open-primary"))
    original_calls = {key: dict(value) for key, value in ledger.calls.items()}
    assert len(hits) == len(original_calls) == 4

    resumed, _, _ = setup(tmp_path, provider, ledger=ledger,
        context=restarted_context(context, tmp_path))
    with resumed:
        actual = resumed.post(URL, json=window("legacy-paid-success")).json()
        assert len(hits) == len(ledger.calls) == 4
        assert actual == expected
        assert ledger.calls == original_calls
        resumed.post(URL, json=window("after-legacy-success-replay"))

    assert hits[-1].url.host == "backup.example"
    assert len(hits) == len(ledger.calls) == 5
    assert all(ledger.calls[key] == value for key, value in original_calls.items())


def test_two_open_providers_are_skipped_with_independent_backup_credentials(tmp_path, monkeypatch):
    backup(monkeypatch)
    monkeypatch.setenv("LIVECUT_BACKUP_TWO_TEXT_KEY", "synthetic-backup-two-key")
    monkeypatch.setenv("LIVECUT_TEXT_FALLBACKS_JSON", json.dumps([
        {"name": "backup", "base_url": "https://backup.example/v1", "api_style": "openai",
         "model": "backup-model", "key_env": "LIVECUT_BACKUP_TEXT_KEY"},
        {"name": "backup-two", "base_url": "https://backup-two.example/v1", "api_style": "openai",
         "model": "backup-two-model", "key_env": "LIVECUT_BACKUP_TWO_TEXT_KEY"},
    ]))
    context = backup_context(tmp_path)
    context = replace(context, lease=replace(context.lease, config={
        **context.lease.config, "text_fallback_models": ["backup-model", "backup-two-model"]}))
    hits = []

    def provider(request):
        hits.append(request)
        if request.url.host != "backup-two.example":
            return httpx.Response(401, json={"error": {"code": "invalid_api_key"}})
        return paid_response(request_window(request))

    headers = {"Authorization": "Bearer synthetic-primary-key",
               "X-Primary-Only": "synthetic-private-header"}
    client, ledger, _ = setup(tmp_path, provider, ledger=Ledger(), context=context)
    with client:
        assert client.post(URL, json=window("open-first-two"), headers=headers).status_code == 200
        assert client.post(URL, json=window("skip-first-two"), headers=headers).status_code == 200

    assert [request.url.host for request in hits] == [
        "primary.example", "backup.example", "backup-two.example", "backup-two.example"]
    assert len(hits) == len(ledger.calls) == 4
    assert [value["status"] for value in ledger.calls.values()] == [
        "FAILED", "FAILED", "SUCCEEDED", "SUCCEEDED"]
    expected_keys = {"primary.example": "Bearer synthetic-primary-key",
                     "backup.example": "Bearer synthetic-backup-key",
                     "backup-two.example": "Bearer synthetic-backup-two-key"}
    for request in hits:
        assert request.headers["Authorization"] == expected_keys[request.url.host]
        if request.url.host != "primary.example":
            assert "X-Primary-Only" not in request.headers
            assert "synthetic-primary-key" not in str(request.headers)
