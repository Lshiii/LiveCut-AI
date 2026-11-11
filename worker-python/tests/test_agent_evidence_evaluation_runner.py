"""Offline file/CLI boundary checks; local media and synthetic model replies."""
import copy
import hashlib
import json
import os
import shutil
import socket
import threading
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

import pytest
from livecut.agent_evidence_evaluation import EvidenceEvaluationError
from livecut.agent_evidence_evaluation_runner import (
    evaluate_agent_evidence_files,
    evidence_reference_template,
)
from livecut.agent_speech_lookup import PROMPT_VERSION as SPEECH_PROMPT
from livecut.worker import LeaseGuard, _native_path, sha256
from test_agent_evidence import block, reply
from test_agent_evidence_evaluation import asr, frame, reference_for
from test_agent_visual import real_source as _real_source
from test_agent_visual import tool
from test_agent_visual_worker import mock_executor, visual_context
from test_worker import claim as base_claim

real_source = _real_source
FIXTURES = Path(__file__).resolve().parents[2] / "fixtures/agent-evidence-evaluation"


def read(path):
    return json.loads(_native_path(Path(path)).read_bytes())


def write(path, value):
    logical = Path(path)
    native = _native_path(logical)
    native.parent.mkdir(parents=True, exist_ok=True)
    native.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return logical


@pytest.fixture(autouse=True)
def forbid_network(monkeypatch):
    def forbidden(*_args, **_kwargs):
        pytest.fail("Offline evidence evaluation attempted network access")
    monkeypatch.setattr(socket, "create_connection", forbidden)
    monkeypatch.setattr(socket.socket, "connect", forbidden)


def fixture_evaluate(output, *, name="visual-answer.json", reference=None, transcript=None, **kwargs):
    return evaluate_agent_evidence_files(FIXTURES / name, reference or FIXTURES / "reference.json",
        transcript_path=transcript or FIXTURES / "transcript.json", output_dir=output,
        fixture=True, **kwargs)


@pytest.fixture
def actual_bundle(tmp_path, real_source, monkeypatch):
    # Keep the real Worker adapter while giving its synthetic fixture Java-shaped
    # owner paths. There is no database, publication claim or provider connection.
    job, stage, video = (str(uuid4()) for _ in range(3))
    token = uuid4().hex
    def canonical_claim(**overrides):
        return base_claim(**{**overrides, "job_id": job, "stage_id": stage,
                            "video_id": video, "run_token": token})
    monkeypatch.setattr("test_worker_execution.claim", canonical_claim)
    ctx = visual_context(tmp_path / "storage", real_source)
    ctx.lease = replace(ctx.lease, stage_type="AGENT_EVIDENCE")
    ctx.guard = LeaseGuard(ctx.lease, threading.Event())
    turn = ctx.lease.config["agent_turn"]
    turn.update(evidence_mode="structured", session_id=str(uuid4()), turn_id=str(uuid4()),
                question="实际本地测试问题 <script>privateQuestion()</script>")
    seen = []
    manifest = mock_executor(monkeypatch, [tool("inspect_frames", timestamps_ms=[0, 500]),
        reply(block(images=["f0001", "f0002"], text="合成回答 <script>privateAnswer()</script>"))], seen)(ctx)
    assert len(seen) == 2
    manifest_path = write(ctx.directory / "manifest.json", manifest)
    answer_path = ctx.directory / "agent-answer.json"
    answer = read(answer_path)
    transcript = ctx.storage_root / turn["source_transcript_key"]
    utterances = read(transcript)
    reference = reference_for(answer, turn["duration_ms"], [
        asr(ids=(utterances[0]["utterance_id"],)), frame("initial", 0, 1), frame("next", 500, 501)])
    reference["notes"] = "PRIVATE_REFERENCE_NOTE <img src=x onerror=privateNote()>"
    reference_path = write(tmp_path / "reference.json", reference)
    return {"root": ctx.storage_root, "source": ctx.storage_root / ctx.lease.source_key,
            "answer": answer_path, "reference": reference_path, "transcript": transcript,
            "manifest": manifest_path, "ctx": ctx, "seen": seen,
            "frames": [ctx.storage_root / artifact["key"] for artifact in manifest["artifacts"]
                       if artifact["kind"] == "agent_visual_frame"]}


