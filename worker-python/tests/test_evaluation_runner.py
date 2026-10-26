"""Offline report plumbing uses synthetic references, never model quality claims."""

import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from livecut.cli import main
from livecut.evaluation import EvaluationError
from livecut.evaluation_inputs import EvaluationInputError
from livecut.evaluation_runner import annotation_template, audit_files, evaluate_files

ROOT = Path(__file__).resolve().parents[2]


def document(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    return path


@pytest.fixture
def fixture_inputs(tmp_path):
    prediction = document(ROOT / "contracts/analysis-result.example.json")
    annotation = document(ROOT / "fixtures/localization-annotation.synthetic.json")
    return write(tmp_path / "prediction.json", prediction), write(tmp_path / "annotation.json", annotation)


def test_synthetic_cli_writes_consistent_json_and_escaped_html_without_providers(tmp_path, fixture_inputs, monkeypatch):
    prediction, annotation = fixture_inputs
    for name in ("transcribe_file", "discover_products", "analyze_video", "cached_observe_video_ocr"):
        monkeypatch.setattr("livecut.cli." + name, Mock(side_effect=AssertionError("Offline evaluation cannot call providers")))
    value = document(prediction)
    value["job_id"] = "<script>alert('fixture')</script>"
    write(prediction, value)
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        assert main(["evaluate-localization", str(prediction), str(annotation), "--fixture",
                     "--output-dir", str(tmp_path / "report")]) == 0
    assert json.loads(output.getvalue())["data_scope"] == "synthetic_fixture"
    report = document(tmp_path / "report/report.json")
    assert report["report_kind"] == "localization_evaluation"
    assert report["result"]["counts"]["reference_total"] == 3
    assert [item["tp"] for item in report["result"]["thresholds"]] == [3, 3, 3]
    html = (tmp_path / "report/index.html").read_text(encoding="utf-8")
    assert "合成验证：不代表识别质量" in html
    assert "<script>" not in html and "&lt;script&gt;" in html
    assert report["binding"]["prediction_sha256"] == hashlib.sha256(prediction.read_bytes()).hexdigest()


def test_unscored_audit_has_no_quality_metrics_and_reports_empty_time_bins(tmp_path, fixture_inputs):
    prediction, _ = fixture_inputs
    report = audit_files(prediction, fixture=True, output_dir=tmp_path / "audit")
    assert report["data_scope"] == "unscored_audit"
    assert "result" not in report and "reference" not in report
    assert not any(key in json.dumps(report) for key in ('"precision"', '"recall"', '"f1"'))
    assert report["evidence_audit"]["maximum_gap_including_edges_ms"] > 0
    assert report["binding"]["source_verification"] == "fixture_metadata_only"


def test_report_keeps_false_positive_and_missed_reference_times(tmp_path, fixture_inputs):
    prediction, annotation = fixture_inputs
    value = document(annotation)
    value["segments"][-1].update(start_ms=50000, end_ms=60000)
    write(annotation, value)
    report = evaluate_files(prediction, annotation, fixture=True, output_dir=tmp_path / "errors")
    primary = report["result"]["thresholds"][1]
    assert (primary["tp"], primary["fp"], primary["fn"]) == (2, 1, 1)
    assert primary["false_positive_ids"] == ["s_003"]
    assert primary["false_negative_ids"] == ["g_003"]
    html = (tmp_path / "errors/index.html").read_text(encoding="utf-8")
    assert "未匹配预测：s_003 [65000, 85000) ms" in html
    assert "未匹配参考：g_003 [50000, 60000) ms" in html


def test_unannotated_template_never_becomes_all_negative_score(tmp_path, fixture_inputs):
    prediction, annotation = fixture_inputs
    value = document(annotation)
    value.update(annotation_status="not_annotated", annotated_ranges=[], segments=[])
    value["provenance"]["label_source"] = "unlabeled"
    write(annotation, value)
    with pytest.raises(EvaluationInputError) as failure:
        evaluate_files(prediction, annotation, fixture=True, output_dir=tmp_path / "forbidden")
    assert failure.value.code == "ANNOTATION_REQUIRED"
    assert not (tmp_path / "forbidden").exists()


def test_existing_report_is_preserved_and_inputs_never_modified(tmp_path, fixture_inputs):
    prediction, annotation = fixture_inputs
    original = [path.read_bytes() for path in fixture_inputs]
    evaluate_files(prediction, annotation, fixture=True, output_dir=tmp_path / "report")
    report = (tmp_path / "report/report.json").read_bytes()
    with pytest.raises(EvaluationError, match="existing outputs"):
        evaluate_files(prediction, annotation, fixture=True, output_dir=tmp_path / "report")
    assert [path.read_bytes() for path in fixture_inputs] == original
    assert (tmp_path / "report/report.json").read_bytes() == report


def test_real_mode_requires_source_binding_and_fixture_flag_cannot_hide_a_real_source(tmp_path, fixture_inputs):
    prediction, annotation = fixture_inputs
    with pytest.raises(EvaluationInputError):
        evaluate_files(prediction, annotation, output_dir=tmp_path / "unbound")
    source = tmp_path / "video.mp4"
    source.write_bytes(b"synthetic placeholder")
    with pytest.raises(EvaluationError, match="Fixture mode"):
        evaluate_files(prediction, annotation, fixture=True, source=source, output_dir=tmp_path / "mixed")
    assert not (tmp_path / "unbound").exists() and not (tmp_path / "mixed").exists()


def test_real_local_manifest_binding_is_required_and_does_not_leak_paths_or_token(tmp_path, fixture_inputs, monkeypatch):
    prediction_path, annotation_path = fixture_inputs
    source = tmp_path / "source.mp4"
    source.write_bytes(b"Synthetic video bytes; media probe is a unit fixture")
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    prediction = document(prediction_path)
    prediction["data_kind"] = "model_result"
    write(prediction_path, prediction)
    annotation = document(annotation_path)
    annotation["source_sha256"] = source_hash
    annotation["provenance"].update(label_source="agent_assisted")
    write(annotation_path, annotation)
    token = "synthetic-private-run-token"
    manifest = {"schema_version": 1, "job_id": prediction["job_id"], "stage_id": "fixture-stage",
                "video_id": prediction["video_id"], "analysis_generation": 1, "stage_type": "SEGMENT",
                "attempt": 1, "run_token": token, "input_sha256": source_hash,
                "data_kind": "model_result", "review_status": "DRAFT", "artifacts": [{
                    "kind": "analysis_result", "sha256": hashlib.sha256(prediction_path.read_bytes()).hexdigest(),
                    "key": f"jobs/{prediction['job_id']}/stages/fixture-stage/attempts/1/{token}/analysis-result.json"}]}
    manifest_path = write(tmp_path / "manifest.json", manifest)
    monkeypatch.setattr("livecut.evaluation_runner.probe_media", lambda *args, **kwargs:
                        SimpleNamespace(duration_ms=prediction["duration_ms"]))
    result = evaluate_files(prediction_path, annotation_path, source=source,
                            prediction_manifest=manifest_path, output_dir=tmp_path / "real-bound")
    assert result["data_scope"] == "agent_assisted_development"
    serialized = json.dumps(result)
    assert token not in serialized and str(tmp_path) not in serialized
    changed = copy.deepcopy(manifest)
    changed["artifacts"][0]["sha256"] = "0" * 64
    write(manifest_path, changed)
    with pytest.raises(EvaluationInputError):
        audit_files(prediction_path, source=source, prediction_manifest=manifest_path,
                    output_dir=tmp_path / "tampered")


def test_template_cli_is_explicitly_unlabeled_and_never_overwrites_source(tmp_path, monkeypatch):
    source = tmp_path / "source.mp4"
    source.write_bytes(b"synthetic media bytes")
    monkeypatch.setattr("livecut.evaluation_runner.probe_media", lambda *args, **kwargs: SimpleNamespace(duration_ms=1000))
    template = annotation_template(source, video_id="fixture-video", original_video_id="fixture-original")
    assert template["annotation_status"] == "not_annotated" and template["segments"] == []
    assert template["provenance"]["label_source"] == "unlabeled"
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        assert main(["init-annotation", str(source), "--video-id", "fixture-video", "--original-video-id",
                     "fixture-original", "--output", str(source)]) == 2
    assert source.read_bytes() == b"synthetic media bytes"
