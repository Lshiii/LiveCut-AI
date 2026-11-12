import json
import queue
import threading
import time
from contextlib import contextmanager
from types import SimpleNamespace
from uuid import uuid4

import pytest
from livecut.cli import main
from livecut.kafka_wakeup import KafkaWakeup, KafkaWakeupError, validate_wakeup
from livecut.worker import StageWorker


def event(**changes):
    return {"schema_version": "1.0", "event_id": str(uuid4()), "event_type": "STAGE_WAKEUP",
            "stage_id": str(uuid4()), "job_id": str(uuid4()), "stage_type": "MEDIA",
            "required_worker_protocol": 1, "reason": "READY",
            "occurred_at": "2026-11-12T00:00:00.123456Z", **changes}


def encoded(value):
    return json.dumps(value).encode()


class Consumer:
    def __init__(self):
        self.inbox = queue.Queue()
        self.commits = 0
        self.closed = False
        self.owner_threads = set()
        self.assigned = True
        self.position_ready = True

    def poll(self, **kwargs):
        self.owner_threads.add(threading.get_ident())
        try:
            value = self.inbox.get(timeout=.01)
        except queue.Empty:
            return {}
        if isinstance(value, Exception):
            raise value
        return {0: value}

    def assignment(self):
        return {0} if self.assigned else set()

    def topics(self):
        return {"livecut.stage-wakeup.v1"}

    def position(self, partition, **kwargs):
        return 0 if self.position_ready else None

    def commit(self, **kwargs):
        self.owner_threads.add(threading.get_ident())
        self.commits += 1

    def close(self, **kwargs):
        self.owner_threads.add(threading.get_ident())
        assert kwargs["autocommit"] is False
        self.closed = True


def until(predicate):
    deadline = time.monotonic() + 2
    while not predicate():
        assert time.monotonic() < deadline
        time.sleep(.005)


def record(value):
    return SimpleNamespace(value=encoded(value), key=value["job_id"].encode())


def test_strict_event_and_key():
    value = event()
    assert validate_wakeup(encoded(value), key=value["job_id"].encode()) == value
    with pytest.raises(KafkaWakeupError, match="INVALID_KAFKA_WAKEUP"):
        validate_wakeup(encoded(value), key=b"another-job")


@pytest.mark.parametrize("changes", [
    {"extra": 1}, {"schema_version": "2.0"}, {"event_type": "EXECUTE"},
    {"stage_id": "not-a-uuid"}, {"event_id": str(uuid4()).upper()},
    {"required_worker_protocol": True}, {"required_worker_protocol": 5},
    {"reason": "CANCEL"}, {"stage_type": "UNKNOWN"}, {"stage_type": []},
    {"occurred_at": "2026-11-12T00:00:00+08:00"},
    {"occurred_at": "2026-11-12 00:00:00Z"}, {"occurred_at": "2026-02-31T00:00:00Z"},
])
def test_invalid_event_rejected(changes):
    with pytest.raises(KafkaWakeupError, match="INVALID_KAFKA_WAKEUP"):
        validate_wakeup(encoded(event(**changes)))


@pytest.mark.parametrize("raw", [b"{}", b"[]", b"\xff", b"x" * 8193,
                                 b'{"a":1,"a":2}', b'{"a":NaN}', b"[" * 2000])
def test_poison_payload_rejected(raw):
    with pytest.raises(KafkaWakeupError, match="INVALID_KAFKA_WAKEUP"):
        validate_wakeup(raw)


def test_consumer_single_thread_commit_after_signal_coalesces_and_does_not_filter_capabilities():
    consumer, notices, configured = Consumer(), [], {}
    stop = threading.Event()

    def factory(*topics, **kwargs):
        configured.update(kwargs)
        assert topics == ("livecut.stage-wakeup.v1",)
        return consumer

    wake = KafkaWakeup("localhost:19092", "media-worker", observer=notices.append,
                       consumer_factory=factory)
    try:
        assert wake.start(stop, lambda: None)
        first = event(stage_type="AGENT_EVIDENCE", required_worker_protocol=4,
                      reason="CAPACITY_RELEASED")
        poison = SimpleNamespace(value=b"not json", key=None)
        consumer.inbox.put([record(first), record(first), poison])
        until(lambda: consumer.commits == 1)
        assert wake.wait(.1, stop, lambda: None)
        assert not wake.wait(.02, stop, lambda: None)
        observed = [row for row in notices if row["status"] == "dispatch-notified"]
        assert len(observed) == 2 and observed[0]["stage_id"] == first["stage_id"]
        assert any(row["status"] == "dispatch-message-rejected" for row in notices)
        assert configured["enable_auto_commit"] is False
        assert configured["allow_auto_create_topics"] is False
        assert configured["auto_offset_reset"] == "latest"
        assert configured["request_timeout_ms"] > configured["session_timeout_ms"] >= 6000
    finally:
        wake.close()
    assert consumer.closed and len(consumer.owner_threads) == 1
    assert threading.get_ident() not in consumer.owner_threads


