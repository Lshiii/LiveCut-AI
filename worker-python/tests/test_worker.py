"""Protocol tests use fake computation; they do not establish model quality."""

import threading
import time
from datetime import datetime, timedelta, timezone

import pytest
from livecut.worker import (
    Lease,
    LeaseGuard,
    StageWorker,
    WorkerError,
    shared_path,
)
from livecut.worker_client import WorkerClientError


def claim(**overrides):
    now = datetime.now(timezone.utc)
    value = {
        "outcome": "CLAIMED", "job_id": "job-1", "stage_id": "stage-1",
        "video_id": "video-1", "generation": 1,
        "stage_type": "MEDIA", "attempt": 1, "run_token": "token-1",
        "lease_until": (now + timedelta(seconds=25)).isoformat(),
        "hard_deadline_at": (now + timedelta(minutes=5)).isoformat(),
        "source_key": "videos/video.mp4", "source_hash": "a" * 64,
        "config": {}, "artifacts": [],
    }
    return {**value, **overrides}


class FakeClient:
    def __init__(self):
        self.claims = []
        self.callbacks = []
        self.fail_claim_once = False
        self.fail_complete_once = False
        self.fail_heartbeat = False
        self.stale_complete = False
        self.reply = claim()

    def ready(self, **kwargs):
        return {"items": [{"id": "stage-1", "stage_type": "MEDIA"}], "limit": 1}

    def claim(self, stage_id, worker_id, claim_request_id):
        self.claims.append((stage_id, worker_id, claim_request_id))
        if self.fail_claim_once:
            self.fail_claim_once = False
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
        return self.reply

    def callback(self, action, stage_id, payload):
        self.callbacks.append((action, stage_id, payload))
        if action == "heartbeat" and self.fail_heartbeat:
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
        if action == "complete" and self.stale_complete:
            raise WorkerClientError("STALE_ATTEMPT")
        if action == "complete" and self.fail_complete_once:
            self.fail_complete_once = False
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
        return {"outcome": "ACCEPTED", "stage": self.reply}


def fake_executor(calls):
    def execute(context):
        calls.append(context.lease.stage_id)
        return {"schema_version": 1, "job_id": context.lease.job_id,
                "video_id": context.lease.video_id, "analysis_generation": context.lease.generation,
                "stage_id": context.lease.stage_id, "stage_type": context.lease.stage_type,
                "attempt": context.lease.attempt, "run_token": context.lease.run_token,
                "input_sha256": context.lease.source_hash, "artifacts": []}
    return execute


def worker(tmp_path, client, calls, **kwargs):
    return StageWorker(client, tmp_path / "storage", tmp_path / "journal", "worker-1",
                       fake_executor(calls), **kwargs)


def test_claim_response_loss_reuses_exact_target_and_request_id(tmp_path):
    client, calls = FakeClient(), []
    client.fail_claim_once = True
    runner = worker(tmp_path, client, calls)
    with pytest.raises(WorkerClientError):
        runner.run_once()
    assert calls == []
    assert runner.run_once() == "completed"
    assert client.claims[0] == client.claims[1]
    assert calls == ["stage-1"]


def test_complete_response_loss_replays_callback_after_restart_without_computing(tmp_path):
    client, calls = FakeClient(), []
    client.fail_complete_once = True
    with pytest.raises(WorkerClientError):
        worker(tmp_path, client, calls).run_once()
    assert calls == ["stage-1"]
    assert worker(tmp_path, client, calls).run_once() == "callback_replayed"
    completes = [entry for entry in client.callbacks if entry[0] == "complete"]
    assert completes[0] == completes[1]
    assert calls == ["stage-1"]
    assert len(client.claims) == 1


def test_old_token_cannot_publish_and_rejected_callback_remains_local(tmp_path):
    client, calls = FakeClient(), []
    client.stale_complete = True
    with pytest.raises(WorkerClientError, match="STALE_ATTEMPT"):
        worker(tmp_path, client, calls).run_once()
    assert (tmp_path / "journal/rejected-callback.json").is_file()
    assert not (tmp_path / "journal/pending-callback.json").exists()
    assert calls == ["stage-1"]


