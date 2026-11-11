"""Independent evidence-group arithmetic over synthetic, strictly valid wires."""
import copy
import json
import socket
from pathlib import Path

import pytest
from livecut.agent_evidence import assemble
from livecut.agent_evidence_evaluation import (
    EvidenceEvaluationError,
    evaluate_evidence_coverage,
    validate_agent_evidence_reference,
)

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


def document(name):
    return json.loads((FIXTURES / name).read_bytes())


def reference_for(result, duration, requirements):
    return {"schema_version": "1.0", "data_kind": "agent_evidence_reference", "reference_id": "ref-1",
            "source": {"video_id": result["video_id"], "source_sha256": result["source_hash"],
                       "transcript_sha256": result["transcript_hash"], "duration_ms": duration},
            "question": result["question"], "annotation_status": "annotated",
            "provenance": {"label_source": "agent_assisted", "split": "dev", "independently_reviewed": False},
            "requirements": requirements}


def asr(identity="speech", ids=("u1",)):
    return {"id": identity, "modality": "ASR", "acceptable_utterance_ids": list(ids)}


def frame(identity="picture", start=1000, end=1500):
    return {"id": identity, "modality": "FRAME", "start_ms": start, "end_ms": end}


def standard(focus=False):
    fixture = document("agent-evidence-focus.synthetic.json" if focus else "agent-evidence.synthetic.json")
    result, items, duration = fixture["result"], fixture["utterances"], fixture["duration_ms"]
    ref = reference_for(result, duration, [asr(), frame(), frame("after", 1500, 2000), frame("absent", 3000, 3500)])
    return result, items, ref, duration


def evaluate(packet):
    result, items, ref, duration = packet
    return evaluate_evidence_coverage(result, items, ref, duration_ms=duration)


def rerender(result, items):
    visible = {uid for call in result["tool_calls"] for uid in call["result_utterance_ids"]}
    result.update(assemble(result["answer_scope"], result["answer_blocks"], items, visible,
                           result["visual_evidence"], result["tool_calls"]))


def test_public_text_and_visual_examples_measure_constructed_coverage_without_quality_claim(monkeypatch):
    def forbidden(*_args, **_kwargs):
        pytest.fail("Offline evaluation attempted a network connection")
    monkeypatch.setattr(socket, "create_connection", forbidden)
    ref = document("agent-evidence-evaluation/reference.json")
    items = document("agent-evidence-evaluation/transcript.json")["utterances"]
    for name, obtained, fraction in [("text-answer.json", 1, .25), ("visual-answer.json", 3, .75)]:
        answer = document("agent-evidence-evaluation/" + name)
        old = copy.deepcopy((answer, items, ref))
        report = evaluate_evidence_coverage(answer, items, ref, duration_ms=4000)
        assert report["counts"]["required"] == 4
        assert report["counts"]["obtained"] == report["counts"]["cited"] == obtained
        assert report["coverage"]["obtained"] == report["coverage"]["cited"] == fraction
        assert report["semantic_quality"] is None
        serialized = json.dumps(report, ensure_ascii=False)
        assert ref["notes"] not in serialized and answer["answer"] not in serialized
        assert items[0]["text"] not in serialized and ref["question"] not in serialized
        assert (answer, items, ref) == old


def test_obtained_cited_direct_observation_and_inference_are_separate_overlapping_counts():
    packet = standard()
    result, items, ref, _ = packet
    # Keep a direct FRAME observation, but use the speech only as INFERENCE.
    result["answer_blocks"] = [result["answer_blocks"][1], result["answer_blocks"][3]]
    result["answer_blocks"][1]["frame_ids"] = ["f0001"]
    for number, block in enumerate(result["answer_blocks"], 1):
        block["block_id"] = f"b{number:02d}"
    rerender(result, items)
    report = evaluate(packet)
    assert report["counts"] == {"required": 4, "obtained": 3, "cited": 2,
                                 "observation_cited": 1, "inference_cited": 2, "legacy_untyped_cited": 0}
    assert report["per_requirement"][0]["matched_evidence"][0]["citation_roles"] == ["INFERENCE"]
    assert not report["per_requirement"][0]["observation_cited"]
    assert report["per_requirement"][2]["obtained"] and not report["per_requirement"][2]["cited"]