def test_consumer_start_waits_for_assignment_before_ready_probe():
    consumer, stop = Consumer(), threading.Event()
    consumer.assigned = False
    wake = KafkaWakeup("localhost:19092", "w", consumer_factory=lambda *a, **k: consumer)
    started = threading.Event()
    thread = threading.Thread(target=lambda: (wake.start(stop, lambda: None), started.set()))
    thread.start()
    try:
        assert not started.wait(.05)
        consumer.assigned = True
        assert started.wait(1)
    finally:
        stop.set()
        thread.join(1)
        wake.close()


def test_assignment_without_fetch_position_cannot_start_ready_drain():
    consumer, stop = Consumer(), threading.Event()
    consumer.position_ready = False
    wake = KafkaWakeup("localhost:19092", "w", consumer_factory=lambda *a, **k: consumer)
    started = threading.Event()
    thread = threading.Thread(target=lambda: (wake.start(stop, lambda: None), started.set()))
    thread.start()
    try:
        assert not started.wait(.05)
        consumer.position_ready = True
        assert started.wait(1)
    finally:
        stop.set()
        thread.join(1)
        wake.close()


def test_wait_consumes_before_subsequent_query_and_preserves_next_signal():
    wake, stop = KafkaWakeup("localhost:19092", "w"), threading.Event()
    with wake._condition:
        wake._pending = True
    assert wake.wait(.1, stop, lambda: None)
    # This arrives after the first wake was consumed and before ready is read.
    with wake._condition:
        wake._pending = True
    assert wake.wait(.1, stop, lambda: None)
    assert not wake.wait(.01, stop, lambda: None)


def test_wait_stop_and_presence_guard_interrupt():
    wake, stop = KafkaWakeup("localhost:19092", "w"), threading.Event()
    stop.set()
    assert not wake.wait(3600, stop, lambda: None)
    stop.clear()

    def guard():
        raise RuntimeError("fenced")

    with pytest.raises(RuntimeError, match="fenced"):
        wake.wait(3600, stop, guard)


def test_closed_adapter_cannot_be_restarted_or_block_waiters():
    wake = KafkaWakeup("localhost:19092", "w")
    wake.close()
    with pytest.raises(KafkaWakeupError, match="KAFKA_CONSUMER_ALREADY_STARTED"):
        wake.start(threading.Event(), lambda: None)
    assert not wake.wait(3600, threading.Event(), lambda: None)


@pytest.mark.parametrize("seconds", [float("nan"), float("inf"), -1, 3601, True, "10"])
def test_wait_interval_is_bounded(seconds):
    wake = KafkaWakeup("localhost:19092", "w")
    with pytest.raises(KafkaWakeupError, match="INVALID_KAFKA_WAIT_INTERVAL"):
        wake.wait(seconds, threading.Event(), lambda: None)


def test_bootstrap_failure_bounded_and_sanitized():
    def factory(*args, **kwargs):
        raise RuntimeError("private broker detail")
    wake = KafkaWakeup("localhost:19092", "w", consumer_factory=factory, reconnect_wait_seconds=0)
    try:
        with pytest.raises(KafkaWakeupError, match="KAFKA_RECONNECT_EXHAUSTED"):
            wake.start(threading.Event(), lambda: None)
        assert "private" not in str(wake._error)
    finally:
        wake.close()


def test_transient_bootstrap_failure_reconnects_without_poll_fallback():
    consumer, notices = Consumer(), []
    attempts = 0

    def factory(*args, **kwargs):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise RuntimeError("unavailable")
        return consumer

    wake = KafkaWakeup("localhost:19092", "w", consumer_factory=factory,
                       reconnect_wait_seconds=.2, observer=notices.append)
    try:
        assert wake.start(threading.Event(), lambda: None)
        assert attempts == 2
        assert any(row["status"] == "dispatch-reconnecting" for row in notices)
    finally:
        wake.close()


def test_persistent_commit_failures_do_not_reset_recovery_budget():
    class FailingCommit(Consumer):
        def poll(self, **kwargs):
            return {0: [record(event())]}

        def commit(self, **kwargs):
            raise RuntimeError("commit failed")

    wake = KafkaWakeup("localhost:19092", "w", consumer_factory=lambda *a, **k: FailingCommit(),
                       reconnect_wait_seconds=.05)
    try:
        try:
            wake.start(threading.Event(), lambda: None)
        except KafkaWakeupError:
            pass
        until(lambda: wake._error is not None)
        with pytest.raises(KafkaWakeupError, match="KAFKA_RECONNECT_EXHAUSTED"):
            wake.check()
    finally:
        wake.close()


