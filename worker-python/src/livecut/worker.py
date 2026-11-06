"""Single-slot computing Worker. Java alone changes business stage state.

The local journal records uncertain claims and pending callbacks. A lost
completion response replays the same callback without rerunning computation.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import tempfile
import threading
import time
import traceback
from contextlib import contextmanager, nullcontext
from copy import deepcopy
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Callable, Iterator, Protocol
from uuid import uuid4

import httpx

from .asr import transcribe_file
from .contracts import validate_analysis_result
from .media import extract_audio, probe_media
from .ocr import ocr_model_id
from .ocr_cache import cached_observe_video_ocr
from .segmentation import discover_products
from .topic_contracts import validate_topic_result
from .topics import discover_topics
from .worker_client import WorkerClient, WorkerClientError

STAGES = ("MEDIA", "ASR", "SEGMENT", "TIMELINE", "EXPORT_CLIP")


class WorkerError(Exception):
    def __init__(self, code: str, *, retryable: bool = False) -> None:
        super().__init__(code)
        self.code, self.retryable = code, retryable


class _FencedCallbackError(WorkerClientError):
    """A pending callback was durably archived after an explicit fence."""

    def __init__(self) -> None:
        super().__init__("STALE_ATTEMPT")


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True,
                       allow_nan=False, default=str) + "\n").encode()


def _native_path(path: Path) -> Path:
    """Use Win32 extended paths for filesystem I/O, never for storage keys."""
    if os.name != "nt":
        return path
    absolute = os.path.abspath(path)
    if absolute.startswith("\\\\?\\"):
        return Path(absolute)
    if absolute.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + absolute[2:])
    return Path("\\\\?\\" + absolute)


def _write(path: Path, value: object, *, guard: Callable[[], None] | None = None) -> None:
    if guard is not None:
        guard()
    target = _native_path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("wb", dir=target.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(_json_bytes(value))
            handle.flush()
            os.fsync(handle.fileno())
        if guard is not None:
            guard()
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _read(path: Path) -> dict:
    try:
        value = json.loads(_native_path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise WorkerError("INVALID_WORKER_JOURNAL") from None
    if not isinstance(value, dict):
        raise WorkerError("INVALID_WORKER_JOURNAL")
    return value


def sha256(path: Path, *, guard: Callable[[], None] | None = None) -> str:
    digest = hashlib.sha256()
    with _native_path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            if guard is not None:
                guard()
            digest.update(block)
    if guard is not None:
        guard()
    return digest.hexdigest()


def shared_path(root: Path, key: str, *, must_exist: bool = False) -> Path:
    """Confine POSIX storage keys, including symlink targets, to one shared root."""
    if (not isinstance(key, str) or not key or "\\" in key or ":" in key
            or "\x00" in key or key.startswith("/") or "//" in key
            or any(part in {"", ".", ".."} for part in key.split("/"))):
        raise WorkerError("UNSAFE_STORAGE_KEY")
    base = root.resolve()
    target = (base / PurePosixPath(key)).resolve()
    if not target.is_relative_to(base):
        raise WorkerError("UNSAFE_STORAGE_KEY")
    if must_exist and not _native_path(target).is_file():
        raise WorkerError("MISSING_ARTIFACT")
    return target


def _identifier(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value):
        raise WorkerError("INVALID_CLAIM")
    return value


def _timestamp(value: object) -> float:
    if not isinstance(value, str):
        raise WorkerError("INVALID_CLAIM")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError
        return parsed.timestamp()
    except (ValueError, OverflowError):
        raise WorkerError("INVALID_CLAIM") from None


@dataclass(frozen=True)
class Lease:
    job_id: str
    video_id: str
    generation: int
    stage_id: str
    stage_type: str
    attempt: int
    run_token: str
    lease_until: str
    hard_deadline_at: str
    source_key: str
    source_hash: str
    config: dict
    artifacts: list[dict]
    input_hash: str | None = None

    @classmethod
    def parse(cls, value: dict, *, validate_export: bool = True) -> Lease:
        if value.get("outcome") not in {"CLAIMED", "ALREADY_CLAIMED"}:
            raise WorkerError("INVALID_CLAIM")
        for field in ("job_id", "video_id", "stage_id", "run_token"):
            _identifier(value.get(field))
        if (value.get("stage_type") not in STAGES or type(value.get("attempt")) is not int
                or value["attempt"] <= 0 or not isinstance(value.get("source_key"), str)
                or type(value.get("generation")) is not int or value["generation"] <= 0
                or not isinstance(value.get("source_hash"), str)
                or not re.fullmatch(r"[a-f0-9]{64}", value["source_hash"])
                or not isinstance(value.get("config"), dict)
                or not isinstance(value.get("artifacts"), list)):
            raise WorkerError("INVALID_CLAIM")
        _timestamp(value.get("lease_until"))
        _timestamp(value.get("hard_deadline_at"))
        if value.get("input_hash") is not None:
            if not isinstance(value["input_hash"], str) or not re.fullmatch(r"[a-f0-9]{64}", value["input_hash"]):
                raise WorkerError("INVALID_CLAIM")
        lease = cls(**{field: value[field] for field in cls.__dataclass_fields__ if field in value})
        if validate_export and lease.stage_type == "EXPORT_CLIP":
            from .worker_export import ExportSpec

            ExportSpec.from_lease(lease)
        return lease

    @property
    def attempt_key(self) -> str:
        return (f"jobs/{self.job_id}/stages/{self.stage_id}/attempts/"
                f"{self.attempt}/{self.run_token}")

    @property
    def credentials(self) -> dict:
        return {"attempt": self.attempt, "run_token": self.run_token}


class LeaseGuard:
    def __init__(self, lease: Lease, stop: threading.Event,
                 *, now: Callable[[], float] = time.time,
                 presence_check: Callable[[], None] | None = None) -> None:
        self._now, self._stop = now, stop
        self._lease_until = _timestamp(lease.lease_until)
        self._deadline = _timestamp(lease.hard_deadline_at)
        self._lost = threading.Event()
        self._lock = threading.Lock()
        self._presence_check = presence_check

    def check(self) -> None:
        if self._stop.is_set():
            raise WorkerError("WORKER_STOPPED")
        if self._presence_check is not None:
            self._presence_check()
        if self._lost.is_set():
            raise WorkerError("LEASE_LOST")
        with self._lock:
            now = self._now()
            if now >= self._deadline:
                raise WorkerError("HARD_DEADLINE_EXCEEDED")
            if now >= self._lease_until:
                raise WorkerError("LEASE_LOST")

    def remaining_seconds(self) -> float:
        self.check()
        with self._lock:
            return max(0.001, self._deadline - self._now())

    def renew(self, response: dict) -> None:
        try:
            stage = response["stage"]
            until = _timestamp(stage["lease_until"])
            deadline = _timestamp(stage["hard_deadline_at"])
            with self._lock:
                if deadline != self._deadline or until > deadline:
                    raise WorkerError("INVALID_HEARTBEAT")
                self._lease_until = until
        except (KeyError, TypeError, WorkerError):
            self.lose()
            raise WorkerError("INVALID_HEARTBEAT") from None

    def lose(self) -> None:
        self._lost.set()


@dataclass
class StageContext:
    lease: Lease
    storage_root: Path
    directory: Path
    guard: LeaseGuard
    provider_input_hash: str | None = None
    render_progress: Callable[[dict], None] | None = None

    def artifact(self, kind: str, path: Path) -> dict:
        resolved = path.resolve()
        if not resolved.is_relative_to(self.directory.resolve()) or not _native_path(resolved).is_file():
            raise WorkerError("UNSAFE_OUTPUT_ARTIFACT")
        return {"kind": kind, "key": resolved.relative_to(self.storage_root.resolve()).as_posix(),
                "sha256": sha256(resolved, guard=self.guard.check)}

    def predecessor(self, stage_type: str, kind: str) -> Path:
        matches = [item for item in self.lease.artifacts if item.get("stage_type") == stage_type]
        if len(matches) != 1:
            raise WorkerError("MISSING_PREDECESSOR")
        reference = matches[0]
        manifest_path = shared_path(self.storage_root, reference["manifest_key"], must_exist=True)
        if sha256(manifest_path, guard=self.guard.check) != reference["manifest_hash"]:
            raise WorkerError("ARTIFACT_HASH_MISMATCH")
        manifest = _read(manifest_path)
        if (manifest.get("job_id") != self.lease.job_id
                or manifest.get("video_id") != self.lease.video_id
                or manifest.get("analysis_generation") != self.lease.generation
                or manifest.get("stage_id") != reference.get("stage_id")
                or manifest.get("stage_type") != stage_type
                or manifest.get("input_sha256") != self.lease.source_hash):
            raise WorkerError("ARTIFACT_INPUT_MISMATCH")
        files = [item for item in manifest.get("artifacts", []) if item.get("kind") == kind]
        if len(files) != 1:
            raise WorkerError("MISSING_ARTIFACT")
        artifact = files[0]
        if not artifact["key"].startswith(manifest_path.parent.relative_to(
                self.storage_root.resolve()).as_posix() + "/"):
            raise WorkerError("UNSAFE_STORAGE_KEY")
        path = shared_path(self.storage_root, artifact["key"], must_exist=True)
        if not path.is_relative_to(manifest_path.parent.resolve()):
            raise WorkerError("UNSAFE_STORAGE_KEY")
        if sha256(path, guard=self.guard.check) != artifact["sha256"]:
            raise WorkerError("ARTIFACT_HASH_MISMATCH")
        return path


class StageExecutor(Protocol):
    def __call__(self, context: StageContext) -> dict: ...


@contextmanager
def _single_worker(directory: Path) -> Iterator[None]:
    with _native_path(directory / ".worker.lock").open("a+b") as handle:
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise WorkerError("WORKER_BUSY") from None
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


class StageWorker:
    """One execution slot, durable claim ID and durable completion outbox."""

    def __init__(
        self, client: WorkerClient, storage_root: Path, journal_dir: Path,
        worker_id: str, executor: StageExecutor, *, heartbeat_seconds: float = 10,
        stop: threading.Event | None = None, target_job_id: str | None = None,
        registry_enabled: bool = True, worker_heartbeat_seconds: float = 10,
        registration_wait_seconds: float = 45,
        reconnect_wait_seconds: float = 180,
        lifecycle_observer: Callable[[dict], None] | None = None,
    ) -> None:
        self.client, self.storage_root = client, storage_root.resolve()
        self.journal_dir, self.worker_id, self.executor = journal_dir.resolve(), _identifier(worker_id), executor
        if heartbeat_seconds <= 0 or heartbeat_seconds > 15:
            raise WorkerError("INVALID_HEARTBEAT_INTERVAL")
        self.heartbeat_seconds = heartbeat_seconds
        if worker_heartbeat_seconds <= 0 or worker_heartbeat_seconds > 15:
            raise WorkerError("INVALID_WORKER_HEARTBEAT_INTERVAL")
        self.registry_enabled = registry_enabled
        self.worker_heartbeat_seconds = worker_heartbeat_seconds
        if (type(registration_wait_seconds) not in (int, float)
                or not 0 <= registration_wait_seconds <= 3600
                or not math.isfinite(registration_wait_seconds)):
            raise WorkerError("INVALID_REGISTRATION_WAIT_CONFIGURATION")
        self.registration_wait_seconds = registration_wait_seconds
        if (type(reconnect_wait_seconds) not in (int, float)
                or not 0 <= reconnect_wait_seconds <= 3600
                or not math.isfinite(reconnect_wait_seconds)):
            raise WorkerError("INVALID_RECONNECT_WAIT_CONFIGURATION")
        self.reconnect_wait_seconds = reconnect_wait_seconds
        self._lifecycle_observer = lifecycle_observer
        self.session_id: str | None = None
        self._presence_error: WorkerClientError | None = None
        self._presence_deadline: float | None = None
        self._presence_lock = threading.RLock()
        self._presence_epoch = 0
        self._continuous = False
        self._reconnect_started: float | None = None
        self._runtime_deadline: float | None = None
        self._presence_thread: threading.Thread | None = None
        self._stage_thread: threading.Thread | None = None
        self._presence_success_at: float | None = None
        self._ready_epoch: int | None = None
        self._presence_renewals = 0
        self._outage_epoch: int | None = None
        self._outage_renewals = 0
        self._reconnect_exhausted_observed = False
        self._presence_wake = threading.Event()
        self._monotonic = time.monotonic
        self._ready_cursor = 0
        self.stop = stop if stop is not None else threading.Event()
        self.target_job_id = (WorkerClient._job_id(target_job_id)
                              if target_job_id is not None else None)
        _native_path(self.journal_dir).mkdir(parents=True, exist_ok=True)

    def _recover_local_result(self) -> None:
        """A completed old attempt may publish its exact callback without a new claim."""
        claim_path = self.journal_dir / "pending-claim.json"
        callback_path = self.journal_dir / "pending-callback.json"
        if not _native_path(claim_path).exists() or _native_path(callback_path).exists():
            return
        pending = _read(claim_path)
        self._check_target_record(pending)
        attempt_key = pending.get("attempt_key")
        if attempt_key is None:
            return
        if pending.get("worker_id") != self.worker_id:
            raise WorkerError("WORKER_ID_MISMATCH")
        result_path = shared_path(self.storage_root, attempt_key) / "worker-result.json"
        if _native_path(result_path).exists():
            callback = _read(result_path)
            self._check_target_record(callback)
            if callback.get("stage_id") != pending.get("stage_id"):
                raise WorkerError("CLAIM_TARGET_CHANGED")
            _write(callback_path, callback)
            _native_path(claim_path).unlink()

    def _runtime_retryable(self, error: WorkerClientError) -> bool:
        if error.code.startswith("INVALID_") or error.code in {
            "WORKER_UNAUTHORIZED", "WORKER_FORBIDDEN", "WORKER_REGISTRATION_CONFLICT",
            "STALE_ATTEMPT",
        }:
            return False
        if error.code == "WORKER_SESSION_CONFLICT" and self.registration_wait_seconds == 0:
            return False
        return error.retryable or error.code in {
            "WORKER_SESSION_EXPIRED", "WORKER_SESSION_CONFLICT", "WORKER_NOT_REGISTERED",
        }

    def _note_outage(self, error: WorkerClientError) -> None:
        if not self._continuous or not self.reconnect_wait_seconds or not self._runtime_retryable(error):
            return
        with self._presence_lock:
            if self._reconnect_started is None:
                self._reconnect_started = self._monotonic()
                self._runtime_deadline = self._reconnect_started + self.reconnect_wait_seconds
                self._outage_epoch = self._presence_epoch
                self._outage_renewals = self._presence_renewals
                self._ready_epoch = None
                self._reconnect_exhausted_observed = False
                # Probe a genuinely working heartbeat promptly, even when the
                # recovery window is shorter than the ordinary pulse interval.
                self._presence_wake.set()

    def _maybe_recovered(self) -> None:
        recovered = None
        with self._presence_lock:
            now = self._monotonic()
            started, deadline = self._reconnect_started, self._runtime_deadline
            heartbeat = (not self.registry_enabled or self._presence_renewals > (
                self._outage_renewals if self._outage_epoch == self._presence_epoch else 0))
            live = (not self.registry_enabled or (self._presence_deadline is not None
                    and now < self._presence_deadline))
            if (started is not None and deadline is not None and not self.stop.is_set()
                    and self._presence_error is None and self._ready_epoch == self._presence_epoch
                    and heartbeat and live and now < deadline):
                recovered = (now - started, deadline - now)
                self._reconnect_started = None
                self._runtime_deadline = None
        if recovered is not None:
            self._observe_lifecycle("recovered", elapsed_seconds=recovered[0],
                                    remaining_seconds=recovered[1])

    def _ready_succeeded(self) -> None:
        with self._presence_lock:
            self._ready_epoch = self._presence_epoch
        self._maybe_recovered()

    def _presence_failed(self, error: WorkerClientError, epoch: int) -> None:
        with self._presence_lock:
            if epoch != self._presence_epoch:
                return
            if self._presence_error is None:
                self._presence_error = error
            self._note_outage(error)
            # The explicit one-shot command remains fail-fast. Continuous mode
            # never clears or repurposes the user's permanent cancellation event.
            if not self._continuous:
                self.stop.set()

    def _check_presence(self, epoch: int | None = None) -> None:
        if not self.registry_enabled:
            return
        with self._presence_lock:
            if epoch is not None and epoch != self._presence_epoch:
                raise WorkerError("WORKER_PRESENCE_LOST")
            if (self._presence_error is not None or self._presence_deadline is None
                    or self._monotonic() >= self._presence_deadline):
                self._presence_failed(self._presence_error or WorkerClientError(
                    "WORKER_SESSION_EXPIRED"), self._presence_epoch)
                raise WorkerError("WORKER_PRESENCE_LOST")

    def _request_guard(self) -> None:
        if self.stop.is_set():
            raise WorkerClientError("WORKER_STOPPED")
        # Heartbeat recovery can clear both fields concurrently. Read the
        # clock and compare under the same lock that publishes that recovery.
        with self._presence_lock:
            now = self._monotonic()
            deadline = self._runtime_deadline
            if deadline is not None and now >= deadline:
                raise WorkerClientError("WORKER_RECONNECT_EXHAUSTED")

    def _client_scope(self, *, deadline: float | Callable[[], float | None] | None = None, guard=None):
        scope = getattr(self.client, "request_scope", None)
        if scope is None:
            return nullcontext()

        def native_deadline():
            # HTTP transports use the process monotonic clock. Translate a
            # remaining duration so injected protocol clocks cannot become an
            # already-expired absolute HTTP deadline (notably during cleanup).
            with self._presence_lock:
                now = self._monotonic()
                value = deadline() if callable(deadline) else deadline
                remaining = None if value is None else value - now
            return None if remaining is None else time.monotonic() + remaining

        return scope(deadline_at=native_deadline, request_guard=guard or self._request_guard)

    def _worker_heartbeat(self, finished: threading.Event, interval: float,
                          session_id: str, epoch: int) -> None:
        # Scheduling uses actual elapsed wall time. The injectable presence
        # clock is also used by protocol simulations and can remain frozen.
        next_pulse = time.monotonic() + (0 if self._reconnect_started is not None else interval)
        while not finished.is_set():
            remaining = next_pulse - time.monotonic()
            if remaining > 0 and not self._presence_wake.wait(min(.05, remaining)):
                continue
            self._presence_wake.clear()
            if finished.is_set():
                return
            if self.stop.is_set():
                return
            try:
                self._check_presence(epoch)
                self._request_guard()
                started = self._monotonic()
                with self._presence_lock:
                    if (finished.is_set() or epoch != self._presence_epoch
                            or self.session_id != session_id or self._presence_deadline is None):
                        return
                    deadlines = [started + interval, self._presence_deadline]
                    if self._runtime_deadline is not None:
                        deadlines.append(self._runtime_deadline)
                    deadline = min(deadlines)
                with self._client_scope(deadline=deadline):
                    receipt = self.client.heartbeat_worker(self.worker_id, session_id,
                                                           timeout_seconds=interval)
                # Request start is deliberately conservative: a delayed receipt
                # must not resurrect a session whose locally known TTL elapsed.
                self._check_presence(epoch)
                self._request_guard()
                with self._presence_lock:
                    if finished.is_set() or epoch != self._presence_epoch or self.session_id != session_id:
                        return
                    deadline = started + receipt["heartbeat_timeout_seconds"]
                    if self._monotonic() >= deadline:
                        raise WorkerClientError("WORKER_SESSION_EXPIRED")
                    self._presence_deadline = deadline
                    self._presence_success_at = self._monotonic()
                    self._presence_renewals += 1
                interval = min(self.worker_heartbeat_seconds, receipt["heartbeat_timeout_seconds"] / 3)
                next_pulse = time.monotonic() + interval
                self._maybe_recovered()
            except (WorkerClientError, WorkerError) as exc:
                # A task lease and a live executor are separate requirements.
                # Stop acquiring/external dispatching as soon as presence is uncertain.
                if self.stop.is_set():
                    return
                self._presence_failed(exc if isinstance(exc, WorkerClientError)
                                      else WorkerClientError("WORKER_SESSION_EXPIRED"), epoch)
                return

    def _observe_lifecycle(self, status: str, **fields: object) -> None:
        if self._lifecycle_observer is not None:
            self._lifecycle_observer({"status": status, "worker_id": self.worker_id, **fields})

    def _registration_guard(self) -> None:
        self._request_guard()

    def _register_with_wait(self, session_id: str, capabilities: list[str],
                            target: dict) -> tuple[dict, float, float | None] | None:
        """Wait for an old owner to leave, without changing its session or lease."""
        started = self._monotonic()
        deadline = started + self.registration_wait_seconds
        with self._presence_lock:
            runtime_deadline = self._runtime_deadline
        if runtime_deadline is not None:
            deadline = min(deadline, runtime_deadline)
        conflict: WorkerClientError | None = None
        while not self.stop.is_set():
            request_started = self._monotonic()
            remaining = deadline - request_started
            if conflict is not None and remaining <= 0:
                raise conflict
            options = dict(target)
            options["request_guard"] = self._registration_guard
            if self.registration_wait_seconds > 0:
                # Each attempt has its own timeout and presence start time. A
                # successful retry must not inherit the first attempt's age.
                if remaining <= 0:
                    raise WorkerClientError("WORKER_SESSION_CONFLICT")
                options["timeout_seconds"] = remaining
            try:
                receipt = self.client.register_worker(
                    self.worker_id, session_id, capabilities, **options)
                return receipt, request_started, deadline if self.registration_wait_seconds else None
            except WorkerClientError as exc:
                if exc.code == "WORKER_STOPPED" and self.stop.is_set():
                    return None
                if exc.code != "WORKER_SESSION_CONFLICT" or self.registration_wait_seconds == 0:
                    raise
                conflict = exc
                now = self._monotonic()
                remaining = deadline - now
                if remaining <= 0:
                    raise
                self._observe_lifecycle("registration-wait", elapsed_seconds=now - started,
                                        remaining_seconds=remaining)
                remaining = deadline - self._monotonic()
                if remaining <= 0:
                    raise
                if self.stop.wait(min(1, remaining)):
                    return None
        return None

    @contextmanager
    def _registered(self, stage_type: str | None, *, candidate_session: str | None = None) -> Iterator[bool]:
        if not self.registry_enabled:
            yield True
            return
        # Never advertise capabilities we will not poll for. This process has
        # exactly one computation slot, independent of its heartbeat threads.
        capabilities = [stage_type] if stage_type is not None else list(STAGES)
        if any(value not in STAGES for value in capabilities):
            raise WorkerError("INVALID_STAGE_TYPE")
        if any(thread is not None and thread.is_alive()
               for thread in (self._presence_thread, self._stage_thread)):
            raise WorkerError("WORKER_HEARTBEAT_NOT_STOPPED")
        session_id = candidate_session or str(uuid4())
        with self._presence_lock:
            self._presence_epoch += 1
            epoch = self._presence_epoch
            self._presence_error = None
            self._presence_success_at = None
            self._presence_renewals = 0
            self._ready_epoch = None
        target = {"target_job_id": self.target_job_id} if self.target_job_id is not None else {}
        registration = self._register_with_wait(session_id, capabilities, target)
        if registration is None:
            yield False
            return
        receipt, started, registration_deadline = registration
        self.session_id = session_id
        with self._presence_lock:
            self._presence_deadline = started + receipt["heartbeat_timeout_seconds"]
        finished = threading.Event()
        interval = min(self.worker_heartbeat_seconds, receipt["heartbeat_timeout_seconds"] / 3)
        thread = threading.Thread(target=self._worker_heartbeat,
                                  args=(finished, interval, session_id, epoch), daemon=True)
        self._presence_thread = thread
        thread.start()
        try:
            # A custom transport can return a valid receipt after its timeout.
            # We own this session and must unregister it, but cannot begin work.
            if (registration_deadline is not None
                    and self._monotonic() >= registration_deadline):
                raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)
            self._request_guard()
            self._check_presence()
            if self.stop.is_set():
                yield False
            else:
                self._observe_lifecycle("registered")
                yield True
            if self._presence_error is not None:
                raise self._presence_error
        except WorkerError as exc:
            if exc.code == "WORKER_PRESENCE_LOST":
                # The main thread and heartbeat may observe the same deadline
                # in either order. Expose one lifecycle error in both cases.
                raise self._presence_error or WorkerClientError("WORKER_SESSION_EXPIRED") from None
            raise
        finally:
            finished.set()
            thread.join(timeout=12)
            if thread.is_alive():
                # A custom transport must not let an old heartbeat overlap a
                # new registration, even when it ignores our request deadline.
                with self._presence_lock:
                    self._presence_epoch += 1
                    self._presence_deadline = None
                    self.session_id = None
                raise WorkerError("WORKER_HEARTBEAT_NOT_STOPPED")
            # A failed unregister leaves presence to expire; it cannot replace
            # the original computation/protocol exception or release its lease.
            try:
                deadline = self._monotonic() + 2
                with self._presence_lock:
                    runtime_deadline = self._runtime_deadline
                if runtime_deadline is not None:
                    deadline = min(deadline, runtime_deadline)

                def cleanup_guard():
                    if self._monotonic() >= deadline:
                        raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True)

                with self._client_scope(deadline=deadline, guard=cleanup_guard):
                    self.client.unregister_worker(self.worker_id, session_id)
            except WorkerClientError:
                pass
            self.session_id = None
            with self._presence_lock:
                self._presence_epoch += 1
                self._presence_deadline = None

    def _check_target_record(self, value: dict) -> None:
        if self.target_job_id is not None and value.get("job_id") != self.target_job_id:
            raise WorkerError("WORKER_JOB_TARGET_MISMATCH")

    def _archive_abandoned_claim(self) -> None:
        """A new session must never turn an old unknown request into a new claim."""
        path = self.journal_dir / "pending-claim.json"
        if not self.registry_enabled or not _native_path(path).exists():
            return
        value = _read(path)
        self._check_target_record(value)
        if value.get("worker_id") != self.worker_id:
            raise WorkerError("WORKER_ID_MISMATCH")
        # Hashes, not untrusted stage/request identifiers, form filesystem names.
        digest = hashlib.sha256(_json_bytes(value)).hexdigest()
        archive = self.journal_dir / "session-abandoned" / (digest + ".json")
        if _native_path(archive).exists():
            if _read(archive) != value:
                raise WorkerError("INVALID_WORKER_JOURNAL")
        else:
            _write(archive, value)
        _native_path(path).unlink()

    def _pending_callback(self) -> bool:
        pending = self.journal_dir / "pending-callback.json"
        if not _native_path(pending).exists():
            return False
        value = _read(pending)
        self._check_target_record(value)
        try:
            # Settlement uses the exact old credentials even when old executor
            # presence is lost. Only user cancellation and API deadlines gate it.
            with self._client_scope(deadline=lambda: self._runtime_deadline):
                response = self.client.callback(value["action"], value["stage_id"], value["payload"])
            if response.get("outcome") not in {"ACCEPTED", "ALREADY_APPLIED"}:
                raise WorkerClientError("INVALID_JAVA_RESPONSE", retryable=True)
        except WorkerClientError as exc:
            if exc.code == "STALE_ATTEMPT":
                # A stale result stays auditable locally but can never overwrite
                # the current business attempt.
                _write(self.journal_dir / "rejected-callback.json", value)
                digest = hashlib.sha256(_json_bytes(value)).hexdigest()
                _write(self.journal_dir / "fenced-callbacks" / (digest + ".json"), value)
                _native_path(pending).unlink()
                raise _FencedCallbackError() from None
            raise
        _native_path(pending).unlink()
        return True

    def _heartbeat(self, context: StageContext, finished: threading.Event) -> None:
        while not finished.wait(self.heartbeat_seconds):
            try:
                context.guard.check()
                deadline = self._monotonic() + self.heartbeat_seconds
                with self._presence_lock:
                    runtime_deadline = self._runtime_deadline
                if runtime_deadline is not None:
                    deadline = min(deadline, runtime_deadline)
                def heartbeat_guard():
                    context.guard.check()
                    self._request_guard()

                with self._client_scope(deadline=deadline, guard=heartbeat_guard):
                    response = self.client.callback("heartbeat", context.lease.stage_id,
                                                    context.lease.credentials)
                context.guard.renew(response)
            except (WorkerClientError, WorkerError):
                context.guard.lose()
                return

    def run_once(self, *, stage_type: str | None = None) -> str:
        with _single_worker(self.journal_dir):
            self._recover_local_result()
            if self.stop.is_set():
                return "stopped"
            if self._pending_callback():
                return "callback_replayed"
            if self.stop.is_set():
                return "stopped"
            self._archive_abandoned_claim()
            with self._registered(stage_type) as registered:
                if not registered:
                    return "stopped"
                return self._run_once(stage_type=stage_type)

    def _next_claim(self, stage_type: str | None) -> tuple[dict, dict] | str:
        """Try a bounded ready page so a full resource pool cannot block every pool."""
        claim_path = self.journal_dir / "pending-claim.json"
        pending = None
        if _native_path(claim_path).exists():
            pending = _read(claim_path)
            self._check_target_record(pending)
        target = {"job_id": self.target_job_id} if self.target_job_id is not None else {}
        maximum_claims = 20 if self.registry_enabled else 1
        if self.registry_enabled and stage_type is None:
            # Query each capability independently. Twenty old MEDIA stages must
            # not hide a runnable SEGMENT stage beyond the first global page.
            query_stages = list(STAGES[self._ready_cursor:] + STAGES[:self._ready_cursor])
            self._ready_cursor = (self._ready_cursor + 1) % len(STAGES)
            page_size = 4
        else:
            query_stages, page_size = [stage_type], maximum_claims
        candidates, query_index, attempted = [], 0, set()
        query_stage = stage_type
        for _ in range(maximum_claims):
            if self.stop.is_set():
                return "stopped"
            self._check_presence()
            if pending is None:
                while not candidates:
                    if query_index == len(query_stages):
                        return "unavailable" if attempted else "idle"
                    query_stage = query_stages[query_index]
                    query_index += 1
                    ready = self.client.ready(stage_type=query_stage, limit=page_size, **target)
                    candidates = ready.get("items")
                    if not isinstance(candidates, list) or len(candidates) > page_size:
                        raise WorkerError("INVALID_JAVA_RESPONSE")
                    for row in candidates:
                        if not isinstance(row, dict):
                            raise WorkerError("INVALID_JAVA_RESPONSE")
                        _identifier(row.get("id"))
                        if self.target_job_id is not None and row.get("job_id") != self.target_job_id:
                            raise WorkerError("CLAIM_TARGET_CHANGED")
                        if self.registry_enabled and query_stage is not None and row.get("stage_type") != query_stage:
                            raise WorkerError("CLAIM_STAGE_TYPE_CHANGED")
                    self._ready_succeeded()
                    candidates = [item for item in candidates if not isinstance(item, dict)
                                  or not isinstance(item.get("id"), str) or item["id"] not in attempted]
                item = candidates.pop(0)
                if not isinstance(item, dict):
                    raise WorkerError("INVALID_JAVA_RESPONSE")
                if self.target_job_id is not None and item.get("job_id") != self.target_job_id:
                    raise WorkerError("CLAIM_TARGET_CHANGED")
                if self.registry_enabled and query_stage is not None and item.get("stage_type") != query_stage:
                    raise WorkerError("CLAIM_STAGE_TYPE_CHANGED")
                pending = {"stage_id": _identifier(item.get("id")),
                           "worker_id": self.worker_id, "claim_request_id": str(uuid4()), **target}
                _write(claim_path, pending)
            if pending.get("worker_id") != self.worker_id:
                raise WorkerError("WORKER_ID_MISMATCH")
            options = dict(target)
            if self.registry_enabled:
                options["session_id"] = self.session_id
                if pending.get("session_id") is None:
                    pending["session_id"] = self.session_id
                    _write(claim_path, pending)
                # Persist the identity before dispatch; only this same live
                # session may retry an uncertain request with its original ID.
            if self.stop.is_set():
                return "stopped"
            self._check_presence()
            claim = self.client.claim(pending["stage_id"], self.worker_id, pending["claim_request_id"], **options)
            if claim.get("outcome") not in {"NO_WORK", "EXPIRED", "NO_CAPACITY"}:
                return claim, pending
            attempted.add(pending["stage_id"])
            _native_path(claim_path).unlink()
            pending = None
        return "unavailable"

    def _run_once(self, *, stage_type: str | None = None) -> str:
        if self._pending_callback():
            return "callback_replayed"
        if self.stop.is_set():
            return "stopped"
        selected = self._next_claim(stage_type)
        if isinstance(selected, str):
            return selected
        claim, pending = selected
        claim_path = self.journal_dir / "pending-claim.json"
        # A malformed frozen export still has valid fenced credentials. Defer
        # its configuration check into the failure callback boundary below.
        lease = Lease.parse(claim, validate_export=False)
        if self.target_job_id is not None and lease.job_id != self.target_job_id:
            raise WorkerError("CLAIM_TARGET_CHANGED")
        if lease.stage_id != pending["stage_id"]:
            raise WorkerError("CLAIM_TARGET_CHANGED")
        if stage_type is not None and lease.stage_type != stage_type:
            raise WorkerError("CLAIM_STAGE_TYPE_CHANGED")
        if self.registry_enabled:
            if pending["session_id"] != self.session_id:
                raise WorkerError("WORKER_SESSION_CHANGED")
            pending["attempt_key"] = lease.attempt_key
            _write(claim_path, pending)
        # A replayed uncertain claim skips ready. Its validated frozen lease is
        # also a healthy control-plane cycle, before potentially long compute.
        self._ready_succeeded()
        directory = shared_path(self.storage_root, lease.attempt_key)
        _native_path(directory).mkdir(parents=True, exist_ok=True)
        epoch = self._presence_epoch
        guard = LeaseGuard(lease, self.stop, presence_check=lambda: self._check_presence(epoch))
        context = StageContext(lease, self.storage_root, directory, guard)

        def render_progress(value):
            guard.check()
            deadline = self._monotonic() + min(2, guard.remaining_seconds())
            try:
                with self._client_scope(deadline=deadline, guard=guard.check):
                    receipt = self.client.callback("render-progress", lease.stage_id,
                                                   {**lease.credentials, **value})
                if receipt.get("outcome") not in {"ACCEPTED", "ALREADY_APPLIED"}:
                    raise WorkerClientError("INVALID_RENDER_PROGRESS_RESPONSE")
            except WorkerClientError as error:
                if (error.retryable and not error.code.startswith("INVALID_")
                        and error.code not in {"STALE_ATTEMPT", "WORKER_UNAUTHORIZED", "WORKER_FORBIDDEN"}):
                    return
                guard.lose()
                raise
            guard.check()

        context.render_progress = render_progress
        callback_path = self.journal_dir / "pending-callback.json"
        result_path = directory / "worker-result.json"
        if _native_path(result_path).exists():
            # Crash after computation but before publishing the local callback.
            previous = _read(result_path)
            self._check_target_record(previous)
            _write(callback_path, previous)
            _native_path(claim_path).unlink()
            self._pending_callback()
            return "callback_replayed"
        finished = threading.Event()
        thread = threading.Thread(target=self._heartbeat, args=(context, finished), daemon=True)
        self._stage_thread = thread
        thread.start()
        try:
            guard.check()
            if lease.stage_type == "EXPORT_CLIP":
                from .worker_export import ExportSpec

                ExportSpec.from_lease(lease)
            manifest = self.executor(context)
            guard.check()
            expected = {"job_id": lease.job_id, "stage_id": lease.stage_id,
                        "stage_type": lease.stage_type, "attempt": lease.attempt,
                        "video_id": lease.video_id, "analysis_generation": lease.generation,
                        "run_token": lease.run_token, "input_sha256": lease.source_hash}
            if lease.stage_type == "EXPORT_CLIP":
                from .worker_export import ExportSpec

                export_spec = ExportSpec.from_lease(lease)
                expected.update({key: value for key, value in asdict(export_spec).items()
                                 if key != "caption_render"})
                expected.update(data_kind="captioned_export" if export_spec.render_mode == "CAPTIONED" else "reviewed_export", review_status="CONFIRMED",
                                stage_input_hash=lease.input_hash)
            if any(manifest.get(key) != value for key, value in expected.items()):
                raise WorkerError("INVALID_RESULT_MANIFEST")
            if not isinstance(manifest.get("artifacts"), list):
                raise WorkerError("INVALID_RESULT_MANIFEST")
            for artifact in manifest["artifacts"]:
                if not isinstance(artifact, dict) or not isinstance(artifact.get("key"), str):
                    raise WorkerError("INVALID_RESULT_MANIFEST")
                file = shared_path(self.storage_root, artifact["key"], must_exist=True)
                if (not file.is_relative_to(directory.resolve())
                        or sha256(file, guard=guard.check) != artifact.get("sha256")):
                    raise WorkerError("INVALID_RESULT_MANIFEST")
            manifest_path = directory / "manifest.json"
            guard.check()
            _write(manifest_path, manifest, guard=guard.check)
            payload = {**lease.credentials, "manifest_key": lease.attempt_key + "/manifest.json",
                       "manifest_hash": sha256(manifest_path, guard=guard.check)}
            callback = {"action": "complete", "stage_id": lease.stage_id, "payload": payload}
        except Exception as exc:
            code = getattr(exc, "code", "WORKER_EXECUTION_FAILED")
            if not isinstance(code, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code):
                code = "WORKER_EXECUTION_FAILED"
            try:
                _write(directory / "worker-error.json", {
                    "error_code": code, "exception_type": type(exc).__name__,
                    "frames": [{"file": Path(frame.filename).name, "line": frame.lineno,
                                "function": frame.name} for frame in traceback.extract_tb(exc.__traceback__)],
                })
            except OSError:
                pass
            callback = {"action": "fail", "stage_id": lease.stage_id,
                        "payload": {**lease.credentials, "error_code": code,
                                    "retryable": bool(getattr(exc, "retryable", False))}}
            from .diagnostics import failure_diagnostic

            diagnostic = failure_diagnostic(exc, code)
            if diagnostic is not None:
                callback["payload"]["diagnostic"] = diagnostic
        finally:
            finished.set()
            thread.join(timeout=12)
            if thread.is_alive():
                guard.lose()
                raise WorkerError("STAGE_HEARTBEAT_NOT_STOPPED")
        # Write the exact callback before any network mutation. Never regenerate
        # its hash or recompute a stage merely because the response was lost.
        if self.target_job_id is not None:
            callback["job_id"] = self.target_job_id
        _write(result_path, callback)
        _write(callback_path, callback)
        _native_path(claim_path).unlink(missing_ok=True)
        self._pending_callback()
        return "completed" if callback["action"] == "complete" else "failed"

    def _wait_runtime_recovery(self, error: WorkerClientError, backoff: float) -> float | None:
        with self._presence_lock:
            self._note_outage(error)
            if (error.code != "WORKER_RECONNECT_EXHAUSTED"
                    and (not self.reconnect_wait_seconds or not self._runtime_retryable(error))):
                raise error
            now = self._monotonic()
            started, deadline = self._reconnect_started, self._runtime_deadline
            if started is None or deadline is None:
                raise error
            remaining = deadline - now
            elapsed = now - started
            observe_exhausted = remaining <= 0 and not self._reconnect_exhausted_observed
            if observe_exhausted:
                self._reconnect_exhausted_observed = True
        if remaining <= 0:
            if observe_exhausted:
                self._observe_lifecycle("reconnect-exhausted", error_code=error.code,
                                        elapsed_seconds=elapsed, remaining_seconds=0.0)
            raise WorkerClientError("WORKER_RECONNECT_EXHAUSTED") from None
        self._observe_lifecycle("reconnecting", error_code=error.code,
                                elapsed_seconds=elapsed, remaining_seconds=remaining)
        if self.stop.wait(min(backoff, remaining)):
            return None
        return min(10.0, backoff * 2)

    def _idle_wait(self, seconds: float) -> None:
        """An operator stop or executor failure must interrupt even a long poll."""
        deadline = self._monotonic() + seconds
        while not self.stop.is_set():
            if self._presence_error is not None:
                raise self._presence_error
            self._request_guard()
            remaining = deadline - self._monotonic()
            if remaining <= 0:
                return
            with self._presence_lock:
                now = self._monotonic()
                runtime_deadline = self._runtime_deadline
            if runtime_deadline is not None:
                remaining = min(remaining, runtime_deadline - now)
            self.stop.wait(max(0, min(.05, remaining)))

    def run(self, *, poll_seconds: float = 3, stage_type: str | None = None) -> None:
        if type(poll_seconds) not in (int, float) or not math.isfinite(poll_seconds) or poll_seconds <= 0:
            raise WorkerError("INVALID_POLL_INTERVAL")
        # The same process owns its journal for the entire advertised lifetime.
        # Registering outside this lock could let two processes share an identity.
        with _single_worker(self.journal_dir):
            self._continuous = True
            candidate_session = str(uuid4())
            backoff = min(1.0, self.reconnect_wait_seconds / 4) if self.reconnect_wait_seconds else 1.0
            try:
                while not self.stop.is_set():
                    try:
                        with self._client_scope(deadline=lambda: self._runtime_deadline):
                            self._request_guard()
                            self._recover_local_result()
                            self._pending_callback()
                            self._archive_abandoned_claim()
                            with self._registered(stage_type, candidate_session=candidate_session) as registered:
                                if not registered:
                                    return
                                # This UUID is now an acknowledged old session.
                                # A later failure must never adopt its attempts.
                                candidate_session = str(uuid4())
                                while not self.stop.is_set():
                                    try:
                                        with self._client_scope(deadline=lambda: self._runtime_deadline):
                                            status = self._run_once(stage_type=stage_type)
                                    except WorkerClientError as exc:
                                        if isinstance(exc, _FencedCallbackError):
                                            continue
                                        self._note_outage(exc)
                                        # A healthy, still-owned session may replay its
                                        # uncertain claim with the exact same receipt ID.
                                        if (self._presence_error is None and exc.retryable
                                                and self.reconnect_wait_seconds
                                                and self._runtime_retryable(exc)):
                                            backoff = self._wait_runtime_recovery(exc, backoff)
                                            if backoff is None:
                                                return
                                            continue
                                        raise
                                    if self._presence_error is not None:
                                        raise self._presence_error
                                    self._request_guard()
                                    self._maybe_recovered()
                                    if self._reconnect_started is None:
                                        backoff = min(1.0, self.reconnect_wait_seconds / 4)
                                    if status in {"idle", "unavailable"}:
                                        self._idle_wait(poll_seconds)
                    except WorkerClientError as exc:
                        if self.stop.is_set():
                            return
                        if isinstance(exc, _FencedCallbackError):
                            continue
                        self._note_outage(exc)
                        if exc.code == "WORKER_SESSION_EXPIRED":
                            # Java definitively fenced this candidate UUID.
                            candidate_session = str(uuid4())
                        backoff = self._wait_runtime_recovery(exc, backoff)
                        if backoff is None:
                            return
            finally:
                self._continuous = False


ProviderTransportFactory = Callable[[StageContext, str], httpx.BaseTransport]


class AnalysisStageExecutor:
    """Existing real computation adapters with injected durable billing guard.

    There is deliberately no unbudgeted provider fallback. The transport factory
    must reserve paid submissions in Java before forwarding them to the provider.
    """

    def __init__(self, *, transport_factory: ProviderTransportFactory | None = None,
                 ffmpeg_path: str = "ffmpeg", ffprobe_path: str = "ffprobe",
                 asr_reuse_source: dict | None = None) -> None:
        self.transport_factory = transport_factory
        self.ffmpeg_path, self.ffprobe_path = ffmpeg_path, ffprobe_path
        self.asr_reuse_source = deepcopy(asr_reuse_source)

    def _execute_agent(self, context: StageContext) -> dict:
        from .video_agent import build_video_answer

        lease, directory = context.lease, context.directory
        config = lease.config.get("agent_turn")
        required = {"turn_id", "session_id", "question", "source_job_id", "source_transcript_key",
                    "source_transcript_hash", "source_analysis_key", "source_analysis_hash",
                    "duration_ms", "history", "max_tool_steps"}
        if (not isinstance(config, dict) or not required.issubset(config)
                or config["source_job_id"] != lease.job_id
                or type(config["duration_ms"]) is not int or config["duration_ms"] <= 0
                or type(config["max_tool_steps"]) is not int or not 1 <= config["max_tool_steps"] <= 4
                or type(lease.config.get("max_text_calls")) is not int
                or not 1 <= lease.config["max_text_calls"] <= 24
                or type(lease.config.get("max_asr_submissions")) is not int
                or lease.config["max_asr_submissions"] != 0):
            raise WorkerError("INVALID_AGENT_INPUT")
        sources = {}
        for name, stage, kind in (("transcript", "ASR", "transcript"),
                                   ("analysis", "TIMELINE", "analysis_result")):
            path = context.predecessor(stage, kind)
            expected = shared_path(context.storage_root, config[f"source_{name}_key"], must_exist=True)
            if path != expected or sha256(path, guard=context.guard.check) != config[f"source_{name}_hash"]:
                raise WorkerError("SOURCE_HASH_MISMATCH")
            sources[name] = _read(path) if name == "analysis" else json.loads(
                _native_path(path).read_text(encoding="utf-8"))
        analysis, transcript = sources["analysis"], sources["transcript"]
        validate_topic_result(analysis)
        if (analysis.get("job_id") != lease.job_id or analysis.get("video_id") != lease.video_id
                or analysis.get("analysis_generation") != lease.generation
                or analysis.get("duration_ms") != config["duration_ms"]
                or analysis.get("utterances") != transcript):
            raise WorkerError("INVALID_AGENT_INPUT")
        if self.transport_factory is None:
            raise WorkerError("DURABLE_BUDGET_REQUIRED")
        embedder = None
        if os.environ.get("LIVECUT_INTENT_EMBEDDING_DIR"):
            from .intent_embedding import LocalIntentEmbedder

            context.guard.check()
            embedder = LocalIntentEmbedder(os.environ["LIVECUT_INTENT_EMBEDDING_DIR"])

        def bound_timeout(request: httpx.Request) -> None:
            context.guard.check()
            remaining = context.guard.remaining_seconds()
            request.extensions["timeout"] = {"connect": min(30, remaining), "read": min(300, remaining),
                                              "write": min(30, remaining), "pool": min(30, remaining)}

        text_transport = self.transport_factory(context, "text")
        with httpx.Client(transport=text_transport, timeout=min(300, context.guard.remaining_seconds()),
                          event_hooks={"request": [bound_timeout]}, follow_redirects=False) as client:
            from .text_resilience import ResilientTextTransport

            client.livecut_resumable_text = isinstance(text_transport, ResilientTextTransport)
            build_video_answer(transcript, analysis["segments"], config["duration_ms"], config["question"],
                _native_path(directory), session_id=config["session_id"], turn_id=config["turn_id"],
                job_id=lease.job_id, video_id=lease.video_id, source_hash=lease.source_hash,
                transcript_hash=config["source_transcript_hash"], history=config["history"],
                model=lease.config["text_model"], max_calls=lease.config["max_text_calls"],
                max_tool_steps=config["max_tool_steps"], client=client,
                base_url=os.environ.get("LIVECUT_TEXT_BASE_URL"), dense_embedder=embedder,
                embedding_model_id=embedder.model_id if embedder else None,
                index_cache_dir=_native_path(context.storage_root / "cache" / "intent-index-v1"),
                guard=context.guard.check)
        context.guard.check()
        artifacts = [context.artifact("agent_answer", directory / "agent-answer.json")]
        if _native_path(directory / "text-execution-trace.json").is_file():
            artifacts.append(context.artifact("text_execution_trace", directory / "text-execution-trace.json"))
        return {"schema_version": 1, "job_id": lease.job_id, "video_id": lease.video_id,
                "analysis_generation": lease.generation, "stage_id": lease.stage_id,
                "stage_type": lease.stage_type, "attempt": lease.attempt, "run_token": lease.run_token,
                "input_sha256": lease.source_hash, "data_kind": "model_result", "review_status": "DRAFT",
                "artifacts": artifacts}

    def _execute_intent(self, context: StageContext) -> dict:
        from .intent import build_clip_plan

        lease, directory = context.lease, context.directory
        config = lease.config.get("clip_request")
        required = {"request_id", "instruction", "source_job_id", "source_transcript_key",
                    "source_transcript_hash", "duration_ms", "max_text_calls", "text_model_id"}
        if (not isinstance(config, dict) or not required.issubset(config)
                or config["source_job_id"] != lease.job_id
                or type(config["max_text_calls"]) is not int or not 1 <= config["max_text_calls"] <= 24
                or type(config["duration_ms"]) is not int or config["duration_ms"] <= 0):
            raise WorkerError("INVALID_INTENT_INPUT")
        transcript_path = context.predecessor("ASR", "transcript")
        expected_path = shared_path(context.storage_root, config["source_transcript_key"], must_exist=True)
        if (transcript_path != expected_path
                or sha256(transcript_path, guard=context.guard.check) != config["source_transcript_hash"]):
            raise WorkerError("SOURCE_HASH_MISMATCH")
        media = _read(context.predecessor("MEDIA", "media_info"))
        if media.get("duration_ms") != config["duration_ms"]:
            raise WorkerError("INVALID_INTENT_INPUT")
        transcript = json.loads(_native_path(transcript_path).read_text(encoding="utf-8"))
        if self.transport_factory is None:
            raise WorkerError("DURABLE_BUDGET_REQUIRED")
        embedder = None
        if os.environ.get("LIVECUT_INTENT_EMBEDDING_DIR"):
            from .intent_embedding import LocalIntentEmbedder

            context.guard.check()
            embedder = LocalIntentEmbedder(os.environ["LIVECUT_INTENT_EMBEDDING_DIR"])

        def bound_timeout(request: httpx.Request) -> None:
            context.guard.check()
            remaining = context.guard.remaining_seconds()
            request.extensions["timeout"] = {"connect": min(30, remaining), "read": min(300, remaining),
                                              "write": min(30, remaining), "pool": min(30, remaining)}

        text_transport = self.transport_factory(context, "text")
        with httpx.Client(transport=text_transport,
                          timeout=min(300, context.guard.remaining_seconds()),
                          event_hooks={"request": [bound_timeout]}, follow_redirects=False) as client:
            from .text_resilience import ResilientTextTransport

            client.livecut_resumable_text = isinstance(text_transport, ResilientTextTransport)
            build_clip_plan(transcript, config["duration_ms"], config["instruction"], _native_path(directory),
                request_id=config["request_id"], job_id=lease.job_id, video_id=lease.video_id,
                source_hash=lease.source_hash, transcript_hash=config["source_transcript_hash"],
                model=config["text_model_id"], max_calls=config["max_text_calls"], client=client,
                base_url=os.environ.get("LIVECUT_TEXT_BASE_URL"), dense_embedder=embedder,
                embedding_model_id=embedder.model_id if embedder else None,
                index_cache_dir=_native_path(context.storage_root / "cache" / "intent-index-v1"))
        context.guard.check()
        artifacts = [context.artifact(kind, directory / filename) for kind, filename in (
            ("clip_plan", "clip-plan.json"), ("retrieval_index", "retrieval-index.json"),
            ("retrieval_trace", "retrieval-trace.json"))]
        if _native_path(directory / "text-execution-trace.json").is_file():
            artifacts.append(context.artifact("text_execution_trace", directory / "text-execution-trace.json"))
        return {"schema_version": 1, "job_id": lease.job_id, "video_id": lease.video_id,
                "analysis_generation": lease.generation, "stage_id": lease.stage_id,
                "stage_type": lease.stage_type, "attempt": lease.attempt, "run_token": lease.run_token,
                "input_sha256": lease.source_hash, "data_kind": "model_result", "review_status": "DRAFT",
                "artifacts": artifacts}

    def __call__(self, context: StageContext) -> dict:
        if context.lease.stage_type == "EXPORT_CLIP":
            from .worker_export import ExportStageExecutor

            return ExportStageExecutor(ffmpeg_path=self.ffmpeg_path,
                                       ffprobe_path=self.ffprobe_path)(context)
        context.guard.check()
        lease, directory = context.lease, context.directory
        config = lease.config
        if "preparation_reuse" in config and self.asr_reuse_source is not None:
            raise WorkerError("PREPARATION_REUSE_CONFLICT")
        preparation_copy = lease.stage_type in {"MEDIA", "ASR"} and "preparation_reuse" in config
        if not preparation_copy:
            # Preparation-copy stages perform the same source verification in
            # their link-rejecting, guard-aware stream; hash a large MP4 once.
            source = shared_path(context.storage_root, lease.source_key, must_exist=True)
            if sha256(source, guard=context.guard.check) != lease.source_hash:
                raise WorkerError("SOURCE_HASH_MISMATCH")
        if "agent_turn" in config and "clip_request" in config:
            raise WorkerError("INVALID_AGENT_INPUT")
        if lease.stage_type == "SEGMENT" and "clip_request" in config:
            return self._execute_intent(context)
        if lease.stage_type == "SEGMENT" and "agent_turn" in config:
            return self._execute_agent(context)
        artifacts: list[dict] = []
        if preparation_copy:
            from .preparation_reuse import reuse_preparation

            artifacts = reuse_preparation(context, config["preparation_reuse"])
        elif lease.stage_type == "MEDIA":
            info = probe_media(_native_path(source), ffprobe_path=self.ffprobe_path,
                               timeout_seconds=min(30, context.guard.remaining_seconds()),
                               guard=context.guard.check)
            context.guard.check()
            audio = directory / "audio.wav"
            extract_audio(_native_path(source), _native_path(audio), ffmpeg_path=self.ffmpeg_path,
                          ffprobe_path=self.ffprobe_path,
                          timeout_seconds=min(300, context.guard.remaining_seconds()),
                          guard=context.guard.check)
            media_path = directory / "media-info.json"
            _write(media_path, {key: value for key, value in asdict(info).items() if key != "path"},
                   guard=context.guard.check)
            artifacts = [context.artifact("media_info", media_path), context.artifact("audio", audio)]
        elif lease.stage_type == "ASR":
            audio = context.predecessor("MEDIA", "audio")
            context.provider_input_hash = sha256(audio, guard=context.guard.check)
            if self.asr_reuse_source is not None:
                from .asr_reuse import reuse_asr

                artifacts = reuse_asr(context, self.asr_reuse_source, audio)
            else:
                if self.transport_factory is None:
                    raise WorkerError("DURABLE_BUDGET_REQUIRED")
                transport = self.transport_factory(context, "asr")
                transcribe_file(_native_path(audio), _native_path(directory / "asr"), transport=transport,
                                model=config.get("asr_model", "paraformer-v2"),
                                timeout_seconds=context.guard.remaining_seconds(),
                                request_timeout_seconds=min(30, context.guard.remaining_seconds()),
                                base_url=os.environ.get("LIVECUT_ASR_BASE_URL"))
                artifacts = [context.artifact("transcript", directory / "asr" / "transcript.json")]
        elif lease.stage_type == "SEGMENT":
            media = _read(context.predecessor("MEDIA", "media_info"))
            transcript = json.loads(_native_path(context.predecessor("ASR", "transcript")).read_text(encoding="utf-8"))
            observations = []
            visual_artifacts: list[dict] = []
            observer_id = "unspecified-observer"
            if config.get("visual_provider", "ocr") == "ocr":
                cached = cached_observe_video_ocr(
                    _native_path(source), _native_path(directory / "ocr"), duration_ms=media["duration_ms"],
                    cache_root=_native_path(context.storage_root / "cache" / "ocr-v1"),
                    ffmpeg_path=self.ffmpeg_path, guard=context.guard.check,
                    sampling_policy=config.get("ocr_sampling", "prefix"),
                    max_frames=config.get("ocr_max_frames", 16),
                )
                observations = cached.observations
                context.guard.check()
                provenance_path = directory / "ocr" / "cache-provenance.json"
                _write(provenance_path, cached.provenance)
                visual_artifacts = [
                    context.artifact("ocr_cache_provenance", provenance_path),
                    context.artifact("ocr_observations", directory / "ocr" / "observations.json"),
                    context.artifact("ocr_frames", directory / "ocr" / "frames.json"),
                    context.artifact("ocr_state", directory / "ocr" / "ocr-state.json"),
                ]
                visual_artifacts.extend(context.artifact(
                    f"ocr_frame_{index + 1:06d}", directory / "ocr" / item["object_key"])
                    for index, item in enumerate(observations))
                observer_id = ocr_model_id()
            elif config.get("visual_provider") != "none":
                raise WorkerError("UNSUPPORTED_VISUAL_PROVIDER")
            # Persist frame keys relative to the shared root so Java/frontend can
            # serve them without exposing Windows paths.
            observations = [dict(item, object_key=context.artifact(
                "frame", directory / "ocr" / item["object_key"])["key"]) for item in observations]
            if self.transport_factory is None:
                raise WorkerError("DURABLE_BUDGET_REQUIRED")
            context.guard.check()
            topics = config.get("analysis_mode", "commerce") == "topics"

            def bound_topic_timeout(request: httpx.Request) -> None:
                # Generation may be slow; refresh every wait limit against the
                # current stage deadline before the budget transport dispatches.
                remaining = context.guard.remaining_seconds()
                request.extensions["timeout"] = {
                    "connect": min(30, remaining), "read": min(300, remaining),
                    "write": min(30, remaining), "pool": min(30, remaining),
                }

            text_transport = self.transport_factory(context, "text")
            with httpx.Client(transport=text_transport,
                              timeout=min(300 if topics else 120, context.guard.remaining_seconds()),
                              event_hooks={"request": [bound_topic_timeout]} if topics else None,
                              follow_redirects=False) as client:
                from .text_resilience import ResilientTextTransport

                client.livecut_resumable_text = isinstance(text_transport, ResilientTextTransport)
                if config.get("analysis_mode", "commerce") == "topics":
                    discover_topics(
                        transcript, media["duration_ms"], _native_path(directory), client=client,
                        model=config.get("text_model", "qwen-plus"), video_id=lease.video_id,
                        job_id=lease.job_id, asr_model_id=config.get("asr_model", "paraformer-v2"),
                        analysis_generation=lease.generation,
                        max_calls=config.get("max_text_calls", 12), visual_observations=observations,
                        base_url=os.environ.get("LIVECUT_TEXT_BASE_URL"))
                elif config.get("analysis_mode", "commerce") == "commerce":
                    discover_products(
                        transcript, media["duration_ms"], _native_path(directory), client=client,
                        model=config.get("text_model", "qwen-plus"), video_id=lease.video_id,
                        job_id=lease.job_id, asr_model_id=config.get("asr_model", "paraformer-v2"),
                        analysis_generation=lease.generation,
                        max_calls=config.get("max_text_calls", 12), visual_observations=observations,
                        visual_model_id=observer_id, two_pass=True,
                        base_url=os.environ.get("LIVECUT_TEXT_BASE_URL"),
                    )
                else:
                    raise WorkerError("UNSUPPORTED_ANALYSIS_MODE")
            artifacts = [context.artifact("analysis_result", directory / "analysis-result.json"),
                         *visual_artifacts]
            if _native_path(directory / "text-execution-trace.json").is_file():
                artifacts.append(context.artifact("text_execution_trace", directory / "text-execution-trace.json"))
        elif lease.stage_type == "TIMELINE":
            result_path = context.predecessor("SEGMENT", "analysis_result")
            result = _read(result_path)
            if config.get("analysis_mode", "commerce") == "topics":
                validate_topic_result(result)
            elif config.get("analysis_mode", "commerce") == "commerce":
                validate_analysis_result(result)
            else:
                raise WorkerError("UNSUPPORTED_ANALYSIS_MODE")
            if (result.get("data_kind") != "model_result" or result.get("job_id") != lease.job_id
                    or result.get("video_id") != lease.video_id
                    or result.get("analysis_generation") != lease.generation
                    or result.get("review_status") != "DRAFT"):
                raise WorkerError("ARTIFACT_INPUT_MISMATCH")
            _write(directory / "analysis-result.json", result)
            artifacts = [context.artifact("analysis_result", directory / "analysis-result.json")]
        context.guard.check()
        return {"schema_version": 1, "job_id": lease.job_id, "stage_id": lease.stage_id,
                "video_id": lease.video_id, "analysis_generation": lease.generation,
                "stage_type": lease.stage_type, "attempt": lease.attempt,
                "run_token": lease.run_token,
                "input_sha256": lease.source_hash, "data_kind": "model_result",
                "review_status": "DRAFT", "artifacts": artifacts}