def test_asr_alternatives_are_or_and_same_evidence_can_cover_multiple_declared_groups():
    packet = standard()
    result, items, ref, _ = packet
    items.append({"utterance_id": "u2", "start_ms": 2000, "end_ms": 3000,
                  "text": "Synthetic unseen alternative", "source_chunk_id": "chunk0"})
    ref["requirements"] = [asr("one", ("u2", "u1")), asr("two", ("u1",)), asr("missing", ("u2",))]
    report = evaluate(packet)
    assert report["counts"]["required"] == 3 and report["counts"]["obtained"] == 2
    assert report["acquisition"]["utterance_count"] == 1
    assert [x["id"] for x in report["per_requirement"][0]["matched_evidence"]] == ["u1"]
    assert report["per_requirement"][2]["matched_evidence"] == []


def test_half_open_frame_window_uses_actual_fraction_not_requested_or_rounded_display():
    packet = standard()
    result, items, ref, _ = packet
    # 1499.6ms rounds to 1500ms but remains below the window endpoint.
    result["visual_evidence"][0].update(source_pts=14996, source_time_base="1/10000", timestamp_ms=1500)
    ref["requirements"] = [frame("actual-inside", 1499, 1500), frame("rounded-only", 1500, 1501),
                           frame("request-only", 1000, 1001), frame("exact-start", 1500, 2000)]
    report = evaluate(packet)
    assert [[v["id"] for v in group["matched_evidence"]] for group in report["per_requirement"]] == [
        ["f0001"], ["f0002"], [], ["f0002"]]
    first = report["per_requirement"][0]
    assert first["criterion"] == {"start_ms": 1499, "end_ms": 1500}
    assert {k: first["matched_evidence"][0][k] for k in ("source_pts", "source_time_base", "timestamp_ms")} == {
        "source_pts": 14996, "source_time_base": "1/10000", "timestamp_ms": 1500}


def test_user_focus_then_model_reuse_keeps_first_acquisition_user_owned():
    packet = standard(focus=True)
    result, _, _, _ = packet
    assert result["tool_calls"][0]["origin"] == "USER_FOCUS"
    assert any("f0001" in step["result_frame_ids"] for step in result["tool_calls"][1:])
    report = evaluate(packet)
    detail = report["per_requirement"][1]["matched_evidence"][0]
    assert detail["first_acquired_origin"] == "USER_FOCUS"
    assert detail["returned_origins"] == ["USER_FOCUS", "MODEL"]
    assert report["acquisition"]["first_by_origin"]["MODEL"]["FRAME"] == 1
    assert report["acquisition"]["returned_by_origin"]["MODEL"]["FRAME"] == 2


@pytest.mark.parametrize("schema_version", ["1.1", "1.2"])
def test_legacy_visual_citation_roles_are_untyped_not_speech_or_inference(schema_version):
    packet = document("agent-visual-focus.synthetic.json" if schema_version == "1.2" else "agent-visual.synthetic.json")
    result, items, duration = packet["result"], packet["utterances"], packet["duration_ms"]
    assert result["schema_version"] == schema_version
    ref = reference_for(result, duration, [asr(), frame()])
    report = evaluate_evidence_coverage(result, items, ref, duration_ms=duration)
    assert report["counts"]["observation_cited"] is report["coverage"]["inference_cited"] is None
    assert report["counts"]["legacy_untyped_cited"] == 2


def test_not_annotated_reference_keeps_denominator_and_all_coverage_unscored():
    packet = standard()
    _, _, ref, _ = packet
    ref.update(annotation_status="not_annotated", requirements=[],
               provenance={"label_source": "unlabeled", "split": "dev", "independently_reviewed": False})
    report = evaluate(packet)
    assert report["counts"]["required"] == 0 and report["per_requirement"] == []
    assert all(value is None for value in report["coverage"].values())
    assert report["acquisition"]["frame_count"] == 2


def test_plain_empty_return_has_unknown_cause_and_does_not_claim_video_absence():
    packet = standard()
    result, _, _, _ = packet
    result["tool_calls"].append({"step": 3, "tool_name": "inspect_frames", "arguments": {"timestamps_ms": [3500]},
                                 "result_utterance_ids": [], "result_frame_ids": []})
    report = evaluate(packet)
    diagnosis = report["tool_diagnostics"]
    assert diagnosis["empty_return_steps"] == 1 and diagnosis["explicit_tool_error_steps"] == 0
    assert diagnosis["steps"][-1]["known_status"] is None
    assert diagnosis["steps"][-1]["empty_return_cause"] == "UNKNOWN"


@pytest.mark.parametrize("index", range(6))
def test_one_four_policy_receipts_keep_program_origin_and_explicit_status(index):
    packet = document("agent-speech-lookup.synthetic.json")["cases"][index]
    answer, items, duration = packet["result"], packet["utterances"], packet["duration_ms"]
    ref = reference_for(answer, duration, [asr(ids=(items[0]["utterance_id"],))])
    report = evaluate_evidence_coverage(answer, items, ref, duration_ms=duration)
    policy = next(step for step in report["tool_diagnostics"]["steps"] if step["origin"] == "EVIDENCE_POLICY")
    assert policy["known_status"] == answer["speech_lookup"]["status"]
    assert policy["receipt_kind"] == "speech_lookup"


