"""Small authenticated client for Java's fenced, idempotent stage protocol."""

from __future__ import annotations

import re
from datetime import datetime
from urllib.parse import urlsplit
from uuid import UUID

import httpx


class WorkerClientError(Exception):
    """Sanitized error; response bodies and authentication headers stay private."""

    def __init__(self, code: str, *, retryable: bool = False) -> None:
        super().__init__(code)
        self.code, self.retryable = code, retryable


class WorkerClient:
    def __init__(
        self, base_url: str, worker_key: str, *, timeout_seconds: float = 10,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        parsed = urlsplit(base_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or (parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"})
                or not worker_key or "\n" in worker_key or "\r" in worker_key):
            raise WorkerClientError("INVALID_WORKER_CONFIGURATION")
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._client = httpx.Client(
            headers={"X-Worker-Key": worker_key}, timeout=timeout_seconds,
            transport=transport, follow_redirects=False, trust_env=False,
        )

    def close(self) -> None:
        self._client.close()

    def _request(self, method: str, path: str, **kwargs: object) -> dict:
        try:
            response = self._client.request(method, self._base_url + path, **kwargs)
        except httpx.RequestError:
            raise WorkerClientError("JAVA_UNAVAILABLE", retryable=True) from None
        if not 200 <= response.status_code < 300:
            code = {401: "WORKER_UNAUTHORIZED", 403: "WORKER_FORBIDDEN",
                    409: "STALE_ATTEMPT"}.get(response.status_code, "JAVA_HTTP_ERROR")
            try:
                error = response.json()
                candidate = error.get("error_code", error.get("code"))
                if isinstance(candidate, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,79}", candidate):
                    code = candidate
            except (ValueError, AttributeError):
                pass
            raise WorkerClientError(code, retryable=response.status_code == 429
                                    or response.status_code >= 500)
        try:
            value = response.json()
        except ValueError:
            raise WorkerClientError("INVALID_JAVA_RESPONSE", retryable=True) from None
        if not isinstance(value, dict):
            raise WorkerClientError("INVALID_JAVA_RESPONSE", retryable=True)
        return value

    @staticmethod
    def _job_id(value: str) -> str:
        try:
            return str(UUID(value))
        except (ValueError, TypeError, AttributeError):
            raise WorkerClientError("INVALID_JOB_ID") from None

    def job(self, job_id: str) -> dict:
        expected = self._job_id(job_id)
        snapshot = self._request("GET", "/api/jobs/" + expected)
        if snapshot.get("id") != expected:
            raise WorkerClientError("INVALID_JAVA_RESPONSE")
        return snapshot

    def ready(self, *, stage_type: str | None = None, limit: int = 10,
              job_id: str | None = None) -> dict:
        params: dict[str, str | int] = {"limit": limit}
        if stage_type is not None:
            params["stage_type"] = stage_type
        if job_id is not None:
            params["job_id"] = self._job_id(job_id)
        return self._request("GET", "/internal/stages/ready", params=params)

    def claim(self, stage_id: str, worker_id: str, claim_request_id: str,
              *, job_id: str | None = None, session_id: str | None = None) -> dict:
        payload = {
            "stage_id": stage_id, "worker_id": worker_id, "claim_request_id": claim_request_id,
        }
        if job_id is not None:
            payload["job_id"] = self._job_id(job_id)
        if session_id is not None:
            payload["session_id"] = self._session_id(session_id)
        return self._request("POST", "/internal/stages/claim", json=payload)

    @staticmethod
    def _session_id(value: str) -> str:
        try:
            return str(UUID(value))
        except (ValueError, TypeError, AttributeError):
            raise WorkerClientError("INVALID_WORKER_SESSION") from None

    @staticmethod
    def _worker_identity(worker_id: str) -> str:
        if not isinstance(worker_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", worker_id):
            raise WorkerClientError("INVALID_WORKER_CONFIGURATION")
        return worker_id

    def _worker_receipt(self, path: str, payload: dict, outcome: str,
                        *, timeout_seconds: float | None = None) -> dict:
        options = {} if timeout_seconds is None else {"timeout": min(self._timeout_seconds, timeout_seconds)}
        response = self._request("POST", path, json=payload, **options)
        ttl = response.get("heartbeat_timeout_seconds")
        try:
            seen = datetime.fromisoformat(response["last_seen_at"].replace("Z", "+00:00"))
            valid_seen = seen.tzinfo is not None
        except (KeyError, AttributeError, TypeError, ValueError):
            valid_seen = False
        if (response.get("outcome") != outcome
                or response.get("worker_id") != payload["worker_id"]
                or response.get("session_id") != payload["session_id"]
                or type(ttl) is not int or not 1 <= ttl <= 3600 or not valid_seen):
            raise WorkerClientError("INVALID_WORKER_RESPONSE")
        return response

    def register_worker(self, worker_id: str, session_id: str, stage_types: list[str],
                        *, target_job_id: str | None = None) -> dict:
        allowed = {"MEDIA", "ASR", "SEGMENT", "TIMELINE", "EXPORT_CLIP"}
        if (not isinstance(stage_types, list) or not stage_types
                or any(not isinstance(value, str) or value not in allowed for value in stage_types)
                or len(set(stage_types)) != len(stage_types)):
            raise WorkerClientError("INVALID_WORKER_CAPABILITIES")
        payload = {"worker_id": self._worker_identity(worker_id),
                   "session_id": self._session_id(session_id), "stage_types": stage_types, "slots": 1}
        if target_job_id is not None:
            payload["target_job_id"] = self._job_id(target_job_id)
        return self._worker_receipt("/internal/workers/register", payload, "REGISTERED")

    def heartbeat_worker(self, worker_id: str, session_id: str,
                         *, timeout_seconds: float | None = None) -> dict:
        if timeout_seconds is not None and (not isinstance(timeout_seconds, (int, float)) or timeout_seconds <= 0):
            raise WorkerClientError("INVALID_WORKER_HEARTBEAT_INTERVAL")
        return self._worker_receipt("/internal/workers/heartbeat", {
            "worker_id": self._worker_identity(worker_id), "session_id": self._session_id(session_id),
        }, "ACCEPTED", timeout_seconds=timeout_seconds)

    def unregister_worker(self, worker_id: str, session_id: str) -> dict:
        return self._worker_receipt("/internal/workers/unregister", {
            "worker_id": self._worker_identity(worker_id), "session_id": self._session_id(session_id),
        }, "UNREGISTERED")

    def callback(self, action: str, stage_id: str, payload: dict) -> dict:
        if action not in {"heartbeat", "checkpoint", "complete", "fail"}:
            raise WorkerClientError("INVALID_CALLBACK")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", stage_id):
            raise WorkerClientError("INVALID_STAGE_ID")
        return self._request("POST", f"/internal/stages/{stage_id}/{action}", json=payload)

    def reserve_call(self, payload: dict) -> dict:
        return self._request("POST", "/internal/model-calls/reserve", json=payload)

    def update_call(self, request_id: str, payload: dict) -> dict:
        if not re.fullmatch(r"[a-f0-9]{64}", request_id):
            raise WorkerClientError("INVALID_MODEL_REQUEST_ID")
        return self._request("POST", f"/internal/model-calls/{request_id}/update", json=payload)
