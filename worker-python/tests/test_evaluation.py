"""Hand calculations and an independent exhaustive oracle; no provider or media execution."""

import copy
import random
from fractions import Fraction

import pytest
from livecut.evaluation import EvaluationError, evaluate_localization


def interval(identity, start, end):
    return {"id": identity, "start_ms": start, "end_ms": end}


def coverage(*ranges):
    return [{"start_ms": start, "end_ms": end} for start, end in ranges]


def evaluate(predictions, references, *, ranges=None, duration=100, thresholds=("0.5",)):
    return evaluate_localization(predictions, references,
                                 coverage=coverage((0, duration)) if ranges is None else ranges,
                                 duration_ms=duration, thresholds=thresholds)


def fraction(value):
    assert isinstance(value, str)
    return Fraction(value)


def masked_parts(prediction, ranges):
    return [(max(prediction["start_ms"], item["start_ms"]),
             min(prediction["end_ms"], item["end_ms"]))
            for item in ranges
            if max(prediction["start_ms"], item["start_ms"])
            < min(prediction["end_ms"], item["end_ms"])]


def overlap(prediction, reference, ranges):
    parts = masked_parts(prediction, ranges)
    size = sum(end - start for start, end in parts)
    intersection = sum(max(0, min(end, reference["end_ms"])
                           - max(start, reference["start_ms"])) for start, end in parts)
    union = size + reference["end_ms"] - reference["start_ms"] - intersection
    return Fraction(intersection, union), intersection, union


def exhaustive_optimum(predictions, references, ranges, threshold):
    """Enumerate all partial injections. No production matching helper is used."""
    included = [item for item in predictions if masked_parts(item, ranges)]
    matrix = [[overlap(prediction, reference, ranges)[0] for reference in references]
              for prediction in included]
    best = (0, Fraction(0))

    def visit(index, used, count, total):
        nonlocal best
        if index == len(included):
            best = max(best, (count, total))
            return
        visit(index + 1, used, count, total)
        for candidate, iou in enumerate(matrix[index]):
            if candidate not in used and iou > 0 and iou >= threshold:
                visit(index + 1, used | {candidate}, count + 1, total + iou)

    visit(0, set(), 0, Fraction(0))
    return best


def assert_certified(row, predictions, references, ranges):
    prediction_map = {item["id"]: item for item in predictions}
    reference_map = {item["id"]: item for item in references}
    included = {item["id"] for item in predictions if masked_parts(item, ranges)}
    threshold = fraction(row["iou_threshold_fraction"])
    prediction_ids, reference_ids = set(), set()
    total = Fraction(0)
    for match in row["matches"]:
        prediction_id, reference_id = match["prediction_id"], match["reference_id"]
        assert prediction_id not in prediction_ids and reference_id not in reference_ids
        assert prediction_id in included and reference_id in reference_map
        prediction_ids.add(prediction_id)
        reference_ids.add(reference_id)
        expected, intersection, union = overlap(prediction_map[prediction_id],
                                                reference_map[reference_id], ranges)
        assert expected >= threshold and expected > 0
        assert fraction(match["iou_fraction"]) == expected
        assert match["iou"] == pytest.approx(float(expected))
        assert match["intersection_ms"] == intersection and match["union_ms"] == union
        total += expected
    assert row["tp"] == len(prediction_ids) == len(reference_ids) == len(row["matches"])
    assert row["fp"] == len(included) - row["tp"]
    assert row["fn"] == len(references) - row["tp"]
    assert row["false_positive_ids"] == sorted(included - prediction_ids)
    assert row["false_negative_ids"] == sorted(reference_map.keys() - reference_ids)
    assert fraction(row["total_iou_fraction"]) == total
    assert row["total_iou"] == pytest.approx(float(total))


def assert_empty_boundary(row):
    assert row["boundary"] == {
        "evaluated_match_count": 0, "censored_match_count": 0,
        "start_error_ms": {"median": None, "mae": None, "p90": None},
        "end_error_ms": {"median": None, "mae": None, "p90": None},
    }


