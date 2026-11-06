package io.livecut.captions;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.livecut.jobs.ArtifactVerifier.SourcePart;

/** A caption revision is separate from source ASR and from the immutable MP4 export. */
public final class CaptionModels {
    private CaptionModels() {}
    public record Source(@JsonProperty("snapshot_hash") String snapshotHash,
                         @JsonProperty("clip_sha256") String clipSha256,
                         @JsonProperty("transcript_hash") String transcriptHash,
                         @JsonProperty("duration_ms") long durationMs,
                         @JsonProperty("media_duration_ms") long mediaDurationMs,
                         @JsonProperty("timing_basis") String timingBasis,List<SourcePart> parts) {
        public Source { parts=List.copyOf(parts); }
    }
    public record Cue(String id,@JsonProperty("part_index") int partIndex,
                      @JsonProperty("source_utterance_id") String sourceUtteranceId,
                      @JsonProperty("source_start_ms") long sourceStartMs,@JsonProperty("source_end_ms") long sourceEndMs,
                      @JsonProperty("retained_start_ms") long retainedStartMs,@JsonProperty("retained_end_ms") long retainedEndMs,
                      @JsonProperty("start_ms") long startMs,@JsonProperty("end_ms") long endMs,
                      String text,@JsonProperty("source_text") String sourceText,
                      @JsonProperty("boundary_partial") boolean boundaryPartial,boolean reviewed,
                      @JsonProperty("requires_review") boolean requiresReview) {}
    public record Content(List<Cue> cues) { public Content { cues=List.copyOf(cues); } }
    public record Snapshot(@JsonProperty("export_id") String exportId,@JsonProperty("clip_id") String clipId,
                           @JsonProperty("clip_title") String clipTitle,@JsonProperty("content_url") String contentUrl,
                           long revision,String status,@JsonProperty("confirmed_revisions") List<Long> confirmedRevisions,
                           @JsonProperty("content_hash") String contentHash,Source source,List<Cue> cues) {
        public Snapshot { confirmedRevisions=List.copyOf(confirmedRevisions);cues=List.copyOf(cues); }
    }
    static Cue original(CaptionMapper.MappingCue source){
        return new Cue(source.id(),source.partIndex(),source.utteranceId(),source.sourceStartMs(),source.sourceEndMs(),
            source.retainedStartMs(),source.retainedEndMs(),source.startMs(),source.endMs(),source.sourceText(),source.sourceText(),
            source.boundaryPartial(),false,source.boundaryPartial());
    }
    static Cue edited(Cue source,long start,long end,String text,boolean reviewed){
        return new Cue(source.id(),source.partIndex(),source.sourceUtteranceId(),source.sourceStartMs(),source.sourceEndMs(),
            source.retainedStartMs(),source.retainedEndMs(),start,end,text,source.sourceText(),source.boundaryPartial(),reviewed,
            source.boundaryPartial()&&!reviewed);
    }
}
