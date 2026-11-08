package io.livecut.clips;

import static io.livecut.clips.ClipPlanValidator.*;
import static io.livecut.clips.ClipRequestModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import io.livecut.jobs.JobModels.StageRow;
import io.livecut.jobs.StageDiagnostics;
import io.livecut.jobs.TextExecutionPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Intent requests own revisions and budgets; source analysis and user drafts remain untouched. */
@Service
public class ClipRequestService {
    private final JdbcTemplate jdbc;
    private final ArtifactVerifier artifacts;
    private final TransactionTemplate tx;
    private final ClipManualReview manual;
    @Value("${LIVECUT_TEXT_MODEL:}")
    private String preferredTextModel;
    @Value("${LIVECUT_TEXT_FALLBACK_MODELS:}")
    private String configuredFallbackModels;
    private record FrozenSource(String video,String hash,String transcriptKey,String transcriptHash,long duration,JsonNode utterances,String model) {}
    public ClipRequestService(JdbcTemplate jdbc,ArtifactVerifier artifacts,PlatformTransactionManager manager,ClipManualReview manual){
        this.jdbc=jdbc;this.artifacts=artifacts;this.manual=manual;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Snapshot create(String parentId,CreateRequest input,String key){
        String parent=uuid(parentId);String instruction=input==null?null:input.instruction();
        if(instruction==null||instruction.isBlank()||instruction.length()>2000||key==null||!key.matches("[A-Za-z0-9_-]{1,100}"))throw bad("INVALID_CLIP_REQUEST","A bounded instruction and idempotency key are required");
        int budget=input.maxTextCalls()==null?6:input.maxTextCalls();if(budget<1||budget>24)throw bad("INVALID_CLIP_REQUEST","Text budget must be 1..24");
        JsonNode selection=AnswerClipSource.selector(input.sourceAnswer());
        String fingerprint=sha(parent+"\n"+instruction+"\n"+budget+(selection==null?"":"\n"+canonicalJson(selection)));
        try{return tx.execute(status->{
            Map<String,Object> job=lockAnalysis(parent);
            List<Map<String,Object>> existing=jdbc.queryForList("SELECT * FROM clip_request WHERE idempotency_key=? FOR UPDATE",key);
            if(!existing.isEmpty())return existingCreate(existing.getFirst(),fingerprint);
            FrozenSource source=source(job);
            AnswerClipSource.Frozen answer=selection==null?null:AnswerClipSource.resolve(jdbc,artifacts,job,source.hash(),source.transcriptHash(),source.duration(),source.utterances(),selection);
            return insertRequest(parent,source,instruction,budget,key,fingerprint,null,answer);
        });}catch(DuplicateKeyException collision){return tx.execute(status->{lockAnalysis(parent);var rows=jdbc.queryForList("SELECT * FROM clip_request WHERE idempotency_key=? FOR UPDATE",key);if(rows.isEmpty())throw collision;return existingCreate(rows.getFirst(),fingerprint);});}
    }
    private Snapshot existingCreate(Map<String,Object> existing,String fingerprint){
        if(!fingerprint.equals(existing.get("request_hash")))throw conflict("IDEMPOTENCY_CONFLICT","Key belongs to a different clipping instruction");
        return snapshot(existing);
    }
    public Page list(String parentId){String parent=uuid(parentId);return tx.execute(status->{lockAnalysis(parent);return new Page(jdbc.queryForList("SELECT * FROM clip_request WHERE job_id=? ORDER BY created_at DESC,id DESC LIMIT 100",parent).stream().map(this::snapshot).toList());});}
    public Snapshot get(String id){return tx.execute(status->snapshot(lockPublicOwner(uuid(id))));}
    public JsonNode createManualReview(String id,JsonNode body,String key){
        String request=uuid(id);JsonNode input=ClipManualReview.input(body,key);String fingerprint=ClipManualReview.fingerprint(request,input);
        try{return tx.execute(status->{
            var owner=lockPublicOwner(request);var existing=jdbc.queryForList("SELECT * FROM clip_plan_manual_review WHERE id=?",key);
            if(!existing.isEmpty()){
                var row=existing.getFirst();if(!request.equals(row.get("request_id"))||!fingerprint.equals(row.get("request_hash")))throw conflict("IDEMPOTENCY_CONFLICT","Manual review key belongs to another immutable submission");
                return manualAt(owner,((Number)row.get("revision")).longValue());
            }
            requireReady(owner);long base=number(input,"base_revision");requireCurrent(owner,base);
            var current=planRow(owner,base);String expected=input.path("plan_hash").asText();JsonNode plan=checkedPlan(owner,current,expected),automatic=automaticPlan(owner);
            manual.stored(owner,current,plan,automatic);manual.verifySource(owner);
            long next=Math.addExact(base,1);JsonNode review=manual.build(owner,plan,automatic,input,key,next,ClipManualReview.now());String hash=review.path("review_hash").asText();
            jdbc.update("INSERT INTO clip_plan_revision (request_id,revision,plan_hash,plan_json,manual_review_id,manual_review_hash) VALUES (?,?,?,?,?,?)",request,next,expected,canonicalJson(plan),key,hash);
            jdbc.update("INSERT INTO clip_plan_manual_review (id,request_id,request_hash,base_revision,base_plan_hash,revision,plan_hash,review_hash,review_json,source_snapshot_hash,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",key,request,fingerprint,base,expected,next,expected,hash,canonicalJson(review),ClipManualReview.sourceHash(owner),Timestamp.from(Instant.parse(review.path("created_at").asText())));
            jdbc.update("UPDATE clip_request SET latest_revision=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",next,request);
            return review;
        });}catch(DuplicateKeyException collision){throw conflict("IDEMPOTENCY_CONFLICT","Manual review key belongs to another immutable submission");}
    }
    public JsonNode getManualReview(String id,String reviewId){String request=uuid(id),key=uuid(reviewId);return tx.execute(status->{var owner=lockPublicOwner(request);var rows=jdbc.queryForList("SELECT revision FROM clip_plan_manual_review WHERE id=? AND request_id=?",Long.class,key,request);if(rows.isEmpty()){
        if(!jdbc.queryForList("SELECT revision FROM clip_plan_revision WHERE request_id=? AND manual_review_id=?",request,key).isEmpty())throw ClipManualReview.invalid();
        throw missing("CLIP_MANUAL_REVIEW_NOT_FOUND");
    }JsonNode review=manualAt(owner,rows.getFirst());if(review==null||!key.equals(review.path("id").asText()))throw ClipManualReview.invalid();return review;});}
    /** Cancellation fences execution, while old dispatched calls may still settle their accounting. */
    public Snapshot cancel(String id){
        String requestId=uuid(id);
        return tx.execute(status->{
            var owner=lockPublicOwner(requestId);
            if("CANCELLED".equals(owner.get("status")))return snapshot(owner);
            if(!active(owner))throw conflict("CLIP_REQUEST_NOT_CANCELLABLE","Only queued or running clipping requests can be cancelled");
            var stages=jdbc.queryForList("SELECT id,status FROM job_stage FORCE INDEX (idx_stage_owner_status) WHERE owner_type='CLIP_REQUEST' AND owner_id=? FOR UPDATE",requestId);
            if(stages.size()!=1||!Set.of("READY","RUNNING","RETRY_WAIT").contains(stages.getFirst().get("status")))throw conflict("CLIP_STAGE_UNAVAILABLE","The clipping stage has already changed");
            jdbc.update("UPDATE job_stage SET status='CANCELLED',run_token=NULL,worker_id=NULL,worker_session_id=NULL,lease_until=NULL,hard_deadline_at=NULL,last_retryable=FALSE,error_code='USER_CANCELLED',diagnostic_json=NULL,text_progress_json=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",stages.getFirst().get("id"));
            jdbc.update("UPDATE clip_request SET status='CANCELLED',state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",requestId);
            return snapshot(lockOwner(requestId,String.valueOf(owner.get("job_id"))));
        });
    }
    /** A deliberate new recovery round never resets the old request's budget or call records. */
    public Snapshot resume(String id,ResumeRequest input,String key){
        String requestId=uuid(id);
        if(key==null||!key.matches("[A-Za-z0-9_-]{1,100}"))throw bad("INVALID_CLIP_REQUEST","A recovery idempotency key is required");
        int budget=input==null||input.maxTextCalls()==null?6:input.maxTextCalls();
        if(budget<1||budget>24)throw bad("INVALID_CLIP_REQUEST","Text budget must be 1..24");
        String fingerprint=sha("resume\n"+requestId+"\n"+budget);
        try{return tx.execute(status->{
            var prior=lockPublicOwner(requestId);String parent=String.valueOf(prior.get("job_id"));
            var existing=jdbc.queryForList("SELECT * FROM clip_request WHERE idempotency_key=? FOR UPDATE",key);
            if(!existing.isEmpty())return existingCreate(existing.getFirst(),fingerprint);
            if(!Set.of("FAILED","CANCELLED").contains(prior.get("status")))throw conflict("CLIP_REQUEST_NOT_RESUMABLE","Only failed or cancelled requests may start a new recovery round");
            FrozenSource source=source(lockAnalysis(parent));
            if(!source.video().equals(prior.get("video_id"))||!source.hash().equals(prior.get("source_hash"))||!source.transcriptHash().equals(prior.get("transcript_hash"))||source.duration()!=((Number)prior.get("duration_ms")).longValue())throw conflict("CLIP_RESUME_SOURCE_CHANGED","The frozen source no longer matches this clipping request");
            return insertRequest(parent,source,String.valueOf(prior.get("instruction")),budget,key,fingerprint,requestId,AnswerClipSource.stored(prior));
        });}catch(DuplicateKeyException collision){return tx.execute(status->{lockPublicOwner(requestId);var rows=jdbc.queryForList("SELECT * FROM clip_request WHERE idempotency_key=? FOR UPDATE",key);if(rows.isEmpty())throw collision;return existingCreate(rows.getFirst(),fingerprint);});}
    }
    private Snapshot insertRequest(String parent,FrozenSource source,String instruction,int budget,String key,String fingerprint,String resumedFrom,AnswerClipSource.Frozen answer){
        String id=UUID.randomUUID().toString(),stage=UUID.randomUUID().toString();
        Map<String,Object> frozen=new TreeMap<>();frozen.put("utterances",source.utterances());if(answer!=null)frozen.put("answer_result",answer.answer());
        String sourceJson=canonicalJson(JSON.valueToTree(frozen)),answerJson=answer==null?null:canonicalJson(answer.source());
        List<String> fallbackModels=TextExecutionPolicy.configuredFallbacks(configuredFallbackModels);
        jdbc.update("INSERT INTO clip_request (id,job_id,video_id,idempotency_key,request_hash,instruction,text_model_id,source_hash,transcript_hash,transcript_key,source_json,duration_ms,max_text_calls,text_fallback_models_json,resumed_from_request_id,source_answer_json,source_answer_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,parent,source.video(),key,fingerprint,instruction,source.model(),source.hash(),source.transcriptHash(),source.transcriptKey(),sourceJson,source.duration(),budget,TextExecutionPolicy.serialize(fallbackModels),resumedFrom,answerJson,answerJson==null?null:sha(answerJson));
        jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,'CLIP_REQUEST',?,?,?,'READY',?)",
            stage,parent,id,answer==null?"SEGMENT":AnswerClipSource.STAGE,"clip-"+id,sha(fingerprint+"\n"+source.hash()+"\n"+source.transcriptHash()+"\n"+source.model()+"\n"+TextExecutionPolicy.serialize(fallbackModels)+(answerJson==null?"":"\n"+answerJson)));
        return snapshot(lockOwner(id,parent));
    }
    public Snapshot save(String id,JsonNode input){
        fields(input,Set.of("base_revision","outputs"));long base=number(input,"base_revision");
        return tx.execute(status->{var owner=lockPublicOwner(uuid(id));requireReady(owner);requireCurrent(owner,base);
            var previous=planRow(owner,base);JsonNode old=JSON.readTree(String.valueOf(previous.get("plan_json")));
            manual.stored(owner,previous,old,automaticPlan(owner));
            ObjectNode updated=(ObjectNode)old.deepCopy();updated.set("outputs",input.path("outputs").deepCopy());
            validateReview(updated,owner,automaticPlan(owner));long next=Math.addExact(base,1);String serialized=canonicalJson(updated);
            jdbc.update("INSERT INTO clip_plan_revision (request_id,revision,plan_hash,plan_json) VALUES (?,?,?,?)",id,next,sha(serialized),serialized);
            jdbc.update("UPDATE clip_request SET latest_revision=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",next,id);
            return snapshot(lockOwner(id,String.valueOf(owner.get("job_id"))));});
    }
    public Snapshot confirm(String id,JsonNode input){
        fields(input,input.has("manual_review_hash")?Set.of("revision","plan_hash","manual_review_hash"):Set.of("revision","plan_hash"));long rev=number(input,"revision");String expected=hash(text(input,"plan_hash",64));String manualHash=manualHash(input);
        return tx.execute(status->{var owner=lockPublicOwner(uuid(id));requireReady(owner);requireCurrent(owner,rev);
            var selected=deliverySelection(owner,rev,expected,manualHash,false);
            if(selected.outputs().isEmpty())throw conflict("CLIP_PLAN_EMPTY_SELECTION","Select at least one output before confirming");
            var rows=jdbc.queryForList("SELECT plan_hash,manual_review_hash FROM clip_plan_confirmation WHERE request_id=? AND revision=?",id,rev);
            if(!rows.isEmpty()){var previous=rows.getFirst();if(!expected.equals(previous.get("plan_hash"))||!Objects.equals(manualHash,previous.get("manual_review_hash")))throw conflict("CLIP_PLAN_CONFIRMATION_CONFLICT","Confirmation cannot be rewritten with different review authority");}
            else jdbc.update("INSERT INTO clip_plan_confirmation (request_id,revision,plan_hash,manual_review_hash) VALUES (?,?,?,?)",id,rev,expected,manualHash);
            return snapshot(owner);});
    }
    public Map<String,Object> lockOwner(String id,String parent){var rows=jdbc.queryForList("SELECT * FROM clip_request WHERE id=? AND job_id=? FOR UPDATE",id,parent);if(rows.isEmpty())throw missing("CLIP_REQUEST_NOT_FOUND");return rows.getFirst();}
    public Map<String,Object> lockPublicOwner(String id){var parent=jdbc.queryForList("SELECT job_id FROM clip_request WHERE id=?",String.class,id);if(parent.isEmpty())throw missing("CLIP_REQUEST_NOT_FOUND");lockAnalysis(parent.getFirst());return lockOwner(id,parent.getFirst());}
    public boolean active(Map<String,Object> owner){return owner!=null&&Set.of("QUEUED","RUNNING").contains(owner.get("status"));}
    public void refreshLocked(String id){
        var stages=jdbc.queryForList("SELECT status,attempt FROM job_stage WHERE owner_type='CLIP_REQUEST' AND owner_id=? ORDER BY id FOR UPDATE",id);
        boolean pending=stages.stream().anyMatch(s->Set.of("READY","RUNNING","RETRY_WAIT").contains(s.get("status")));
        boolean attempted=stages.stream().anyMatch(s->((Number)s.get("attempt")).intValue()>0);
        boolean failed=stages.stream().anyMatch(s->"FAILED".equals(s.get("status")));
        boolean cancelled=stages.stream().anyMatch(s->"CANCELLED".equals(s.get("status")));
        String state=cancelled?"CANCELLED":pending?(attempted?"RUNNING":"QUEUED"):failed?"FAILED":"PLAN_READY";
        jdbc.update("UPDATE clip_request SET status=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status<>'CANCELLED'",state,id);
    }
    public Map<String,Object> claimConfig(StageRow stage){
        var owner=jdbc.queryForMap("SELECT * FROM clip_request WHERE id=?",stage.ownerId());Map<String,Object> clip=new TreeMap<>();
        var answer=AnswerClipSource.stored(owner);String expected=answer==null?"SEGMENT":AnswerClipSource.STAGE;
        if(!expected.equals(stage.stageType()))throw conflict("CLIP_STAGE_UNAVAILABLE","Clipping stage does not match its frozen answer mode");
        if(answer!=null)clip.put("answer_source",answer.source());
        clip.put("request_id",stage.ownerId());clip.put("instruction",owner.get("instruction"));clip.put("source_job_id",stage.jobId());
        clip.put("source_transcript_key",owner.get("transcript_key"));clip.put("source_transcript_hash",owner.get("transcript_hash"));
        clip.put("duration_ms",((Number)owner.get("duration_ms")).longValue());clip.put("max_text_calls",((Number)owner.get("max_text_calls")).intValue());clip.put("text_model_id",owner.get("text_model_id"));
        return Map.of("clip_request",clip,"text_model",owner.get("text_model_id"),"text_fallback_models",TextExecutionPolicy.storedFallbacks(owner.get("text_fallback_models_json")),"max_text_calls",owner.get("max_text_calls"),"max_asr_submissions",0,"visual_provider","none");
    }
    public ArtifactVerifier.VerifiedManifest verifyResult(StageRow stage,Map<String,Object> job,String key,String hash){
        var owner=lockOwner(stage.ownerId(),stage.jobId());var identity=identity(stage,job,String.valueOf(owner.get("source_hash")));
        var manifest=artifacts.verifyClipPlanManifest(key,hash,identity);var plan=manifest.artifacts().stream().filter(a->a.kind().equals("clip_plan")).findFirst().orElseThrow();
        validate(artifacts.readJsonArtifact(plan,identity),owner,true);return manifest;
    }
    public void recordSuccess(StageRow stage,Map<String,Object> job,ArtifactVerifier.VerifiedManifest manifest){
        var owner=lockOwner(stage.ownerId(),stage.jobId());var identity=identity(stage,job,String.valueOf(owner.get("source_hash")));
        var file=manifest.artifacts().stream().filter(a->a.kind().equals("clip_plan")).findFirst().orElseThrow();
        var plan=validate(artifacts.readJsonArtifact(file,identity),owner,true);String serialized=canonicalJson(plan);
        jdbc.update("INSERT INTO clip_plan_revision (request_id,revision,plan_hash,plan_json) VALUES (?,0,?,?)",stage.ownerId(),sha(serialized),serialized);
    }
    public JsonNode confirmedCandidate(Map<String,Object> owner,long rev,String expected,boolean confirmed){
        return deliverySelection(owner,rev,expected,null,confirmed).plan();
    }
    public record DeliverySelection(JsonNode plan,JsonNode review,List<JsonNode> outputs) { public DeliverySelection{outputs=List.copyOf(outputs);} }
    public DeliverySelection deliverySelection(Map<String,Object> owner,long revision,String planHash,String manualHash,boolean confirmed){
        var row=planRow(owner,revision);JsonNode plan=checkedPlan(owner,row,planHash),review=manual.stored(owner,row,plan,automaticPlan(owner));
        String actual=review==null?null:review.path("review_hash").asText();if(!Objects.equals(actual,manualHash))throw conflict("CLIP_MANUAL_REVIEW_MISMATCH","Supply this revision's exact manual review hash");
        if(confirmed){var rows=jdbc.queryForList("SELECT plan_hash,manual_review_hash FROM clip_plan_confirmation WHERE request_id=? AND revision=?",owner.get("id"),revision);if(rows.size()!=1||!planHash.equals(rows.getFirst().get("plan_hash"))||!Objects.equals(actual,rows.getFirst().get("manual_review_hash")))throw conflict("EXPORT_REQUIRES_CONFIRMATION","Confirm this exact plan and manual review first");}
        Set<String> human=new HashSet<>();if(review!=null)review.path("outputs").forEach(value->human.add(value.path("output_id").asText()));List<JsonNode> selected=new ArrayList<>();
        for(JsonNode output:plan.path("outputs"))if(output.path("enabled").asBoolean()||human.contains(output.path("output_id").asText()))selected.add(output);
        return new DeliverySelection(plan,review,selected);
    }
    public static String manualHash(JsonNode input){return input.has("manual_review_hash")?hash(text(input,"manual_review_hash",64)):null;}
    private Map<String,Object> planRow(Map<String,Object> owner,long revision){var rows=jdbc.queryForList("SELECT * FROM clip_plan_revision WHERE request_id=? AND revision=?",owner.get("id"),revision);if(rows.size()!=1)throw missing("CLIP_PLAN_REVISION_NOT_FOUND");return rows.getFirst();}
    private JsonNode checkedPlan(Map<String,Object> owner,Map<String,Object> row,String expected){if(!expected.equals(row.get("plan_hash")))throw conflict("CLIP_PLAN_HASH_CONFLICT","Plan hash does not match this revision");JsonNode plan=JSON.readTree(String.valueOf(row.get("plan_json")));if(!ClipManualReview.digest(plan).equals(expected))throw conflict("CLIP_PLAN_SOURCE_INVALID","Saved plan failed its canonical hash");return validateReview(plan,owner,automaticPlan(owner));}
    private JsonNode manualAt(Map<String,Object> owner,long revision){var row=planRow(owner,revision);JsonNode plan=checkedPlan(owner,row,String.valueOf(row.get("plan_hash")));return manual.stored(owner,row,plan,automaticPlan(owner));}
    private List<Long> effectiveConfirmations(Map<String,Object> owner){List<Long> result=new ArrayList<>();for(var row:jdbc.queryForList("SELECT c.revision,c.plan_hash,c.manual_review_hash,r.manual_review_id,r.manual_review_hash AS marker_hash FROM clip_plan_confirmation c JOIN clip_plan_revision r ON r.request_id=c.request_id AND r.revision=c.revision AND r.plan_hash=c.plan_hash WHERE c.request_id=? ORDER BY c.revision",owner.get("id"))){
        if(row.get("manual_review_id")==null&&row.get("marker_hash")==null&&row.get("manual_review_hash")==null){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_manual_review WHERE request_id=? AND revision=?",Integer.class,owner.get("id"),row.get("revision"))==0)result.add(((Number)row.get("revision")).longValue());
        }else if(Objects.equals(row.get("marker_hash"),row.get("manual_review_hash"))){try{JsonNode review=manualAt(owner,((Number)row.get("revision")).longValue());if(review!=null&&review.path("review_hash").asText().equals(row.get("manual_review_hash")))result.add(((Number)row.get("revision")).longValue());}catch(JobException invalid){/* Invalid historical proof cannot grant confirmation. */}}
    }return result;}
    /** Revision zero is inserted only when the automatic stage succeeds; public review APIs never replace it. */
    private JsonNode automaticPlan(Map<String,Object> owner){
        var rows=jdbc.queryForList("SELECT plan_hash,plan_json FROM clip_plan_revision WHERE request_id=? AND revision=0",owner.get("id"));
        if(rows.size()!=1)throw conflict("CLIP_PLAN_SOURCE_INVALID","The original automatic plan is unavailable");
        try{
            var row=rows.getFirst();JsonNode plan=JSON.readTree(String.valueOf(row.get("plan_json")));
            if(!sha(canonicalJson(plan)).equals(row.get("plan_hash")))throw new IllegalArgumentException("Automatic plan hash mismatch");
            validate(plan,owner,true);return plan;
        }catch(RuntimeException invalid){throw conflict("CLIP_PLAN_SOURCE_INVALID","The original automatic plan failed validation");}
    }
    private JsonNode revision(Map<String,Object> owner,long rev){var rows=jdbc.queryForList("SELECT plan_json FROM clip_plan_revision WHERE request_id=? AND revision=?",String.class,owner.get("id"),rev);if(rows.isEmpty())throw missing("CLIP_PLAN_REVISION_NOT_FOUND");return JSON.readTree(rows.getFirst());}
    private Snapshot snapshot(Map<String,Object> owner){
        var answer=AnswerClipSource.stored(owner);
        String id=String.valueOf(owner.get("id"));long rev=((Number)owner.get("latest_revision")).longValue();
        var planRows=jdbc.queryForList("SELECT * FROM clip_plan_revision WHERE request_id=? AND revision=?",id,rev);
        JsonNode plan=planRows.isEmpty()?null:JSON.readTree(String.valueOf(planRows.getFirst().get("plan_json")));
        JsonNode review=planRows.isEmpty()?null:manual.stored(owner,planRows.getFirst(),plan,planRows.getFirst().get("manual_review_id")==null?plan:automaticPlan(owner));
        var stages=jdbc.queryForList("SELECT * FROM job_stage WHERE owner_type='CLIP_REQUEST' AND owner_id=?",id);if(stages.size()!=1)throw conflict("CLIP_STAGE_UNAVAILABLE","Clipping stage is unavailable");var s=stages.getFirst();
        Stage stage=new Stage(String.valueOf(s.get("id")),String.valueOf(s.get("stage_type")),String.valueOf(s.get("status")),((Number)s.get("attempt")).intValue(),(String)s.get("error_code"),StageDiagnostics.read((String)s.get("diagnostic_json"),(String)s.get("error_code"),((Number)s.get("attempt")).intValue()),TextExecutionPolicy.readProgress(s.get("text_progress_json")));
        return new Snapshot(id,String.valueOf(owner.get("job_id")),String.valueOf(owner.get("video_id")),String.valueOf(owner.get("instruction")),String.valueOf(owner.get("status")),((Number)owner.get("state_version")).longValue(),instant(owner.get("created_at")),instant(owner.get("updated_at")),
            new Budget(((Number)owner.get("max_text_calls")).intValue(),((Number)owner.get("text_calls_reserved")).intValue()),stage,new Source(String.valueOf(owner.get("source_hash")),String.valueOf(owner.get("transcript_hash")),((Number)owner.get("duration_ms")).longValue()),rev,
            planRows.isEmpty()?null:String.valueOf(planRows.getFirst().get("plan_hash")),effectiveConfirmations(owner),plan,JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances"),(String)owner.get("resumed_from_request_id"),answer==null?null:answer.source(),review);
    }
    private FrozenSource source(Map<String,Object> job){
        if(!"REVIEW_READY".equals(job.get("status"))||!"topics".equals(JSON.readTree(String.valueOf(job.get("config_json"))).path("analysis_mode").asText()))throw conflict("CLIP_SOURCE_NOT_READY","A completed topic analysis is required");
        String parent=String.valueOf(job.get("id")),video=String.valueOf(job.get("video_id")),hash=jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?",String.class,video);
        Map<String,Object> asr=completedStage(parent,"ASR"),timeline=completedStage(parent,"TIMELINE");
        var asrIdentity=identity(asr,job,hash);var manifest=artifacts.verifyManifest(String.valueOf(asr.get("manifest_key")),String.valueOf(asr.get("manifest_hash")),asrIdentity);
        var transcript=manifest.artifacts().stream().filter(a->a.kind().equals("transcript")).findFirst().orElseThrow();JsonNode utterances=artifacts.readJsonArtifact(transcript,asrIdentity);
        var timelineIdentity=identity(timeline,job,hash);JsonNode result=artifacts.readAnalysisResult(String.valueOf(timeline.get("manifest_key")),String.valueOf(timeline.get("manifest_hash")),timelineIdentity);
        long duration=number(result,"duration_ms");if(duration<=0||!utterances.isArray()||utterances.isEmpty()||utterances.size()>100000||!utterances.equals(result.path("utterances")))throw conflict("CLIP_SOURCE_INVALID","Frozen ASR and analysis must match");
        Set<String> ids=new HashSet<>();long previous=0;
        for(JsonNode row:utterances){String uid=id(row,"utterance_id");long start=number(row,"start_ms"),end=number(row,"end_ms");text(row,"text",100000);if(!ids.add(uid)||start<previous||end<=start||end>duration)throw conflict("CLIP_SOURCE_INVALID","Invalid source ASR chronology");previous=end;}
        String model=JSON.readTree(String.valueOf(job.get("config_json"))).path("text_model").asText();
        if(!preferredTextModel.isBlank())model=preferredTextModel;
        if(!model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw bad("INVALID_CLIP_REQUEST","Invalid configured text model");
        return new FrozenSource(video,hash,transcript.key(),transcript.sha256(),duration,utterances,model);
    }
    private Map<String,Object> completedStage(String parent,String type){var rows=jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type=? AND status='SUCCEEDED' FOR UPDATE",parent,type);if(rows.size()!=1)throw conflict("CLIP_SOURCE_UNAVAILABLE","Completed source artifacts are required");return rows.getFirst();}
    private ArtifactVerifier.ManifestIdentity identity(Map<String,Object> stage,Map<String,Object> job,String hash){return new ArtifactVerifier.ManifestIdentity(String.valueOf(job.get("id")),String.valueOf(job.get("video_id")),((Number)job.get("generation")).intValue(),String.valueOf(stage.get("id")),String.valueOf(stage.get("stage_type")),((Number)stage.get("attempt")).intValue(),String.valueOf(stage.get("run_token")),hash);}
    private ArtifactVerifier.ManifestIdentity identity(StageRow stage,Map<String,Object> job,String hash){return new ArtifactVerifier.ManifestIdentity(stage.jobId(),String.valueOf(job.get("video_id")),((Number)job.get("generation")).intValue(),stage.id(),stage.stageType(),stage.attempt(),stage.runToken(),hash);}
    private Map<String,Object> lockAnalysis(String id){var rows=jdbc.queryForList("SELECT * FROM analysis_job WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw missing("JOB_NOT_FOUND");return rows.getFirst();}
    private static void requireReady(Map<String,Object> owner){if(!"PLAN_READY".equals(owner.get("status")))throw conflict("CLIP_PLAN_NOT_READY","Wait for the clipping proposal");}
    private static void requireCurrent(Map<String,Object> owner,long rev){if(((Number)owner.get("latest_revision")).longValue()!=rev)throw conflict("CLIP_PLAN_REVISION_CONFLICT","The clipping plan changed; reload before saving");}
    public static String canonicalJson(JsonNode node){return JSON.writeValueAsString(canonical(node));}
    private static Object canonical(JsonNode node){if(node.isObject()){Map<String,Object> result=new TreeMap<>();for(String key:node.propertyNames())result.put(key,canonical(node.path(key)));return result;}if(node.isArray()){List<Object> result=new ArrayList<>();node.forEach(v->result.add(canonical(v)));return result;}if(node.isNull())return null;if(node.isString())return node.stringValue();if(node.isBoolean())return node.booleanValue();if(node.isIntegralNumber())return node.longValue();throw bad("INVALID_CLIP_PLAN","Unexpected number type");}
    public static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    public static String uuid(String value){try{String id=UUID.fromString(value).toString();if(!id.equals(value))throw new IllegalArgumentException();return id;}catch(Exception error){throw bad("INVALID_ARGUMENT","Expected a UUID");}}
    private static Instant instant(Object value){return value instanceof Timestamp stamp?stamp.toInstant():((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);}
    private static JobException bad(String code,String message){return new JobException(HttpStatus.BAD_REQUEST,code,message);}
    private static JobException conflict(String code,String message){return new JobException(HttpStatus.CONFLICT,code,message);}
    private static JobException missing(String code){return new JobException(HttpStatus.NOT_FOUND,code,"Requested clipping resource was not found");}
}
