"""Bounded, budgeted text recovery. No background paid requests survive a timeout.

The retry ordinal belongs to the logical request, rather than a worker attempt.
Java remains the authority for reservations and fencing. A timed out dispatch
stays UNKNOWN; recovery deliberately reserves a different, bounded ordinal.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import time
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Callable
from urllib.parse import urlsplit

import httpcore
import httpx

from .text_circuit import (
    LOOKUP_FOUND,
    ROUTED_IDENTITY,
    WIRE_DISPATCHED,
    WIRE_RESPONSE_READ,
    TextCircuit,
    TextCircuitPolicy,
    atomic_state,
    profile_key,
    read_state,
    stage_state_directory,
)

_ACTIVE_DEADLINE: ContextVar[tuple[float, Callable] | None] = ContextVar("text_deadline", default=None)
_TRANSIENT = (httpx.ConnectError, httpx.ConnectTimeout, httpx.ReadTimeout,
              httpx.WriteTimeout, httpx.ReadError, httpx.WriteError, httpx.RemoteProtocolError)
_SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\Z")
_MODEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}\Z")


def _provider_failure(status: int, raw: object, partial: bool) -> tuple[str, bool] | None:
    """Classify wire failures without inspecting or publishing arbitrary messages.

    Exact service codes matter: InternalError.Algo.InvalidParameter and quota
    exhaustion must not be retried as transient InternalError or rate limiting.
    The boolean means switch service without retrying these credentials.
    """
    codes = []
    if isinstance(raw, dict):
        error = raw.get("error")
        candidates = [raw.get("code")]
        if isinstance(error, dict):
            candidates.extend((error.get("code"), error.get("type")))
        for value in candidates:
            if isinstance(value, str) and value.isascii() and len(value) <= 128:
                codes.append(value.lower())
    if any(code in {"throttling.allocationquota", "insufficient_quota", "arrearage",
                    "insufficient_balance", "balance_not_enough"} for code in codes) or status == 402:
        return "PROVIDER_QUOTA_EXHAUSTED", True
    if any(code in {"invalidapikey", "invalid_api_key", "authentication_error",
                    "accessdenied", "access_denied", "permission_denied"} for code in codes) or status in {401, 403}:
        return "PROVIDER_UNAUTHORIZED", True
    if status == 429 or any(code in {"throttling", "throttling.ratequota", "ratelimitexceeded",
                                    "rate_limit_exceeded", "rate_limit_error", "toomanyrequests"} for code in codes):
        return "PROVIDER_RATE_LIMITED", False
    if status == 408 or any(code in {"requesttimeout", "request_timeout", "timeout"} for code in codes):
        return "PROVIDER_TIMEOUT", False
    if status >= 500 or partial or any(code in {"internalerror", "internal_server_error", "server_error",
                                              "serviceunavailable", "service_unavailable", "overloaded_error"} for code in codes):
        return "PROVIDER_UNAVAILABLE", False
    if 200 <= status < 300:
        if not isinstance(raw, dict) or not raw:
            return "PROVIDER_RESPONSE_INVALID", False
        if not raw.get("code") and not raw.get("error"):
            return None
    return "PROVIDER_REQUEST_INVALID", False


class TextRecoveryError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code, self.retryable = code, False


def _timeout(value: float | None, error: type[Exception]) -> float | None:
    active = _ACTIVE_DEADLINE.get()
    if active is None:
        return value
    deadline, guard = active
    guard()
    left = deadline - time.monotonic()
    if left <= 0:
        raise error("Text response deadline exceeded")
    return left if value is None else min(value, left)


class _DeadlineStream(httpcore.NetworkStream):
    def __init__(self, inner):
        self.inner = inner

    def read(self, max_bytes, timeout=None):
        if _ACTIVE_DEADLINE.get() is None:
            return self.inner.read(max_bytes, timeout)
        # Poll the same socket, without issuing another HTTP request. A silent
        # provider must not hold the worker for 90s after its lease is fenced.
        # Preserve the original idle deadline across these short read waits.
        idle_deadline = None if timeout is None else time.monotonic() + timeout
        while True:
            idle_left = None if idle_deadline is None else idle_deadline - time.monotonic()
            if idle_left is not None and idle_left <= 0:
                raise httpcore.ReadTimeout("Text response idle deadline exceeded")
            remaining = _timeout(idle_left, httpcore.ReadTimeout)
            try:
                return self.inner.read(max_bytes, min(.25, remaining) if remaining is not None else .25)
            except httpcore.ReadTimeout:
                # The next pass checks cancellation and both deadlines. No
                # bytes were returned by recv, so no protocol data is replayed.
                continue

    def write(self, buffer, timeout=None):
        self.inner.write(buffer, _timeout(timeout, httpcore.WriteTimeout))

    def start_tls(self, ssl_context, server_hostname=None, timeout=None):
        return _DeadlineStream(self.inner.start_tls(
            ssl_context, server_hostname, _timeout(timeout, httpcore.ConnectTimeout)))

    def close(self):
        self.inner.close()

    def get_extra_info(self, info):
        return self.inner.get_extra_info(info)


class _DeadlineBackend(httpcore.NetworkBackend):
    def __init__(self):
        self.inner = httpcore.SyncBackend()

    def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        return _DeadlineStream(self.inner.connect_tcp(host, port,
            _timeout(timeout, httpcore.ConnectTimeout), local_address, socket_options))

    def connect_unix_socket(self, path, timeout=None, socket_options=None):
        return _DeadlineStream(self.inner.connect_unix_socket(
            path, _timeout(timeout, httpcore.ConnectTimeout), socket_options))

    def sleep(self, seconds):
        self.inner.sleep(seconds)


class DeadlineHTTPTransport(httpx.BaseTransport):
    """A public httpcore backend clamps each socket operation to a wall deadline.

    Read-idle timeouts alone do not bound providers that stream whitespace.
    The response is consumed while its deadline is active and then returned
    buffered. DNS resolution retains the operating system's resolver behavior.
    Requests without a deadline retain the legacy HTTPX transport (notably ASR).
    """
    def __init__(self, *, trust_env: bool = True):
        self.legacy = httpx.HTTPTransport(retries=0, trust_env=trust_env)
        # Keep the model transport's existing default SSL context. Internal
        # Worker calls explicitly retain HTTPX's verify=True, trust_env=False.
        options = {} if trust_env else {"ssl_context": httpx.create_ssl_context(trust_env=False)}
        self.pool = httpcore.ConnectionPool(network_backend=_DeadlineBackend(), retries=0,
            max_connections=2, max_keepalive_connections=0, **options)

    def close(self):
        self.pool.close()
        self.legacy.close()

    def handle_request(self, request):
        deadline = request.extensions.get("livecut_deadline_at")
        if deadline is None:
            return self.legacy.handle_request(request)
        guard = request.extensions.get("livecut_guard", lambda: None)
        token = _ACTIVE_DEADLINE.set((deadline, guard))
        response = None
        try:
            _timeout(None, httpcore.ReadTimeout)
            core_request = httpcore.Request(method=request.method,
                url=httpcore.URL(scheme=request.url.raw_scheme, host=request.url.raw_host,
                                 port=request.url.port, target=request.url.raw_path),
                headers=request.headers.raw, content=request.stream, extensions=request.extensions)
            response = self.pool.handle_request(core_request)
            chunks, size = [], 0
            for chunk in response.iter_stream():
                _timeout(None, httpcore.ReadTimeout)
                size += len(chunk)
                if size > 8 * 1024 * 1024:
                    raise TextRecoveryError("MODEL_RESPONSE_TOO_LARGE")
                chunks.append(chunk)
            _timeout(None, httpcore.ReadTimeout)
            return httpx.Response(response.status, headers=response.headers,
                content=b"".join(chunks), extensions=response.extensions, request=request)
        except (httpcore.TimeoutException, httpcore.NetworkError, httpcore.ProtocolError,
                httpcore.ProxyError, httpcore.UnsupportedProtocol) as exc:
            mapped = getattr(httpx, type(exc).__name__, httpx.TransportError)
            raise mapped("Text provider transport failed", request=request) from exc
        finally:
            if response is not None:
                response.close()
            _ACTIVE_DEADLINE.reset(token)


@dataclass(frozen=True)
class TextRetryPolicy:
    max_attempts: int = 3
    attempt_timeout_seconds: float = 90
    total_timeout_seconds: float = 240
    backoff_seconds: float = 1

    def __post_init__(self):
        if (type(self.max_attempts) is not int or not 1 <= self.max_attempts <= 3
                or any(type(v) not in {int, float} or not math.isfinite(v) for v in (
                    self.attempt_timeout_seconds, self.total_timeout_seconds, self.backoff_seconds))
                or not 0 < self.attempt_timeout_seconds <= 300
                or not self.attempt_timeout_seconds <= self.total_timeout_seconds <= 900
                or not 0 <= self.backoff_seconds <= 30):
            raise TextRecoveryError("INVALID_TEXT_RETRY_CONFIGURATION")

    @classmethod
    def from_env(cls):
        try:
            return cls(int(os.environ.get("LIVECUT_TEXT_MAX_ATTEMPTS", "3")),
                float(os.environ.get("LIVECUT_TEXT_ATTEMPT_TIMEOUT_SECONDS", "90")),
                float(os.environ.get("LIVECUT_TEXT_TOTAL_TIMEOUT_SECONDS", "240")),
                float(os.environ.get("LIVECUT_TEXT_RETRY_BACKOFF_SECONDS", "1")))
        except ValueError:
            raise TextRecoveryError("INVALID_TEXT_RETRY_CONFIGURATION") from None


@dataclass(frozen=True)
class _Profile:
    name: str
    endpoint: str
    style: str
    model: str
    key_env: str | None = None

    def public(self):
        return {"name": self.name, "endpoint": self.endpoint, "api_style": self.style,
                "model": self.model, "key_env": self.key_env}


def _fallbacks(context):
    try:
        rows = json.loads(os.environ.get("LIVECUT_TEXT_FALLBACKS_JSON", "[]"))
        if not isinstance(rows, list) or len(rows) > 2:
            raise ValueError
        configured = context.lease.config.get("text_fallback_models", [])
        if not isinstance(configured, list) or any(not isinstance(v, str) for v in configured):
            raise ValueError
        result, names = [], set()
        for row in rows:
            if not isinstance(row, dict) or set(row) != {"name", "base_url", "api_style", "model", "key_env"}:
                raise ValueError
            name, base, style, model, key_env = (row[k] for k in ("name", "base_url", "api_style", "model", "key_env"))
            if (not all(isinstance(v, str) for v in (name, base, style, model, key_env))
                    or not _SAFE_NAME.fullmatch(name) or name in names
                    or not _MODEL.fullmatch(model) or style not in {"openai", "dashscope"}
                    or not re.fullmatch(r"LIVECUT_[A-Z][A-Z0-9_]{0,100}", key_env)
                    or key_env in {"LIVECUT_WORKER_API_KEY", "LIVECUT_DB_PASSWORD"}):
                raise ValueError
            parsed = urlsplit(base)
            if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
                    or parsed.query or parsed.fragment):
                raise ValueError
            if model not in configured:
                names.add(name)
                continue
            key = os.environ.get(key_env, "")
            if not key or "\r" in key or "\n" in key:
                raise ValueError
            base = base.rstrip("/")
            suffix = "/chat/completions" if style == "openai" else "/services/aigc/text-generation/generation"
            if not base.endswith(suffix):
                base += ("" if style == "openai" or base.endswith("/api/v1") else "/api/v1") + suffix
            result.append(_Profile(name, base, style, model, key_env))
            names.add(name)
        return result
    except (ValueError, TypeError, KeyError):
        raise TextRecoveryError("INVALID_TEXT_RETRY_CONFIGURATION") from None


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _digest(value):
    return hashlib.sha256(_json(value).encode()).hexdigest()


class ResilientTextTransport(httpx.BaseTransport):
    def caption_text_budget(self) -> int | None:
        read = getattr(self.inner, "caption_text_budget", None)
        return read() if callable(read) else None

    def __init__(self, inner, context, *, policy=None, progress=None, sleep=time.sleep,
                 monotonic=time.monotonic, circuit_policy=None):
        self.inner, self.context = inner, context
        self.policy = policy or TextRetryPolicy.from_env()
        self.progress, self.sleep, self.monotonic = progress, sleep, monotonic
        self.fallbacks = _fallbacks(context)
        self.circuit = TextCircuit(context, circuit_policy)
        self.trace = {"schema_version": 1, "job_id": context.lease.job_id,
                      "stage_id": context.lease.stage_id, "calls": []}
        self._logical_hash = None
        self._current_timeout = self.policy.attempt_timeout_seconds

    def close(self):
        self.inner.close()

    @staticmethod
    def _validate_frozen(value):
        common = {"version", "max_attempts", "attempt_timeout_seconds", "total_timeout_seconds",
                  "backoff_seconds", "profiles"}
        if (not isinstance(value, dict) or type(value.get("version")) is not int
                or value["version"] not in {1, 2}):
            raise TextRecoveryError("INVALID_TEXT_RETRY_STATE")
        version = value["version"]
        extra = {"circuit", "route_profile_indices", "probe_profile_indices"} if version == 2 else set()
        try:
            if set(value) != common | extra:
                raise ValueError
            TextRetryPolicy(value["max_attempts"], value["attempt_timeout_seconds"],
                            value["total_timeout_seconds"], value["backoff_seconds"])
            profiles = value["profiles"]
            if not isinstance(profiles, list) or not 1 <= len(profiles) <= 3:
                raise ValueError
            for index, profile in enumerate(profiles):
                if (not isinstance(profile, dict)
                        or set(profile) != {"name", "endpoint", "api_style", "model", "key_env"}
                        or any(not isinstance(profile[k], str)
                               for k in ("name", "endpoint", "api_style", "model"))
                        or not _SAFE_NAME.fullmatch(profile["name"])
                        or not _MODEL.fullmatch(profile["model"])
                        or profile["api_style"] not in {"openai", "dashscope"}):
                    raise ValueError
                endpoint = urlsplit(profile["endpoint"])
                if (endpoint.scheme not in {"https", "http"} or not endpoint.hostname
                        or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment):
                    raise ValueError
                if (index == 0 and (profile["name"] != "primary" or profile["key_env"] is not None)
                        or index > 0 and (not isinstance(profile["key_env"], str)
                            or not re.fullmatch(r"LIVECUT_[A-Z][A-Z0-9_]{0,100}", profile["key_env"]))):
                    raise ValueError
            if len({p["name"] for p in profiles}) != len(profiles):
                raise ValueError
            if version == 2:
                circuit = value["circuit"]
                if not isinstance(circuit, dict) or set(circuit) != {"failure_threshold", "cooldown_seconds"}:
                    raise ValueError
                TextCircuitPolicy(**circuit)
                route, probes = value["route_profile_indices"], value["probe_profile_indices"]
                if (not isinstance(route, list) or not route or not isinstance(probes, list)
                        or any(type(i) is not int or not 0 <= i < len(profiles) for i in route + probes)
                        or route != sorted(set(route)) or probes != sorted(set(probes))
                        or not set(probes) <= set(route)):
                    raise ValueError
        except (ValueError, TypeError, KeyError, TextRecoveryError):
            raise TextRecoveryError("INVALID_TEXT_RETRY_STATE") from None

    def _legacy_call_exists(self, request, logical_hash, base):
        from .model_budget import ModelBudgetError

        # Before advisory routing a window without a policy, preserve work paid
        # before retry policies existed. This lookup cannot reserve or dispatch.
        extensions = {**request.extensions,
                      "livecut_text_retry": {"slot": 0, "logical_hash": logical_hash,
                                             "policy_hash": _digest(base)},
                      "livecut_text_replay_only": True}
        extensions.pop(ROUTED_IDENTITY, None)
        probe = httpx.Request("POST", request.url, headers=request.headers,
                              content=request.content, extensions=extensions)
        response = None
        try:
            response = self.inner.handle_request(probe)
        except ModelBudgetError as error:
            if error.code not in {"TEXT_PREVIOUS_OUTCOME_UNKNOWN", "TEXT_RECOVERY_NOT_RECORDED"}:
                raise
        finally:
            if response is not None:
                response.close()
        return probe.extensions.get(LOOKUP_FOUND) is True

    def _freeze(self, logical_hash, base, keys, request):
        self.context.guard.check()
        try:
            directory = stage_state_directory(self.context, "text-retry-policies")
            path = directory / (logical_hash + ".json")
            try:
                frozen = read_state(path)
            except FileNotFoundError:
                if self._legacy_call_exists(request, logical_hash, base):
                    value = base
                else:
                    route, probes = self.circuit.route(keys)
                    value = {**base, "version": 2, "circuit": self.circuit.policy.public(),
                             "route_profile_indices": route, "probe_profile_indices": probes}
                self._validate_frozen(value)
                frozen = {"policy": value, "started_at_ms": int(time.time() * 1000)}
                self.context.guard.check()
                try:
                    atomic_state(path, frozen, create_only=True)
                except FileExistsError:
                    frozen = read_state(path)
            if (not isinstance(frozen, dict) or set(frozen) != {"policy", "started_at_ms"}
                    or type(frozen["started_at_ms"]) is not int
                    or not 0 <= frozen["started_at_ms"] <= 2 ** 53 - 1):
                raise ValueError
            value = frozen["policy"]
            self._validate_frozen(value)
            expected = dict(base)
            if value["version"] == 2:
                expected.update(version=2, circuit=self.circuit.policy.public())
            actual = {k: v for k, v in value.items()
                      if k not in {"route_profile_indices", "probe_profile_indices"}}
            if actual != expected:
                raise TextRecoveryError("TEXT_RETRY_POLICY_CONFLICT")
            started_at = frozen["started_at_ms"]
        except (OSError, ValueError, TypeError, UnicodeError):
            raise TextRecoveryError("INVALID_TEXT_RETRY_STATE") from None
        age = time.time() - started_at / 1000
        if age < -1:
            raise TextRecoveryError("TEXT_RETRY_CLOCK_CONFLICT")
        return value, max(0, age)

    def _emit(self, state, number, profile, started, *, code=None, delay=0):
        from .worker import _write
        value = {"state": state, "attempt_number": number, "max_attempts": self.policy.max_attempts,
                 "provider": profile.name, "model_id": profile.model,
                 "timeout_seconds": max(1, math.ceil(self._current_timeout)),
                 "elapsed_seconds": min(1800, max(0, int(self.monotonic() - started))),
                 "retry_in_seconds": math.ceil(delay)}
        if code:
            value["error_code"] = code
        self.trace["calls"].append({**value, "logical_hash": self._logical_hash, "slot": number - 1})
        _write(self.context.directory / "text-execution-trace.json", self.trace)
        if self.progress:
            self.progress(value)

    def _wait(self, seconds):
        until = self.monotonic() + seconds
        while self.monotonic() < until:
            self.context.guard.check()
            self.sleep(min(.1, until - self.monotonic()))

    @staticmethod
    def _body(profile, messages):
        if profile.style == "dashscope":
            return {"model": profile.model, "input": {"messages": messages}, "parameters": {
                "result_format": "message", "response_format": {"type": "json_object"},
                "enable_thinking": False, "temperature": 0}}
        body = {"model": profile.model, "messages": messages, "stream": False,
                "response_format": {"type": "json_object"}, "temperature": 0}
        if urlsplit(profile.endpoint).hostname == "api.deepseek.com":
            body["thinking"] = {"type": "disabled"}
        return body

    def handle_request(self, request):
        from .model_budget import ModelBudgetError
        if request.method != "POST" or not request.url.path.endswith(("/chat/completions", "/services/aigc/text-generation/generation")):
            return self.inner.handle_request(request)
        self.context.guard.check()
        try:
            primary_body = json.loads(request.read())
            model = primary_body["model"]
            messages = primary_body["messages"] if "messages" in primary_body else primary_body["input"]["messages"]
            if not isinstance(model, str) or not _MODEL.fullmatch(model) or not isinstance(messages, list):
                raise ValueError
        except (ValueError, KeyError, TypeError):
            raise ModelBudgetError("INVALID_PROVIDER_REQUEST") from None
        primary = _Profile("primary", str(request.url), "openai" if request.url.path.endswith("/chat/completions") else "dashscope", model)
        profiles = [primary, *self.fallbacks]
        credentials = ["Bearer " + os.environ[p.key_env] if p.key_env
                       else request.headers.get("Authorization", "") for p in profiles]
        health_keys = [profile_key(p, credential) for p, credential in zip(profiles, credentials)]
        logical_hash = _digest({"endpoint": str(request.url), "body": primary_body})
        self._logical_hash = logical_hash
        policy = {"version": 1, "max_attempts": self.policy.max_attempts,
                  "attempt_timeout_seconds": float(self.policy.attempt_timeout_seconds),
                  "total_timeout_seconds": float(self.policy.total_timeout_seconds),
                  "backoff_seconds": float(self.policy.backoff_seconds),
                  "profiles": [p.public() for p in profiles]}
        policy, prior_elapsed = self._freeze(logical_hash, policy, health_keys, request)
        policy_hash = _digest(policy)
        started = self.monotonic() - prior_elapsed
        total_deadline = min(started + self.policy.total_timeout_seconds,
                             self.monotonic() + self.context.guard.remaining_seconds())
        route = policy.get("route_profile_indices", list(range(len(profiles))))
        probes = policy.get("probe_profile_indices", [])
        route_position = 0
        profile_index = route[route_position]
        profile = profiles[profile_index]
        last_code = "PROVIDER_UNAVAILABLE"
        expired = False
        if profile_index:
            self._emit("SWITCHING_PROVIDER", 1, profile, started, code="PROVIDER_CIRCUIT_OPEN")
        for slot in range(self.policy.max_attempts):
            self.context.guard.check()
            remaining = total_deadline - self.monotonic()
            expired = remaining <= 0
            timeout = min(self.policy.attempt_timeout_seconds, self.context.guard.remaining_seconds())
            if not expired:
                timeout = min(timeout, remaining)
            self._current_timeout = timeout
            self._emit("REQUESTING", slot + 1, profile, started)
            extensions = {**request.extensions, "livecut_text_retry": {"slot": slot, "logical_hash": logical_hash, "policy_hash": policy_hash},
                          "livecut_deadline_at": time.monotonic() + timeout, "livecut_guard": self.context.guard.check}
            extensions.pop(ROUTED_IDENTITY, None)
            if policy["version"] == 2 and profile_index:
                extensions[ROUTED_IDENTITY] = True
            if expired:
                extensions["livecut_text_replay_only"] = True
            extensions["timeout"] = {name: min(value, timeout) if isinstance(value, (int, float)) else timeout
                                      for name, value in request.extensions.get("timeout", {}).items()}
            headers = {"Content-Type": "application/json"}
            if credentials[profile_index]:
                headers["Authorization"] = credentials[profile_index]
            candidate = httpx.Request("POST", profile.endpoint, headers=headers,
                json=primary_body if profile is primary else self._body(profile, messages), extensions=extensions)
            response, auth_failed, retry_after = None, False, 0
            try:
                response = self.inner.handle_request(candidate)
                response.read()
                status = response.status_code
                try:
                    raw = response.json()
                except ValueError:
                    raw = None
                partial = response.headers.get("x-dashscope-partialresponse", "").lower() == "true"
                failure = _provider_failure(status, raw, partial)
                if candidate.extensions.get(WIRE_DISPATCHED) is True:
                    self.circuit.record(health_keys[profile_index], None if failure is None else failure[0])
                if failure is None:
                    self._emit("SUCCEEDED", slot + 1, profile, started)
                    return response
                retry_header = response.headers.get("retry-after", "")
                if retry_header.isascii() and retry_header.isdigit() and len(retry_header) <= 6:
                    retry_after = min(30, int(retry_header))
                last_code, auth_failed = failure
                if auth_failed:
                    if route_position + 1 >= len(route):
                        self._emit("EXHAUSTED", slot + 1, profile, started, code=last_code)
                        return response
                elif last_code == "PROVIDER_REQUEST_INVALID":
                    self._emit("EXHAUSTED", slot + 1, profile, started, code="PROVIDER_REQUEST_INVALID")
                    return response
            except _TRANSIENT as error:
                if (candidate.extensions.get(WIRE_DISPATCHED) is not True
                        or candidate.extensions.get(WIRE_RESPONSE_READ) is True):
                    # Transport-shaped Java/storage/callback errors are not a
                    # provider outcome and cannot alter its circuit or route.
                    raise
                last_code = "PROVIDER_TIMEOUT" if isinstance(error, httpx.TimeoutException) else "PROVIDER_UNAVAILABLE"
                self.circuit.record(health_keys[profile_index], last_code)
            except ModelBudgetError as error:
                if error.code not in {"TEXT_PREVIOUS_OUTCOME_UNKNOWN", "TEXT_RECOVERY_NOT_RECORDED"}:
                    raise
                if error.code == "TEXT_RECOVERY_NOT_RECORDED" and not expired:
                    raise
                last_code = "PREVIOUS_OUTCOME_UNKNOWN"
            finally:
                if response is not None:
                    response.close()
            if slot + 1 >= self.policy.max_attempts:
                break
            # With one backup, give the primary one transient retry. Auth failures
            # skip directly to the next provider. Two backups receive one slot each.
            switch = route_position + 1 < len(route) and (
                auth_failed or len(route) > 2 or slot >= 1 or profile_index in probes)
            if switch:
                route_position += 1
                profile_index = route[route_position]
                profile = profiles[profile_index]
                self._emit("SWITCHING_PROVIDER", slot + 2, profile, started, code=last_code)
            delay = min(30, max(retry_after, self.policy.backoff_seconds * (2 ** slot)))
            if expired:
                continue
            if self.monotonic() + delay >= total_deadline:
                self._emit("EXHAUSTED", slot + 1, profile, started, code="TEXT_RETRY_DEADLINE_EXCEEDED")
                raise TextRecoveryError("TEXT_RETRY_DEADLINE_EXCEEDED")
            self._emit("RETRY_WAIT", slot + 1, profile, started, code=last_code, delay=delay)
            self._wait(delay)
        terminal = "TEXT_RETRY_DEADLINE_EXCEEDED" if expired else "TEXT_RETRIES_EXHAUSTED"
        self._emit("EXHAUSTED", self.policy.max_attempts, profile, started, code=terminal)
        raise TextRecoveryError(terminal)
