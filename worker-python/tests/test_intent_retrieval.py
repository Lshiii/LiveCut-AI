"""Synthetic retrieval/index integrity cases, not relevance benchmark claims."""
import json
import math

import pytest
from livecut.intent_retrieval import build_index, retrieve
from livecut.segmentation import SegmentationError


def source():
    return [{"utterance_id": f"u{i}", "start_ms": i * 10000, "end_ms": i * 10000 + 9000,
             "text": text, "source_chunk_id": "audio-0"} for i, text in enumerate([
                 "讲解开始，先介绍问题。", "修改历史消息造成缓存失效。", "这是因果解释的结论。",
                 "现在转到解决方法。", "保持前缀并追加消息。", "最后总结。"])]


def index(tmp_path, **kwargs):
    return build_index(source(), source_hash="a" * 64, transcript_hash="b" * 64,
                       cache_dir=tmp_path, max_items=1, **kwargs)


def test_lexical_ranking_expands_whole_sentence_context_in_source_order(tmp_path):
    data = index(tmp_path)
    result = retrieve(data, ["缓存失效"], source(), top_k=1)
    assert result["candidates"][0]["utterance_ids"] == ["u1"]
    assert [u["utterance_id"] for u in result["utterances"]] == ["u0", "u1", "u2", "u3"]
    assert data["retrieval_mode"] == "bm25" and data["vectors"] is None


def test_no_keyword_match_does_not_manufacture_lexical_candidates(tmp_path):
    result = retrieve(index(tmp_path), ["火星天气"], source(), top_k=1)
    assert not result["candidates"] and not result["utterances"]


def test_dense_queries_and_documents_are_both_encoded_and_index_is_reused(tmp_path):
    calls = []
    def encoder(texts):
        calls.append(list(texts))
        return [[1.0, 0.0] if "保持" in text or "稳定" in text else [0.0, 1.0] for text in texts]
    data = index(tmp_path, dense_embedder=encoder, embedding_model_id="synthetic-encoder-v1")
    assert len(calls) == 1 and len(calls[0]) == len(source())
    again = index(tmp_path, dense_embedder=encoder, embedding_model_id="synthetic-encoder-v1")
    assert data == again and len(calls) == 1
    result = retrieve(data, ["稳定"], source(), dense_embedder=encoder, top_k=1)
    assert calls[-1] == ["稳定"]
    assert result["candidates"][0]["utterance_ids"] == ["u4"]
    assert data["retrieval_mode"] == "bm25_dense_rrf"


def test_index_identity_changes_for_source_transcript_chunking_or_encoder(tmp_path):
    first = index(tmp_path)
    changed = build_index(source(), source_hash="c" * 64, transcript_hash="b" * 64, cache_dir=tmp_path, max_items=1)
    transcript = build_index(source(), source_hash="a" * 64, transcript_hash="d" * 64, cache_dir=tmp_path, max_items=1)
    chunk = build_index(source(), source_hash="a" * 64, transcript_hash="b" * 64, cache_dir=tmp_path, max_items=2)
    assert len({x["index_hash"] for x in [first, changed, transcript, chunk]}) == 4


def test_finite_but_tampered_vectors_fail_content_integrity_check(tmp_path):
    def encoder(texts):
        return [[1.0, 0.0] for _ in texts]
    data = index(tmp_path, dense_embedder=encoder, embedding_model_id="synthetic-v1")
    path = tmp_path / ("index-" + data["index_hash"] + ".json")
    altered = json.loads(path.read_text())
    altered["vectors"][0] = [0.0, 1.0]
    path.write_text(json.dumps(altered))
    with pytest.raises(SegmentationError) as error:
        index(tmp_path, dense_embedder=encoder, embedding_model_id="synthetic-v1")
    assert error.value.code == "INVALID_RETRIEVAL_INDEX"


@pytest.mark.parametrize("vectors", [[[math.nan, 1.0]], [[0.0, 0.0]], [["1", 0.0]], [[True, 1.0]], [[1.0], [1.0, 2.0]]])
def test_invalid_dense_vectors_are_rejected_without_lexical_fallback(tmp_path, vectors):
    with pytest.raises(SegmentationError) as error:
        index(tmp_path, dense_embedder=lambda texts: vectors, embedding_model_id="invalid-v1")
    assert error.value.code == "INVALID_EMBEDDINGS"


def test_dense_encoder_identity_cannot_be_claimed_without_encoder(tmp_path):
    with pytest.raises(SegmentationError):
        index(tmp_path, embedding_model_id="missing-v1")
    with pytest.raises(SegmentationError):
        index(tmp_path, dense_embedder=lambda texts: [[1.0] for _ in texts])


def test_empty_dense_index_can_be_cached_and_replayed(tmp_path):
    for _ in range(2):
        result = build_index([], source_hash="a" * 64, transcript_hash="b" * 64, cache_dir=tmp_path,
                             dense_embedder=lambda texts: pytest.fail("no empty inference"), embedding_model_id="synthetic-v1")
        assert result["vectors"] == []


def test_long_source_sentence_tail_is_encoded_without_splitting_its_asr_id(tmp_path):
    item = {**source()[0], "text": "甲" * 850 + "结尾关键解释"}
    calls = []
    def encoder(texts):
        calls.extend(texts)
        return [[1.0, 1.0] for _ in texts]
    data = build_index([item], source_hash="a" * 64, transcript_hash="b" * 64, cache_dir=tmp_path,
                       dense_embedder=encoder, embedding_model_id="bounded-synthetic-v1")
    assert len(calls) == 3 and max(map(len, calls)) <= 400
    assert "".join(calls) == item["text"] and calls[-1].endswith("结尾关键解释")
    assert data["chunks"][0]["utterance_ids"] == [item["utterance_id"]]
