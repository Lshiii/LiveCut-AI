package io.livecut.agent;

import static io.livecut.agent.AgentModels.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.captions.CaptionService;
import io.livecut.captions.CaptionSourceResolver;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import io.livecut.jobs.JobModels.StageRow;
import io.livecut.jobs.StageDiagnostics;
import io.livecut.jobs.TextExecutionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Independent conversations reuse published evidence, never the editable review or its budget. */
@Service
public class AgentService {
    private final JdbcTemplate jdbc;
    private final ArtifactVerifier artifacts;
    private final CaptionService captions;
    private final CaptionSourceResolver captionSources;
    private final TransactionTemplate tx;
    @Value("${LIVECUT_TEXT_MODEL:}") private String preferredTextModel="";
    @Value("${LIVECUT_TEXT_FALLBACK_MODELS:}") private String configuredFallbackModels="";
    @Value("${LIVECUT_AGENT_VISUAL_MODEL:}") private String configuredVisualModel="";
    private record FrozenSource(String video,String hash,String transcriptKey,String transcriptHash,String analysisKey,
                                String analysisHash,long duration,JsonNode utterances,JsonNode topics) {}
    public AgentService(JdbcTemplate jdbc,ArtifactVerifier artifacts,CaptionService captions,CaptionSourceResolver captionSources,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.artifacts=artifacts;this.captions=captions;this.captionSources=captionSources;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Session createSession(String jobId,JsonNode input,String key){
        String parent=uuid(jobId);key(key);if(input==null||!input.isObject()||!input.isEmpty())throw bad("INVALID_AGENT_SESSION","Session input must be empty");
        String fingerprint=sha("agent-session\n"+parent);
        try{return tx.execute(status->{
            var job=lockAnalysis(parent);
            var existing=jdbc.queryForList("SELECT * FROM agent_session WHERE idempotency_key=? FOR UPDATE",key);
            if(!existing.isEmpty())return existingSession(existing.getFirst(),fingerprint);
            var source=source(job);String id=UUID.randomUUID().toString();
            String sourceJson=canonical(AgentAnswerValidator.JSON.valueToTree(Map.of("utterances",source.utterances(),"topics",source.topics())));
            jdbc.update("INSERT INTO agent_session (id,job_id,video_id,idempotency_key,request_hash,source_hash,transcript_hash,transcript_key,analysis_hash,analysis_key,source_json,duration_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id,parent,source.video(),key,fingerprint,source.hash(),source.transcriptHash(),source.transcriptKey(),source.analysisHash(),source.analysisKey(),sourceJson,source.duration());
            return sessionSnapshot(lockSession(id,parent));
        });}catch(DuplicateKeyException collision){return tx.execute(status->{lockAnalysis(parent);var rows=jdbc.queryForList("SELECT * FROM agent_session WHERE idempotency_key=? FOR UPDATE",key);if(rows.isEmpty())throw collision;return existingSession(rows.getFirst(),fingerprint);});}
    }
    private Session existingSession(Map<String,Object> owner,String fingerprint){
        if(!fingerprint.equals(owner.get("request_hash")))throw conflict("IDEMPOTENCY_CONFLICT","Session key belongs to different input");return sessionSnapshot(owner);
    }
    public Page list(String jobId){String parent=uuid(jobId);return tx.execute(status->{lockAnalysis(parent);return new Page(jdbc.queryForList("SELECT * FROM agent_session WHERE job_id=? AND kind='VIDEO' ORDER BY created_at DESC,id DESC LIMIT 100",parent).stream().map(this::sessionSnapshot).toList());});}
    public Session getSession(String id){return tx.execute(status->{var owner=lockPublicSession(uuid(id));requireKind(owner,false);return sessionSnapshot(owner);});}
    public Turn getTurn(String id){return tx.execute(status->{var owner=lockPublicOwner(uuid(id));requireKind(owner,false);return turnSnapshot(owner);});}
    public Capabilities capabilities(){String model=configuredVisualModel.trim();boolean configured=validModel(model);return new Capabilities(configured?"frames":null,configured?model:null,32,12000,500);}
    public byte[] visualFrame(String id,String frameId){
        if(frameId==null||!frameId.matches("f00(?:0[1-9]|[12][0-9]|3[0-2])"))throw missing("AGENT_VISUAL_FRAME_NOT_FOUND");
        return tx.execute(status->{var owner=lockPublicOwner(uuid(id));requireKind(owner,false);
            if(!"SUCCEEDED".equals(owner.get("status"))||!"frames".equals(visualMode(owner)))throw missing("AGENT_VISUAL_FRAME_NOT_FOUND");
            var job=lockAnalysis(String.valueOf(owner.get("job_id")));sameSource(owner,source(job));
            var stages=jdbc.queryForList("SELECT * FROM job_stage WHERE owner_type='AGENT_TURN' AND owner_id=? FOR UPDATE",id);
            if(stages.size()!=1||!"AGENT_VISUAL".equals(stages.getFirst().get("stage_type"))||!"SUCCEEDED".equals(stages.getFirst().get("status")))throw conflict("AGENT_STAGE_UNAVAILABLE","Visual evidence execution is unavailable");
            var stage=stages.getFirst();var identity=identity(stage,job,String.valueOf(owner.get("source_hash")));
            JsonNode answer=AgentAnswerValidator.JSON.readTree(String.valueOf(owner.get("result_json")));
            if(!sha(canonical(answer)).equals(owner.get("result_hash")))throw conflict("AGENT_RESULT_INVALID","Stored answer failed integrity validation");
            AgentAnswerValidator.validate(answer,owner);
            var manifest=artifacts.verifyAgentAnswerManifest(String.valueOf(stage.get("manifest_key")),String.valueOf(stage.get("manifest_hash")),identity);
            var ref=manifest.artifacts().stream().filter(a->a.kind().equals("agent_answer")).findFirst().orElseThrow();
            if(!canonical(answer).equals(canonical(artifacts.readJsonArtifact(ref,identity))))throw conflict("AGENT_RESULT_INVALID","Stored answer no longer matches its artifact");
            byte[] bytes=AgentVisualArtifacts.verify(jdbc,artifacts,job,owner,answer,manifest,identity).get(frameId);
            if(bytes==null)throw missing("AGENT_VISUAL_FRAME_NOT_FOUND");return bytes;
        });
    }
    /** Resolves an original submission; business absence alone permits replay of its original key/body. */
    public Session findSessionSubmission(String jobId,String submissionKey){
        String parent=uuid(jobId);key(submissionKey);
        return tx.execute(status->{
            lockAnalysis(parent);
            var rows=jdbc.queryForList("SELECT * FROM agent_session WHERE idempotency_key=?",submissionKey);
            if(rows.isEmpty())throw missing("AGENT_SESSION_SUBMISSION_NOT_FOUND");var found=rows.getFirst();
            if(!parent.equals(found.get("job_id"))||!"VIDEO".equals(found.get("kind")))throw conflict("IDEMPOTENCY_CONFLICT","Submission belongs to a different conversation owner");
            var owner=lockSession(String.valueOf(found.get("id")),parent);
            if(!"VIDEO".equals(owner.get("kind")))throw conflict("IDEMPOTENCY_CONFLICT","Submission belongs to a different conversation owner");
            if(!sha("agent-session\n"+parent).equals(owner.get("request_hash")))throw invalidSubmission();
            return sessionSnapshot(owner);
        });
    }
    public Turn findTurnSubmission(String sessionId,String submissionKey){
        String id=uuid(sessionId);key(submissionKey);
        return tx.execute(status->{
            var session=lockPublicSession(id);requireKind(session,false);String parent=String.valueOf(session.get("job_id"));
            if(!sha("agent-session\n"+parent).equals(session.get("request_hash")))throw invalidSubmission();
            var rows=jdbc.queryForList("SELECT * FROM agent_turn WHERE idempotency_key=?",submissionKey);
            if(rows.isEmpty())throw missing("AGENT_TURN_SUBMISSION_NOT_FOUND");var found=rows.getFirst();
            if(!id.equals(found.get("session_id"))||!parent.equals(found.get("job_id")))throw conflict("IDEMPOTENCY_CONFLICT","Submission belongs to a different conversation owner");
            var owner=lockOwner(String.valueOf(found.get("id")),parent);requireKind(owner,false);
            String question=String.valueOf(owner.get("question"));int limit=((Number)owner.get("max_text_calls")).intValue();
            if(question.isBlank()||question.length()>2000||limit<1||limit>24||!turnFingerprint(id,question,limit,visualMode(owner)).equals(owner.get("request_hash")))throw invalidSubmission();
            return turnSnapshot(owner);
        });
    }
    public Turn createTurn(String sessionId,JsonNode input,String key){
        String id=uuid(sessionId);key(key);
        if(input==null||!input.isObject()||!input.has("question")||!Set.of("question","max_text_calls","visual_mode").containsAll(input.propertyNames()))throw bad("INVALID_AGENT_TURN","A question and optional text budget are required");
        JsonNode q=input.path("question");if(!q.isString()||q.stringValue().isBlank()||q.stringValue().length()>2000)throw bad("INVALID_AGENT_TURN","Question must be 1..2000 characters");
        int budget=8;if(input.has("max_text_calls")){JsonNode n=input.path("max_text_calls");if(!n.isIntegralNumber()||!n.canConvertToInt())throw bad("INVALID_AGENT_TURN","Text budget must be 1..24");budget=n.intValue();}
        if(budget<1||budget>24)throw bad("INVALID_AGENT_TURN","Text budget must be 1..24");
        String mode="none";if(input.has("visual_mode")){JsonNode value=input.path("visual_mode");if(!value.isString()||!Set.of("none","frames").contains(value.stringValue()))throw bad("INVALID_AGENT_TURN","Visual mode must be none or frames");mode=value.stringValue();}
        final String visual=mode;final int limit=budget;String question=q.stringValue(),fingerprint=turnFingerprint(id,question,limit,visual);
        try{return tx.execute(status->{
            var session=lockPublicSession(id);requireKind(session,false);String parent=String.valueOf(session.get("job_id"));
            var existing=jdbc.queryForList("SELECT * FROM agent_turn WHERE idempotency_key=? FOR UPDATE",key);
            if(!existing.isEmpty())return existingTurn(existing.getFirst(),fingerprint);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE session_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,id)>0)throw conflict("AGENT_SESSION_BUSY","Wait for or cancel the active turn");
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE session_id=?",Integer.class,id);
            if(count>=100)throw conflict("AGENT_SESSION_LIMIT","Start a new session after 100 turns");
            var job=lockAnalysis(parent);var current=source(job);sameSource(session,current);
            String turn=UUID.randomUUID().toString(),stage=UUID.randomUUID().toString(),model=visual.equals("frames")?visualModel():model(job);
            String fallbacks=visual.equals("frames")?"[]":TextExecutionPolicy.serialize(TextExecutionPolicy.configuredFallbacks(configuredFallbackModels));
            String history=history(id);
            jdbc.update("INSERT INTO agent_turn (id,session_id,job_id,sequence_number,question,idempotency_key,request_hash,text_model_id,text_fallback_models_json,max_text_calls,history_json,visual_mode) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                turn,id,parent,count+1,question,key,fingerprint,model,fallbacks,limit,history,visual);
            jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,'AGENT_TURN',?, ?,?,'READY',?)",
                stage,parent,turn,visual.equals("frames")?"AGENT_VISUAL":"SEGMENT","agent-"+turn,sha(fingerprint+"\n"+current.hash()+"\n"+current.transcriptHash()+"\n"+current.analysisHash()+"\n"+model+"\n"+fallbacks+"\n"+history));
            return turnSnapshot(lockOwner(turn,parent));
        });}catch(DuplicateKeyException collision){return tx.execute(status->{lockPublicSession(id);var rows=jdbc.queryForList("SELECT * FROM agent_turn WHERE idempotency_key=? FOR UPDATE",key);if(rows.isEmpty())throw collision;return existingTurn(rows.getFirst(),fingerprint);});}
    }
    private Turn existingTurn(Map<String,Object> turn,String fingerprint){
        if(!fingerprint.equals(turn.get("request_hash")))throw conflict("IDEMPOTENCY_CONFLICT","Turn key belongs to different input");return turnSnapshot(turn);
    }
    public Turn cancel(String id){String turn=uuid(id);return tx.execute(status->{
        var owner=lockPublicOwner(turn);requireKind(owner,false);if("CANCELLED".equals(owner.get("status")))return turnSnapshot(owner);
        if(!active(owner))throw conflict("AGENT_TURN_NOT_CANCELLABLE","Only queued or running turns can be cancelled");
        var stages=jdbc.queryForList("SELECT id,status FROM job_stage FORCE INDEX (idx_stage_owner_status) WHERE owner_type='AGENT_TURN' AND owner_id=? FOR UPDATE",turn);
        if(stages.size()!=1||!Set.of("READY","RUNNING","RETRY_WAIT").contains(stages.getFirst().get("status")))throw conflict("AGENT_STAGE_UNAVAILABLE","Agent stage already changed");
        jdbc.update("UPDATE job_stage SET status='CANCELLED',run_token=NULL,worker_id=NULL,worker_session_id=NULL,lease_until=NULL,hard_deadline_at=NULL,last_retryable=FALSE,error_code='USER_CANCELLED',diagnostic_json=NULL,text_progress_json=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",stages.getFirst().get("id"));
        jdbc.update("UPDATE agent_turn SET status='CANCELLED',state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",turn);
        return turnSnapshot(lockOwner(turn,String.valueOf(owner.get("job_id"))));
    });}
    public Map<String,Object> lockOwner(String id,String job){
        var rows=jdbc.queryForList("SELECT t.*,s.kind,s.caption_export_id,s.caption_clip_id,s.video_id,s.source_hash,s.transcript_hash,s.transcript_key,s.analysis_key,s.analysis_hash,s.source_json,s.duration_ms FROM agent_turn t JOIN agent_session s ON s.id=t.session_id WHERE t.id=? AND t.job_id=? AND s.job_id=t.job_id FOR UPDATE",id,job);
        if(rows.isEmpty())throw missing("AGENT_TURN_NOT_FOUND");return rows.getFirst();
    }
    private Map<String,Object> lockPublicOwner(String id){
        var hints=jdbc.queryForList("SELECT job_id,session_id FROM agent_turn WHERE id=?",id);if(hints.isEmpty())throw missing("AGENT_TURN_NOT_FOUND");
        var hint=hints.getFirst();String job=String.valueOf(hint.get("job_id"));lockAnalysis(job);lockSession(String.valueOf(hint.get("session_id")),job);return lockOwner(id,job);
    }
    private Map<String,Object> lockPublicSession(String id){
        var parents=jdbc.queryForList("SELECT job_id FROM agent_session WHERE id=?",String.class,id);if(parents.isEmpty())throw missing("AGENT_SESSION_NOT_FOUND");lockAnalysis(parents.getFirst());return lockSession(id,parents.getFirst());
    }
    private Map<String,Object> lockSession(String id,String job){var rows=jdbc.queryForList("SELECT * FROM agent_session WHERE id=? AND job_id=? FOR UPDATE",id,job);if(rows.isEmpty())throw missing("AGENT_SESSION_NOT_FOUND");return rows.getFirst();}
    public boolean active(Map<String,Object> owner){return owner!=null&&Set.of("QUEUED","RUNNING").contains(owner.get("status"));}
    public void refreshLocked(String id){
        var rows=jdbc.queryForList("SELECT status,attempt FROM job_stage WHERE owner_type='AGENT_TURN' AND owner_id=? FOR UPDATE",id);if(rows.size()!=1)throw conflict("AGENT_STAGE_UNAVAILABLE","Agent stage is unavailable");
        var stage=rows.getFirst();String status=String.valueOf(stage.get("status"));
        String next=switch(status){case "SUCCEEDED" -> "SUCCEEDED";case "FAILED" -> "FAILED";case "CANCELLED" -> "CANCELLED";default -> ((Number)stage.get("attempt")).intValue()>0?"RUNNING":"QUEUED";};
        jdbc.update("UPDATE agent_turn SET status=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status<>'CANCELLED'",next,id);
    }
    public Map<String,Object> claimConfig(StageRow stage){
        var owner=lockOwner(stage.ownerId(),stage.jobId());Map<String,Object> turn=new TreeMap<>();
        if(isCaption(owner)){if(!CaptionProofreadValidator.STAGE.equals(stage.stageType()))throw conflict("CAPTION_PROOFREAD_STAGE_INVALID","Caption proofreading requires its declared worker capability");
            turn.put("kind","CAPTION");turn.put("caption_context",CaptionProofreadValidator.storedContext(owner));}
        else if(!(visualMode(owner).equals("frames")?"AGENT_VISUAL":"SEGMENT").equals(stage.stageType()))throw conflict("AGENT_STAGE_UNAVAILABLE","Video conversation stage type changed");
        if("frames".equals(visualMode(owner))){turn.put("visual_mode","frames");if(!TextExecutionPolicy.storedFallbacks(owner.get("text_fallback_models_json")).isEmpty())throw conflict("AGENT_STAGE_UNAVAILABLE","Visual conversations cannot use text fallbacks");}
        for(String field:List.of("session_id","question","duration_ms"))turn.put(field,owner.get(field));
        turn.put("turn_id",stage.ownerId());turn.put("source_job_id",stage.jobId());turn.put("source_transcript_key",owner.get("transcript_key"));turn.put("source_transcript_hash",owner.get("transcript_hash"));
        turn.put("source_analysis_key",owner.get("analysis_key"));turn.put("source_analysis_hash",owner.get("analysis_hash"));turn.put("max_tool_steps",isCaption(owner)?Math.min(4,((Number)owner.get("max_text_calls")).intValue()-1):4);
        turn.put("history",isCaption(owner)?List.of():AgentAnswerValidator.JSON.readValue(String.valueOf(owner.get("history_json")),List.class));
        return Map.of("agent_turn",turn,"text_model",owner.get("text_model_id"),"text_fallback_models",TextExecutionPolicy.storedFallbacks(owner.get("text_fallback_models_json")),
            "max_text_calls",owner.get("max_text_calls"),"max_asr_submissions",0,"visual_provider","none");
    }
    public ArtifactVerifier.VerifiedManifest verifyResult(StageRow stage,Map<String,Object> job,String key,String hash){
        var owner=lockOwner(stage.ownerId(),stage.jobId());var identity=identity(stage,job,String.valueOf(owner.get("source_hash")));
        if(isCaption(owner)){var manifest=artifacts.verifyCaptionProofreadManifest(key,hash,identity);var file=manifest.artifacts().stream().filter(a->a.kind().equals("caption_proofread")).findFirst().orElseThrow();CaptionProofreadValidator.validate(artifacts.readJsonArtifact(file,identity),owner);return manifest;}
        var manifest=artifacts.verifyAgentAnswerManifest(key,hash,identity);
        var file=manifest.artifacts().stream().filter(a->a.kind().equals("agent_answer")).findFirst().orElseThrow();
        validateAnswer(stage,job,owner,artifacts.readJsonArtifact(file,identity),manifest,identity);return manifest;
    }
    public void recordSuccess(StageRow stage,Map<String,Object> job,ArtifactVerifier.VerifiedManifest manifest){
        var owner=lockOwner(stage.ownerId(),stage.jobId());var identity=identity(stage,job,String.valueOf(owner.get("source_hash")));
        var file=manifest.artifacts().stream().filter(a->a.kind().equals(isCaption(owner)?"caption_proofread":"agent_answer")).findFirst().orElseThrow();
        JsonNode result=isCaption(owner)?CaptionProofreadValidator.validate(artifacts.readJsonArtifact(file,identity),owner):validateAnswer(stage,job,owner,artifacts.readJsonArtifact(file,identity),manifest,identity);String serialized=canonical(result);
        jdbc.update("UPDATE agent_turn SET result_json=?,result_hash=? WHERE id=?",serialized,sha(serialized),stage.ownerId());
    }
    private JsonNode validateAnswer(StageRow stage,Map<String,Object> job,Map<String,Object> owner,JsonNode result,ArtifactVerifier.VerifiedManifest manifest,ArtifactVerifier.ManifestIdentity identity){
        if(!(visualMode(owner).equals("frames")?"AGENT_VISUAL":"SEGMENT").equals(stage.stageType()))throw conflict("AGENT_STAGE_UNAVAILABLE","Answer stage does not match frozen mode");
        AgentAnswerValidator.validate(result,owner);
        if("frames".equals(visualMode(owner))){sameSource(owner,source(job));AgentVisualArtifacts.verify(jdbc,artifacts,job,owner,result,manifest,identity);}
        return result;
    }
    private String history(String session){
        var rows=jdbc.queryForList("SELECT question,result_json,result_hash FROM agent_turn WHERE session_id=? AND status='SUCCEEDED' ORDER BY sequence_number DESC LIMIT 6",session);
        List<Map<String,String>> result=new ArrayList<>();int characters=0;
        for(var row:rows){JsonNode prior=AgentAnswerValidator.JSON.readTree(String.valueOf(row.get("result_json")));if(!sha(canonical(prior)).equals(row.get("result_hash")))throw conflict("AGENT_HISTORY_INVALID","Stored conversation history failed integrity validation");
            String question=String.valueOf(row.get("question")),answer=prior.path("answer").asText();int size=question.length()+answer.length();
            if(characters+size>12000)break;characters+=size;result.add(Map.of("question",question,"answer",answer));}
        Collections.reverse(result);return canonical(AgentAnswerValidator.JSON.valueToTree(result));
    }
    private Session sessionSnapshot(Map<String,Object> owner){
        String id=String.valueOf(owner.get("id"));var turns=jdbc.queryForList("SELECT * FROM agent_turn WHERE session_id=? ORDER BY sequence_number",id).stream().map(this::turnSnapshot).toList();
        return new Session(id,String.valueOf(owner.get("job_id")),String.valueOf(owner.get("video_id")),instant(owner.get("created_at")),
            new Source(String.valueOf(owner.get("source_hash")),String.valueOf(owner.get("transcript_hash")),((Number)owner.get("duration_ms")).longValue()),turns);
    }
    private Turn turnSnapshot(Map<String,Object> owner){
        String id=String.valueOf(owner.get("id"));var rows=jdbc.queryForList("SELECT * FROM job_stage WHERE owner_type='AGENT_TURN' AND owner_id=?",id);if(rows.size()!=1)throw conflict("AGENT_STAGE_UNAVAILABLE","Agent stage is unavailable");var s=rows.getFirst();
        Stage stage=new Stage(String.valueOf(s.get("id")),String.valueOf(s.get("stage_type")),String.valueOf(s.get("status")),((Number)s.get("attempt")).intValue(),(String)s.get("error_code"),
            StageDiagnostics.read((String)s.get("diagnostic_json"),(String)s.get("error_code"),((Number)s.get("attempt")).intValue()),TextExecutionPolicy.readProgress(s.get("text_progress_json")));
        JsonNode result="SUCCEEDED".equals(owner.get("status"))&&owner.get("result_json")!=null?AgentAnswerValidator.JSON.readTree(String.valueOf(owner.get("result_json"))):null;
        return new Turn(id,String.valueOf(owner.get("session_id")),String.valueOf(owner.get("job_id")),String.valueOf(owner.get("question")),((Number)owner.get("sequence_number")).intValue(),
            String.valueOf(owner.get("status")),((Number)owner.get("state_version")).longValue(),instant(owner.get("created_at")),instant(owner.get("updated_at")),
            new Budget(((Number)owner.get("max_text_calls")).intValue(),((Number)owner.get("text_calls_reserved")).intValue()),stage,result,visualMode(owner));
    }
    private FrozenSource source(Map<String,Object> job){
        if(!"REVIEW_READY".equals(job.get("status"))||!"topics".equals(AgentAnswerValidator.JSON.readTree(String.valueOf(job.get("config_json"))).path("analysis_mode").asText()))throw conflict("AGENT_SOURCE_NOT_READY","A completed topics analysis is required");
        String parent=String.valueOf(job.get("id")),video=String.valueOf(job.get("video_id")),hash=jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?",String.class,video);
        var asr=completedStage(parent,"ASR");var asrIdentity=identity(asr,job,hash);var asrManifest=artifacts.verifyManifest(String.valueOf(asr.get("manifest_key")),String.valueOf(asr.get("manifest_hash")),asrIdentity);
        var transcript=asrManifest.artifacts().stream().filter(a->a.kind().equals("transcript")).findFirst().orElseThrow();var utterances=artifacts.readJsonArtifact(transcript,asrIdentity);
        var timeline=completedStage(parent,"TIMELINE");var timelineIdentity=identity(timeline,job,hash);var timelineManifest=artifacts.verifyManifest(String.valueOf(timeline.get("manifest_key")),String.valueOf(timeline.get("manifest_hash")),timelineIdentity);
        var analysis=timelineManifest.artifacts().stream().filter(a->a.kind().equals("analysis_result")).findFirst().orElseThrow();var result=artifacts.readJsonArtifact(analysis,timelineIdentity);
        if(!"topics".equals(result.path("analysis_mode").asText())||!parent.equals(result.path("job_id").asText())||!video.equals(result.path("video_id").asText())
            ||!result.path("analysis_generation").isIntegralNumber()||result.path("analysis_generation").longValue()!=((Number)job.get("generation")).longValue())throw conflict("AGENT_SOURCE_INVALID","Published analysis identity does not match this source");
        long duration=AgentAnswerValidator.number(result,"duration_ms");
        if(duration<=0||!utterances.isArray()||utterances.isEmpty()||utterances.size()>100000||!utterances.equals(result.path("utterances"))||!result.path("segments").isArray())throw conflict("AGENT_SOURCE_INVALID","Frozen ASR and topics must match");
        Set<String> ids=new HashSet<>();long previous=0;
        for(JsonNode row:utterances){String uid=AgentAnswerValidator.text(row,"utterance_id",128);long start=AgentAnswerValidator.number(row,"start_ms"),end=AgentAnswerValidator.number(row,"end_ms");AgentAnswerValidator.text(row,"text",100000);
            if(!uid.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||!ids.add(uid)||start<previous||end<=start||end>duration)throw conflict("AGENT_SOURCE_INVALID","Invalid ASR chronology");previous=end;}
        return new FrozenSource(video,hash,transcript.key(),transcript.sha256(),analysis.key(),analysis.sha256(),duration,utterances,result.path("segments"));
    }
    private void sameSource(Map<String,Object> session,FrozenSource current){
        JsonNode frozen=AgentAnswerValidator.JSON.readTree(String.valueOf(session.get("source_json")));
        if(!current.video().equals(session.get("video_id"))||!current.hash().equals(session.get("source_hash"))||!current.transcriptKey().equals(session.get("transcript_key"))
            ||!current.transcriptHash().equals(session.get("transcript_hash"))||!current.analysisKey().equals(session.get("analysis_key"))||!current.analysisHash().equals(session.get("analysis_hash"))
            ||current.duration()!=((Number)session.get("duration_ms")).longValue()||!frozen.path("utterances").equals(current.utterances())||!frozen.path("topics").equals(current.topics()))throw conflict("AGENT_SOURCE_CHANGED","Published evidence no longer matches this session");
    }
    public CaptionProofreadModels.Session createCaptionSession(String exportValue,String clipValue,JsonNode input,String key){
        String export=uuid(exportValue),clip=uuid(clipValue);key(key);captionFields(input,Set.of("caption_revision","caption_content_hash"),Set.of("caption_revision","caption_content_hash"));
        long revision=captionNumber(input,"caption_revision");String contentHash=input.path("caption_content_hash").asText();
        if(!input.path("caption_content_hash").isString()||!contentHash.matches("[a-f0-9]{64}"))throw invalidCaption("A saved caption content hash is required");
        String fingerprint=sha("caption-session\n"+export+"\n"+clip+"\n"+revision+"\n"+contentHash);
        try{return tx.execute(status->{
            var job=lockCaptionParent(export,clip);
            var existing=jdbc.queryForList("SELECT * FROM agent_session WHERE idempotency_key=? FOR UPDATE",key);
            if(!existing.isEmpty())return existingCaptionSession(existing.getFirst(),fingerprint);
            var resolved=captionSources.resolve(export,clip);var caption=captions.get(export,clip,revision);
            if(!caption.contentHash().equals(contentHash))throw conflict("CAPTION_PROOFREAD_REVISION_MISMATCH","The saved caption revision hash does not match");
            if(caption.cues().isEmpty())throw invalidCaption("There are no saved subtitles to proofread");
            var source=captionSource(job,resolved);JsonNode captionJson=AgentAnswerValidator.JSON.valueToTree(caption);
            String snapshotHash=sha(canonical(captionJson)),sourceJson=canonical(AgentAnswerValidator.JSON.valueToTree(Map.of("utterances",source.utterances(),"topics",List.of(),"caption",captionJson,"caption_snapshot_hash",snapshotHash)));
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO agent_session (id,job_id,video_id,idempotency_key,request_hash,source_hash,transcript_hash,transcript_key,analysis_hash,analysis_key,source_json,duration_ms,kind,caption_export_id,caption_clip_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'CAPTION',?,?)",
                id,job.get("id"),source.video(),key,fingerprint,source.hash(),source.transcriptHash(),source.transcriptKey(),source.analysisHash(),source.analysisKey(),sourceJson,source.duration(),export,clip);
            return captionSessionSnapshot(lockSession(id,String.valueOf(job.get("id"))));
        });}catch(DuplicateKeyException collision){return tx.execute(status->{lockCaptionParent(export,clip);var rows=jdbc.queryForList("SELECT * FROM agent_session WHERE idempotency_key=? FOR UPDATE",key);if(rows.isEmpty())throw collision;return existingCaptionSession(rows.getFirst(),fingerprint);});}
    }
    private CaptionProofreadModels.Session existingCaptionSession(Map<String,Object> owner,String fingerprint){
        requireKind(owner,true);if(!fingerprint.equals(owner.get("request_hash")))throw conflict("IDEMPOTENCY_CONFLICT","This key belongs to different caption input");return captionSessionSnapshot(owner);
    }
    public CaptionProofreadModels.Page listCaptionSessions(String exportValue,String clipValue){String export=uuid(exportValue),clip=uuid(clipValue);return tx.execute(status->{
        lockCaptionParent(export,clip);return new CaptionProofreadModels.Page(jdbc.queryForList("SELECT * FROM agent_session WHERE kind='CAPTION' AND caption_export_id=? AND caption_clip_id=? ORDER BY created_at DESC,id DESC LIMIT 100",export,clip).stream().map(this::captionSessionSnapshot).toList());});}
    public CaptionProofreadModels.Session getCaptionSession(String value){return tx.execute(status->{var owner=lockPublicSession(uuid(value));requireKind(owner,true);return captionSessionSnapshot(owner);});}
    public CaptionProofreadModels.Turn getCaptionTurn(String value){return tx.execute(status->{var owner=lockPublicOwner(uuid(value));requireKind(owner,true);return captionTurnSnapshot(owner);});}
    public CaptionProofreadModels.TurnPage captionTurns(String value,int offset,int limit){
        if(offset<0||offset>10000||limit<1||limit>100)throw invalidCaption("History offset must be 0..10000 and limit 1..100");
        return tx.execute(status->{var owner=lockPublicSession(uuid(value));requireKind(owner,true);return captionTurnPage(owner,offset,limit);});
    }
    public CaptionProofreadModels.Turn createCaptionTurn(String value,JsonNode input,String key){
        String id=uuid(value);key(key);captionFields(input,Set.of("offset","max_text_calls","instruction"),Set.of("offset"));
        long supplied=captionNumber(input,"offset");if(supplied>Integer.MAX_VALUE)throw invalidCaption("Invalid subtitle batch offset");int offset=(int)supplied;
        int budget=4;if(input.has("max_text_calls")){long number=captionNumber(input,"max_text_calls");if(number<1||number>24)throw invalidCaption("Text budget must be 1..24");budget=(int)number;}
        String question=CaptionProofreadValidator.DEFAULT_INSTRUCTION;
        if(input.has("instruction")){JsonNode instruction=input.path("instruction");if(!instruction.isString()||!CaptionProofreadValidator.validText(instruction.stringValue(),1000,true))throw invalidCaption("Instruction must contain valid text of at most 1000 characters");if(!instruction.stringValue().isBlank())question=instruction.stringValue();}
        final int limit=budget;final String instruction=question,fingerprint=sha("caption-turn\n"+id+"\n"+offset+"\n"+instruction+"\n"+limit);
        try{return tx.execute(status->{
            var session=lockPublicSession(id);requireKind(session,true);String parent=String.valueOf(session.get("job_id"));
            var existing=jdbc.queryForList("SELECT * FROM agent_turn WHERE idempotency_key=? FOR UPDATE",key);
            if(!existing.isEmpty())return existingCaptionTurn(existing.getFirst(),fingerprint);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE session_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,id)>0)throw conflict("AGENT_SESSION_BUSY","Wait for or cancel the active batch");
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE session_id=?",Integer.class,id);
            if(count>=10000)throw conflict("CAPTION_PROOFREAD_SESSION_LIMIT","Start a new caption proofread session after 10000 batches");
            JsonNode frozen=CaptionProofreadValidator.frozen(session),caption=frozen.path("caption");
            String sourceExport=String.valueOf(session.get("caption_export_id")),sourceClip=String.valueOf(session.get("caption_clip_id"));
            var resolved=captionSources.resolve(sourceExport,sourceClip);var current=captions.get(sourceExport,sourceClip,caption.path("revision").longValue());
            if(!canonical(captionIdentity(caption)).equals(canonical(captionIdentity(AgentAnswerValidator.JSON.valueToTree(current)))))throw conflict("CAPTION_PROOFREAD_SOURCE_CHANGED","The named immutable caption revision no longer matches");
            var job=lockAnalysis(parent);sameSource(session,captionSource(job,resolved));JsonNode context=CaptionProofreadValidator.context(frozen,offset);
            String turn=UUID.randomUUID().toString(),stage=UUID.randomUUID().toString(),model=model(job),fallbacks=TextExecutionPolicy.serialize(TextExecutionPolicy.configuredFallbacks(configuredFallbackModels));
            jdbc.update("INSERT INTO agent_turn (id,session_id,job_id,sequence_number,question,idempotency_key,request_hash,text_model_id,text_fallback_models_json,max_text_calls,history_json,caption_context_json) VALUES (?,?,?,?,?,?,?,?,?,?,'[]',?)",
                turn,id,parent,count+1,instruction,key,fingerprint,model,fallbacks,limit,canonical(context));
            jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,'AGENT_TURN',?,'CAPTION_PROOFREAD',?,'READY',?)",
                stage,parent,turn,"caption-"+turn,sha(fingerprint+"\n"+session.get("source_hash")+"\n"+session.get("transcript_hash")+"\n"+session.get("analysis_hash")+"\n"+model+"\n"+fallbacks+"\n"+canonical(context)));
            return captionTurnSnapshot(lockOwner(turn,parent));
        });}catch(DuplicateKeyException collision){return tx.execute(status->{var session=lockPublicSession(id);requireKind(session,true);var rows=jdbc.queryForList("SELECT * FROM agent_turn WHERE idempotency_key=? FOR UPDATE",key);if(rows.isEmpty())throw collision;return existingCaptionTurn(rows.getFirst(),fingerprint);});}
    }
    private CaptionProofreadModels.Turn existingCaptionTurn(Map<String,Object> owner,String fingerprint){
        if(!fingerprint.equals(owner.get("request_hash")))throw conflict("IDEMPOTENCY_CONFLICT","This key belongs to a different caption batch");
        var locked=lockOwner(String.valueOf(owner.get("id")),String.valueOf(owner.get("job_id")));requireKind(locked,true);return captionTurnSnapshot(locked);
    }
    public CaptionProofreadModels.Turn cancelCaptionTurn(String value){String id=uuid(value);return tx.execute(status->{var owner=lockPublicOwner(id);requireKind(owner,true);
        if("CANCELLED".equals(owner.get("status")))return captionTurnSnapshot(owner);
        if(!active(owner))throw conflict("CAPTION_PROOFREAD_NOT_CANCELLABLE","Only queued or running caption batches may be cancelled");
        var stages=jdbc.queryForList("SELECT id,status FROM job_stage FORCE INDEX (idx_stage_owner_status) WHERE owner_type='AGENT_TURN' AND owner_id=? FOR UPDATE",id);
        if(stages.size()!=1||!Set.of("READY","RUNNING","RETRY_WAIT").contains(stages.getFirst().get("status")))throw conflict("AGENT_STAGE_UNAVAILABLE","Caption proofreading stage changed");
        jdbc.update("UPDATE job_stage SET status='CANCELLED',run_token=NULL,worker_id=NULL,worker_session_id=NULL,lease_until=NULL,hard_deadline_at=NULL,last_retryable=FALSE,error_code='USER_CANCELLED',diagnostic_json=NULL,text_progress_json=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",stages.getFirst().get("id"));
        jdbc.update("UPDATE agent_turn SET status='CANCELLED',state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
        return captionTurnSnapshot(lockOwner(id,String.valueOf(owner.get("job_id"))));});}
    private CaptionProofreadModels.Session captionSessionSnapshot(Map<String,Object> owner){
        requireKind(owner,true);JsonNode frozen=CaptionProofreadValidator.frozen(owner),caption=frozen.path("caption");
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE session_id=?",Integer.class,owner.get("id"));
        int offset=Math.max(0,count-20);var page=captionTurnPage(owner,offset,20,frozen);
        var source=new CaptionProofreadModels.Source(String.valueOf(owner.get("source_hash")),String.valueOf(owner.get("transcript_hash")),((Number)owner.get("duration_ms")).longValue(),
            caption.path("export_id").asText(),caption.path("clip_id").asText(),caption.path("revision").longValue(),caption.path("content_hash").asText(),frozen.path("caption_snapshot_hash").asText(),caption.path("source"),caption.path("cues").size());
        return new CaptionProofreadModels.Session(String.valueOf(owner.get("id")),String.valueOf(owner.get("job_id")),String.valueOf(owner.get("video_id")),instant(owner.get("created_at")),source,count,offset,page.items());
    }
    private CaptionProofreadModels.TurnPage captionTurnPage(Map<String,Object> owner,int offset,int limit){
        return captionTurnPage(owner,offset,limit,CaptionProofreadValidator.frozen(owner));
    }
    private CaptionProofreadModels.TurnPage captionTurnPage(Map<String,Object> owner,int offset,int limit,JsonNode frozen){
        String id=String.valueOf(owner.get("id")),parent=String.valueOf(owner.get("job_id"));int total=jdbc.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE session_id=?",Integer.class,id),start=Math.min(offset,total);
        var rows=jdbc.queryForList("SELECT * FROM agent_turn WHERE session_id=? AND job_id=? ORDER BY sequence_number LIMIT ? OFFSET ?",id,parent,limit,start);
        List<CaptionProofreadModels.Turn> items=new ArrayList<>();
        for(var row:rows){Map<String,Object> turn=new HashMap<>(row);for(String field:List.of("kind","caption_export_id","caption_clip_id","video_id","source_hash","transcript_hash","transcript_key","analysis_key","analysis_hash","source_json","duration_ms"))turn.put(field,owner.get(field));items.add(captionTurnSnapshot(turn,frozen));}
        return new CaptionProofreadModels.TurnPage(items,total,limit,start);
    }
    private CaptionProofreadModels.Turn captionTurnSnapshot(Map<String,Object> owner){
        return captionTurnSnapshot(owner,CaptionProofreadValidator.frozen(owner));
    }
    private CaptionProofreadModels.Turn captionTurnSnapshot(Map<String,Object> owner,JsonNode frozen){
        requireKind(owner,true);JsonNode context=CaptionProofreadValidator.storedContext(owner,frozen);var turn=turnSnapshot(owner);JsonNode result=turn.result();
        if(result!=null){if(!sha(canonical(result)).equals(owner.get("result_hash")))throw conflict("CAPTION_PROOFREAD_RESULT_INVALID","Stored caption suggestions failed integrity validation");CaptionProofreadValidator.validate(result,owner,frozen);}
        if(!CaptionProofreadValidator.STAGE.equals(turn.stage().stageType()))throw conflict("CAPTION_PROOFREAD_STAGE_INVALID","Stored caption batch stage changed");
        List<CaptionProofreadModels.Evidence> evidence=new ArrayList<>();
        if(result!=null){Set<String> ids=new HashSet<>();result.path("suggestions").forEach(item->item.path("evidence_utterance_ids").forEach(id->ids.add(id.asText())));
            for(JsonNode row:frozen.path("utterances"))if(ids.contains(row.path("utterance_id").asText()))evidence.add(new CaptionProofreadModels.Evidence(row.path("utterance_id").asText(),row.path("start_ms").longValue(),row.path("end_ms").longValue(),row.path("text").asText()));}
        return new CaptionProofreadModels.Turn(turn.id(),turn.sessionId(),turn.jobId(),turn.question(),turn.sequence(),turn.status(),turn.stateVersion(),turn.createdAt(),turn.updatedAt(),turn.budget(),turn.stage(),CaptionProofreadValidator.batch(context),result,evidence);
    }
    private Map<String,Object> lockCaptionParent(String export,String clip){
        var rows=jdbc.queryForList("SELECT e.job_id FROM export_job e JOIN export_clip c ON c.export_id=e.id WHERE e.id=? AND c.id=?",export,clip);
        if(rows.size()!=1)throw missing("CAPTION_PROOFREAD_SOURCE_NOT_FOUND");return lockAnalysis(String.valueOf(rows.getFirst().get("job_id")));
    }
    private FrozenSource captionSource(Map<String,Object> job,CaptionSourceResolver.Resolved resolved){
        if(!"REVIEW_READY".equals(job.get("status"))||!resolved.jobId().equals(job.get("id")))throw conflict("CAPTION_PROOFREAD_SOURCE_NOT_READY","The published source analysis must be complete");
        String parent=String.valueOf(job.get("id")),video=String.valueOf(job.get("video_id")),hash=resolved.basis().path("source_video_hash").asText();
        if(!hash.equals(jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?",String.class,video)))throw conflict("CAPTION_PROOFREAD_SOURCE_CHANGED","The source video changed");
        var asr=completedStage(parent,"ASR");var asrIdentity=identity(asr,job,hash);var asrManifest=artifacts.verifyManifest(String.valueOf(asr.get("manifest_key")),String.valueOf(asr.get("manifest_hash")),asrIdentity);
        var transcript=asrManifest.artifacts().stream().filter(a->a.kind().equals("transcript")).findFirst().orElseThrow();var utterances=artifacts.readJsonArtifact(transcript,asrIdentity);
        var timeline=completedStage(parent,"TIMELINE");var timelineIdentity=identity(timeline,job,hash);var timelineManifest=artifacts.verifyManifest(String.valueOf(timeline.get("manifest_key")),String.valueOf(timeline.get("manifest_hash")),timelineIdentity);
        var analysis=timelineManifest.artifacts().stream().filter(a->a.kind().equals("analysis_result")).findFirst().orElseThrow();var result=artifacts.readJsonArtifact(analysis,timelineIdentity);
        long duration=resolved.basis().path("source_duration_ms").longValue();
        if(!utterances.isArray()||utterances.isEmpty()||utterances.size()>100000||!utterances.equals(result.path("utterances"))||duration<=0||!resolved.source().transcriptHash().equals(transcript.sha256())
            ||!parent.equals(result.path("job_id").asText())||!video.equals(result.path("video_id").asText())
            ||!result.path("analysis_generation").isIntegralNumber()||result.path("analysis_generation").longValue()!=((Number)job.get("generation")).longValue()
            ||!result.path("duration_ms").isIntegralNumber()||result.path("duration_ms").longValue()!=duration)throw conflict("CAPTION_PROOFREAD_SOURCE_CHANGED","The frozen ASR no longer matches this caption source");
        Set<String> ids=new HashSet<>();long previous=0;for(JsonNode row:utterances){String id=AgentAnswerValidator.text(row,"utterance_id",128);long start=AgentAnswerValidator.number(row,"start_ms"),end=AgentAnswerValidator.number(row,"end_ms");AgentAnswerValidator.text(row,"text",100000);
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||!ids.add(id)||start<previous||end<=start||end>duration)throw conflict("CAPTION_PROOFREAD_SOURCE_CHANGED","Invalid source sentence chronology");previous=end;}
        return new FrozenSource(video,hash,transcript.key(),transcript.sha256(),analysis.key(),analysis.sha256(),duration,utterances,AgentAnswerValidator.JSON.createArrayNode());
    }
    private static JsonNode captionIdentity(JsonNode caption){var result=AgentAnswerValidator.JSON.createObjectNode();for(String name:caption.propertyNames())if(!Set.of("status","confirmed_revisions").contains(name))result.set(name,caption.path(name));return result;}
    private static boolean isCaption(Map<String,Object> owner){return "CAPTION".equals(owner.get("kind"));}
    private static void requireKind(Map<String,Object> owner,boolean caption){if(isCaption(owner)!=caption)throw missing(caption?"CAPTION_PROOFREAD_NOT_FOUND":"AGENT_SESSION_NOT_FOUND");}
    private static void captionFields(JsonNode input,Set<String> allowed,Set<String> required){if(input==null||!input.isObject()||!allowed.containsAll(input.propertyNames())||!input.propertyNames().containsAll(required))throw invalidCaption("Unexpected or missing caption proofreading fields");}
    private static long captionNumber(JsonNode input,String field){JsonNode value=input.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw invalidCaption("Invalid "+field);return value.longValue();}
    private static JobException invalidCaption(String message){return bad("INVALID_CAPTION_PROOFREAD_REQUEST",message);}
    private String model(Map<String,Object> job){String model=preferredTextModel.isBlank()?AgentAnswerValidator.JSON.readTree(String.valueOf(job.get("config_json"))).path("text_model").asText():preferredTextModel;
        if(!model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw bad("INVALID_AGENT_TURN","Invalid configured text model");return model;}
    private String visualModel(){String model=configuredVisualModel.trim();if(!validModel(model))throw conflict("AGENT_VISUAL_NOT_CONFIGURED","No valid visual planner model is configured");return model;}
    private static boolean validModel(String model){return model!=null&&model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");}
    private static String visualMode(Map<String,Object> owner){Object value=owner.get("visual_mode");String mode=value==null?"none":String.valueOf(value);if(!Set.of("none","frames").contains(mode))throw invalidSubmission();return mode;}
    private static String turnFingerprint(String id,String question,int limit,String mode){return sha("agent-turn\n"+id+"\n"+question+"\n"+limit+("frames".equals(mode)?"\nvisual_mode=frames":""));}
    private Map<String,Object> completedStage(String parent,String type){var rows=jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type=? AND status='SUCCEEDED' FOR UPDATE",parent,type);if(rows.size()!=1)throw conflict("AGENT_SOURCE_UNAVAILABLE","Published source is unavailable");return rows.getFirst();}
    private Map<String,Object> lockAnalysis(String id){var rows=jdbc.queryForList("SELECT * FROM analysis_job WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw missing("JOB_NOT_FOUND");return rows.getFirst();}
    private ArtifactVerifier.ManifestIdentity identity(Map<String,Object> stage,Map<String,Object> job,String hash){return new ArtifactVerifier.ManifestIdentity(String.valueOf(job.get("id")),String.valueOf(job.get("video_id")),((Number)job.get("generation")).intValue(),String.valueOf(stage.get("id")),String.valueOf(stage.get("stage_type")),((Number)stage.get("attempt")).intValue(),String.valueOf(stage.get("run_token")),hash);}
    private ArtifactVerifier.ManifestIdentity identity(StageRow stage,Map<String,Object> job,String hash){return new ArtifactVerifier.ManifestIdentity(stage.jobId(),String.valueOf(job.get("video_id")),((Number)job.get("generation")).intValue(),stage.id(),stage.stageType(),stage.attempt(),stage.runToken(),hash);}
    private static String uuid(String value){return ClipRequestService.uuid(value);}
    private static String sha(String value){return ClipRequestService.sha(value);}
    private static String canonical(JsonNode value){return ClipRequestService.canonicalJson(value);}
    private static void key(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,100}"))throw bad("INVALID_AGENT_REQUEST","A bounded idempotency key is required");}
    private static Instant instant(Object value){return value instanceof Timestamp stamp?stamp.toInstant():((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);}
    private static JobException bad(String code,String message){return new JobException(HttpStatus.BAD_REQUEST,code,message);}
    private static JobException conflict(String code,String message){return new JobException(HttpStatus.CONFLICT,code,message);}
    private static JobException missing(String code){return new JobException(HttpStatus.NOT_FOUND,code,"Requested conversation resource was not found");}
    private static JobException invalidSubmission(){return conflict("AGENT_SUBMISSION_INVALID","Stored conversation submission failed its original request fingerprint");}
}