def evaluate_actual(bundle, output):
    return evaluate_agent_evidence_files(bundle["answer"], bundle["reference"],
        transcript_path=bundle["transcript"], source=bundle["source"],
        answer_manifest=bundle["manifest"], storage_root=bundle["root"], output_dir=output)


@pytest.mark.parametrize("name,count", [("text-answer.json", 1), ("visual-answer.json", 3)])
def test_public_fixture_creates_bounded_json_and_html_with_constructed_group_counts(tmp_path, name, count):
    output = tmp_path / "report"
    original = {path.name: sha256(path) for path in FIXTURES.iterdir() if path.is_file()}
    report = fixture_evaluate(output, name=name)
    assert report["schema_version"] == "1.0"
    assert report["data_scope"] == "synthetic_fixture"
    assert report["result"]["counts"]["required"] == 4
    assert report["result"]["counts"]["obtained"] == report["result"]["counts"]["cited"] == count
    assert report["result"]["semantic_quality"] is None
    assert read(output / "report.json") == report
    html = (output / "index.html").read_text("utf-8")
    assert "<html" in html.lower() and "<table" in html.lower()
    assert "unobserved-later-view" in html
    assert str(FIXTURES.resolve()) not in html
    assert original == {path.name: sha256(path) for path in FIXTURES.iterdir() if path.is_file()}


def test_unannotated_reference_publishes_null_coverage_instead_of_a_perfect_score(tmp_path):
    reference = read(FIXTURES / "reference.json")
    reference.update(annotation_status="not_annotated", requirements=[],
        provenance={"label_source": "unlabeled", "split": "dev", "independently_reviewed": False})
    reference_path = write(tmp_path / "unannotated.json", reference)
    report = fixture_evaluate(tmp_path / "report", reference=reference_path)
    assert report["result"]["counts"]["required"] == 0
    assert all(value is None for value in report["result"]["coverage"].values())


@pytest.mark.parametrize("field", ["source", "answer_manifest", "storage_root"])
def test_fixture_mode_rejects_every_real_source_parameter_without_writing(tmp_path, field):
    output = tmp_path / "report"
    with pytest.raises(EvidenceEvaluationError):
        fixture_evaluate(output, **{field: tmp_path / "unused"})
    assert not output.exists()


@pytest.mark.parametrize("provided", [(), ("source",), ("answer_manifest",), ("storage_root",),
    ("source", "answer_manifest"), ("source", "storage_root"), ("answer_manifest", "storage_root")])
def test_non_fixture_mode_requires_all_real_input_parameters_before_reporting(tmp_path, provided):
    output = tmp_path / "report"
    with pytest.raises(EvidenceEvaluationError):
        evaluate_agent_evidence_files(FIXTURES / "visual-answer.json", FIXTURES / "reference.json",
            transcript_path=FIXTURES / "transcript.json", output_dir=output,
            **{field: tmp_path / "unused" for field in provided})
    assert not output.exists()


def test_existing_output_directory_is_rejected_and_prior_bytes_are_preserved(tmp_path):
    output = tmp_path / "report"
    output.mkdir()
    sentinel = output / "report.json"
    sentinel.write_bytes(b"original-unrelated-result")
    with pytest.raises(EvidenceEvaluationError):
        fixture_evaluate(output)
    assert sentinel.read_bytes() == b"original-unrelated-result"
    assert list(output.iterdir()) == [sentinel]


