"""Independent, fenced URL-import worker; it never creates an analysis stage."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from uuid import UUID, uuid4

from .media import MediaError, _require_aligned_timeline, _run_process, probe_media
from .source_import_download import ImportDownloadError, validate_url
from .worker import _single_worker
from .worker_client import WorkerClient, WorkerClientError


class SourceImportError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _uuid(value):
    try:
        result = str(UUID(value))
        if result != value:
            raise ValueError
        return result
    except (ValueError, TypeError, AttributeError):
        raise SourceImportError("SOURCE_IMPORT_INVALID_CLAIM") from None


def _instant(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError
        return parsed.timestamp()
    except (TypeError, AttributeError, ValueError, OverflowError):
        raise SourceImportError("SOURCE_IMPORT_INVALID_CLAIM") from None


def _atomic(path: Path, value):
    _safe(path.parent)
    temporary = path.with_suffix(path.suffix + ".tmp")
    if temporary.exists() and temporary.is_symlink():
        raise SourceImportError("SOURCE_IMPORT_UNSAFE_PATH")
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=True)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


def _read(path: Path):
    if path.stat().st_size > 128 * 1024 or path.is_symlink():
        raise SourceImportError("SOURCE_IMPORT_INVALID_JOURNAL")
    try:
        return json.loads(path.read_text("utf-8"))
    except (OSError, ValueError):
        raise SourceImportError("SOURCE_IMPORT_INVALID_JOURNAL") from None


def _safe(path: Path):
    for part in (path, *path.parents):
        if part.exists() and (part.is_symlink() or getattr(part.lstat(), "st_file_attributes", 0) & 0x400):
            raise SourceImportError("SOURCE_IMPORT_UNSAFE_PATH")


def _size(directory: Path):
    size = 0
    for item in directory.rglob("*"):
        _safe(item)
        if item.is_file():
            size += item.stat().st_size
    return size


def _cleanup_temporary(directory):
    """Remove only this attempt's fixed temporary files, never source.mp4."""
    _safe(directory)
    for name in ("stream-0.media", "stream-1.media", "normalized.mp4",
                 "download-progress.json", "download-progress.tmp"):
        target = directory / name
        if target.is_symlink() or target.is_file():
            target.unlink(missing_ok=True)


@dataclass
class ImportClaim:
    id: str
    url: str
    attempt: int
    run_token: str
    lease_until: float
    hard_deadline_at: float
    max_bytes: int

    @classmethod
    def parse(cls, value):
        fields = {"id", "url", "attempt", "run_token", "lease_until", "hard_deadline_at", "max_bytes"}
        if (not isinstance(value, dict) or set(value) != fields
                or type(value["attempt"]) is not int or not 1 <= value["attempt"] <= 3
                or type(value["max_bytes"]) is not int or not 1 <= value["max_bytes"] <= 2**40):
            raise SourceImportError("SOURCE_IMPORT_INVALID_CLAIM")
        try:
            validate_url(value["url"])
        except ImportDownloadError as error:
            raise SourceImportError(str(error)) from None
        lease, deadline = _instant(value["lease_until"]), _instant(value["hard_deadline_at"])
        if lease > deadline:
            raise SourceImportError("SOURCE_IMPORT_INVALID_CLAIM")
        return cls(_uuid(value["id"]), value["url"], value["attempt"], _uuid(value["run_token"]),
                   lease, deadline, value["max_bytes"])

    def identity(self):
        return {"attempt": self.attempt, "run_token": self.run_token}


class SourceImportClient:
    def __init__(self, client: WorkerClient):
        self.client = client

    def claim(self, body):
        response = self.client._request("POST", "/internal/source-imports/claim", json=body)
        if set(response) != {"claim"}:
            raise SourceImportError("SOURCE_IMPORT_INVALID_CLAIM")
        return response["claim"]

    def callback(self, import_id, action, body):
        _uuid(import_id)
        if action not in {"heartbeat", "complete", "fail"}:
            raise SourceImportError("SOURCE_IMPORT_INVALID_JOURNAL")
        value = self.client._request("POST", f"/internal/source-imports/{import_id}/{action}", json=body)
        if action != "heartbeat":
            fields = {"id", "url", "status", "phase", "progress_percent", "downloaded_bytes",
                      "video_id", "error_code", "attempt", "created_at", "updated_at"}
            if (set(value) != fields or value["id"] != import_id or value["attempt"] != body["attempt"]
                    or value["status"] != ("COMPLETED" if action == "complete" else "FAILED")):
                raise WorkerClientError("INVALID_JAVA_RESPONSE", retryable=True)
            if action == "complete":
                try:
                    _uuid(value["video_id"])
                except SourceImportError:
                    raise WorkerClientError("INVALID_JAVA_RESPONSE", retryable=True) from None
            elif value["video_id"] is not None or value["error_code"] != body["error_code"]:
                raise WorkerClientError("INVALID_JAVA_RESPONSE", retryable=True)
        return value


