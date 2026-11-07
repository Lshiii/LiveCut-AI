"""Source-bound text retrieval. Dense vectors are optional, never simulated."""
from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Callable

from .segmentation import SegmentationError, _discovery_directory_lock, _json, _save

INDEX_VERSION = "intent-chunks-v1-bm25-cjk-bigrams-dense-400char-mean"
Embedder = Callable[[list[str]], list[list[float]]]


def terms(text: str) -> list[str]:
    """A documented local lexical tokenizer, not a learned semantic encoder."""
    normalized = unicodedata.normalize("NFKC", text).casefold()
    result = re.findall(r"[a-z0-9]+", normalized)
    for phrase in re.findall(r"[\u3400-\u9fff]+", normalized):
        result.extend(phrase)
        result.extend(phrase[i:i + 2] for i in range(len(phrase) - 1))
    return result


def _vectors(value: object, count: int, dimension: int | None = None) -> list[list[float]]:
    if not isinstance(value, list) or len(value) != count:
        raise SegmentationError("INVALID_EMBEDDINGS", "Embedding batch has an invalid row count")
    result = []
    for row in value:
        if not isinstance(row, list) or not 1 <= len(row) <= 4096:
            raise SegmentationError("INVALID_EMBEDDINGS", "Embedding dimensions are invalid")
        if dimension is None:
            dimension = len(row)
        if len(row) != dimension or any(type(x) not in {float, int} or not math.isfinite(x) for x in row):
            raise SegmentationError("INVALID_EMBEDDINGS", "Embedding values must be finite equal-sized vectors")
        norm = math.sqrt(sum(float(x) ** 2 for x in row))
        if not math.isfinite(norm) or norm <= 0:
            raise SegmentationError("INVALID_EMBEDDINGS", "Embedding vectors must have a positive norm")
        result.append([float(x) / norm for x in row])
    return result


def _chunks(items: list[dict], max_chars: int, max_items: int) -> list[dict]:
    result, cursor = [], 0
    while cursor < len(items):
        end, size = cursor, 0
        while end < len(items) and end - cursor < max_items:
            extra = len(items[end]["text"])
            if end > cursor and (size + extra > max_chars
                                 or items[end]["end_ms"] - items[cursor]["start_ms"] > 40000):
                break
            size += extra
            end += 1
        section = items[cursor:end]
        result.append({"chunk_id": f"chunk-{len(result) + 1:05d}",
            "utterance_ids": [u["utterance_id"] for u in section],
            "start_ms": section[0]["start_ms"], "end_ms": section[-1]["end_ms"],
            "text": "\n".join(u["text"] for u in section)})
        cursor = end if end == cursor + 1 else end - 1
        if end == len(items):
            break
    return result


def _document_vectors(chunks: list[dict], embedder: Embedder) -> list[list[float]]:
    """Cover long whole-source sentences without silently truncating their tail.

    Retrieval chunks still retain complete ASR IDs/text. Only the local encoder
    representation uses bounded character slices, averaged and L2 normalized.
    The CPU BGE tokenizer's 512-token limit can therefore accommodate each slice.
    """
    slices, groups = [], []
    for chunk in chunks:
        start = len(slices)
        text = chunk["text"]
        slices.extend(text[offset:offset + 400] for offset in range(0, len(text), 400))
        groups.append((start, len(slices)))
    if not slices:
        return []
    encoded = _vectors(embedder(slices), len(slices))
    pooled = [[sum(encoded[i][column] for i in range(start, end)) / (end - start)
               for column in range(len(encoded[0]))] for start, end in groups]
    return _vectors(pooled, len(chunks))


def build_index(items: list[dict], *, source_hash: str, transcript_hash: str,
                cache_dir: str | Path, dense_embedder: Embedder | None = None,
                embedding_model_id: str | None = None, max_chars: int = 900,
                max_items: int = 8) -> dict:
    """Reuse a validated index only for identical source, transcript and encoder."""
    if type(max_chars) is not int or not 100 <= max_chars <= 4000 or type(max_items) is not int or not 1 <= max_items <= 20:
        raise SegmentationError("INVALID_INPUT", "Invalid evidence chunk limits")
    if dense_embedder is not None and (not isinstance(embedding_model_id, str)
                                       or not 1 <= len(embedding_model_id.strip()) <= 200):
        raise SegmentationError("INVALID_INPUT", "A dense encoder requires a stable model fingerprint")
    if dense_embedder is None and embedding_model_id is not None:
        raise SegmentationError("INVALID_INPUT", "An encoder identity cannot imply absent embeddings")
    chunks = _chunks(items, max_chars, max_items)
    config = {"version": INDEX_VERSION, "source_hash": source_hash, "transcript_hash": transcript_hash,
              "utterances": items, "max_chars": max_chars, "max_items": max_items,
              "embedding_model_id": embedding_model_id}
    fingerprint = hashlib.sha256(_json(config).encode()).hexdigest()
    path = Path(cache_dir) / f"index-{fingerprint}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = "bm25_dense_rrf" if dense_embedder is not None else "bm25"
    with _discovery_directory_lock(path.parent / fingerprint):
        return _index_file(path, fingerprint, chunks, source_hash, transcript_hash,
                           embedding_model_id, dense_embedder, mode)


