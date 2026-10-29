package io.livecut.topics;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.livecut.review.ReviewModels.Utterance;

public final class TopicModels {
    private TopicModels() {}
    public record Segment(String id, @JsonProperty("start_ms") long startMs,
            @JsonProperty("end_ms") long endMs, String title, String summary, List<String> keywords,
            boolean enabled, String origin, @JsonProperty("boundary_source") String boundarySource,
            @JsonProperty("content_source") String contentSource,
            @JsonProperty("original_prediction_id") String originalPredictionId) {}
    public record Content(List<Segment> segments) {}
    public record Snapshot(@JsonProperty("job_id") String jobId, @JsonProperty("video_id") String videoId,
            int generation, @JsonProperty("analysis_mode") String analysisMode,
            @JsonProperty("data_kind") String dataKind, @JsonProperty("duration_ms") long durationMs,
            long revision, String status, @JsonProperty("source_hash") String sourceHash,
            @JsonProperty("confirmed_revisions") List<Long> confirmedRevisions,
            List<Segment> segments, List<Utterance> utterances) {}
    public record Confirmed(@JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("data_kind") String dataKind, @JsonProperty("analysis_mode") String analysisMode,
            @JsonProperty("job_id") String jobId, @JsonProperty("video_id") String videoId,
            @JsonProperty("analysis_generation") int generation, long revision,
            @JsonProperty("review_status") String reviewStatus, @JsonProperty("confirmed_at") String confirmedAt,
            @JsonProperty("duration_ms") long durationMs,
            @JsonProperty("source_video_sha256") String sourceVideoSha256,
            @JsonProperty("prediction_source_sha256") String predictionSourceSha256, List<Segment> segments) {}
}
