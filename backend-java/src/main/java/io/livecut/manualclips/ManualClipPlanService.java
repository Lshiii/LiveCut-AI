package io.livecut.manualclips;

import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import io.livecut.transcripts.TranscriptService;
import io.livecut.transcripts.TranscriptService.SourceTicket;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Independent human plans. Verified source IO precedes every new write transaction. */
@Service
public class ManualClipPlanService {
    private static final long SAFE=9007199254740991L;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> CREATE=Set.of("source_fingerprint","title","start_utterance_id","end_utterance_id");
    private static final Set<String> REVISION=Set.of("base_revision","base_plan_hash","source_fingerprint","title","start_utterance_id","end_utterance_id");
    private static final Set<String> SOURCE=Set.of("job_id","video_id","generation","source_video_sha256","transcript_sha256","analysis_sha256","duration_ms","source_fingerprint");
    private static final Set<String> CONTENT=Set.of("schema_version","data_kind","source_kind","id","source","revision","title","start_utterance_id","end_utterance_id","range");
    private final JdbcTemplate jdbc;
    private final TranscriptService transcripts;
    private final TransactionTemplate writes,reads;
    public ManualClipPlanService(JdbcTemplate jdbc,TranscriptService transcripts,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.transcripts=transcripts;writes=new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public JsonNode create(String value,JsonNode raw,String key){
        String job=uuid(value);key(key);JsonNode replay=replayIfPresent(key,"CREATE",job,raw);if(replay!=null)return replay;
        JsonNode input=input(raw,false);SourceTicket ticket;
        try{ticket=admit(job,input.path("source_fingerprint").asText(),key,"CREATE",job,input);}catch(ReplayedSubmission completed){return completed.receipt;}
        return writeOrReplay(key,"CREATE",job,input,()->{
            lockAnalysis(job);JsonNode existing=replayIfPresent(key,"CREATE",job,input);if(existing!=null)return existing;
            current(ticket);String id=UUID.randomUUID().toString();JsonNode source=JSON.valueToTree(ticket.source());
            JsonNode content=payload(id,source,1,input,ticket);String hash=digest(content);
            jdbc.update("INSERT INTO manual_clip_plan (id,job_id,video_id,source_json,source_fingerprint,latest_revision) VALUES (?,?,?,?,?,1)",id,job,ticket.source().videoId(),canonical(source),ticket.source().sourceFingerprint());
            insertRevision(id,1,content,hash);return saveReceipt(key,"CREATE",job,id,input,1,hash);
        });
    }

    public JsonNode revise(String value,JsonNode raw,String key){
        String id=uuid(value);key(key);JsonNode replay=replayIfPresent(key,"REVISION",id,raw);if(replay!=null)return replay;
        JsonNode input=input(raw,true);Map<String,Object> hint=owner(id,false);String job=(String)hint.get("job_id");
        SourceTicket ticket;
        try{ticket=admit(job,input.path("source_fingerprint").asText(),key,"REVISION",id,input);}catch(ReplayedSubmission completed){return completed.receipt;}
        return writeOrReplay(key,"REVISION",id,input,()->{
            lockAnalysis(job);Map<String,Object> plan=owner(id,true);JsonNode existing=replayIfPresent(key,"REVISION",id,input);if(existing!=null)return existing;
            current(ticket);sameSource(plan,ticket);long base=positive(input,"base_revision"),head=number(plan.get("latest_revision"));
            if(base!=head||!input.path("base_plan_hash").asText().equals(stored(plan,base).path("plan_hash").asText()))throw revisionConflict();
            verified(plan,base,ticket);if(base==SAFE)throw revisionConflict();long next=base+1;
            JsonNode content=payload(id,JSON.valueToTree(ticket.source()),next,input,ticket);String hash=digest(content);
            insertRevision(id,next,content,hash);jdbc.update("UPDATE manual_clip_plan SET latest_revision=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",next,id);
            return saveReceipt(key,"REVISION",job,id,input,next,hash);
        });
    }

    public JsonNode createSubmission(String value,String key){return submission("CREATE",uuid(value),key);}
    public JsonNode revisionSubmission(String value,String key){return submission("REVISION",uuid(value),key);}
    private JsonNode submission(String operation,String owner,String key){
        key(key);return reads.execute(ignored->{var row=receipt(key);if(row==null)throw missing("MANUAL_CLIP_SUBMISSION_NOT_FOUND","The exact manual submission was not found");
            JsonNode checked=checkedReceipt(row);requireReceiptOwner(checked,operation,owner);return checked;});
    }

    public ManualClipModels.Page list(String value,int limit,long offset){
        String job=uuid(value);if(limit<1||limit>50||offset<0||offset>SAFE)throw invalid();
        return reads.execute(ignored->{
            if(jdbc.queryForList("SELECT id FROM analysis_job WHERE id=?",String.class,job).isEmpty())throw new JobException(HttpStatus.NOT_FOUND,"JOB_NOT_FOUND","Analysis job was not found");
            long total=jdbc.queryForObject("SELECT COUNT(*) FROM manual_clip_plan WHERE job_id=?",Long.class,job);if(total<0||total>SAFE)throw sourceInvalid();
            var rows=jdbc.queryForList("SELECT p.id,p.job_id,p.video_id,p.latest_revision,p.created_at,p.updated_at,r.title,r.plan_hash,EXISTS(SELECT 1 FROM manual_clip_confirmation c WHERE c.plan_id=p.id AND c.revision=r.revision AND c.plan_hash=r.plan_hash) AS confirmed FROM manual_clip_plan p JOIN manual_clip_revision r ON r.plan_id=p.id AND r.revision=p.latest_revision WHERE p.job_id=? ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?",job,limit,offset);
            if(rows.size()!=Math.min(limit,Math.max(0,total-offset)))throw sourceInvalid();
            var items=rows.stream().map(row->new ManualClipModels.Summary(uuidStored(row.get("id")),uuidStored(row.get("job_id")),uuidStored(row.get("video_id")),titleStored(row.get("title")),number(row.get("latest_revision")),hashStored(row.get("plan_hash")),truth(row.get("confirmed"))?"CONFIRMED":"DRAFT",instant(row.get("created_at")),instant(row.get("updated_at")))).toList();
            return new ManualClipModels.Page("1.0","manual_clip_plan_list",job,items,total,limit,offset);
        });
    }

    public JsonNode get(String value,Long revision){
        String id=uuid(value);if(revision!=null&&(revision<1||revision>SAFE))throw invalid();var hint=owner(id,false);
        SourceTicket ticket=source((String)hint.get("job_id"),(String)hint.get("source_fingerprint"));
        return reads.execute(ignored->{var plan=owner(id,false);current(ticket);sameSource(plan,ticket);long selected=revision==null?number(plan.get("latest_revision")):revision;return detail(plan,verified(plan,selected,ticket));});
    }

    public JsonNode confirm(String value,JsonNode raw){
        String id=uuid(value);fields(raw,Set.of("revision","plan_hash","accept_sentence_boundaries"));long revision=positive(raw,"revision");String hash=hash(raw,"plan_hash");
        if(!raw.path("accept_sentence_boundaries").isBoolean()||!raw.path("accept_sentence_boundaries").booleanValue())throw invalid();
        var hint=owner(id,false);String job=(String)hint.get("job_id");SourceTicket ticket=source(job,(String)hint.get("source_fingerprint"));
        return writes.execute(ignored->{lockAnalysis(job);var plan=owner(id,true);current(ticket);sameSource(plan,ticket);
            if(revision!=number(plan.get("latest_revision")))throw revisionConflict();JsonNode content=verified(plan,revision,ticket);if(!hash.equals(content.path("plan_hash").asText()))throw revisionConflict();
            var confirmations=jdbc.queryForList("SELECT plan_hash FROM manual_clip_confirmation WHERE plan_id=? AND revision=?",String.class,id,revision);
            if(confirmations.isEmpty()){jdbc.update("INSERT INTO manual_clip_confirmation (plan_id,revision,plan_hash) VALUES (?,?,?)",id,revision,hash);jdbc.update("UPDATE manual_clip_plan SET state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);plan=owner(id,false);}
            else if(confirmations.size()!=1||!hash.equals(confirmations.getFirst()))throw sourceInvalid();
            return detail(plan,content);
        });
    }

    /** Caller owns analysis first; this method acquires only the manual owner and performs no file IO. */
    public JsonNode confirmedLocked(String value,long revision,String hash,SourceTicket ticket){
        String id=uuid(value);if(revision<1||revision>SAFE||hash==null||!hash.matches("[a-f0-9]{64}"))throw invalid();
        var plan=owner(id,true);current(ticket);sameSource(plan,ticket);JsonNode content=verified(plan,revision,ticket);
        if(!hash.equals(content.path("plan_hash").asText()))throw revisionConflict();
        var rows=jdbc.queryForList("SELECT plan_hash FROM manual_clip_confirmation WHERE plan_id=? AND revision=?",String.class,id,revision);
        if(rows.size()!=1||!hash.equals(rows.getFirst()))throw new JobException(HttpStatus.CONFLICT,"MANUAL_CLIP_CONFIRMATION_REQUIRED","Confirm this exact manual revision first");
        return detail(plan,content);
    }

    private SourceTicket admit(String job,String fingerprint,String key,String operation,String owner,JsonNode input){
        try{return source(job,fingerprint);}catch(JobException failure){JsonNode replay=replayIfPresent(key,operation,owner,input);if(replay!=null)throw new ReplayedSubmission(replay);throw failure;}
    }
    private JsonNode writeOrReplay(String key,String operation,String owner,JsonNode input,Supplier<JsonNode> action){
        try{return writes.execute(ignored->action.get());}
        catch(DuplicateKeyException race){JsonNode replay=replayIfPresent(key,operation,owner,input);if(replay!=null)return replay;throw race;}
    }
    private SourceTicket source(String job,String fingerprint){try{return transcripts.verifySource(job,fingerprint);}catch(JobException failure){throw mapSource(failure);}}
    private void current(SourceTicket ticket){try{transcripts.requireCurrent(ticket);}catch(JobException failure){throw mapSource(failure);}}
    private static JobException mapSource(JobException failure){return "TRANSCRIPT_SOURCE_CHANGED".equals(failure.getCode())?sourceChanged():sourceInvalid();}
    private void lockAnalysis(String job){if(jdbc.queryForList("SELECT id FROM analysis_job WHERE id=? FOR UPDATE",String.class,job).isEmpty())throw sourceInvalid();}
    private Map<String,Object> owner(String id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM manual_clip_plan WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.size()!=1)throw missing("MANUAL_CLIP_NOT_FOUND","Manual clip plan was not found");return rows.getFirst();}

    private JsonNode payload(String id,JsonNode source,long revision,JsonNode input,SourceTicket ticket){
        var result=JSON.createObjectNode().put("schema_version","1.0").put("data_kind","manual_clip_plan_revision").put("source_kind","MANUAL_ASR").put("id",id);
        result.set("source",source);result.put("revision",revision).put("title",input.path("title").asText()).put("start_utterance_id",input.path("start_utterance_id").asText()).put("end_utterance_id",input.path("end_utterance_id").asText());
        result.set("range",JSON.valueToTree(TranscriptService.resolveRange(ticket,input.path("start_utterance_id").asText(),input.path("end_utterance_id").asText())));return result;
    }
    private void insertRevision(String id,long revision,JsonNode content,String hash){jdbc.update("INSERT INTO manual_clip_revision (plan_id,revision,plan_hash,title,content_json) VALUES (?,?,?,?,?)",id,revision,hash,content.path("title").asText(),canonical(content));}
    private JsonNode stored(Map<String,Object> owner,long revision){
        var rows=jdbc.queryForList("SELECT * FROM manual_clip_revision WHERE plan_id=? AND revision=?",owner.get("id"),revision);
        if(rows.size()!=1)throw missing("MANUAL_CLIP_REVISION_NOT_FOUND","Manual clip revision was not found");
        try{var row=rows.getFirst();JsonNode content=JSON.readTree(String.valueOf(row.get("content_json")));fields(content,CONTENT);
            if(!"1.0".equals(content.path("schema_version").asText())||!"manual_clip_plan_revision".equals(content.path("data_kind").asText())||!"MANUAL_ASR".equals(content.path("source_kind").asText())
                ||!Objects.equals(owner.get("id"),content.path("id").asText())||positive(content,"revision")!=revision||revision>number(owner.get("latest_revision"))
                ||!hashStored(row.get("plan_hash")).equals(digest(content))||!Objects.equals(row.get("title"),content.path("title").asText())||!canonical(storedSource(owner)).equals(canonical(content.path("source"))))throw sourceInvalid();
            title(content);identifier(content,"start_utterance_id");identifier(content,"end_utterance_id");JsonNode range=content.path("range");fields(range,Set.of("start_ms","end_ms","utterance_count","start_text","end_text"));
            if(nonnegative(range,"start_ms")>=positive(range,"end_ms")||positive(range,"end_ms")>nonnegative(content.path("source"),"duration_ms")||positive(range,"utterance_count")>100000)throw sourceInvalid();
            text(range,"start_text",100000);text(range,"end_text",100000);ObjectNode checked=(ObjectNode)content.deepCopy();checked.put("plan_hash",digest(content));return checked;
        }catch(JobException failure){if("MANUAL_CLIP_REVISION_NOT_FOUND".equals(failure.getCode()))throw failure;throw sourceInvalid();}catch(RuntimeException failure){throw sourceInvalid();}
    }
    private JsonNode verified(Map<String,Object> plan,long revision,SourceTicket ticket){
        sameSource(plan,ticket);JsonNode checked=stored(plan,revision);
        try{JsonNode expected=JSON.valueToTree(TranscriptService.resolveRange(ticket,checked.path("start_utterance_id").asText(),checked.path("end_utterance_id").asText()));if(!canonical(expected).equals(canonical(checked.path("range"))))throw sourceInvalid();return checked;}
        catch(RuntimeException failure){throw sourceInvalid();}
    }
    private JsonNode storedSource(Map<String,Object> plan){
        try{JsonNode source=JSON.readTree(String.valueOf(plan.get("source_json")));fields(source,SOURCE);uuid(source.path("job_id").asText());uuid(source.path("video_id").asText());positive(source,"generation");positive(source,"duration_ms");
            for(String name:List.of("source_video_sha256","transcript_sha256","analysis_sha256","source_fingerprint"))hash(source,name);
            if(!Objects.equals(plan.get("job_id"),source.path("job_id").asText())||!Objects.equals(plan.get("video_id"),source.path("video_id").asText())||!Objects.equals(plan.get("source_fingerprint"),source.path("source_fingerprint").asText()))throw sourceInvalid();
            String fp=ClipRequestService.sha("transcript-source-v1\n"+source.path("job_id").asText()+"\n"+source.path("video_id").asText()+"\n"+positive(source,"generation")+"\n"+source.path("source_video_sha256").asText()+"\n"+source.path("transcript_sha256").asText()+"\n"+source.path("analysis_sha256").asText()+"\n"+positive(source,"duration_ms")+"\n");
            if(!fp.equals(source.path("source_fingerprint").asText()))throw sourceInvalid();return source;
        }catch(RuntimeException failure){throw sourceInvalid();}
    }
    private void sameSource(Map<String,Object> plan,SourceTicket ticket){if(ticket==null||!canonical(storedSource(plan)).equals(canonical(JSON.valueToTree(ticket.source()))))throw sourceChanged();}
    private JsonNode detail(Map<String,Object> plan,JsonNode checked){
        ObjectNode result=(ObjectNode)checked.deepCopy();result.put("data_kind","manual_clip_plan").put("latest_revision",number(plan.get("latest_revision"))).put("created_at",instant(plan.get("created_at")).toString()).put("updated_at",instant(plan.get("updated_at")).toString());
        var confirmed=jdbc.queryForList("SELECT revision,plan_hash FROM manual_clip_confirmation WHERE plan_id=? ORDER BY revision",plan.get("id"));var revisions=result.putArray("confirmed_revisions");boolean selected=false;
        for(var row:confirmed){long revision=number(row.get("revision"));JsonNode content=stored(plan,revision);if(!Objects.equals(row.get("plan_hash"),content.path("plan_hash").asText()))throw sourceInvalid();revisions.add(revision);if(revision==positive(checked,"revision"))selected=true;}
        result.put("status",selected?"CONFIRMED":"DRAFT");return result;
    }

    private JsonNode saveReceipt(String key,String operation,String job,String id,JsonNode input,long revision,String planHash){
        String requestHash=requestHash(operation,operation.equals("CREATE")?job:id,input);JsonNode receipt=receiptNode(key,operation,job,id,input,requestHash,revision,planHash);
        jdbc.update("INSERT INTO manual_clip_submission (idempotency_key,operation,job_id,plan_id,input_json,request_hash,revision,plan_hash,receipt_json) VALUES (?,?,?,?,?,?,?,?,?)",key,operation,job,id,canonical(input),requestHash,revision,planHash,canonical(receipt));return receipt;
    }
    private Map<String,Object> receipt(String key){var rows=jdbc.queryForList("SELECT * FROM manual_clip_submission WHERE idempotency_key=?",key);return rows.isEmpty()?null:rows.getFirst();}
    private JsonNode replayIfPresent(String key,String operation,String owner,JsonNode input){var row=receipt(key);if(row==null)return null;JsonNode checked=checkedReceipt(row);requireReceiptOwner(checked,operation,owner);
        try{if(input==null||!canonical(checked.path("input")).equals(canonical(input)))throw idempotency();}catch(RuntimeException invalid){throw idempotency();}return checked;}
    private static void requireReceiptOwner(JsonNode receipt,String operation,String owner){if(!operation.equals(receipt.path("operation").asText())||!owner.equals(receipt.path(operation.equals("CREATE")?"job_id":"plan_id").asText()))throw idempotency();}
    private JsonNode checkedReceipt(Map<String,Object> row){
        try{String operation=String.valueOf(row.get("operation"));if(!Set.of("CREATE","REVISION").contains(operation))throw submissionInvalid();String key=String.valueOf(row.get("idempotency_key"));key(key);String job=uuid(String.valueOf(row.get("job_id"))),id=uuid(String.valueOf(row.get("plan_id")));
            JsonNode input=input(JSON.readTree(String.valueOf(row.get("input_json"))),operation.equals("REVISION"));String requestHash=requestHash(operation,operation.equals("CREATE")?job:id,input);long revision=number(row.get("revision"));String planHash=hashStored(row.get("plan_hash"));var plan=owner(id,false);JsonNode content=stored(plan,revision);
            if(!job.equals(plan.get("job_id"))||!requestHash.equals(row.get("request_hash"))||!planHash.equals(content.path("plan_hash").asText())||!input.path("source_fingerprint").asText().equals(plan.get("source_fingerprint"))
                ||!input.path("title").equals(content.path("title"))||!input.path("start_utterance_id").equals(content.path("start_utterance_id"))||!input.path("end_utterance_id").equals(content.path("end_utterance_id")))throw submissionInvalid();
            if(operation.equals("CREATE")){if(revision!=1)throw submissionInvalid();}else if(positive(input,"base_revision")+1!=revision||!input.path("base_plan_hash").asText().equals(stored(plan,positive(input,"base_revision")).path("plan_hash").asText()))throw submissionInvalid();
            JsonNode expected=receiptNode(key,operation,job,id,input,requestHash,revision,planHash),actual=JSON.readTree(String.valueOf(row.get("receipt_json")));if(!canonical(expected).equals(canonical(actual)))throw submissionInvalid();return expected;
        }catch(RuntimeException failure){throw submissionInvalid();}
    }
    private static JsonNode receiptNode(String key,String operation,String job,String id,JsonNode input,String requestHash,long revision,String planHash){var value=JSON.createObjectNode().put("schema_version","1.0").put("data_kind","manual_clip_submission").put("operation",operation).put("job_id",job).put("plan_id",id).put("idempotency_key",key).put("request_hash",requestHash).put("revision",revision).put("plan_hash",planHash);value.set("input",input.deepCopy());return value;}
    private static String requestHash(String operation,String owner,JsonNode input){return ClipRequestService.sha("manual-clip-request-v1\n"+operation+"\n"+owner+"\n"+canonical(input)+"\n");}
    private static JsonNode input(JsonNode raw,boolean revision){fields(raw,revision?REVISION:CREATE);hash(raw,"source_fingerprint");title(raw);identifier(raw,"start_utterance_id");identifier(raw,"end_utterance_id");if(revision){positive(raw,"base_revision");hash(raw,"base_plan_hash");}return raw.deepCopy();}
    private static String canonical(JsonNode node){return ClipRequestService.canonicalJson(node);}
    private static String digest(JsonNode node){return ClipRequestService.sha(canonical(node));}
    private static void fields(JsonNode node,Set<String> expected){if(node==null||!node.isObject()||!node.propertyNames().equals(expected))throw invalid();}
    private static String text(JsonNode node,String name,int maximum){JsonNode value=node.path(name);if(!value.isString()||value.stringValue().isBlank()||value.stringValue().length()>maximum)throw invalid();return value.stringValue();}
    private static String title(JsonNode node){return text(node,"title",120);}
    private static String identifier(JsonNode node,String name){String value=text(node,name,128);if(!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw invalid();return value;}
    private static String hash(JsonNode node,String name){String value=text(node,name,64);if(!value.matches("[a-f0-9]{64}"))throw invalid();return value;}
    private static long nonnegative(JsonNode node,String name){JsonNode value=node.path(name);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0||value.longValue()>SAFE)throw invalid();return value.longValue();}
    private static long positive(JsonNode node,String name){long value=nonnegative(node,name);if(value<1)throw invalid();return value;}
    private static long number(Object value){if(!(value instanceof Number number)||number.longValue()<1||number.longValue()>SAFE)throw sourceInvalid();return number.longValue();}
    private static String uuid(String value){try{String canonical=UUID.fromString(value).toString();if(!canonical.equals(value))throw invalid();return canonical;}catch(IllegalArgumentException|NullPointerException failure){throw invalid();}}
    private static void key(String key){if(key==null||!key.matches("[A-Za-z0-9_-]{1,100}"))throw invalid();}
    private static String uuidStored(Object value){try{return uuid((String)value);}catch(RuntimeException failure){throw sourceInvalid();}}
    private static String hashStored(Object value){if(!(value instanceof String hash)||!hash.matches("[a-f0-9]{64}"))throw sourceInvalid();return hash;}
    private static String titleStored(Object value){if(!(value instanceof String title)||title.isBlank()||title.length()>120)throw sourceInvalid();return title;}
    private static boolean truth(Object value){return Boolean.TRUE.equals(value)||value instanceof Number number&&number.intValue()==1;}
    private static Instant instant(Object value){return value instanceof Timestamp t?t.toInstant():((java.time.LocalDateTime)value).toInstant(ZoneOffset.UTC);}
    private static JobException invalid(){return new JobException(HttpStatus.BAD_REQUEST,"MANUAL_CLIP_INVALID","Manual clip input or query is invalid");}
    private static JobException missing(String code,String message){return new JobException(HttpStatus.NOT_FOUND,code,message);}
    private static JobException sourceInvalid(){return new JobException(HttpStatus.CONFLICT,"MANUAL_CLIP_SOURCE_INVALID","The stored manual source or content failed verification");}
    private static JobException sourceChanged(){return new JobException(HttpStatus.CONFLICT,"MANUAL_CLIP_SOURCE_CHANGED","The verified manual source changed");}
    private static JobException revisionConflict(){return new JobException(HttpStatus.CONFLICT,"MANUAL_CLIP_REVISION_CONFLICT","Read and use the current exact manual revision and hash");}
    private static JobException submissionInvalid(){return new JobException(HttpStatus.CONFLICT,"MANUAL_CLIP_SUBMISSION_INVALID","The immutable manual submission failed verification");}
    private static JobException idempotency(){return new JobException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This key belongs to another manual operation, owner or input");}
    private static final class ReplayedSubmission extends RuntimeException { private final JsonNode receipt;private ReplayedSubmission(JsonNode receipt){this.receipt=receipt;} }
}
