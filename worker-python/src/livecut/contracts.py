"""Validate initial machine predictions, without treating fixtures as model runs.

Schema validation deliberately runs before cross-field validation.  This module
validates a result bundle, not the existence of its media files or model quality.
The run manifest owns the permitted local boundary sampling window, which is not
part of the v2 result schema; a frame outside a segment is therefore not rejected
solely on that basis.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, SchemaError, validators


class ContractValidationError(ValueError):
    """A bundle failed validation; ``errors`` contains readable JSON paths."""

    def __init__(self, errors: list[str]) -> None:
        self.errors = list(errors)
        super().__init__("Analysis result validation failed:\n" + "\n".join(self.errors))


# JSON Schema accepts 1.0 as an integer.  The wire contract intentionally requires
# literal integer milliseconds (and rejects bool, an int subclass in Python).
_TYPE_CHECKER = Draft202012Validator.TYPE_CHECKER.redefine(
    "integer", lambda _checker, value: type(value) is int
).redefine(
    "number",
    lambda _checker, value: (
        type(value) is int or (type(value) is float and math.isfinite(value))
    ),
)
_StrictValidator = validators.extend(Draft202012Validator, type_checker=_TYPE_CHECKER)


def _json_path(parts: Any) -> str:
    path = "$"
    for part in parts:
        path += f"[{part}]" if isinstance(part, int) else f".{part}"
    return path


def _default_schema_path() -> Path:
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "contracts" / "analysis-result.schema.json"
        if candidate.is_file():
            return candidate
    raise ContractValidationError(
        ["$: cannot locate contracts/analysis-result.schema.json; pass schema_path explicitly"]
    )


def validate_analysis_result(data: dict, schema_path: Path | None = None) -> None:
    """Validate an initial result bundle or raise :class:`ContractValidationError`.

    ``schema_path`` points to an explicit schema JSON file; by default the schema
    is found in the repository's ``contracts`` directory, independent of cwd.
    The input is never changed, approved, or promoted from a synthetic fixture.
    """
    path = Path(schema_path) if schema_path is not None else _default_schema_path()
    try:
        with path.open(encoding="utf-8-sig") as handle:
            schema = json.load(handle)
        _StrictValidator.check_schema(schema)
    except (OSError, ValueError, SchemaError) as exc:
        raise ContractValidationError([f"$: cannot load schema {path}: {exc}"]) from exc

    errors = [
        f"{_json_path(error.absolute_path)}: {error.message}"
        for error in sorted(
            _StrictValidator(schema).iter_errors(data),
            key=lambda error: (_json_path(error.absolute_path), error.message),
        )
    ]
    if errors:
        raise ContractValidationError(errors)

    _validate_business_rules(data, errors)
    if errors:
        raise ContractValidationError(errors)


def _validate_business_rules(data: dict, errors: list[str]) -> None:
    duration = data["duration_ms"]

    def add(path: str, message: str) -> None:
        errors.append(f"{path}: {message}")

    def index_unique(items: list[dict], field: str, name: str) -> dict:
        by_id = {}
        for index, item in enumerate(items):
            value = item[field]
            if value in by_id:
                add(f"$.{name}[{index}].{field}", f"duplicate {field} {value!r}")
            else:
                by_id[value] = item
        return by_id

    products = index_unique(data["products"], "product_id", "products")
    utterances = index_unique(data["utterances"], "utterance_id", "utterances")
    frames = index_unique(data["frames"], "frame_id", "frames")
    index_unique(data["segments"], "segment_id", "segments")
    utterance_order = {
        item["utterance_id"]: index for index, item in enumerate(data["utterances"])
    }

    def check_interval(item: dict, path: str) -> None:
        if not item["start_ms"] < item["end_ms"] <= duration:
            add(path, "require start_ms < end_ms <= duration_ms")

    for index, utterance in enumerate(data["utterances"]):
        check_interval(utterance, f"$.utterances[{index}]")
        if index and utterance["start_ms"] < data["utterances"][index - 1]["start_ms"]:
            add(f"$.utterances[{index}].start_ms", "utterances must be in chronological order")
    for index, frame in enumerate(data["frames"]):
        if not frame["timestamp_ms"] < duration:
            add(f"$.frames[{index}].timestamp_ms", "require timestamp_ms < duration_ms")

    def check_products(product_ids: list[str], path: str) -> None:
        for index, product_id in enumerate(product_ids):
            if product_id not in products:
                add(f"{path}[{index}]", f"unknown product_id {product_id!r}")

    active: list[tuple[int, dict]] = []
    for index, segment in enumerate(data["segments"]):
        base = f"$.segments[{index}]"
        check_interval(segment, base)
        if index and segment["start_ms"] < data["segments"][index - 1]["start_ms"]:
            add(f"{base}.start_ms", "segments must be in chronological order")

        identity = segment["identity"]
        candidates = identity["candidate_product_ids"]
        check_products(candidates, f"{base}.identity.candidate_product_ids")
        if identity["status"] == "identified":
            if identity["product_id"] not in products:
                add(f"{base}.identity.product_id", f"unknown product_id {identity['product_id']!r}")
            if identity["product_id"] not in candidates:
                add(f"{base}.identity.product_id", "identified product_id must be in candidate_product_ids")
        if segment["content_kind"] == "non_product" and identity["status"] != "not_applicable":
            add(f"{base}.identity.status", "non_product requires not_applicable identity")
        if segment["content_kind"] == "product_talk" and identity["status"] == "not_applicable":
            add(f"{base}.identity.status", "product_talk requires a product identity status")

        required_reasons = set()
        for value, reason in (
            (identity["status"] == "ambiguous", "IDENTITY_AMBIGUOUS"),
            (identity["status"] == "unknown", "UNKNOWN_PRODUCT"),
            (segment["content_kind"] == "mixed", "MIXED_PRODUCTS"),
            (segment["content_kind"] == "uncertain", "BOUNDARY_UNCERTAIN"),
            (segment["visual_status"] == "conflict", "AUDIO_VISUAL_CONFLICT"),
        ):
            if value:
                required_reasons.add(reason)
        reasons = set(segment["review_reasons"])
        if (required_reasons or reasons) and not segment["review_required"]:
            add(f"{base}.review_required", "risk or review reasons require priority review")
        for reason in sorted(required_reasons - reasons):
            add(f"{base}.review_reasons", f"missing required reason {reason}")

        start = utterances.get(segment["start_utterance_id"])
        end = utterances.get(segment["end_utterance_id"])
        for field, anchor in (("start_utterance_id", start), ("end_utterance_id", end)):
            if anchor is None:
                add(f"{base}.{field}", f"unknown utterance_id {segment[field]!r}")
            elif not segment["start_ms"] <= anchor["start_ms"] < anchor["end_ms"] <= segment["end_ms"]:
                add(f"{base}.{field}", "anchor utterance must lie inside segment interval")
        if start is not None and segment["start_ms"] != start["start_ms"]:
            add(f"{base}.start_ms", "must equal start_utterance.start_ms")
        if end is not None and segment["end_ms"] != end["end_ms"]:
            add(f"{base}.end_ms", "must equal end_utterance.end_ms")
        if start is not None and end is not None:
            if utterance_order[start["utterance_id"]] > utterance_order[end["utterance_id"]]:
                add(base, "start_utterance_id must not follow end_utterance_id")

        previous_order = -1
        for evidence_index, utterance_id in enumerate(segment["audio_evidence_utterance_ids"]):
            evidence_path = f"{base}.audio_evidence_utterance_ids[{evidence_index}]"
            evidence = utterances.get(utterance_id)
            if evidence is None:
                add(evidence_path, f"unknown utterance_id {utterance_id!r}")
                continue
            order = utterance_order[utterance_id]
            if order < previous_order:
                add(evidence_path, "audio evidence must follow transcript order")
            previous_order = order
            if not segment["start_ms"] <= evidence["start_ms"] < evidence["end_ms"] <= segment["end_ms"]:
                add(evidence_path, "audio evidence must lie inside segment interval")
            if start is not None and end is not None:
                if not utterance_order[start["utterance_id"]] <= order <= utterance_order[end["utterance_id"]]:
                    add(evidence_path, "audio evidence must lie between anchor utterances")

        clear_observations = []
        observed_frame_ids = set()
        for observation_index, observation in enumerate(segment["visual_observations"]):
            observation_path = f"{base}.visual_observations[{observation_index}]"
            frame_id = observation["frame_id"]
            if frame_id not in frames:
                add(f"{observation_path}.frame_id", f"unknown frame_id {frame_id!r}")
            if frame_id in observed_frame_ids:
                add(f"{observation_path}.frame_id", f"duplicate observation for frame_id {frame_id!r}")
            observed_frame_ids.add(frame_id)
            check_products(observation["candidate_product_ids"], f"{observation_path}.candidate_product_ids")
            if observation["visibility"] == "clear":
                clear_observations.append(observation)
        _check_visual_status(segment, clear_observations, base, add)

        # Half-open intervals may touch.  Review-only ambiguous hypotheses may
        # overlap one another, but a pair of settled talk intervals may not.
        active = [(prior_index, prior) for prior_index, prior in active if prior["end_ms"] > segment["start_ms"]]
        for prior_index, prior in active:
            if not (_is_unresolved(prior) and _is_unresolved(segment)):
                add(base, f"illegal overlap with $.segments[{prior_index}]; resolve intervals or retain unresolved review candidates")
        active.append((index, segment))


def _is_unresolved(segment: dict) -> bool:
    return segment["review_required"] and (
        segment["identity"]["status"] == "ambiguous"
        or segment["content_kind"] in {"mixed", "uncertain"}
    )


def _check_visual_status(segment: dict, clear: list[dict], base: str, add: Any) -> None:
    status = segment["visual_status"]
    if status not in {"supported", "conflict"}:
        return
    if not clear:
        add(f"{base}.visual_status", f"{status} requires clear visual evidence")
        return

    identity = segment["identity"]
    target = {identity["product_id"]} if identity["status"] == "identified" else set(identity["candidate_product_ids"])
    visible_candidates = [set(observation["candidate_product_ids"]) for observation in clear]
    if target:
        supporting = any(candidate and candidate <= target for candidate in visible_candidates)
        conflicting = any(candidate and candidate.isdisjoint(target) for candidate in visible_candidates)
        if status == "supported" and (not supporting or conflicting):
            add(f"{base}.visual_status", "supported requires clear candidate support and no clear identity conflict")
        if status == "conflict" and not conflicting:
            add(f"{base}.visual_status", "conflict requires a clear candidate inconsistent with the audio identity")
    elif status == "supported":
        # An unknown product can have a clear, described visual observation
        # without receiving an invented product identity.
        if not any(observation["observed_attributes"] for observation in clear):
            add(f"{base}.visual_status", "supported without product identity requires clear observed attributes")
    else:
        add(f"{base}.visual_status", "conflict requires a comparable audio identity")
