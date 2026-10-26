"""Hand-computable synthetic inputs test binding rules, never recognition quality."""

from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from pathlib import Path

import pytest
from livecut.evaluation_inputs import (
    MAX_JSON_BYTES,
    EvaluationInputError,
    load_json,
    validate_annotation,
    validate_evaluation_binding,
)

ROOT = Path(__file__).resolve().parents[2]
SOURCE = "a" * 64
PREDICTION = "b" * 64


def annotation() -> dict:
    return load_json(ROOT / "fixtures/localization-annotation.synthetic.json")


def prediction() -> dict:
    return load_json(ROOT / "contracts/analysis-result.example.json")


def real_pair() -> tuple[dict, dict]:
    # Only change metadata of synthetic bytes to exercise the real-input guard.
    # Neither this fixture nor its fake manifest establishes real model output.
    pred, reference = prediction(), annotation()
    pred["data_kind"] = "model_result"
    reference["provenance"].update(label_source="human", split="test", independently_reviewed=True)
    return pred, reference


def manifest(pred: dict) -> dict:
    token, stage = "11111111-1111-4111-8111-111111111111", "synthetic_stage_001"
    key = f"jobs/{pred['job_id']}/stages/{stage}/attempts/1/{token}/analysis-result.json"
    return {"schema_version": 1, "job_id": pred["job_id"], "stage_id": stage,
            "video_id": pred["video_id"], "analysis_generation": pred["analysis_generation"],
            "stage_type": "SEGMENT", "attempt": 1, "run_token": token, "input_sha256": SOURCE,
            "data_kind": pred["data_kind"], "review_status": "DRAFT",
            "artifacts": [{"kind": "analysis_result", "key": key, "sha256": PREDICTION}]}


def bound(pred: dict, reference: dict, **changes: object) -> None:
    arguments = {"source_sha256": SOURCE, "duration_ms": 90000, "manifest": manifest(pred),
                 "prediction_sha256": PREDICTION}
    arguments.update(changes)
    validate_evaluation_binding(pred, reference, **arguments)


def test_synthetic_example_is_explicit_and_matches_the_existing_prediction_without_mutation():
    pred, reference = prediction(), annotation()
    before = deepcopy((pred, reference))
    validate_annotation(reference)
    validate_evaluation_binding(pred, reference, fixture=True)
    assert (pred, reference) == before
    with pytest.raises(EvaluationInputError, match="PROVENANCE"):
        validate_evaluation_binding(pred, reference)


@pytest.mark.parametrize("field,value", [("duration_ms", True), ("duration_ms", 90000.0),
                                        ("duration_ms", 0), ("source_sha256", "A" * 64),
                                        ("source_sha256", SOURCE + "\n"), ("video_id", "id\n"),
                                        ("notes", "x" * 2001), ("unexpected", "private")])
def test_annotation_scalar_bounds_and_unknown_fields(field, value):
    reference = annotation()
    reference[field] = value
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference)


@pytest.mark.parametrize("change", [
    {"start_ms": True}, {"end_ms": 25000.0}, {"end_ms": 5000}, {"start_ms": -1},
    {"end_ms": 90001}, {"sku_id": "not-an-input"}, {"id": "id\n"},
])
def test_gt_segments_reject_invalid_milliseconds_and_unknown_fields(change):
    reference = annotation()
    reference["segments"][0].update(change)
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference)


def test_gt_ids_are_unique_and_the_explicit_500_event_limit_is_enforced():
    reference = annotation()
    reference["segments"] = [{"id": f"g{i}", "start_ms": 0, "end_ms": 1} for i in range(500)]
    validate_annotation(reference)
    reference["segments"].append({"id": "g500", "start_ms": 0, "end_ms": 1})
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference)
    reference = annotation()
    reference["segments"][1]["id"] = reference["segments"][0]["id"]
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference)


def test_partial_ranges_are_explicit_separated_and_each_gt_must_fit_inside_one_range():
    reference = annotation()
    reference.update(annotation_status="partially_annotated",
                     annotated_ranges=[{"start_ms": 0, "end_ms": 30000},
                                       {"start_ms": 60000, "end_ms": 90000}])
    reference["segments"].pop(1)
    validate_annotation(reference)
    reference["segments"][0].update(start_ms=20000, end_ms=70000)
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference)
    for ranges in ([], [{"start_ms": 0, "end_ms": 90000}],
                   [{"start_ms": 60000, "end_ms": 90000}, {"start_ms": 0, "end_ms": 30000}],
                   [{"start_ms": 0, "end_ms": 30000}, {"start_ms": 30000, "end_ms": 40000}],
                   [{"start_ms": 0, "end_ms": 30000}, {"start_ms": 29999, "end_ms": 40000}]):
        reference.update(annotated_ranges=ranges, segments=[])
        with pytest.raises(EvaluationInputError):
            validate_annotation(reference)