def test_exact_match_uses_the_public_report_schema_and_full_coverage():
    predictions, references = [interval("prediction", 10, 30)], [interval("reference", 10, 30)]
    report = evaluate(predictions, references)
    assert isinstance(report["matching_method"], str) and report["matching_method"]
    assert isinstance(report["iou_domain"], str) and report["iou_domain"]
    assert report["coverage"] == {"duration_ms": 100, "annotated_ms": 100,
                                   "annotated_fraction": 1, "ranges": coverage((0, 100))}
    assert report["counts"] == {"prediction_total": 1, "prediction_included": 1,
                                 "prediction_excluded": 0, "prediction_partially_clipped": 0,
                                 "reference_total": 1}
    row = report["thresholds"][0]
    assert (row["tp"], row["fp"], row["fn"]) == (1, 0, 0)
    assert (row["precision"], row["recall"], row["f1"]) == (1, 1, 1)
    assert_certified(row, predictions, references, coverage((0, 100)))
    assert row["boundary"] == {"evaluated_match_count": 1, "censored_match_count": 0,
                                "start_error_ms": {"median": 0, "mae": 0, "p90": 0},
                                "end_error_ms": {"median": 0, "mae": 0, "p90": 0}}


def test_maximum_cardinality_takes_priority_over_the_single_largest_iou():
    predictions = [interval("wide", 0, 14), interval("prefix", 0, 6)]
    references = [interval("first", 0, 10), interval("second", 10, 20)]
    ranges = coverage((0, 20))
    # Greedy picks wide/first=5/7 and strands prefix. Two matches instead
    # require prefix/first=3/5 and wide/second=1/5, with total 4/5.
    row = evaluate(predictions, references, duration=20, thresholds=("1/5",))["thresholds"][0]
    assert (row["tp"], row["fp"], row["fn"]) == (2, 0, 0)
    assert fraction(row["total_iou_fraction"]) == Fraction(4, 5)
    assert_certified(row, predictions, references, ranges)


def test_equal_cardinality_uses_the_global_largest_exact_total_instead_of_greedy_edges():
    predictions = [interval("wide", 0, 16), interval("middle", 4, 11)]
    references = [interval("first", 0, 10), interval("second", 10, 20)]
    # Greedy: 5/8 + 1/16 = 11/16. Optimal: 3/10 + 6/11 = 93/110.
    row = evaluate(predictions, references, duration=20, thresholds=("1/20",))["thresholds"][0]
    assert row["tp"] == 2
    assert fraction(row["total_iou_fraction"]) == Fraction(93, 110)
    assert_certified(row, predictions, references, coverage((0, 20)))


def test_overlapping_references_are_legal_and_still_need_one_to_one_global_matching():
    predictions = [interval("almost-full", 0, 9), interval("tail", 5, 10)]
    references = [interval("full", 0, 10), interval("prefix", 0, 6)]
    row = evaluate(predictions, references, duration=10, thresholds=("0.1",))["thresholds"][0]
    assert row["tp"] == 2
    assert fraction(row["total_iou_fraction"]) == Fraction(7, 6)
    assert_certified(row, predictions, references, coverage((0, 10)))


def test_duplicate_candidates_cannot_manufacture_multiple_true_positives():
    predictions = [interval("copy-b", 0, 10), interval("copy-a", 0, 10), interval("copy-c", 0, 10)]
    references = [interval("truth", 0, 10)]
    row = evaluate(predictions, references, duration=10)["thresholds"][0]
    assert (row["tp"], row["fp"], row["fn"]) == (1, 2, 0)
    assert row["precision"] == pytest.approx(1 / 3)
    assert row["recall"] == 1 and row["f1"] == pytest.approx(0.5)
    assert_certified(row, predictions, references, coverage((0, 10)))


def test_threshold_comparison_is_inclusive_and_does_not_round_exact_fractions_to_floats():
    thresholds = ("0.333333333333333333333333333333", "1/3",
                  "0.333333333333333333333333333334")
    report = evaluate([interval("p", 0, 1)], [interval("r", 0, 3)],
                      duration=3, thresholds=thresholds)
    by_threshold = {fraction(row["iou_threshold_fraction"]): row for row in report["thresholds"]}
    assert set(by_threshold) == {Fraction(value) for value in thresholds}
    assert [by_threshold[Fraction(value)]["tp"] for value in thresholds] == [1, 1, 0]
    assert fraction(by_threshold[Fraction(1, 3)]["matches"][0]["iou_fraction"]) == Fraction(1, 3)


