package io.livecut.captions;

import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.exports.ExportModels;
import io.livecut.exports.ExportService;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.ArtifactVerifier.ManifestIdentity;
import io.livecut.jobs.ArtifactVerifier.SourcePart;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Resolves only already-published, hashed sources; this component never calls an ASR/model or edits an export. */
@Component
public class CaptionSourceResolver {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final ExportService exports;
    private final ArtifactVerifier artifacts;
    public record Resolved(String exportId,String clipId,String jobId,String title,String contentUrl,
                           CaptionModels.Source source,List<CaptionModels.Cue> originals,JsonNode basis,String basisHash,
                           ExportModels.Content verifiedContent,String verifiedContentKey,JsonNode verifiedMediaInfo) {
        public Resolved { originals=List.copyOf(originals); }
    }
    public CaptionSourceResolver(JdbcTemplate jdbc,ExportService exports,ArtifactVerifier artifacts){this.jdbc=jdbc;this.exports=exports;this.artifacts=artifacts;}
    /** Caller owns a transaction: ExportService locks analysis -> export -> clip/stage before caption rows. */
    public Resolved resolve(String exportId,String clipId){
        exportId=ClipRequestService.uuid(exportId);clipId=ClipRequestService.uuid(clipId);
        ExportModels.Snapshot batch=exports.get(exportId);
        String target=clipId;
        var clip=batch.clips().stream().filter(c->c.id().equals(target)).findFirst().orElseThrow(CaptionSourceResolver::invalid);
        var content=exports.content(exportId,clipId);
        var owner=jdbc.queryForMap("SELECT * FROM export_job WHERE id=?",exportId);
        if(!"CLEAN".equals(owner.get("render_mode")))throw invalid();
        var job=jdbc.queryForMap("SELECT * FROM analysis_job WHERE id=?",batch.jobId());
        JsonNode frozen=owner.get("manual_clip_plan_id")!=null?exports.verifyManualFrozen(owner):owner.get("clip_request_id")==null?JSON.readTree(String.valueOf(owner.get("frozen_json"))):exports.verifyClipRequestFrozen(owner);
        if(!ClipRequestService.sha(ClipRequestService.canonicalJson(frozen)).equals(batch.snapshotHash())
            ||!batch.jobId().equals(frozen.path("job_id").asText())||!batch.videoId().equals(frozen.path("video_id").asText())
            ||!frozen.path("generation").isIntegralNumber()||frozen.path("generation").longValue()!=((Number)job.get("generation")).longValue()
            ||!String.valueOf(owner.get("source_hash")).equals(frozen.path("source_hash").asText()))throw invalid();
        var stage=jdbc.queryForMap("SELECT * FROM job_stage WHERE id=? AND owner_type='EXPORT' AND owner_id=?",clip.stageId(),exportId);
        var clipIdentity=identity(stage,job,String.valueOf(owner.get("source_hash")));
        // content() already verified the full export manifest and video; reread its small hashed metadata only.
        JsonNode manifest=artifacts.readJsonArtifact(new ArtifactVerifier.VerifiedArtifact("manifest",String.valueOf(stage.get("manifest_key")),String.valueOf(stage.get("manifest_hash")),0),clipIdentity);
        JsonNode mediaRef=null,clipRef=null;for(JsonNode file:manifest.path("artifacts")){if("media_info".equals(file.path("kind").asText()))mediaRef=file;if("clip".equals(file.path("kind").asText()))clipRef=file;}
        if(mediaRef==null||clipRef==null||!content.hash().equals(clipRef.path("sha256").asText()))throw invalid();
        JsonNode media=artifacts.readJsonArtifact(new ArtifactVerifier.VerifiedArtifact("media_info",mediaRef.path("key").asText(),mediaRef.path("sha256").asText(),0),clipIdentity);
        long mediaDuration=positive(media,"duration_ms");
        var asr=completed(batch.jobId(),"ASR");var asrIdentity=identity(asr,job,String.valueOf(owner.get("source_hash")));
        var asrManifest=artifacts.verifyManifest(String.valueOf(asr.get("manifest_key")),String.valueOf(asr.get("manifest_hash")),asrIdentity);
        var transcript=asrManifest.artifacts().stream().filter(a->a.kind().equals("transcript")).findFirst().orElseThrow(CaptionSourceResolver::invalid);
        JsonNode utterances=artifacts.readJsonArtifact(transcript,asrIdentity);long sourceDuration;
        String reviewedHash;
        if(batch.manualClipPlanId()!=null){
            JsonNode manual=frozen.path("manual_plan"),source=manual.path("source");
            if(!batch.manualClipPlanId().equals(manual.path("id").asText())||!batch.jobId().equals(source.path("job_id").asText())||!batch.videoId().equals(source.path("video_id").asText())
                    ||!owner.get("source_hash").equals(source.path("source_video_sha256").asText())||!transcript.sha256().equals(source.path("transcript_sha256").asText()))throw invalid();
            sourceDuration=source.path("duration_ms").longValue();reviewedHash=frozen.path("plan_hash").asText();
        }else if(batch.clipRequestId()!=null){
            var request=jdbc.queryForMap("SELECT * FROM clip_request WHERE id=? AND job_id=?",batch.clipRequestId(),batch.jobId());
            if(!batch.videoId().equals(request.get("video_id"))||!owner.get("source_hash").equals(request.get("source_hash"))
                ||!transcript.sha256().equals(request.get("transcript_hash"))||!transcript.key().equals(request.get("transcript_key"))
                ||!utterances.equals(JSON.readTree(String.valueOf(request.get("source_json"))).path("utterances")))throw invalid();
            sourceDuration=((Number)request.get("duration_ms")).longValue();reviewedHash=frozen.path("plan_hash").asText();
        }else{
            var review=jdbc.queryForList("SELECT source_hash,source_json,duration_ms FROM review_document WHERE job_id=?",batch.jobId());if(review.size()!=1)throw invalid();
            var document=review.getFirst();JsonNode original=JSON.readTree(String.valueOf(document.get("source_json")));
            var timeline=completed(batch.jobId(),"TIMELINE");var timelineIdentity=identity(timeline,job,String.valueOf(owner.get("source_hash")));
            var timelineManifest=artifacts.verifyManifest(String.valueOf(timeline.get("manifest_key")),String.valueOf(timeline.get("manifest_hash")),timelineIdentity);
            var analysis=timelineManifest.artifacts().stream().filter(a->a.kind().equals("analysis_result")).findFirst().orElseThrow(CaptionSourceResolver::invalid);
            if(!analysis.sha256().equals(document.get("source_hash"))||!artifacts.readJsonArtifact(analysis,timelineIdentity).equals(original)
                ||!utterances.equals(original.path("utterances")))throw invalid();
            sourceDuration=((Number)document.get("duration_ms")).longValue();reviewedHash=String.valueOf(document.get("source_hash"));
        }
        List<SourcePart> parts=clip.parts()==null?List.of(new SourcePart(clip.startMs(),clip.endMs())):clip.parts();
        List<CaptionMapper.SourceUtterance> inputs=new ArrayList<>();
        if(!utterances.isArray())throw invalid();
        for(JsonNode u:utterances)inputs.add(new CaptionMapper.SourceUtterance(u.path("utterance_id").asText(),number(u,"start_ms"),positive(u,"end_ms"),u.path("text").asText()));
        CaptionMapper.MappingResult mapping;
        try{mapping=CaptionMapper.map(sourceDuration,parts,inputs);}catch(IllegalArgumentException failure){throw invalid();}
        List<CaptionModels.Cue> originals=mapping.cues().stream().map(CaptionModels::original).toList();
        var source=new CaptionModels.Source(batch.snapshotHash(),content.hash(),transcript.sha256(),mapping.durationMs(),mediaDuration,mapping.timingBasis(),parts);
        Map<String,Object> basis=new TreeMap<>();basis.put("schema_version","1.0");basis.put("caption_version","sentence-captions-v1");basis.put("export_id",exportId);basis.put("clip_id",clipId);
        basis.put("job_id",batch.jobId());basis.put("video_id",batch.videoId());basis.put("source_video_hash",owner.get("source_hash"));basis.put("source_duration_ms",sourceDuration);
        basis.put("source",source);basis.put("transcript_key",transcript.key());basis.put("reviewed_source_hash",reviewedHash);
        if(batch.manualClipPlanId()!=null)basis.put("manual_clip_plan_id",batch.manualClipPlanId());
        basis.put("original_content_hash",ClipRequestService.sha(ClipRequestService.canonicalJson(JSON.valueToTree(new CaptionModels.Content(originals)))));
        JsonNode basisNode=JSON.valueToTree(basis);String canonical=ClipRequestService.canonicalJson(basisNode);
        String title=clip.title()!=null?clip.title():clip.productName()!=null?clip.productName():"视频片段";
        return new Resolved(exportId,clipId,batch.jobId(),title,clip.contentUrl(),source,originals,basisNode,ClipRequestService.sha(canonical),content,clipRef.path("key").asText(),media);
    }
    private Map<String,Object> completed(String job,String type){var rows=jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type=? AND status='SUCCEEDED' FOR UPDATE",job,type);if(rows.size()!=1)throw invalid();return rows.getFirst();}
    private static ManifestIdentity identity(Map<String,Object> stage,Map<String,Object> job,String hash){
        return new ManifestIdentity(String.valueOf(job.get("id")),String.valueOf(job.get("video_id")),((Number)job.get("generation")).intValue(),
            String.valueOf(stage.get("id")),String.valueOf(stage.get("stage_type")),((Number)stage.get("attempt")).intValue(),String.valueOf(stage.get("run_token")),hash);
    }
    private static long number(JsonNode value,String field){JsonNode n=value.path(field);if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw invalid();return n.longValue();}
    private static long positive(JsonNode value,String field){long n=number(value,field);if(n<=0)throw invalid();return n;}
    static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"CAPTION_SOURCE_INVALID","The published caption source no longer matches its immutable export");}
}