@pytest.mark.parametrize("changed", ["transcript_bytes", "reference_video", "reference_question", "invalid_answer"])
def test_fixture_hash_and_exact_question_drift_rejects_without_partial_report(tmp_path, changed):
    answer, reference, transcript = (read(FIXTURES / name) for name in
        ("visual-answer.json", "reference.json", "transcript.json"))
    answer_path = write(tmp_path / "answer.json", answer)
    reference_path = write(tmp_path / "reference.json", reference)
    # Start with the exact frozen transcript file, retaining its original SHA.
    transcript_path = tmp_path / "transcript.json"
    shutil.copy2(FIXTURES / "transcript.json", transcript_path)
    if changed == "transcript_bytes":
        transcript_path.write_bytes(transcript_path.read_bytes() + b"\r\n")
    elif changed == "reference_video":
        reference["source"]["video_id"] = "different-video"
        write(reference_path, reference)
    elif changed == "reference_question":
        reference["question"] += " changed"
        write(reference_path, reference)
    else:
        answer["answer"] = "private forged result"
        write(answer_path, answer)
    output = tmp_path / "report"
    with pytest.raises(EvidenceEvaluationError) as raised:
        evaluate_agent_evidence_files(answer_path, reference_path, transcript_path=transcript_path,
            fixture=True, output_dir=output)
    assert str(raised.value) == raised.value.code
    assert str(tmp_path) not in str(raised.value) and "private" not in str(raised.value)
    assert not output.exists()


def test_valid_local_worker_files_verify_actual_jpegs_without_more_model_calls(actual_bundle, tmp_path):
    before = {path: sha256(path) for path in [actual_bundle["source"], actual_bundle["transcript"],
        actual_bundle["answer"], actual_bundle["manifest"], *actual_bundle["frames"]]}
    report = evaluate_actual(actual_bundle, tmp_path / "report")
    assert report["data_scope"] == "agent_assisted_development"
    assert report["result"]["counts"]["required"] == 3
    assert report["result"]["counts"]["obtained"] == report["result"]["counts"]["cited"] == 2
    assert report["result"]["acquisition"]["frame_count"] == 2
    assert len(actual_bundle["seen"]) == 2
    assert all(sha256(path) == digest for path, digest in before.items())
    serialized = json.dumps(report, ensure_ascii=False)
    html = (tmp_path / "report/index.html").read_text("utf-8")
    for secret in ("privateQuestion", "privateAnswer", "PRIVATE_REFERENCE_NOTE", str(actual_bundle["root"])):
        assert secret not in serialized and secret not in html
    assert "<script>private" not in html and "<img src=x" not in html


@pytest.mark.parametrize("changed", ["source", "transcript", "jpeg", "job", "other_attempt", "frame_size", "extra_ref"])
def test_real_file_corruption_and_cross_attempt_refs_do_not_publish_success(actual_bundle, tmp_path, changed):
    bundle = actual_bundle
    if changed in {"source", "transcript", "jpeg"}:
        path = bundle["frames"][0] if changed == "jpeg" else bundle[changed]
        _native_path(path).write_bytes(_native_path(path).read_bytes() + b"changed")
    else:
        manifest = read(bundle["manifest"])
        if changed == "job":
            manifest["job_id"] = str(uuid4())
        elif changed == "other_attempt":
            artifact = next(a for a in manifest["artifacts"] if a["kind"] == "agent_visual_frame")
            other = bundle["ctx"].directory.parent.parent / "2" / uuid4().hex / "visual-frames/f0001.jpg"
            _native_path(other).parent.mkdir(parents=True)
            shutil.copy2(_native_path(bundle["frames"][0]), _native_path(other))
            artifact["key"] = other.relative_to(bundle["root"]).as_posix()
        elif changed == "frame_size":
            answer = read(bundle["answer"])
            answer["visual_evidence"][0]["width"] += 1
            write(bundle["answer"], answer)
            next(a for a in manifest["artifacts"] if a["kind"] == "agent_answer")["sha256"] = sha256(bundle["answer"])
        else:
            artifact = copy.deepcopy(next(a for a in manifest["artifacts"] if a["kind"] == "agent_visual_frame"))
            extra = bundle["ctx"].directory / "visual-frames/f0032.jpg"
            shutil.copy2(_native_path(bundle["frames"][0]), _native_path(extra))
            artifact["key"] = extra.relative_to(bundle["root"]).as_posix()
            manifest["artifacts"].append(artifact)
        write(bundle["manifest"], manifest)
    output = tmp_path / "report"
    with pytest.raises(EvidenceEvaluationError) as raised:
        evaluate_actual(bundle, output)
    assert str(raised.value) == raised.value.code and str(tmp_path) not in str(raised.value)
    assert not output.exists()
    assert len(bundle["seen"]) == 2


