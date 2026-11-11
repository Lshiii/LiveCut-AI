"""Offline question-level evidence coverage, never answer correctness scoring.

The caller binds local files and actual image bytes. These pure-input checks
validate the supplied answer protocol and compare only declared evidence groups.
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from .agent_evidence import is_js_blank, utf16_length
from .agent_frames import MAX_SAFE, actual_position
from .contracts import _StrictValidator
from .evaluation_inputs import _check_tree, load_json
from .segmentation import _inputs
from .video_agent import _HASH, _ID, validate_agent_answer

ORIGINS = ("MODEL", "USER_FOCUS", "EVIDENCE_POLICY")
TEXT_TOOLS = {"search_transcript", "read_context", "list_topics"}
OBSERVATION_ROLES = {"SPEECH", "FRAME", "SAMPLED_CHANGE"}


class EvidenceEvaluationError(ValueError):
    """Stable public code; input prose, paths and provider details stay private."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def _fail(code="INVALID_AGENT_EVIDENCE_REFERENCE"):
    raise EvidenceEvaluationError(code)


@lru_cache(maxsize=1)
def _reference_validator():
    try:
        path = next(parent / "contracts/agent-evidence-reference.schema.json"
                    for parent in Path(__file__).resolve().parents
                    if (parent / "contracts/agent-evidence-reference.schema.json").is_file())
        schema = load_json(path)
        _StrictValidator.check_schema(schema)
        return _StrictValidator(schema)
    except Exception:
        _fail("AGENT_EVIDENCE_REFERENCE_SCHEMA_UNAVAILABLE")


def validate_agent_evidence_reference(reference: dict) -> dict:
    """Validate declared provenance and bounded requirements without rewriting."""
    validator = _reference_validator()
    try:
        _check_tree(reference)
        if next(validator.iter_errors(reference), None) is not None:
            _fail()
        source = reference["source"]
        if (not _ID.fullmatch(reference["reference_id"])
                or not _ID.fullmatch(source["video_id"])
                or any(not _HASH.fullmatch(source[name])
                       for name in ("source_sha256", "transcript_sha256"))
                or is_js_blank(reference["question"])
                or utf16_length(reference["question"]) > 2000):
            _fail()
        seen = set()
        for requirement in reference["requirements"]:
            if not _ID.fullmatch(requirement["id"]) or requirement["id"] in seen:
                _fail()
            seen.add(requirement["id"])
            if requirement["modality"] == "ASR":
                if any(not _ID.fullmatch(uid) for uid in requirement["acceptable_utterance_ids"]):
                    _fail()
            elif not 0 <= requirement["start_ms"] < requirement["end_ms"] <= source["duration_ms"]:
                _fail()
        return reference
    except EvidenceEvaluationError:
        raise
    except Exception:
        _fail()


def _validated_inputs(answer, utterances, reference, duration_ms):
    validate_agent_evidence_reference(reference)
    if type(duration_ms) is not int or not 1 <= duration_ms <= MAX_SAFE:
        _fail("INVALID_AGENT_EVIDENCE_INPUT")
    try:
        _check_tree(utterances)
        items = _inputs(utterances, duration_ms)
    except Exception:
        _fail("INVALID_AGENT_EVIDENCE_INPUT")
    source = reference["source"]
    if duration_ms != source["duration_ms"]:
        _fail("AGENT_EVIDENCE_SOURCE_MISMATCH")
    actual_ids = {item["utterance_id"] for item in items}
    if any(uid not in actual_ids for group in reference["requirements"]
           if group["modality"] == "ASR" for uid in group["acceptable_utterance_ids"]):
        _fail("AGENT_EVIDENCE_SOURCE_MISMATCH")
    expected = {"video_id": source["video_id"], "source_hash": source["source_sha256"],
                "transcript_hash": source["transcript_sha256"], "question": reference["question"]}
    if not isinstance(answer, dict) or any(answer.get(key) != value for key, value in expected.items()):
        _fail("AGENT_EVIDENCE_SOURCE_MISMATCH")
    try:
        _check_tree(answer)
        if answer.get("schema_version") == "1.0":
            validate_agent_answer(answer, items, expected=expected)
        elif answer.get("schema_version") in {"1.1", "1.2", "1.3", "1.4", "1.5"}:
            from .agent_visual import validate_visual_answer

            validate_visual_answer(answer, items, expected=expected, duration_ms=duration_ms)
        else:
            _fail("INVALID_AGENT_EVIDENCE_ANSWER")
    except Exception:
        _fail("INVALID_AGENT_EVIDENCE_ANSWER")
    return items


def _acquisition(answer):
    records = {"ASR": {}, "FRAME": {}}
    for call in answer["tool_calls"]:
        origin = call.get("origin", "MODEL")
        for modality, field in (("ASR", "result_utterance_ids"), ("FRAME", "result_frame_ids")):
            for identity in call.get(field, []):
                record = records[modality].setdefault(identity, {
                    "first_acquired_origin": origin, "returned_origins": [], "citation_roles": [],
                })
                if origin not in record["returned_origins"]:
                    record["returned_origins"].append(origin)
    if answer["schema_version"] in {"1.3", "1.4", "1.5"}:
        for block in answer["answer_blocks"]:
            for modality, field in (("ASR", "utterance_ids"), ("FRAME", "frame_ids")):
                for identity in block[field]:
                    roles = records[modality][identity]["citation_roles"]
                    if block["kind"] not in roles:
                        roles.append(block["kind"])
    else:
        for modality, field, identity_key in (("ASR", "citations", "utterance_id"),
                                               ("FRAME", "visual_citations", "frame_id")):
            for citation in answer.get(field, []):
                records[modality][citation[identity_key]]["citation_roles"] = ["legacy_untyped"]
    summary = {"utterance_count": len(records["ASR"]), "frame_count": len(records["FRAME"]),
               "first_by_origin": {}, "returned_by_origin": {}}
    for field, first in (("first_by_origin", True), ("returned_by_origin", False)):
        summary[field] = {origin: {modality: sum(
            (record["first_acquired_origin"] == origin if first else origin in record["returned_origins"])
            for record in records[modality].values()) for modality in ("ASR", "FRAME")}
            for origin in ORIGINS}
    return records, summary


