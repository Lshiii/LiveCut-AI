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

from .text_circuit import LOOKUP_FOUND, ROUTED_IDENTITY, WIRE_DISPATCHED, WIRE_RESPONSE_READ
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
        if inner is None:
            from .text_resilience import DeadlineHTTPTransport

            inner = DeadlineHTTPTransport()
        self.inner = inner
        self._caption_reserved: int | None = None
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

    def caption_text_budget(self) -> int | None:
        """Read the exact caption owner; an unavailable snapshot never opens tools."""
        lease = self.context.lease
        config = lease.config.get("agent_turn", {})
        maximum = lease.config.get("max_text_calls")
        self.context.guard.check()
        try:
            if (lease.stage_type != "CAPTION_PROOFREAD" or not isinstance(config, dict)
                    or config.get("kind") != "CAPTION" or type(maximum) is not int
                    or not 1 <= maximum <= 24):
                return None
            try:
                turn = self.client.caption_proofread_turn(config.get("turn_id"))
            except WorkerClientError:
                return None
            if not isinstance(turn, dict):
                return None
            stage, budget = turn.get("stage"), turn.get("budget")
            if (turn.get("id") != config.get("turn_id")
                    or turn.get("session_id") != config.get("session_id")
                    or turn.get("job_id") != lease.job_id or not isinstance(stage, dict)
                    or stage.get("id") != lease.stage_id
                    or stage.get("stage_type") != "CAPTION_PROOFREAD"):
                return None
            if (not isinstance(turn.get("status"), str) or not isinstance(stage.get("status"), str)
                    or type(stage.get("attempt")) is not int):
                return None
            if (turn["status"] != "RUNNING" or stage["status"] != "RUNNING"
                    or stage["attempt"] != lease.attempt):
                self.context.guard.lose()
                self.context.guard.check()
            if (not isinstance(budget, dict)
                    or type(budget.get("max_text_calls")) is not int
                    or budget["max_text_calls"] != maximum
                    or type(budget.get("text_calls_reserved")) is not int
                    or not 0 <= budget["text_calls_reserved"] <= maximum
                    or self._caption_reserved is not None
                    and budget["text_calls_reserved"] < self._caption_reserved):
                return None
            self._caption_reserved = budget["text_calls_reserved"]
            return maximum - self._caption_reserved
        finally:
            self.context.guard.check()

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
                or any(key not in {"x-dashscope-partialresponse", "retry-after"} for key in headers)
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
        retry_after = response.headers.get("retry-after", "")
        if retry_after.isascii() and retry_after.isdigit() and len(retry_after) <= 6:
            wrapper["headers"]["retry-after"] = str(min(30, int(retry_after)))
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
        request.extensions[WIRE_DISPATCHED] = False
        request.extensions[WIRE_RESPONSE_READ] = False
        request.extensions[LOOKUP_FOUND] = False
        self.context.guard.check()
        if self.context.lease.stage_type == "EXPORT_CLIP":
            raise ModelBudgetError("MODEL_OPERATION_NOT_ALLOWED")
        path = request.url.path.rstrip("/")
        kind = None
        if request.method == "POST" and path.endswith("/services/audio/asr/transcription"):
            kind = "ASR"
        elif request.method == "POST" and path.endswith(("/services/aigc/text-generation/generation", "/chat/completions")):
            kind = "TEXT"
        if kind is None:
            if request.method == "POST" and "/tasks/" in path:
                return self._query(request)
            return self.inner.handle_request(request)
        allowed = {"ASR": {"ASR"}, "TEXT": {"SEGMENT", "CAPTION_PROOFREAD"}}
        if self.context.lease.stage_type not in allowed[kind]:
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
        recovery = request.extensions.get("livecut_text_retry")
        routed = request.extensions.get(ROUTED_IDENTITY, False)
        if type(routed) is not bool or routed and (kind != "TEXT" or recovery is None):
            raise ModelBudgetError("INVALID_TEXT_RETRY_STATE")
        if recovery is not None:
            if (kind != "TEXT" or not isinstance(recovery, dict)
                    or set(recovery) != {"slot", "logical_hash", "policy_hash"}
                    or type(recovery["slot"]) is not int or not 0 <= recovery["slot"] <= 2
                    or any(not isinstance(recovery[field], str)
                           or len(recovery[field]) != 64
                           or any(c not in "0123456789abcdef" for c in recovery[field])
                           for field in ("logical_hash", "policy_hash"))):
                raise ModelBudgetError("INVALID_TEXT_RETRY_STATE")
            # Ordinal zero retains the historic identity, so an already paid
            # success remains reusable when recovery is enabled for old work.
            if recovery["slot"] or routed:
                identity["recovery"] = recovery
        serialized = json.dumps(identity, ensure_ascii=False, sort_keys=True,
                                separators=(",", ":"), allow_nan=False).encode()
        input_hash = hashlib.sha256(serialized).hexdigest()
        operation_key = kind.lower() + "-" + input_hash
        request_id = hashlib.sha256((self.context.lease.stage_id + ":" + operation_key).encode()).hexdigest()
        payload = {
            "stage_id": self.context.lease.stage_id, **self.context.lease.credentials,
            "request_id": request_id, "input_hash": input_hash,
            "operation_key": operation_key, "model_id": model, "kind": kind,
        }
        replay_only = request.extensions.get("livecut_text_replay_only", False)
        if replay_only:
            if kind != "TEXT" or recovery is None or replay_only is not True:
                raise ModelBudgetError("INVALID_TEXT_RETRY_STATE")
            reservation = self.client.lookup_call(payload)
            if reservation.get("outcome") == "NOT_FOUND":
                raise ModelBudgetError("TEXT_RECOVERY_NOT_RECORDED")
        else:
            reservation = self.client.reserve_call(payload)
        call = reservation.get("call", {})
        if (not isinstance(call, dict) or call.get("input_hash") != input_hash
                or call.get("request_id") != request_id):
            raise ModelBudgetError("INVALID_BUDGET_RESPONSE")
        if replay_only:
            request.extensions[LOOKUP_FOUND] = True
        status = call.get("status")
        if status == "SUCCEEDED" or (kind == "ASR" and status in {"SUBMITTED", "UNKNOWN_OUTCOME"}
                                      and call.get("provider_task_id")):
            response = self._replay(call, request)
            task_id = call.get("provider_task_id")
            if kind == "ASR" and isinstance(task_id, str) and task_id:
                self._remember_asr(task_id, request_id)
            return response
        if kind == "TEXT" and recovery is not None:
            if status in {"FAILED", "UNKNOWN_OUTCOME"} and call.get("response_key"):
                return self._replay(call, request)
            if status in {"DISPATCHING", "UNKNOWN_OUTCOME"}:
                # A new ordinal may be reserved under the explicit bounded
                # recovery policy, but this original dispatch is never resent.
                raise ModelBudgetError("TEXT_PREVIOUS_OUTCOME_UNKNOWN")
            if replay_only:
                raise ModelBudgetError("TEXT_RECOVERY_NOT_RECORDED")
        if status != "RESERVED":
            raise ModelBudgetError("MODEL_CALL_REQUIRES_RECOVERY")
        self._preflight_recording(request_id)
        self.context.guard.check()
        dispatch = self._update(request_id, "DISPATCHING")
        if dispatch.get("outcome") != "UPDATED" or dispatch.get("call", {}).get("status") != "DISPATCHING":
            raise ModelBudgetError("MODEL_DISPATCH_NOT_GRANTED")
        self.context.guard.check()
        response = None
        try:
            request.extensions[WIRE_DISPATCHED] = True
            response = self.inner.handle_request(request)
            response.read()
            request.extensions[WIRE_RESPONSE_READ] = True
        except Exception:
            if response is not None:
                response.close()
            try:
                self._update(request_id, "UNKNOWN_OUTCOME")
            except WorkerClientError:
                pass
            raise
        try:
            response_key, response_hash = self._record(request_id, response)
        except OSError:
            try:
                self._emergency_response(request_id, kind, response)
            finally:
                response.close()
            raise ModelBudgetError("MODEL_RESPONSE_PERSISTENCE_FAILED") from None
        except Exception:
            response.close()
            raise
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
        if 200 <= response.status_code < 300 and not raw.get("code") and not raw.get("error") and not partial:
            status = "SUBMITTED" if kind == "ASR" and task_id else (
                "SUCCEEDED" if kind == "TEXT" and raw else "UNKNOWN_OUTCOME")
        elif 400 <= response.status_code < 500:
            status = "FAILED"
        else:
            status = "UNKNOWN_OUTCOME"
        try:
            self._update(request_id, status, task_id=task_id, usage=usage,
                         response_key=response_key, response_hash=response_hash)
            if kind == "ASR" and status == "SUBMITTED" and isinstance(task_id, str):
                self._remember_asr(task_id, request_id)
        except Exception:
            response.close()
            raise
        return response


def transport_factory(client: WorkerClient):
    """Build a per-stage transport; operation labels never bypass path gating."""
    def create(context: StageContext, operation: str) -> httpx.BaseTransport:
        if operation not in {"asr", "text"}:
            raise ModelBudgetError("UNSUPPORTED_PROVIDER_OPERATION")
        budget = BudgetTransport(client, context)
        if operation == "text":
            from .text_resilience import ResilientTextTransport

            def progress(value):
                client.callback("text-progress", context.lease.stage_id,
                                {**context.lease.credentials, "progress": value})

            return ResilientTextTransport(budget, context, progress=progress)
        return budget
    return create
