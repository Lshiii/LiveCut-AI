package io.livecut.exports;

import java.util.List;
import io.livecut.jobs.ArtifactVerifier.SourcePart;

/** Internal admitted identities. No local paths or worker credentials enter the ZIP manifest. */
public final class ExportVideoModels {
    private ExportVideoModels() {}
    public record Plan(String exportId,String jobId,String videoId,String sourceVideoHash,String snapshotHash,
                       long approvedRevision,String profile,String clipRequestId,List<Clip> clips,String manualClipPlanId) {
        public Plan { clips=List.copyOf(clips); }
        public Plan(String exportId,String jobId,String videoId,String sourceVideoHash,String snapshotHash,long approvedRevision,String profile,String clipRequestId,List<Clip> clips){this(exportId,jobId,videoId,sourceVideoHash,snapshotHash,approvedRevision,profile,clipRequestId,clips,null);}
    }
    public record Clip(String clipId,String segmentId,String title,long startMs,long endMs,long durationMs,
                       List<SourcePart> parts,long sizeBytes,String contentHash,Execution execution) {
        public Clip { parts=List.copyOf(parts); }
    }
    public record Execution(String stageId,int attempt,String runToken,String inputHash,String manifestKey,String manifestHash) {}
}
