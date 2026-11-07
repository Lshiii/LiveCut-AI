"""Stage-local advisory provider health; never an authority for paid retries.

Only opaque profile digests are stored here. Frozen logical retry policies and
Java's invocation ledger remain authoritative when health is missing or broken.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import stat
import tempfile
import time
from dataclasses import asdict, dataclass
from pathlib import Path

WIRE_DISPATCHED = "livecut_text_wire_dispatched"
WIRE_RESPONSE_READ = "livecut_text_wire_response_read"
LOOKUP_FOUND = "livecut_text_lookup_found"
ROUTED_IDENTITY = "livecut_text_routed_identity"
_HEX = re.compile(r"[0-9a-f]{64}\Z")


@dataclass(frozen=True)
class TextCircuitPolicy:
    failure_threshold: int = 2
    cooldown_seconds: int = 60

    def __post_init__(self):
        if (type(self.failure_threshold) is not int or not 1 <= self.failure_threshold <= 10
                or type(self.cooldown_seconds) is not int or not 1 <= self.cooldown_seconds <= 300):
            raise ValueError("INVALID_TEXT_CIRCUIT_CONFIGURATION")

    def public(self):
        return asdict(self)


def stage_state_directory(context, name: str) -> Path:
    """Reject traversal and reparse points before touching persistent state."""
    from .worker import _native_path

    identifiers = (context.lease.job_id, context.lease.stage_id)
    if any(not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value)
           for value in identifiers):
        raise OSError("Unsafe text state path")
    root = context.storage_root.resolve()
    target = root
    for part in ("jobs", identifiers[0], "stages", identifiers[1], name):
        target = target / part
        native = _native_path(target)
        try:
            metadata = native.lstat()
        except FileNotFoundError:
            native.mkdir(exist_ok=True)
            metadata = native.lstat()
        if (stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode)
                or getattr(metadata, "st_file_attributes", 0) & 0x400):
            raise OSError("Unsafe text state path")
    return _native_path(target)


def read_state(path: Path):
    metadata = path.lstat()
    if (not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode)
            or getattr(metadata, "st_file_attributes", 0) & 0x400):
        raise OSError("Unsafe text state file")
    with path.open("rb") as stream:
        raw = stream.read(65537)
    if len(raw) > 65536:
        raise ValueError("Text state too large")
    return json.loads(raw)


def atomic_state(path: Path, value, *, create_only=False):
    """Publish complete fsynced data; a crash cannot expose a partial policy.

    Hard linking the complete temporary file gives create-if-absent semantics
    across processes. An interrupted unpublished temporary file is harmless.
    """
    temporary = None
    try:
        with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":"), allow_nan=False).encode())
            stream.flush()
            os.fsync(stream.fileno())
        if create_only:
            os.link(temporary, path)
        else:
            os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def profile_key(profile, credential: str) -> str:
    fingerprint = hashlib.sha256(credential.encode()).hexdigest()
    identity = [profile.endpoint, profile.style, profile.model, fingerprint]
    return hashlib.sha256(json.dumps(identity, separators=(",", ":")).encode()).hexdigest()


class TextCircuit:
    def __init__(self, context, policy=None, *, wall_time=None):
        self.context = context
        self.policy = policy or TextCircuitPolicy()
        self.wall_time = wall_time or time.time

    def _path(self, key):
        if not isinstance(key, str) or not _HEX.fullmatch(key):
            raise ValueError("Invalid health identity")
        return stage_state_directory(self.context, "text-provider-health") / (key + ".json")

    def health(self, key):
        try:
            value = read_state(self._path(key))
            if (not isinstance(value, dict)
                    or set(value) != {"version", "consecutive_failures", "open_until"}
                    or type(value["version"]) is not int or value["version"] != 1
                    or type(value["consecutive_failures"]) is not int
                    or not 0 <= value["consecutive_failures"] <= 10
                    or type(value["open_until"]) not in {int, float}
                    or not math.isfinite(value["open_until"]) or value["open_until"] < 0):
                raise ValueError
            return value
        except (OSError, ValueError, TypeError, UnicodeError):
            return {"version": 1, "consecutive_failures": 0, "open_until": 0}

    def route(self, keys):
        health = [self.health(key) for key in keys]
        now = self.wall_time()
        available = [i for i, value in enumerate(health) if value["open_until"] <= now]
        if not available:
            # No sleeping or extra dispatch: one earliest-reopening profile gets
            # the same bounded logical attempt budget as any ordinary route.
            available = [min(range(len(keys)), key=lambda i: (health[i]["open_until"], i))]
        probes = [i for i in available
                  if health[i]["consecutive_failures"] >= self.policy.failure_threshold]
        return available, probes

    def record(self, key, code=None):
        value = self.health(key)
        if code is None:
            value.update(consecutive_failures=0, open_until=0)
        elif code in {"PROVIDER_UNAUTHORIZED", "PROVIDER_QUOTA_EXHAUSTED"}:
            value.update(consecutive_failures=self.policy.failure_threshold,
                         open_until=self.wall_time() + self.policy.cooldown_seconds)
        elif code in {"PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE", "PROVIDER_RATE_LIMITED",
                      "PROVIDER_RESPONSE_INVALID"}:
            failures = min(10, value["consecutive_failures"] + 1)
            value["consecutive_failures"] = failures
            if failures >= self.policy.failure_threshold:
                value["open_until"] = self.wall_time() + self.policy.cooldown_seconds
        else:
            # A request/schema failure breaks a sequence of transient failures;
            # it does not itself prove successful service recovery.
            value["consecutive_failures"] = 0
        try:
            path = self._path(key)
            if path.exists() or path.is_symlink():
                read_state(path)  # Refuse to replace links or special files.
            atomic_state(path, value)
        except (OSError, ValueError, TypeError, UnicodeError):
            pass
