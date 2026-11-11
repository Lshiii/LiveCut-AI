package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Reads only published same-generation OCR. No cache, review initialization or model runtime. */
public final class AgentScreenSource {
    private AgentScreenSource() {}
    private static final int MIB=1024*1024;
    private static final Map<String,String> FIXED=Map.of("analysis_result","analysis-result.json","ocr_state","ocr/ocr-state.json","ocr_frames","ocr/frames.json","ocr_observations","ocr/observations.json","ocr_cache_provenance","ocr/cache-provenance.json","text_execution_trace","text-execution-trace.json");
    private record Metadata(Map<String,Object> job,Map<String,Object> segment,Map<String,Object> timeline,String identity) {}
    public record Prepared(JsonNode context,JsonNode rows,String metadataIdentity) {
        public JsonNode publicSource(){return context.path("screen_source").deepCopy();}
        public void assertCurrent(JdbcTemplate jdbc){if(!metadataIdentity.equals(metadata(jdbc,context.path("segment").path("job_id").asText()).identity()))throw invalid();}
    }
    public static Prepared prepare(JdbcTemplate jdbc,ArtifactVerifier artifacts,String jobId){
        Metadata before=metadata(jdbc,jobId);
        try{
            var job=before.job();String hash=String.valueOf(job.get("source_hash"));var segmentIdentity=identity(before.segment(),job);
            var manifest=artifacts.verifyManifest(String.valueOf(before.segment().get("manifest_key")),String.valueOf(before.segment().get("manifest_hash")),segmentIdentity);
            Map<String,ArtifactVerifier.VerifiedArtifact> refs=new LinkedHashMap<>();manifest.artifacts().forEach(ref->refs.put(ref.kind(),ref));
            long present=List.of("ocr_state","ocr_frames","ocr_observations","ocr_cache_provenance").stream().filter(refs::containsKey).count();if(present==0)throw unavailable();if(present!=4)throw invalid();
            var timelineIdentity=identity(before.timeline(),job);var timelineManifest=artifacts.verifyManifest(String.valueOf(before.timeline().get("manifest_key")),String.valueOf(before.timeline().get("manifest_hash")),timelineIdentity);
            var analysis=timelineManifest.artifacts().stream().filter(ref->ref.kind().equals("analysis_result")).findFirst().orElseThrow(AgentScreenSource::invalid);
            JsonNode segment=artifacts.readJsonArtifact(refs.get("analysis_result"),segmentIdentity),timeline=artifacts.readJsonArtifact(analysis,timelineIdentity);
            if(!segment.equals(timeline)||!"topics".equals(timeline.path("analysis_mode").asText())||!jobId.equals(timeline.path("job_id").asText())||!job.get("video_id").equals(timeline.path("video_id").asText())||number(timeline,"analysis_generation")!=((Number)job.get("generation")).longValue())throw invalid();
            long duration=AgentScreenSearch.safe(timeline,"duration_ms");JsonNode config=JSON.readTree(String.valueOf(job.get("config_json")));
            if(config.has("ocr_sampling")&&!config.path("ocr_sampling").isString()||config.has("ocr_max_frames")&&(!config.path("ocr_max_frames").isIntegralNumber()||!config.path("ocr_max_frames").canConvertToInt()))throw invalid();
            String sampling=config.has("ocr_sampling")?config.path("ocr_sampling").asText():"prefix";int maximum=config.has("ocr_max_frames")?config.path("ocr_max_frames").intValue():16;
            if(!Set.of("prefix","uniform").contains(sampling)||maximum<1||maximum>120)throw invalid();
            JsonNode state=read(artifacts,refs.get("ocr_state"),segmentIdentity),frames=read(artifacts,refs.get("ocr_frames"),segmentIdentity),observations=read(artifacts,refs.get("ocr_observations"),segmentIdentity),provenance=read(artifacts,refs.get("ocr_cache_provenance"),segmentIdentity);
            JsonNode rows=validateEvidence(state,frames,observations,provenance,hash,duration,sampling,maximum);
            var resultFrames=JSON.createArrayNode();
            for(int index=0;index<rows.size();index++){
                JsonNode frame=frames.get(index),row=rows.get(index);String kind=String.format(Locale.ROOT,"ocr_frame_%06d",index+1);var ref=refs.get(kind);
                if(ref==null||!ref.key().equals(segmentIdentity.attemptPrefix()+"ocr/"+frame.path("object_key").asText())||!ref.sha256().equals(row.path("ocr_sha256").asText())||ref.sizeBytes()<4||ref.sizeBytes()>4L*MIB)throw invalid();
                byte[] image=artifacts.readReplayFile(ref.key(),segmentIdentity.attemptPrefix(),4*MIB);if(!digest(image).equals(ref.sha256())||(image[0]&255)!=255||(image[1]&255)!=216||(image[image.length-2]&255)!=255||(image[image.length-1]&255)!=217)throw invalid();
                resultFrames.addObject().put("frame_id",row.path("ocr_frame_id").asText()).put("timestamp_ms",row.path("ocr_timestamp_ms").longValue()).put("object_key",ref.key());
            }
            if(!AgentScreenSearch.same(timeline.path("frames"),resultFrames)||refs.size()!=5+rows.size()+(refs.containsKey("text_execution_trace")?1:0))throw invalid();
            for(var ref:refs.values()){String fixed=FIXED.get(ref.kind());if(fixed!=null&&!ref.key().equals(segmentIdentity.attemptPrefix()+fixed))throw invalid();}
            var publicSource=JSON.createObjectNode().put("segment_stage_id",segmentIdentity.stageId()).put("segment_manifest_hash",String.valueOf(before.segment().get("manifest_hash")))
                .put("frames_hash",refs.get("ocr_frames").sha256()).put("observations_hash",refs.get("ocr_observations").sha256()).put("index_version",AgentScreenSearch.INDEX).put("duration_ms",duration)
                .put("sampling_policy",sampling).put("interval_ms",45000).put("frame_count",rows.size()).put("text_frame_count",(int)java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(row->AgentScreenSearch.hasText(row.path("observed_text").asText())).count()).put("coverage_truncated",state.path("coverage_truncated").booleanValue());
            publicSource.put("index_hash",AgentScreenSearch.indexHash(rows,hash,publicSource));
            var descriptor=JSON.createObjectNode().put("schema_version",1);var s=descriptor.putObject("segment");s.put("job_id",jobId).put("video_id",segmentIdentity.videoId()).put("analysis_generation",segmentIdentity.generation()).put("stage_id",segmentIdentity.stageId()).put("stage_type","SEGMENT").put("attempt",segmentIdentity.attempt()).put("run_token",segmentIdentity.runToken()).put("input_sha256",hash).put("manifest_key",String.valueOf(before.segment().get("manifest_key"))).put("manifest_hash",String.valueOf(before.segment().get("manifest_hash")));
            descriptor.putObject("analysis").put("key",analysis.key()).put("sha256",analysis.sha256());descriptor.putObject("ocr_config").put("sampling_policy",sampling).put("max_frames",maximum).put("interval_ms",45000);descriptor.set("screen_source",publicSource);
            if(!before.identity().equals(metadata(jdbc,jobId).identity()))throw invalid();return new Prepared(descriptor,rows,before.identity());
        }catch(JobException failure){if(Set.of("AGENT_SCREEN_SOURCE_INVALID","AGENT_SCREEN_SOURCE_UNAVAILABLE").contains(failure.getCode()))throw failure;throw invalid();}catch(RuntimeException failure){throw invalid();}
    }
    public static Prepared verify(JdbcTemplate jdbc,ArtifactVerifier artifacts,Map<String,Object> owner){
        JsonNode stored=AgentScreenSearch.context(owner);Prepared prepared=prepare(jdbc,artifacts,String.valueOf(owner.get("job_id")));
        if(!AgentScreenSearch.same(stored,prepared.context())||!stored.path("analysis").path("key").asText().equals(owner.get("analysis_key"))||!stored.path("analysis").path("sha256").asText().equals(owner.get("analysis_hash"))||!stored.path("segment").path("video_id").asText().equals(owner.get("video_id"))||!stored.path("segment").path("input_sha256").asText().equals(owner.get("source_hash")))throw invalid();return prepared;
    }
    private static Metadata metadata(JdbcTemplate jdbc,String id){
        var jobs=jdbc.queryForList("SELECT j.id,j.video_id,j.generation,j.status,j.config_json,v.content_hash AS source_hash FROM analysis_job j JOIN video_asset v ON v.id=j.video_id WHERE j.id=?",id);if(jobs.size()!=1)throw unavailable();var job=jobs.getFirst();JsonNode config=JSON.readTree(String.valueOf(job.get("config_json")));
        if(!"REVIEW_READY".equals(job.get("status"))||!"topics".equals(config.path("analysis_mode").asText())||!"ocr".equals(config.path("visual_provider").asText()))throw unavailable();
        var stages=jdbc.queryForList("SELECT id,job_id,owner_type,owner_id,stage_type,status,attempt,run_token,input_hash,manifest_key,manifest_hash FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type IN ('SEGMENT','TIMELINE') ORDER BY stage_type,id",id);
        Map<String,Object> segment=null,timeline=null;for(var stage:stages){if(!"SUCCEEDED".equals(stage.get("status")))throw unavailable();if(!id.equals(stage.get("owner_id")))throw invalid();if("SEGMENT".equals(stage.get("stage_type"))){if(segment!=null)throw invalid();segment=stage;}else{if(timeline!=null)throw invalid();timeline=stage;}}
        if(segment==null||timeline==null)throw unavailable();return new Metadata(job,segment,timeline,AgentScreenSearch.canonical(JSON.valueToTree(Map.of("job",job,"stages",stages))));
    }
    private static ArtifactVerifier.ManifestIdentity identity(Map<String,Object> stage,Map<String,Object> job){return new ArtifactVerifier.ManifestIdentity(String.valueOf(job.get("id")),String.valueOf(job.get("video_id")),((Number)job.get("generation")).intValue(),String.valueOf(stage.get("id")),String.valueOf(stage.get("stage_type")),((Number)stage.get("attempt")).intValue(),String.valueOf(stage.get("run_token")),String.valueOf(job.get("source_hash")));}
    private static JsonNode read(ArtifactVerifier verifier,ArtifactVerifier.VerifiedArtifact ref,ArtifactVerifier.ManifestIdentity identity){if(ref==null||ref.sizeBytes()>MIB)throw invalid();byte[] bytes=verifier.readReplayFile(ref.key(),identity.attemptPrefix(),MIB);if(!digest(bytes).equals(ref.sha256()))throw invalid();return verifier.readJsonArtifact(ref,identity);}
    private static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    /** Pure typed validation is shared by fixture tests; all file reads are separately bound above. */
    public static JsonNode validateEvidence(JsonNode state,JsonNode frames,JsonNode observations,JsonNode provenance,String hash,long duration,String sampling,int maximum){
        if(duration<=0||duration>AgentVisualEvidence.SAFE_INTEGER||!Set.of("prefix","uniform").contains(sampling)||maximum<1||maximum>120)throw invalid();
        fields(state,Set.of("input","frames","observations","coverage_truncated"));var expected=JSON.createObjectNode().put("source_sha256",hash).put("duration_ms",duration).put("interval_ms",45000).put("max_frames",maximum).put("model_id",text(state.path("input"),"model_id",128));if(sampling.equals("uniform"))expected.put("sampling_policy","uniform");
        long count=Math.min(maximum,(duration+44999)/45000);if(sampling.equals("uniform"))count=Math.min(count,duration);
        boolean truncated=sampling.equals("prefix")&&(duration+44999)/45000>maximum;
        if(duration<=0||count<1||count>120||!AgentScreenSearch.same(state.path("input"),expected)||!AgentScreenSearch.same(state.path("frames"),frames)||!AgentScreenSearch.same(state.path("observations"),observations)||!frames.isArray()||frames.size()!=count||!observations.isArray()||observations.size()!=count||!state.path("coverage_truncated").isBoolean()||state.path("coverage_truncated").booleanValue()!=truncated)throw invalid();
        var provenanceFields=new HashSet<>(Set.of("schema_version","cache_key","cache_hit","binding_sha256","bundle_manifest_sha256","frame_count","coverage_truncated","observer_id"));if(sampling.equals("uniform"))provenanceFields.add("sampling_policy");fields(provenance,provenanceFields);
        if(number(provenance,"schema_version")!=1||!provenance.path("cache_hit").isBoolean()||number(provenance,"frame_count")!=count||!provenance.path("coverage_truncated").isBoolean()||provenance.path("coverage_truncated").booleanValue()!=truncated||!provenance.path("observer_id").equals(expected.path("model_id"))||sampling.equals("uniform")&&!"uniform".equals(provenance.path("sampling_policy").asText()))throw invalid();
        for(String field:List.of("cache_key","binding_sha256","bundle_manifest_sha256"))if(!provenance.path(field).isString()||!provenance.path(field).asText().matches("[a-f0-9]{64}"))throw invalid();if(!provenance.path("cache_key").equals(provenance.path("binding_sha256")))throw invalid();
        var rows=JSON.createArrayNode();for(int i=0;i<count;i++){
            JsonNode frame=frames.get(i),observation=observations.get(i);fields(frame,Set.of("frame_id","requested_timestamp_ms","timestamp_ms","source_pts","source_time_base","object_key","sha256"));fields(observation,Set.of("frame_id","timestamp_ms","object_key","presented_category","observed_text","visibility"));
            String id=String.format(Locale.ROOT,"ocr_%06d",i+1),key="frames/"+id+".jpg";long requested=sampling.equals("prefix")?i*45000L:count==1?0:(2L*i+1)*duration/(2*count);long actual=AgentScreenSearch.safe(frame,"timestamp_ms"),pts=AgentScreenSearch.safe(frame,"source_pts");
            if(!id.equals(frame.path("frame_id").asText())||!id.equals(observation.path("frame_id").asText())||!key.equals(frame.path("object_key").asText())||!key.equals(observation.path("object_key").asText())||AgentScreenSearch.safe(frame,"requested_timestamp_ms")!=requested||actual>=duration||actual+1<requested||number(observation,"timestamp_ms")!=actual||!observation.path("presented_category").isNull()||!Set.of("clear","not_visible").contains(observation.path("visibility").asText())||!observation.path("observed_text").isString())throw invalid();
            String body=observation.path("observed_text").asText();if(body.codePointCount(0,body.length())>2000||!AgentScreenSearch.unicode(body)||observation.path("visibility").asText().equals("not_visible")&&!body.isEmpty()||!frame.path("sha256").isString()||!frame.path("sha256").asText().matches("[a-f0-9]{64}")||!frame.path("source_time_base").asText().matches("[1-9][0-9]{0,9}/[1-9][0-9]{0,9}"))throw invalid();
            String[] ratio=frame.path("source_time_base").asText().split("/");var n=new BigInteger(ratio[0]);var d=new BigInteger(ratio[1]);var millis=BigInteger.valueOf(pts).multiply(n).multiply(BigInteger.valueOf(1000));if(millis.compareTo(BigInteger.valueOf(duration).multiply(d))>=0||BigInteger.valueOf(actual).multiply(d).subtract(millis).abs().compareTo(d)>0)throw invalid();
            rows.addObject().put("ocr_frame_id",id).put("requested_ms",requested).put("ocr_timestamp_ms",actual).put("source_pts",pts).put("source_time_base",frame.path("source_time_base").asText()).put("observed_text",body).put("ocr_sha256",frame.path("sha256").asText());
        }return rows;
    }
    public static JobException unavailable(){return new JobException(HttpStatus.CONFLICT,"AGENT_SCREEN_SOURCE_UNAVAILABLE","A published same-job OCR source is required");}
    public static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"AGENT_SCREEN_SOURCE_INVALID","Published screen evidence changed or is invalid");}
}
