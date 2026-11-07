package io.livecut.captions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.exports.ExportModels;
import io.livecut.exports.ExportService;
import io.livecut.jobs.JobException;
import io.livecut.jobs.StageDiagnostics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.livecut.captions.CaptionRenderModels.*;

/** Owns derived render intent; analysis, clean export and confirmed captions stay immutable. */
@Service
public class CaptionRenderService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final ExportService exports;private final CaptionService captions;private final CaptionRenderAssets assets;
    private final TransactionTemplate tx;private final int maxAttempts;
    public CaptionRenderService(JdbcTemplate jdbc,ExportService exports,CaptionService captions,CaptionRenderAssets assets,PlatformTransactionManager manager,
        @Value("${livecut.jobs.max-attempts:3}") int maxAttempts){this.jdbc=jdbc;this.exports=exports;this.captions=captions;this.assets=assets;this.maxAttempts=maxAttempts;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public Snapshot create(String exportValue,String clipValue,String key,JsonNode body){
        String sourceExport=id(exportValue),sourceClip=id(clipValue);final long revision;final String contentHash,style,frame;
        try{CaptionRenderSpec.fields(body,Set.of("caption_revision","caption_content_hash","style_preset","frame_preset"));
            revision=CaptionRenderSpec.number(body,"caption_revision",0,CaptionRenderSpec.MAX_SAFE);contentHash=CaptionRenderSpec.text(body,"caption_content_hash");CaptionRenderSpec.hash(contentHash);
            style=CaptionRenderSpec.text(body,"style_preset");frame=CaptionRenderSpec.text(body,"frame_preset");
            if(!CaptionRenderSpec.STYLES.contains(style)||!CaptionRenderSpec.FRAMES.contains(frame)||key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw invalid();}
        String requestHash=ClipRequestService.sha(ClipRequestService.canonicalJson(body));
        return tx.execute(status->{
            var source=source(sourceExport,sourceClip);var existing=jdbc.queryForList("SELECT id,request_hash FROM caption_render WHERE source_clip_id=? AND idempotency_key=?",sourceClip,key);
            if(!existing.isEmpty()){if(!requestHash.equals(existing.getFirst().get("request_hash")))throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This creation key belongs to different render options");return snapshot(lock(String.valueOf(existing.getFirst().get("id"))));}
            CaptionService.VerifiedDelivery delivery;
            try{delivery=captions.readConfirmedForDelivery(sourceExport,sourceClip,revision);}
            catch(JobException failure){if(Set.of("CAPTION_CONFIRMATION_REQUIRED","CAPTIONS_NOT_INITIALIZED").contains(failure.getCode()))throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_REQUIRES_CONFIRMATION","Confirm this caption revision before rendering");
                if(failure.getCode().equals("CAPTION_REVISION_NOT_FOUND"))throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_REVISION_MISMATCH","The selected caption revision is unavailable");
                throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_SOURCE_UNAVAILABLE","The confirmed source could not be verified");}
            var caption=delivery.snapshot();if(!caption.contentHash().equals(contentHash))throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_REVISION_MISMATCH","The selected caption revision hash has changed");
            JsonNode media=delivery.mediaInfo();final int width,height;final long duration;final boolean hasAudio;
            try{width=(int)CaptionRenderSpec.number(media,"width",2,4096);height=(int)CaptionRenderSpec.number(media,"height",2,4096);duration=CaptionRenderSpec.number(media,"duration_ms",1,CaptionRenderSpec.MAX_SAFE);
                JsonNode audio=media.path("audio_codec");if(!audio.isNull()&&!audio.isMissingNode()&&!audio.asText().equals("aac"))throw new IllegalArgumentException();hasAudio=!audio.isNull()&&!audio.isMissingNode();
            }catch(RuntimeException bad){throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_SOURCE_UNAVAILABLE","The clean clip media metadata is unavailable");}
            var output=CaptionRenderSpec.outputSize(frame,width,height);
            Map<String,Object> render=new TreeMap<>();render.put("schema_version",1);render.put("profile_version",CaptionRenderSpec.VERSION);render.put("source_export_id",sourceExport);render.put("source_clip_id",sourceClip);render.put("source_snapshot_hash",caption.source().snapshotHash());
            render.put("input_media",Map.of("key",delivery.contentKey(),"sha256",delivery.content().hash(),"size_bytes",delivery.content().sizeBytes(),"duration_ms",duration,"width",width,"height",height,"has_audio",hasAudio));
            render.put("captions",assets.cues(caption));render.put("style_preset",style);render.put("frame_preset",frame);render.put("output",Map.of("width",output.width(),"height",output.height()));render.put("font",assets.font());render.put("timing_basis","CLIP_RELATIVE_ASS_CS_FLOOR");
            JsonNode spec=JSON.valueToTree(render);CaptionRenderSpec.validate(spec);String renderHash=ClipRequestService.sha(ClipRequestService.canonicalJson(spec));
            var created=exports.createCaptionRender(String.valueOf(source.get("job_id")),String.valueOf(source.get("video_id")),((Number)source.get("approved_revision")).longValue(),String.valueOf(source.get("source_hash")),caption.clipTitle(),spec,ClipRequestService.sha(sourceClip+"\n"+key));
            jdbc.update("INSERT INTO caption_render (id,source_export_id,source_clip_id,job_id,derived_clip_id,idempotency_key,request_hash,caption_revision,caption_content_hash,caption_render_hash) VALUES (?,?,?,?,?,?,?,?,?,?)",created.exportId(),sourceExport,sourceClip,source.get("job_id"),created.clipId(),key,requestHash,revision,contentHash,renderHash);
            return snapshot(lock(created.exportId()));
        });
    }
    public Page list(String exportValue,String clipValue){String e=id(exportValue),c=id(clipValue);return tx.execute(status->{source(e,c);return new Page(jdbc.queryForList("SELECT r.id FROM caption_render r JOIN export_job e ON e.id=r.id WHERE r.source_export_id=? AND r.source_clip_id=? ORDER BY e.created_at DESC,r.id DESC LIMIT 50",String.class,e,c).stream().map(value->snapshot(lock(value))).toList());});}
    public Snapshot get(String value){String id=id(value);return tx.execute(status->snapshot(lock(id)));}
    public Snapshot cancel(String value){String id=id(value);return tx.execute(status->{var owner=lock(id);String state=String.valueOf(owner.get("status"));
        if(state.equals("CANCELLED"))return snapshot(owner);if(!Set.of("QUEUED","RUNNING").contains(state))throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_NOT_CANCELLABLE","Only an active render may be cancelled");
        jdbc.queryForList("SELECT id FROM job_stage WHERE owner_type='EXPORT' AND owner_id=? ORDER BY id FOR UPDATE",id);
        jdbc.update("UPDATE job_stage SET status='CANCELLED',run_token=NULL,worker_id=NULL,worker_session_id=NULL,lease_until=NULL,hard_deadline_at=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE owner_type='EXPORT' AND owner_id=? AND status IN ('READY','RUNNING','RETRY_WAIT')",id);
        jdbc.update("UPDATE export_job SET status='CANCELLED',state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);return snapshot(lock(id));});}
    public Snapshot retry(String value){String id=id(value);return tx.execute(status->{var owner=lock(id);var row=stage(id);
        if(!"FAILED".equals(owner.get("status"))||!"FAILED".equals(row.get("status")))throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_NOT_RETRYABLE","Only a failed render may retry its frozen inputs");
        if(CaptionRenderSpec.requiresNewRender(row.get("error_code")))throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_NOT_RETRYABLE","Correct and confirm captions, then create a new render");
        if(((Number)row.get("attempt")).intValue()>=maxAttempts)throw error(HttpStatus.CONFLICT,"ATTEMPTS_EXHAUSTED","Render attempts are exhausted");
        exports.retry(id,String.valueOf(owner.get("derived_clip_id")));jdbc.update("UPDATE caption_render SET media_json=NULL WHERE id=?",id);return snapshot(lock(id));});}
    public ExportModels.Content content(String value){String id=id(value);return tx.execute(status->{var owner=lock(id);if(!"SUCCEEDED".equals(owner.get("status")))throw error(HttpStatus.CONFLICT,"EXPORT_CONTENT_NOT_READY","The captioned video has not completed successfully");
        var file=exports.content(id,String.valueOf(owner.get("derived_clip_id")));return new ExportModels.Content(file.path(),file.sizeBytes(),file.hash(),id+"-captioned.mp4");});}
    private Map<String,Object> source(String export,String clip){
        exports.get(export);var owner=jdbc.queryForMap("SELECT * FROM export_job WHERE id=?",export);
        if(!"CLEAN".equals(owner.get("render_mode"))||jdbc.queryForObject("SELECT COUNT(*) FROM export_clip WHERE id=? AND export_id=?",Integer.class,clip,export)!=1)throw error(HttpStatus.CONFLICT,"CAPTION_RENDER_SOURCE_UNAVAILABLE","Select an original clean export clip");return owner;
    }
    private Map<String,Object> lock(String id){
        var references=jdbc.queryForList("SELECT job_id FROM caption_render WHERE id=?",String.class,id);if(references.isEmpty())throw missing();
        jdbc.queryForMap("SELECT id FROM analysis_job WHERE id=? FOR UPDATE",references.getFirst());var owner=new HashMap<>(exports.lockOwner(id,references.getFirst()));
        var render=jdbc.queryForMap("SELECT * FROM caption_render WHERE id=?",id);owner.putAll(render);return owner;
    }
    private Map<String,Object> stage(String id){var rows=jdbc.queryForList("SELECT s.*,c.size_bytes FROM job_stage s JOIN export_clip c ON c.stage_id=s.id AND c.id=s.shard_key WHERE s.owner_type='EXPORT' AND s.owner_id=? AND c.export_id=?",id,id);if(rows.size()!=1)throw corrupt();return rows.getFirst();}
    private Snapshot snapshot(Map<String,Object> owner){
        JsonNode frozen=exports.captionRenderFrozen(owner),spec=frozen.path("caption_render");String id=String.valueOf(owner.get("id"));var row=stage(id);
        if(!spec.path("source_export_id").asText().equals(owner.get("source_export_id"))||!spec.path("source_clip_id").asText().equals(owner.get("source_clip_id"))
            ||spec.path("captions").path("revision").longValue()!=((Number)owner.get("caption_revision")).longValue()||!spec.path("captions").path("content_hash").asText().equals(owner.get("caption_content_hash"))
            ||!frozen.path("caption_render_hash").asText().equals(owner.get("caption_render_hash"))||!row.get("shard_key").equals(owner.get("derived_clip_id")))throw corrupt();
        String state=String.valueOf(owner.get("status"));if(!Set.of("QUEUED","RUNNING","SUCCEEDED","FAILED","CANCELLED").contains(state))throw corrupt();
        boolean requiresNew=CaptionRenderSpec.requiresNewRender(row.get("error_code"));
        int attempt=((Number)row.get("attempt")).intValue();boolean success=state.equals("SUCCEEDED"),canCancel=Set.of("QUEUED","RUNNING").contains(state),canRetry=state.equals("FAILED")&&attempt<maxAttempts&&!requiresNew;
        String reason=state.equals("CANCELLED")?"CANCELLED_REQUIRES_NEW_RENDER":!state.equals("FAILED")?"NOT_FAILED":requiresNew?"INPUT_REQUIRES_NEW_RENDER":attempt>=maxAttempts?"ATTEMPTS_EXHAUSTED":null;
        String error=(String)row.get("error_code");Map<String,Object> diagnostic=StageDiagnostics.read((String)row.get("diagnostic_json"),error,attempt);Progress progress=null;Media media=null;Long size=null;String url=null;
        if(row.get("render_progress_json")!=null){try{var p=JSON.readTree(String.valueOf(row.get("render_progress_json")));CaptionRenderSpec.fields(p,Set.of("sequence","phase","processed_ms","total_ms","updated_at"));
            CaptionRenderSpec.number(p,"sequence",1,CaptionRenderSpec.MAX_SAFE);String phase=CaptionRenderSpec.text(p,"phase");long processed=CaptionRenderSpec.number(p,"processed_ms",0,CaptionRenderSpec.MAX_SAFE),total=CaptionRenderSpec.number(p,"total_ms",1,CaptionRenderSpec.MAX_SAFE);
            if(!Set.of("PREPARING","ENCODING","VALIDATING","PUBLISHING").contains(phase)||processed>total||total!=spec.path("input_media").path("duration_ms").longValue())throw corrupt();
            progress=new Progress(phase,processed,total,Instant.parse(CaptionRenderSpec.text(p,"updated_at")));}catch(RuntimeException bad){throw corrupt();}}
        if(success){try{var m=JSON.readTree(String.valueOf(owner.get("media_json")));CaptionRenderSpec.fields(m,Set.of("duration_ms","width","height"));
                long duration=CaptionRenderSpec.number(m,"duration_ms",1,CaptionRenderSpec.MAX_SAFE);int width=(int)CaptionRenderSpec.number(m,"width",2,4096),height=(int)CaptionRenderSpec.number(m,"height",2,4096);
                if(width!=spec.path("output").path("width").intValue()||height!=spec.path("output").path("height").intValue()||!"SUCCEEDED".equals(row.get("status"))||row.get("size_bytes")==null)throw corrupt();
                media=new Media(duration,width,height);size=((Number)row.get("size_bytes")).longValue();url="/api/caption-renders/"+id+"/content";
            }catch(JobException bad){throw bad;}catch(RuntimeException bad){throw corrupt();}}
        return new Snapshot(id,String.valueOf(owner.get("source_export_id")),String.valueOf(owner.get("source_clip_id")),String.valueOf(owner.get("job_id")),((Number)owner.get("caption_revision")).longValue(),String.valueOf(owner.get("caption_content_hash")),spec.path("input_media").path("sha256").asText(),spec.path("style_preset").asText(),spec.path("frame_preset").asText(),CaptionRenderSpec.VERSION,CaptionRenderSpec.FONT_PROFILE,state,((Number)owner.get("state_version")).longValue(),attempt,instant(owner.get("created_at")),instant(owner.get("updated_at")),error,diagnostic,new Actions(canCancel,canRetry,maxAttempts,reason),progress,url,url==null?null:url+"?download=true",size,media);
    }
    private static Instant instant(Object value){return value instanceof Timestamp t?t.toInstant():((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);}
    private static String id(String value){try{return ClipRequestService.uuid(value);}catch(RuntimeException bad){throw invalid();}}
    private static JobException invalid(){return error(HttpStatus.BAD_REQUEST,"CAPTION_RENDER_INVALID_INPUT","Invalid caption render input");}
    private static JobException missing(){return error(HttpStatus.NOT_FOUND,"CAPTION_RENDER_NOT_FOUND","Caption render was not found");}
    private static JobException corrupt(){return error(HttpStatus.CONFLICT,"EXPORT_SNAPSHOT_INVALID","Frozen caption render failed integrity validation");}
    private static JobException error(HttpStatus status,String code,String message){return new JobException(status,code,message);}
}