def test_reference_template_freezes_raw_source_and_transcript_bytes_as_unlabeled(actual_bundle):
    transcript = actual_bundle["transcript"]
    _native_path(transcript).write_bytes(_native_path(transcript).read_bytes() + b"\r\n")
    template = evidence_reference_template(actual_bundle["source"], transcript,
        video_id=actual_bundle["ctx"].lease.video_id, question=" Unlabeled real-video question ")
    assert template["annotation_status"] == "not_annotated" and template["requirements"] == []
    assert template["provenance"] == {"label_source": "unlabeled", "split": "dev", "independently_reviewed": False}
    assert template["question"] == " Unlabeled real-video question "
    assert template["source"]["source_sha256"] == sha256(actual_bundle["source"])
    assert template["source"]["transcript_sha256"] == hashlib.sha256(_native_path(transcript).read_bytes()).hexdigest()
    assert template["source"]["duration_ms"] == 20000
    assert len(actual_bundle["seen"]) == 2


def test_optional_trace_bytes_are_verified_with_every_declared_artifact(actual_bundle, tmp_path):
    bundle = actual_bundle
    trace = write(bundle["ctx"].directory / "text-execution-trace.json", {"schema_version": 1, "events": []})
    manifest = read(bundle["manifest"])
    manifest["artifacts"].append({"kind": "text_execution_trace",
        "key": trace.relative_to(bundle["root"]).as_posix(), "sha256": sha256(trace)})
    write(bundle["manifest"], manifest)
    report = evaluate_actual(bundle, tmp_path / "valid-trace")
    assert report["binding"]["verified_artifact_count"] == 4
    _native_path(trace).write_bytes(b"changed trace bytes")
    output = tmp_path / "invalid-trace"
    with pytest.raises(EvidenceEvaluationError):
        evaluate_actual(bundle, output)
    assert not output.exists() and len(bundle["seen"]) == 2


def test_renderer_escapes_all_dynamic_report_text_instead_of_embedding_markup(tmp_path):
    from livecut.agent_evidence_evaluation_runner import _render

    report = fixture_evaluate(tmp_path / "report")
    payload = '<script data-check="x">unsafeRenderer()</script>&'
    report["reference"]["reference_id"] = payload
    report["limitations"].append(payload)
    report["result"]["per_requirement"][0]["id"] = payload
    report["result"]["per_requirement"][0]["criterion"] = {"fixture": payload}
    report["binding"]["renderer_regression"] = payload
    rendered = _render(report)
    assert payload not in rendered and "<script" not in rendered
    assert "&lt;script" in rendered and "&amp;" in rendered
    assert "unsafeRenderer()" in rendered


def test_concurrently_created_empty_output_directory_is_preserved_without_publication(tmp_path, monkeypatch):
    output = tmp_path / "raced-report"
    original_mkdir = Path.mkdir
    peer_inode = []
    def competing_mkdir(path, *args, **kwargs):
        if path.name == output.name and not path.exists():
            original_mkdir(path)
            peer_inode.append(path.stat().st_ino)
        return original_mkdir(path, *args, **kwargs)
    monkeypatch.setattr(Path, "mkdir", competing_mkdir)
    with pytest.raises(EvidenceEvaluationError, match="^AGENT_EVIDENCE_OUTPUT_EXISTS$"):
        fixture_evaluate(output)
    assert len(peer_inode) == 1 and output.stat().st_ino == peer_inode[0]
    assert list(output.iterdir()) == []


@pytest.mark.parametrize("failure", ["peer_report", "index_link"])
def test_link_publication_failure_preserves_peer_bytes_and_has_no_success_index(tmp_path, monkeypatch, failure):
    output = tmp_path / "report"
    original_link = os.link
    linked = []
    def failing_link(source, destination, *args, **kwargs):
        name = Path(destination).name
        linked.append(name)
        if failure == "peer_report" and name == "report.json":
            Path(destination).write_bytes(b"peer-file-must-survive")
            raise FileExistsError("simulated concurrent peer output")
        if failure == "index_link" and name == "index.html":
            raise OSError("simulated second-link failure")
        return original_link(source, destination, *args, **kwargs)
    monkeypatch.setattr(os, "link", failing_link)
    with pytest.raises(EvidenceEvaluationError) as raised:
        fixture_evaluate(output)
    assert str(raised.value) == raised.value.code
    assert not (output / "index.html").exists()
    if failure == "peer_report":
        assert (output / "report.json").read_bytes() == b"peer-file-must-survive"
        assert linked == ["report.json"]
    else:
        assert read(output / "report.json")["result"]["counts"]["required"] == 4
        assert linked == ["report.json", "index.html"]
    assert not list(tmp_path.glob(".agent-evaluation-*"))
    with pytest.raises(EvidenceEvaluationError, match="^AGENT_EVIDENCE_OUTPUT_EXISTS$"):
        fixture_evaluate(output)


