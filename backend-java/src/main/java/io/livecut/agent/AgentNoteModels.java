package io.livecut.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** A detached verified snapshot; no private artifact paths or execution credentials. */
public final class AgentNoteModels {
    private AgentNoteModels() {}

    public record Snapshot(String turnId,String sessionId,String jobId,String videoId,
                           String sourceHash,String transcriptHash,String resultHash,
                           String sourceFilename,long durationMs,JsonNode result,Map<String,byte[]> images,
                           String replaySourceTurnId,String replaySnapshotHash) {
        public Snapshot(String turnId,String sessionId,String jobId,String videoId,String sourceHash,String transcriptHash,String resultHash,String sourceFilename,long durationMs,JsonNode result,Map<String,byte[]> images){this(turnId,sessionId,jobId,videoId,sourceHash,transcriptHash,resultHash,sourceFilename,durationMs,result,images,null,null);}
        public Snapshot { result=result==null?null:result.deepCopy();images=copy(images); }
        @Override public JsonNode result(){return result==null?null:result.deepCopy();}
        @Override public Map<String,byte[]> images(){return copy(images);}
    }
    public record Document(String markdown,String packageMarkdown,byte[] sourceJson) {
        public Document { sourceJson=sourceJson==null?null:sourceJson.clone(); }
        @Override public byte[] sourceJson(){return sourceJson==null?null:sourceJson.clone();}
    }
    public record Preview(@JsonProperty("schema_version") int schemaVersion,
                          @JsonProperty("turn_id") String turnId,@JsonProperty("session_id") String sessionId,
                          @JsonProperty("job_id") String jobId,@JsonProperty("video_id") String videoId,
                          @JsonProperty("source_hash") String sourceHash,@JsonProperty("transcript_hash") String transcriptHash,
                          @JsonProperty("result_hash") String resultHash,@JsonProperty("source_filename") String sourceFilename,
                          @JsonProperty("answer_scope") String answerScope,@JsonProperty("citation_count") int citationCount,
                          @JsonProperty("image_count") int imageCount,String markdown,
                          @JsonProperty("download_filename") String downloadFilename) {}
    public record Download(String turnId,String resultHash,String filename,byte[] bytes) {
        public Download { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
    }
    private static Map<String,byte[]> copy(Map<String,byte[]> images){
        if(images==null)return null;Map<String,byte[]> result=new LinkedHashMap<>();
        images.forEach((id,bytes)->result.put(id,bytes==null?null:bytes.clone()));
        return Collections.unmodifiableMap(result);
    }
}
