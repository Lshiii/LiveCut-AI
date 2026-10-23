"""Synthetic contract checks; these are not recognition-quality measurements."""

from __future__ import annotations

import copy
import json
import math
import tempfile
import unittest
from pathlib import Path

from livecut.contracts import ContractValidationError, validate_analysis_result

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "contracts" / "analysis-result.schema.json"
EXAMPLE = ROOT / "contracts" / "analysis-result.example.json"


class AnalysisResultContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.result = json.loads(EXAMPLE.read_text(encoding="utf-8-sig"))

    def assert_invalid(self, fragment: str) -> ContractValidationError:
        with self.assertRaises(ContractValidationError) as caught:
            validate_analysis_result(self.result)
        self.assertIsInstance(caught.exception.errors, list)
        self.assertTrue(caught.exception.errors)
        self.assertIn(fragment, str(caught.exception))
        return caught.exception

    def first(self) -> dict:
        return self.result["segments"][0]

    def disable_vision(self, segment: dict) -> None:
        segment["visual_status"] = "not_checked"
        segment["visual_observations"] = []

    def test_synthetic_fixture_validates_without_mutation_or_promotion(self) -> None:
        original = copy.deepcopy(self.result)
        self.assertIsNone(validate_analysis_result(self.result))
        self.assertEqual(self.result, original)
        self.assertEqual(self.result["data_kind"], "synthetic_fixture")
        self.assertEqual(self.result["review_status"], "DRAFT")

    def test_explicit_schema_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            explicit = Path(directory) / "schema.json"
            explicit.write_text(SCHEMA.read_text(encoding="utf-8-sig"), encoding="utf-8")
            self.assertIsNone(validate_analysis_result(self.result, schema_path=explicit))

    def test_missing_explicit_schema_has_readable_error(self) -> None:
        with self.assertRaisesRegex(ContractValidationError, "cannot load schema"):
            validate_analysis_result(self.result, SCHEMA.parent / "missing.schema.json")

    def test_wrong_shape_fails_schema_before_business_access(self) -> None:
        for data in ({}, [], None, {"segments": [True]}):
            with self.subTest(data=data):
                with self.assertRaises(ContractValidationError):
                    validate_analysis_result(data)

    def test_undeclared_fields_are_rejected(self) -> None:
        self.result["catalog"] = []
        self.assert_invalid("Additional properties")

    def test_all_milliseconds_reject_float_and_bool(self) -> None:
        original = copy.deepcopy(self.result)
        paths = [
            ("duration_ms",),
            ("utterances", 0, "start_ms"),
            ("utterances", 0, "end_ms"),
            ("frames", 0, "timestamp_ms"),
            ("segments", 0, "start_ms"),
            ("segments", 0, "end_ms"),
        ]
        for path in paths:
            for value in (1000.0, True, False):
                with self.subTest(path=path, value=value):
                    self.result = copy.deepcopy(original)
                    item = self.result
                    for part in path[:-1]:
                        item = item[part]
                    item[path[-1]] = value
                    self.assert_invalid("integer")

    def test_nonfinite_risk_score_rejected(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                self.first()["risk_score"] = value
                self.assert_invalid("number")

    def test_invalid_intervals_and_duration_bounds(self) -> None:
        original = copy.deepcopy(self.result)
        for collection in ("utterances", "segments"):
            for kind in ("zero_length", "reversed", "past_duration"):
                with self.subTest(collection=collection, kind=kind):
                    self.result = copy.deepcopy(original)
                    item = self.result[collection][0]
                    if kind == "zero_length":
                        item["end_ms"] = item["start_ms"]
                    elif kind == "reversed":
                        item["start_ms"] = item["end_ms"] + 1
                    else:
                        item["end_ms"] = self.result["duration_ms"] + 1
                    self.assert_invalid("start_ms < end_ms <= duration_ms")

    def test_frame_at_duration_is_outside_half_open_video(self) -> None:
        self.result["frames"][0]["timestamp_ms"] = self.result["duration_ms"]
        self.assert_invalid("timestamp_ms < duration_ms")

    def test_negative_timestamp_is_rejected(self) -> None:
        self.result["frames"][0]["timestamp_ms"] = -1
        self.assert_invalid("minimum")

    def test_ids_are_unique_within_each_namespace(self) -> None:
        original = copy.deepcopy(self.result)
        for collection, field in (
            ("products", "product_id"),
            ("utterances", "utterance_id"),
            ("frames", "frame_id"),
            ("segments", "segment_id"),
        ):
            with self.subTest(collection=collection):
                self.result = copy.deepcopy(original)
                self.result[collection].append(copy.deepcopy(self.result[collection][0]))
                self.assert_invalid(f"duplicate {field}")

    def test_missing_start_and_end_anchor_references(self) -> None:
        for field in ("start_utterance_id", "end_utterance_id"):
            with self.subTest(field=field):
                original = self.first()[field]
                self.first()[field] = "missing_utterance"
                self.assert_invalid(f"{field}: unknown utterance_id")
                self.first()[field] = original

    def test_missing_audio_evidence_reference(self) -> None:
        self.first()["audio_evidence_utterance_ids"] = ["missing_utterance"]
        self.assert_invalid("unknown utterance_id")

    def test_missing_visual_frame_reference(self) -> None:
        self.first()["visual_observations"][0]["frame_id"] = "missing_frame"
        self.assert_invalid("unknown frame_id")

    def test_unknown_product_references_are_rejected_everywhere(self) -> None:
        original = copy.deepcopy(self.result)
        for kind in ("selected", "identity_candidate", "visual_candidate"):
            with self.subTest(kind=kind):
                self.result = copy.deepcopy(original)
                segment = self.first()
                if kind == "selected":
                    segment["identity"]["product_id"] = "missing_product"
                elif kind == "identity_candidate":
                    segment["identity"]["candidate_product_ids"].append("missing_product")
                else:
                    segment["visual_observations"][0]["candidate_product_ids"] = ["missing_product"]
                self.assert_invalid("unknown product_id")

    def test_identified_product_must_be_an_identity_candidate(self) -> None:
        self.first()["identity"]["candidate_product_ids"] = [self.result["products"][1]["product_id"]]
        self.assert_invalid("identified product_id must be in candidate_product_ids")

    def test_nonidentified_status_cannot_have_selected_product(self) -> None:
        self.first()["identity"]["status"] = "ambiguous"
        self.assert_invalid("is not of type 'null'")

    def test_ambiguous_and_unknown_require_review_and_corresponding_reason(self) -> None:
        original = copy.deepcopy(self.result)
        for status, reason in (("ambiguous", "IDENTITY_AMBIGUOUS"), ("unknown", "UNKNOWN_PRODUCT")):
            for missing in ("flag", "reason"):
                with self.subTest(status=status, missing=missing):
                    self.result = copy.deepcopy(original)
                    segment = self.first()
                    segment["identity"] = {"status": status, "product_id": None, "candidate_product_ids": []}
                    self.disable_vision(segment)
                    segment["review_required"] = missing != "flag"
                    segment["review_reasons"] = [reason] if missing == "flag" else ["BOUNDARY_UNCERTAIN"]
                    self.assert_invalid("review_required" if missing == "flag" else f"missing required reason {reason}")

    def test_mixed_and_uncertain_require_review_reason(self) -> None:
        for content, reason in (("mixed", "MIXED_PRODUCTS"), ("uncertain", "BOUNDARY_UNCERTAIN")):
            with self.subTest(content=content):
                self.first()["content_kind"] = content
                self.assert_invalid(f"missing required reason {reason}")

    def test_nonproduct_requires_not_applicable_identity(self) -> None:
        self.first()["content_kind"] = "non_product"
        self.assert_invalid("non_product requires not_applicable identity")

    def test_nonproduct_with_no_product_identity_is_legal(self) -> None:
        segment = self.first()
        segment["content_kind"] = "non_product"
        segment["identity"] = {"status": "not_applicable", "product_id": None, "candidate_product_ids": []}
        self.disable_vision(segment)
        validate_analysis_result(self.result)

    def test_product_talk_cannot_claim_not_applicable(self) -> None:
        self.first()["identity"] = {"status": "not_applicable", "product_id": None, "candidate_product_ids": []}
        self.assert_invalid("product_talk requires a product identity status")

    def test_empty_products_and_unknown_talk_are_legal(self) -> None:
        self.result["products"] = []
        segment = self.first()
        self.result["segments"] = [segment]
        segment["identity"] = {"status": "unknown", "product_id": None, "candidate_product_ids": []}
        segment["review_required"] = True
        segment["review_reasons"] = ["UNKNOWN_PRODUCT"]
        self.disable_vision(segment)
        validate_analysis_result(self.result)

    def test_no_detected_product_segments_is_legal(self) -> None:
        self.result["products"] = []
        self.result["segments"] = []
        validate_analysis_result(self.result)

    def test_automatic_boundaries_must_equal_asr_anchors(self) -> None:
        for field in ("start_ms", "end_ms"):
            with self.subTest(field=field):
                self.first()[field] += 1
                self.assert_invalid("must equal")
                self.first()[field] -= 1

    def test_reversed_anchors_are_rejected(self) -> None:
        segment = self.first()
        segment["start_utterance_id"], segment["end_utterance_id"] = segment["end_utterance_id"], segment["start_utterance_id"]
        self.assert_invalid("start_utterance_id must not follow end_utterance_id")

    def test_anchor_must_fit_even_when_not_repeated_in_evidence(self) -> None:
        segment = self.first()
        anchor = next(
            utterance for utterance in self.result["utterances"]
            if utterance["utterance_id"] == segment["start_utterance_id"]
        )
        anchor["end_ms"] = segment["end_ms"] + 1
        segment["audio_evidence_utterance_ids"] = [segment["end_utterance_id"]]
        self.assert_invalid("anchor utterance must lie inside segment interval")

    def test_audio_evidence_must_be_ordered(self) -> None:
        self.first()["audio_evidence_utterance_ids"].reverse()
        self.assert_invalid("audio evidence must follow transcript order")

    def test_audio_evidence_must_be_inside_segment(self) -> None:
        self.first()["audio_evidence_utterance_ids"].append(self.result["utterances"][-1]["utterance_id"])
        self.assert_invalid("audio evidence must lie inside segment interval")

    def test_transcript_must_be_ordered(self) -> None:
        self.result["utterances"].reverse()
        self.assert_invalid("utterances must be in chronological order")

    def test_segments_must_be_ordered(self) -> None:
        self.result["segments"].reverse()
        self.assert_invalid("segments must be in chronological order")

    def test_supported_and_conflict_cannot_use_blurred_or_invisible_evidence(self) -> None:
        original = copy.deepcopy(self.result)
        for status in ("supported", "conflict"):
            for visibility in ("blurred", "not_visible"):
                with self.subTest(status=status, visibility=visibility):
                    self.result = copy.deepcopy(original)
                    segment = self.first()
                    segment["visual_status"] = status
                    segment["visual_observations"][0]["visibility"] = visibility
                    if status == "conflict":
                        segment["review_required"] = True
                        segment["review_reasons"] = ["AUDIO_VISUAL_CONFLICT"]
                    self.assert_invalid(f"{status} requires clear visual evidence")

    def test_wrong_product_cannot_be_reported_as_visual_support(self) -> None:
        self.first()["visual_observations"][0]["candidate_product_ids"] = [self.result["products"][1]["product_id"]]
        self.assert_invalid("supported requires clear candidate support")

    def test_one_supporting_frame_does_not_hide_another_clear_conflict(self) -> None:
        conflict = copy.deepcopy(self.first()["visual_observations"][0])
        conflict["frame_id"] = self.result["frames"][-1]["frame_id"]
        conflict["candidate_product_ids"] = [self.result["products"][1]["product_id"]]
        self.first()["visual_observations"].append(conflict)
        self.assert_invalid("no clear identity conflict")

    def test_visual_conflict_requires_different_clear_product_and_review(self) -> None:
        segment = self.first()
        segment["visual_status"] = "conflict"
        segment["visual_observations"][0]["candidate_product_ids"] = [self.result["products"][1]["product_id"]]
        segment["review_required"] = True
        segment["review_reasons"] = ["AUDIO_VISUAL_CONFLICT"]
        validate_analysis_result(self.result)
        segment["review_required"] = False
        self.assert_invalid("review_required")

    def test_agreeing_frame_cannot_be_labeled_conflict(self) -> None:
        segment = self.first()
        segment["visual_status"] = "conflict"
        segment["review_required"] = True
        segment["review_reasons"] = ["AUDIO_VISUAL_CONFLICT"]
        self.assert_invalid("conflict requires a clear candidate inconsistent")

    def test_not_checked_cannot_have_observations(self) -> None:
        self.first()["visual_status"] = "not_checked"
        self.assert_invalid("visual_observations")

    def test_duplicate_observation_does_not_count_as_more_evidence(self) -> None:
        self.first()["visual_observations"].append(copy.deepcopy(self.first()["visual_observations"][0]))
        self.assert_invalid("duplicate observation")

    def test_local_boundary_frames_are_not_rejected_as_outside_segment(self) -> None:
        frame_id = self.first()["visual_observations"][0]["frame_id"]
        frame = next(frame for frame in self.result["frames"] if frame["frame_id"] == frame_id)
        frame["timestamp_ms"] = max(0, self.first()["start_ms"] - 500)
        validate_analysis_result(self.result)

    def test_returning_product_stays_two_independent_intervals(self) -> None:
        first, returning = self.first(), self.result["segments"][-1]
        self.assertEqual(first["identity"]["product_id"], returning["identity"]["product_id"])
        self.assertLess(first["end_ms"], returning["start_ms"])
        validate_analysis_result(self.result)

    def test_identified_product_intervals_cannot_overlap(self) -> None:
        duplicate = copy.deepcopy(self.first())
        duplicate["segment_id"] = "overlapping_identified"
        self.result["segments"].insert(1, duplicate)
        self.assert_invalid("illegal overlap")

    def test_half_open_intervals_may_touch(self) -> None:
        first = self.first()
        second = copy.deepcopy(first)
        second["segment_id"] = "touching_interval"
        start_anchor = next(
            utterance for utterance in self.result["utterances"]
            if utterance["utterance_id"] == first["start_utterance_id"]
        )
        end_anchor = next(
            utterance for utterance in self.result["utterances"]
            if utterance["utterance_id"] == first["end_utterance_id"]
        )
        self.assertEqual(start_anchor["end_ms"], end_anchor["start_ms"])
        first["end_utterance_id"] = first["start_utterance_id"]
        first["end_ms"] = start_anchor["end_ms"]
        first["audio_evidence_utterance_ids"] = [first["start_utterance_id"]]
        second["start_utterance_id"] = second["end_utterance_id"]
        second["start_ms"] = end_anchor["start_ms"]
        second["audio_evidence_utterance_ids"] = [second["end_utterance_id"]]
        self.disable_vision(first)
        self.disable_vision(second)
        self.result["segments"].insert(1, second)
        validate_analysis_result(self.result)

    def test_unresolved_review_candidates_can_overlap(self) -> None:
        segment = self.first()
        segment["identity"] = {
            "status": "ambiguous",
            "product_id": None,
            "candidate_product_ids": [product["product_id"] for product in self.result["products"]],
        }
        segment["review_required"] = True
        segment["review_reasons"] = ["IDENTITY_AMBIGUOUS"]
        duplicate = copy.deepcopy(segment)
        duplicate["segment_id"] = "overlapping_unresolved"
        self.result["segments"].insert(1, duplicate)
        validate_analysis_result(self.result)


if __name__ == "__main__":
    unittest.main()
