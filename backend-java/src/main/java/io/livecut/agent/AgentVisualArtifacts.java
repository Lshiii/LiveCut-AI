package io.livecut.agent;

import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Binds sampled evidence to a fenced manifest and actual bounded JPEG bytes. */
public final class AgentVisualArtifacts {
    private AgentVisualArtifacts() {}
    public static Map<String,byte[]> verify(JdbcTemplate jdbc,ArtifactVerifier artifacts,Map<String,Object> job,
            Map<String,Object> owner,JsonNode answer,ArtifactVerifier.VerifiedManifest manifest,ArtifactVerifier.ManifestIdentity identity){
        if(!"frames".equals(owner.get("visual_mode"))||!AgentFocus.stage(owner).equals(identity.stageType()))throw invalid();
        var mediaStages=jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type='MEDIA' AND status='SUCCEEDED' FOR UPDATE",job.get("id"));
        if(mediaStages.size()!=1)throw invalid();var mediaStage=mediaStages.getFirst();
        var mediaIdentity=new ArtifactVerifier.ManifestIdentity(identity.jobId(),identity.videoId(),identity.generation(),String.valueOf(mediaStage.get("id")),"MEDIA",((Number)mediaStage.get("attempt")).intValue(),String.valueOf(mediaStage.get("run_token")),identity.sourceHash());
        var mediaManifest=artifacts.verifyManifest(String.valueOf(mediaStage.get("manifest_key")),String.valueOf(mediaStage.get("manifest_hash")),mediaIdentity);
        var info=mediaManifest.artifacts().stream().filter(a->a.kind().equals("media_info")).findFirst().orElseThrow(AgentVisualArtifacts::invalid);
        JsonNode media=artifacts.readJsonArtifact(info,mediaIdentity);long width=AgentAnswerValidator.number(media,"width"),height=AgentAnswerValidator.number(media,"height");
        if(width<=0||height<=0||AgentAnswerValidator.number(media,"duration_ms")!=((Number)owner.get("duration_ms")).longValue())throw invalid();
        List<String> startFields=new ArrayList<>(List.of("format_start_time_seconds","video_start_time_seconds"));if(media.path("audio_codec").isString())startFields.add("audio_start_time_seconds");
        for(String field:startFields){
            JsonNode start=media.path(field);if(!start.isNumber()||!Double.isFinite(start.doubleValue())||Math.abs(start.doubleValue())>0.000001)throw invalid();
        }
        JsonNode angle=media.path("rotation_degrees");if(!angle.isIntegralNumber()||!angle.canConvertToInt())throw invalid();int rotation=Math.floorMod(angle.intValue(),360);if(rotation%90!=0)throw invalid();
        if(rotation==90||rotation==270){long swap=width;width=height;height=swap;}
        Map<String,ArtifactVerifier.VerifiedArtifact> refs=new HashMap<>();
        boolean replay=AgentResponseReplayData.isReplay(owner);
        Set<String> allowed=replay?Set.of("agent_answer","agent_visual_frame","agent_cached_answer_replay"):Set.of("agent_answer","agent_visual_frame","text_execution_trace");
        if(replay&&manifest.artifacts().stream().filter(file->file.kind().equals("agent_cached_answer_replay")).count()!=1)throw invalid();
        for(var artifact:manifest.artifacts()){
            if(!allowed.contains(artifact.kind()))throw invalid();
            if(artifact.kind().equals("agent_visual_frame")){
                if(refs.put(artifact.key(),artifact)!=null)throw invalid();
            }else if(artifact.key().startsWith(identity.attemptPrefix()+"visual-frames/"))throw invalid();
        }
        Map<String,byte[]> result=new LinkedHashMap<>();
        for(JsonNode frame:answer.path("visual_evidence")){
            String id=frame.path("frame_id").asText(),key=identity.attemptPrefix()+"visual-frames/"+id+".jpg";var ref=refs.remove(key);
            if(ref==null||!ref.sha256().equals(frame.path("sha256").asText())||ref.sizeBytes()!=frame.path("bytes").longValue()
                    ||frame.path("width").longValue()>width||frame.path("height").longValue()>height)throw invalid();
            byte[] bytes=artifacts.readVisualFrame(ref,identity);
            if(bytes.length!=frame.path("bytes").longValue()||bytes.length<4||(bytes[0]&255)!=255||(bytes[1]&255)!=216
                    ||(bytes[bytes.length-2]&255)!=255||(bytes[bytes.length-1]&255)!=217)throw invalid();
            try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
                Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid();ImageReader reader=readers.next();
                try{reader.addIIOReadWarningListener((source,warning)->{throw invalid();});reader.setInput(input,true,true);if(!reader.getFormatName().equalsIgnoreCase("JPEG")||reader.getWidth(0)!=frame.path("width").intValue()||reader.getHeight(0)!=frame.path("height").intValue())throw invalid();
                    // The dimensions were bounded before decode; reject truncated/corrupt JPEG payloads.
                    if(reader.read(0)==null)throw invalid();
                }finally{reader.dispose();}
            }catch(JobException failure){throw failure;}catch(Exception failure){throw invalid();}
            result.put(id,bytes);
        }
        if(!refs.isEmpty())throw invalid();return result;
    }
    private static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"AGENT_VISUAL_EVIDENCE_INVALID","Visual evidence no longer matches its source and fenced JPEG manifest");}
}