def test_supported_threshold_types_and_default_thresholds_keep_exact_rational_values():
    prediction, reference = [interval("same", 0, 10)], [interval("same", 0, 10)]
    typed = evaluate(prediction, reference, duration=10, thresholds=[1, Fraction(3, 4), 0.5, "0.25"])
    assert {fraction(row["iou_threshold_fraction"]) for row in typed["thresholds"]} == {
        Fraction(1), Fraction(3, 4), Fraction(1, 2), Fraction(1, 4)}
    default = evaluate_localization(prediction, reference, coverage=coverage((0, 10)), duration_ms=10)
    assert {fraction(row["iou_threshold_fraction"]) for row in default["thresholds"]} == {
        Fraction(3, 10), Fraction(1, 2), Fraction(7, 10)}


def test_one_masked_prediction_cannot_be_split_into_two_matching_slots():
    predictions = [interval("one-original-prediction", 0, 30)]
    references = [interval("first", 0, 10), interval("second", 20, 30)]
    ranges = coverage((0, 10), (20, 30))
    report = evaluate(predictions, references, ranges=ranges, duration=30)
    assert report["coverage"]["annotated_ms"] == 20
    assert report["coverage"]["annotated_fraction"] == pytest.approx(2 / 3)
    assert report["counts"] == {"prediction_total": 1, "prediction_included": 1,
                                 "prediction_excluded": 0, "prediction_partially_clipped": 1,
                                 "reference_total": 2}
    row = report["thresholds"][0]
    assert (row["tp"], row["fp"], row["fn"]) == (1, 0, 1)
    match = row["matches"][0]
    assert fraction(match["iou_fraction"]) == Fraction(1, 2)
    assert match["intersection_ms"] == 10 and match["union_ms"] == 20
    assert match["boundary_censored"] is True
    assert match["start_error_ms"] is None and match["end_error_ms"] is None
    assert row["boundary"] == {"evaluated_match_count": 0, "censored_match_count": 1,
                                "start_error_ms": {"median": None, "mae": None, "p90": None},
                                "end_error_ms": {"median": None, "mae": None, "p90": None}}
    assert_certified(row, predictions, references, ranges)


def test_iou_union_includes_all_annotated_parts_of_the_original_prediction():
    predictions, references = [interval("cross-gap", 2, 28)], [interval("first", 0, 10)]
    ranges = coverage((0, 10), (20, 30))
    report = evaluate(predictions, references, ranges=ranges, duration=30, thresholds=("4/9", "0.45"))
    rows = {fraction(row["iou_threshold_fraction"]): row for row in report["thresholds"]}
    matched = rows[Fraction(4, 9)]
    assert matched["tp"] == 1
    assert matched["matches"][0]["intersection_ms"] == 8
    assert matched["matches"][0]["union_ms"] == 18
    assert fraction(matched["total_iou_fraction"]) == Fraction(4, 9)
    assert rows[Fraction(45, 100)]["tp"] == 0


def test_clipping_a_boundary_to_the_reference_does_not_create_zero_boundary_error():
    row = evaluate([interval("clipped", 0, 15)], [interval("truth", 0, 10)],
                   ranges=coverage((0, 10), (20, 30)), duration=30)["thresholds"][0]
    assert row["tp"] == 1 and fraction(row["matches"][0]["iou_fraction"]) == 1
    assert row["matches"][0]["boundary_censored"] is True
    assert row["matches"][0]["start_error_ms"] is None
    assert row["matches"][0]["end_error_ms"] is None
    assert row["boundary"]["evaluated_match_count"] == 0
    assert row["boundary"]["end_error_ms"]["mae"] is None


def test_predictions_outside_coverage_are_excluded_from_false_positives_and_denominators():
    predictions = [interval("left-outside", 0, 10), interval("right-outside", 20, 30),
                   interval("inside", 10, 20), interval("partial", 5, 15)]
    references, ranges = [interval("truth", 10, 20)], coverage((10, 20))
    report = evaluate(predictions, references, ranges=ranges, duration=30)
    assert report["counts"] == {"prediction_total": 4, "prediction_included": 2,
                                 "prediction_excluded": 2, "prediction_partially_clipped": 1,
                                 "reference_total": 1}
    row = report["thresholds"][0]
    assert (row["tp"], row["fp"], row["fn"]) == (1, 1, 0)
    assert row["false_positive_ids"] == ["partial"]
    assert row["precision"] == 0.5 and row["recall"] == 1
    assert row["f1"] == pytest.approx(2 / 3)
    assert_certified(row, predictions, references, ranges)