class ImportGuard:
    def __init__(self, client, claim, directory, *, heartbeat_seconds=15, clock=time.time,
                 cancelled=lambda: False):
        self.client, self.claim, self.directory = client, claim, directory
        self.heartbeat_seconds, self.clock, self.cancelled = heartbeat_seconds, clock, cancelled
        self.next_heartbeat = 0
        self.phase = "DOWNLOADING"
        self.downloaded_bytes = 0

    def check(self):
        now = self.clock()
        if self.cancelled():
            raise SourceImportError("SOURCE_IMPORT_STOPPED")
        if now >= self.claim.hard_deadline_at:
            raise SourceImportError("SOURCE_IMPORT_TIMEOUT")
        if now >= self.claim.lease_until:
            raise SourceImportError("SOURCE_IMPORT_LEASE_EXPIRED")
        # Download input <= max_bytes and normalized output <= max_bytes.
        # The small extra allowance covers two bounded receipts, never media.
        if _size(self.directory) > self.claim.max_bytes * 2 + 256 * 1024:
            raise SourceImportError("SOURCE_IMPORT_STAGING_LIMIT")
        if now >= self.next_heartbeat:
            body = {**self.claim.identity(), "phase": self.phase, "progress_percent": None,
                    "downloaded_bytes": self.downloaded_bytes}
            response = self.client.callback(self.claim.id, "heartbeat", body)
            if set(response) != {"lease_until", "hard_deadline_at"}:
                raise SourceImportError("SOURCE_IMPORT_INVALID_CLAIM")
            lease, deadline = _instant(response["lease_until"]), _instant(response["hard_deadline_at"])
            if deadline != self.claim.hard_deadline_at or lease > deadline or lease <= self.clock():
                raise SourceImportError("SOURCE_IMPORT_INVALID_CLAIM")
            self.claim.lease_until = lease
            self.next_heartbeat = now + self.heartbeat_seconds

    def remaining(self):
        self.check()
        return max(0.001, min(self.claim.lease_until, self.claim.hard_deadline_at) - self.clock())


def _download_child(claim, directory, guard, *, metadata_only=False, timeout_seconds=None):
    request = {"url": claim.url, "directory": str(directory), "max_bytes": claim.max_bytes,
               "metadata_only": metadata_only}
    env = {key: value for key, value in os.environ.items()
           if key.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL"}}
    command = [sys.executable, "-I", "-m", "livecut.source_import_download"]
    deadline = min(claim.hard_deadline_at, time.time() + (timeout_seconds or 3600))
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                                   shell=False, env=env,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            process.stdin.write(json.dumps(request).encode())
            process.stdin.close()
            while True:
                progress = directory / "download-progress.json"
                if progress.exists():
                    value = _read(progress).get("downloaded_bytes")
                    if type(value) is not int or not 0 <= value <= claim.max_bytes:
                        raise SourceImportError("SOURCE_IMPORT_TOO_LARGE")
                    guard.downloaded_bytes = value
                guard.check()
                if time.time() >= deadline:
                    raise SourceImportError("SOURCE_IMPORT_TIMEOUT")
                if os.fstat(stdout.fileno()).st_size + os.fstat(stderr.fileno()).st_size > 1024 * 1024:
                    raise SourceImportError("SOURCE_IMPORT_DOWNLOAD_FAILED")
                try:
                    process.wait(timeout=0.2)
                    break
                except subprocess.TimeoutExpired:
                    pass
            stdout.seek(0)
            try:
                value = json.loads(stdout.read(65537))
                if not isinstance(value, dict):
                    raise ValueError
            except (TypeError, ValueError):
                raise SourceImportError("SOURCE_IMPORT_DOWNLOAD_FAILED") from None
            if not value.get("ok"):
                code = value.get("error_code")
                if not isinstance(code, str) or not re.fullmatch(r"SOURCE_IMPORT_[A-Z_]{1,60}", code):
                    code = "SOURCE_IMPORT_DOWNLOAD_FAILED"
                raise SourceImportError(code)
            if process.returncode != 0 or not isinstance(value.get("result"), dict):
                raise SourceImportError("SOURCE_IMPORT_DOWNLOAD_FAILED")
            guard.check()
            return value["result"]
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)


