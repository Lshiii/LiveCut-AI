"""Exact, deterministic interval detection metrics over explicitly annotated time.

This pure module knows neither labels' provenance nor prediction artifacts. The
caller must validate those independently before treating any score as evidence.
"""

from __future__ import annotations

from dataclasses import dataclass
from fractions import Fraction
from typing import Any

_MAX_INTERVALS = 500
_MAX_PAIRS = 100000
_MAX_COVERAGE_RANGES = 1000


class EvaluationError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        self.code = code
        super().__init__(message)


def _invalid() -> EvaluationError:
    return EvaluationError("INVALID_EVALUATION_INPUT", "Evaluation requires valid bounded interval inputs")


def _limit() -> EvaluationError:
    return EvaluationError("EVALUATION_LIMIT_EXCEEDED", "Evaluation exceeds its supported resource bounds")


@dataclass(frozen=True)
class _Interval:
    id: str
    start: int
    end: int


@dataclass(frozen=True)
class _MaskedPrediction:
    interval: _Interval
    parts: tuple[tuple[int, int], ...]
    length: int


def _time_range(item: Any, duration: int, fields: set[str]) -> tuple[int, int]:
    if not isinstance(item, dict) or set(item) != fields:
        raise _invalid()
    start, end = item["start_ms"], item["end_ms"]
    if type(start) is not int or type(end) is not int or not 0 <= start < end <= duration:
        raise _invalid()
    return start, end


def _intervals(items: Any, duration: int) -> list[_Interval]:
    if not isinstance(items, list):
        raise _invalid()
    if len(items) > _MAX_INTERVALS:
        raise _limit()
    result, seen = [], set()
    for item in items:
        start, end = _time_range(item, duration, {"id", "start_ms", "end_ms"})
        identity = item["id"]
        if not isinstance(identity, str) or not identity.strip() or identity in seen:
            raise _invalid()
        seen.add(identity)
        result.append(_Interval(identity, start, end))
    return sorted(result, key=lambda item: item.id)


def _coverage(items: Any, duration: int) -> list[tuple[int, int]]:
    if not isinstance(items, list) or not items:
        raise _invalid()
    if len(items) > _MAX_COVERAGE_RANGES:
        raise _limit()
    result: list[tuple[int, int]] = []
    for item in items:
        start, end = _time_range(item, duration, {"start_ms", "end_ms"})
        if result and start < result[-1][1]:
            raise _invalid()
        if result and start == result[-1][1]:
            result[-1] = result[-1][0], end
        else:
            result.append((start, end))
    return result


def _thresholds(values: Any) -> list[Fraction]:
    if not isinstance(values, (tuple, list)) or not values or len(values) > 10:
        raise _invalid()
    result: list[Fraction] = []
    for value in values:
        if type(value) not in {str, int, float, Fraction}:
            raise _invalid()
        # Do not let a tiny user-supplied exponent allocate unbounded integers.
        if isinstance(value, str):
            if len(value) > 128:
                raise _invalid()
            lower = value.lower()
            if "e" in lower:
                try:
                    exponent = int(lower.rsplit("e", 1)[1])
                except ValueError:
                    raise _invalid() from None
                if abs(exponent) > 1000:
                    raise _invalid()
        try:
            fraction = value if isinstance(value, Fraction) else Fraction(str(value))
        except (ValueError, ZeroDivisionError, OverflowError):
            raise _invalid() from None
        if not 0 < fraction <= 1 or fraction in result:
            raise _invalid()
        result.append(fraction)
    return result


def _mask(predictions: list[_Interval], coverage: list[tuple[int, int]]) -> list[_MaskedPrediction]:
    result = []
    for prediction in predictions:
        parts = tuple((max(prediction.start, start), min(prediction.end, end))
                      for start, end in coverage
                      if max(prediction.start, start) < min(prediction.end, end))
        if parts:
            result.append(_MaskedPrediction(prediction, parts, sum(end - start for start, end in parts)))
    return result


