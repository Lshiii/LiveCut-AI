"""Bounded price-context retrieval for product questions in text QA."""
import re
import unicodedata
from bisect import bisect_left

_PRICE_INTENT = re.compile(r"报价|价格|价钱|售价|到手价|直播价|多少钱|多少[元块]|几块钱|怎么卖|how much|\bprices?\b", re.I)
_SHOPPING = re.compile(r"商品|产品|在售|带货|直播|主播|卖点|这款|几盒|套餐|下单|product|item|offer", re.I)
_NUMBER = r"(?:\d+(?:\.\d+)?|[零〇一二两三四五六七八九十百千]+)"
_UNIT = r"(?:盒|袋|包|份|件|瓶|桶|套|个|支|罐|斤|公斤|克|升|毫升|片|枚|只|条|箱|本|把|组|双|杯|粒|张)"
_AMOUNT = re.compile(_NUMBER + r"\s*(?:块钱?|元|毛钱|刀)|[¥￥$]\s*\d")
_COUNT = re.compile(_NUMBER + r"\s*(?:大|小)?" + _UNIT)
_DIGIT = re.compile(_NUMBER)
_PRICE_AND_PACK = re.compile(r"\d+(?:\.\d+)?\s*(?:是|买|拍|拿|到手|到手价)\s*" + _NUMBER + r"\s*" + _UNIT
                            + "|" + _COUNT.pattern + r"\s*(?:卖|只要|到手|=)\s*\d")
_DIRECT = re.compile(r"到手|拍[下这]|直播价|卖价|下单|买一送一|成交价|券后|活动价|售价|报价|价格|团购价")
_REFERENCE = re.compile(r"建议零售|市场价|原价|指导价|划线价|平[常时]价")
_CALC = re.compile(r"相当于|相对|折算|折合|算下来|平均|合计|除以|乘以|平摊|摊下来")
_DETAILS_REQUESTED = re.compile(r"规格|数量|买赠|赠品|赠送|容量|净重|多大|几[瓶盒桶包袋个]")
_DETAIL_TERMS = [
    re.compile(_NUMBER + r"\s*(?:公斤|千克|毫升|升|克|斤|磅)"),
    re.compile(_NUMBER + r"\s*(?:到|至|[-~])\s*" + _NUMBER + r"\s*" + _UNIT),
    re.compile(_NUMBER + r"[大小]" + _UNIT + r".{0,15}" + _NUMBER + r"[大小]" + _UNIT),
    re.compile(r"(?:另赠|加赠|还送|还给.{0,8}送|赠送|再送).{0,6}" + _NUMBER),
]
_PREFIX = "核价"

OFFER_SYSTEM = """
本题要求核实商品报价。商品名未知时先用 search_transcript 通查商品和销售原话。识别候选后，在还可查工具时用一次 search_transcript 按商品逐个独立补查：query 格式为“核价：商品名|别名@规格；另一商品名|其别名”，top_k=6。省略未知的别名或规格，别名只用问题/真实转写中有根据的称呼。至多四种商品。用户已点名时可直接用这个格式。两个规格属于同一商品时查一次即可。
只根据返回的原句回答。核价检索返回的是邻近线索，仍需逐件检查商品、金额、数量与单位的归属，给出可回看的原句引用。price_context 标出带参考定价或算术措辞的句子，这些提示不代表实际售价或商品归属。按商品分别写具体卖点、明确直播报价及相应规格。没有明确直播报价时，若该商品出现建议零售价、原价、赠品价值或平均/折算价，指出与该商品对应的参考或折算原句，并解释为什么这些数字不能充当实际售价。数字粘连、单独无单位数字、营养/容量数字不可当明码报价。不要只摘一处含糊的复述，优先引用能互相印证的清晰原句，结合临近的点名和规格句；没有实际成交凭证时用“主播报价”，不声称已成交。没有核价的其他商品仍可能在售，不能把遗漏或没有价格解释为仅闲聊。在售状态独立于是否找到报价；只在有明确原句支持时才能说某物未售。"""


def wants_product_prices(question, topics):
    return bool(_PRICE_INTENT.search(question) and (_SHOPPING.search(question) or any(
        _SHOPPING.search(topic["title"]) for topic in topics)))


def price_query(query):
    """Return product aliases and optional variant from a standard transcript tool query."""
    head, sep, rest = query.replace("：", ":", 1).partition(":")
    if not sep or head.strip() != _PREFIX:
        return []
    groups = []
    for part in re.split(r"[;；]", rest):
        name, _, spec = part.partition("@")
        aliases = list(dict.fromkeys(alias.strip() for alias in name.split("|") if alias.strip()))
        if not 1 <= len(aliases) <= 4 or any(len(alias) > 60 for alias in aliases) or len(spec) > 40:
            return []
        groups.append((aliases, spec.strip()))
    return groups if 1 <= len(groups) <= 4 else []


def _normalized(value):
    return "".join(unicodedata.normalize("NFKC", value).casefold().split())


def _price_cue(text):
    """Rank places to read, never infer an actual selling price from the score."""
    if not _DIGIT.search(text):
        return 0, ""
    if _REFERENCE.search(text):
        return 7, "reference"
    if _CALC.search(text):
        return 5, "calculation"
    if _PRICE_AND_PACK.search(text) or (_DIRECT.search(text) and _AMOUNT.search(text)):
        return 8, "offer"
    if _DIRECT.search(text) and re.search(r"\d", text):
        return 5, "offer"
    if _AMOUNT.search(text):
        return 3, "amount"
    if _COUNT.search(text) and len(_DIGIT.findall(text)) >= 2:
        return 4, "quantity"
    return 0, ""