def test_only_unannotated_predictions_leave_metrics_na_instead_of_creating_false_positives():
    report = evaluate([interval("outside", 0, 5)], [], ranges=coverage((10, 20)), duration=20)
    assert report["counts"] == {"prediction_total": 1, "prediction_included": 0,
                                 "prediction_excluded": 1, "prediction_partially_clipped": 0,
                                 "reference_total": 0}
    row = report["thresholds"][0]
    assert (row["tp"], row["fp"], row["fn"]) == (0, 0, 0)
    assert row["precision"] is None and row["recall"] is None and row["f1"] is None
    assert row["false_positive_ids"] == []
    assert_empty_boundary(row)


@pytest.mark.parametrize("predictions,references,precision,recall,f1", [
    ([], [], None, None, None),
    ([interval("p", 0, 10)], [], 0, None, 0),
    ([], [interval("r", 0, 10)], None, 0, 0),
])
def test_zero_denominators_are_na_but_nonzero_zero_scores_stay_zero(
    predictions, references, precision, recall, f1,
):
    row = evaluate(predictions, references, duration=10)["thresholds"][0]
    assert row["precision"] == precision and row["recall"] == recall and row["f1"] == f1
    assert fraction(row["total_iou_fraction"]) == 0
    assert_empty_boundary(row)


def test_touching_endpoints_do_not_intersect_or_match_at_a_positive_threshold():
    row = evaluate([interval("p", 10, 20)], [interval("r", 0, 10)],
                   duration=20, thresholds=("0.01",))["thresholds"][0]
    assert (row["tp"], row["fp"], row["fn"]) == (0, 1, 1)
    assert row["matches"] == []
    assert_empty_boundary(row)


def test_signed_match_errors_and_absolute_boundary_aggregates_have_different_meanings():
    predictions = [interval("p1", 95, 205), interval("p2", 302, 397), interval("p3", 508, 590)]
    references = [interval("r1", 100, 200), interval("r2", 300, 400), interval("r3", 500, 600)]
    row = evaluate(predictions, references, duration=1000)["thresholds"][0]
    errors = {match["prediction_id"]: (match["start_error_ms"], match["end_error_ms"])
              for match in row["matches"]}
    assert errors == {"p1": (-5, 5), "p2": (2, -3), "p3": (8, -10)}
    assert all(match["boundary_censored"] is False for match in row["matches"])
    assert row["boundary"] == {"evaluated_match_count": 3, "censored_match_count": 0,
                                "start_error_ms": {"median": 5, "mae": 5, "p90": 8},
                                "end_error_ms": {"median": 5, "mae": 6, "p90": 10}}


def test_even_median_and_nearest_rank_p90_are_computed_on_absolute_errors():
    references = [interval(f"r-{index}", index * 1000 + 100, index * 1000 + 200)
                  for index in range(10)]
    predictions = [interval(f"p-{index}", value["start_ms"] + index + 1,
                            value["end_ms"] + index + 1) for index, value in enumerate(references)]
    row = evaluate(predictions, references, duration=10000)["thresholds"][0]
    assert row["tp"] == 10
    assert row["boundary"]["start_error_ms"] == {"median": 5.5, "mae": 5.5, "p90": 9}
    assert row["boundary"]["end_error_ms"] == {"median": 5.5, "mae": 5.5, "p90": 9}


def test_censored_matches_do_not_contribute_boundary_errors_to_valid_matches():
    predictions = [interval("measurable", 15, 85), interval("censored", 180, 280)]
    references = [interval("first", 20, 80), interval("second", 220, 280)]
    row = evaluate(predictions, references, duration=300,
                   ranges=coverage((0, 100), (200, 300)))["thresholds"][0]
    assert row["tp"] == 2
    assert row["boundary"] == {"evaluated_match_count": 1, "censored_match_count": 1,
                                "start_error_ms": {"median": 5, "mae": 5, "p90": 5},
                                "end_error_ms": {"median": 5, "mae": 5, "p90": 5}}