def add_replay_receipt(bundle, *, speech=True):
    answer = read(bundle["answer"])
    if speech:
        utterances = read(bundle["transcript"])
        ids = [item["utterance_id"] for item in utterances[:2]]
        answer.update(schema_version="1.4", evidence_policy="speech_lookup_v1", focus_timestamp_ms=0,
            speech_lookup={"tool_step": 2, "strategy": "FOCUS_CONTEXT", "status": "RETURNED",
                "query_truncated": False, "anchor_distance_ms": 0,
                "candidate_utterance_count": 2, "returned_utterance_ids": ids})
        answer["provenance"]["prompt_version"] = SPEECH_PROMPT
        answer["tool_calls"] = [
            {"step": 1, "origin": "USER_FOCUS", "tool_name": "inspect_frames",
                "arguments": {"timestamps_ms": [0]}, "result_utterance_ids": [], "result_frame_ids": ["f0001"]},
            {"step": 2, "origin": "EVIDENCE_POLICY", "tool_name": "read_context",
                "arguments": {"utterance_id": ids[0], "before": 1, "after": 1},
                "result_utterance_ids": ids, "result_frame_ids": []},
            {"step": 3, "origin": "MODEL", "tool_name": "inspect_frames",
                "arguments": {"timestamps_ms": [0, 500]}, "result_utterance_ids": [],
                "result_frame_ids": ["f0001", "f0002"]},
        ]
        write(bundle["answer"], answer)
    receipt = {"schema_version": 1, "data_kind": "cached_agent_answer_replay",
        "turn_id": answer["turn_id"], "source_turn_id": str(uuid4()), "source_stage_id": str(uuid4()),
        "source_attempt": 1, "snapshot_hash": "a"*64, "source_request_id": "b"*64,
        "source_response_hash": "c"*64, "source_window_sha256": "d"*64,
        "decoder_profile": "speech-optional-hint-v1", "rebound_fields": ["turn_id"], "provider_requests": 0}
    path = write(bundle["ctx"].directory / "cached-answer-replay.json", receipt)
    manifest = read(bundle["manifest"])
    next(a for a in manifest["artifacts"] if a["kind"] == "agent_answer")["sha256"] = sha256(bundle["answer"])
    manifest["artifacts"].append({"kind": "agent_cached_answer_replay",
        "key": path.relative_to(bundle["root"]).as_posix(), "sha256": sha256(path)})
    write(bundle["manifest"], manifest)
    return path, receipt


def test_valid_one_four_replay_receipt_is_only_a_local_consistency_declaration(actual_bundle, tmp_path):
    receipt_path, receipt = add_replay_receipt(actual_bundle)
    report = evaluate_actual(actual_bundle, tmp_path / "replay-report")
    replay = report["binding"]["cached_answer_replay"]
    assert replay == {"receipt_sha256": sha256(receipt_path), "source_turn_id": receipt["source_turn_id"],
                      "verification": "declaration_consistency_only"}
    assert report["binding"]["model_response_origin"] == "not_verified"
    assert report["binding"]["verified_artifact_count"] == 4
    assert report["binding"]["verified_jpeg_count"] == 2
    assert report["prediction"]["schema_version"] == "1.4"
    assert len(actual_bundle["seen"]) == 2


@pytest.mark.parametrize("changed", ["wrong_turn", "same_turn", "missing_field", "provider_bool", "provider_one",
    "profile", "invalid_hash", "invalid_uuid", "artifact_hash", "other_attempt"])