def test_full_coverage_requires_the_complete_video_and_a_true_negative_can_be_empty():
    reference = annotation()
    reference["segments"] = []
    validate_annotation(reference)
    reference["annotated_ranges"][0]["end_ms"] -= 1
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference)


def test_unlabeled_templates_are_valid_for_audit_only_and_never_become_negative_gold():
    pred, reference = real_pair()
    reference.update(annotation_status="not_annotated", annotated_ranges=[], segments=[])
    reference["provenance"].update(label_source="unlabeled", independently_reviewed=False)
    validate_annotation(reference, require_annotated=False)
    bound(pred, reference, require_annotated=False)
    with pytest.raises(EvaluationInputError, match="ANNOTATION_REQUIRED"):
        bound(pred, reference)
    reference["annotated_ranges"] = [{"start_ms": 0, "end_ms": 1}]
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference, require_annotated=False)
    reference = annotation()
    reference["provenance"]["label_source"] = "unlabeled"
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference)


@pytest.mark.parametrize("source", ["agent_assisted", "synthetic_fixture", "unlabeled"])
def test_non_human_annotations_cannot_claim_independent_human_review(source):
    reference = annotation()
    reference["provenance"].update(label_source=source, split="test", independently_reviewed=True)
    with pytest.raises(EvaluationInputError):
        validate_annotation(reference)


def test_agent_assisted_test_reference_can_be_validated_but_is_not_upgraded_to_human():
    pred, reference = real_pair()
    reference["provenance"].update(label_source="agent_assisted", independently_reviewed=False)
    bound(pred, reference)
    assert reference["provenance"]["label_source"] == "agent_assisted"
    assert reference["provenance"]["independently_reviewed"] is False


@pytest.mark.parametrize("label,kind,fixture", [("human", "synthetic_fixture", True),
                                               ("agent_assisted", "synthetic_fixture", True),
                                               ("synthetic_fixture", "model_result", False),
                                               ("human", "model_result", True)])
def test_synthetic_and_real_provenance_cannot_cross_or_be_hidden_by_a_fixture_flag(label, kind, fixture):
    pred, reference = real_pair()
    pred["data_kind"] = kind
    reference["provenance"].update(label_source=label, independently_reviewed=False)
    with pytest.raises(EvaluationInputError, match="PROVENANCE"):
        bound(pred, reference, fixture=fixture)


@pytest.mark.parametrize("missing", ["source_sha256", "duration_ms", "manifest", "prediction_sha256"])
def test_real_binding_requires_actual_source_probe_manifest_and_prediction_file_digest(missing):
    pred, reference = real_pair()
    with pytest.raises(EvaluationInputError):
        bound(pred, reference, **{missing: None})


@pytest.mark.parametrize("change", [{"source_sha256": "c" * 64}, {"duration_ms": 89999},
                                  {"duration_ms": True}, {"prediction_sha256": "c" * 64}])
def test_real_binding_rejects_source_duration_and_prediction_digest_mismatch(change):
    pred, reference = real_pair()
    with pytest.raises(EvaluationInputError):
        bound(pred, reference, **change)


@pytest.mark.parametrize("field,value", [("job_id", "other"), ("video_id", "other"),
                                        ("analysis_generation", True), ("analysis_generation", 2),
                                        ("input_sha256", "c" * 64), ("stage_type", "MEDIA"),
                                        ("stage_type", []), ("review_status", "CONFIRMED"),
                                        ("data_kind", "reviewed_export"), ("attempt", False),
                                        ("run_token", "bad"), ("extra", "private")])
def test_manifest_identity_schema_and_execution_metadata_are_checked(field, value):
    pred, reference = real_pair()
    value_manifest = manifest(pred)
    value_manifest[field] = value
    with pytest.raises(EvaluationInputError):
        bound(pred, reference, manifest=value_manifest)