def _iou(prediction: _MaskedPrediction, reference: _Interval) -> tuple[Fraction, int, int]:
    intersection = sum(max(0, min(end, reference.end) - max(start, reference.start))
                       for start, end in prediction.parts)
    union = prediction.length + reference.end - reference.start - intersection
    return Fraction(intersection, union), intersection, union


def _hungarian(costs: list[list[Fraction | int]]) -> list[tuple[int, int]]:
    """Minimum-cost rectangular assignment, with sorted rows/columns for ties.

    Every row has an independent dummy option. The standard augmenting-path
    potentials use Fraction throughout; no epsilon or float rounding decides an
    edge, objective, or tie. Strict comparisons preserve first (stable-ID) ties.
    """
    rows, columns = len(costs), len(costs[0])
    row_potential = [Fraction(0)] * (rows + 1)
    column_potential = [Fraction(0)] * (columns + 1)
    owner, previous = [0] * (columns + 1), [0] * (columns + 1)
    for row in range(1, rows + 1):
        owner[0], column = row, 0
        distance: list[Fraction | None] = [None] * (columns + 1)
        visited = [False] * (columns + 1)
        while True:
            visited[column] = True
            active_row = owner[column]
            delta, next_column = None, 0
            for candidate in range(1, columns + 1):
                if visited[candidate]:
                    continue
                reduced = costs[active_row - 1][candidate - 1] - row_potential[active_row] - column_potential[candidate]
                if distance[candidate] is None or reduced < distance[candidate]:
                    distance[candidate], previous[candidate] = reduced, column
                if delta is None or distance[candidate] < delta:
                    delta, next_column = distance[candidate], candidate
            assert delta is not None
            for candidate in range(columns + 1):
                if visited[candidate]:
                    row_potential[owner[candidate]] += delta
                    column_potential[candidate] -= delta
                elif distance[candidate] is not None:
                    distance[candidate] -= delta
            column = next_column
            if owner[column] == 0:
                break
        while column:
            predecessor = previous[column]
            owner[column] = owner[predecessor]
            column = predecessor
    return sorted((owner[column] - 1, column - 1)
                  for column in range(1, columns + 1) if owner[column])


def _match(scores: list[list[tuple[Fraction, int, int]]], threshold: Fraction,
           prediction_count: int, reference_count: int) -> list[tuple[int, int]]:
    if not prediction_count or not reference_count:
        return []
    transpose = reference_count < prediction_count
    rows = reference_count if transpose else prediction_count
    columns = prediction_count if transpose else reference_count
    # One extra eligible match outweighs every possible sum-IoU improvement.
    cardinality_reward = rows + 1
    costs: list[list[Fraction | int]] = []
    for row in range(rows):
        values: list[Fraction | int] = []
        for column in range(columns):
            score = scores[column][row][0] if transpose else scores[row][column][0]
            values.append(-(cardinality_reward + score) if score >= threshold else 1)
        costs.append(values + [0] * rows)
    result = []
    for row, column in _hungarian(costs):
        if column < columns:
            prediction, reference = (column, row) if transpose else (row, column)
            if scores[prediction][reference][0] >= threshold:
                result.append((prediction, reference))
    return sorted(result)


