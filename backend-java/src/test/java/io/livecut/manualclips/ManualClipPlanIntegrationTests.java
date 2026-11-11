package io.livecut.manualclips;

import static org.junit.jupiter.api.Assertions.*;
import io.livecut.LivecutBackendApplication;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import io.livecut.transcripts.TranscriptService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real isolated MySQL/HTTP; source artifacts are synthetic, registered directly without a worker/model POST. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualClipPlanIntegrationTests {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> RECEIPT=Set.of("schema_version","data_kind","operation","job_id","plan_id","idempotency_key","input","request_hash","revision","plan_hash");
    @TempDir static Path root;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> ownJobs=new ArrayList<>(),ownVideos=new ArrayList<>();
    private ConfigurableApplicationContext app;private JdbcTemplate jdbc;private URI base;
    private record Fixture(String job,String video,String fingerprint,String asrStage,String token,Path transcript) {}
    @BeforeAll void start(){
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"));
        app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,
            "--livecut.worker.api-key=manual-domain-synthetic-only","--livecut.jobs.scan-enabled=false","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false","--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup(){for(String id:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);for(String id:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);ownJobs.clear();ownVideos.clear();}
    @AfterAll void stop(){if(app!=null)app.close();}

    @Test void createIsARealManualR1WithAllIntermediateSentencesAndNoPlanningSideEffects()throws Exception{
        var f=fixture();long stages=count("job_stage");ObjectNode input=createInput(f,"  人工 Ａ 😀  ","u1","u3");String key=key();
        JsonNode receipt=ok(send("POST",createPath(f),input,key));assertReceipt(receipt,"CREATE",f.job(),input,key,1);String id=receipt.path("plan_id").asText();
        JsonNode detail=detail(id);assertEquals("manual_clip_plan",detail.path("data_kind").asText());assertEquals("MANUAL_ASR",detail.path("source_kind").asText());assertEquals(1,detail.path("revision").asInt());assertEquals(1,detail.path("latest_revision").asInt());assertEquals("DRAFT",detail.path("status").asText());assertEquals(input.path("title"),detail.path("title"));assertTrue(detail.path("confirmed_revisions").isEmpty());
        assertEquals(Set.of("schema_version","data_kind","source_kind","id","source","revision","latest_revision","plan_hash","status","confirmed_revisions","created_at","updated_at","title","start_utterance_id","end_utterance_id","range"),detail.propertyNames());
        assertEquals(Set.of("start_ms","end_ms","utterance_count","start_text","end_text"),detail.path("range").propertyNames());assertEquals(0,detail.path("range").path("start_ms").asLong());assertEquals(5000,detail.path("range").path("end_ms").asLong());assertEquals(3,detail.path("range").path("utterance_count").asInt());assertEquals("起句",detail.path("range").path("start_text").asText());assertEquals("止句",detail.path("range").path("end_text").asText());assertPayloadHash(detail);assertEquals(receipt.path("plan_hash"),detail.path("plan_hash"));
        assertEquals(stages,count("job_stage"));for(String table:List.of("model_call","clip_request","agent_session","export_job","review_document"))assertEquals(0,scopedCount(table,f.job()));
        String text=JSON.writeValueAsString(receipt)+JSON.writeValueAsString(detail);for(String privateValue:List.of("source_key","run_token","manifest_key","jobs/",f.token(),root.toString(),"max_text_calls","text_model_id","FOUND","model_result"))assertFalse(text.contains(privateValue));
    }

    @Test void sameSentenceRangeAndExactly120Utf16TitleRemainUnmodified()throws Exception{
        var f=fixture();String title="😀".repeat(60);JsonNode receipt=ok(send("POST",createPath(f),createInput(f,title,"u2","u2"),key()));JsonNode detail=detail(receipt.path("plan_id").asText());
        assertEquals(title,detail.path("title").asText());assertEquals(1,detail.path("range").path("utterance_count").asInt());assertEquals(2000,detail.path("range").path("start_ms").asLong());assertEquals(3000,detail.path("range").path("end_ms").asLong());
    }

    @Test void immutableCreateAndRevisionReceiptsNeverResolveToAnAdvancedHead()throws Exception{
        var f=fixture();String createKey=key(),revisionKey=key();ObjectNode input=createInput(f,"r1","u1","u3");JsonNode first=ok(send("POST",createPath(f),input,createKey));String id=first.path("plan_id").asText();JsonNode r1=detail(id);confirm(id,r1);
        ObjectNode edited=revisionInput(r1,"r2","u2","u4");JsonNode second=ok(send("POST",path(id)+"/revisions",edited,revisionKey));JsonNode r2=detail(id);assertEquals(2,r2.path("revision").asInt());assertEquals("DRAFT",r2.path("status").asText());
        ok(send("POST",path(id)+"/revisions",revisionInput(r2,"r3","u3","u5"),key()));
        assertEquals(first,ok(send("POST",createPath(f),input,createKey)));assertEquals(first,ok(send("GET","/api/jobs/"+f.job()+"/manual-clip-plan-submissions/"+createKey,null,null)));
        assertEquals(second,ok(send("POST",path(id)+"/revisions",edited,revisionKey)));assertEquals(second,ok(send("GET",path(id)+"/revision-submissions/"+revisionKey,null,null)));
        JsonNode old=ok(send("GET",path(id)+"?revision=1",null,null));assertEquals(1,old.path("revision").asInt());assertEquals(3,old.path("latest_revision").asInt());assertEquals("CONFIRMED",old.path("status").asText());assertEquals(r1.path("plan_hash"),old.path("plan_hash"));assertEquals("r1",old.path("title").asText());
    }

    @Test void createAndRevisionUseOneGlobalKeySpaceAcrossOwnersOperationsAndExactBodies()throws Exception{
        var a=fixture();var b=fixture();String used=key();ObjectNode input=createInput(a,"原始正文","u1","u3");JsonNode receipt=ok(send("POST",createPath(a),input,used));String id=receipt.path("plan_id").asText();JsonNode head=detail(id);
        error(send("POST",createPath(b),createInput(b,"原始正文","u1","u3"),used),409,"IDEMPOTENCY_CONFLICT");error(send("POST",path(id)+"/revisions",revisionInput(head,"新正文","u1","u3"),used),409,"IDEMPOTENCY_CONFLICT");
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(v->v.put("title","原始正文 "),v->v.put("end_utterance_id","u4"),v->v.put("extra",true),v->v.put("title",""))){ObjectNode changed=input.deepCopy();change.accept(changed);error(send("POST",createPath(a),changed,used),409,"IDEMPOTENCY_CONFLICT");}
        error(send("GET","/api/jobs/"+b.job()+"/manual-clip-plan-submissions/"+used,null,null),409,"IDEMPOTENCY_CONFLICT");error(send("GET",path(id)+"/revision-submissions/"+used,null,null),409,"IDEMPOTENCY_CONFLICT");
        assertEquals(1,scopedCount("manual_clip_plan",a.job()));assertEquals(0,scopedCount("manual_clip_plan",b.job()));assertEquals(1,scopedCount("manual_clip_submission",a.job()));
    }

    @Test void exactReceiptsAndPostReplaysRecoverWhenSourceFilesAreUnavailable()throws Exception{
        var f=fixture();String createKey=key(),revisionKey=key();ObjectNode input=createInput(f,"r1","u1","u3");JsonNode first=ok(send("POST",createPath(f),input,createKey));String id=first.path("plan_id").asText();ObjectNode edit=revisionInput(detail(id),"r2","u2","u4");JsonNode second=ok(send("POST",path(id)+"/revisions",edit,revisionKey));
        Files.delete(f.transcript());Map<String,List<Map<String,Object>>> before=state();error(send("GET",path(id),null,null),409,"MANUAL_CLIP_SOURCE_INVALID");
        assertEquals(first,ok(send("POST",createPath(f),input,createKey)));assertEquals(second,ok(send("POST",path(id)+"/revisions",edit,revisionKey)));assertEquals(first,ok(send("GET","/api/jobs/"+f.job()+"/manual-clip-plan-submissions/"+createKey,null,null)));assertEquals(second,ok(send("GET",path(id)+"/revision-submissions/"+revisionKey,null,null)));assertEquals(before,state());
    }

    @Test void tamperedReceiptsInputHashesAndOriginalRevisionContentCannotBeRecovered()throws Exception{
        var f=fixture();String key=key();ObjectNode input=createInput(f,"原始","u1","u3");JsonNode receipt=ok(send("POST",createPath(f),input,key));String id=receipt.path("plan_id").asText();
        String original=jdbc.queryForObject("SELECT receipt_json FROM manual_clip_submission WHERE idempotency_key=?",String.class,key);ObjectNode changed=(ObjectNode)JSON.readTree(original);changed.put("revision",2);jdbc.update("UPDATE manual_clip_submission SET receipt_json=? WHERE idempotency_key=?",JSON.writeValueAsString(changed),key);
        error(send("GET","/api/jobs/"+f.job()+"/manual-clip-plan-submissions/"+key,null,null),409,"MANUAL_CLIP_SUBMISSION_INVALID");error(send("POST",createPath(f),input,key),409,"MANUAL_CLIP_SUBMISSION_INVALID");jdbc.update("UPDATE manual_clip_submission SET receipt_json=? WHERE idempotency_key=?",original,key);
        jdbc.update("UPDATE manual_clip_submission SET request_hash=? WHERE idempotency_key=?","0".repeat(64),key);error(send("POST",createPath(f),input,key),409,"MANUAL_CLIP_SUBMISSION_INVALID");jdbc.update("UPDATE manual_clip_submission SET request_hash=? WHERE idempotency_key=?",receipt.path("request_hash").asText(),key);
        jdbc.update("UPDATE manual_clip_revision SET title='被篡改' WHERE plan_id=? AND revision=1",id);error(send("GET","/api/jobs/"+f.job()+"/manual-clip-plan-submissions/"+key,null,null),409,"MANUAL_CLIP_SUBMISSION_INVALID");
    }

    @Test void invalidNewInputsRangesAndSourcesLeaveNoPlanRevisionOrReceipt()throws Exception{
        var f=fixture();ObjectNode original=createInput(f,"标题","u1","u3");
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(v->v.remove("title"),v->v.put("title"," "),v->v.put("title","x".repeat(121)),v->v.put("title","😀".repeat(61)),v->v.put("start_ms",0),v->v.put("source_fingerprint","bad"),v->v.put("start_utterance_id","../x"))){ObjectNode input=original.deepCopy();change.accept(input);error(send("POST",createPath(f),input,key()),400,"MANUAL_CLIP_INVALID");}
        for(String[] range:List.of(new String[]{"unknown","u3"},new String[]{"u4","u2"})){error(send("POST",createPath(f),createInput(f,"范围",range[0],range[1]),key()),400,"MANUAL_CLIP_INVALID_RANGE");}
        ObjectNode foreign=original.deepCopy().put("source_fingerprint","0".repeat(64));error(send("POST",createPath(f),foreign,key()),409,"MANUAL_CLIP_SOURCE_CHANGED");
        Files.writeString(f.transcript(),"tampered");error(send("POST",createPath(f),original,key()),409,"MANUAL_CLIP_SOURCE_INVALID");assertEquals(0,count("manual_clip_plan"));assertEquals(0,count("manual_clip_revision"));assertEquals(0,count("manual_clip_submission"));
    }

    @Test void staleBaseAndHashFailuresDoNotAppendAnyRevisionOrConsumeTheNewKey()throws Exception{
        var f=fixture();JsonNode first=ok(send("POST",createPath(f),createInput(f,"r1","u1","u3"),key()));String id=first.path("plan_id").asText();JsonNode r1=detail(id);ok(send("POST",path(id)+"/revisions",revisionInput(r1,"r2","u2","u4"),key()));JsonNode r2=detail(id);Map<String,List<Map<String,Object>>> before=state();
        error(send("POST",path(id)+"/revisions",revisionInput(r1,"stale","u1","u2"),key()),409,"MANUAL_CLIP_REVISION_CONFLICT");ObjectNode badHash=revisionInput(r2,"bad","u1","u3").put("base_plan_hash","0".repeat(64));error(send("POST",path(id)+"/revisions",badHash,key()),409,"MANUAL_CLIP_REVISION_CONFLICT");
        ObjectNode badRange=revisionInput(r2,"倒序","u5","u2");error(send("POST",path(id)+"/revisions",badRange,key()),400,"MANUAL_CLIP_INVALID_RANGE");assertEquals(before,state());
    }

    @Test void confirmationRequiresTheCurrentExactVersionAndNeverTransfersToNewRevisions()throws Exception{
        var f=fixture();String id=ok(send("POST",createPath(f),createInput(f,"r1","u1","u3"),key())).path("plan_id").asText();JsonNode r1=detail(id);
        error(send("POST",path(id)+"/confirm",confirmation(r1).put("accept_sentence_boundaries",false),null),400,"MANUAL_CLIP_INVALID");error(send("POST",path(id)+"/confirm",confirmation(r1).put("plan_hash","0".repeat(64)),null),409,"MANUAL_CLIP_REVISION_CONFLICT");assertEquals("MANUAL_CLIP_CONFIRMATION_REQUIRED",lockedFailure(id,1,r1.path("plan_hash").asText(),f));
        JsonNode confirmed=confirm(id,r1);assertEquals("CONFIRMED",confirmed.path("status").asText());Map<String,List<Map<String,Object>>> before=state();assertEquals(confirmed,confirm(id,r1));assertEquals(before,state());
        ok(send("POST",path(id)+"/revisions",revisionInput(r1,"r2","u2","u4"),key()));JsonNode r2=detail(id);assertEquals("DRAFT",r2.path("status").asText());assertEquals(List.of(1L),r2.path("confirmed_revisions").valueStream().map(JsonNode::asLong).toList());error(send("POST",path(id)+"/confirm",confirmation(r1),null),409,"MANUAL_CLIP_REVISION_CONFLICT");assertEquals("MANUAL_CLIP_CONFIRMATION_REQUIRED",lockedFailure(id,2,r2.path("plan_hash").asText(),f));
        JsonNode old=locked(id,1,r1.path("plan_hash").asText(),f);assertEquals(1,old.path("revision").asInt());assertEquals(2,old.path("latest_revision").asInt());assertEquals("CONFIRMED",old.path("status").asText());
    }

    @Test void storedRangeAndDeletedConfirmationCannotGrantExportAuthority()throws Exception{
        var f=fixture();String id=ok(send("POST",createPath(f),createInput(f,"r1","u1","u3"),key())).path("plan_id").asText();JsonNode plan=detail(id);confirm(id,plan);
        jdbc.update("DELETE FROM manual_clip_confirmation WHERE plan_id=?",id);assertEquals("MANUAL_CLIP_CONFIRMATION_REQUIRED",lockedFailure(id,1,plan.path("plan_hash").asText(),f));confirm(id,plan);
        ObjectNode payload=(ObjectNode)JSON.readTree(jdbc.queryForObject("SELECT content_json FROM manual_clip_revision WHERE plan_id=? AND revision=1",String.class,id));((ObjectNode)payload.path("range")).put("utterance_count",2);String changedHash=digest(payload);jdbc.update("UPDATE manual_clip_revision SET content_json=?,plan_hash=? WHERE plan_id=? AND revision=1",canonical(payload),changedHash,id);jdbc.update("UPDATE manual_clip_confirmation SET plan_hash=? WHERE plan_id=?",changedHash,id);
        assertEquals("MANUAL_CLIP_SOURCE_INVALID",lockedFailure(id,1,changedHash,f));error(send("GET",path(id),null,null),409,"MANUAL_CLIP_SOURCE_INVALID");
    }

    @Test void listAndExactGetAreReadOnlyAndMetadataDoesNotRequireLiveArtifacts()throws Exception{
        var f=fixture();String id=ok(send("POST",createPath(f),createInput(f,"只读","u1","u3"),key())).path("plan_id").asText();Map<String,List<Map<String,Object>>> before=state();JsonNode detail=detail(id);JsonNode list=ok(send("GET",createPath(f)+"?limit=1&offset=0",null,null));
        assertEquals(Set.of("schema_version","data_kind","job_id","items","total","limit","offset"),list.propertyNames());assertEquals("manual_clip_plan_list",list.path("data_kind").asText());assertEquals(1,list.path("total").asInt());assertEquals(Set.of("id","job_id","video_id","title","revision","plan_hash","status","created_at","updated_at"),list.path("items").get(0).propertyNames());assertEquals(detail.path("plan_hash"),list.path("items").get(0).path("plan_hash"));assertEquals(before,state());
        Files.delete(f.transcript());assertEquals(list,ok(send("GET",createPath(f)+"?limit=1&offset=0",null,null)));JsonNode far=ok(send("GET",createPath(f)+"?offset=9007199254740991",null,null));assertTrue(far.path("items").isEmpty());assertEquals(before,state());
    }

    @Test void allGetQueriesAndWriteKeysRejectUnknownDuplicateEmptyAndUnsafeValues()throws Exception{
        var f=fixture();ObjectNode input=createInput(f,"合法","u1","u3");String id=ok(send("POST",createPath(f),input,key())).path("plan_id").asText();
        for(String suffix:List.of("?x=1","?limit=","?limit=0","?limit=51","?limit=01","?offset=-1","?offset=9007199254740992","?limit=1&limit=2","?offset=0&offset=0"))error(send("GET",createPath(f)+suffix,null,null),400,"MANUAL_CLIP_INVALID");
        for(String suffix:List.of("?revision=","?revision=0","?revision=01","?revision=1&revision=1","?x=1"))error(send("GET",path(id)+suffix,null,null),400,"MANUAL_CLIP_INVALID");
        error(send("GET",path(id)+"?revision=99",null,null),404,"MANUAL_CLIP_REVISION_NOT_FOUND");error(send("GET",path(UUID.randomUUID().toString()),null,null),404,"MANUAL_CLIP_NOT_FOUND");
        String unknown=key();error(send("GET","/api/jobs/"+f.job()+"/manual-clip-plan-submissions/"+unknown,null,null),404,"MANUAL_CLIP_SUBMISSION_NOT_FOUND");error(send("GET",path(id)+"/revision-submissions/"+unknown+"?x=1",null,null),400,"MANUAL_CLIP_INVALID");
        for(String invalid:List.of("","bad key","x".repeat(101),"../bad"))error(send("POST",createPath(f),input,invalid),400,"MANUAL_CLIP_INVALID");error(send("POST",createPath(f),input,null),400,"MANUAL_CLIP_INVALID");
    }

    @Test void sourceIoIsOutsideTransactionsAndChangedAdmissionRollsBackEveryManualRow()throws Exception{
        var f=fixture();var manager=app.getBean(PlatformTransactionManager.class);AtomicBoolean checked=new AtomicBoolean();ArtifactVerifier real=new ArtifactVerifier(root){@Override public VerifiedManifest verifyManifest(String key,String hash,ManifestIdentity expected){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());checked.set(true);return super.verifyManifest(key,hash,expected);}};
        TranscriptService changedAfterAdmission=new TranscriptService(jdbc,real,manager){@Override public SourceTicket verifySource(String job,String expected){SourceTicket ticket=super.verifySource(job,expected);jdbc.update("UPDATE job_stage SET run_token=? WHERE id=?",UUID.randomUUID().toString(),f.asrStage());return ticket;}};
        var plans=new ManualClipPlanService(jdbc,changedAfterAdmission,manager);ObjectNode input=createInput(f,"竞态","u1","u3");
        try{JobException failure=assertThrows(JobException.class,()->plans.create(f.job(),input,key()));assertEquals("MANUAL_CLIP_SOURCE_CHANGED",failure.getCode());assertTrue(checked.get());assertEquals(0,count("manual_clip_plan"));assertEquals(0,count("manual_clip_revision"));assertEquals(0,count("manual_clip_submission"));}
        finally{jdbc.update("UPDATE job_stage SET run_token=? WHERE id=?",f.token(),f.asrStage());}
        JsonNode receipt=app.getBean(ManualClipPlanService.class).create(f.job(),input,key());assertEquals(1,receipt.path("revision").asInt());String id=receipt.path("plan_id").asText();JsonNode saved=detail(id);
        for(String operation:List.of("revision","confirm")){
            long revisions=count("manual_clip_revision"),receipts=count("manual_clip_submission"),confirmations=count("manual_clip_confirmation");
            try{JobException failure=assertThrows(JobException.class,()->{if(operation.equals("revision"))plans.revise(id,revisionInput(saved,"race","u2","u4"),key());else plans.confirm(id,confirmation(saved));});assertEquals("MANUAL_CLIP_SOURCE_CHANGED",failure.getCode());}
            finally{jdbc.update("UPDATE job_stage SET run_token=? WHERE id=?",f.token(),f.asrStage());}
            assertEquals(revisions,count("manual_clip_revision"));assertEquals(receipts,count("manual_clip_submission"));assertEquals(confirmations,count("manual_clip_confirmation"));
        }
        var audited=new ManualClipPlanService(jdbc,new TranscriptService(jdbc,real,manager),manager);
        JsonNode auditedReceipt=audited.create(f.job(),createInput(f,"事务外验源","u1","u3"),key());String auditedId=auditedReceipt.path("plan_id").asText();JsonNode auditedR1=audited.get(auditedId,null);
        audited.revise(auditedId,revisionInput(auditedR1,"事务外修订验源","u2","u4"),key());JsonNode auditedR2=audited.get(auditedId,null);assertEquals("CONFIRMED",audited.confirm(auditedId,confirmation(auditedR2)).path("status").asText());
    }

    @Test void concurrentIdenticalCreatesShareOneReceiptAndCompetingRevisionsDoNotOverwriteHead()throws Exception{
        var f=fixture();ObjectNode input=createInput(f,"并发","u1","u3");String key=key();CountDownLatch gate=new CountDownLatch(1);
        var a=CompletableFuture.supplyAsync(()->after(gate,createPath(f),input,key));var b=CompletableFuture.supplyAsync(()->after(gate,createPath(f),input,key));gate.countDown();JsonNode one=ok(a.get(10,TimeUnit.SECONDS)),two=ok(b.get(10,TimeUnit.SECONDS));assertEquals(one,two);assertEquals(1,scopedCount("manual_clip_plan",f.job()));assertEquals(1,scopedCount("manual_clip_submission",f.job()));
        String id=one.path("plan_id").asText();JsonNode head=detail(id);CountDownLatch revisions=new CountDownLatch(1);var first=CompletableFuture.supplyAsync(()->after(revisions,path(id)+"/revisions",revisionInput(head,"A","u1","u2"),key()));var second=CompletableFuture.supplyAsync(()->after(revisions,path(id)+"/revisions",revisionInput(head,"B","u2","u4"),key()));revisions.countDown();List<HttpResponse<byte[]>> results=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));assertEquals(List.of(200,409),results.stream().map(HttpResponse::statusCode).sorted().toList());assertEquals(2,detail(id).path("revision").asInt());assertEquals(2,count("manual_clip_revision"));assertEquals(2,count("manual_clip_submission"));
    }

    private JsonNode locked(String id,long revision,String hash,Fixture f){var ticket=app.getBean(TranscriptService.class).verifySource(f.job(),f.fingerprint());return new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).execute(ignored->{jdbc.queryForList("SELECT id FROM analysis_job WHERE id=? FOR UPDATE",f.job());return app.getBean(ManualClipPlanService.class).confirmedLocked(id,revision,hash,ticket);});}
    private String lockedFailure(String id,long revision,String hash,Fixture f){return assertThrows(JobException.class,()->locked(id,revision,hash,f)).getCode();}
    private JsonNode detail(String id)throws Exception{return ok(send("GET",path(id),null,null));}
    private JsonNode confirm(String id,JsonNode detail)throws Exception{return ok(send("POST",path(id)+"/confirm",confirmation(detail),null));}
    private static ObjectNode confirmation(JsonNode detail){return JSON.createObjectNode().put("revision",detail.path("revision").asLong()).put("plan_hash",detail.path("plan_hash").asText()).put("accept_sentence_boundaries",true);}
    private static ObjectNode createInput(Fixture f,String title,String start,String end){return JSON.createObjectNode().put("source_fingerprint",f.fingerprint()).put("title",title).put("start_utterance_id",start).put("end_utterance_id",end);}
    private static ObjectNode revisionInput(JsonNode detail,String title,String start,String end){return JSON.createObjectNode().put("base_revision",detail.path("revision").asLong()).put("base_plan_hash",detail.path("plan_hash").asText()).put("source_fingerprint",detail.path("source").path("source_fingerprint").asText()).put("title",title).put("start_utterance_id",start).put("end_utterance_id",end);}
    private static String key(){return UUID.randomUUID().toString();}
    private static String path(String id){return "/api/manual-clip-plans/"+id;}
    private static String createPath(Fixture f){return "/api/jobs/"+f.job()+"/manual-clip-plans";}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private long scopedCount(String table,String job){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE job_id=?",Long.class,job);}
    private Map<String,List<Map<String,Object>>> state(){Map<String,List<Map<String,Object>>> result=new TreeMap<>();for(String table:List.of("manual_clip_plan","manual_clip_revision","manual_clip_confirmation","manual_clip_submission","analysis_job","job_stage","model_call","review_document"))result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1,2"));return result;}
    private HttpResponse<byte[]> after(CountDownLatch gate,String path,JsonNode input,String key){try{gate.await();return send("POST",path,input,key);}catch(Exception failure){throw new RuntimeException(failure);}}
    private HttpResponse<byte[]> send(String method,String path,JsonNode input,String key)throws Exception{var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15));if(key!=null)request.header("Idempotency-Key",key);if(input!=null)request.header("Content-Type","application/json");return http.send(request.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(input))).build(),HttpResponse.BodyHandlers.ofByteArray());}
    private static JsonNode ok(HttpResponse<byte[]> response){assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return JSON.readTree(response.body());}
    private static void error(HttpResponse<byte[]> response,int status,String code){assertEquals(status,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));assertEquals(code,JSON.readTree(response.body()).path("error_code").asText());}
    private static String canonical(JsonNode node){return ClipRequestService.canonicalJson(node);}
    private static String digest(JsonNode node){return ClipRequestService.sha(canonical(node));}
    private static void assertReceipt(JsonNode receipt,String operation,String owner,JsonNode input,String key,int revision){assertEquals(RECEIPT,receipt.propertyNames());assertEquals("1.0",receipt.path("schema_version").asText());assertEquals("manual_clip_submission",receipt.path("data_kind").asText());assertEquals(operation,receipt.path("operation").asText());assertEquals(input,receipt.path("input"));assertEquals(key,receipt.path("idempotency_key").asText());assertEquals(revision,receipt.path("revision").asInt());assertEquals(ClipRequestService.sha("manual-clip-request-v1\n"+operation+"\n"+owner+"\n"+canonical(input)+"\n"),receipt.path("request_hash").asText());}
    private static void assertPayloadHash(JsonNode detail){ObjectNode payload=JSON.createObjectNode();for(String name:List.of("schema_version","source_kind","id","source","revision","title","start_utterance_id","end_utterance_id","range"))payload.set(name,detail.path(name));payload.put("data_kind","manual_clip_plan_revision");assertEquals(digest(payload),detail.path("plan_hash").asText());}

    private Fixture fixture()throws Exception{
        String job=UUID.randomUUID().toString(),video=UUID.randomUUID().toString(),sourceKey="videos/"+video+"/source.mp4";byte[] raw={1,2,3,4};String sourceHash=sha(raw);write(sourceKey,raw);
        jdbc.update("INSERT INTO video_asset (id,content_hash,source_key,original_filename,size_bytes,media_validation_status,duration_ms) VALUES (?,?,?,?,4,'VALID',10000)",video,sourceHash,sourceKey,"manual-synthetic.mp4");ownVideos.add(video);
        jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,'{\"analysis_mode\":\"topics\"}','REVIEW_READY',4,1)",job,video,key(),"a".repeat(64),"b".repeat(64));ownJobs.add(job);
        var utterances=JSON.createArrayNode();String[] texts={"起句","未搜索命中的中间句","止句","第二段","尾句"};for(int i=0;i<5;i++)utterances.addObject().put("utterance_id","u"+(i+1)).put("start_ms",i*2000).put("end_ms",i*2000+1000).put("text",texts[i]).put("source_chunk_id","c0");
        ObjectNode analysis=JSON.createObjectNode().put("schema_version","1.0").put("analysis_mode","topics").put("data_kind","model_result").put("review_status","DRAFT").put("job_id",job).put("video_id",video).put("analysis_generation",1).put("duration_ms",10000).put("transcript_version","synthetic-asr-v1").put("pipeline_version","topics-text-v1");analysis.set("utterances",utterances);analysis.putArray("frames");
        var segment=analysis.putArray("segments").addObject().put("segment_id","topic-1").put("title","合成章节").put("summary","仅供协议测试").put("start_ms",0).put("end_ms",9000).put("start_utterance_id","u1").put("end_utterance_id","u5");segment.putArray("keywords").add("合成");var evidence=segment.putArray("audio_evidence_utterance_ids");for(int i=1;i<=5;i++)evidence.add("u"+i);
        analysis.putObject("provenance").put("asr_model_id","synthetic-asr").put("text_model_id","synthetic-text").put("prompt_version","synthetic-topics-v1").put("config_hash","b".repeat(64));
        String asrStage=null,asrToken=null,asrKey=null;byte[] transcript=JSON.writeValueAsBytes(utterances),analysisBytes=JSON.writeValueAsBytes(analysis);
        for(String type:List.of("ASR","TIMELINE")){String stage=UUID.randomUUID().toString(),token=UUID.randomUUID().toString(),prefix="jobs/"+job+"/stages/"+stage+"/attempts/1/"+token+"/",kind=type.equals("ASR")?"transcript":"analysis_result",artifact=prefix+kind+".json";byte[] bytes=type.equals("ASR")?transcript:analysisBytes;write(artifact,bytes);
            ObjectNode manifest=JSON.createObjectNode().put("schema_version",1).put("job_id",job).put("video_id",video).put("analysis_generation",1).put("stage_id",stage).put("stage_type",type).put("attempt",1).put("run_token",token).put("input_sha256",sourceHash).put("data_kind","model_result").put("review_status","DRAFT");manifest.putArray("artifacts").addObject().put("kind",kind).put("key",artifact).put("sha256",sha(bytes));byte[] sealed=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",sealed);
            jdbc.update("INSERT INTO job_stage (id,job_id,owner_id,stage_type,status,attempt,run_token,input_hash,manifest_key,manifest_hash) VALUES (?,?,?,?,'SUCCEEDED',1,?,?,?,?)",stage,job,job,type,token,sourceHash,prefix+"manifest.json",sha(sealed));if(type.equals("ASR")){asrStage=stage;asrToken=token;asrKey=artifact;}}
        String fingerprint=ClipRequestService.sha("transcript-source-v1\n"+job+"\n"+video+"\n1\n"+sourceHash+"\n"+sha(transcript)+"\n"+sha(analysisBytes)+"\n10000\n");return new Fixture(job,video,fingerprint,asrStage,asrToken,root.resolve(asrKey));
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void write(String key,byte[] bytes)throws Exception{Path path=root.resolve(key);Files.createDirectories(path.getParent());Files.write(path,bytes);}
}
