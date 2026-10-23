"""Resumable Bailian Paraformer file ASR, without implicit paid retries.

Official contracts (checked 2026-10-23):
https://help.aliyun.com/zh/model-studio/paraformer-recorded-speech-recognition-restful-api
https://help.aliyun.com/zh/model-studio/get-temporary-file-url/

Temporary uploads are a personal POC input mechanism, not durable production
storage. Checkpoints/raw responses must live in the caller's ignored data area.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import tempfile
import time
import wave
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator
from urllib.parse import quote, urlsplit
from uuid import uuid4

import httpx


class ASRError(Exception):
    """Sanitized provider/local failure; never contains keys or signed URLs."""

    def __init__(self, code: str, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


def _atomic_json(path: Path, data: Any, *, secret: str | None = None) -> None:
    text = json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if secret:
        text = text.replace(secret, "[REDACTED]")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, suffix=".tmp", delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except OSError:
        raise ASRError("STORAGE_ERROR", "Unable to persist the ASR checkpoint or response") from None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _read_json(path: Path) -> Any:
    try:
        with path.open(encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        raise ASRError("INVALID_CHECKPOINT", "ASR state or cached response is unreadable; do not automatically resubmit") from None


@contextmanager
def _exclusive_output(directory: Path) -> Iterator[None]:
    """An OS lock releases after a process crash; file existence is not a lock."""
    try:
        lock_handle = (directory / ".asr.lock").open("a+b")
    except OSError:
        raise ASRError("STORAGE_ERROR", "Unable to open the ASR output lock") from None
    with lock_handle as handle:
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
            raise ASRError("ASR_BUSY", "Another process owns this ASR output directory", retryable=True) from None
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _https_url(value: Any, error_code: str) -> str:
    if not isinstance(value, str):
        raise ASRError(error_code, "Expected an HTTPS provider endpoint")
    try:
        parsed = urlsplit(value)
    except ValueError:
        raise ASRError(error_code, "Invalid provider endpoint") from None
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise ASRError(error_code, "Expected an HTTPS provider endpoint without embedded credentials")
    return value


def _base_url(value: str | None) -> str:
    result = value or os.environ.get("LIVECUT_ASR_BASE_URL") or os.environ.get("DASHSCOPE_HTTP_BASE_URL") or os.environ.get("DASHSCOPE_BASE_URL")
    result = (result or "https://dashscope.aliyuncs.com/api/v1").rstrip("/")
    _https_url(result, "INVALID_CONFIGURATION")
    if urlsplit(result).query:
        raise ASRError("INVALID_CONFIGURATION", "ASR base URL must not contain query parameters")
    if not urlsplit(result).path:
        result += "/api/v1"
    return result


def _positive_seconds(value: Any, name: str) -> float:
    if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
        raise ASRError("INVALID_CONFIGURATION", f"{name} must be a positive finite number")
    return float(value)


def _audio_hash(path: Path) -> str:
    try:
        with wave.open(str(path), "rb") as audio:
            if (audio.getnchannels(), audio.getframerate(), audio.getsampwidth()) != (1, 16000, 2) or audio.getnframes() <= 0:
                raise ASRError("INVALID_AUDIO", "ASR input must be nonempty 16kHz mono PCM16 WAV")
        if path.stat().st_size > 1_000_000_000:
            raise ASRError("INVALID_AUDIO", "Temporary upload input exceeds the 1GB POC limit")
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        return digest.hexdigest()
    except (OSError, wave.Error, EOFError):
        raise ASRError("INVALID_AUDIO", "ASR input is missing or is not a valid WAV file") from None


def _provider_code(data: Any) -> str | None:
    value = data.get("code") if isinstance(data, dict) else None
    return value if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,96}", value) else None


class _BailianHTTP:
    def __init__(self, client: httpx.Client, key: str, base_url: str, query_retries: int) -> None:
        self.client, self.key, self.base_url, self.query_retries = client, key, base_url, query_retries

    def request(
        self, method: str, url: str, *, safe_query: bool = False, authenticated: bool = False, **kwargs: Any
    ) -> httpx.Response:
        headers = dict(kwargs.pop("headers", {}))
        if authenticated:
            headers["Authorization"] = f"Bearer {self.key}"
        attempts = self.query_retries + 1 if safe_query else 1
        for attempt in range(attempts):
            try:
                response = self.client.request(method, url, headers=headers, **kwargs)
            except httpx.RequestError as exc:
                if safe_query and attempt + 1 < attempts:
                    time.sleep(min(2 ** attempt, 2))
                    continue
                code = ("QUERY_TIMEOUT" if isinstance(exc, httpx.TimeoutException) else "NETWORK_ERROR") if safe_query else "UNKNOWN_OUTCOME"
                raise ASRError(code, "ASR request did not return a reliable response; resume existing state", retryable=safe_query) from None
            if safe_query and (response.status_code == 429 or response.status_code >= 500) and attempt + 1 < attempts:
                time.sleep(min(2 ** attempt, 2))
                continue
            if 200 <= response.status_code < 300:
                return response
            status = response.status_code
            code = {400: "INVALID_REQUEST", 401: "AUTHENTICATION_FAILED", 403: "PERMISSION_DENIED",
                    404: "RESOURCE_NOT_FOUND", 422: "INVALID_REQUEST", 429: "RATE_LIMITED"}.get(status)
            if 300 <= status < 400:
                code = "REDIRECT_NOT_ALLOWED"
            if code is None:
                code = "PROVIDER_UNAVAILABLE" if safe_query else "UNKNOWN_OUTCOME"
            # Do not echo provider bodies: they may include temporary URLs or credentials.
            raise ASRError(code, f"ASR provider returned HTTP {status}", retryable=safe_query and (status == 429 or status >= 500))
        raise AssertionError("unreachable")

    def json(self, response: httpx.Response) -> dict:
        try:
            data = response.json()
        except ValueError:
            raise ASRError("INVALID_RESPONSE", "ASR provider returned invalid JSON") from None
        if not isinstance(data, dict):
            raise ASRError("INVALID_RESPONSE", "ASR provider returned an invalid response object")
        return data

    def upload_audio(self, path: Path, model: str) -> str:
        response = self.request("GET", f"{self.base_url}/uploads", params={"action": "getPolicy", "model": model}, safe_query=True, authenticated=True)
        policy = self.json(response).get("data")
        fields = ("upload_dir", "upload_host", "oss_access_key_id", "signature", "policy", "x_oss_object_acl", "x_oss_forbid_overwrite")
        if not isinstance(policy, dict) or any(not isinstance(policy.get(field), str) or not policy[field] for field in fields):
            raise ASRError("INVALID_RESPONSE", "Upload policy is incomplete")
        if "max_file_size_mb" in policy:
            try:
                limit_mb = float(policy["max_file_size_mb"])
            except (TypeError, ValueError, OverflowError):
                raise ASRError("INVALID_RESPONSE", "Upload policy has an invalid size limit") from None
            if not math.isfinite(limit_mb) or limit_mb <= 0:
                raise ASRError("INVALID_RESPONSE", "Upload policy has an invalid size limit")
            if path.stat().st_size > limit_mb * 1_000_000:
                raise ASRError("INVALID_AUDIO", "Audio exceeds this model's temporary upload size limit")
        host = _https_url(policy["upload_host"], "INVALID_RESPONSE")
        name = f"audio-{uuid4().hex}.wav"
        key = f"{policy['upload_dir'].rstrip('/')}/{name}"
        data = {"OSSAccessKeyId": policy["oss_access_key_id"], "Signature": policy["signature"],
                "policy": policy["policy"], "x-oss-object-acl": policy["x_oss_object_acl"],
                "x-oss-forbid-overwrite": policy["x_oss_forbid_overwrite"], "key": key, "success_action_status": "200"}
        # The OSS host receives its signed form only, never the model API key.
        with path.open("rb") as audio:
            self.request("POST", host, data=data, files={"file": (name, audio, "audio/wav")})
        return f"oss://{key}"

    def submit_task(self, file_url: str, model: str) -> dict:
        response = self.request(
            "POST", f"{self.base_url}/services/audio/asr/transcription", authenticated=True,
            headers={"X-DashScope-Async": "enable", "X-DashScope-OssResourceResolve": "enable"},
            json={"model": model, "input": {"file_urls": [file_url]}, "parameters": {"channel_id": [0]}},
        )
        return self.json(response)

    def query_task(self, task_id: str) -> dict:
        # Official endpoint table and Python example use POST for this read-only
        # query (the same page's curl example omits an explicit HTTP method).
        response = self.request("POST", f"{self.base_url}/tasks/{quote(task_id, safe='')}", authenticated=True, safe_query=True)
        return self.json(response)

    def download_result(self, url: str) -> dict:
        response = self.request("GET", _https_url(url, "INVALID_RESPONSE"), safe_query=True)
        return self.json(response)


def map_transcript(raw: dict, *, source_chunk_id: str = "c_001", chunk_start_ms: int = 0) -> list[dict]:
    """Map provider sentence milliseconds to stable global anchors; never guess."""
    if type(chunk_start_ms) is not int or chunk_start_ms < 0:
        raise ASRError("INVALID_CONFIGURATION", "chunk_start_ms must be nonnegative integer milliseconds")
    if not isinstance(source_chunk_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", source_chunk_id):
        raise ASRError("INVALID_CONFIGURATION", "source_chunk_id must be a simple stable identifier")
    transcripts = raw.get("transcripts") if isinstance(raw, dict) else None
    if not isinstance(transcripts, list) or len(transcripts) > 1:
        raise ASRError("INVALID_RESULT", "Expected a single-channel transcription result")
    utterances: list[dict] = []
    previous_end = 0
    for transcript in transcripts:
        if not isinstance(transcript, dict) or type(transcript.get("channel_id")) is not int or transcript["channel_id"] != 0:
            raise ASRError("INVALID_RESULT", "Unexpected transcript channel")
        sentences = transcript.get("sentences")
        if not isinstance(sentences, list):
            raise ASRError("INVALID_RESULT", "Result is missing sentence timestamps")
        for sentence in sentences:
            if not isinstance(sentence, dict):
                raise ASRError("INVALID_RESULT", "Invalid sentence object")
            start, end, text = sentence.get("begin_time"), sentence.get("end_time"), sentence.get("text")
            if type(start) is not int or type(end) is not int or start < previous_end or end <= start:
                raise ASRError("INVALID_RESULT", "Sentence timestamps are missing, overlapping, or out of order")
            if not isinstance(text, str) or not text.strip():
                raise ASRError("INVALID_RESULT", "A timestamped sentence has no readable text")
            utterances.append({"utterance_id": f"{source_chunk_id}_u{len(utterances) + 1:06d}",
                               "start_ms": start + chunk_start_ms, "end_ms": end + chunk_start_ms,
                               "text": text, "source_chunk_id": source_chunk_id})
            previous_end = end
    return utterances


def _task_id(data: Any) -> str | None:
    output = data.get("output") if isinstance(data, dict) else None
    task_id = output.get("task_id") if isinstance(output, dict) else None
    return task_id if isinstance(task_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,256}", task_id) else None


def transcribe_file(
    audio_path: str | Path,
    output_dir: str | Path,
    *,
    api_key: str | None = None,
    base_url: str | None = None,
    model: str = "paraformer-v2",
    timeout_seconds: float = 600,
    poll_interval_seconds: float = 2,
    request_timeout_seconds: float = 30,
    max_query_retries: int = 2,
    source_chunk_id: str = "c_001",
    chunk_start_ms: int = 0,
    transport: httpx.BaseTransport | None = None,
) -> list[dict]:
    """Upload/submit once, resume task polling, cache raw and normalized results.

    ``timeout_seconds`` limits the polling window for this invocation. Per-request
    HTTP timeout is separate. UNKNOWN_OUTCOME requires provider/manual resolution;
    rerunning this function never silently creates a replacement paid task.
    """
    timeout_seconds = _positive_seconds(timeout_seconds, "timeout_seconds")
    poll_interval_seconds = _positive_seconds(poll_interval_seconds, "poll_interval_seconds")
    request_timeout_seconds = _positive_seconds(request_timeout_seconds, "request_timeout_seconds")
    if type(max_query_retries) is not int or not 0 <= max_query_retries <= 3:
        raise ASRError("INVALID_CONFIGURATION", "max_query_retries must be between 0 and 3")
    if not isinstance(model, str) or not model.strip():
        raise ASRError("INVALID_CONFIGURATION", "ASR model ID must be explicit")
    map_transcript({"transcripts": []}, source_chunk_id=source_chunk_id, chunk_start_ms=chunk_start_ms)
    source = Path(audio_path).expanduser().resolve()
    endpoint = _base_url(base_url)
    fingerprint = {"audio_sha256": _audio_hash(source), "model": model, "base_url": endpoint,
                   "source_chunk_id": source_chunk_id, "chunk_start_ms": chunk_start_ms, "adapter_version": 1}
    directory = Path(output_dir).expanduser().resolve()
    try:
        directory.mkdir(parents=True, exist_ok=True)
    except OSError:
        raise ASRError("STORAGE_ERROR", "Unable to create the ASR output directory") from None
    with _exclusive_output(directory):
        checkpoint_path = directory / "checkpoint.json"
        raw_result_path = directory / "raw-result.json"
        checkpoint = _read_json(checkpoint_path) if checkpoint_path.exists() else {"version": 1, "input": fingerprint, "state": "NEW"}
        if not isinstance(checkpoint, dict) or checkpoint.get("version") != 1 or checkpoint.get("input") != fingerprint:
            raise ASRError("CHECKPOINT_INPUT_MISMATCH", "Output directory belongs to different ASR input/configuration")
        states = {"NEW", "UPLOADED", "SUBMITTING", "UNKNOWN_OUTCOME", "POLLING", "SUCCEEDED", "FAILED", "SUBMIT_REJECTED"}
        if checkpoint.get("state") not in states:
            raise ASRError("INVALID_CHECKPOINT", "Unknown persisted ASR state; do not automatically resubmit")
        if checkpoint.get("state") in ("POLLING", "SUCCEEDED"):
            if not _task_id({"output": {"task_id": checkpoint.get("provider_task_id")}}):
                raise ASRError("INVALID_CHECKPOINT", "Active/completed ASR state is missing its task ID; do not resubmit")
        if checkpoint.get("state") in ("SUBMITTING", "UNKNOWN_OUTCOME"):
            saved = _read_json(directory / "raw-submit.json") if (directory / "raw-submit.json").exists() else {}
            recovered = _task_id(saved)
            if recovered:
                checkpoint.update(provider_task_id=recovered, state="POLLING")
                _atomic_json(checkpoint_path, checkpoint)
            else:
                raise ASRError("UNKNOWN_OUTCOME", "A previous ASR submission may have been accepted; resolve it before any resubmission")
        if checkpoint.get("state") in ("FAILED", "SUBMIT_REJECTED"):
            raise ASRError(checkpoint.get("error_code", "TASK_FAILED"), "The persisted ASR operation failed; no automatic resubmission")
        if checkpoint.get("provider_task_id") and raw_result_path.exists():
            result = map_transcript(_read_json(raw_result_path), source_chunk_id=source_chunk_id, chunk_start_ms=chunk_start_ms)
            _atomic_json(directory / "transcript.json", result)
            checkpoint.update(state="SUCCEEDED")
            _atomic_json(checkpoint_path, checkpoint)
            return result
        if checkpoint.get("state") == "SUCCEEDED":
            raise ASRError("INVALID_CHECKPOINT", "Completed ASR checkpoint is missing its raw result; do not resubmit")
        key = api_key or os.environ.get("DASHSCOPE_API_KEY")
        if not isinstance(key, str) or not key.strip():
            raise ASRError("MISSING_API_KEY", "Set DASHSCOPE_API_KEY in the backend environment")
        if not re.fullmatch(r"[!-~]+", key):
            raise ASRError("INVALID_CONFIGURATION", "API key contains invalid characters; check its backend configuration")
        with httpx.Client(transport=transport, timeout=request_timeout_seconds, follow_redirects=False) as client:
            provider = _BailianHTTP(client, key, endpoint, max_query_retries)
            if not checkpoint.get("provider_task_id"):
                if not checkpoint.get("file_url") or checkpoint.get("upload_expires_at", 0) <= time.time():
                    file_url = provider.upload_audio(source, model)
                    checkpoint.update(state="UPLOADED", file_url=file_url, upload_expires_at=time.time() + 48 * 3600)
                    _atomic_json(checkpoint_path, checkpoint, secret=key)
                checkpoint.update(state="SUBMITTING")
                _atomic_json(checkpoint_path, checkpoint, secret=key)
                try:
                    submitted = provider.submit_task(checkpoint["file_url"], model)
                    _atomic_json(directory / "raw-submit.json", submitted, secret=key)
                    task_id = _task_id(submitted)
                    if task_id is None:
                        raise ASRError("UNKNOWN_OUTCOME", "Submission response has no usable task ID; do not automatically resubmit")
                except ASRError as exc:
                    uncertain = exc.code in ("UNKNOWN_OUTCOME", "INVALID_RESPONSE", "STORAGE_ERROR")
                    checkpoint.update(state="UNKNOWN_OUTCOME" if uncertain else "SUBMIT_REJECTED", error_code="UNKNOWN_OUTCOME" if uncertain else exc.code)
                    _atomic_json(checkpoint_path, checkpoint, secret=key)
                    if uncertain:
                        raise ASRError("UNKNOWN_OUTCOME", "ASR submission outcome is unknown; do not automatically resubmit") from None
                    raise
                checkpoint.update(state="POLLING", provider_task_id=task_id)
                _atomic_json(checkpoint_path, checkpoint, secret=key)
            deadline = time.monotonic() + timeout_seconds
            while time.monotonic() < deadline:
                polled = provider.query_task(checkpoint["provider_task_id"])
                _atomic_json(directory / "raw-poll.json", polled, secret=key)
                output = polled.get("output")
                if not isinstance(output, dict) or _task_id(polled) != checkpoint["provider_task_id"]:
                    raise ASRError("INVALID_RESPONSE", "Task query response has an unexpected task identity")
                status = output.get("task_status")
                if status == "SUCCEEDED":
                    files = output.get("results")
                    if not isinstance(files, list) or len(files) != 1 or not isinstance(files[0], dict):
                        raise ASRError("INVALID_RESPONSE", "Expected exactly one file transcription result")
                    if files[0].get("subtask_status") != "SUCCEEDED":
                        checkpoint.update(state="FAILED", error_code="SUBTASK_FAILED", provider_code=_provider_code(files[0]))
                        _atomic_json(checkpoint_path, checkpoint, secret=key)
                        raise ASRError("SUBTASK_FAILED", "ASR file subtask failed despite its parent task status")
                    raw = provider.download_result(files[0].get("transcription_url"))
                    _atomic_json(raw_result_path, raw, secret=key)
                    result = map_transcript(raw, source_chunk_id=source_chunk_id, chunk_start_ms=chunk_start_ms)
                    _atomic_json(directory / "transcript.json", result, secret=key)
                    checkpoint.update(state="SUCCEEDED", usage=polled.get("usage"), completed_at=time.time())
                    _atomic_json(checkpoint_path, checkpoint, secret=key)
                    return result
                if status not in ("PENDING", "RUNNING"):
                    checkpoint.update(state="FAILED", error_code="TASK_FAILED", provider_code=_provider_code(output))
                    _atomic_json(checkpoint_path, checkpoint, secret=key)
                    raise ASRError("TASK_FAILED", "ASR task terminated without a successful transcription")
                checkpoint.update(last_task_status=status)
                _atomic_json(checkpoint_path, checkpoint, secret=key)
                remaining = deadline - time.monotonic()
                if remaining > 0:
                    time.sleep(min(poll_interval_seconds, remaining))
            raise ASRError("POLL_TIMEOUT", "Polling window elapsed; the persisted provider task can be resumed", retryable=True)
