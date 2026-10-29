"""Topic predictions have their own contract; no synthetic product identities."""
import json

from .contracts import (
    ContractValidationError,
    _default_schema_path,
    _json_path,
    _StrictValidator,
)


def validate_topic_result(data: dict) -> None:
    schema = json.loads(_default_schema_path().with_name("topic-analysis-result.schema.json").read_text(encoding="utf-8"))
    errors = [f"{_json_path(error.absolute_path)}: {error.message}"
              for error in _StrictValidator(schema).iter_errors(data)]
    if errors:
        raise ContractValidationError(errors)
    utterances = data["utterances"]
    ids = [item["utterance_id"] for item in utterances]
    if len(ids) != len(set(ids)):
        errors.append("utterance IDs must be unique")
    for index, item in enumerate(utterances):
        if not 0 <= item["start_ms"] < item["end_ms"] <= data["duration_ms"]:
            errors.append("utterance interval outside source")
        if not item["text"].strip():
            errors.append("utterance text must not be empty")
        if index and item["start_ms"] < utterances[index - 1]["end_ms"]:
            errors.append("utterances must be chronological and non-overlapping")
    by_id = {item["utterance_id"]: item for item in utterances}
    frame_ids = set()
    for frame in data["frames"]:
        if frame["frame_id"] in frame_ids or not 0 <= frame["timestamp_ms"] < data["duration_ms"]:
            errors.append("frame IDs and times must be valid source observations")
        frame_ids.add(frame["frame_id"])
    flattened, segment_ids = [], set()
    for segment in data["segments"]:
        evidence = segment["audio_evidence_utterance_ids"]
        if len({word.strip() for word in segment["keywords"]}) != len(segment["keywords"]):
            errors.append("topic keywords must be unique after trimming")
        if segment["segment_id"] in segment_ids:
            errors.append("segment IDs must be unique")
        segment_ids.add(segment["segment_id"])
        if (not evidence or evidence[0] != segment["start_utterance_id"]
                or evidence[-1] != segment["end_utterance_id"] or any(uid not in by_id for uid in evidence)):
            errors.append("topic range must reference source utterances")
            continue
        if (segment["start_ms"] != by_id[evidence[0]]["start_ms"]
                or segment["end_ms"] != by_id[evidence[-1]]["end_ms"]):
            errors.append("topic times must equal source ASR boundaries")
        flattened.extend(evidence)
    if flattened != ids:
        errors.append("topics must partition every source utterance exactly once in source order")
    if errors:
        raise ContractValidationError(errors)