def test_artifact_reference_is_unique_safe_and_bound_but_never_opened(monkeypatch):
    pred, reference = real_pair()
    value_manifest = manifest(pred)
    validate_annotation(reference)
    original = Path.open

    def guarded(path, *args, **kwargs):
        assert path.name != "analysis-result.json", "Manifest artifact references must not be opened"
        return original(path, *args, **kwargs)

    monkeypatch.setattr(Path, "open", guarded)
    bound(pred, reference)
    for key in ("../private", "C:/private", "/private", "https://private", "jobs\\private", "unrelated/result.json"):
        changed = deepcopy(value_manifest)
        changed["artifacts"][0]["key"] = key
        with pytest.raises(EvaluationInputError):
            bound(pred, reference, manifest=changed)
    changed = deepcopy(value_manifest)
    duplicate = dict(changed["artifacts"][0])
    duplicate["key"] = duplicate["key"].replace("analysis-result.json", "other.json")
    changed["artifacts"].append(duplicate)
    with pytest.raises(EvaluationInputError):
        bound(pred, reference, manifest=changed)


def test_invalid_prediction_and_changed_video_identity_are_rejected_without_echoing_model_text():
    pred, reference = real_pair()
    pred["review_status"] = "CONFIRMED"
    with pytest.raises(EvaluationInputError) as failure:
        bound(pred, reference)
    assert str(failure.value) == "INVALID_EVALUATION_PREDICTION"
    pred, reference = real_pair()
    reference["video_id"] = "other"
    with pytest.raises(EvaluationInputError):
        bound(pred, reference)


def test_manifest_rejects_duplicate_non_prediction_kinds_like_the_java_publisher():
    pred, reference = real_pair()
    value_manifest = manifest(pred)
    prefix = value_manifest["artifacts"][0]["key"].rsplit("/", 1)[0]
    value_manifest["artifacts"].append({"kind": "ocr_provenance", "key": prefix + "/one.json",
                                        "sha256": "c" * 64})
    bound(pred, reference, manifest=value_manifest)
    value_manifest["artifacts"].append({"kind": "ocr_provenance", "key": prefix + "/two.json",
                                        "sha256": "d" * 64})
    with pytest.raises(EvaluationInputError):
        bound(pred, reference, manifest=value_manifest)


def test_json_limits_flags_and_unreadable_files_fail_safely(tmp_path):
    for limit in (False, 0, -1, 1.0):
        with pytest.raises(EvaluationInputError, match="INVALID_JSON_LIMIT"):
            load_json(tmp_path / "missing-private.json", max_bytes=limit)
    with pytest.raises(EvaluationInputError, match="INVALID_EVALUATION_JSON"):
        load_json(tmp_path / "missing-private.json")
    path = tmp_path / "bad-utf8.json"
    path.write_bytes(b'{"a":"\xff"}')
    with pytest.raises(EvaluationInputError, match="INVALID_EVALUATION_JSON"):
        load_json(path)
    with pytest.raises(EvaluationInputError):
        validate_evaluation_binding(prediction(), annotation(), fixture=1)
    with pytest.raises(EvaluationInputError):
        validate_annotation(annotation(), require_annotated=1)


@pytest.mark.parametrize("text", ['{"a":1,"a":2}', '{"a":{"b":1,"b":2}}',
                                 '{"a":NaN}', '{"a":Infinity}', '{"a":1e999}',
                                 '{}{}', '[1]', '{"a":'])
def test_json_loader_rejects_duplicate_nonfinite_invalid_and_nonobject_json(tmp_path, text):
    path = tmp_path / "private-input.json"
    path.write_text(text, encoding="utf-8")
    with pytest.raises(EvaluationInputError) as failure:
        load_json(path)
    assert str(failure.value) == "INVALID_EVALUATION_JSON"
    assert str(path) not in str(failure.value)


def test_json_loader_accepts_bom_without_rehashing_and_rejects_size_depth_and_node_overflow(tmp_path):
    path = tmp_path / "input.json"
    raw = b"\xef\xbb\xbf" + json.dumps({"value": "[\\\"nested\\\"]"}).encode()
    path.write_bytes(raw)
    before = hashlib.sha256(raw).hexdigest()
    assert load_json(path)["value"] == '[\\"nested\\"]'
    assert hashlib.sha256(path.read_bytes()).hexdigest() == before
    with pytest.raises(EvaluationInputError):
        load_json(path, max_bytes=len(raw) - 1)
    with pytest.raises(EvaluationInputError):
        load_json(path, max_bytes=MAX_JSON_BYTES + 1)
    path.write_text('{"value":' + '[' * 65 + '0' + ']' * 65 + '}', encoding="utf-8")
    with pytest.raises(EvaluationInputError):
        load_json(path)
    path.write_text(json.dumps({"value": [0] * 200000}), encoding="utf-8")
    with pytest.raises(EvaluationInputError):
        load_json(path)