def test_ties_are_stable_across_input_order_without_requiring_one_arbitrary_pairing():
    predictions = [interval("p-z", 0, 10), interval("p-a", 0, 10), interval("p-m", 0, 10)]
    references = [interval("r-z", 0, 10), interval("r-a", 0, 10)]
    original = copy.deepcopy((predictions, references))
    baseline = evaluate(predictions, references, duration=10)
    assert baseline["thresholds"][0]["tp"] == 2
    assert fraction(baseline["thresholds"][0]["total_iou_fraction"]) == 2
    for seed in range(8):
        shuffled_predictions, shuffled_references = copy.deepcopy(original)
        generator = random.Random(seed)
        generator.shuffle(shuffled_predictions)
        generator.shuffle(shuffled_references)
        assert evaluate(shuffled_predictions, shuffled_references, duration=10) == baseline
    assert (predictions, references) == original


def test_independent_small_exhaustive_oracle_certifies_cardinality_and_exact_iou_optimization():
    generator = random.Random(20261026)
    ranges = coverage((0, 8), (12, 20))
    for case in range(36):
        predictions, references = [], []
        for index in range(generator.randrange(0, 6)):
            start = generator.randrange(0, 20)
            predictions.append(interval(f"p-{case}-{index}", start, generator.randrange(start + 1, 21)))
        for index in range(generator.randrange(0, 6)):
            left, right = generator.choice([(0, 8), (12, 20)])
            start = generator.randrange(left, right)
            references.append(interval(f"r-{case}-{index}", start, generator.randrange(start + 1, right + 1)))
        before = copy.deepcopy((predictions, references, ranges))
        report = evaluate(predictions, references, ranges=ranges, duration=20,
                          thresholds=(Fraction(1, 5), Fraction(1, 2), Fraction(2, 3)))
        for row in report["thresholds"]:
            expected_count, expected_total = exhaustive_optimum(
                predictions, references, ranges, fraction(row["iou_threshold_fraction"]))
            assert row["tp"] == expected_count, f"oracle cardinality mismatch in case {case}"
            assert fraction(row["total_iou_fraction"]) == expected_total, f"oracle IoU mismatch in case {case}"
            assert_certified(row, predictions, references, ranges)
        assert (predictions, references, ranges) == before
        generator.shuffle(predictions)
        generator.shuffle(references)
        assert evaluate(predictions, references, ranges=ranges, duration=20,
                        thresholds=(Fraction(1, 5), Fraction(1, 2), Fraction(2, 3))) == report


@pytest.mark.parametrize("invalid", ["predictions_container", "references_container", "coverage_container",
    "empty_coverage", "unsorted_coverage", "overlapping_coverage", "empty_range", "coverage_extra",
    "prediction_not_object", "prediction_missing_field", "prediction_extra", "prediction_id_type",
    "prediction_id_empty", "prediction_duplicate_id", "prediction_start_bool", "prediction_end_float",
    "prediction_negative", "prediction_empty", "prediction_reversed", "prediction_out_of_duration",
    "reference_extra", "reference_duplicate_id", "reference_crosses_gap", "reference_outside_coverage",
    "reference_time_string", "coverage_time_bool", "duration_bool", "duration_float", "duration_zero"])