def test_one_five_valid_shared_vectors_preserve_only_known_screen_receipts():
    fixture = document("agent-screen-search.synthetic.json")
    checked = 0
    for case in fixture["cases"]:
        if not case["expected_valid"]:
            continue
        result, duration = case["result"], case["duration_ms"]
        ref = reference_for(result, duration, [frame(start=0, end=duration)])
        report = evaluate_evidence_coverage(result, fixture["utterances"], ref, duration_ms=duration)
        original = {r["tool_step"]: r for r in result["screen_searches"]}
        for step in report["tool_diagnostics"]["steps"]:
            if step["receipt_kind"] == "screen_search":
                assert step["known_status"] == original[step["step"]]["status"]
                assert step["known_error_code"] == original[step["step"]]["error_code"]
        checked += 1
    assert checked >= 10


@pytest.mark.parametrize("mutation", [
    lambda r: r.update(extra="private data"),
    lambda r: r["source"].update(duration_ms=True),
    lambda r: r["source"].update(source_sha256="a"*64+"\n"),
    lambda r: r.update(reference_id="id\n"),
    lambda r: r.update(question="\ufeff\u00a0"),
    lambda r: r.update(question="😀"*1001),
    lambda r: r.update(question="\ud800"),
    lambda r: r.update(requirements=[]),
    lambda r: r["requirements"].append(copy.deepcopy(r["requirements"][0])),
    lambda r: r["requirements"][0].update(acceptable_utterance_ids=["u1", "u1"]),
    lambda r: r["requirements"][0].update(acceptable_utterance_ids=[]),
    lambda r: r["requirements"][1].update(start_ms=1500),
    lambda r: r["requirements"][1].update(end_ms=4001),
    lambda r: r["requirements"][1].update(start_ms=1.0),
    lambda r: r["provenance"].update(label_source="agent_assisted", split="test"),
    lambda r: r["provenance"].update(label_source="agent_assisted", independently_reviewed=True),
    lambda r: r["provenance"].update(label_source="human", split="test", independently_reviewed=False),
    lambda r: r.update(annotation_status="not_annotated"),
    lambda r: r.update(requirements=[frame(str(i)) for i in range(201)]),
])
def test_invalid_reference_is_rejected_without_echoing_private_inputs(mutation):
    _, _, ref, _ = standard()
    mutation(ref)
    with pytest.raises(EvidenceEvaluationError) as raised:
        validate_agent_evidence_reference(ref)
    assert raised.value.code == "INVALID_AGENT_EVIDENCE_REFERENCE"
    assert str(raised.value) == raised.value.code


@pytest.mark.parametrize("key", ["video_id", "source_hash", "transcript_hash", "question"])
def test_cross_source_or_changed_question_is_rejected_before_computing_scores(key):
    packet = standard()
    packet[0][key] = "private-other-input"
    with pytest.raises(EvidenceEvaluationError, match="^AGENT_EVIDENCE_SOURCE_MISMATCH$"):
        evaluate(packet)


@pytest.mark.parametrize("mutation", [
    lambda p: p[2]["requirements"][0].update(acceptable_utterance_ids=["unseen-source-id"]),
    lambda p: p[2]["source"].update(duration_ms=5000),
])
def test_reference_time_or_asr_alternatives_must_belong_to_actual_source(mutation):
    packet = standard()
    mutation(packet)
    with pytest.raises(EvidenceEvaluationError, match="^AGENT_EVIDENCE_SOURCE_MISMATCH$"):
        evaluate(packet)


@pytest.mark.parametrize("mutation", [
    lambda p: p[0].update(answer="private forged answer"),
    lambda p: p[0]["tool_calls"][0].update(result_utterance_ids=[]),
    lambda p: p[0]["visual_evidence"][0].update(source_pts=True),
    lambda p: p[0].update(schema_version="9.9"),
])
def test_incomplete_or_forged_answer_does_not_yield_partial_metrics(mutation):
    packet = standard()
    mutation(packet)
    with pytest.raises(EvidenceEvaluationError, match="^INVALID_AGENT_EVIDENCE_ANSWER$"):
        evaluate(packet)


@pytest.mark.parametrize("value", [True, 0, -1, 4000.0, 2**53])
def test_duration_requires_positive_safe_integer(value):
    result, items, ref, _ = standard()
    with pytest.raises(EvidenceEvaluationError, match="^INVALID_AGENT_EVIDENCE_INPUT$"):
        evaluate_evidence_coverage(result, items, ref, duration_ms=value)