def _statistics(values: list[int]) -> dict:
    if not values:
        return {"median": None, "mae": None, "p90": None}
    ordered = sorted(abs(value) for value in values)
    count = len(ordered)
    try:
        median = (ordered[count // 2] if count % 2 else
                  float(Fraction(ordered[count // 2 - 1] + ordered[count // 2], 2)))
        mean = float(Fraction(sum(ordered), count))
    except OverflowError:
        raise _limit() from None
    return {"median": median, "mae": mean, "p90": ordered[(9 * count + 9) // 10 - 1]}


def evaluate_localization(
    predictions: list[dict], references: list[dict], *, coverage: list[dict],
    duration_ms: int, thresholds: tuple | list = ("0.3", "0.5", "0.7"),
) -> dict:
    """Score one video without categories, catalog IDs, or model/provider calls.

    Unannotated time is masked out of IoU. A prediction intersecting separate
    annotated ranges remains ONE detection with one matching slot. Boundary
    errors are signed in matches and absolute in summaries; a censored match
    contributes to detection but never acquires an artificial clipped boundary.
    """
    if type(duration_ms) is not int or duration_ms <= 0:
        raise _invalid()
    predicted, expected = _intervals(predictions, duration_ms), _intervals(references, duration_ms)
    ranges, cutoffs = _coverage(coverage, duration_ms), _thresholds(thresholds)
    if len(predicted) * len(expected) > _MAX_PAIRS:
        raise _limit()
    reference_ranges = []
    for reference in expected:
        containing = next(((start, end) for start, end in ranges
                           if start <= reference.start < reference.end <= end), None)
        if containing is None:
            raise _invalid()
        reference_ranges.append(containing)
    included = _mask(predicted, ranges)
    scores = [[_iou(prediction, reference) for reference in expected] for prediction in included]
    results = []
    for cutoff in cutoffs:
        pairs = _match(scores, cutoff, len(included), len(expected))
        matched_predictions, matched_references = set(), set()
        matches, start_errors, end_errors = [], [], []
        total_iou = Fraction(0)
        censored_count = 0
        for prediction_index, reference_index in pairs:
            prediction, reference = included[prediction_index], expected[reference_index]
            score, intersection, union = scores[prediction_index][reference_index]
            start, end = reference_ranges[reference_index]
            censored = not (start <= prediction.interval.start < prediction.interval.end <= end)
            start_error = None if censored else prediction.interval.start - reference.start
            end_error = None if censored else prediction.interval.end - reference.end
            if censored:
                censored_count += 1
            else:
                start_errors.append(start_error)
                end_errors.append(end_error)
            total_iou += score
            matched_predictions.add(prediction_index)
            matched_references.add(reference_index)
            matches.append({"prediction_id": prediction.interval.id, "reference_id": reference.id,
                            "iou": float(score), "iou_fraction": str(score),
                            "intersection_ms": intersection, "union_ms": union,
                            "boundary_censored": censored, "start_error_ms": start_error,
                            "end_error_ms": end_error})
        tp, fp, fn = len(pairs), len(included) - len(pairs), len(expected) - len(pairs)
        results.append({
            "iou_threshold": float(cutoff), "iou_threshold_fraction": str(cutoff),
            "tp": tp, "fp": fp, "fn": fn,
            "precision": tp / (tp + fp) if tp + fp else None,
            "recall": tp / (tp + fn) if tp + fn else None,
            "f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None,
            "total_iou": float(total_iou), "total_iou_fraction": str(total_iou),
            "matches": matches,
            "false_positive_ids": [prediction.interval.id for index, prediction in enumerate(included)
                                   if index not in matched_predictions],
            "false_negative_ids": [reference.id for index, reference in enumerate(expected)
                                   if index not in matched_references],
            "boundary": {"evaluated_match_count": len(start_errors), "censored_match_count": censored_count,
                         "start_error_ms": _statistics(start_errors), "end_error_ms": _statistics(end_errors)},
        })
    annotated_ms = sum(end - start for start, end in ranges)
    return {
        "matching_method": "maximum_cardinality_then_total_iou", "iou_domain": "annotated_time_only",
        "coverage": {"duration_ms": duration_ms, "annotated_ms": annotated_ms,
                     "annotated_fraction": float(Fraction(annotated_ms, duration_ms)),
                     "ranges": [{"start_ms": start, "end_ms": end} for start, end in ranges]},
        "counts": {"prediction_total": len(predicted), "prediction_included": len(included),
                   "prediction_excluded": len(predicted) - len(included),
                   "prediction_partially_clipped": sum(prediction.length != prediction.interval.end
                                                       - prediction.interval.start for prediction in included),
                   "reference_total": len(expected)},
        "thresholds": results,
    }