def test_stop_interrupts_assignment_wait_and_close_is_owned_by_consumer_thread():
    consumer, stop = Consumer(), threading.Event()
    consumer.assigned = False
    wake = KafkaWakeup("localhost:19092", "w", consumer_factory=lambda *a, **k: consumer)
    result = []
    thread = threading.Thread(target=lambda: result.append(wake.start(stop, lambda: None)))
    thread.start()
    time.sleep(.02)
    stop.set()
    thread.join(1)
    wake.close()
    assert result == [False] and consumer.closed


@pytest.mark.parametrize("kwargs", [
    {"bootstrap_servers": ""}, {"bootstrap_servers": "user:secret@localhost"},
    {"topic": "../topic"}, {"topic": ".."}, {"reconnect_wait_seconds": float("nan")},
    {"reconnect_wait_seconds": True},
])
def test_configuration_rejected(kwargs):
    with pytest.raises(KafkaWakeupError, match="INVALID_KAFKA_CONFIGURATION"):
        KafkaWakeup(**{"bootstrap_servers": "localhost:19092", "worker_id": "w", **kwargs})


def test_worker_waits_for_kafka_not_poll_interval_and_drains_completed_work(tmp_path, monkeypatch):
    worker = StageWorker(None, tmp_path / "storage", tmp_path / "journal", "w", None,
                         registry_enabled=False)
    calls = []

    @contextmanager
    def registered(*args, **kwargs):
        calls.append("registered")
        yield True

    monkeypatch.setattr(worker, "_registered", registered)
    monkeypatch.setattr(worker, "_recover_local_result", lambda: None)
    monkeypatch.setattr(worker, "_pending_callback", lambda: None)
    monkeypatch.setattr(worker, "_archive_abandoned_claim", lambda: None)
    statuses = iter(("idle", "completed", "idle"))

    def run_once(**kwargs):
        calls.append("ready")
        return next(statuses)

    monkeypatch.setattr(worker, "_run_once", run_once)
    monkeypatch.setattr(worker, "_idle_wait", lambda _: pytest.fail("poll fallback"))

    class Wake:
        def start(self, stop, guard):
            calls.append("subscribed")
            return True

        def check(self):
            pass

        def wait(self, seconds, stop, guard):
            assert seconds == 600
            calls.append("wait")
            guard()
            if calls.count("wait") == 2:
                stop.set()

        def close(self):
            calls.append("closed")

    worker.run(poll_seconds=.001, wakeup=Wake(), reconcile_seconds=600)
    assert calls == ["subscribed", "registered", "ready", "wait", "ready", "ready", "wait", "closed"]


def test_cli_rejects_once_kafka_without_creating_worker_or_network(tmp_path, capsys):
    args = ["worker", "--storage-root", str(tmp_path), "--journal-dir", str(tmp_path / "j"),
            "--dispatch-mode", "kafka", "--once"]
    assert main(args) == 2
    assert json.loads(capsys.readouterr().out)["error_code"] == "KAFKA_REQUIRES_CONTINUOUS_WORKER"


@pytest.mark.parametrize("value", ["nan", "9", "3601", "invalid"])
def test_cli_rejects_invalid_reconcile_before_network(tmp_path, capsys, value):
    assert main(["worker", "--storage-root", str(tmp_path), "--journal-dir", str(tmp_path / "j"),
                 "--dispatch-mode", "kafka", "--reconcile-seconds", value]) == 2
    assert json.loads(capsys.readouterr().out)["error_code"] == "INVALID_RECONCILE_INTERVAL"


def test_cli_kafka_environment_reaches_run_without_altering_poll_default(tmp_path, monkeypatch):
    configured = {}

    class Client:
        def __init__(self, *args):
            pass

        def close(self):
            configured["closed"] = True

    class Runner:
        def __init__(self, *args, **kwargs):
            pass

        def run(self, **kwargs):
            configured.update(kwargs)

    monkeypatch.setenv("LIVECUT_DISPATCH_MODE", "kafka")
    monkeypatch.setenv("LIVECUT_KAFKA_BOOTSTRAP_SERVERS", "test-broker:19092")
    monkeypatch.setenv("LIVECUT_KAFKA_TOPIC", "test.wakeup")
    monkeypatch.setenv("LIVECUT_KAFKA_RECONCILE_SECONDS", "600")
    monkeypatch.setattr("livecut.worker_client.WorkerClient", Client)
    monkeypatch.setattr("livecut.worker.StageWorker", Runner)
    assert main(["worker", "--storage-root", str(tmp_path), "--journal-dir", str(tmp_path / "j")]) == 0
    assert configured["wakeup"].bootstrap_servers == "test-broker:19092"
    assert configured["wakeup"].topic == "test.wakeup"
    assert configured["reconcile_seconds"] == 600
    assert configured["closed"]
