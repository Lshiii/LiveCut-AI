package io.livecut.sourceimports;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.livecut.jobs.JobException;
import io.livecut.video.VideoConfiguration;
import io.livecut.video.VideoRepository;
import io.livecut.video.VideoStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import static io.livecut.sourceimports.SourceImportModels.*;

/** Sources precede analysis jobs. Python never writes video_asset or analysis state. */
@Service
public class SourceImportService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SourceImportStorage storage;
    private final VideoRepository videos;
    private final long maximum;
    private final int leaseSeconds,deadlineSeconds,maxAttempts;
    private final boolean scanEnabled;
    public SourceImportService(JdbcTemplate jdbc,PlatformTransactionManager manager,SourceImportStorage storage,VideoRepository videos,
        VideoConfiguration.StorageProperties properties,
        @Value("${livecut.imports.lease-seconds:90}") int leaseSeconds,
        @Value("${livecut.imports.deadline-seconds:3600}") int deadlineSeconds,
        @Value("${livecut.imports.max-attempts:3}") int maxAttempts,
        @Value("${livecut.imports.scan-enabled:${livecut.jobs.scan-enabled:true}}") boolean scanEnabled) {
        if(leaseSeconds<1 || deadlineSeconds<leaseSeconds || deadlineSeconds>86400 || maxAttempts<1 || maxAttempts>3) throw new IllegalArgumentException("Invalid import limits");
        this.jdbc=jdbc;this.storage=storage;this.videos=videos;this.maximum=properties.maxVideoBytes();
        this.leaseSeconds=leaseSeconds;this.deadlineSeconds=deadlineSeconds;this.maxAttempts=maxAttempts;this.scanEnabled=scanEnabled;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Snapshot create(JsonNode input,String key) {
        fields(input,Set.of("url"));String url=SourceImportUrl.canonical(text(input,"url"));String requestKey=identifier(key);
        try {
            return tx.execute(status->{
                var existing=jdbc.queryForList("SELECT * FROM source_import WHERE request_key=? FOR UPDATE",requestKey);
                if(!existing.isEmpty())return existing(existing.getFirst(),url);
                String id=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO source_import (id,request_key,source_url,reserved_video_id,max_bytes) VALUES (?,?,?,?,?)",id,requestKey,url,UUID.randomUUID().toString(),maximum);
                return snapshot(row(id,false));
            });
        }catch(DuplicateKeyException concurrent){return tx.execute(status->existing(jdbc.queryForMap("SELECT * FROM source_import WHERE request_key=?",requestKey),url));}
    }
    public Snapshot get(String value){String id=uuid(value);expire(id);return snapshot(row(id,false));}
    public Snapshot byKey(String key){
        var rows=jdbc.queryForList("SELECT id FROM source_import WHERE request_key=?",String.class,identifier(key));
        if(rows.isEmpty())throw missing();return get(rows.getFirst());
    }
    public Page list(String limit){
        int count;try{count=Integer.parseInt(limit);if(count<1||count>100)throw new NumberFormatException();}catch(NumberFormatException failure){throw bad("INVALID_ARGUMENT");}
        recoverExpired();return new Page(jdbc.queryForList("SELECT * FROM source_import ORDER BY created_at DESC,id DESC LIMIT ?",count).stream().map(this::snapshot).toList());
    }
    public Snapshot cancel(String value){String id=uuid(value);return tx.execute(status->{var row=row(id,true);if(!Set.of("COMPLETED","CANCELLED").contains(row.get("status")))jdbc.update("UPDATE source_import SET status='CANCELLED',phase='CANCELLED',progress_percent=NULL,run_token=NULL,lease_until=NULL,hard_deadline_at=NULL,error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);return snapshot(row(id,false));});}
    public Snapshot retry(String value,JsonNode input){
        fields(input,Set.of("expected_attempt"));int expected=(int)number(input,"expected_attempt",0,3);
        String id=uuid(value);expire(id);
        return tx.execute(status->{var row=row(id,true);
            if(integer(row,"attempt")>expected)return snapshot(row);
            if(integer(row,"attempt")<expected)throw conflict("SOURCE_IMPORT_RETRY_CONFLICT");
            if(Set.of("QUEUED","RUNNING","COMPLETED").contains(row.get("status")))return snapshot(row);
            if(!"FAILED".equals(row.get("status")) || integer(row,"attempt")>=maxAttempts)throw conflict("SOURCE_IMPORT_NOT_RETRYABLE");
            jdbc.update("UPDATE source_import SET status='QUEUED',phase='QUEUED',progress_percent=NULL,downloaded_bytes=0,run_token=NULL,worker_id=NULL,lease_until=NULL,hard_deadline_at=NULL,error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
            return snapshot(row(id,false));});
    }
    public ClaimResponse claim(JsonNode input){
        fields(input,Set.of("worker_id","claim_request_id"));String worker=identifier(text(input,"worker_id")),key=identifier(text(input,"claim_request_id"));
        recoverExpired();
        return tx.execute(status->{
            int inserted=jdbc.update("INSERT IGNORE INTO source_import_claim (worker_id,claim_request_id) VALUES (?,?)",worker,key);
            var receipt=jdbc.queryForMap("SELECT * FROM source_import_claim WHERE worker_id=? AND claim_request_id=? FOR UPDATE",worker,key);
            if(inserted==0){
                if(receipt.get("import_id")==null)return new ClaimResponse(null);
                var row=row(String.valueOf(receipt.get("import_id")),true);
                if(!"RUNNING".equals(row.get("status")) || !java.util.Objects.equals(row.get("run_token"),receipt.get("run_token"))
                    || integer(row,"attempt")!=integer(receipt,"attempt") || !future(row,"lease_until") || !future(row,"hard_deadline_at"))return new ClaimResponse(null);
                return new ClaimResponse(claimView(row));
            }
            var rows=jdbc.queryForList("SELECT * FROM source_import WHERE status='QUEUED' AND attempt<? ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",maxAttempts);
            if(rows.isEmpty())return new ClaimResponse(null);
            var row=rows.getFirst();String id=String.valueOf(row.get("id")),token=UUID.randomUUID().toString();Instant now=now();
            jdbc.update("UPDATE source_import SET status='RUNNING',phase='DOWNLOADING',progress_percent=NULL,downloaded_bytes=0,attempt=attempt+1,worker_id=?,run_token=?,lease_until=?,hard_deadline_at=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",worker,token,Timestamp.from(now.plusSeconds(leaseSeconds)),Timestamp.from(now.plusSeconds(deadlineSeconds)),id);
            var current=row(id,false);
            jdbc.update("UPDATE source_import_claim SET import_id=?,attempt=?,run_token=? WHERE worker_id=? AND claim_request_id=?",id,integer(current,"attempt"),token,worker,key);
            return new ClaimResponse(claimView(current));
        });
    }
    public Heartbeat heartbeat(String value,JsonNode input){
        fields(input,Set.of("attempt","run_token","phase","progress_percent","downloaded_bytes"));Credential credential=credential(input);
        String id=uuid(value),phase=text(input,"phase");
        if(!Set.of("DOWNLOADING","NORMALIZING","VERIFYING").contains(phase))throw bad("INVALID_ARGUMENT");
        JsonNode percent=input.get("progress_percent");Integer progress=percent.isNull()?null:(int)number(input,"progress_percent",0,100);
        long bytes=number(input,"downloaded_bytes",0,1L<<30);expire(id);
        return tx.execute(status->{var row=row(id,true);active(row,credential);
            if(bytes>((Number)row.get("max_bytes")).longValue())throw bad("INVALID_ARGUMENT");
            int oldPhase=phaseOrder(String.valueOf(row.get("phase"))),nextPhase=phaseOrder(phase);
            if(nextPhase<oldPhase || bytes<((Number)row.get("downloaded_bytes")).longValue())throw conflict("SOURCE_IMPORT_PROGRESS_CONFLICT");
            Instant deadline=time(row,"hard_deadline_at"),until=now().plusSeconds(leaseSeconds);if(until.isAfter(deadline))until=deadline;
            jdbc.update("UPDATE source_import SET phase=?,progress_percent=?,downloaded_bytes=?,lease_until=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",phase,progress,bytes,Timestamp.from(until),id);
            return new Heartbeat(until,deadline);});
    }
    public Snapshot fail(String value,JsonNode input){
        fields(input,Set.of("attempt","run_token","error_code"));String id=uuid(value),code=text(input,"error_code");Credential credential=credential(input);
        if(!code.matches("[A-Z][A-Z0-9_]{0,63}"))throw bad("INVALID_ARGUMENT");expire(id);
        return tx.execute(status->{var row=row(id,true);
            if("FAILED".equals(row.get("status"))&&matches(row,credential)&&code.equals(row.get("error_code")))return snapshot(row);
            active(row,credential);jdbc.update("UPDATE source_import SET status='FAILED',phase='FAILED',progress_percent=NULL,error_code=?,lease_until=NULL,hard_deadline_at=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",code,id);return snapshot(row(id,false));});
    }
    public Snapshot complete(String value,JsonNode input){
        fields(input,Set.of("attempt","run_token","sha256","size_bytes"));String id=uuid(value),hash=text(input,"sha256");Credential credential=credential(input);
        if(!hash.matches("[0-9a-f]{64}"))throw bad("INVALID_ARGUMENT");long size=number(input,"size_bytes",1,1L<<30);expire(id);
        var before=row(id,false);admitCompletion(before,credential,hash,size);
        for(int round=0;round<2;round++) {
          SourceImportStorage.Prepared prepared=null;
          try {
            prepared=storage.prepare(id,credential.attempt(),credential.token(),String.valueOf(before.get("reserved_video_id")),hash,size,((Number)before.get("max_bytes")).longValue());
            var ready=prepared;
            Snapshot result=tx.execute(status->{var current=row(id,true);admitCompletion(current,credential,hash,size);
                // Another identical completion may have published during our copy.
                // Release the row lock, discard our copy and hash its published target outside the lock.
                if("COMPLETED".equals(current.get("status"))&&!ready.existing())return null;
                storage.publish(ready);
                String videoId=String.valueOf(current.get("reserved_video_id"));
                var stored=videos.find(videoId);
                if(stored.isEmpty())videos.insert(new VideoStorage.StoredVideo(videoId,"link-import-"+id+".mp4",hash,"videos/"+videoId+".mp4",size));
                else if(!stored.get().contentHash().equals(hash)||stored.get().sizeBytes()!=size||!stored.get().sourceKey().equals("videos/"+videoId+".mp4"))throw conflict("SOURCE_IMPORT_CONTENT_CHANGED");
                if(!"COMPLETED".equals(current.get("status")))jdbc.update("UPDATE source_import SET status='COMPLETED',phase='COMPLETED',progress_percent=100,result_hash=?,result_size=?,error_code=NULL,lease_until=NULL,hard_deadline_at=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",hash,size,id);
                return snapshot(row(id,false));});
            if(result!=null)return result;
          }finally{storage.discard(prepared);}
        }
        throw conflict("SOURCE_IMPORT_CONTENT_CHANGED");
    }
    private void admitCompletion(Map<String,Object> row,Credential value,String hash,long size){
        if(size>((Number)row.get("max_bytes")).longValue())throw bad("INVALID_ARGUMENT");
        if("COMPLETED".equals(row.get("status"))&&matches(row,value)){
            if(!hash.equals(row.get("result_hash")) || ((Number)row.get("result_size")).longValue()!=size)throw conflict("SOURCE_IMPORT_RESULT_CONFLICT");
        }else active(row,value);
    }
    @Scheduled(fixedDelayString="${livecut.imports.scan-delay-ms:1000}")
    public void scheduledRecovery(){if(scanEnabled)recoverExpired();}
    public int recoverExpired(){return expire(null);}
    private int expire(String id){return jdbc.update("UPDATE source_import SET status='FAILED',phase='FAILED',progress_percent=NULL,error_code=CASE WHEN hard_deadline_at<=CURRENT_TIMESTAMP(6) THEN 'SOURCE_IMPORT_DEADLINE_EXCEEDED' ELSE 'SOURCE_IMPORT_LEASE_EXPIRED' END,lease_until=NULL,hard_deadline_at=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE status='RUNNING' AND (lease_until<=CURRENT_TIMESTAMP(6) OR hard_deadline_at<=CURRENT_TIMESTAMP(6))"+(id==null?"":" AND id=?"),id==null?new Object[]{}:new Object[]{id});}
    private Snapshot existing(Map<String,Object> row,String url){if(!url.equals(row.get("source_url")))throw conflict("IDEMPOTENCY_CONFLICT");return snapshot(row);}
    private Map<String,Object> row(String id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM source_import WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw missing();return rows.getFirst();}
    private Snapshot snapshot(Map<String,Object> row){return new Snapshot(String.valueOf(row.get("id")),String.valueOf(row.get("source_url")),String.valueOf(row.get("status")),String.valueOf(row.get("phase")),(Integer)row.get("progress_percent"),((Number)row.get("downloaded_bytes")).longValue(),"COMPLETED".equals(row.get("status"))?String.valueOf(row.get("reserved_video_id")):null,(String)row.get("error_code"),integer(row,"attempt"),time(row,"created_at"),time(row,"updated_at"));}
    private Claim claimView(Map<String,Object> row){return new Claim(String.valueOf(row.get("id")),String.valueOf(row.get("source_url")),integer(row,"attempt"),String.valueOf(row.get("run_token")),time(row,"lease_until"),time(row,"hard_deadline_at"),((Number)row.get("max_bytes")).longValue());}
    private void active(Map<String,Object> row,Credential credential){if(!"RUNNING".equals(row.get("status"))||!matches(row,credential)||!future(row,"lease_until")||!future(row,"hard_deadline_at"))throw conflict("STALE_IMPORT_ATTEMPT");}
    private static boolean matches(Map<String,Object> row,Credential value){return integer(row,"attempt")==value.attempt()&&value.token().equals(row.get("run_token"));}
    private boolean future(Map<String,Object> row,String key){return row.get(key)!=null&&time(row,key).isAfter(now());}
    private Instant now(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).toInstant();}
    private static Instant time(Map<String,Object> row,String key){Object value=row.get(key);if(value instanceof Timestamp timestamp)return timestamp.toInstant();if(value instanceof java.time.LocalDateTime local)return local.toInstant(java.time.ZoneOffset.UTC);throw new IllegalStateException("Unsupported JDBC timestamp");}
    private static int integer(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
    private record Credential(int attempt,String token){}
    private static Credential credential(JsonNode input){return new Credential((int)number(input,"attempt",1,Integer.MAX_VALUE),uuid(text(input,"run_token")));}
    private static String uuid(String value){try{String result=UUID.fromString(value).toString();if(!result.equals(value))throw new IllegalArgumentException();return result;}catch(IllegalArgumentException|NullPointerException failure){throw bad("INVALID_ARGUMENT");}}
    private static String identifier(String value){if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,128}"))throw bad("INVALID_ARGUMENT");return value;}
    private static String text(JsonNode input,String field){if(!input.path(field).isString())throw bad("INVALID_ARGUMENT");return input.path(field).asText();}
    private static long number(JsonNode input,String field,long low,long high){var value=input.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<low||value.longValue()>high)throw bad("INVALID_ARGUMENT");return value.longValue();}
    private static void fields(JsonNode input,Set<String> expected){if(input==null||!input.isObject()||!input.propertyNames().equals(expected))throw bad("INVALID_ARGUMENT");}
    private static int phaseOrder(String phase){return switch(phase){case "DOWNLOADING"->0;case "NORMALIZING"->1;case "VERIFYING"->2;default->-1;};}
    private static JobException missing(){return new JobException(HttpStatus.NOT_FOUND,"SOURCE_IMPORT_NOT_FOUND","Source import was not found");}
    private static JobException bad(String code){return new JobException(HttpStatus.BAD_REQUEST,code,"Source import request has invalid fields");}
    private static JobException conflict(String code){return new JobException(HttpStatus.CONFLICT,code,"Source import state or immutable input changed");}
}
