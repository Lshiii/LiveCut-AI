"""Build reviewable alternatives from a montage's already fetched, source-bound evidence."""
from __future__ import annotations

import re
from collections import Counter

from .intent_retrieval import terms

# Editorial instructions are shared by unrelated products and cannot identify a subject.
_GENERIC = set(terms("主播 商品 产品 介绍 具体 讲解 卖点 片段 完整 展示 定位 需要 保留 优点 主要 使用 内容 方法 开场"))
_INCOMPLETE = re.compile(r"(?:乘以|等于|分别是|一共是|总共是|首先是)[\d.]*[。！？，、\s]*$")
_DETAIL = re.compile(r"克|升|容量|配料|原料|规格|特点|含量|口感|做法|蒸|炒|去骨|去刺|每[瓶桶盒袋]|[大小][桶瓶]|第[一二三]个")


def _subject_terms(text: str) -> set[str]:
    return {token for token in terms(text) if len(token) > 1 and not token.isdecimal()} - _GENERIC


def _part(items: list[dict], first: int, last: int) -> dict:
    head, tail = items[first], items[last]
    return {"start_utterance_id": head["utterance_id"], "end_utterance_id": tail["utterance_id"],
            "start_ms": head["start_ms"], "end_ms": tail["end_ms"]}


def component_candidates(rows: list[dict], goals: list[dict], retrieval: dict, items: list[dict]) -> list[dict]:
    """Choice zero is the exact original selection. Rank other original sentences within its own goal.

    A component whose primary overlaps or interleaves another has no unambiguous swap operation.
    The original montage is still publishable in that case.
    """
    if not rows or any(r["status"] != "FOUND" for r in rows):
        return []
    by_id = {u["utterance_id"]: i for i, u in enumerate(items)}
    groups = sorted(rows, key=lambda r: r["parts"][0]["start_ms"])
    if any(left["parts"][-1]["end_ms"] > right["parts"][0]["start_ms"]
           for left, right in zip(groups, groups[1:])):
        return []
    blocked = {by_id[e["utterance_id"]] for row in rows for e in row["excluded"]}
    goals_by_id = {g["goal_id"]: g for g in goals}
    subjects = {row["goal_id"]: _subject_terms(" ".join([row["title"], *goals_by_id[row["goal_id"]]["queries"]]))
                for row in groups}
    frequencies = Counter(term for tokens in subjects.values() for term in tokens)
    result = []
    for n, row in enumerate(groups):
        gid = row["goal_id"]
        primary = {k: row[k] for k in ("reason", "parts", "evidence_utterance_ids")}
        choices = [primary]
        first = by_id[groups[n - 1]["parts"][-1]["end_utterance_id"]] + 1 if n > 0 else 0
        last = by_id[groups[n + 1]["parts"][0]["start_utterance_id"]] if n + 1 < len(groups) else len(items)
        allowed = {by_id[u["utterance_id"]] for u in retrieval[gid]["utterances"]} - blocked
        mentioned = {term for term in subjects[gid] if frequencies[term] == 1}
        evidence = {by_id[uid] for uid in row["evidence_utterance_ids"]}
        # Only title/query tokens corroborated by an already attributed source sentence
        # may identify a new occurrence, even when retrieval returned generic nearby hits.
        named = mentioned & set().union(*(_subject_terms(items[i]["text"]) for i in evidence))
        primary_bounds = [(by_id[p["start_utterance_id"]], by_id[p["end_utterance_id"]]) for p in row["parts"]]
        ranked = []
        # The phase-0 query may only know "cooking oil". Now the primary source
        # says the brand. Use those corroborated terms to look for another
        # occurrence in the original transcript, without another model call.
        for i in range(first, last):
            if i in blocked:
                continue
            overlap = named & _subject_terms(items[i]["text"])
            original = next(((a, b) for a, b in primary_bounds if a <= i <= b), None)
            if i not in allowed and (len(overlap) < 2 or not _DETAIL.search(items[i]["text"])):
                continue
            if not overlap or (not original and not _DETAIL.search(items[i]["text"]) and len(overlap) < 2):
                continue
            end = i
            if original:
                # Expand inside this product's verified selection. Do not restore an
                # excluded aside or a number hanging off the end of a different topic.
                while end + 1 <= min(original[1], i + 3) and end + 1 in allowed and end + 1 in evidence:
                    if items[end + 1]["start_ms"] - items[end]["end_ms"] > 5000 or _INCOMPLETE.search(items[end + 1]["text"]):
                        break
                    end += 1
            if _INCOMPLETE.search(items[end]["text"]):
                continue
            parts = [_part(items, i, end)]
            if parts == primary["parts"]:
                continue
            detail = bool(_DETAIL.search(" ".join(item["text"] for item in items[i:end + 1])))
            if not original and not detail:
                continue
            score = (not bool(original), detail, len(overlap), end - i, -i)
            ranked.append((score, i, end, parts))
        kept = []
        for _, i, end, parts in sorted(ranked, key=lambda entry: entry[0], reverse=True):
            # Different buttons should offer different speech, not just drop a leading word.
            if any(max(0, min(end, b) - max(i, a) + 1) * 2 > min(end - i + 1, b - a + 1)
                   for a, b in kept):
                continue
            ids = [u["utterance_id"] for u in items[i:end + 1]]
            choices.append({"parts": parts, "evidence_utterance_ids": ids,
                            "reason": "同一素材的另一组原句，可回看核对。" if i in evidence
                            else "原转写中另一次提到这项素材及讲解细节，采用前请回看。"})
            kept.append((i, end))
            if len(choices) == 4:
                break
        if len(primary_bounds) > 1:
            for a, b in primary_bounds:
                if len(choices) == 4:
                    break
                direct = [items[i]["utterance_id"] for i in range(a, b + 1) if i in evidence]
                if not direct or any(min(b, end) >= max(a, start) for start, end in kept):
                    continue
                choices.append({"reason": "单独保留原方案中这一组完整原句。", "parts": [_part(items, a, b)],
                                "evidence_utterance_ids": direct})
        result.append({"component_id": gid, "title": row["title"], "choices": choices})
    return result
