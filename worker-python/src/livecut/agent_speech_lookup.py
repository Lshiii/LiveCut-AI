"""Explicit, bounded local transcript preflight for the opt-in 1.4 policy."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

from .agent_evidence import JS_TRIM_WHITESPACE, SYSTEM, utf16_length
from .agent_focus import _pairs
from .contracts import _StrictValidator
from .intent_retrieval import build_index, retrieve
from .segmentation import SegmentationError, _json, _save

POLICY = "speech_lookup_v1"
PROMPT_VERSION = "video-agent-evidence-speech-v1"
SPEECH_SYSTEM = SYSTEM + """
本轮用户显式要求回答前核对讲者原句。程序已执行一次EVIDENCE_POLICY本地预查，实际原句在observations中。
它不是你自主选择的工具，不是新ASR，也不证明原句相关或全片已查完。只有实际输入的原句可以引用。
speech_lookup是程序记录；EMPTY_RETURN只表示本次有限查询未返回原句，CONTEXT_LIMIT可能仍有部分原句。
QUESTION_SEARCH的实际query可能是完整问题的有界前缀，不能声称覆盖后面的子问题；原问题全文仍需回答。
原句不足或不相关时可在剩余工具/预算内自行search_transcript或read_context，也可明确合理不足。
不要为了通过形式检查引用无关原句。禁止生成evidence_policy、speech_lookup或任何origin；程序负责发布这些字段。
"""
FIELDS = {"tool_step", "strategy", "status", "query_truncated", "anchor_distance_ms", "candidate_utterance_count", "returned_utterance_ids"}


def normalize_speech_answer_decision(value):
    """Default only an omitted, non-actionable clip hint in the opt-in profile."""
    if (isinstance(value, dict) and value.get("type") == "answer"
            and set(value) == {"type", "answer_scope", "answer_blocks"}):
        return {**value, "suggested_clip_instruction": None}
    return value


def trim_js(value: str) -> str:
    return value.strip("".join(JS_TRIM_WHITESPACE))


def validate_question(question):
    try:
        length = utf16_length(question) if isinstance(question, str) else 2001
    except SegmentationError:
        raise SegmentationError("INVALID_AGENT_SPEECH_INPUT", "The new policy question contains an invalid surrogate") from None
    if (not isinstance(question, str) or not trim_js(question) or length > 2000
            or any(ord(ch) < 32 and ch not in "\t\r\n" or 127 <= ord(ch) <= 159 for ch in question)):
        raise SegmentationError("INVALID_AGENT_SPEECH_INPUT", "The new policy requires a bounded nonblank question")


def question_query(question: str) -> tuple[str, bool]:
    validate_question(question)
    text = trim_js(question)
    truncated = utf16_length(text) > 300
    units, chars = 0, []
    for char in text:
        size = utf16_length(char)
        if units + size > 300:
            break
        units += size
        chars.append(char)
    query = trim_js("".join(chars))
    if not query:
        raise SegmentationError("INVALID_AGENT_SPEECH_INPUT", "The bounded transcript query is empty")
    return query, truncated


def nearest_utterance(utterances, focus):
    if focus is None or not utterances:
        return None, None
    def distance(item):
        if focus < item["start_ms"]:
            return item["start_ms"] - focus
        if focus >= item["end_ms"]:
            return focus - item["end_ms"] + 1
        return 0
    index = min(range(len(utterances)), key=lambda i: (distance(utterances[i]), i))
    return index, distance(utterances[index])


def _pack(candidates, priority, order):
    retained, size = [], 0
    for item in priority:
        length = utf16_length(item["text"])
        if size + length <= 10000:
            retained.append(item)
            size += length
    retained.sort(key=lambda item: order[item["utterance_id"]])
    status = "CONTEXT_LIMIT" if len(candidates) > len(retained) else "RETURNED" if retained else "EMPTY_RETURN"
    return retained, status


def prepare_speech_lookup(utterances, question, *, focus_timestamp_ms=None, source_hash, transcript_hash,
                          index_cache_dir, guard=lambda: None):
    """Return deterministic whole-sentence observations; never call a provider."""
    validate_question(question)
    guard()
    order = {item["utterance_id"]: i for i, item in enumerate(utterances)}
    anchor, distance = nearest_utterance(utterances, focus_timestamp_ms)
    if anchor is not None and distance <= 15000:
        strategy, query_truncated = "FOCUS_CONTEXT", False
        arguments = {"utterance_id": utterances[anchor]["utterance_id"], "before": 1, "after": 1}
        candidates = utterances[max(0, anchor-1):anchor+2]
        priority = [utterances[i] for i in (anchor, anchor-1, anchor+1) if 0 <= i < len(utterances)]
    else:
        strategy = "QUESTION_SEARCH"
        query, query_truncated = question_query(question)
        arguments = {"query": query, "top_k": 4}
        index = build_index(utterances, source_hash=source_hash, transcript_hash=transcript_hash,
                            cache_dir=index_cache_dir, max_items=1, max_chars=4000)
        found = retrieve(index, [query], utterances, top_k=4, context_before=0, context_after=0, max_visible_chars=16000)
        by_id = {item["utterance_id"]: item for item in utterances}
        if any(len(item["utterance_ids"]) != 1 for item in found["candidates"]):
            raise SegmentationError("INVALID_AGENT_SPEECH_CHECKPOINT", "Policy index must contain single sentences")
        candidates = [by_id[item["utterance_ids"][0]] for item in found["candidates"]]
        priority = candidates
    retained, status = _pack(candidates, priority, order)
    receipt = {"tool_step": 2 if focus_timestamp_ms is not None else 1, "strategy": strategy, "status": status,
               "query_truncated": query_truncated, "anchor_distance_ms": distance,
               "candidate_utterance_count": len(candidates), "returned_utterance_ids": [u["utterance_id"] for u in retained]}
    guard()
    return {"arguments": arguments, "receipt": receipt,
            "observation": {"utterances": copy.deepcopy(retained), "context_truncated": status == "CONTEXT_LIMIT"}}


def _write_policy(path, value, guard):
    guard()
    if path.is_symlink() or path.with_suffix(path.suffix + ".tmp").is_symlink():
        raise SegmentationError("INVALID_AGENT_SPEECH_CHECKPOINT", "Policy observation or temporary file cannot follow links")
    try:
        _save(path, value)
    except OSError:
        raise SegmentationError("SPEECH_OBSERVATION_STORAGE_FAILED", "Policy observation could not be published; no model request was made") from None
    guard()


def prime_speech_lookup(directory, fingerprint, utterances, question, *, prior_requests=False, **options):
    path = Path(directory) / "speech-observation.json"
    guard = options.get("guard", lambda: None)
    guard()
    base = {"config_hash": fingerprint, "status": "PREPARING", "prepared": None, "prepared_sha256": None}
    if path.is_symlink():
        raise SegmentationError("INVALID_AGENT_SPEECH_CHECKPOINT", "Policy observation cannot follow links")
    resume_preparing = False
    if path.exists():
        try:
            if path.stat().st_size > 1024*1024:
                raise ValueError
            saved = json.loads(path.read_text("utf-8"), object_pairs_hook=_pairs)
            if set(saved) != set(base) or saved["config_hash"] != fingerprint:
                raise ValueError
            if saved["status"] == "PREPARING":
                if prior_requests or saved["prepared"] is not None or saved["prepared_sha256"] is not None:
                    raise ValueError
                resume_preparing = True
            elif saved["status"] != "READY" or saved["prepared_sha256"] != hashlib.sha256(_json(saved["prepared"]).encode()).hexdigest():
                raise ValueError
            else:
                expected = prepare_speech_lookup(utterances, question, **options)
                if saved["prepared"] != expected:
                    raise ValueError
                guard()
                return copy.deepcopy(saved["prepared"])
        except (OSError, ValueError, TypeError, KeyError):
            raise SegmentationError("INVALID_AGENT_SPEECH_CHECKPOINT", "The original policy observation is missing, incomplete or changed") from None
    if prior_requests and not resume_preparing:
        raise SegmentationError("INVALID_AGENT_SPEECH_CHECKPOINT", "A dispatched request cannot acquire another policy observation")
    try:
        if not resume_preparing:
            _write_policy(path, base, guard)
        prepared = prepare_speech_lookup(utterances, question, **options)
        guard()
        _write_policy(path, {**base, "status": "READY", "prepared": prepared,
                            "prepared_sha256": hashlib.sha256(_json(prepared).encode()).hexdigest()}, guard)
        guard()
        return prepared
    except OSError:
        raise SegmentationError("SPEECH_OBSERVATION_STORAGE_FAILED", "Policy observation was not published; no model request was made") from None


def validate_speech_lookup(result, utterances):
    """Verify source/strategy/whole-sentence bounds, not lexical relevance/ranking."""
    validate_question(result["question"])
    receipt, calls = result["speech_lookup"], result["tool_calls"]
    if any(call["tool_name"] == "search_transcript" and utf16_length(call["arguments"]["query"]) > 300 for call in calls):
        raise ValueError("A new-profile model search query exceeds 300 UTF-16 units")
    focus = result.get("focus_timestamp_ms")
    step = 2 if focus is not None else 1
    if set(receipt) != FIELDS or receipt["tool_step"] != step or len(calls) < step:
        raise ValueError("Invalid policy receipt")
    policy = calls[step-1]
    if (any(call["origin"] != ("USER_FOCUS" if focus is not None and i == 0 else "EVIDENCE_POLICY" if i == step-1 else "MODEL")
            for i, call in enumerate(calls)) or policy["result_frame_ids"]):
        raise ValueError("Invalid policy origin")
    returned = receipt["returned_utterance_ids"]
    if returned != policy["result_utterance_ids"]:
        raise ValueError("Policy did not publish its actual returned IDs")
    order = {u["utterance_id"]: i for i, u in enumerate(utterances)}
    if any(uid not in order for uid in returned) or len(set(returned)) != len(returned) or returned != sorted(returned, key=order.get):
        raise ValueError("Unverified policy source IDs")
    if sum(utf16_length(utterances[order[uid]]["text"]) for uid in returned) > 10000:
        raise ValueError("Policy observation exceeds its whole-sentence bound")
    anchor, distance = nearest_utterance(utterances, focus)
    if receipt["anchor_distance_ms"] != distance:
        raise ValueError("Policy anchor distance changed")
    candidate_count = receipt["candidate_utterance_count"]
    if anchor is not None and distance <= 15000:
        candidates = utterances[max(0, anchor-1):anchor+2]
        priority = [utterances[i] for i in (anchor, anchor-1, anchor+1) if 0 <= i < len(utterances)]
        retained, _ = _pack(candidates, priority, order)
        if (receipt["strategy"] != "FOCUS_CONTEXT" or policy["tool_name"] != "read_context"
                or policy["arguments"] != {"utterance_id": utterances[anchor]["utterance_id"], "before": 1, "after": 1}
                or receipt["query_truncated"] is not False or candidate_count != len(candidates)
                or returned != [u["utterance_id"] for u in retained]):
            raise ValueError("Policy context differs from the deterministic frozen source range")
    else:
        query, truncated = question_query(result["question"])
        if (receipt["strategy"] != "QUESTION_SEARCH" or policy["tool_name"] != "search_transcript"
                or policy["arguments"] != {"query": query, "top_k": 4} or receipt["query_truncated"] != truncated
                or not 0 <= candidate_count <= 4):
            raise ValueError("Policy search differs from its bounded original question")
    expected_status = "CONTEXT_LIMIT" if candidate_count > len(returned) else "RETURNED" if returned else "EMPTY_RETURN"
    if candidate_count < len(returned) or receipt["status"] != expected_status:
        raise ValueError("Policy status differs from actual returned evidence")


def validate_speech_answer(result, utterances, *, expected=None, expected_frames=None, duration_ms=None):
    try:
        schema = next(parent / "contracts/agent-answer-evidence-speech.schema.json" for parent in Path(__file__).resolve().parents
                      if (parent / "contracts/agent-answer-evidence-speech.schema.json").is_file())
        _StrictValidator(json.loads(schema.read_text("utf-8-sig"))).validate(result)
        if expected and any(result.get(key) != value for key, value in expected.items()):
            raise ValueError("Speech policy ownership changed")
        validate_speech_lookup(result, utterances)
        from .agent_evidence import PROMPT_VERSION as EVIDENCE_PROMPT
        from .agent_evidence import validate_evidence_answer

        projected = copy.deepcopy(result)
        projected.pop("evidence_policy")
        projected.pop("speech_lookup")
        projected["schema_version"] = "1.3"
        projected["provenance"]["prompt_version"] = EVIDENCE_PROMPT
        for call in projected["tool_calls"]:
            if "focus_timestamp_ms" in projected:
                if call["origin"] == "EVIDENCE_POLICY":
                    call["origin"] = "MODEL"
            else:
                call.pop("origin")
        validate_evidence_answer(projected, utterances, expected_frames=expected_frames, duration_ms=duration_ms)
        return result
    except Exception:
        raise SegmentationError("INVALID_AGENT_SPEECH_ANSWER", "Speech policy result failed source and receipt validation") from None