def normalize_download(result, claim, directory, guard, *, ffmpeg_path="ffmpeg", ffprobe_path="ffprobe"):
    files = result.get("files")
    if (not isinstance(files, list) or not 1 <= len(files) <= 2
            or files != [f"stream-{i}.media" for i in range(len(files))]):
        raise SourceImportError("SOURCE_IMPORT_DOWNLOAD_FAILED")
    inputs = [directory / name for name in files]
    if any(not file.is_file() or not 0 < file.stat().st_size <= claim.max_bytes for file in inputs):
        raise SourceImportError("SOURCE_IMPORT_DOWNLOAD_FAILED")
    if sum(file.stat().st_size for file in inputs) > claim.max_bytes:
        raise SourceImportError("SOURCE_IMPORT_TOO_LARGE")
    guard.downloaded_bytes = sum(file.stat().st_size for file in inputs)
    guard.phase, guard.next_heartbeat = "NORMALIZING", 0
    guard.check()
    temporary, target = directory / "normalized.mp4", directory / "source.mp4"
    if temporary.exists() or target.exists():
        raise SourceImportError("SOURCE_IMPORT_OUTPUT_CONFLICT")
    timing = _input_timing(inputs, claim, guard, ffprobe_path)
    # Demuxers receive only local file protocol. Downloaded playlists cannot
    # make FFmpeg fetch network URLs, even if a remote body masquerades as media.
    command = [str(ffmpeg_path), "-nostdin", "-hide_banner", "-v", "error", "-n", "-copyts"]
    for source in inputs:
        command += ["-protocol_whitelist", "file", "-format_whitelist",
                    "mov,matroska,webm,mpegts,aac,mp3,flv", "-i", str(source)]
    command += ["-map", "0:v:0", "-map", "0:a:0" if len(inputs) == 1 else "1:a:0",
                "-vf", f"setpts=PTS-STARTPTS,tpad=start_mode=add:color=black:start_duration={timing['video_delay']:.9f},scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-af", f"asetpts=PTS-({timing['origin']:.9f})/TB,aresample=async=1:first_pts=0",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2",
                "-map_metadata", "-1", "-movflags", "+faststart", "-t", "14400",
                "-fs", str(claim.max_bytes + 1), str(temporary)]
    _run_process(command, timeout_seconds=max(0.001, claim.hard_deadline_at - time.time()),
                 failure_code="SOURCE_IMPORT_NORMALIZE_FAILED", guard=guard.check)
    guard.phase, guard.next_heartbeat = "VERIFYING", 0
    guard.check()
    if not temporary.is_file() or not 0 < temporary.stat().st_size <= claim.max_bytes:
        raise SourceImportError("SOURCE_IMPORT_TOO_LARGE")
    info = probe_media(temporary, ffprobe_path=ffprobe_path,
                       timeout_seconds=min(30, guard.remaining()), guard=guard.check)
    _require_aligned_timeline(info)
    if info.audio_codec != "aac" or info.duration_ms > 4 * 3600 * 1000:
        raise SourceImportError("SOURCE_IMPORT_AUDIO_REQUIRED")
    if abs(info.duration_ms - timing["duration"] * 1000) > 500:
        raise SourceImportError("SOURCE_IMPORT_DURATION_MISMATCH")
    digest = hashlib.sha256()
    with temporary.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            guard.check()
            digest.update(chunk)
    guard.check()
    size = temporary.stat().st_size
    os.replace(temporary, target)
    # Keep only the validated atomic final file plus small local receipts.
    for file in inputs:
        file.unlink()
    return {**claim.identity(), "sha256": digest.hexdigest(), "size_bytes": size}


