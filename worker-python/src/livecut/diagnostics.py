"""Small public failure context; exception text and provider data stay private."""

from __future__ import annotations

from typing import Any

_REASONS = {
    "INVALID_MODEL_OUTPUT": {"ASSIGNMENT_KEYS_MISMATCH", "CLASSIFICATION_VALUES_INVALID",
                             "CLASSIFICATION_SCHEMA_INVALID"},
    "INVALID_DISCOVERY_OUTPUT": {"DISCOVERY_SCHEMA_INVALID"},
    "BUDGET_EXHAUSTED": {"REQUEST_BUDGET_EXHAUSTED", "WINDOW_LIMIT_EXCEEDED"},
    "MODEL_BUDGET_EXHAUSTED": {"REQUEST_BUDGET_EXHAUSTED"},
}
_COUNTS = ("missing_core_id_count", "extra_context_id_count", "unknown_key_count",
           "non_core_key_count", "value_error_count")
_REPAIRS = {"NOT_ATTEMPTED", "FAILED_VALIDATION", "INTERRUPTED"}


def failure_diagnostic(exc: Exception, code: str) -> dict[str, Any] | None:
    """Keep only bounded enums and counts; Java supplies identity and policy.

    Never derive fields from str(exc), an exception cause, prompts, IDs or raw
    response bodies. Unsupported optional fields can be omitted safely.
    """
    value = getattr(exc, "diagnostic", None)
    if not isinstance(value, dict):
        return None
    result: dict[str, Any] = {}
    reason = value.get("reason_code")
    if isinstance(reason, str) and reason in _REASONS.get(code, set()):
        result["reason_code"] = reason
    index, count = value.get("window_index"), value.get("window_count")
    if type(index) is int and type(count) is int and 0 <= index < count <= 10000:
        result.update(window_index=index, window_count=count)
    for field in _COUNTS:
        item = value.get(field)
        if type(item) is int and 0 <= item <= 1000000:
            result[field] = item
    totals = ("non_core_key_count", "extra_context_id_count", "unknown_key_count")
    if (all(field in result for field in totals)
            and result[totals[0]] != result[totals[1]] + result[totals[2]]):
        for field in totals:
            result.pop(field)
    repair = value.get("format_repair")
    if (isinstance(repair, str) and repair in _REPAIRS
            and (repair != "FAILED_VALIDATION" or code == "INVALID_MODEL_OUTPUT")):
        result["format_repair"] = repair
    return result or None