def test_invalid_shapes_types_identity_and_annotation_domains_are_rejected(invalid):
    predictions, references, ranges, duration = [interval("p", 1, 5)], [interval("r", 1, 5)], coverage((0, 10), (20, 30)), 30
    if invalid == "predictions_container":
        predictions = tuple(predictions)
    elif invalid == "references_container":
        references = None
    elif invalid == "coverage_container":
        ranges = tuple(ranges)
    elif invalid == "empty_coverage":
        ranges = []
    elif invalid == "unsorted_coverage":
        ranges.reverse()
    elif invalid == "overlapping_coverage":
        ranges = coverage((0, 12), (10, 30))
    elif invalid == "empty_range":
        ranges[0]["end_ms"] = 0
    elif invalid == "coverage_extra":
        ranges[0]["label"] = "not part of the mathematics API"
    elif invalid == "prediction_not_object":
        predictions = [None]
    elif invalid == "prediction_missing_field":
        predictions[0].pop("id")
    elif invalid == "prediction_extra":
        predictions[0]["score"] = 0.9
    elif invalid == "prediction_id_type":
        predictions[0]["id"] = True
    elif invalid == "prediction_id_empty":
        predictions[0]["id"] = ""
    elif invalid == "prediction_duplicate_id":
        predictions.append(dict(predictions[0]))
    elif invalid == "prediction_start_bool":
        predictions[0]["start_ms"] = True
    elif invalid == "prediction_end_float":
        predictions[0]["end_ms"] = 5.0
    elif invalid == "prediction_negative":
        predictions[0]["start_ms"] = -1
    elif invalid == "prediction_empty":
        predictions[0]["end_ms"] = 1
    elif invalid == "prediction_reversed":
        predictions[0]["end_ms"] = 0
    elif invalid == "prediction_out_of_duration":
        predictions[0]["end_ms"] = 31
    elif invalid == "reference_extra":
        references[0]["category"] = "outside the mathematics API"
    elif invalid == "reference_duplicate_id":
        references.append(dict(references[0]))
    elif invalid == "reference_crosses_gap":
        references[0].update(start_ms=5, end_ms=25)
    elif invalid == "reference_outside_coverage":
        references[0].update(start_ms=11, end_ms=19)
    elif invalid == "reference_time_string":
        references[0]["end_ms"] = "5"
    elif invalid == "coverage_time_bool":
        ranges[0]["start_ms"] = False
    elif invalid == "duration_bool":
        duration = True
    elif invalid == "duration_float":
        duration = 30.0
    else:
        duration = 0
    with pytest.raises(EvaluationError) as failure:
        evaluate(predictions, references, ranges=ranges, duration=duration)
    assert failure.value.code == "INVALID_EVALUATION_INPUT"


@pytest.mark.parametrize("thresholds", [(), "0.5", (0,), (-0.1,), ("1.01",), (True,),
                                         (float("nan"),), (float("inf"),), ("NaN",),
                                         ("not-a-threshold",), ("0.5", Fraction(1, 2))])
def test_invalid_or_equivalent_duplicate_thresholds_are_rejected(thresholds):
    with pytest.raises(EvaluationError) as failure:
        evaluate([], [], thresholds=thresholds)
    assert failure.value.code == "INVALID_EVALUATION_INPUT"


@pytest.mark.parametrize("side", ["prediction", "reference"])
def test_five_hundred_items_per_side_is_allowed_but_the_next_item_is_rejected(side):
    values = [interval(f"id-{index}", 0, 10) for index in range(500)]
    predictions, references = (values, []) if side == "prediction" else ([], values)
    row = evaluate(predictions, references, duration=10)["thresholds"][0]
    assert row["fp"] == (500 if side == "prediction" else 0)
    assert row["fn"] == (500 if side == "reference" else 0)
    values.append(interval("id-over-limit", 0, 10))
    with pytest.raises(EvaluationError) as failure:
        evaluate(predictions, references, duration=10)
    assert failure.value.code == "EVALUATION_LIMIT_EXCEEDED"


def test_more_than_one_hundred_thousand_candidate_pairs_fail_with_a_bounded_limit_error():
    predictions = [interval(f"p-{index}", 0, 10) for index in range(317)]
    references = [interval(f"r-{index}", 0, 10) for index in range(317)]
    with pytest.raises(EvaluationError) as failure:
        evaluate(predictions, references, duration=10)
    assert failure.value.code == "EVALUATION_LIMIT_EXCEEDED"


def test_coverage_and_threshold_count_limits_also_bound_work_for_empty_inputs():
    with pytest.raises(EvaluationError) as failure:
        evaluate([], [], ranges=coverage(*[(index * 2, index * 2 + 1) for index in range(1001)]),
                 duration=2002)
    assert failure.value.code == "EVALUATION_LIMIT_EXCEEDED"
    with pytest.raises(EvaluationError) as failure:
        evaluate([], [], thresholds=tuple(Fraction(index, 11) for index in range(1, 12)))
    assert failure.value.code == "INVALID_EVALUATION_INPUT"
