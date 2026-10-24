"""Fence provider requests behind Java's persistent invocation reservation.

No adapter is patched. Existing ASR and text clients inject this transport, and
every HTTP request passes the lease guard. A reserved call is marked DISPATCHING
before network I/O so an uncertain outcome cannot silently spend twice.
"""

from __future__ import annotations

import hashlib
import json
from typing import TYPE_CHECKING
from urllib.parse import unquote

import httpx

from .worker_client import WorkerClient, WorkerClientError

if TYPE_CHECKING:
    from .worker import StageContext


class ModelBudgetError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = False


class BudgetTransport(httpx.BaseTransport):
    def __init__(self, client: WorkerClient, context: StageContext, *,
                 inner: httpx.BaseTransport | None = None) -> None:
        from .worker import _native_path

        self.client, self.context = client, context
        self.inner = inner if inner is not None else httpx.HTTPTransport(retries=0)
        self._asr_calls: dict[str, str] = {}
        self._recovery_path = context.directory / "model-call-recovery.json"
        if _native_path(self._recovery_path).exists():
            try:
                recovery = json.loads(_native_path(self._recovery_path).read_text(encoding="utf-8"))
                if not isinstance(recovery, dict) or any(
                    not isinstance(key, str) or not isinstance(value, str)
                    for key, value in recovery.items()
                ):
                    raise ValueError
                self._asr_calls = recovery
            except (OSError, ValueError):
                raise ModelBudgetError("INVALID_MODEL_RECOVERY_STATE") from None

    def close(self) -> None:
        self.inner.close()

    def _update(self, request_id: str, status: str, *, task_id: str | None = None,
                usage: dict | None = None, response_key: str | None = None,
                response_hash: str | None = None) -> dict:
        return self.client.update_call(request_id, {
            "stage_id": self.context.lease.stage_id, **self.context.lease.credentials,
            "status": status, "provider_task_id": task_id, "usage": usage or {},
            "response_key": response_key, "response_hash": response_hash,
        })

    def _replay(self, call: dict, request: httpx.Request) -> httpx.Response:
        from .worker import _read, sha256, shared_path

        key, digest = call.get("response_key"), call.get("response_hash")
        if not isinstance(key, str) or not isinstance(digest, str):
            raise ModelBudgetError("MODEL_CALL_REQUIRES_RECOVERY")
        path = shared_path(self.context.storage_root, key, must_exist=True)
        if sha256(path) != digest:
            raise ModelBudgetError("MODEL_RESPONSE_HASH_MISMATCH")
        wrapper = _read(path)
        status = wrapper.get("http_status")
        headers = wrapper.get("headers")
        if (type(status) is not int or not 100 <= status <= 599 or not isinstance(headers, dict)
                or any(key != "x-dashscope-partialresponse" for key in headers)
                or "body" not in wrapper):
            raise ModelBudgetError("INVALID_MODEL_RESPONSE_RECORDING")
        if wrapper.get("body_is_json", True):
            return httpx.Response(status, json=wrapper["body"], headers=headers, request=request)
        if not isinstance(wrapper["body"], str):
            raise ModelBudgetError("INVALID_MODEL_RESPONSE_RECORDING")
        return httpx.Response(status, text=wrapper["body"], headers=headers, request=request)

    def _record(self, request_id: str, response: httpx.Response) -> tuple[str, str]:
        from .worker import _native_path, _write, sha256

        wrapper = self._response_wrapper(response)
        directory = self.context.directory / "provider-responses"
        path = directory / (request_id + ".json")
        if _native_path(path).exists():
            raise ModelBudgetError("MODEL_RESPONSE_ALREADY_EXISTS")
        _write(path, wrapper)
        return path.relative_to(self.context.storage_root).as_posix(), sha256(path)

    @staticmethod
    def _response_wrapper(response: httpx.Response) -> dict:
        try:
            body, is_json = response.json(), True
        except ValueError:
            body, is_json = response.text, False
        wrapper = {"http_status": response.status_code, "body": body,
                   "body_is_json": is_json, "headers": {}}
        if "x-dashscope-partialresponse" in response.headers:
            wrapper["headers"]["x-dashscope-partialresponse"] = response.headers["x-dashscope-partialresponse"]
        return wrapper

    def _emergency_response(self, request_id: str, kind: str, response: httpx.Response) -> None:
        from .worker import _write

        wrapper = self._response_wrapper(response)
        body = wrapper["body"]
        output = body.get("output", {}) if isinstance(body, dict) else {}
        task_id = output.get("task_id") if isinstance(output, dict) else None
        # Best-effort paths are intentionally short. A known task ID is also
        # settled independently to Java, even if local disk remains unavailable.
        try:
            _write(self.context.directory / "provider-response-emergency.json", {
                "request_id": request_id, "kind": kind, "response": wrapper,
                "recovery_reason": "PRIMARY_RESPONSE_PERSISTENCE_FAILED",
            })
        except OSError:
            pass
        try:
            if kind == "ASR" and isinstance(task_id, str) and task_id:
                self._update(request_id, "SUBMITTED", task_id=task_id)
            else:
                self._update(request_id, "UNKNOWN_OUTCOME")
        except WorkerClientError:
            pass

    def _preflight_recording(self, request_id: str) -> None:
        from .worker import _native_path, _write

        target = self.context.directory / "provider-responses" / (request_id + ".json")
        if _native_path(target).exists():
            raise ModelBudgetError("MODEL_RESPONSE_ALREADY_EXISTS")
        probe = target.with_suffix(".write-probe")
        try:
            _write(probe, {"writability_probe": True})
        except OSError:
            raise ModelBudgetError("MODEL_RESPONSE_STORAGE_UNAVAILABLE") from None
        finally:
            _native_path(probe).unlink(missing_ok=True)

    def _remember_asr(self, task_id: str, request_id: str) -> None:
        from .worker import _write

        self._asr_calls[task_id] = request_id
        _write(self._recovery_path, self._asr_calls)

    def _query(self, request: httpx.Request) -> httpx.Response:
        response = self.inner.handle_request(request)
        task_id = unquote(request.url.path.rsplit("/", 1)[-1])
        request_id = self._asr_calls.get(task_id)
        if request_id:
            response.read()
            try:
                raw = response.json()
                status = raw.get("output", {}).get("task_status")
                usage = raw.get("usage", {})
                if not isinstance(usage, dict):
                    usage = {}
                usage = {key: value for key, value in usage.items()
                         if isinstance(key, str) and type(value) is int and value >= 0}
            except (ValueError, AttributeError):
                status, usage = None, {}
            if status in {"SUCCEEDED", "FAILED", "CANCELED"}:
                self._update(request_id, "SUCCEEDED" if status == "SUCCEEDED" else "FAILED",
                             task_id=task_id, usage=usage)
        return response

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        self.context.guard.check()
        if self.context.lease.stage_type == "EXPORT_CLIP":
            raise ModelBudgetError("MODEL_OPERATION_NOT_ALLOWED")
        path = request.url.path.rstrip("/")
        kind = None
        if request.method == "POST" and path.endswith("/services/audio/asr/transcription"):
            kind = "ASR"
        elif request.method == "POST" and path.endswith("/services/aigc/text-generation/generation"):
            kind = "TEXT"
        if kind is None:
            if request.method == "POST" and "/tasks/" in path:
                return self._query(request)
            return self.inner.handle_request(request)
        if self.context.lease.stage_type != {"ASR": "ASR", "TEXT": "SEGMENT"}[kind]:
            raise ModelBudgetError("MODEL_OPERATION_NOT_ALLOWED")
        try:
            body = json.loads(request.read())
            model = body["model"]
            if not isinstance(model, str) or not model:
                raise ValueError
        except (ValueError, KeyError, TypeError):
            raise ModelBudgetError("INVALID_PROVIDER_REQUEST") from None
        endpoint = {"scheme": request.url.scheme, "host": request.url.host,
                    "port": request.url.port, "path": request.url.path}
        if kind == "ASR":
            # Signed/temporary upload URLs are deliberately excluded. The same
            # source/model/shard remains the same billable submission identity.
            identity = {"kind": kind, "source_hash": self.context.lease.source_hash,
                        "audio_sha256": self.context.provider_input_hash,
                        "stage_input_hash": self.context.lease.input_hash,
                        "endpoint": endpoint, "model": model,
                        "parameters": body.get("parameters", {})}
        else:
            identity = {"endpoint": endpoint, "body": body}
        serialized = json.dumps(identity, ensure_ascii=False, sort_keys=True,
                                separators=(",", ":"), allow_nan=False).encode()
        input_hash = hashlib.sha256(serialized).hexdigest()
        operation_key = kind.lower() + "-" + input_hash
        request_id = hashlib.sha256((self.context.lease.stage_id + ":" + operation_key).encode()).hexdigest()
        reservation = self.client.reserve_call({
            "stage_id": self.context.lease.stage_id, **self.context.lease.credentials,
            "request_id": request_id, "input_hash": input_hash,
            "operation_key": operation_key, "model_id": model, "kind": kind,
        })
        call = reservation.get("call", {})
        if (not isinstance(call, dict) or call.get("input_hash") != input_hash
                or call.get("request_id") != request_id):
            raise ModelBudgetError("INVALID_BUDGET_RESPONSE")
        status = call.get("status")
        if status == "SUCCEEDED" or (kind == "ASR" and status in {"SUBMITTED", "UNKNOWN_OUTCOME"}
                                      and call.get("provider_task_id")):
            response = self._replay(call, request)
            task_id = call.get("provider_task_id")
            if kind == "ASR" and isinstance(task_id, str) and task_id:
                self._remember_asr(task_id, request_id)
            return response
        if status != "RESERVED":
            raise ModelBudgetError("MODEL_CALL_REQUIRES_RECOVERY")
        self._preflight_recording(request_id)
        self.context.guard.check()
        dispatch = self._update(request_id, "DISPATCHING")
        if dispatch.get("outcome") != "UPDATED" or dispatch.get("call", {}).get("status") != "DISPATCHING":
            raise ModelBudgetError("MODEL_DISPATCH_NOT_GRANTED")
        self.context.guard.check()
        try:
            response = self.inner.handle_request(request)
            response.read()
        except Exception:
            try:
                self._update(request_id, "UNKNOWN_OUTCOME")
            except WorkerClientError:
                pass
            raise
        try:
            response_key, response_hash = self._record(request_id, response)
        except OSError:
            self._emergency_response(request_id, kind, response)
            raise ModelBudgetError("MODEL_RESPONSE_PERSISTENCE_FAILED") from None
        try:
            raw = response.json()
        except ValueError:
            raw = {}
        if not isinstance(raw, dict):
            raw = {}
        task_id = raw.get("output", {}).get("task_id") if isinstance(raw.get("output"), dict) else None
        usage = raw.get("usage", {})
        if not isinstance(usage, dict):
            usage = {}
        usage = {key: value for key, value in usage.items()
                 if isinstance(key, str) and type(value) is int and value >= 0}
        partial = response.headers.get("x-dashscope-partialresponse", "").lower() == "true"
        if 200 <= response.status_code < 300 and not raw.get("code") and not partial:
            status = "SUBMITTED" if kind == "ASR" and task_id else (
                "SUCCEEDED" if kind == "TEXT" and raw else "UNKNOWN_OUTCOME")
        elif 400 <= response.status_code < 500:
            status = "FAILED"
        else:
            status = "UNKNOWN_OUTCOME"
        self._update(request_id, status, task_id=task_id, usage=usage,
                     response_key=response_key, response_hash=response_hash)
        if kind == "ASR" and status == "SUBMITTED" and isinstance(task_id, str):
            self._remember_asr(task_id, request_id)
        return response


def transport_factory(client: WorkerClient):
    """Build a per-stage transport; operation labels never bypass path gating."""
    def create(context: StageContext, operation: str) -> BudgetTransport:
        if operation not in {"asr", "text"}:
            raise ModelBudgetError("UNSUPPORTED_PROVIDER_OPERATION")
        return BudgetTransport(client, context)
    return create