def price_context(rows):
    labels = {"reference": "参考定价措辞", "calculation": "算术或折算措辞"}
    return [{"utterance_id": row["utterance_id"], "cue": labels[kind]} for row in rows
            if (kind := _price_cue(row["text"])[1]) in labels][:24]


def _product_anchors(items, normalized, names):
    anchors = [i for i, text in enumerate(normalized) if any(name in text for name in names)]
    chinese_names = [name for name in names if re.fullmatch(r"[\u3400-\u9fff]{4,16}", name)]
    if not anchors or not chinese_names:
        return anchors
    # Recover forms spoken separately from the full SKU. Nearby sentences can
    # first omit the brand prefix and then shorten an internal modifier.
    neighborhood = []
    last_checked = -1
    for anchor in anchors:
        for i in range(max(last_checked + 1, anchor - 8, 0), min(len(items), anchor + 9)):
            if abs(items[i]["start_ms"] - items[anchor]["start_ms"]) > 45000:
                continue
            text = normalized[i]
            for name in names:
                text = text.replace(name, " ")
            neighborhood.append(text)
            last_checked = i
    suffixes = {name[k:] for name in chinese_names for k in range(1, len(name) - 2)}
    observed = {suffix for suffix in suffixes if any(suffix in text for text in neighborhood)}
    qualified = {name for name in observed if not any(name != other and other.endswith(name) for other in observed)}
    short_forms = {name[:prefix] + name[-suffix:] for name in [*chinese_names, *qualified]
                   for prefix in range(2, min(5, len(name) - 2) + 1) for suffix in (1, 2)
                   if 1 <= len(name) - prefix - suffix <= 5}
    qualified.update(form for form in short_forms if any(form in text for text in neighborhood))
    if qualified:
        all_names = [*names, *qualified]
        anchors = [i for i, text in enumerate(normalized) if any(name in text for name in all_names)]
    return anchors


def product_candidates(items, groups, top_k, *, topics=(), question=""):
    """Split one bounded tool step fairly across named products and nearby price contexts."""
    normalized = [_normalized(u["text"]) for u in items]
    details_requested = _DETAILS_REQUESTED.search(question)
    cues = [(i, *_price_cue(row["text"]), tuple(j for j, cue in enumerate(_DETAIL_TERMS) if
             details_requested and cue.search(row["text"]))) for i, row in enumerate(items)]
    order = {row["utterance_id"]: i for i, row in enumerate(items)}
    spans = sorted((order[t["start_utterance_id"]], order[t["end_utterance_id"]]) for t in topics)
    starts = [first for first, _ in spans]
    chapters = []
    for i in range(len(items)):
        pos = bisect_left(starts, i + 1) - 1
        chapters.append(pos if pos >= 0 and i <= spans[pos][1] else None)
    groups_of_hits = []
    for aliases, spec in groups:
        names = [_normalized(alias) for alias in aliases]
        anchors = _product_anchors(items, normalized, names)
        if not anchors:
            continue
        hits, details = [], {}
        for i, weight, kind, detail_types in cues:
            if not weight and not detail_types:
                continue
            insertion = bisect_left(anchors, i)
            neighbors = anchors[max(0, insertion - 1):insertion + 1]
            anchor = min(neighbors, key=lambda a: abs(items[a]["start_ms"] - items[i]["start_ms"]))
            distance = abs(items[anchor]["start_ms"] - items[i]["start_ms"])
            same_chapter = chapters[i] is not None and chapters[i] == chapters[anchor]
            time_limit = 300000 if same_chapter else (45000 if chapters[i] is not None else 120000)
            # At a product transition a host may use just the brand to enumerate bottles or packs.
            if detail_types and any(len(name) >= 4 and name[:3] in normalized[i] for name in names):
                time_limit = max(time_limit, 120000)
            if distance > time_limit or abs(anchor - i) > (50 if same_chapter else 24):
                continue
            for slot in detail_types:
                detail = (not same_chapter, distance, i, anchor)
                if slot not in details or detail < details[slot]:
                    details[slot] = detail
            if not weight:
                continue
            nearby = "".join(normalized[max(0, i - 1):i + 2])
            hits.append((-weight, not (spec and _normalized(spec) in nearby), distance, i, anchor, kind))
        hits.sort()
        # A repeated pitch must leave room for the contrasting retail/estimated amounts.
        ranked, repeats, kinds = [], [], set()
        for hit in hits:
            kind = hit[-1]
            (repeats if kind in kinds else ranked).append((hit[3], hit[4]))
            kinds.add(kind)
        groups_of_hits.append((ranked + repeats or [(anchors[0], anchors[0])], details))
    candidates, seen = [], set()
    for k in range(top_k):
        for hits, details in groups_of_hits:
            if k >= len(hits):
                continue
            quote, anchor = hits[k]
            if (quote, anchor) in seen:
                continue
            seen.add((quote, anchor))
            positions = {anchor, quote}
            # The name and the amount can be separated by several sentences.
            # Always include the immediate clarification around the amount itself.
            positions.update(i for i in (quote - 1, quote + 1) if 0 <= i < len(items)
                             and abs(items[i]["start_ms"] - items[quote]["start_ms"]) <= 15000)
            if k == 0:
                for _, _, detail, matching_name in details.values():
                    positions.update((detail, matching_name))
            candidates.append({"utterance_ids": [items[i]["utterance_id"] for i in sorted(positions)]})
            if len(candidates) == top_k:
                return candidates
    return candidates