def _index_file(path: Path, fingerprint: str, chunks: list[dict], source_hash: str, transcript_hash: str,
                embedding_model_id: str | None, dense_embedder: Embedder | None, mode: str) -> dict:
    if path.exists():
        import json
        try:
            index = json.loads(path.read_text(encoding="utf-8"))
            if (set(index) != {"index_hash", "version", "source_hash", "transcript_hash", "embedding_model_id",
                              "retrieval_mode", "chunks", "vectors", "content_hash"}
                    or index["index_hash"] != fingerprint or index["chunks"] != chunks
                    or index["retrieval_mode"] != mode or index["version"] != INDEX_VERSION
                    or index["source_hash"] != source_hash or index["transcript_hash"] != transcript_hash
                    or index["embedding_model_id"] != embedding_model_id
                    or index["content_hash"] != hashlib.sha256(_json({k: v for k, v in index.items() if k != "content_hash"}).encode()).hexdigest()):
                raise ValueError
            if dense_embedder is not None:
                _vectors(index["vectors"], len(chunks))
            elif index["vectors"] is not None:
                raise ValueError
            return index
        except (OSError, ValueError, TypeError, KeyError) as exc:
            raise SegmentationError("INVALID_RETRIEVAL_INDEX", "Cached evidence index is malformed") from exc
    vectors = _document_vectors(chunks, dense_embedder) if dense_embedder else None
    index = {"index_hash": fingerprint, "version": INDEX_VERSION, "source_hash": source_hash,
             "transcript_hash": transcript_hash, "embedding_model_id": embedding_model_id,
             "retrieval_mode": mode, "chunks": chunks, "vectors": vectors}
    index["content_hash"] = hashlib.sha256(_json(index).encode()).hexdigest()
    _save(path, index)
    return index


def retrieve(index: dict, queries: list[str], items: list[dict], *, dense_embedder: Embedder | None = None,
             top_k: int = 4, context_before: int = 1, context_after: int = 2,
             max_visible_chars: int = 16000) -> dict:
    """Rank each goal independently, then expose bounded whole-sentence context."""
    if (not isinstance(queries, list) or not 1 <= len(queries) <= 3
            or any(not isinstance(q, str) or not 1 <= len(q) <= 300 for q in queries)
            or type(top_k) is not int or not 1 <= top_k <= 12
            or type(context_before) is not int or not 0 <= context_before <= 5
            or type(context_after) is not int or not 0 <= context_after <= 5
            or type(max_visible_chars) is not int or max_visible_chars < 100):
        raise SegmentationError("INVALID_INPUT", "Invalid retrieval bounds")
    chunks = index["chunks"]
    if not chunks:
        return {"queries": queries, "candidates": [], "utterances": [], "context_truncated": False}
    corpus = [Counter(terms(c["text"])) for c in chunks]
    frequencies = Counter(token for document in corpus for token in document)
    lengths = [sum(document.values()) for document in corpus]
    average = sum(lengths) / len(lengths) or 1
    lexical = [0.0] * len(corpus)
    for query in queries:
        for token in set(terms(query)):
            df = frequencies[token]
            idf = math.log(1 + (len(corpus) - df + .5) / (df + .5))
            for i, document in enumerate(corpus):
                tf = document[token]
                lexical[i] += idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * lengths[i] / average))
    ranks = [[i for i in sorted(range(len(chunks)), key=lambda i: (-lexical[i], i)) if lexical[i] > 0]]
    if index["retrieval_mode"] == "bm25_dense_rrf":
        if dense_embedder is None:
            raise SegmentationError("MISSING_EMBEDDER", "Dense evidence index requires its original encoder")
        vectors = _vectors(index["vectors"], len(chunks))
        query_vectors = _vectors(dense_embedder(queries), len(queries), len(vectors[0]))
        dense = [max(sum(a * b for a, b in zip(row, query, strict=True)) for query in query_vectors)
                 for row in vectors]
        ranks.append(sorted(range(len(chunks)), key=lambda i: (-dense[i], i)))
    fused: Counter = Counter()
    for ranking in ranks:
        for rank, i in enumerate(ranking, 1):
            fused[i] += 1 / (60 + rank)
    chosen = sorted(fused, key=lambda i: (-fused[i], i))[:top_k]
    order = {u["utterance_id"]: i for i, u in enumerate(items)}
    visible: set[int] = set()
    candidates, size, truncated = [], 0, False
    for i in chosen:
        chunk = chunks[i]
        first, last = order[chunk["utterance_ids"][0]], order[chunk["utterance_ids"][-1]]
        positions = set(range(max(0, first - context_before), min(len(items), last + context_after + 1)))
        extra = sum(len(items[p]["text"]) for p in positions - visible)
        if size + extra > max_visible_chars:
            truncated = True
            continue
        visible.update(positions)
        size += extra
        candidates.append({"chunk_id": chunk["chunk_id"], "utterance_ids": chunk["utterance_ids"],
                           "rank_score": fused[i]})
    return {"queries": queries, "candidates": candidates,
            "utterances": [items[i] for i in sorted(visible)], "context_truncated": truncated}