def _tool_diagnostics(answer):
    screen = {receipt["tool_step"]: receipt for receipt in answer.get("screen_searches", [])}
    speech = answer.get("speech_lookup")
    steps, known_statuses = [], {}
    attempted = {"ASR": False, "FRAME": False}
    for call in answer["tool_calls"]:
        name, number = call["tool_name"], call["step"]
        modality = "ASR" if name in TEXT_TOOLS else "FRAME"
        attempted[modality] = True
        row = {"step": number, "tool_name": name, "origin": call.get("origin", "MODEL"),
               "modality": modality, "utterance_count": len(call["result_utterance_ids"]),
               "frame_count": len(call.get("result_frame_ids", []))}
        row["empty_return"] = not (row["utterance_count"] or row["frame_count"])
        receipt, kind = (screen[number], "screen_search") if number in screen else (
            (speech, "speech_lookup") if speech and speech["tool_step"] == number else (None, None))
        if receipt:
            row.update(receipt_kind=kind, known_status=receipt["status"],
                       known_error_code=receipt.get("error_code"),
                       empty_return_cause="EXPLICIT_RECEIPT" if row["empty_return"] else None)
            key = kind + ":" + receipt["status"]
            known_statuses[key] = known_statuses.get(key, 0) + 1
        else:
            row.update(receipt_kind=None, known_status=None, known_error_code=None,
                       empty_return_cause="UNKNOWN" if row["empty_return"] else None)
        steps.append(row)
    return {"step_count": len(steps), "modality_attempted": attempted,
            "empty_return_steps": sum(row["empty_return"] for row in steps),
            "explicit_tool_error_steps": sum(row["known_status"] == "TOOL_ERROR" for row in steps),
            "explicit_status_counts": known_statuses, "steps": steps}


def evaluate_evidence_coverage(answer: dict, utterances: list[dict], reference: dict,
                               *, duration_ms: int) -> dict:
    """Measure required-group coverage of observed IDs and citations, not truth.

    ASR alternatives are OR within each group. FRAME groups use exact actual PTS
    in half-open windows. A reused focus frame remains first acquired by the user.
    """
    items = _validated_inputs(answer, utterances, reference, duration_ms)
    records, acquisition = _acquisition(answer)
    frames = answer.get("visual_evidence", [])
    metadata = {
        "ASR": {item["utterance_id"]: {name: item[name] for name in ("start_ms", "end_ms")}
                for item in items},
        "FRAME": {item["frame_id"]: {name: item[name]
                                    for name in ("source_pts", "source_time_base", "timestamp_ms")}
                  for item in frames},
    }
    structured = answer["schema_version"] in {"1.3", "1.4", "1.5"}
    counts = {"required": len(reference["requirements"]), "obtained": 0, "cited": 0,
              "observation_cited": 0 if structured else None,
              "inference_cited": 0 if structured else None, "legacy_untyped_cited": 0}
    groups = []
    for requirement in reference["requirements"]:
        modality = requirement["modality"]
        if modality == "ASR":
            alternatives = set(requirement["acceptable_utterance_ids"])
            criterion = {"acceptable_utterance_ids": list(requirement["acceptable_utterance_ids"])}
            matches = [item["utterance_id"] for item in items
                       if item["utterance_id"] in alternatives and item["utterance_id"] in records["ASR"]]
        else:
            criterion = {name: requirement[name] for name in ("start_ms", "end_ms")}
            matches = [frame["frame_id"] for frame in frames
                       if requirement["start_ms"] <= actual_position(frame)*1000 < requirement["end_ms"]]
        details = [{"id": identity, "obtained": True,
                    "cited": bool(records[modality][identity]["citation_roles"]),
                    **metadata[modality][identity],
                    **{key: list(value) if isinstance(value, list) else value
                       for key, value in records[modality][identity].items()}}
                   for identity in matches]
        roles = {role for detail in details for role in detail["citation_roles"]}
        row = {"id": requirement["id"], "modality": modality, "criterion": criterion,
               "obtained": bool(details),
               "cited": any(detail["cited"] for detail in details),
               "observation_cited": bool(roles & OBSERVATION_ROLES) if structured else None,
               "inference_cited": "INFERENCE" in roles if structured else None,
               "legacy_untyped_cited": "legacy_untyped" in roles, "matched_evidence": details}
        groups.append(row)
        for name in ("obtained", "cited", "observation_cited", "inference_cited", "legacy_untyped_cited"):
            if counts[name] is not None:
                counts[name] += row[name]
    coverage = {name: value / counts["required"] if counts["required"] and value is not None else None
                for name, value in counts.items() if name != "required"}
    return {"counts": counts, "coverage": coverage, "per_requirement": groups,
            "acquisition": acquisition, "tool_diagnostics": _tool_diagnostics(answer),
            "semantic_quality": None,
            "limitations": [
                "Coverage counts declared requirement groups against validated tool traces and citations, not answer correctness.",
                "File or manifest consistency does not independently prove a model saw these pixels or understood their content.",
                "Reference provenance is declared metadata, not independently verified human annotation.",
                "Empty tool returns without explicit receipts have an unknown cause and do not prove absence from the video.",
                "Observation and inference coverage can overlap; legacy untyped citations do not provide structured citation roles.",
            ]}