def test_replay_receipt_rejects_invalid_declarations_bytes_and_paths(actual_bundle, tmp_path, changed):
    path, receipt = add_replay_receipt(actual_bundle)
    if changed == "wrong_turn":
        receipt["turn_id"] = str(uuid4())
    elif changed == "same_turn":
        receipt["source_turn_id"] = receipt["turn_id"]
    elif changed == "missing_field":
        del receipt["snapshot_hash"]
    elif changed == "provider_bool":
        receipt["provider_requests"] = True
    elif changed == "provider_one":
        receipt["provider_requests"] = 1
    elif changed == "profile":
        receipt["decoder_profile"] = "wrong-decoder"
    elif changed == "invalid_hash":
        receipt["source_response_hash"] = "not-a-sha256"
    elif changed == "invalid_uuid":
        receipt["source_stage_id"] = "not-a-stage-uuid"
    write(path, receipt)
    manifest = read(actual_bundle["manifest"])
    artifact = next(a for a in manifest["artifacts"] if a["kind"] == "agent_cached_answer_replay")
    if changed == "artifact_hash":
        _native_path(path).write_bytes(_native_path(path).read_bytes() + b"\r\n")
    else:
        artifact["sha256"] = sha256(path)
        if changed == "other_attempt":
            other = actual_bundle["ctx"].directory.parent.parent / "2" / uuid4().hex / "cached-answer-replay.json"
            _native_path(other).parent.mkdir(parents=True)
            shutil.copy2(_native_path(path), _native_path(other))
            artifact["key"] = other.relative_to(actual_bundle["root"]).as_posix()
    write(actual_bundle["manifest"], manifest)
    output = tmp_path / "rejected-replay"
    with pytest.raises(EvidenceEvaluationError) as raised:
        evaluate_actual(actual_bundle, output)
    assert str(raised.value) == raised.value.code and str(tmp_path) not in str(raised.value)
    assert not output.exists() and len(actual_bundle["seen"]) == 2


def test_valid_one_five_result_rejects_an_attached_replay_receipt(actual_bundle, tmp_path, real_source, monkeypatch):
    from test_agent_screen_source import published_context

    ctx, _ = published_context(_native_path(tmp_path / "sc"), real_source)
    seen = []
    manifest = mock_executor(monkeypatch, [tool("search_screen_text", query="UNSPOKEN_SCREEN_TOKEN", top_k=1),
        reply(block(images=["f0001"]))], seen)(ctx)
    manifest_path = write(ctx.directory / "manifest.json", manifest)
    answer_path = ctx.directory / "agent-answer.json"
    answer = read(answer_path)
    transcript = ctx.storage_root / ctx.lease.config["agent_turn"]["source_transcript_key"]
    reference = reference_for(answer, 20000, [frame("initial", 0, 1)])
    bundle = {"root": ctx.storage_root, "source": ctx.storage_root / ctx.lease.source_key,
        "answer": answer_path, "reference": write(tmp_path / "screen-reference.json", reference),
        "transcript": transcript, "manifest": manifest_path, "ctx": ctx, "seen": seen}
    baseline = evaluate_actual(bundle, tmp_path / "valid-one-five")
    assert baseline["prediction"]["schema_version"] == "1.5" and baseline["binding"]["cached_answer_replay"] is None
    add_replay_receipt(bundle, speech=False)
    output = tmp_path / "invalid-one-five-replay"
    with pytest.raises(EvidenceEvaluationError, match="^INVALID_AGENT_EVIDENCE_MANIFEST$"):
        evaluate_actual(bundle, output)
    assert not output.exists() and len(seen) == 2


def test_output_inside_shared_storage_is_refused_without_touching_frozen_inputs(actual_bundle):
    inputs = [actual_bundle["source"], actual_bundle["transcript"], actual_bundle["answer"],
              actual_bundle["manifest"], *actual_bundle["frames"]]
    before = {path: sha256(path) for path in inputs}
    output = actual_bundle["ctx"].directory / "nested-evaluation"
    with pytest.raises(EvidenceEvaluationError, match="^UNSAFE_AGENT_EVIDENCE_OUTPUT$"):
        evaluate_actual(actual_bundle, output)
    assert not _native_path(output).exists()
    assert all(sha256(path) == digest for path, digest in before.items())
    assert len(actual_bundle["seen"]) == 2
