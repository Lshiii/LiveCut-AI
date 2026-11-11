package io.livecut.transcripts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Public original-ASR projection; storage references and execution credentials stay private. */
public final class TranscriptModels {
    private TranscriptModels() {}
    public record Source(@JsonProperty("job_id") String jobId, @JsonProperty("video_id") String videoId,
            int generation, @JsonProperty("source_video_sha256") String sourceVideoSha256,
            @JsonProperty("transcript_sha256") String transcriptSha256, @JsonProperty("analysis_sha256") String analysisSha256,
            @JsonProperty("duration_ms") long durationMs, @JsonProperty("source_fingerprint") String sourceFingerprint) {}
    public record Utterance(@JsonProperty("utterance_id") String utteranceId, @JsonProperty("start_ms") long startMs,
            @JsonProperty("end_ms") long endMs, String text) {}
    public record Page(@JsonProperty("schema_version") String schemaVersion, @JsonProperty("data_kind") String dataKind,
            Source source, String q, long offset, int limit, @JsonProperty("total_utterances") int totalUtterances,
            @JsonProperty("matched_utterances") int matchedUtterances, @JsonProperty("next_offset") Long nextOffset,
            List<Utterance> items) {
        public Page { items=List.copyOf(items); }
    }
    public record Context(@JsonProperty("schema_version") String schemaVersion, @JsonProperty("data_kind") String dataKind,
            Source source, Utterance center, Utterance previous, Utterance next) {}
    public record Range(@JsonProperty("start_ms") long startMs,@JsonProperty("end_ms") long endMs,
            @JsonProperty("utterance_count") int utteranceCount,@JsonProperty("start_text") String startText,
            @JsonProperty("end_text") String endText) {}
    public record RangeResult(@JsonProperty("schema_version") String schemaVersion,@JsonProperty("data_kind") String dataKind,
            Source source,@JsonProperty("start_utterance_id") String startUtteranceId,
            @JsonProperty("end_utterance_id") String endUtteranceId,Range range) {}
}
