package io.livecut.agent;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/** Caption-only snapshots deliberately do not pass through the video conversation API. */
public final class CaptionProofreadModels {
    private CaptionProofreadModels() {}
    public record Source(@JsonProperty("video_sha256") String videoSha256,
            @JsonProperty("transcript_hash") String transcriptHash,@JsonProperty("duration_ms") long durationMs,
            @JsonProperty("export_id") String exportId,@JsonProperty("clip_id") String clipId,
            @JsonProperty("caption_revision") long captionRevision,@JsonProperty("caption_content_hash") String captionContentHash,
            @JsonProperty("caption_snapshot_hash") String captionSnapshotHash,@JsonProperty("caption_source") JsonNode captionSource,
            @JsonProperty("total_cues") int totalCues) {}
    public record Batch(int offset,int count,@JsonProperty("total_cues") int totalCues,
            @JsonProperty("next_offset") Integer nextOffset,@JsonProperty("cue_ids") List<String> cueIds) {
        public Batch { cueIds=List.copyOf(cueIds); }
    }
    public record Turn(String id,@JsonProperty("session_id") String sessionId,@JsonProperty("job_id") String jobId,
            String question,int sequence,String status,@JsonProperty("state_version") long stateVersion,
            @JsonProperty("created_at") Instant createdAt,@JsonProperty("updated_at") Instant updatedAt,
            AgentModels.Budget budget,AgentModels.Stage stage,Batch batch,JsonNode result,List<Evidence> evidence) {
        public Turn { evidence=List.copyOf(evidence); }
    }
    public record Evidence(@JsonProperty("utterance_id") String utteranceId,@JsonProperty("start_ms") long startMs,
            @JsonProperty("end_ms") long endMs,String text) {}
    public record Session(String id,@JsonProperty("job_id") String jobId,@JsonProperty("video_id") String videoId,
            @JsonProperty("created_at") Instant createdAt,Source source,@JsonProperty("turns_total") int turnsTotal,
            @JsonProperty("turns_offset") int turnsOffset,List<Turn> turns) {
        public Session { turns=List.copyOf(turns); }
    }
    public record Page(List<Session> items) { public Page { items=List.copyOf(items); } }
    public record TurnPage(List<Turn> items,int total,int limit,int offset) { public TurnPage { items=List.copyOf(items); } }
}
