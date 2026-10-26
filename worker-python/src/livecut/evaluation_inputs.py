"""Strict offline localization inputs; manifest references are never opened.

Ranges must be separated: adjacent ranges are rejected rather than silently
merged. Annotation provenance is declared metadata, not proof of human work or
third-party authenticity. Only the report layer may assign a reporting scope.
"""

from __future__ import annotations

import json
import math
import re
from functools import lru_cache
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, SchemaError, validators

from .contracts import ContractValidationError, validate_analysis_result

MAX_JSON_BYTES = 8 * 1024 * 1024
_MAX_DEPTH = 64
_MAX_NODES = 200000
_HASH = re.compile(r"[a-f0-9]{64}\Z")
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}\Z")
_StrictValidator = validators.extend(Draft202012Validator, type_checker=
    Draft202012Validator.TYPE_CHECKER.redefine("integer", lambda _checker, value: type(value) is int))


class EvaluationInputError(ValueError):
    """Safe, stable code: never echo input text, tokens or filesystem paths."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


def _invalid(code: str = "INVALID_ANNOTATION") -> EvaluationInputError:
    return EvaluationInputError(code)


def _pairs(values: list[tuple[str, Any]]) -> dict:
    result: dict = {}
    for key, value in values:
        if key in result:
            raise ValueError("Duplicate object key")
        result[key] = value
    return result


def _constant(_value: str) -> None:
    raise ValueError("Non-finite JSON number")


def _float(value: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        raise ValueError("Non-finite JSON number")
    return result


def _check_depth(text: str) -> None:
    depth = tokens = 0
    quoted = escaped = False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            tokens += 1
            if depth > _MAX_DEPTH:
                raise ValueError("JSON nesting exceeds limit")
        elif char in "]}":
            depth -= 1
        elif char == ",":
            tokens += 1
        if tokens > _MAX_NODES:
            raise ValueError("JSON token count exceeds limit")


def _check_tree(value: Any) -> None:
    stack = [(value, 0)]
    count = 0
    while stack:
        item, depth = stack.pop()
        count += 1
        if count > _MAX_NODES or depth > _MAX_DEPTH:
            raise ValueError("JSON tree exceeds limits")
        if isinstance(item, dict):
            if len(item) > _MAX_NODES - count - len(stack):
                raise ValueError("JSON tree exceeds limits")
            stack.extend((child, depth + 1) for child in item.values())
        elif isinstance(item, list):
            if len(item) > _MAX_NODES - count - len(stack):
                raise ValueError("JSON tree exceeds limits")
            stack.extend((child, depth + 1) for child in item)


def load_json(path: str | Path, max_bytes: int = MAX_JSON_BYTES) -> dict:
    """Load UTF-8 (including BOM) without changing raw-file digest semantics."""
    if type(max_bytes) is not int or not 1 <= max_bytes <= MAX_JSON_BYTES:
        raise _invalid("INVALID_JSON_LIMIT")
    try:
        candidate = Path(path)
        if not candidate.is_file():
            raise ValueError("JSON must be a regular file")
        with candidate.open("rb") as handle:
            raw = handle.read(max_bytes + 1)
        if len(raw) > max_bytes:
            raise ValueError("JSON exceeds limit")
        text = raw.decode("utf-8-sig")
        _check_depth(text)
        value = json.loads(text, object_pairs_hook=_pairs, parse_constant=_constant, parse_float=_float)
        _check_tree(value)
        if not isinstance(value, dict):
            raise ValueError("JSON root must be an object")
        return value
    except (OSError, ValueError, TypeError, RecursionError, OverflowError):
        raise _invalid("INVALID_EVALUATION_JSON") from None


@lru_cache(maxsize=1)
def _annotation_validator() -> Any:
    for parent in Path(__file__).resolve().parents:
        path = parent / "contracts" / "localization-annotation.schema.json"
        if path.is_file():
            try:
                schema = load_json(path)
                _StrictValidator.check_schema(schema)
                return _StrictValidator(schema)
            except (EvaluationInputError, SchemaError):
                raise _invalid("ANNOTATION_SCHEMA_UNAVAILABLE") from None
    raise _invalid("ANNOTATION_SCHEMA_UNAVAILABLE")


def validate_annotation(data: dict, *, require_annotated: bool = True) -> None:
    """Validate complete declared ranges, never interpret an empty template as negatives."""
    if type(require_annotated) is not bool:
        raise _invalid()
    try:
        _check_tree(data)
        if next(_annotation_validator().iter_errors(data), None) is not None:
            raise _invalid()
    except EvaluationInputError:
        raise
    except (ValueError, TypeError, RecursionError, OverflowError):
        raise _invalid() from None
    status, duration = data["annotation_status"], data["duration_ms"]
    if require_annotated and status == "not_annotated":
        raise _invalid("ANNOTATION_REQUIRED")
    ranges = data["annotated_ranges"]
    previous_end = -1
    for item in ranges:
        start, end = item["start_ms"], item["end_ms"]
        if not 0 <= start < end <= duration or start <= previous_end:
            raise _invalid()
        previous_end = end
    whole = ranges == [{"start_ms": 0, "end_ms": duration}]
    if (status == "fully_annotated" and not whole) or (status == "partially_annotated" and whole):
        raise _invalid()
    seen: set[str] = set()
    for segment in data["segments"]:
        start, end = segment["start_ms"], segment["end_ms"]
        if segment["id"] in seen or not 0 <= start < end <= duration:
            raise _invalid()
        if not any(item["start_ms"] <= start < end <= item["end_ms"] for item in ranges):
            raise _invalid()
        seen.add(segment["id"])


def _digest(value: Any) -> bool:
    return isinstance(value, str) and _HASH.fullmatch(value) is not None


def _positive(value: Any) -> bool:
    return type(value) is int and 0 < value <= 9007199254740991


def _artifact_key(value: Any) -> bool:
    return (isinstance(value, str) and 0 < len(value) <= 512
            and not value.startswith("/") and "\\" not in value and ":" not in value
            and all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) and part not in {".", ".."}
                    for part in value.split("/")))


def _manifest_binding(manifest: Any, prediction: dict, source_hash: str,
                      prediction_hash: Any) -> None:
    if not isinstance(manifest, dict) or not _digest(prediction_hash):
        raise _invalid("INVALID_EVALUATION_MANIFEST")
    expected = {"schema_version", "job_id", "stage_id", "video_id", "analysis_generation",
                "stage_type", "attempt", "run_token", "input_sha256", "data_kind", "review_status", "artifacts"}
    if set(manifest) != expected or type(manifest["schema_version"]) is not int or manifest["schema_version"] != 1:
        raise _invalid("INVALID_EVALUATION_MANIFEST")
    if (not isinstance(manifest["stage_type"], str) or manifest["stage_type"] not in {"SEGMENT", "TIMELINE"}
            or manifest["data_kind"] != prediction["data_kind"] or manifest["review_status"] != "DRAFT"
            or manifest["job_id"] != prediction["job_id"] or manifest["video_id"] != prediction["video_id"]
            or type(manifest["analysis_generation"]) is not int
            or manifest["analysis_generation"] != prediction["analysis_generation"]
            or manifest["input_sha256"] != source_hash or not _positive(manifest["attempt"])
            or not isinstance(manifest["stage_id"], str) or not _ID.fullmatch(manifest["stage_id"])
            or not isinstance(manifest["run_token"], str)
            or not re.fullmatch(r"[A-Za-z0-9_-]{16,64}", manifest["run_token"])):
        raise _invalid("EVALUATION_BINDING_MISMATCH")
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or not 1 <= len(artifacts) <= 64:
        raise _invalid("INVALID_EVALUATION_MANIFEST")
    matches, keys, kinds = [], set(), set()
    prefix = (f"jobs/{manifest['job_id']}/stages/{manifest['stage_id']}/attempts/"
              f"{manifest['attempt']}/{manifest['run_token']}/")
    for artifact in artifacts:
        if (not isinstance(artifact, dict) or set(artifact) != {"kind", "key", "sha256"}
                or not isinstance(artifact["kind"], str)
                or not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", artifact["kind"])
                or not _artifact_key(artifact["key"]) or not artifact["key"].startswith(prefix)
                or not _digest(artifact["sha256"])
                or artifact["key"] in keys or artifact["kind"] in kinds):
            raise _invalid("INVALID_EVALUATION_MANIFEST")
        keys.add(artifact["key"])
        kinds.add(artifact["kind"])
        if artifact["kind"] == "analysis_result":
            matches.append(artifact)
    if len(matches) != 1 or matches[0]["sha256"] != prediction_hash:
        raise _invalid("EVALUATION_BINDING_MISMATCH")


def validate_evaluation_binding(prediction: dict, annotation: dict, *,
                                source_sha256: str | None = None, duration_ms: int | None = None,
                                manifest: dict | None = None, prediction_sha256: str | None = None,
                                fixture: bool = False, require_annotated: bool = True) -> None:
    """Check caller-computed digests/metadata; no Java API or manifest-key I/O occurs."""
    if type(fixture) is not bool:
        raise _invalid("INVALID_EVALUATION_BINDING")
    validate_annotation(annotation, require_annotated=require_annotated)
    try:
        _check_tree(prediction)
        validate_analysis_result(prediction)
    except (ContractValidationError, ValueError, TypeError, RecursionError, OverflowError):
        raise _invalid("INVALID_EVALUATION_PREDICTION") from None
    if prediction["video_id"] != annotation["video_id"] or prediction["duration_ms"] != annotation["duration_ms"]:
        raise _invalid("EVALUATION_BINDING_MISMATCH")
    synthetic = prediction["data_kind"] == "synthetic_fixture"
    label = annotation["provenance"]["label_source"]
    audit_only = not require_annotated and annotation["annotation_status"] == "not_annotated"
    if fixture != synthetic or (not audit_only and (label == "synthetic_fixture") != synthetic):
        raise _invalid("EVALUATION_PROVENANCE_MISMATCH")
    if source_sha256 is not None and (not _digest(source_sha256) or source_sha256 != annotation["source_sha256"]):
        raise _invalid("EVALUATION_BINDING_MISMATCH")
    if duration_ms is not None and (not _positive(duration_ms) or duration_ms != annotation["duration_ms"]):
        raise _invalid("EVALUATION_BINDING_MISMATCH")
    if not synthetic and (source_sha256 is None or duration_ms is None or manifest is None):
        raise _invalid("EVALUATION_SOURCE_REQUIRED")
    if manifest is not None:
        _manifest_binding(manifest, prediction, annotation["source_sha256"], prediction_sha256)
    elif prediction_sha256 is not None and not _digest(prediction_sha256):
        raise _invalid("INVALID_EVALUATION_BINDING")