def _input_timing(inputs, claim, guard, ffprobe_path):
    streams = []
    for source in inputs:
        response = _run_process([str(ffprobe_path), "-v", "error", "-protocol_whitelist", "file",
            "-format_whitelist", "mov,matroska,webm,mpegts,aac,mp3,flv", "-show_format", "-show_streams",
            "-of", "json", str(source)], timeout_seconds=min(30, guard.remaining()),
            failure_code="SOURCE_IMPORT_PROBE_FAILED", guard=guard.check)
        try:
            value = json.loads(response.stdout)
            for stream in value["streams"]:
                if stream.get("codec_type") not in {"video", "audio"}:
                    continue
                start = float(stream["start_time"])
                duration = float(stream.get("duration", value["format"].get("duration")))
                if not math.isfinite(start) or not math.isfinite(duration) or not 0 < duration <= 14400:
                    raise ValueError
                streams.append((stream["codec_type"], start, duration))
        except (ValueError, TypeError, KeyError):
            raise SourceImportError("SOURCE_IMPORT_UNSUPPORTED_TIMELINE") from None
    videos, audios = [s for s in streams if s[0] == "video"], [s for s in streams if s[0] == "audio"]
    if len(videos) != 1 or len(audios) != 1:
        raise SourceImportError("SOURCE_IMPORT_AUDIO_REQUIRED")
    # Normalize both tracks against one common origin. Missing leading video is
    # black padding (rounded by FFmpeg to its frame grid); missing leading audio
    # is silence. Never independently pull both first observations to time zero.
    if abs(videos[0][1] - audios[0][1]) > 5:
        raise SourceImportError("SOURCE_IMPORT_UNSUPPORTED_TIMELINE")
    if abs(videos[0][2] - audios[0][2]) > 2:
        raise SourceImportError("SOURCE_IMPORT_DURATION_MISMATCH")
    origin = min(videos[0][1], audios[0][1])
    duration = max(videos[0][1] - origin + videos[0][2], audios[0][1] - origin + audios[0][2])
    if duration > 14400:
        raise SourceImportError("SOURCE_IMPORT_DURATION_LIMIT")
    return {"duration": duration, "video_delay": videos[0][1] - origin, "origin": origin}