def test_duplicate_source_utterances_are_not_silently_overwritten():
    packet = standard()
    packet[1].append(copy.deepcopy(packet[1][0]))
    with pytest.raises(EvidenceEvaluationError, match="^INVALID_AGENT_EVIDENCE_INPUT$"):
        evaluate(packet)


def test_declared_independent_human_test_is_accepted_as_metadata_not_authenticated():
    _, _, ref, _ = standard()
    ref["provenance"] = {"label_source": "human", "split": "test", "independently_reviewed": True}
    before = copy.deepcopy(ref)
    assert validate_agent_evidence_reference(ref) is ref
    assert ref == before


def test_exact_two_hundred_groups_do_not_become_two_hundred_unique_utterances():
    packet = standard()
    packet[2]["requirements"] = [asr(f"g{i}") for i in range(200)]
    report = evaluate(packet)
    assert report["counts"]["required"] == report["counts"]["obtained"] == 200
    assert report["acquisition"]["utterance_count"] == 1


def test_two_hundred_asr_alternatives_are_bounded_but_only_one_must_be_observed():
    packet = standard()
    result, items, ref, _ = packet
    items.extend({"utterance_id": f"s{i}", "start_ms": 2000+i, "end_ms": 2001+i,
                  "text": "Synthetic unobserved alternative", "source_chunk_id": "chunk0"} for i in range(199))
    ref["requirements"] = [asr(ids=tuple(u["utterance_id"] for u in reversed(items)))]
    report = evaluate(packet)
    assert report["counts"]["obtained"] == report["counts"]["cited"] == 1
    ref["requirements"][0]["acceptable_utterance_ids"].append("over-limit")
    with pytest.raises(EvidenceEvaluationError, match="^INVALID_AGENT_EVIDENCE_REFERENCE$"):
        evaluate(packet)


@pytest.mark.parametrize("question", ["x"*2000, "😀"*1000, "\ufeff 原问题 \u00a0"])
def test_valid_exact_question_is_bound_without_trimming_or_rewriting(question):
    packet = standard()
    packet[0]["question"] = packet[2]["question"] = question
    before = copy.deepcopy(packet)
    assert evaluate(packet)["counts"]["cited"] == 3
    assert packet == before


def test_legacy_text_reference_to_visual_window_has_no_visual_tool_or_false_tool_failure():
    answer = document("agent-evidence-evaluation/text-answer.json")
    items = document("agent-evidence-evaluation/transcript.json")["utterances"]
    ref = document("agent-evidence-evaluation/reference.json")
    report = evaluate_evidence_coverage(answer, items, ref, duration_ms=4000)
    assert report["tool_diagnostics"]["modality_attempted"] == {"ASR": True, "FRAME": False}
    assert report["counts"]["legacy_untyped_cited"] == 1
    assert report["counts"]["observation_cited"] is report["counts"]["inference_cited"] is None
    assert report["tool_diagnostics"]["explicit_tool_error_steps"] == 0


def test_safe_integer_maximum_duration_uses_exact_frame_comparison_without_time_bins():
    result, items, ref, _ = standard()
    duration = 2**53-1
    ref["source"]["duration_ms"] = duration
    ref["requirements"] = [frame("tail", duration-1, duration)]
    report = evaluate_evidence_coverage(result, items, ref, duration_ms=duration)
    assert report["counts"]["obtained"] == 0 and report["coverage"]["cited"] == 0


def test_observation_and_general_blocks_are_counted_without_leaking_their_content():
    result, items, ref, duration = standard()
    result["answer_scope"] = "GENERAL"
    result["answer_blocks"] = [{"block_id": "b01", "kind": "GENERAL", "text": "Private generic answer",
                                "utterance_ids": [], "frame_ids": []}]
    rerender(result, items)
    report = evaluate_evidence_coverage(result, items, ref, duration_ms=duration)
    assert report["counts"]["obtained"] == 3 and report["counts"]["cited"] == 0
    assert "Private generic answer" not in json.dumps(report)


def test_output_criteria_and_source_metadata_are_safe_independent_values():
    packet = standard()
    before = copy.deepcopy(packet)
    report = evaluate(packet)
    group = report["per_requirement"][0]
    assert group["criterion"] == {"acceptable_utterance_ids": ["u1"]}
    assert group["matched_evidence"][0]["start_ms"] == 0
    assert group["matched_evidence"][0]["end_ms"] == 1500
    group["criterion"]["acceptable_utterance_ids"].append("output-only")
    group["matched_evidence"][0]["returned_origins"].append("output-only")
    assert packet == before
