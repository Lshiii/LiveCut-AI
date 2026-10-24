package io.livecut.review;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ReviewModels {
    private ReviewModels() {}

    public record Product(String id, String name,
                          @JsonProperty("identity_source") String identitySource) {}

    public record Segment(String id, @JsonProperty("start_ms") long startMs,
                          @JsonProperty("end_ms") long endMs,
                          @JsonProperty("product_id") String productId, boolean enabled,
                          String origin, @JsonProperty("boundary_source") String boundarySource,
                          @JsonProperty("identity_source") String identitySource,
                          @JsonProperty("original_prediction_id") String originalPredictionId) {}

    public record Utterance(@JsonProperty("utterance_id") String utteranceId,
                            @JsonProperty("start_ms") long startMs,
                            @JsonProperty("end_ms") long endMs, String text,
                            @JsonProperty("source_chunk_id") String sourceChunkId) {}

    /** Deliberately excludes frame/object keys and private worker references. */
    public record Prediction(String id, @JsonProperty("content_kind") String contentKind,
                             @JsonProperty("identity_status") String identityStatus,
                             @JsonProperty("visual_status") String visualStatus,
                             @JsonProperty("review_reasons") List<String> reviewReasons,
                             @JsonProperty("audio_evidence_utterance_ids") List<String> audioEvidenceUtteranceIds) {}

    public record Snapshot(@JsonProperty("job_id") String jobId,
                           @JsonProperty("video_id") String videoId, int generation,
                           @JsonProperty("data_kind") String dataKind,
                           @JsonProperty("duration_ms") long durationMs, long revision, String status,
                           @JsonProperty("source_hash") String sourceHash,
                           @JsonProperty("confirmed_revisions") List<Long> confirmedRevisions,
                           List<Product> products, List<Segment> segments,
                           List<Utterance> utterances, List<Prediction> predictions) {}
}