def test_heartbeat_failure_stops_new_work_and_reports_fenced_failure(tmp_path):
    client = FakeClient()
    client.fail_heartbeat = True
    successful_checks = []

    def execute(context):
        for _ in range(200):
            context.guard.check()
            successful_checks.append(1)
            time.sleep(0.005)
        raise AssertionError("heartbeat should invalidate guard")

    runner = StageWorker(client, tmp_path / "storage", tmp_path / "journal", "worker-1",
                         execute, heartbeat_seconds=0.01)
    assert runner.run_once() == "failed"
    assert 0 < len(successful_checks) < 200
    failures = [entry for entry in client.callbacks if entry[0] == "fail"]
    assert failures[0][2]["error_code"] == "LEASE_LOST"
    assert not any(entry[0] == "complete" for entry in client.callbacks)


def test_hard_deadline_prevents_executor(tmp_path):
    client, calls = FakeClient(), []
    past = datetime.now(timezone.utc) - timedelta(seconds=1)
    client.reply = claim(hard_deadline_at=past.isoformat())
    assert worker(tmp_path, client, calls).run_once() == "failed"
    assert calls == []
    assert client.callbacks[-1][2]["error_code"] == "HARD_DEADLINE_EXCEEDED"


def test_stop_does_not_claim(tmp_path):
    client, calls = FakeClient(), []
    stop = threading.Event()
    stop.set()
    assert worker(tmp_path, client, calls, stop=stop).run_once() == "stopped"
    assert client.claims == []


@pytest.mark.parametrize("key", ["../escape", "/root/file", "C:/secret", "a\\b", "a/../b", "a//b", "./x"])
def test_storage_key_rejects_traversal(tmp_path, key):
    with pytest.raises(WorkerError, match="UNSAFE_STORAGE_KEY"):
        shared_path(tmp_path, key)


def test_storage_key_rejects_symlink_escape(tmp_path):
    root, outside = tmp_path / "root", tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    try:
        (root / "link").symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("platform does not allow symlinks")
    with pytest.raises(WorkerError, match="UNSAFE_STORAGE_KEY"):
        shared_path(root, "link/secret")


def test_guard_rejects_deadline_extension_and_stops(tmp_path):
    value = claim()
    guard = LeaseGuard(Lease.parse(value), threading.Event())
    changed = claim(hard_deadline_at=(datetime.now(timezone.utc) + timedelta(hours=3)).isoformat())
    with pytest.raises(WorkerError, match="INVALID_HEARTBEAT"):
        guard.renew({"stage": changed})
    with pytest.raises(WorkerError, match="LEASE_LOST"):
        guard.check()


def test_claim_identity_cannot_change(tmp_path):
    client, calls = FakeClient(), []
    client.reply = claim(stage_id="stage-other")
    with pytest.raises(WorkerError, match="CLAIM_TARGET_CHANGED"):
        worker(tmp_path, client, calls).run_once()
    assert calls == []


def test_media_only_filter_refuses_pending_claim_for_provider_stage(tmp_path):
    client, calls = FakeClient(), []
    client.reply = claim(stage_type="ASR")
    with pytest.raises(WorkerError, match="CLAIM_STAGE_TYPE_CHANGED"):
        worker(tmp_path, client, calls).run_once(stage_type="MEDIA")
    assert calls == []


def test_unsafe_run_token_is_rejected():
    with pytest.raises(WorkerError, match="INVALID_CLAIM"):
        Lease.parse(claim(run_token="../other"))


@pytest.mark.parametrize("field,value", [("video_id", None), ("generation", 0), ("generation", True)])
def test_claim_requires_video_and_analysis_generation(field, value):
    with pytest.raises(WorkerError, match="INVALID_CLAIM"):
        Lease.parse(claim(**{field: value}))


def test_invalid_completion_ack_keeps_callback_for_retry_without_recompute(tmp_path):
    client, calls = FakeClient(), []
    original = client.callback
    client.callback = lambda action, stage_id, payload: {} if action == "complete" else original(
        action, stage_id, payload)
    runner = worker(tmp_path, client, calls)
    with pytest.raises(WorkerClientError, match="INVALID_JAVA_RESPONSE"):
        runner.run_once()
    assert (tmp_path / "journal/pending-callback.json").exists()
    client.callback = original
    assert runner.run_once() == "callback_replayed"
    assert calls == ["stage-1"]
