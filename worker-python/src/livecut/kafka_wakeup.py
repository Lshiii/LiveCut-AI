"""Kafka carries wakeups only; Java ready/claim remains the execution authority.

The consumer owns all Kafka APIs on one thread, including offset commits. A
single pending bit bounds memory while computation is busy. Committing means
the notification was observed, never that a business stage was completed.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import threading
import time
from datetime import datetime
from typing import Callable
from uuid import UUID

STAGE_TYPES = frozenset(("MEDIA", "ASR", "SEGMENT", "CAPTION_PROOFREAD", "ANCHOR_CLIP",
                        "AGENT_VISUAL", "AGENT_VISUAL_FOCUS", "AGENT_EVIDENCE", "TIMELINE",
                        "EXPORT_CLIP"))
FIELDS = frozenset(("schema_version", "event_id", "event_type", "stage_id", "job_id",
                    "stage_type", "required_worker_protocol", "reason", "occurred_at"))


class KafkaWakeupError(Exception):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


def _object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate")
        value[key] = item
    return value


def validate_wakeup(raw: bytes, *, key: bytes | None = None) -> dict:
    """Reject poison messages without logging their payload or using their IDs."""
    try:
        if not isinstance(raw, bytes) or not 1 <= len(raw) <= 8192:
            raise ValueError("size")
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError("constant")))
        if not isinstance(value, dict) or value.keys() != FIELDS:
            raise ValueError("shape")
        if (value["schema_version"] != "1.0" or value["event_type"] != "STAGE_WAKEUP"
                or value["stage_type"] not in STAGE_TYPES
                or type(value["required_worker_protocol"]) is not int
                or value["required_worker_protocol"] not in {1, 2, 3, 4}
                or value["reason"] not in {"READY", "CAPACITY_RELEASED"}):
            raise ValueError("field")
        for field in ("event_id", "stage_id", "job_id"):
            if not isinstance(value[field], str) or str(UUID(value[field])) != value[field]:
                raise ValueError("id")
        stamp = value["occurred_at"]
        if (not isinstance(stamp, str) or len(stamp) > 40 or not stamp.endswith("Z")
                or re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z", stamp) is None
                or datetime.fromisoformat(stamp[:-1] + "+00:00").utcoffset().total_seconds() != 0):
            raise ValueError("time")
        if key is not None and key != value["job_id"].encode("ascii"):
            raise ValueError("key")
        return value
    except (ValueError, TypeError, UnicodeError, RecursionError, AttributeError):
        raise KafkaWakeupError("INVALID_KAFKA_WAKEUP") from None


class KafkaWakeup:
    def __init__(self, bootstrap_servers: str, worker_id: str, *,
                 topic: str = "livecut.stage-wakeup.v1", reconnect_wait_seconds: float = 180,
                 observer: Callable[[dict], None] | None = None,
                 consumer_factory: Callable | None = None) -> None:
        if (not isinstance(bootstrap_servers, str) or not bootstrap_servers.strip()
                or len(bootstrap_servers) > 2048
                or any(c in bootstrap_servers for c in ("\n", "\r", "@", "://"))
                or not isinstance(worker_id, str) or not worker_id
                or not isinstance(topic, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,249}", topic)
                or topic in {".", ".."}
                or type(reconnect_wait_seconds) not in (int, float)
                or not math.isfinite(reconnect_wait_seconds)
                or not 0 <= reconnect_wait_seconds <= 3600):
            raise KafkaWakeupError("INVALID_KAFKA_CONFIGURATION")
        self.bootstrap_servers, self.topic = bootstrap_servers, topic
        # Each executor receives every wakeup, including those needed by another
        # capability. A shared group could commit a message on the wrong worker.
        suffix = hashlib.sha256(worker_id.encode("utf-8")).hexdigest()[:32]
        self.group_id = "livecut-worker-" + suffix
        self.reconnect_wait_seconds = reconnect_wait_seconds
        self.observer, self.consumer_factory = observer, consumer_factory
        self._condition = threading.Condition()
        self._pending = False
        self._ready = threading.Event()
        self._closed = threading.Event()
        self._thread: threading.Thread | None = None
        self._error: KafkaWakeupError | None = None

    def _observe(self, status: str, **fields) -> None:
        if self.observer is not None:
            try:
                self.observer({"status": status, "dispatch_mode": "kafka", **fields})
            except Exception:
                pass

    def start(self, stop: threading.Event, guard: Callable[[], None]) -> bool:
        if self._thread is not None or self._closed.is_set():
            raise KafkaWakeupError("KAFKA_CONSUMER_ALREADY_STARTED")
        if self.consumer_factory is None:
            try:
                from kafka import KafkaConsumer
            except ImportError:
                raise KafkaWakeupError("KAFKA_DEPENDENCY_MISSING") from None
            self.consumer_factory = KafkaConsumer
        self._thread = threading.Thread(target=self._consume, name="kafka-stage-wakeup", daemon=True)
        self._thread.start()
        while not self._ready.wait(.05):
            self.check()
            guard()
            if stop.is_set():
                return False
        self.check()
        self._observe("dispatch-subscribed", group_id=self.group_id)
        return not stop.is_set()

    def check(self) -> None:
        if self._error is not None:
            raise self._error

    def wait(self, seconds: float, stop: threading.Event, guard: Callable[[], None]) -> bool:
        if (type(seconds) not in (int, float) or not math.isfinite(seconds)
                or not 0 <= seconds <= 3600):
            raise KafkaWakeupError("INVALID_KAFKA_WAIT_INTERVAL")
        deadline = time.monotonic() + seconds
        with self._condition:
            while not stop.is_set() and not self._closed.is_set():
                self.check()
                guard()
                if self._pending:
                    self._pending = False
                    return True
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    self._observe("dispatch-reconcile")
                    return False
                self._condition.wait(min(.05, remaining))
        return False

    def _consume(self) -> None:
        consumer = None
        outage_started = time.monotonic()
        last_health = outage_started
        first_attempt = True
        try:
            while not self._closed.is_set():
                try:
                    if (not first_attempt and outage_started is not None
                            and time.monotonic() - outage_started >= self.reconnect_wait_seconds):
                        raise KafkaWakeupError("KAFKA_RECONNECT_EXHAUSTED")
                    first_attempt = False
                    if consumer is None:
                        consumer = self.consumer_factory(
                            self.topic, bootstrap_servers=self.bootstrap_servers,
                            group_id=self.group_id, client_id=self.group_id,
                            enable_auto_commit=False, auto_offset_reset="latest",
                            allow_auto_create_topics=False,
                            api_version_auto_timeout_ms=2000, request_timeout_ms=15000,
                            session_timeout_ms=10000, heartbeat_interval_ms=3000,
                            max_poll_records=100, fetch_max_bytes=1048576,
                            max_partition_fetch_bytes=1048576)
                    batch = consumer.poll(timeout_ms=500, max_records=100)
                    # Metadata is a bounded broker health probe, not a task scan.
                    # poll may return empty forever while an assigned broker is down.
                    now = time.monotonic()
                    if now - last_health >= 10:
                        if self.topic not in consumer.topics():
                            raise KafkaWakeupError("KAFKA_TOPIC_UNAVAILABLE")
                        last_health = now
                    assigned = consumer.assignment()
                    # A poll timeout can return after group assignment but
                    # before latest/committed fetch positions are initialized.
                    # Draining READY before that boundary could miss a wakeup
                    # appended between the drain and the eventual latest reset.
                    positions_ready = bool(assigned) and all(
                        type(consumer.position(partition, timeout_ms=0)) is int
                        for partition in assigned)
                    if positions_ready:
                        self._ready.set()
                    elif outage_started is None:
                        outage_started = now
                    if batch:
                        wake = False
                        for records in batch.values():
                            for record in records:
                                try:
                                    event = validate_wakeup(record.value, key=record.key)
                                    wake = True
                                    self._observe("dispatch-notified", event_id=event["event_id"],
                                                  stage_id=event["stage_id"], stage_type=event["stage_type"],
                                                  reason=event["reason"])
                                except KafkaWakeupError:
                                    self._observe("dispatch-message-rejected", error_code="INVALID_KAFKA_WAKEUP")
                        if wake:
                            with self._condition:
                                self._pending = True
                                self._condition.notify_all()
                        # A crash after the signal and before commit replays a
                        # harmless wakeup. A crash after commit uses startup drain.
                        consumer.commit(timeout_ms=1000)
                    if positions_ready:
                        outage_started = None
                    if (outage_started is not None
                            and now - outage_started >= self.reconnect_wait_seconds):
                        raise KafkaWakeupError("KAFKA_RECONNECT_EXHAUSTED")
                except Exception:
                    if self._closed.is_set():
                        break
                    now = time.monotonic()
                    if outage_started is None:
                        outage_started = now
                    if now - outage_started >= self.reconnect_wait_seconds:
                        self._error = KafkaWakeupError("KAFKA_RECONNECT_EXHAUSTED")
                        with self._condition:
                            self._condition.notify_all()
                        return
                    self._observe("dispatch-reconnecting", error_code="KAFKA_UNAVAILABLE")
                    if consumer is not None:
                        try:
                            consumer.close(autocommit=False, timeout_ms=1000)
                        except Exception:
                            pass
                        consumer = None
                    remaining = self.reconnect_wait_seconds - (now - outage_started)
                    self._closed.wait(min(1, max(.001, self.reconnect_wait_seconds / 4), remaining))
        finally:
            if consumer is not None:
                try:
                    consumer.close(autocommit=False, timeout_ms=1000)
                except Exception:
                    pass

    def close(self) -> None:
        self._closed.set()
        with self._condition:
            self._condition.notify_all()
        if self._thread is not None:
            # A bounded metadata health probe may own the consumer for up to
            # request_timeout_ms; close must run on that same owner thread.
            self._thread.join(timeout=18)
            if self._thread.is_alive():
                raise KafkaWakeupError("KAFKA_CONSUMER_STOP_TIMEOUT")
