"""Single-slot computing Worker. Java alone changes business stage state.

The local journal records uncertain claims and pending callbacks. A lost
completion response replays the same callback without rerunning computation.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import threading
import time
import traceback
from contextlib import contextmanager
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
from .worker_client import WorkerClient, WorkerClientError

STAGES = ("MEDIA", "ASR", "SEGMENT", "TIMELINE", "EXPORT_CLIP")


class WorkerError(Exception):
    def __init__(self, code: str, *, retryable: bool = False) -> None:
        super().__init__(code)
        self.code, self.retryable = code, retryable


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


def _write(path: Path, value: object) -> None:
    target = _native_path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("wb", dir=target.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(_json_bytes(value))
            handle.flush()
            os.fsync(handle.fileno())
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


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with _native_path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
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
                 *, now: Callable[[], float] = time.time) -> None:
        self._now, self._stop = now, stop
        self._lease_until = _timestamp(lease.lease_until)
        self._deadline = _timestamp(lease.hard_deadline_at)
        self._lost = threading.Event()
        self._lock = threading.Lock()

    def check(self) -> None:
        if self._stop.is_set():
            raise WorkerError("WORKER_STOPPED")
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

    def artifact(self, kind: str, path: Path) -> dict:
        resolved = path.resolve()
        if not resolved.is_relative_to(self.directory.resolve()) or not _native_path(resolved).is_file():
            raise WorkerError("UNSAFE_OUTPUT_ARTIFACT")
        return {"kind": kind, "key": resolved.relative_to(self.storage_root.resolve()).as_posix(),
                "sha256": sha256(resolved)}

    def predecessor(self, stage_type: str, kind: str) -> Path:
        matches = [item for item in self.lease.artifacts if item.get("stage_type") == stage_type]
        if len(matches) != 1:
            raise WorkerError("MISSING_PREDECESSOR")
        reference = matches[0]
        manifest_path = shared_path(self.storage_root, reference["manifest_key"], must_exist=True)
        if sha256(manifest_path) != reference["manifest_hash"]:
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
        if sha256(path) != artifact["sha256"]:
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
    ) -> None:
        self.client, self.storage_root = client, storage_root.resolve()
        self.journal_dir, self.worker_id, self.executor = journal_dir.resolve(), _identifier(worker_id), executor
        if heartbeat_seconds <= 0 or heartbeat_seconds > 15:
            raise WorkerError("INVALID_HEARTBEAT_INTERVAL")
        self.heartbeat_seconds = heartbeat_seconds
        self.stop = stop if stop is not None else threading.Event()
        self.target_job_id = (WorkerClient._job_id(target_job_id)
                              if target_job_id is not None else None)
        _native_path(self.journal_dir).mkdir(parents=True, exist_ok=True)

    def _check_target_record(self, value: dict) -> None:
        if self.target_job_id is not None and value.get("job_id") != self.target_job_id:
            raise WorkerError("WORKER_JOB_TARGET_MISMATCH")

    def _pending_callback(self) -> bool:
        pending = self.journal_dir / "pending-callback.json"
        if not _native_path(pending).exists():
            return False
        value = _read(pending)
        self._check_target_record(value)
        try:
            response = self.client.callback(value["action"], value["stage_id"], value["payload"])
            if response.get("outcome") not in {"ACCEPTED", "ALREADY_APPLIED"}:
                raise WorkerClientError("INVALID_JAVA_RESPONSE", retryable=True)
        except WorkerClientError as exc:
            if not exc.retryable:
                # A stale result stays auditable locally but can never overwrite
                # the current business attempt.
                _write(self.journal_dir / "rejected-callback.json", value)
                _native_path(pending).unlink()
            raise
        _native_path(pending).unlink()
        return True

    def _heartbeat(self, context: StageContext, finished: threading.Event) -> None:
        while not finished.wait(self.heartbeat_seconds):
            try:
                context.guard.check()
                response = self.client.callback("heartbeat", context.lease.stage_id,
                                                context.lease.credentials)
                context.guard.renew(response)
            except (WorkerClientError, WorkerError):
                context.guard.lose()
                return

    def run_once(self, *, stage_type: str | None = None) -> str:
        with _single_worker(self.journal_dir):
            return self._run_once(stage_type=stage_type)

    def _run_once(self, *, stage_type: str | None = None) -> str:
        if self._pending_callback():
            return "callback_replayed"
        if self.stop.is_set():
            return "stopped"
        claim_path = self.journal_dir / "pending-claim.json"
        if _native_path(claim_path).exists():
            pending = _read(claim_path)
            self._check_target_record(pending)
        else:
            target = {"job_id": self.target_job_id} if self.target_job_id is not None else {}
            ready = self.client.ready(stage_type=stage_type, limit=1, **target)
            items = ready.get("items")
            if not isinstance(items, list):
                raise WorkerError("INVALID_JAVA_RESPONSE")
            if not items:
                return "idle"
            if self.target_job_id is not None and (not isinstance(items[0], dict)
                                                  or items[0].get("job_id") != self.target_job_id):
                raise WorkerError("CLAIM_TARGET_CHANGED")
            pending = {"stage_id": _identifier(items[0].get("id")),
                       "worker_id": self.worker_id, "claim_request_id": str(uuid4())}
            pending.update(target)
            _write(claim_path, pending)
        if pending.get("worker_id") != self.worker_id:
            raise WorkerError("WORKER_ID_MISMATCH")
        target = {"job_id": self.target_job_id} if self.target_job_id is not None else {}
        claim = self.client.claim(pending["stage_id"], self.worker_id, pending["claim_request_id"], **target)
        if claim.get("outcome") in {"NO_WORK", "EXPIRED"}:
            _native_path(claim_path).unlink()
            return "unavailable"
        # A malformed frozen export still has valid fenced credentials. Defer
        # its configuration check into the failure callback boundary below.
        lease = Lease.parse(claim, validate_export=False)
        if self.target_job_id is not None and lease.job_id != self.target_job_id:
            raise WorkerError("CLAIM_TARGET_CHANGED")
        if lease.stage_id != pending["stage_id"]:
            raise WorkerError("CLAIM_TARGET_CHANGED")
        if stage_type is not None and lease.stage_type != stage_type:
            raise WorkerError("CLAIM_STAGE_TYPE_CHANGED")
        directory = shared_path(self.storage_root, lease.attempt_key)
        _native_path(directory).mkdir(parents=True, exist_ok=True)
        guard = LeaseGuard(lease, self.stop)
        context = StageContext(lease, self.storage_root, directory, guard)
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

                expected.update(asdict(ExportSpec.from_lease(lease)))
                expected.update(data_kind="reviewed_export", review_status="CONFIRMED",
                                stage_input_hash=lease.input_hash)
            if any(manifest.get(key) != value for key, value in expected.items()):
                raise WorkerError("INVALID_RESULT_MANIFEST")
            if not isinstance(manifest.get("artifacts"), list):
                raise WorkerError("INVALID_RESULT_MANIFEST")
            for artifact in manifest["artifacts"]:
                if not isinstance(artifact, dict) or not isinstance(artifact.get("key"), str):
                    raise WorkerError("INVALID_RESULT_MANIFEST")
                file = shared_path(self.storage_root, artifact["key"], must_exist=True)
                if not file.is_relative_to(directory.resolve()) or sha256(file) != artifact.get("sha256"):
                    raise WorkerError("INVALID_RESULT_MANIFEST")
            manifest_path = directory / "manifest.json"
            _write(manifest_path, manifest)
            payload = {**lease.credentials, "manifest_key": lease.attempt_key + "/manifest.json",
                       "manifest_hash": sha256(manifest_path)}
            callback = {"action": "complete", "stage_id": lease.stage_id, "payload": payload}
        except Exception as exc:
            code = getattr(exc, "code", "WORKER_EXECUTION_FAILED")
            if not isinstance(code, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,79}", code):
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
        finally:
            finished.set()
            thread.join(timeout=12)
        # Write the exact callback before any network mutation. Never regenerate
        # its hash or recompute a stage merely because the response was lost.
        if self.target_job_id is not None:
            callback["job_id"] = self.target_job_id
        _write(result_path, callback)
        _write(callback_path, callback)
        _native_path(claim_path).unlink(missing_ok=True)
        self._pending_callback()
        return "completed" if callback["action"] == "complete" else "failed"

    def run(self, *, poll_seconds: float = 3, stage_type: str | None = None) -> None:
        if poll_seconds <= 0:
            raise WorkerError("INVALID_POLL_INTERVAL")
        while not self.stop.is_set():
            try:
                status = self.run_once(stage_type=stage_type)
            except WorkerClientError as exc:
                if not exc.retryable and exc.code not in {"STALE_ATTEMPT", "LEASE_EXPIRED"}:
                    raise
                status = "unavailable"
            if status in {"idle", "unavailable"}:
                self.stop.wait(poll_seconds)


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

    def __call__(self, context: StageContext) -> dict:
        if context.lease.stage_type == "EXPORT_CLIP":
            from .worker_export import ExportStageExecutor

            return ExportStageExecutor(ffmpeg_path=self.ffmpeg_path,
                                       ffprobe_path=self.ffprobe_path)(context)
        context.guard.check()
        lease, directory = context.lease, context.directory
        source = shared_path(context.storage_root, lease.source_key, must_exist=True)
        if sha256(source) != lease.source_hash:
            raise WorkerError("SOURCE_HASH_MISMATCH")
        config = lease.config
        artifacts: list[dict] = []
        if lease.stage_type == "MEDIA":
            info = probe_media(_native_path(source), ffprobe_path=self.ffprobe_path,
                               timeout_seconds=min(30, context.guard.remaining_seconds()))
            context.guard.check()
            audio = directory / "audio.wav"
            extract_audio(_native_path(source), _native_path(audio), ffmpeg_path=self.ffmpeg_path,
                          ffprobe_path=self.ffprobe_path,
                          timeout_seconds=min(300, context.guard.remaining_seconds()))
            media_path = directory / "media-info.json"
            _write(media_path, {key: value for key, value in asdict(info).items() if key != "path"})
            artifacts = [context.artifact("media_info", media_path), context.artifact("audio", audio)]
        elif lease.stage_type == "ASR":
            audio = context.predecessor("MEDIA", "audio")
            context.provider_input_hash = sha256(audio)
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
            with httpx.Client(transport=self.transport_factory(context, "text"),
                              timeout=min(120, context.guard.remaining_seconds()),
                              follow_redirects=False) as client:
                discover_products(
                    transcript, media["duration_ms"], _native_path(directory), client=client,
                    model=config.get("text_model", "qwen-plus"), video_id=lease.video_id,
                    job_id=lease.job_id, asr_model_id=config.get("asr_model", "paraformer-v2"),
                    analysis_generation=lease.generation,
                    max_calls=config.get("max_text_calls", 12), visual_observations=observations,
                    visual_model_id=observer_id, two_pass=True,
                    base_url=os.environ.get("LIVECUT_TEXT_BASE_URL"),
                )
            artifacts = [context.artifact("analysis_result", directory / "analysis-result.json"),
                         *visual_artifacts]
        elif lease.stage_type == "TIMELINE":
            result_path = context.predecessor("SEGMENT", "analysis_result")
            result = _read(result_path)
            validate_analysis_result(result)
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