class SourceImportWorker:
    def __init__(self, client, storage_root, journal_dir, worker_id="source-import-local", *,
                 downloader=_download_child, normalizer=normalize_download, clock=time.time):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", worker_id):
            raise SourceImportError("SOURCE_IMPORT_INVALID_WORKER")
        self.client, self.storage_root, self.journal_dir = client, Path(storage_root).resolve(), Path(journal_dir).resolve()
        self.worker_id, self.downloader, self.normalizer, self.clock = worker_id, downloader, normalizer, clock
        self.session = str(uuid4())
        self.stopped = False
        self.journal_dir.mkdir(parents=True, exist_ok=True)
        _safe(self.journal_dir)

    def _callback(self):
        path = self.journal_dir / "pending-callback.json"
        if not path.exists():
            return None
        value = _read(path)
        if (set(value) != {"id", "action", "body", "lease_until", "hard_deadline_at"}
                or value["action"] not in {"complete", "fail"}
                or not isinstance(value["body"], dict)
                or type(value["lease_until"]) not in (int, float)
                or type(value["hard_deadline_at"]) not in (int, float)
                or not math.isfinite(value["lease_until"])
                or not math.isfinite(value["hard_deadline_at"])):
            raise SourceImportError("SOURCE_IMPORT_INVALID_JOURNAL")
        _uuid(value["id"])
        body = value["body"]
        fields = {"attempt", "run_token"} | ({"sha256", "size_bytes"} if value["action"] == "complete" else {"error_code"})
        if set(body) != fields or type(body["attempt"]) is not int or not 1 <= body["attempt"] <= 3:
            raise SourceImportError("SOURCE_IMPORT_INVALID_JOURNAL")
        _uuid(body["run_token"])
        if (value["action"] == "complete" and (not isinstance(body["sha256"], str)
                or not re.fullmatch(r"[a-f0-9]{64}", body["sha256"])
                or type(body["size_bytes"]) is not int or body["size_bytes"] <= 0)
                or value["action"] == "fail" and (not isinstance(body["error_code"], str)
                or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", body["error_code"]))):
            raise SourceImportError("SOURCE_IMPORT_INVALID_JOURNAL")
        # Replay only the immutable exact callback even after its old lease:
        # Java may already have committed success before its reply was lost.
        # This never renews the claim or re-executes download/normalization.
        try:
            response = self.client.callback(value["id"], value["action"], value["body"])
        except WorkerClientError as error:
            if error.code in {"STALE_IMPORT_ATTEMPT", "SOURCE_IMPORT_NOT_FOUND"}:
                path.replace(self.journal_dir / f"stale-callback-{uuid4()}.json")
                return "stale_callback"
            raise
        _atomic(self.journal_dir / "last-receipt.json", {"request": value, "response": response})
        path.unlink()
        return "callback_replayed"

    def run_once(self):
        with _single_worker(self.journal_dir):
            replayed = self._callback()
            if replayed:
                return replayed
            pending = self.journal_dir / "pending-claim.json"
            if pending.exists():
                saved = _read(pending)
                if saved.get("session") != self.session:
                    # Never resend another process session's unknown claim.
                    pending.replace(self.journal_dir / f"abandoned-claim-{uuid4()}.json")
                elif set(saved) != {"session", "body"}:
                    raise SourceImportError("SOURCE_IMPORT_INVALID_JOURNAL")
            if not pending.exists():
                _atomic(pending, {"session": self.session, "body": {
                    "worker_id": self.worker_id, "claim_request_id": str(uuid4())}})
            value = self.client.claim(_read(pending)["body"])
            if value is None:
                pending.unlink()
                return "idle"
            claim = ImportClaim.parse(value)
            # Retain an authenticated accepted claim before clearing uncertainty.
            _atomic(self.journal_dir / "last-claim.json", {"session": self.session, "claim": value})
            pending.unlink()
            directory = self.storage_root / "imports" / claim.id / "attempts" / str(claim.attempt) / claim.run_token
            _safe(directory)
            directory.mkdir(parents=True, exist_ok=True)
            guard = ImportGuard(self.client, claim, directory, clock=self.clock, cancelled=lambda: self.stopped)
            try:
                guard.check()
                result = self.downloader(claim, directory, guard)
                body = self.normalizer(result, claim, directory, guard)
                action = "complete"
            except WorkerClientError as error:
                # Cancellation/lease fencing and uncertain heartbeat responses
                # stop the child immediately; never publish through a lost lease.
                if error.code in {"STALE_IMPORT_ATTEMPT", "SOURCE_IMPORT_NOT_FOUND"}:
                    return "stale"
                raise
            except (SourceImportError, MediaError) as error:
                if error.code in {"SOURCE_IMPORT_LEASE_EXPIRED", "SOURCE_IMPORT_TIMEOUT"}:
                    return "expired"
                body, action = {**claim.identity(), "error_code": error.code}, "fail"
            finally:
                # Child and FFmpeg are already stopped before this runs, even
                # for KeyboardInterrupt, stale ownership or control-plane loss.
                # Unknown complete callbacks keep the validated final MP4.
                _cleanup_temporary(directory)
            callback = {"id": claim.id, "action": action, "body": body,
                        "lease_until": claim.lease_until, "hard_deadline_at": claim.hard_deadline_at}
            _atomic(self.journal_dir / "pending-callback.json", callback)
            self._callback()
            return "completed" if action == "complete" else "failed"


def main() -> int:
    parser = argparse.ArgumentParser(description="Import one public video without creating analysis jobs")
    parser.add_argument("--java-url", default="http://127.0.0.1:8080")
    parser.add_argument("--storage-root", required=True, type=Path)
    parser.add_argument("--journal-dir", required=True, type=Path)
    parser.add_argument("--worker-id", default="source-import-local")
    parser.add_argument("--poll-seconds", type=float, default=3)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    if not math.isfinite(args.poll_seconds) or not 0.2 <= args.poll_seconds <= 60:
        parser.error("poll-seconds must be between 0.2 and 60")
    client = None
    try:
        client = WorkerClient(args.java_url, os.environ.get("LIVECUT_WORKER_API_KEY", ""))
        def normalize(result, claim, directory, guard):
            return normalize_download(result, claim, directory, guard,
                ffmpeg_path=os.environ.get("LIVECUT_FFMPEG_PATH", "ffmpeg"),
                ffprobe_path=os.environ.get("LIVECUT_FFPROBE_PATH", "ffprobe"))
        worker = SourceImportWorker(SourceImportClient(client), args.storage_root, args.journal_dir,
                                    args.worker_id, normalizer=normalize)
        while True:
            try:
                print(json.dumps({"status": worker.run_once()}), flush=True)
            except WorkerClientError as error:
                print(json.dumps({"error_code": error.code}), flush=True)
                if args.once or not error.retryable:
                    return 1
            if args.once:
                return 0
            time.sleep(args.poll_seconds)
    except KeyboardInterrupt:
        return 130
    except (SourceImportError, WorkerClientError, OSError) as error:
        print(json.dumps({"error_code": getattr(error, "code", "SOURCE_IMPORT_LOCAL_ERROR")}))
        return 1
    finally:
        if client:
            client.close()


if __name__ == "__main__":
    raise SystemExit(main())
