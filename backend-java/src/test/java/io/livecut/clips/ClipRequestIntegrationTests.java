package io.livecut.clips;

import static org.junit.jupiter.api.Assertions.*;
import static io.livecut.clips.ClipPlanValidator.JSON;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;
import io.livecut.LivecutBackendApplication;
import io.livecut.exports.ExportService;
import io.livecut.exports.ExportVideoDeliveryService;
import io.livecut.agent.AgentService;
import io.livecut.agent.AgentModels;
import io.livecut.jobs.*;
import io.livecut.jobs.JobModels.*;
import io.livecut.jobs.ModelCallModels.*;
import io.livecut.video.VideoService;
import io.livecut.video.VideoApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real MySQL and HTTP with explicitly synthetic media/model artifacts. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClipRequestIntegrationTests {
    @TempDir static Path root;
    private static final String KEY="intent-test-only-worker-key";
    private ConfigurableApplicationContext app;private JdbcTemplate jdbc;private JobService jobs;private ClipRequestService clips;private ExportService exports;private ModelCallService calls;
    private URI base;private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> ownJobs=new ArrayList<>(),ownVideos=new ArrayList<>();
    private final List<String> ownWorkers=new ArrayList<>();
    private record Fixture(String job,String video,String hash,JsonNode utterances) {}
    @BeforeAll void start(){assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"));startContext();}
    private void startContext(){String font=System.getenv("LIVECUT_CAPTION_FONT_PATH");if(font==null||font.isBlank())font=Path.of("../data/fonts/noto-sans-cjk-sc-2.004/NotoSansCJKsc-Regular.otf").toAbsolutePath().normalize().toString();app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,"--livecut.captions.render-font-path="+font,"--livecut.worker.api-key="+KEY,"--livecut.workers.require-registration=false","--livecut.jobs.scan-enabled=false","--livecut.jobs.retry-delay-seconds=0","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false","--spring.main.banner-mode=off","--logging.level.root=WARN");jdbc=app.getBean(JdbcTemplate.class);jobs=app.getBean(JobService.class);clips=app.getBean(ClipRequestService.class);exports=app.getBean(ExportService.class);calls=app.getBean(ModelCallService.class);base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));}
    @AfterEach void cleanup(){for(String id:ownWorkers){jdbc.update("DELETE FROM worker_instance WHERE worker_id=?",id);jdbc.update("DELETE FROM worker_session_history WHERE worker_id=?",id);}ownWorkers.clear();for(String id:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);for(String id:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);ownJobs.clear();ownVideos.clear();}
    @AfterAll void stop(){if(app!=null)app.close();}

    @Test void historyForNewSourceHasNoItemsCursorOrActiveRequests()throws Exception{
        Fixture f=fixture();
        for(String query:List.of("","?limit=1","?limit=30")){
            var response=send("GET","/api/jobs/"+f.job()+"/clip-request-history"+query,null,null,false);assertEquals(200,response.statusCode());
            JsonNode page=JSON.readTree(response.body());assertEquals("1.0",page.path("schema_version").asText());assertEquals(f.job(),page.path("job_id").asText());
            assertTrue(page.path("items").isArray());assertTrue(page.path("items").isEmpty());assertTrue(page.path("next_cursor").isNull());assertEquals(0,page.path("active_count").asInt());
            assertEquals(query.isEmpty()?20:Integer.parseInt(query.substring(7)),page.path("limit").asInt());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM clip_request WHERE job_id=?",Integer.class,f.job()));
    }

    @Test void historyTraversesBeyondLegacyLimitWithMicrosecondAndSameTimeTies()throws Exception{
        Fixture f=fixture();var seed=create(f,4);var time=java.time.LocalDateTime.of(2026,11,7,12,0,0,123456000);
        List<String> ids=new ArrayList<>();ids.add(seed.id());
        for(int i=1;i<107;i++){String id=UUID.randomUUID().toString();cloneHistoryRow(seed.id(),id);ids.add(id);}
        // Millisecond-equal rows exercise DATETIME(6), and tied microseconds exercise ascii_bin UUIDs.
        Map<String,java.time.LocalDateTime> times=new HashMap<>();
        for(int i=0;i<ids.size();i++){var created=time.plusNanos((i%3)*1000L);times.put(ids.get(i),created);jdbc.update("UPDATE clip_request SET created_at=?,updated_at=? WHERE id=?",created,created,ids.get(i));}
        ids.sort(Comparator.<String,java.time.LocalDateTime>comparing(times::get).thenComparing(Comparator.naturalOrder()).reversed());
        List<String> observed=new ArrayList<>();String cursor=null;int pages=0;
        do{JsonNode page=history(f.job(),30,cursor);assertEquals("1.0",page.path("schema_version").asText());assertEquals(f.job(),page.path("job_id").asText());assertEquals(107,page.path("active_count").asInt());assertEquals(30,page.path("limit").asInt());for(JsonNode item:page.path("items")){assertEquals(f.job(),item.path("job_id").asText());observed.add(item.path("id").asText());}cursor=page.path("next_cursor").isNull()?null:page.path("next_cursor").asText();pages++;}while(cursor!=null);
        assertEquals(ids,observed);assertEquals(4,pages);assertEquals(107,new HashSet<>(observed).size());
    }

    @Test void historyCeilingAndKeysetSurviveConcurrentInsertAndMutableStateChanges()throws Exception{
        Fixture f=fixture();var seed=create(f,4);var time=java.time.LocalDateTime.of(2026,11,7,12,0,0,123456000);List<String> ids=new ArrayList<>(List.of(seed.id()));
        for(int i=0;i<5;i++){String id=UUID.randomUUID().toString();cloneHistoryRow(seed.id(),id);ids.add(id);}
        for(int i=0;i<ids.size();i++)jdbc.update("UPDATE clip_request SET created_at=?,updated_at=? WHERE id=?",time.plusSeconds(i),time,ids.get(i));Collections.reverse(ids);
        JsonNode first=history(f.job(),2,null);String cursor=first.path("next_cursor").asText();
        String newest=UUID.randomUUID().toString();CompletableFuture.runAsync(()->{cloneHistoryRow(seed.id(),newest);jdbc.update("UPDATE clip_request SET created_at=?,updated_at=? WHERE id=?",time.plusDays(1),time.plusDays(1),newest);}).join();
        jdbc.update("UPDATE clip_request SET status='FAILED',state_version=state_version+1,updated_at=? WHERE id=?",time.plusDays(2),ids.getLast());
        List<String> observed=new ArrayList<>();for(JsonNode item:first.path("items"))observed.add(item.path("id").asText());
        do{JsonNode page=history(f.job(),2,cursor);assertEquals(6,page.path("active_count").asInt(),"count includes new active rows outside the fixed ceiling and excludes failed rows");for(JsonNode item:page.path("items"))observed.add(item.path("id").asText());cursor=page.path("next_cursor").isNull()?null:page.path("next_cursor").asText();}while(cursor!=null);
        assertEquals(ids,observed);assertEquals(newest,history(f.job(),2,null).path("items").get(0).path("id").asText());
    }

    @Test void historyRejectsInvalidForeignForgedAndDeletedBoundariesWithoutWriting()throws Exception{
        Fixture f=fixture();var a=create(f,4);var b=create(f,4);Fixture other=fixture();
        JsonNode first=history(f.job(),1,null);String cursor=first.path("next_cursor").asText();
        assertHistoryError(other.job(),"?cursor="+cursor,400,"INVALID_CLIP_HISTORY_CURSOR");
        assertHistoryError(f.job(),"?cursor=",400,"INVALID_CLIP_HISTORY_CURSOR");
        assertHistoryError(f.job(),"?cursor=%25",400,"INVALID_CLIP_HISTORY_CURSOR");
        for(String limit:List.of("0","31","01","1.0","-1","","1,2"))assertHistoryError(f.job(),"?limit="+limit,400,"INVALID_CLIP_HISTORY_LIMIT");
        assertHistoryError(UUID.randomUUID().toString(),"",404,"JOB_NOT_FOUND");
        var decoded=ClipRequestHistoryService.decode(cursor,f.job());
        var forged=new ClipRequestHistoryService.Cursor(f.job(),new ClipRequestHistoryService.Boundary(decoded.ceiling().createdAt().plusNanos(1000),decoded.ceiling().id()),decoded.after());
        assertHistoryError(f.job(),"?cursor="+ClipRequestHistoryService.encode(forged),400,"INVALID_CLIP_HISTORY_CURSOR");
        jdbc.update("DELETE FROM clip_request WHERE id=?",decoded.after().id());
        assertHistoryError(f.job(),"?cursor="+cursor,400,"INVALID_CLIP_HISTORY_CURSOR");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM clip_request WHERE job_id=?",Integer.class,f.job()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id IN (?,?)",Integer.class,f.job(),other.job()));
    }

    @Test void historyResponseDoesNotGrowWithFrozenTranscriptPlanOrAnswerDocuments()throws Exception{
        Fixture f=fixture();var request=ready(f);String path="/api/jobs/"+f.job()+"/clip-request-history";
        var before=send("GET",path,null,null,false);assertEquals(200,before.statusCode());
        JsonNode page=JSON.readTree(before.body()),item=page.path("items").get(0);
        assertEquals(Set.of("schema_version","job_id","items","limit","next_cursor","active_count"),page.propertyNames());
        assertEquals(Set.of("id","job_id","video_id","instruction","status","state_version","created_at","updated_at","budget","source","revision","plan_hash","source_mode"),item.propertyNames());
        assertEquals(20,page.path("limit").asInt());assertTrue(page.path("next_cursor").isNull());assertEquals(request.planHash(),item.path("plan_hash").asText());assertEquals("INSTRUCTION",item.path("source_mode").asText());
        var huge=JSON.createArrayNode();for(int i=0;i<15000;i++)huge.addObject().put("utterance_id","large-"+i).put("text","合成转写仅测试摘要不读取全文".repeat(10));
        String source=JSON.writeValueAsString(Map.of("utterances",huge)),plan=JSON.writeValueAsString(Map.of("test_padding","x".repeat(2_000_000)));
        jdbc.update("UPDATE clip_request SET source_json=? WHERE id=?",source,request.id());jdbc.update("UPDATE clip_plan_revision SET plan_json=? WHERE request_id=?",plan,request.id());
        var after=send("GET",path,null,null,false);assertEquals(200,after.statusCode());assertArrayEquals(before.body(),after.body(),"history must not serialize or validate full source or plan documents");assertTrue(after.body().length<2000);
        jdbc.update("UPDATE clip_request SET source_answer_json=?,source_answer_hash=? WHERE id=?",plan,"a".repeat(64),request.id());
        JsonNode anchored=history(f.job(),20,null).path("items").get(0);assertEquals("ANSWER_CITATIONS",anchored.path("source_mode").asText());assertFalse(anchored.has("source_answer"));
        assertTrue(send("GET","/api/clip-requests/"+request.id(),null,null,false).statusCode()>=400,"selected detail still validates frozen provenance");
    }

    @Test void historyReadsDoNotAcquireAnalysisOrRequestWriteLocks()throws Exception{
        Fixture f=fixture();var request=create(f,4);
        var template=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        template.execute(status->{jdbc.queryForObject("SELECT id FROM analysis_job WHERE id=? FOR UPDATE",String.class,f.job());jdbc.queryForObject("SELECT id FROM clip_request WHERE id=? FOR UPDATE",String.class,request.id());
            var result=CompletableFuture.supplyAsync(()->{try{return history(f.job(),20,null);}catch(Exception error){throw new RuntimeException(error);}});
            assertDoesNotThrow(()->assertEquals(request.id(),result.get(5,java.util.concurrent.TimeUnit.SECONDS).path("items").get(0).path("id").asText()));return null;});
    }

    private void cloneHistoryRow(String seed,String id){jdbc.update("INSERT INTO clip_request (id,job_id,video_id,idempotency_key,request_hash,instruction,text_model_id,source_hash,transcript_hash,transcript_key,source_json,duration_ms,max_text_calls) SELECT ?,job_id,video_id,?,request_hash,instruction,text_model_id,source_hash,transcript_hash,transcript_key,source_json,duration_ms,max_text_calls FROM clip_request WHERE id=?",id,"history-"+id,seed);}
    private JsonNode history(String job,int limit,String cursor)throws Exception{var response=send("GET","/api/jobs/"+job+"/clip-request-history?limit="+limit+(cursor==null?"":"&cursor="+cursor),null,null,false);assertEquals(200,response.statusCode(),()->new String(response.body(),StandardCharsets.UTF_8));return JSON.readTree(response.body());}
    private void assertHistoryError(String job,String query,int status,String code)throws Exception{var response=send("GET","/api/jobs/"+job+"/clip-request-history"+query,null,null,false);assertEquals(status,response.statusCode());assertEquals(code,JSON.readTree(response.body()).path("error_code").asText());}

    @Test void requestsFreezeSourceAndIdempotencyWithoutTouchingAnalysisOrDraft() throws Exception {
        Fixture f=fixture();var before=jobs.get(f.job());String key="intent-"+UUID.randomUUID();
        var first=clips.create(f.job(),new ClipRequestModels.CreateRequest("分别找原因和方法",6),key);
        assertEquals(first.id(),clips.create(f.job(),new ClipRequestModels.CreateRequest("分别找原因和方法",6),key).id());
        assertEquals("IDEMPOTENCY_CONFLICT",failure(()->clips.create(f.job(),new ClipRequestModels.CreateRequest("其他要求",6),key)));
        assertEquals(before,jobs.get(f.job()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM review_document WHERE job_id=?",Integer.class,f.job()));
        assertEquals("QUEUED",first.status());assertNull(first.plan());assertEquals(4,first.utterances().size());assertEquals(6,first.budget().maxTextCalls());
        String publicJson=JSON.writeValueAsString(first);for(String secret:List.of("run_token","transcript_key","source_json","jobs/"))assertFalse(publicJson.contains(secret));
        assertEquals(1,clips.list(f.job()).items().size());
    }
    @Test void incompleteAndTamperedSourcesAreRejectedBeforeCreatingAnyRequest() throws Exception {
        Fixture f=fixture();jdbc.update("UPDATE analysis_job SET status='RUNNING' WHERE id=?",f.job());
        assertEquals("CLIP_SOURCE_NOT_READY",failure(()->create(f,4)));jdbc.update("UPDATE analysis_job SET status='REVIEW_READY' WHERE id=?",f.job());
        String manifest=jdbc.queryForObject("SELECT manifest_key FROM job_stage WHERE job_id=? AND stage_type='ASR'",String.class,f.job());
        Files.writeString(root.resolve(manifest),"tampered");assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->create(f,4)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM clip_request WHERE job_id=?",Integer.class,f.job()));
    }
    @Test void newProviderModelDoesNotChangeSourceOrExistingRequest() throws Exception {
        Fixture f=fixture();var original=jobs.get(f.job());String key="provider-"+UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(clips,"preferredTextModel","deepseek-flash");
        try {
            var first=clips.create(f.job(),new ClipRequestModels.CreateRequest("分别找原因和方法",6),key);
            assertEquals("deepseek-flash",jdbc.queryForObject("SELECT text_model_id FROM clip_request WHERE id=?",String.class,first.id()));
            assertEquals(original,jobs.get(f.job()));
            org.springframework.test.util.ReflectionTestUtils.setField(clips,"preferredTextModel","qwen-plus");
            assertEquals(first.id(),clips.create(f.job(),new ClipRequestModels.CreateRequest("分别找原因和方法",6),key).id());
            var second=create(f,6);assertEquals("qwen-plus",jdbc.queryForObject("SELECT text_model_id FROM clip_request WHERE id=?",String.class,second.id()));
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(clips,"preferredTextModel","");}
    }
    @Test void clipBudgetIsIndependentAndUnknownDispatchCannotSpendAgain() throws Exception {
        Fixture f=fixture();var original=jobs.get(f.job());var request=create(f,1);var lease=claim(request);
        String requestId="a".repeat(64);var reservation=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),requestId,"b".repeat(64),"intent-first","qwen-plus","TEXT");
        assertEquals("RESERVED",calls.reserve(reservation).outcome());assertEquals("EXISTING",calls.reserve(reservation).outcome());
        assertEquals(1,clips.get(request.id()).budget().textCallsReserved());assertEquals(original,jobs.get(f.job()));
        assertEquals("UPDATED",calls.update(requestId,new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of())).outcome());
        calls.update(requestId,new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"UNKNOWN_OUTCOME",null,Map.of()));
        assertEquals("MODEL_CALL_STATE_CONFLICT",failure(()->calls.update(requestId,new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of()))));
        assertEquals("MODEL_BUDGET_EXHAUSTED",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"c".repeat(64),"d".repeat(64),"intent-second","qwen-plus","TEXT"))));
        assertEquals("MODEL_KIND_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"e".repeat(64),"f".repeat(64),"intent-asr","paraformer-v2","ASR"))));
        assertEquals("CLIP_REQUEST",jdbc.queryForObject("SELECT owner_type FROM model_call WHERE request_id=?",String.class,requestId));
    }

    @Test void readOnlyCallLookupDoesNotReserveOrResetExhaustedBudget() throws Exception {
        Fixture f=fixture();var request=create(f,1);var lease=claim(request);
        var input=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"9".repeat(64),"8".repeat(64),"lookup-only","qwen-plus","TEXT");
        assertEquals("NOT_FOUND",calls.lookup(input).outcome());assertNull(calls.lookup(input).call());
        assertEquals(0,clips.get(request.id()).budget().textCallsReserved());
        calls.reserve(input);assertEquals("EXISTING",calls.lookup(input).outcome());
        assertEquals(input.requestId(),calls.lookup(input).call().requestId());
        assertEquals(1,clips.get(request.id()).budget().textCallsReserved());
        var wrongInput=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),input.requestId(),"7".repeat(64),input.operationKey(),"qwen-plus","TEXT");
        assertEquals("MODEL_CALL_INPUT_CONFLICT",failure(()->calls.lookup(wrongInput)));
        var stale=new ReserveRequest(lease.stageId(),lease.attempt(),"stale-token-00000000",input.requestId(),input.inputHash(),input.operationKey(),"qwen-plus","TEXT");
        assertEquals("STALE_ATTEMPT",failure(()->calls.lookup(stale)));
        assertEquals(1,clips.get(request.id()).budget().textCallsReserved());
    }

    @Test void queuedCancellationIsIdempotentAndLeavesAnalysisAndDraftUntouched() throws Exception {
        Fixture f=fixture();var before=jobs.get(f.job());var request=create(f,4);
        var cancelled=clips.cancel(request.id());assertEquals("CANCELLED",cancelled.status());assertEquals("CANCELLED",cancelled.stage().status());
        assertEquals(cancelled,clips.cancel(request.id()));assertEquals("NO_WORK",claim(cancelled).outcome());
        clips.refreshLocked(request.id());assertEquals(cancelled,clips.get(request.id()));
        assertEquals(before,jobs.get(f.job()));assertEquals(0,cancelled.budget().textCallsReserved());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM review_document WHERE job_id=?",Integer.class,f.job()));
    }

    @Test void runningCancellationFencesLateResultsButDispatchedCallsCanSettle() throws Exception {
        Fixture f=fixture();var before=jobs.get(f.job());var request=create(f,4);var lease=claim(request);
        var completion=publishPlan(f,request,lease,null);String callId="6".repeat(64);
        calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),callId,"5".repeat(64),"cancelled-dispatch","qwen-plus","TEXT"));
        calls.update(callId,new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of()));
        var cancelled=clips.cancel(request.id());assertEquals(1,cancelled.budget().textCallsReserved());
        Map<String,Object> row=jdbc.queryForMap("SELECT run_token,worker_id,worker_session_id,lease_until,hard_deadline_at FROM job_stage WHERE id=?",lease.stageId());assertTrue(row.values().stream().allMatch(Objects::isNull));
        assertEquals("STALE_ATTEMPT",failure(()->jobs.heartbeat(lease.stageId(),new Credential(lease.attempt(),lease.runToken()))));
        assertEquals("STALE_ATTEMPT",failure(()->jobs.complete(lease.stageId(),completion)));
        assertEquals("STALE_ATTEMPT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"4".repeat(64),"3".repeat(64),"after-cancel","qwen-plus","TEXT"))));
        assertEquals("UPDATED",calls.update(callId,new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"UNKNOWN_OUTCOME",null,Map.of())).outcome());
        assertEquals("UPDATED",calls.update(callId,new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"SUCCEEDED",null,Map.of("total_tokens",12))).outcome());
        assertEquals("CANCELLED",clips.get(request.id()).status());assertEquals(before,jobs.get(f.job()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_revision WHERE request_id=?",Integer.class,request.id()));
    }

    @Test void resumeCreatesOneIndependentRoundWithCurrentProviderAndFrozenSource() throws Exception {
        Fixture f=fixture();var before=jobs.get(f.job());var old=create(f,2);var lease=claim(old);
        calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"2".repeat(64),"1".repeat(64),"old-budget","qwen-plus","TEXT"));
        var cancelled=clips.cancel(old.id());String key="resume-"+UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(clips,"preferredTextModel","deepseek-flash");
        try {
            var first=CompletableFuture.supplyAsync(()->clips.resume(old.id(),new ClipRequestModels.ResumeRequest(3),key));
            var second=CompletableFuture.supplyAsync(()->clips.resume(old.id(),new ClipRequestModels.ResumeRequest(3),key));
            var resumed=first.join();assertEquals(resumed.id(),second.join().id());
            assertNotEquals(old.id(),resumed.id());assertNotEquals(old.stage().id(),resumed.stage().id());
            assertEquals(old.id(),resumed.resumedFromRequestId());assertEquals(old.jobId(),resumed.jobId());assertEquals(old.videoId(),resumed.videoId());
            assertEquals(old.source(),resumed.source());assertEquals(old.instruction(),resumed.instruction());assertEquals(old.utterances(),resumed.utterances());
            assertEquals(3,resumed.budget().maxTextCalls());assertEquals(0,resumed.budget().textCallsReserved());assertEquals(0,resumed.stage().attempt());
            assertEquals("deepseek-flash",jdbc.queryForObject("SELECT text_model_id FROM clip_request WHERE id=?",String.class,resumed.id()));
            assertEquals(2,clips.list(f.job()).items().size());assertEquals(cancelled,clips.get(old.id()));assertEquals(before,jobs.get(f.job()));
            var newLease=claim(resumed);assertEquals(0,newLease.config().get("max_asr_submissions"));
            assertTrue(newLease.artifacts().stream().anyMatch(a->a.stageType().equals("ASR")));
            assertEquals("IDEMPOTENCY_CONFLICT",failure(()->clips.resume(old.id(),new ClipRequestModels.ResumeRequest(4),key)));
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(clips,"preferredTextModel","");}
    }

    @Test void readyPlansCannotBeCancelledOrResumedAndInvalidRoundsAreRejected() throws Exception {
        Fixture f=fixture();var request=ready(f);String key="resume-"+UUID.randomUUID();
        assertEquals("CLIP_REQUEST_NOT_CANCELLABLE",failure(()->clips.cancel(request.id())));
        assertEquals("CLIP_REQUEST_NOT_RESUMABLE",failure(()->clips.resume(request.id(),new ClipRequestModels.ResumeRequest(4),key)));
        assertEquals(request,clips.get(request.id()));var pending=create(f,4);
        assertEquals("CLIP_REQUEST_NOT_RESUMABLE",failure(()->clips.resume(pending.id(),null,key)));
        clips.cancel(pending.id());assertEquals("INVALID_CLIP_REQUEST",failure(()->clips.resume(pending.id(),new ClipRequestModels.ResumeRequest(25),key)));
        assertEquals("INVALID_CLIP_REQUEST",failure(()->clips.resume(pending.id(),new ClipRequestModels.ResumeRequest(0),key)));
        assertEquals("INVALID_CLIP_REQUEST",failure(()->clips.resume(pending.id(),null,"unsafe/key")));
    }

    @Test void failedRecoveryChecksSourceAgainAndDoesNotReuseAnOldAcceptedCreateKey() throws Exception {
        Fixture f=fixture();String createKey="ordinary-"+UUID.randomUUID();var old=clips.create(f.job(),new ClipRequestModels.CreateRequest("继续找原因",4),createKey);var l=claim(old);
        jobs.fail(l.stageId(),new FailRequest(l.attempt(),l.runToken(),"TEXT_RETRIES_EXHAUSTED",false));
        assertEquals("FAILED",clips.get(old.id()).status());
        assertEquals("CLIP_REQUEST_NOT_CANCELLABLE",failure(()->clips.cancel(old.id())));
        assertEquals("IDEMPOTENCY_CONFLICT",failure(()->clips.resume(old.id(),null,createKey)));
        jdbc.update("UPDATE clip_request SET transcript_hash=? WHERE id=?","f".repeat(64),old.id());
        assertEquals("CLIP_RESUME_SOURCE_CHANGED",failure(()->clips.resume(old.id(),null,"changed-"+UUID.randomUUID())));
        assertEquals(1,clips.list(f.job()).items().size());
        jdbc.update("UPDATE clip_request SET transcript_hash=? WHERE id=?",old.source().transcriptHash(),old.id());
        String manifest=jdbc.queryForObject("SELECT manifest_key FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type='ASR'",String.class,f.job());
        Files.writeString(root.resolve(manifest),"tampered");
        assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->clips.resume(old.id(),null,"tampered-"+UUID.randomUUID())));
        assertEquals(1,clips.list(f.job()).items().size());
    }

    @Test void recoveryHttpRoutesKeepLineageAndReplayTheAcceptedNewRequest() throws Exception {
        Fixture f=fixture();var old=create(f,4);
        var cancelled=send("POST","/api/clip-requests/"+old.id()+"/cancel",Map.of(),null,false);assertEquals(200,cancelled.statusCode());
        assertEquals("CANCELLED",JSON.readTree(cancelled.body()).path("status").asText());
        String path="/api/clip-requests/"+old.id()+"/resume",key="http-resume-"+UUID.randomUUID();
        var resumed=send("POST",path,Map.of("max_text_calls",5),key,false);assertEquals(200,resumed.statusCode());
        JsonNode result=JSON.readTree(resumed.body());assertEquals(old.id(),result.path("resumed_from_request_id").asText());assertEquals(5,result.path("budget").path("max_text_calls").asInt());
        assertEquals(result,JSON.readTree(send("POST",path,Map.of("max_text_calls",5),key,false).body()));
        assertEquals(400,send("POST",path,Map.of("max_text_calls",5),null,false).statusCode());
    }
    @Test void concurrentReservationsCannotOverspendLastClipSlot() throws Exception {
        Fixture f=fixture();var request=create(f,1);var lease=claim(request);
        var a=CompletableFuture.supplyAsync(()->reserveOutcome(lease,"a"));var b=CompletableFuture.supplyAsync(()->reserveOutcome(lease,"b"));
        var outcomes=List.of(a.join(),b.join());assertEquals(1,outcomes.stream().filter("RESERVED"::equals).count());assertEquals(1,outcomes.stream().filter("MODEL_BUDGET_EXHAUSTED"::equals).count());
        assertEquals(0,jobs.get(f.job()).budget().textCallsReserved());
    }
    @Test void planCompletionPublishesIndependentUnselectedRevisionAndOldCallbackIsIdempotent() throws Exception {
        Fixture f=fixture();var original=jobs.get(f.job());var request=create(f,6);var lease=claim(request);var result=publishPlan(f,request,lease,null);
        assertEquals("ACCEPTED",jobs.complete(lease.stageId(),result).outcome());assertEquals("ALREADY_APPLIED",jobs.complete(lease.stageId(),result).outcome());
        var ready=clips.get(request.id());assertEquals("PLAN_READY",ready.status());assertFalse(ready.plan().path("outputs").get(0).path("enabled").asBoolean());assertNotNull(ready.planHash());assertEquals(original,jobs.get(f.job()));
        assertEquals("CLIP_PLAN_EMPTY_SELECTION",failure(()->clips.confirm(request.id(),JSON.valueToTree(Map.of("revision",0,"plan_hash",ready.planHash())))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_revision WHERE request_id=?",Integer.class,request.id()));
    }
    @Test void invalidAutomaticPlanCannotPublishOrSelectForeignSource() throws Exception {
        Fixture f=fixture();var request=create(f,6);var lease=claim(request);
        var bad=publishPlan(f,request,lease,p->((ObjectNode)p).put("request_id",UUID.randomUUID().toString()));
        assertEquals("INVALID_CLIP_PLAN",failure(()->jobs.complete(lease.stageId(),bad)));assertEquals("RUNNING",clips.get(request.id()).status());assertNull(clips.get(request.id()).plan());
    }
    @Test void sourceIntervalsExclusionsAndGoalCoverageAreEnforced() throws Exception {
        Fixture f=fixture();var request=ready(f);JsonNode original=request.plan();
        List<java.util.function.Consumer<ObjectNode>> corruptions=List.of(
            p->((ObjectNode)p.path("outputs").get(0).path("parts").get(0)).put("start_ms",5),
            p->((ObjectNode)p.path("outputs").get(0).path("parts").get(0)).put("start_utterance_id","foreign"),
            p->((ObjectNode)p.path("outputs").get(0).path("excluded").get(0)).put("utterance_id","u1"),
            p->((ObjectNode)p.path("outputs").get(0)).put("status","NOT_FOUND"),
            p->((ObjectNode)p.path("outputs").get(1)).put("goal_id","cause"));
        for(var corrupt:corruptions){ObjectNode p=(ObjectNode)original.deepCopy();corrupt.accept(p);assertEquals("INVALID_CLIP_PLAN",failure(()->clips.save(request.id(),edit(request,p.path("outputs")))));}
        assertEquals(0,clips.get(request.id()).revision());
    }
    @Test void staleManualEditsAreRejectedAndConfirmedExportKeepsImmutablePlan() throws Exception {
        Fixture f=fixture();var request=ready(f);var selected=select(request);
        assertEquals("CLIP_PLAN_REVISION_CONFLICT",failure(()->clips.save(request.id(),edit(request,request.plan().path("outputs")))));
        clips.confirm(selected.id(),confirmation(selected));var batch=exports.createClipRequest(selected.id(),exportInput(selected));
        assertEquals(selected.id(),batch.clipRequestId());assertEquals(2,batch.clips().size());
        var cause=batch.clips().stream().filter(c->c.segmentId().equals("out-cause")).findFirst().orElseThrow();assertEquals(2,cause.parts().size());assertEquals(2000,cause.durationMs());assertEquals(0,cause.startMs());assertEquals(3000,cause.endMs());
        assertEquals(batch.id(),exports.createClipRequest(selected.id(),exportInput(selected)).id());assertTrue(exports.list(f.job(),100,0).items().isEmpty());assertEquals(1,exports.listClipRequest(selected.id()).items().size());
        ObjectNode outputs=(ObjectNode)selected.plan().deepCopy();((ObjectNode)outputs.path("outputs").get(0)).put("title","new title");clips.save(selected.id(),edit(selected,outputs.path("outputs")));
        assertEquals(batch.snapshotHash(),exports.get(batch.id()).snapshotHash());assertEquals(batch.id(),exports.createClipRequest(selected.id(),exportInput(selected)).id());
        app.close();startContext();assertEquals(batch.snapshotHash(),exports.get(batch.id()).snapshotHash());assertEquals(2,clips.get(selected.id()).revision());
    }
    @Test void partialGoalsRemainExplicitAndOnlyFoundSelectionCanExport() throws Exception {
        Fixture f=fixture();var r=create(f,4);var lease=claim(r);var completion=publishPlan(f,r,lease,p->{ObjectNode out=(ObjectNode)p.path("outputs").get(1);out.put("status","NOT_FOUND").put("reason","not discussed");out.putArray("parts");out.putArray("evidence_utterance_ids");});jobs.complete(lease.stageId(),completion);
        var ready=clips.get(r.id());ObjectNode selected=(ObjectNode)ready.plan().deepCopy();((ObjectNode)selected.path("outputs").get(0)).put("enabled",true);var saved=clips.save(r.id(),edit(ready,selected.path("outputs")));clips.confirm(r.id(),confirmation(saved));assertEquals(1,exports.createClipRequest(r.id(),exportInput(saved)).clips().size());
        ((ObjectNode)selected.path("outputs").get(1)).put("enabled",true);assertEquals("INVALID_CLIP_PLAN",failure(()->clips.save(r.id(),edit(saved,selected.path("outputs")))));
    }
    @Test void httpPlanEditsCannotRelabelUnresolvedOutputsOrReplaceTheirIdentities() throws Exception {
        for(String state:List.of("NOT_FOUND","NEEDS_REVIEW")){
            Fixture f=fixture();var ready=readyWithStatus(f,state);var original=jobs.get(f.job());
            ObjectNode promoted=(ObjectNode)ready.plan().deepCopy();ObjectNode output=(ObjectNode)promoted.path("outputs").get(1);
            output.put("status","FOUND").put("enabled",true);
            // NOT_FOUND has no parts: provide legitimate source references so shape checks alone cannot catch the promotion.
            output.putArray("parts").addObject().put("start_utterance_id","u4").put("end_utterance_id","u4").put("start_ms",3000).put("end_ms",4000);
            output.putArray("evidence_utterance_ids").add("u4");
            var relabelled=send("PUT","/api/clip-requests/"+ready.id()+"/plan",edit(ready,promoted.path("outputs")),null,false);
            assertEquals(400,relabelled.statusCode());assertEquals("INVALID_CLIP_PLAN",JSON.readTree(relabelled.body()).path("error_code").asText());
            ObjectNode renamed=(ObjectNode)ready.plan().deepCopy();((ObjectNode)renamed.path("outputs").get(1)).put("output_id","forged-output");
            assertEquals(400,send("PUT","/api/clip-requests/"+ready.id()+"/plan",edit(ready,renamed.path("outputs")),null,false).statusCode());
            // Swapping two existing IDs is no substitute for retaining each goal/output association.
            ((ObjectNode)renamed.path("outputs").get(0)).put("output_id","out-method");((ObjectNode)renamed.path("outputs").get(1)).put("output_id","out-cause");
            assertEquals(400,send("PUT","/api/clip-requests/"+ready.id()+"/plan",edit(ready,renamed.path("outputs")),null,false).statusCode());
            assertEquals(ready,clips.get(ready.id()));assertEquals(original,jobs.get(f.job()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_revision WHERE request_id=?",Integer.class,ready.id()));
        }
    }
    @Test void foundReviewEditsKeepValidPartExclusionAndSelectionChanges() throws Exception {
        Fixture f=fixture();var ready=ready(f);var original=jobs.get(f.job());ObjectNode edited=(ObjectNode)ready.plan().deepCopy();
        ObjectNode exchangedGoals=(ObjectNode)ready.plan().deepCopy();((ObjectNode)exchangedGoals.path("outputs").get(0)).put("goal_id","method");((ObjectNode)exchangedGoals.path("outputs").get(1)).put("goal_id","cause");
        assertEquals(400,send("PUT","/api/clip-requests/"+ready.id()+"/plan",edit(ready,exchangedGoals.path("outputs")),null,false).statusCode());
        ObjectNode cause=(ObjectNode)edited.path("outputs").get(0);cause.put("title","人工核对后的原因").put("enabled",true);
        cause.putArray("parts").addObject().put("start_utterance_id","u1").put("end_utterance_id","u1").put("start_ms",0).put("end_ms",1000);
        cause.putArray("evidence_utterance_ids").add("u1");cause.withArray("excluded").addObject().put("utterance_id","u3").put("reason","人工移除重复解释");
        var saved=send("PUT","/api/clip-requests/"+ready.id()+"/plan",edit(ready,edited.path("outputs")),null,false);assertEquals(200,saved.statusCode());
        var selected=clips.get(ready.id());assertEquals(1,selected.revision());assertEquals("FOUND",selected.plan().path("outputs").get(0).path("status").asText());
        assertEquals(200,send("POST","/api/clip-requests/"+ready.id()+"/confirm",confirmation(selected),null,false).statusCode());
        var exported=send("POST","/api/clip-requests/"+ready.id()+"/exports",exportInput(selected),null,false);assertEquals(200,exported.statusCode());
        JsonNode clip=JSON.readTree(exported.body()).path("clips").get(0);assertEquals("人工核对后的原因",clip.path("title").asText());assertEquals(1,clip.path("parts").size());assertEquals(1000,clip.path("duration_ms").asLong());
        assertEquals(original,jobs.get(f.job()));assertEquals(ready.budget(),clips.get(ready.id()).budget());
        assertEquals(ready.plan(),JSON.readTree(jdbc.queryForObject("SELECT plan_json FROM clip_plan_revision WHERE request_id=? AND revision=0",String.class,ready.id())));
    }
    @Test void legacyRelabelledDraftCannotBeConfirmedOrUsedAsTheNextReviewAuthority() throws Exception {
        Fixture f=fixture();var ready=readyWithStatus(f,"NEEDS_REVIEW");var original=jobs.get(f.job());
        ObjectNode promoted=(ObjectNode)ready.plan().deepCopy();((ObjectNode)promoted.path("outputs").get(1)).put("status","FOUND").put("enabled",true);
        var legacy=persistLegacyRevision(ready,promoted,false);String originalLegacy=jdbc.queryForObject("SELECT plan_json FROM clip_plan_revision WHERE request_id=? AND revision=1",String.class,ready.id());
        assertEquals(200,send("GET","/api/clip-requests/"+ready.id(),null,null,false).statusCode());
        var rejected=send("POST","/api/clip-requests/"+ready.id()+"/confirm",confirmation(legacy),null,false);assertEquals(400,rejected.statusCode());assertEquals("INVALID_CLIP_PLAN",JSON.readTree(rejected.body()).path("error_code").asText());
        assertEquals("INVALID_CLIP_PLAN",failure(()->clips.save(legacy.id(),edit(legacy,legacy.plan().path("outputs")))));
        assertTrue(clips.get(ready.id()).confirmedRevisions().isEmpty());assertEquals(legacy,clips.get(ready.id()));
        // Correcting the submitted review back to the automatic status is allowed; the old malformed revision remains untouched.
        ObjectNode corrected=(ObjectNode)ready.plan().deepCopy();((ObjectNode)corrected.path("outputs").get(0)).put("enabled",true);
        var repaired=clips.save(ready.id(),edit(legacy,corrected.path("outputs")));clips.confirm(repaired.id(),confirmation(repaired));assertEquals(1,exports.createClipRequest(repaired.id(),exportInput(repaired)).clips().size());
        assertEquals(originalLegacy,jdbc.queryForObject("SELECT plan_json FROM clip_plan_revision WHERE request_id=? AND revision=1",String.class,ready.id()));
        assertEquals(original,jobs.get(f.job()));assertEquals(ready.budget(),clips.get(ready.id()).budget());
    }
    @Test void alreadyConfirmedLegacyIdentityChangesStayReadableButCannotCreateNewExports() throws Exception {
        Fixture f=fixture();var selected=select(readyWithStatus(f,"FOUND"));clips.confirm(selected.id(),confirmation(selected));var existing=exports.createClipRequest(selected.id(),exportInput(selected));
        ObjectNode changed=(ObjectNode)selected.plan().deepCopy();((ObjectNode)changed.path("outputs").get(1)).put("output_id","historical-forged-output");
        var legacy=persistLegacyRevision(selected,changed,true);var before=clips.get(legacy.id());
        assertEquals(200,send("GET","/api/clip-requests/"+legacy.id(),null,null,false).statusCode());
        assertTrue(before.confirmedRevisions().contains(legacy.revision()));
        assertEquals("INVALID_CLIP_PLAN",failure(()->clips.confirm(legacy.id(),confirmation(legacy))));
        assertEquals("INVALID_CLIP_PLAN",failure(()->exports.createClipRequest(legacy.id(),exportInput(legacy))));
        assertEquals(before,clips.get(legacy.id()));assertEquals(existing,exports.get(existing.id()));assertEquals(existing.id(),exports.createClipRequest(selected.id(),exportInput(selected)).id());
    }
    @Test void missingOrTamperedAutomaticRevisionCannotBeReplacedByTheCurrentDraft() throws Exception {
        Fixture f=fixture();var selected=select(ready(f));String original=jdbc.queryForObject("SELECT plan_json FROM clip_plan_revision WHERE request_id=? AND revision=0",String.class,selected.id());
        jdbc.update("UPDATE clip_plan_revision SET plan_hash=? WHERE request_id=? AND revision=0","0".repeat(64),selected.id());
        assertEquals("CLIP_PLAN_SOURCE_INVALID",failure(()->clips.confirm(selected.id(),confirmation(selected))));
        assertEquals("CLIP_PLAN_SOURCE_INVALID",failure(()->clips.save(selected.id(),edit(selected,selected.plan().path("outputs")))));
        assertEquals(original,jdbc.queryForObject("SELECT plan_json FROM clip_plan_revision WHERE request_id=? AND revision=0",String.class,selected.id()));
        jdbc.update("DELETE FROM clip_plan_revision WHERE request_id=? AND revision=0",selected.id());
        assertEquals("CLIP_PLAN_SOURCE_INVALID",failure(()->clips.confirm(selected.id(),confirmation(selected))));
        assertEquals(selected,clips.get(selected.id()));
    }
    @Test void reclaimedClipAttemptRejectsOldResultWithoutChangingSourceJob() throws Exception {
        Fixture f=fixture();var before=jobs.get(f.job());var r=create(f,4);var first=claim(r);var result=publishPlan(f,r,first,null);jdbc.update("UPDATE job_stage SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",first.stageId());jobs.recoverExpired();var second=claim(clips.get(r.id()));assertEquals(2,second.attempt());assertEquals("STALE_ATTEMPT",failure(()->jobs.complete(first.stageId(),result)));assertEquals(before,jobs.get(f.job()));
    }
    @Test void composedManifestChecksExactPartsAndSummedOutputDuration() throws Exception {
        Fixture f=fixture();var r=select(ready(f));clips.confirm(r.id(),confirmation(r));var batch=exports.createClipRequest(r.id(),exportInput(r));var clip=batch.clips().stream().filter(c->c.segmentId().equals("out-cause")).findFirst().orElseThrow();var lease=jobs.claim(new ClaimRequest(clip.stageId(),"intent-export-test",UUID.randomUUID().toString()));
        var config=(Map<?,?>)lease.config().get("export");assertEquals(2000L,config.get("duration_ms"));assertEquals(r.id(),config.get("clip_request_id"));
        CompleteRequest completion=publishExport(lease,config,false);assertEquals("ACCEPTED",jobs.complete(lease.stageId(),completion).outcome());assertEquals("SUCCEEDED",exports.get(batch.id()).clips().stream().filter(c->c.id().equals(clip.id())).findFirst().orElseThrow().status());
        assertTrue(exports.manifest(batch.id()).clips().stream().filter(c->c.clipId().equals(clip.id())).findFirst().orElseThrow().parts().size()==2);
        var other=exports.createClipRequest(r.id(),exportInput(r));assertEquals(batch.id(),other.id());
        JsonNode downloaded=JSON.readTree(send("GET","/api/exports/"+batch.id()+"/manifest",null,null,false).body());assertEquals(r.id(),downloaded.path("clip_request_id").asText());
    }
    @Test void videoPackagePreservesSelectedComposedSourceAndIgnoresCorruptUnselectedSuccessfulClip()throws Exception{
        Fixture f=fixture();var r=select(ready(f));clips.confirm(r.id(),confirmation(r));var batch=exports.createClipRequest(r.id(),exportInput(r));
        for(var clip:batch.clips()){
            var lease=jobs.claim(new ClaimRequest(clip.stageId(),"video-package-synthetic",UUID.randomUUID().toString()));
            assertEquals("ACCEPTED",jobs.complete(lease.stageId(),publishExport(lease,(Map<?,?>)lease.config().get("export"),false)).outcome());
        }
        var selected=batch.clips().stream().filter(clip->clip.segmentId().equals("out-cause")).findFirst().orElseThrow();
        var unselected=batch.clips().stream().filter(clip->clip.segmentId().equals("out-method")).findFirst().orElseThrow();
        byte[] selectedBytes=Files.readAllBytes(exports.content(batch.id(),selected.id()).path());
        Path unselectedFile=exports.content(batch.id(),unselected.id()).path();
        Files.write(unselectedFile,new byte[]{3,2,1,0});
        jdbc.update("UPDATE export_clip SET source_parts_json=?,topic_title=? WHERE id=?","[]","损坏的未选片元数据",unselected.id());
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?",String.class,unselected.stageId()));

        var admitted=exports.videoPackagePlan(batch.id(),List.of(selected.id()));
        assertEquals(r.id(),admitted.clipRequestId());assertEquals(1,admitted.clips().size());
        assertEquals(2,admitted.clips().getFirst().parts().size());assertEquals(2000,admitted.clips().getFirst().durationMs());
        assertEquals(3000,admitted.clips().getFirst().endMs()-admitted.clips().getFirst().startMs());
        assertEquals(4,exports.videoPackageContent(admitted,admitted.clips().getFirst()).sizeBytes());
        var delivery=app.getBean(ExportVideoDeliveryService.class);
        try(var ready=delivery.bundle(batch.id(),List.of(selected.id()));ZipFile zip=new ZipFile(ready.path().toFile(),StandardCharsets.UTF_8)){
            assertEquals(Set.of("clips/001-"+selected.id()+".mp4","README.txt","manifest.json"),new HashSet<>(zip.stream().map(entry->entry.getName()).toList()));
            assertArrayEquals(selectedBytes,zip.getInputStream(zip.getEntry("clips/001-"+selected.id()+".mp4")).readAllBytes());
            JsonNode manifest=JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
            assertEquals("export_video_package",manifest.path("data_kind").asText());assertEquals(r.id(),manifest.path("clip_request_id").asText());
            assertEquals(f.job(),manifest.path("job_id").asText());assertEquals(f.video(),manifest.path("video_id").asText());assertEquals(f.hash(),manifest.path("source_video_sha256").asText());
            assertEquals(batch.snapshotHash(),manifest.path("snapshot_hash").asText());assertEquals(r.revision(),manifest.path("approved_revision").longValue());
            assertEquals("h264-aac-v1",manifest.path("profile").asText());assertEquals("CLEAN",manifest.path("render_mode").asText());
            JsonNode clip=manifest.path("clips").get(0);assertEquals(1,manifest.path("clips").size());assertEquals(selected.id(),clip.path("clip_id").asText());
            assertEquals("out-cause",clip.path("segment_id").asText());assertEquals(0,clip.path("start_ms").longValue());assertEquals(3000,clip.path("end_ms").longValue());assertEquals(2000,clip.path("duration_ms").longValue());
            assertEquals(JSON.readTree("[{\"start_ms\":0,\"end_ms\":1000},{\"start_ms\":2000,\"end_ms\":3000}]"),clip.path("parts"));
            assertEquals(sha(selectedBytes),clip.path("file").path("sha256").asText());assertFalse(clip.has("caption_revision"));
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM caption_document WHERE export_id=?",Integer.class,batch.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.job()));
        jdbc.update("UPDATE export_clip SET source_parts_json=? WHERE id=?","[{\"start_ms\":0,\"end_ms\":1000}]",selected.id());
        assertEquals("EXPORT_SNAPSHOT_INVALID",failure(()->exports.videoPackagePlan(batch.id(),List.of(selected.id()))));
        assertEquals("EXPORT_SNAPSHOT_INVALID",failure(()->exports.videoPackageContent(admitted,admitted.clips().getFirst())));
        assertEquals("EXPORT_SNAPSHOT_INVALID",failure(()->delivery.bundle(batch.id(),List.of(selected.id()))));
        try(var temporary=Files.list(root.resolve("delivery-tmp"))){assertEquals(0,temporary.count());}
    }
    @Test void changedPartOrderOrOmittedExclusionCannotMasqueradeAsFrozenExport() throws Exception {
        Fixture f=fixture();var r=select(ready(f));clips.confirm(r.id(),confirmation(r));var batch=exports.createClipRequest(r.id(),exportInput(r));var clip=batch.clips().stream().filter(c->c.segmentId().equals("out-cause")).findFirst().orElseThrow();var lease=jobs.claim(new ClaimRequest(clip.stageId(),"intent-export-test",UUID.randomUUID().toString()));var config=(Map<?,?>)lease.config().get("export");var broken=publishExport(lease,config,true);assertEquals("INVALID_MANIFEST",failure(()->jobs.complete(lease.stageId(),broken)));
    }
    @Test void httpApiIsIndependentAndPublicPlanDoesNotLeakPrivateExecutionPaths() throws Exception {
        Fixture f=fixture();String path="/api/jobs/"+f.job()+"/clip-requests";
        var response=send("POST",path,Map.of("instruction","find causes","max_text_calls",4),"http-"+UUID.randomUUID(),false);assertEquals(200,response.statusCode());JsonNode r=JSON.readTree(response.body());assertEquals(1,JSON.readTree(send("GET",path,null,null,false).body()).path("items").size());
        String body=new String(send("GET","/api/clip-requests/"+r.path("id").asText(),null,null,false).body(),StandardCharsets.UTF_8);for(String secret:List.of("run_token","manifest_key","transcript_key","source_json","jobs/",KEY))assertFalse(body.contains(secret));
    }
    @Test void textProgressIsFencedPublicAndDoesNotTouchCompletedSource() throws Exception {
        Fixture f=fixture();var r=create(f,6);var l=claim(r);var original=jobs.get(f.job());
        var progress=Map.of("state","RETRY_WAIT","attempt_number",2,"max_attempts",3,"provider","primary","model_id","qwen-plus","timeout_seconds",90,"elapsed_seconds",90,"retry_in_seconds",2);
        String path="/internal/stages/"+l.stageId()+"/text-progress";var body=Map.of("attempt",l.attempt(),"run_token",l.runToken(),"progress",progress);
        assertEquals(401,send("POST",path,body,null,false).statusCode());
        assertEquals(200,send("POST",path,body,null,true).statusCode());
        var after=clips.get(r.id());assertEquals("RETRY_WAIT",after.stage().textProgress().get("state"));assertTrue(after.stateVersion()>r.stateVersion());assertEquals(original,jobs.get(f.job()));
        var duplicate=send("POST",path,body,null,true);assertEquals("ALREADY_APPLIED",JSON.readTree(duplicate.body()).path("outcome").asText());
        var unsafe=new HashMap<String,Object>(progress);unsafe.put("api_key","secret-not-public");
        assertEquals(400,send("POST",path,Map.of("attempt",l.attempt(),"run_token",l.runToken(),"progress",unsafe),null,true).statusCode());
        assertEquals(409,send("POST",path,Map.of("attempt",l.attempt(),"run_token","stale-token-0000000000","progress",progress),null,true).statusCode());
        jdbc.update("UPDATE job_stage SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",l.stageId());jobs.recoverExpired();
        claim(clips.get(r.id()));assertNull(clips.get(r.id()).stage().textProgress());
        assertEquals(409,send("POST",path,body,null,true).statusCode());assertEquals(original,jobs.get(f.job()));
    }
    @Test void clipFallbackModelsAreFrozenAndAllowedWithoutMutatingSource() throws Exception {
        Fixture f=fixture();var original=jobs.get(f.job());
        org.springframework.test.util.ReflectionTestUtils.setField(clips,"configuredFallbackModels","deepseek-flash");
        ClipRequestModels.Snapshot r;
        try{r=create(f,2);}finally{org.springframework.test.util.ReflectionTestUtils.setField(clips,"configuredFallbackModels","");}
        var l=claim(r);assertEquals(List.of("deepseek-flash"),l.config().get("text_fallback_models"));
        var reservation=calls.reserve(new ReserveRequest(l.stageId(),l.attempt(),l.runToken(),"fallback-clip-"+UUID.randomUUID(),"a".repeat(64),"fallback-clipping","deepseek-flash","TEXT"));
        assertEquals("RESERVED",reservation.outcome());assertEquals(1,clips.get(r.id()).budget().textCallsReserved());assertEquals(original,jobs.get(f.job()));
    }

    @Test void answerAnchorsAreResolvedFromVerifiedSameSourceAndIdempotentlyFrozen()throws Exception{
        var f=fixture();var answer=readyAnswer(f,"VIDEO");var before=jobs.get(f.job());String key="answer-anchor-"+UUID.randomUUID();
        var input=new ClipRequestModels.CreateRequest("剪出所选原句完整解释",4,selection(answer,"u1","u3"));var created=clips.create(f.job(),input,key);
        assertEquals("ANCHOR_CLIP",created.stage().stageType());assertEquals(answer.id(),created.sourceAnswer().path("turn_id").asText());assertEquals(answer.sessionId(),created.sourceAnswer().path("session_id").asText());assertEquals(JSON.valueToTree(List.of("u1","u3")),created.sourceAnswer().path("utterance_ids"));assertEquals(JSON.valueToTree(List.of("u1","u2","u3","u4")),created.sourceAnswer().path("context_utterance_ids"));assertEquals(before,jobs.get(f.job()));
        assertEquals(created.id(),clips.create(f.job(),input,key).id());assertEquals("IDEMPOTENCY_CONFLICT",failure(()->clips.create(f.job(),new ClipRequestModels.CreateRequest(input.instruction(),4),key)));
        String original=jdbc.queryForObject("SELECT result_json FROM agent_turn WHERE id=?",String.class,answer.id());jdbc.update("UPDATE agent_turn SET result_json=? WHERE id=?",original.replace("缓存原因答复","被篡改答复"),answer.id());
        assertEquals(created,clips.get(created.id()));assertEquals(created.id(),clips.create(f.job(),input,key).id());assertEquals("ANSWER_SOURCE_INVALID",failure(()->anchor(f,answer,4,"u1")));
        JsonNode publicJson=JSON.readTree(send("GET","/api/clip-requests/"+created.id(),null,null,false).body());assertEquals(created.sourceAnswer(),publicJson.path("source_answer"));for(String privateField:List.of("run_token","answer_result","source_answer_json","source_answer_hash","transcript_key"))assertFalse(publicJson.toString().contains(privateField));
    }
    @Test void answerSelectorsRejectBrowserTimesForeignCitationsAndNonVideoTurnsBeforeWork()throws Exception{
        var f=fixture();var answer=readyAnswer(f,"VIDEO");var other=fixture();
        assertEquals("ANSWER_SOURCE_UNAVAILABLE",failure(()->anchor(other,answer,4,"u1")));assertEquals("INVALID_ANSWER_SOURCE",failure(()->anchor(f,answer,4,"u4")));assertEquals("INVALID_ANSWER_SOURCE",failure(()->anchor(f,answer,4,"u3","u1")));assertEquals("INVALID_ANSWER_SOURCE",failure(()->anchor(f,answer,4,"u1","u1")));
        ObjectNode browser=(ObjectNode)selection(answer,"u1");browser.put("start_ms",2500);assertEquals(400,send("POST","/api/jobs/"+f.job()+"/clip-requests",Map.of("instruction","browser cannot choose time","source_answer",browser),"browser-"+UUID.randomUUID(),false).statusCode());
        var general=readyAnswer(f,"GENERAL");assertEquals("ANSWER_SOURCE_UNAVAILABLE",failure(()->anchor(f,general,4,"u1")));
        var agents=app.getBean(AgentService.class);var session=agents.createSession(f.job(),JSON.createObjectNode(),"queued-session-"+UUID.randomUUID());var queued=agents.createTurn(session.id(),JSON.valueToTree(Map.of("question","还未完成")),"queued-turn-"+UUID.randomUUID());assertEquals("ANSWER_SOURCE_UNAVAILABLE",failure(()->anchor(f,queued,4,"u1")));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM clip_request WHERE job_id IN (?,?)",Integer.class,f.job(),other.job()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id IN (?,?)",Integer.class,f.job(),other.job()));
    }
    @Test void anchorCapabilityAndSharedTextSlotsRejectLegacyExecutors()throws Exception{
        var f=fixture();var answer=readyAnswer(f,"VIDEO");var first=anchor(f,answer,4,"u1");var capacity=app.getBean(WorkerCapacityService.class);String old="old-anchor-"+UUID.randomUUID(),oldSession=UUID.randomUUID().toString();ownWorkers.add(old);capacity.register(new WorkerModels.Registration(old,oldSession,List.of("SEGMENT"),3,null));
        assertEquals("WORKER_CAPABILITY_MISMATCH",failure(()->jobs.claim(new ClaimRequest(first.stage().id(),old,UUID.randomUUID().toString(),null,oldSession))));assertTrue(jobs.ready("SEGMENT",100,f.job()).stream().noneMatch(stage->stage.id().equals(first.stage().id())));assertTrue(jobs.ready("ANCHOR_CLIP",100,f.job()).stream().anyMatch(stage->stage.id().equals(first.stage().id())));
        String worker="capable-anchor-"+UUID.randomUUID(),session=UUID.randomUUID().toString();ownWorkers.add(worker);capacity.register(new WorkerModels.Registration(worker,session,List.of("ANCHOR_CLIP","SEGMENT"),3,null));var lease=jobs.claim(new ClaimRequest(first.stage().id(),worker,UUID.randomUUID().toString(),null,session));assertEquals("CLAIMED",lease.outcome());assertEquals(first.sourceAnswer(),JSON.valueToTree(((Map<?,?>)lease.config().get("clip_request")).get("answer_source")));assertEquals(0,lease.config().get("max_asr_submissions"));
        var ordinary=create(f,4);assertEquals("CLAIMED",jobs.claim(new ClaimRequest(ordinary.stage().id(),worker,UUID.randomUUID().toString(),null,session)).outcome());var waiting=anchor(f,answer,4,"u3");assertEquals("NO_CAPACITY",jobs.claim(new ClaimRequest(waiting.stage().id(),worker,UUID.randomUUID().toString(),null,session)).outcome());
    }
    @Test void anchorCompletionReviewConfirmationAndExportKeepEverySelectedSourceSentence()throws Exception{
        var f=fixture();var answer=readyAnswer(f,"VIDEO");var created=anchor(f,answer,4,"u1","u3");var lease=claim(created);var original=jobs.get(f.job());
        var omitted=publishAnchorPlan(f,created,lease,"FOUND",plan->parts((ObjectNode)plan.path("outputs").get(0),"u1","u1"));assertEquals("INVALID_CLIP_PLAN",failure(()->jobs.complete(lease.stageId(),omitted)));
        assertEquals("ACCEPTED",jobs.complete(lease.stageId(),publishAnchorPlan(f,created,lease,"FOUND",null)).outcome());var ready=clips.get(created.id());assertEquals("PLAN_READY",ready.status());
        ObjectNode cut=(ObjectNode)ready.plan().deepCopy();parts((ObjectNode)cut.path("outputs").get(0),"u3","u3");assertEquals("INVALID_CLIP_PLAN",failure(()->clips.save(ready.id(),edit(ready,cut.path("outputs")))));
        var selected=select(ready);clips.confirm(selected.id(),confirmation(selected));assertEquals(1,exports.createClipRequest(selected.id(),exportInput(selected)).clips().size());assertEquals(original,jobs.get(f.job()));
        ObjectNode legacy=(ObjectNode)selected.plan().deepCopy();parts((ObjectNode)legacy.path("outputs").get(0),"u1","u1");var tampered=persistLegacyRevision(selected,legacy,true);assertEquals("INVALID_CLIP_PLAN",failure(()->clips.confirm(tampered.id(),confirmation(tampered))));assertEquals("INVALID_CLIP_PLAN",failure(()->exports.createClipRequest(tampered.id(),exportInput(tampered))));
    }
    @Test void anchoredMissingAndNeedsReviewRemainHonestAndCannotBePromoted()throws Exception{
        for(String state:List.of("NOT_FOUND","NEEDS_REVIEW")){var f=fixture();var answer=readyAnswer(f,"VIDEO");var created=anchor(f,answer,4,"u1","u3");var lease=claim(created);jobs.complete(lease.stageId(),publishAnchorPlan(f,created,lease,state,null));var ready=clips.get(created.id());assertEquals(state,ready.plan().path("outputs").get(0).path("status").asText());assertEquals("CLIP_PLAN_EMPTY_SELECTION",failure(()->clips.confirm(ready.id(),confirmation(ready))));ObjectNode promoted=(ObjectNode)ready.plan().deepCopy();((ObjectNode)promoted.path("outputs").get(0)).put("status","FOUND").put("enabled",true);assertEquals("INVALID_CLIP_PLAN",failure(()->clips.save(ready.id(),edit(ready,promoted.path("outputs")))));}
    }
    @Test void anchorCancellationUnknownAccountingAndRecoveryKeepFrozenAnswerAndIndependentBudget()throws Exception{
        var f=fixture();var answer=readyAnswer(f,"VIDEO");var created=anchor(f,answer,1,"u1","u3");var lease=claim(created);var completion=publishAnchorPlan(f,created,lease,"FOUND",null);var original=jobs.get(f.job());String request=UUID.randomUUID().toString().replace("-","").repeat(2);
        var reservation=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),request,"b".repeat(64),"anchor-unknown","qwen-plus","TEXT");assertEquals("RESERVED",calls.reserve(reservation).outcome());assertEquals("EXISTING",calls.reserve(reservation).outcome());calls.update(request,new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of()));clips.cancel(created.id());assertEquals("STALE_ATTEMPT",failure(()->jobs.complete(lease.stageId(),completion)));assertEquals("UPDATED",calls.update(request,new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"UNKNOWN_OUTCOME",null,Map.of())).outcome());assertEquals("STALE_ATTEMPT",failure(()->calls.reserve(reservation)));
        String key="resume-anchor-"+UUID.randomUUID();var resumed=clips.resume(created.id(),new ClipRequestModels.ResumeRequest(2),key);assertEquals(created.sourceAnswer(),resumed.sourceAnswer());assertEquals("ANCHOR_CLIP",resumed.stage().stageType());assertEquals(0,resumed.budget().textCallsReserved());assertEquals(resumed.id(),clips.resume(created.id(),new ClipRequestModels.ResumeRequest(2),key).id());var next=claim(resumed);assertEquals("MODEL_KIND_CONFLICT",failure(()->calls.reserve(new ReserveRequest(next.stageId(),next.attempt(),next.runToken(),"a".repeat(64),"a".repeat(64),"bad-asr","paraformer-v2","ASR"))));assertEquals(original,jobs.get(f.job()));
    }
    private ClipRequestModels.Snapshot anchor(Fixture f,AgentModels.Turn answer,int budget,String...ids){return clips.create(f.job(),new ClipRequestModels.CreateRequest("剪出这些回答原句的完整解释",budget,selection(answer,ids)),"anchor-"+UUID.randomUUID());}
    private static JsonNode selection(AgentModels.Turn turn,String...ids){return JSON.valueToTree(Map.of("turn_id",turn.id(),"utterance_ids",List.of(ids)));}
    private AgentModels.Turn readyAnswer(Fixture f,String scope)throws Exception{
        var agents=app.getBean(AgentService.class);var session=agents.createSession(f.job(),JSON.createObjectNode(),"anchor-session-"+UUID.randomUUID());var turn=agents.createTurn(session.id(),JSON.valueToTree(Map.of("question","缓存失效原因是什么","max_text_calls",2)),"anchor-answer-"+UUID.randomUUID());var lease=jobs.claim(new ClaimRequest(turn.stage().id(),"anchor-answer-worker",UUID.randomUUID().toString()));
        ObjectNode answer=JSON.createObjectNode().put("schema_version","1.0").put("session_id",turn.sessionId()).put("turn_id",turn.id()).put("job_id",f.job()).put("video_id",f.video()).put("source_hash",f.hash()).put("transcript_hash",session.source().transcriptHash()).put("question",turn.question()).put("answer","缓存原因答复，需回看原句。").put("answer_scope",scope);var cited=answer.putArray("citations");for(int index:List.of(0,2)){var citation=cited.addObject();for(String key:List.of("utterance_id","start_ms","end_ms","text"))citation.set(key,f.utterances().get(index).path(key));}var tool=answer.putArray("tool_calls").addObject().put("step",1).put("tool_name","read_context");tool.putObject("arguments").put("utterance_id","u1").put("before",0).put("after",3);tool.putArray("result_utterance_ids").add("u1").add("u2").add("u3").add("u4");answer.putObject("provenance").put("text_model_id","qwen-plus").put("prompt_version","video-agent-v1").put("retrieval_mode","none");answer.putNull("suggested_clip_instruction");
        byte[] bytes=JSON.writeValueAsBytes(answer);String prefix=prefix(lease);write(prefix+"agent-answer.json",bytes);var manifest=manifest(f.job(),f.video(),lease.stageId(),"SEGMENT",lease.runToken(),f.hash());manifest.putArray("artifacts").addObject().put("kind","agent_answer").put("key",prefix+"agent-answer.json").put("sha256",sha(bytes));byte[] data=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",data);jobs.complete(lease.stageId(),new CompleteRequest(lease.attempt(),lease.runToken(),prefix+"manifest.json",sha(data)));return agents.getTurn(turn.id());
    }
    private CompleteRequest publishAnchorPlan(Fixture f,ClipRequestModels.Snapshot request,ClaimResult lease,String state,java.util.function.Consumer<ObjectNode> mutate)throws Exception{
        return publishPlan(f,request,lease,plan->{plan.putArray("goals").addObject().put("goal_id","answer-source").put("description",request.instruction());plan.putObject("constraints").put("keep_explanation_complete",true).putArray("exclude");var output=plan.putArray("outputs").addObject().put("output_id","anchored-output").put("goal_id","answer-source").put("title","引用原句解释").put("status",state).put("reason","合成模型fixture，不代表语义质量").put("enabled",false);output.putArray("excluded");output.putArray("parts");output.putArray("evidence_utterance_ids");if(!state.equals("NOT_FOUND"))parts(output,"u1","u3");var owner=jdbc.queryForMap("SELECT * FROM clip_request WHERE id=?",request.id());plan.putObject("provenance").put("text_model_id","qwen-plus").put("prompt_version","anchor-clips-v1").put("retrieval_mode","anchor_context").put("index_hash",AnswerClipSource.indexHash(owner,request.sourceAnswer()));if(mutate!=null)mutate.accept(plan);});
    }
    @Test void manualReviewHttpCreatesImmutableProofWithoutPromotingTheAiPlan()throws Exception{
        var f=fixture();var r=readyWithStatus(f,"NEEDS_REVIEW");var before=jobs.get(f.job());String key=UUID.randomUUID().toString();
        JsonNode beforeJson=JSON.readTree(send("GET","/api/clip-requests/"+r.id(),null,null,false).body());assertTrue(beforeJson.has("manual_review"));assertTrue(beforeJson.path("manual_review").isNull());
        JsonNode body=manualInput(r,"out-method");var response=send("POST","/api/clip-requests/"+r.id()+"/manual-reviews",body,key,false);assertEquals(200,response.statusCode());JsonNode review=JSON.readTree(response.body());
        assertEquals(Set.of("schema_version","id","request_id","job_id","video_id","source_hash","transcript_hash","duration_ms","automatic_plan_hash","base_revision","base_plan_hash","revision","plan_hash","outputs","created_at","review_hash"),review.propertyNames());
        assertEquals(Set.of("output_id","goal_id","output_hash","note","accept_sentence_boundaries"),review.path("outputs").get(0).propertyNames());
        assertEquals(key,review.path("id").asText());assertEquals(1,review.path("revision").asLong());assertEquals(r.planHash(),review.path("plan_hash").asText());assertEquals(r.planHash(),review.path("base_plan_hash").asText());assertEquals(r.planHash(),review.path("automatic_plan_hash").asText());
        ObjectNode withoutHash=(ObjectNode)review.deepCopy();withoutHash.remove("review_hash");assertEquals(ClipManualReview.digest(withoutHash),review.path("review_hash").asText());assertTrue(review.path("created_at").asText().matches(".*\\.[0-9]{6}Z"));
        assertEquals(ClipManualReview.digest(r.plan().path("outputs").get(1)),review.path("outputs").get(0).path("output_hash").asText());
        var fresh=clips.get(r.id());assertEquals(review,fresh.manualReview());assertEquals(r.plan(),fresh.plan());assertEquals("NEEDS_REVIEW",fresh.plan().path("outputs").get(1).path("status").asText());assertFalse(fresh.plan().path("outputs").get(1).path("enabled").asBoolean());
        assertEquals(review,JSON.readTree(send("GET","/api/clip-requests/"+r.id()+"/manual-reviews/"+key,null,null,false).body()));
        assertEquals(review,clips.createManualReview(r.id(),body,key));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_revision WHERE request_id=?",Integer.class,r.id()));assertEquals(before,jobs.get(f.job()));assertEquals(r.budget(),fresh.budget());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.job()));assertEquals(r.plan(),JSON.readTree(jdbc.queryForObject("SELECT plan_json FROM clip_plan_revision WHERE request_id=? AND revision=0",String.class,r.id())));
    }
    @Test void manualReviewRequiresCanonicalBoundedExplicitInputAndNeverPartiallyWrites()throws Exception{
        var r=readyWithStatus(fixture(),"NEEDS_REVIEW");ObjectNode valid=(ObjectNode)manualInput(r,"out-method");
        List<ObjectNode> invalid=new ArrayList<>();invalid.add(valid.deepCopy().put("revision",1));invalid.add(valid.deepCopy().put("base_revision",-1));invalid.add(valid.deepCopy().put("plan_hash","A".repeat(64)));
        for(String note:List.of(""," ","x".repeat(1001),"😀".repeat(501))){ObjectNode item=valid.deepCopy();((ObjectNode)item.path("outputs").get(0)).put("note",note);invalid.add(item);}
        for(Object ack:List.of(false,"true")){ObjectNode item=valid.deepCopy();((ObjectNode)item.path("outputs").get(0)).set("accept_sentence_boundaries",JSON.valueToTree(ack));invalid.add(item);}
        ObjectNode trusted=valid.deepCopy();((ObjectNode)trusted.path("outputs").get(0)).put("output_hash","a".repeat(64));invalid.add(trusted);
        ObjectNode duplicate=valid.deepCopy();duplicate.withArray("outputs").add(duplicate.path("outputs").get(0).deepCopy());invalid.add(duplicate);
        ObjectNode tooMany=valid.deepCopy();var values=tooMany.putArray("outputs");for(int i=0;i<7;i++)values.addObject().put("output_id","out-"+i).put("note","review").put("accept_sentence_boundaries",true);invalid.add(tooMany);
        for(JsonNode body:invalid)assertEquals("INVALID_CLIP_MANUAL_REVIEW",failure(()->clips.createManualReview(r.id(),body,UUID.randomUUID().toString())));
        for(String key:List.of("bad",UUID.randomUUID().toString().toUpperCase(Locale.ROOT),"1-1-1-1-1"))assertEquals("INVALID_CLIP_MANUAL_REVIEW",failure(()->clips.createManualReview(r.id(),valid,key)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_manual_review WHERE request_id=?",Integer.class,r.id()));assertEquals(0,clips.get(r.id()).revision());
        ((ObjectNode)valid.path("outputs").get(0)).put("note","😀".repeat(500));assertEquals(1000,clips.createManualReview(r.id(),valid,UUID.randomUUID().toString()).path("outputs").get(0).path("note").asText().length());
    }
    @Test void manualBatchRejectsFoundMissingEmptyEvidenceAndWrongOrderAtomically()throws Exception{
        for(String status:List.of("FOUND","NOT_FOUND")){var r=readyWithStatus(fixture(),status);assertEquals("CLIP_MANUAL_REVIEW_NOT_ALLOWED",failure(()->clips.createManualReview(r.id(),manualInput(r,"out-method"),UUID.randomUUID().toString())));assertEquals(0,clips.get(r.id()).revision());}
        for(boolean emptyParts:List.of(false,true)){var r=readyManual(fixture(),p->{var out=(ObjectNode)p.path("outputs").get(1);out.putArray("evidence_utterance_ids");if(emptyParts)out.putArray("parts");});assertEquals("CLIP_MANUAL_REVIEW_NOT_ALLOWED",failure(()->clips.createManualReview(r.id(),manualInput(r,"out-method"),UUID.randomUUID().toString())));}
        var both=readyManual(fixture(),null);assertEquals("CLIP_MANUAL_REVIEW_NOT_ALLOWED",failure(()->clips.createManualReview(both.id(),manualInput(both,"out-method","out-cause"),UUID.randomUUID().toString())));
        assertEquals("CLIP_MANUAL_REVIEW_NOT_ALLOWED",failure(()->clips.createManualReview(both.id(),manualInput(both,"out-cause","missing-output"),UUID.randomUUID().toString())));assertEquals(0,clips.get(both.id()).revision());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_manual_review WHERE request_id=?",Integer.class,both.id()));
    }
    @Test void manualBatchesReplaceClearAndOrdinarySavesInvalidateWithoutChangingOldProofs()throws Exception{
        var r=readyManual(fixture(),null);JsonNode first=clips.createManualReview(r.id(),manualInput(r,"out-cause","out-method"),UUID.randomUUID().toString());var one=clips.get(r.id());assertEquals(2,one.manualReview().path("outputs").size());
        clips.createManualReview(r.id(),manualInput(one,"out-method"),UUID.randomUUID().toString());var two=clips.get(r.id());assertEquals(1,two.manualReview().path("outputs").size());assertEquals("out-method",two.manualReview().path("outputs").get(0).path("output_id").asText());
        clips.createManualReview(r.id(),manualInput(two),UUID.randomUUID().toString());var cleared=clips.get(r.id());assertEquals(3,cleared.revision());assertTrue(cleared.manualReview().path("outputs").isEmpty());assertEquals("CLIP_PLAN_EMPTY_SELECTION",failure(()->clips.confirm(cleared.id(),manualConfirmation(cleared))));
        clips.createManualReview(r.id(),manualInput(cleared,"out-cause"),UUID.randomUUID().toString());var human=clips.get(r.id());clips.confirm(human.id(),manualConfirmation(human));
        var ordinary=clips.save(r.id(),edit(human,human.plan().path("outputs")));assertEquals(human.planHash(),ordinary.planHash());assertNull(ordinary.manualReview());assertEquals("CLIP_MANUAL_REVIEW_MISMATCH",failure(()->clips.confirm(ordinary.id(),JSON.createObjectNode().put("revision",ordinary.revision()).put("plan_hash",ordinary.planHash()).put("manual_review_hash",human.manualReview().path("review_hash").asText()))));
        assertEquals("CLIP_PLAN_EMPTY_SELECTION",failure(()->clips.confirm(ordinary.id(),confirmation(ordinary))));assertEquals(first,clips.getManualReview(r.id(),first.path("id").asText()));assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_manual_review WHERE request_id=?",Integer.class,r.id()));
    }
    @Test void manualIdempotencyIsImmutableAcrossNewHeadsAndDifferentOwners()throws Exception{
        var r=readyWithStatus(fixture(),"NEEDS_REVIEW");String key=UUID.randomUUID().toString();var body=manualInput(r,"out-method");var proof=clips.createManualReview(r.id(),body,key);var human=clips.get(r.id());clips.save(r.id(),edit(human,human.plan().path("outputs")));
        assertEquals(proof,clips.createManualReview(r.id(),body,key));assertEquals(2,clips.get(r.id()).revision());ObjectNode altered=(ObjectNode)body.deepCopy();((ObjectNode)altered.path("outputs").get(0)).put("note","different");assertEquals("IDEMPOTENCY_CONFLICT",failure(()->clips.createManualReview(r.id(),altered,key)));
        var other=readyWithStatus(fixture(),"NEEDS_REVIEW");assertEquals("IDEMPOTENCY_CONFLICT",failure(()->clips.createManualReview(other.id(),manualInput(other,"out-method"),key)));
        var missing=send("GET","/api/clip-requests/"+other.id()+"/manual-reviews/"+key,null,null,false);assertEquals(404,missing.statusCode());assertEquals("CLIP_MANUAL_REVIEW_NOT_FOUND",JSON.readTree(missing.body()).path("error_code").asText());
        assertEquals("CLIP_PLAN_REVISION_CONFLICT",failure(()->clips.createManualReview(r.id(),body,UUID.randomUUID().toString())));
    }
    @Test void concurrentManualSubmissionsSerializeBaseAndReplayOneKeyExactly()throws Exception{
        var r=readyWithStatus(fixture(),"NEEDS_REVIEW");var body=manualInput(r,"out-method");String key=UUID.randomUUID().toString();
        var a=CompletableFuture.supplyAsync(()->clips.createManualReview(r.id(),body,key));var b=CompletableFuture.supplyAsync(()->clips.createManualReview(r.id(),body,key));assertEquals(a.join(),b.join());assertEquals(1,clips.get(r.id()).revision());
        var latest=clips.get(r.id());var next=manualInput(latest,"out-method");List<String> outcomes=Collections.synchronizedList(new ArrayList<>());
        var left=CompletableFuture.runAsync(()->{try{clips.createManualReview(r.id(),next,UUID.randomUUID().toString());outcomes.add("ok");}catch(JobException e){outcomes.add(e.getCode());}});var right=CompletableFuture.runAsync(()->{try{clips.createManualReview(r.id(),next,UUID.randomUUID().toString());outcomes.add("ok");}catch(JobException e){outcomes.add(e.getCode());}});CompletableFuture.allOf(left,right).join();assertEquals(Set.of("ok","CLIP_PLAN_REVISION_CONFLICT"),new HashSet<>(outcomes));assertEquals(2,clips.get(r.id()).revision());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_manual_review WHERE request_id=?",Integer.class,r.id()));
    }
    @Test void manualConfirmationAndExportsRequireExactProofAndNeverRewriteOldConfirmation()throws Exception{
        var r=readyWithStatus(fixture(),"NEEDS_REVIEW");clips.createManualReview(r.id(),manualInput(r,"out-method"),UUID.randomUUID().toString());var human=clips.get(r.id());
        assertEquals("CLIP_MANUAL_REVIEW_MISMATCH",failure(()->clips.confirm(human.id(),confirmation(human))));assertEquals("CLIP_MANUAL_REVIEW_MISMATCH",failure(()->exports.createClipRequest(human.id(),exportInput(human))));
        ObjectNode wrong=(ObjectNode)manualConfirmation(human);wrong.put("manual_review_hash","0".repeat(64));assertEquals("CLIP_MANUAL_REVIEW_MISMATCH",failure(()->clips.confirm(human.id(),wrong)));
        jdbc.update("INSERT INTO clip_plan_confirmation (request_id,revision,plan_hash) VALUES (?,?,?)",human.id(),human.revision(),human.planHash());assertTrue(clips.get(human.id()).confirmedRevisions().isEmpty());assertEquals("CLIP_PLAN_CONFIRMATION_CONFLICT",failure(()->clips.confirm(human.id(),manualConfirmation(human))));assertEquals("EXPORT_REQUIRES_CONFIRMATION",failure(()->exports.createClipRequest(human.id(),manualExport(human))));
        jdbc.update("DELETE FROM clip_plan_confirmation WHERE request_id=? AND revision=?",human.id(),human.revision());assertEquals(List.of(human.revision()),clips.confirm(human.id(),manualConfirmation(human)).confirmedRevisions());assertEquals(1,exports.createClipRequest(human.id(),manualExport(human)).clips().size());
        var ordinary=ready(fixture());ObjectNode forged=(ObjectNode)confirmation(ordinary);forged.put("manual_review_hash",human.manualReview().path("review_hash").asText());assertEquals("CLIP_MANUAL_REVIEW_MISMATCH",failure(()->clips.confirm(ordinary.id(),forged)));
        assertEquals("INVALID_CLIP_PLAN",failure(()->clips.confirm(ordinary.id(),((ObjectNode)confirmation(ordinary)).putNull("manual_review_hash"))));assertEquals("INVALID_CLIP_PLAN",failure(()->exports.createClipRequest(ordinary.id(),((ObjectNode)exportInput(ordinary)).putNull("manual_review_hash"))));
    }
    @Test void missingManualProofAndMismatchedMarkersCannotBecomeOrdinaryVersions()throws Exception{
        for(String mutation:List.of("missing-row","wrong-id","wrong-hash","cleared-markers","row-id","source-snapshot","review-json")){
            var r=readyWithStatus(fixture(),"NEEDS_REVIEW");JsonNode proof=clips.createManualReview(r.id(),manualInput(r,"out-method"),UUID.randomUUID().toString());var human=clips.get(r.id());String key=proof.path("id").asText();
            switch(mutation){
                case "missing-row"->jdbc.update("DELETE FROM clip_plan_manual_review WHERE id=?",key);
                case "wrong-id"->jdbc.update("UPDATE clip_plan_revision SET manual_review_id=? WHERE request_id=? AND revision=1",UUID.randomUUID().toString(),r.id());
                case "wrong-hash"->jdbc.update("UPDATE clip_plan_revision SET manual_review_hash=? WHERE request_id=? AND revision=1","0".repeat(64),r.id());
                case "cleared-markers"->jdbc.update("UPDATE clip_plan_revision SET manual_review_id=NULL,manual_review_hash=NULL WHERE request_id=? AND revision=1",r.id());
                case "row-id"->jdbc.update("UPDATE clip_plan_manual_review SET id=? WHERE id=?",UUID.randomUUID().toString(),key);
                case "source-snapshot"->jdbc.update("UPDATE clip_plan_manual_review SET source_snapshot_hash=? WHERE id=?","0".repeat(64),key);
                case "review-json"->{ObjectNode changed=(ObjectNode)proof.deepCopy();((ObjectNode)changed.path("outputs").get(0)).put("note","tampered");jdbc.update("UPDATE clip_plan_manual_review SET review_json=? WHERE id=?",ClipRequestService.canonicalJson(changed),key);}
            }
            assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->clips.get(r.id())),mutation);assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->clips.confirm(r.id(),manualConfirmation(human))),mutation);assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->clips.save(r.id(),edit(human,human.plan().path("outputs")))),mutation);
            if(mutation.equals("missing-row"))assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->clips.getManualReview(r.id(),key)));
        }
    }
    @Test void manualMarkerCheckRejectsBothHalfNullCombinationsInMysql()throws Exception{
        var r=ready(fixture());for(boolean idOnly:List.of(false,true)){
            var error=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE clip_plan_revision SET "+(idOnly?"manual_review_id":"manual_review_hash")+"=? WHERE request_id=?",idOnly?UUID.randomUUID().toString():"a".repeat(64),r.id()));assertInstanceOf(java.sql.SQLException.class,error.getMostSpecificCause());assertEquals(3819,((java.sql.SQLException)error.getMostSpecificCause()).getErrorCode());
        }assertNull(clips.get(r.id()).manualReview());
    }
    @Test void orphanHistoricalReviewIsExcludedFromConfirmedRevisions()throws Exception{
        var r=readyWithStatus(fixture(),"NEEDS_REVIEW");clips.createManualReview(r.id(),manualInput(r,"out-method"),UUID.randomUUID().toString());var human=clips.get(r.id());clips.confirm(human.id(),manualConfirmation(human));var plain=clips.save(human.id(),edit(human,human.plan().path("outputs")));
        jdbc.update("UPDATE clip_plan_revision SET manual_review_id=NULL,manual_review_hash=NULL WHERE request_id=? AND revision=1",r.id());jdbc.update("UPDATE clip_plan_confirmation SET manual_review_hash=NULL WHERE request_id=? AND revision=1",r.id());assertTrue(clips.get(plain.id()).confirmedRevisions().isEmpty());
    }
    @Test void manualReviewRechecksActualAsrSavedPlanAndOriginalAuthority()throws Exception{
        for(String mutation:List.of("source-json","source-artifact","source-label","plan-hash","r0")){
            var f=fixture();var r=readyWithStatus(f,"NEEDS_REVIEW");
            switch(mutation){
                case "source-json"->{var source=JSON.readTree(jdbc.queryForObject("SELECT source_json FROM clip_request WHERE id=?",String.class,r.id()));((ObjectNode)source.path("utterances").get(3)).put("text","falsified source text");jdbc.update("UPDATE clip_request SET source_json=? WHERE id=?",ClipRequestService.canonicalJson(source),r.id());}
                case "source-artifact"->{String path=jdbc.queryForObject("SELECT manifest_key FROM job_stage WHERE job_id=? AND stage_type='ASR'",String.class,f.job());Files.writeString(root.resolve(path),"tampered");}
                case "source-label"->jdbc.update("UPDATE clip_request SET transcript_hash=? WHERE id=?","0".repeat(64),r.id());
                case "plan-hash"->jdbc.update("UPDATE clip_plan_revision SET plan_hash=? WHERE request_id=? AND revision=0","0".repeat(64),r.id());
                case "r0"->jdbc.update("DELETE FROM clip_plan_revision WHERE request_id=? AND revision=0",r.id());
            }
            assertThrows(JobException.class,()->clips.createManualReview(r.id(),manualInput(r,"out-method"),UUID.randomUUID().toString()),mutation);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM clip_plan_manual_review WHERE request_id=?",Integer.class,r.id()));
        }
    }
    @Test void manualBatchesCannotBypassExcludedSentencesOrAnswerAnchorCoverage()throws Exception{
        var r=readyManual(fixture(),null);ObjectNode invalid=(ObjectNode)r.plan().deepCopy();parts((ObjectNode)invalid.path("outputs").get(0),"u1","u3");persistLegacyRevision(r,invalid,false);var bad=clips.get(r.id());assertEquals("INVALID_CLIP_PLAN",failure(()->clips.createManualReview(r.id(),manualInput(bad,"out-cause"),UUID.randomUUID().toString())));
        var f=fixture();var answer=readyAnswer(f,"VIDEO");var created=anchor(f,answer,4,"u1","u3");var lease=claim(created);jobs.complete(lease.stageId(),publishAnchorPlan(f,created,lease,"NEEDS_REVIEW",null));var anchored=clips.get(created.id());clips.createManualReview(anchored.id(),manualInput(anchored,"anchored-output"),UUID.randomUUID().toString());var reviewed=clips.get(anchored.id());clips.confirm(reviewed.id(),manualConfirmation(reviewed));assertEquals(1,exports.createClipRequest(reviewed.id(),manualExport(reviewed)).clips().size());
        ObjectNode omitted=(ObjectNode)reviewed.plan().deepCopy();parts((ObjectNode)omitted.path("outputs").get(0),"u3","u3");assertEquals("INVALID_CLIP_PLAN",failure(()->clips.save(reviewed.id(),edit(reviewed,omitted.path("outputs")))));
    }
    @Test void manualAndFoundUnionFreezesProofWhileOrdinaryHistoricalSnapshotsStayByteCompatible()throws Exception{
        var f=fixture();var r=readyWithStatus(f,"NEEDS_REVIEW");ObjectNode selected=(ObjectNode)r.plan().deepCopy();((ObjectNode)selected.path("outputs").get(0)).put("enabled",true);var found=clips.save(r.id(),edit(r,selected.path("outputs")));clips.confirm(found.id(),confirmation(found));var old=exports.createClipRequest(found.id(),exportInput(found));String oldFrozen=jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?",String.class,old.id());assertFalse(JSON.readTree(oldFrozen).has("manual_review"));
        clips.createManualReview(found.id(),manualInput(found,"out-method"),UUID.randomUUID().toString());var human=clips.get(r.id());clips.confirm(human.id(),manualConfirmation(human));var batch=exports.createClipRequest(human.id(),manualExport(human));assertEquals(2,batch.clips().size());assertNotEquals(old.snapshotHash(),batch.snapshotHash());JsonNode frozen=JSON.readTree(jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?",String.class,batch.id()));assertEquals(human.manualReview(),frozen.path("manual_review"));assertEquals(human.manualReview().path("review_hash").asText(),frozen.path("manual_review_hash").asText());
        assertEquals(old,exports.get(old.id()));assertEquals(old.id(),exports.createClipRequest(found.id(),exportInput(found)).id());assertEquals(oldFrozen,jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?",String.class,old.id()));assertEquals(batch.id(),exports.createClipRequest(human.id(),manualExport(human)).id());
    }
    @Test void manualExportStrictWorkerClaimAndEveryConsumptionRecheckImmutableProof()throws Exception{
        var r=readyManual(fixture(),null);clips.createManualReview(r.id(),manualInput(r,"out-cause"),UUID.randomUUID().toString());var human=clips.get(r.id());clips.confirm(human.id(),manualConfirmation(human));var batch=exports.createClipRequest(human.id(),manualExport(human));var clip=batch.clips().getFirst();var lease=jobs.claim(new ClaimRequest(clip.stageId(),"manual-export-test",UUID.randomUUID().toString()));var config=(Map<?,?>)lease.config().get("export");
        assertEquals(Set.of("export_id","clip_id","approved_revision","snapshot_hash","segment_id","start_ms","end_ms","encoding_profile","parts","duration_ms","clip_request_id"),config.keySet());CompleteRequest completion=publishExport(lease,config,false);
        String key=human.manualReview().path("id").asText(),original=jdbc.queryForObject("SELECT review_json FROM clip_plan_manual_review WHERE id=?",String.class,key);ObjectNode changed=(ObjectNode)human.manualReview().deepCopy();changed.put("review_hash","0".repeat(64));jdbc.update("UPDATE clip_plan_manual_review SET review_json=? WHERE id=?",ClipRequestService.canonicalJson(changed),key);
        assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->jobs.complete(lease.stageId(),completion)));assertNull(jdbc.queryForObject("SELECT content_hash FROM export_clip WHERE id=?",String.class,clip.id()));jdbc.update("UPDATE clip_plan_manual_review SET review_json=? WHERE id=?",original,key);assertEquals("ACCEPTED",jobs.complete(lease.stageId(),completion).outcome());assertEquals(4,exports.content(batch.id(),clip.id()).sizeBytes());
        var captions=app.getBean(io.livecut.captions.CaptionService.class);var caption=captions.initialize(batch.id(),clip.id());assertEquals(2,caption.cues().size());assertTrue(caption.confirmedRevisions().isEmpty());
        jdbc.update("DELETE FROM clip_plan_manual_review WHERE id=?",key);assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->exports.get(batch.id())));assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->exports.content(batch.id(),clip.id())));assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->captions.get(batch.id(),clip.id(),null)));
    }
    @Test void videoPackageRequiresOriginalManualSelectionProofAfterMediaHasCompleted()throws Exception{
        var r=readyManual(fixture(),null);clips.createManualReview(r.id(),manualInput(r,"out-cause"),UUID.randomUUID().toString());
        var human=clips.get(r.id());clips.confirm(human.id(),manualConfirmation(human));var batch=exports.createClipRequest(human.id(),manualExport(human));var clip=batch.clips().getFirst();
        var lease=jobs.claim(new ClaimRequest(clip.stageId(),"manual-video-package-synthetic",UUID.randomUUID().toString()));
        assertEquals("ACCEPTED",jobs.complete(lease.stageId(),publishExport(lease,(Map<?,?>)lease.config().get("export"),false)).outcome());
        var admitted=exports.videoPackagePlan(batch.id(),List.of(clip.id()));assertEquals(2000,admitted.clips().getFirst().durationMs());
        var delivery=app.getBean(ExportVideoDeliveryService.class);
        try(var ready=delivery.bundle(batch.id(),List.of(clip.id()))){assertTrue(Files.exists(ready.path()));}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM caption_document WHERE export_id=?",Integer.class,batch.id()));
        jdbc.update("DELETE FROM clip_plan_manual_review WHERE id=?",human.manualReview().path("id").asText());
        assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->exports.videoPackagePlan(batch.id(),List.of(clip.id()))));
        assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->exports.videoPackageContent(admitted,admitted.clips().getFirst())));
        assertEquals("CLIP_MANUAL_REVIEW_INVALID",failure(()->delivery.bundle(batch.id(),List.of(clip.id()))));
        try(var temporary=Files.list(root.resolve("delivery-tmp"))){assertEquals(0,temporary.count());}
    }
    @Test void manualExportCannotClaimFrozenWrongPartsOrDetachedProofEvenWithRehashedJson()throws Exception{
        for(String mutation:List.of("proof-hash","frozen-parts","persisted-parts","missing-proof")){
            var r=readyManual(fixture(),null);clips.createManualReview(r.id(),manualInput(r,"out-cause"),UUID.randomUUID().toString());var human=clips.get(r.id());clips.confirm(human.id(),manualConfirmation(human));var batch=exports.createClipRequest(human.id(),manualExport(human));var clip=batch.clips().getFirst();
            if(mutation.equals("persisted-parts"))jdbc.update("UPDATE export_clip SET source_parts_json=? WHERE id=?","[{\"start_ms\":0,\"end_ms\":1000}]",clip.id());
            else if(mutation.equals("missing-proof"))jdbc.update("DELETE FROM clip_plan_manual_review WHERE id=?",human.manualReview().path("id").asText());
            else{ObjectNode frozen=(ObjectNode)JSON.readTree(jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?",String.class,batch.id()));if(mutation.equals("proof-hash"))frozen.put("manual_review_hash","0".repeat(64));else ((ObjectNode)frozen.path("clips").get(0).path("parts").get(0)).put("end_ms",500);String serialized=ClipRequestService.canonicalJson(frozen),hash=ClipRequestService.sha(serialized);jdbc.update("UPDATE export_job SET frozen_json=?,snapshot_hash=?,fingerprint=? WHERE id=?",serialized,hash,hash,batch.id());}
            assertThrows(JobException.class,()->jobs.claim(new ClaimRequest(clip.stageId(),"manual-corrupt-claim",UUID.randomUUID().toString())),mutation);assertEquals("READY",jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?",String.class,clip.stageId()));assertNull(jdbc.queryForObject("SELECT run_token FROM job_stage WHERE id=?",String.class,clip.stageId()));
        }
    }
    @Test void manualCaptionedDerivativesRecheckSourceProofOnClaimCompletionAndContent()throws Exception{
        var r=readyManual(fixture(),null);clips.createManualReview(r.id(),manualInput(r,"out-cause"),UUID.randomUUID().toString());var human=clips.get(r.id());clips.confirm(human.id(),manualConfirmation(human));var clean=exports.createClipRequest(human.id(),manualExport(human));var clip=clean.clips().getFirst();
        var cleanLease=jobs.claim(new ClaimRequest(clip.stageId(),"manual-caption-clean",UUID.randomUUID().toString()));jobs.complete(cleanLease.stageId(),publishExport(cleanLease,(Map<?,?>)cleanLease.config().get("export"),false));
        var captions=app.getBean(io.livecut.captions.CaptionService.class);captions.initialize(clean.id(),clip.id());var confirmed=captions.confirm(clean.id(),clip.id(),JSON.createObjectNode().put("revision",0).put("acknowledge_review",true));
        var renders=app.getBean(io.livecut.captions.CaptionRenderService.class);var render=renders.create(clean.id(),clip.id(),"manual-captioned-test",JSON.createObjectNode().put("caption_revision",0).put("caption_content_hash",confirmed.contentHash()).put("style_preset","WHITE_OUTLINE_V1").put("frame_preset","SOURCE"));
        String stage=jdbc.queryForObject("SELECT stage_id FROM export_clip WHERE export_id=?",String.class,render.id()),reviewId=human.manualReview().path("id").asText();String original=jdbc.queryForObject("SELECT review_json FROM clip_plan_manual_review WHERE id=?",String.class,reviewId);
        ObjectNode corrupt=(ObjectNode)human.manualReview().deepCopy();((ObjectNode)corrupt.path("outputs").get(0)).put("note","corrupt source human decision");String tampered=ClipRequestService.canonicalJson(corrupt);
        jdbc.update("UPDATE clip_plan_manual_review SET review_json=? WHERE id=?",tampered,reviewId);assertEquals("EXPORT_SNAPSHOT_INVALID",failure(()->jobs.claim(new ClaimRequest(stage,"manual-captioned-test",UUID.randomUUID().toString()))));assertEquals("READY",jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?",String.class,stage));
        jdbc.update("UPDATE clip_plan_manual_review SET review_json=? WHERE id=?",original,reviewId);var lease=jobs.claim(new ClaimRequest(stage,"manual-captioned-test",UUID.randomUUID().toString()));var config=(Map<?,?>)lease.config().get("export");assertFalse(config.containsKey("manual_review"));assertFalse(config.containsKey("manual_review_hash"));CompleteRequest completion=publishCaptioned(lease,config);
        jdbc.update("UPDATE clip_plan_manual_review SET review_json=? WHERE id=?",tampered,reviewId);assertEquals("EXPORT_SNAPSHOT_INVALID",failure(()->jobs.complete(stage,completion)));assertNull(jdbc.queryForObject("SELECT content_hash FROM export_clip WHERE export_id=?",String.class,render.id()));
        jdbc.update("UPDATE clip_plan_manual_review SET review_json=? WHERE id=?",original,reviewId);assertEquals("ACCEPTED",jobs.complete(stage,completion).outcome());assertEquals("SUCCEEDED",renders.get(render.id()).status());String derivedClip=jdbc.queryForObject("SELECT id FROM export_clip WHERE export_id=?",String.class,render.id());assertEquals(4,exports.content(render.id(),derivedClip).sizeBytes());
        jdbc.update("UPDATE clip_plan_manual_review SET review_json=? WHERE id=?",tampered,reviewId);assertEquals("EXPORT_SNAPSHOT_INVALID",failure(()->exports.content(render.id(),derivedClip)));assertEquals("EXPORT_SNAPSHOT_INVALID",failure(()->renders.get(render.id())));assertEquals("EXPORT_SNAPSHOT_INVALID",failure(()->exports.get(render.id())));
    }
    private ClipRequestModels.Snapshot readyManual(Fixture f,java.util.function.Consumer<ObjectNode> mutate)throws Exception{var r=create(f,6);var lease=claim(r);jobs.complete(lease.stageId(),publishPlan(f,r,lease,p->{for(JsonNode output:p.path("outputs"))((ObjectNode)output).put("status","NEEDS_REVIEW");if(mutate!=null)mutate.accept(p);}));return clips.get(r.id());}
    private static JsonNode manualInput(ClipRequestModels.Snapshot r,String...ids){ObjectNode body=JSON.createObjectNode().put("base_revision",r.revision()).put("plan_hash",r.planHash());var outputs=body.putArray("outputs");for(String id:ids)outputs.addObject().put("output_id",id).put("note","已核对当前原句范围，接受句级边界及可能保留的混合内容").put("accept_sentence_boundaries",true);return body;}
    private static JsonNode manualConfirmation(ClipRequestModels.Snapshot r){return ((ObjectNode)confirmation(r)).put("manual_review_hash",r.manualReview().path("review_hash").asText());}
    private static JsonNode manualExport(ClipRequestModels.Snapshot r){return ((ObjectNode)exportInput(r)).put("manual_review_hash",r.manualReview().path("review_hash").asText());}
    private static void parts(ObjectNode output,String first,String last){int start=Integer.parseInt(first.substring(1)),end=Integer.parseInt(last.substring(1));output.putArray("parts").addObject().put("start_utterance_id",first).put("end_utterance_id",last).put("start_ms",(start-1)*1000).put("end_ms",end*1000);output.putArray("evidence_utterance_ids").add(first);}
    private ClipRequestModels.Snapshot create(Fixture f,int budget){return clips.create(f.job(),new ClipRequestModels.CreateRequest("分别找缓存失效原因和解决方法，跳过关注引导",budget),"intent-"+UUID.randomUUID());}
    private ClaimResult claim(ClipRequestModels.Snapshot request){return jobs.claim(new ClaimRequest(request.stage().id(),"intent-test-worker",UUID.randomUUID().toString()));}
    private String reserveOutcome(ClaimResult l,String letter){try{return calls.reserve(new ReserveRequest(l.stageId(),l.attempt(),l.runToken(),letter.repeat(64),letter.repeat(64),"op-"+letter,"qwen-plus","TEXT")).outcome();}catch(VideoApiException e){return e.getCode();}}
    private ClipRequestModels.Snapshot ready(Fixture f)throws Exception{var r=create(f,6);var l=claim(r);jobs.complete(l.stageId(),publishPlan(f,r,l,null));return clips.get(r.id());}
    private ClipRequestModels.Snapshot readyWithStatus(Fixture f,String state)throws Exception{var r=create(f,6);var l=claim(r);jobs.complete(l.stageId(),publishPlan(f,r,l,p->{ObjectNode output=(ObjectNode)p.path("outputs").get(1);output.put("status",state);if(state.equals("NOT_FOUND")){output.putArray("parts");output.putArray("evidence_utterance_ids");}}));return clips.get(r.id());}
    /** Simulates rows written by the older API only within this test's synthetic request. */
    private ClipRequestModels.Snapshot persistLegacyRevision(ClipRequestModels.Snapshot source,JsonNode plan,boolean confirmed){
        long revision=source.revision()+1;String serialized=ClipRequestService.canonicalJson(plan),hash=ClipRequestService.sha(serialized);
        jdbc.update("INSERT INTO clip_plan_revision (request_id,revision,plan_hash,plan_json) VALUES (?,?,?,?)",source.id(),revision,hash,serialized);
        jdbc.update("UPDATE clip_request SET latest_revision=?,state_version=state_version+1 WHERE id=?",revision,source.id());
        if(confirmed)jdbc.update("INSERT INTO clip_plan_confirmation (request_id,revision,plan_hash) VALUES (?,?,?)",source.id(),revision,hash);
        return clips.get(source.id());
    }
    private ClipRequestModels.Snapshot select(ClipRequestModels.Snapshot request){ObjectNode plan=(ObjectNode)request.plan().deepCopy();for(JsonNode o:plan.path("outputs"))((ObjectNode)o).put("enabled",true);return clips.save(request.id(),edit(request,plan.path("outputs")));}
    private static JsonNode edit(ClipRequestModels.Snapshot r,JsonNode outputs){return JSON.createObjectNode().put("base_revision",r.revision()).set("outputs",outputs);}
    private static JsonNode confirmation(ClipRequestModels.Snapshot r){return JSON.valueToTree(Map.of("revision",r.revision(),"plan_hash",r.planHash()));}
    private static JsonNode exportInput(ClipRequestModels.Snapshot r){return JSON.valueToTree(Map.of("revision",r.revision(),"plan_hash",r.planHash(),"profile","mp4-h264-aac-v1"));}
    private static String failure(Runnable action){RuntimeException e=assertThrows(RuntimeException.class,action::run);if(e instanceof JobException job)return job.getCode();if(e instanceof VideoApiException api)return api.getCode();throw new AssertionError("Expected a controlled API exception",e);}
    private Fixture fixture()throws Exception{
        byte[] header=ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();var video=app.getBean(VideoService.class).upload(new MockMultipartFile("file","intent-synthetic.mp4","video/mp4",header));ownVideos.add(video.id());
        var job=jobs.create(new CreateJobRequest(video.id(),Map.of("analysis_mode","topics","visual_provider","none","max_text_calls",0,"max_asr_submissions",0)),"intent-source-"+UUID.randomUUID());ownJobs.add(job.id());
        var utterances=JSON.createArrayNode();for(int i=1;i<=4;i++)utterances.addObject().put("utterance_id","u"+i).put("start_ms",(i-1)*1000).put("end_ms",i*1000).put("text",i==2?"关注点赞":i==4?"只追加历史消息可以复用缓存":"修改前缀使缓存失效").put("source_chunk_id","c0");
        ObjectNode result=JSON.createObjectNode().put("schema_version","1.0").put("analysis_mode","topics").put("data_kind","model_result").put("job_id",job.id()).put("video_id",video.id()).put("analysis_generation",1).put("duration_ms",4000).put("transcript_version","synthetic").put("pipeline_version","synthetic").put("review_status","DRAFT");result.set("utterances",utterances);result.putArray("frames");result.putObject("provenance").put("asr_model_id","paraformer-v2").put("text_model_id","qwen-plus").put("prompt_version","synthetic").put("config_hash","a".repeat(64));var topic=result.putArray("segments").addObject().put("segment_id","topic-1").put("title","缓存").put("summary","合成缓存示例").put("start_ms",0).put("end_ms",4000).put("start_utterance_id","u1").put("end_utterance_id","u4");topic.putArray("keywords").add("缓存");topic.putArray("audio_evidence_utterance_ids").add("u1").add("u2").add("u3").add("u4");
        for(var s:job.stages()){String token=UUID.randomUUID().toString();String prefix="jobs/"+job.id()+"/stages/"+s.id()+"/attempts/1/"+token+"/";Map<String,byte[]> files=new LinkedHashMap<>();if(s.stageType().equals("ASR"))files.put("transcript",JSON.writeValueAsBytes(utterances));else if(s.stageType().equals("MEDIA")){files.put("audio",new byte[]{1,2,3});files.put("media_info",JSON.writeValueAsBytes(Map.of("duration_ms",4000)));}else files.put("analysis_result",JSON.writeValueAsBytes(result));ObjectNode manifest=manifest(job.id(),video.id(),s.id(),s.stageType(),token,video.contentHash());var a=manifest.putArray("artifacts");for(var file:files.entrySet()){String key=prefix+file.getKey()+".json";write(key,file.getValue());a.addObject().put("kind",file.getKey()).put("key",key).put("sha256",sha(file.getValue()));}byte[] bytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",bytes);jdbc.update("UPDATE job_stage SET status='SUCCEEDED',attempt=1,run_token=?,manifest_key=?,manifest_hash=? WHERE id=?",token,prefix+"manifest.json",sha(bytes),s.id());}jdbc.update("UPDATE analysis_job SET status='REVIEW_READY' WHERE id=?",job.id());return new Fixture(job.id(),video.id(),video.contentHash(),utterances);
    }
    private CompleteRequest publishPlan(Fixture f,ClipRequestModels.Snapshot request,ClaimResult l,java.util.function.Consumer<ObjectNode> mutate)throws Exception{
        ObjectNode plan=JSON.createObjectNode().put("schema_version","1.0").put("request_id",request.id()).put("job_id",f.job()).put("video_id",f.video()).put("source_hash",f.hash()).put("transcript_hash",request.source().transcriptHash()).put("duration_ms",4000).put("instruction",request.instruction());plan.putArray("goals").addObject().put("goal_id","cause").put("description","原因");((tools.jackson.databind.node.ArrayNode)plan.path("goals")).addObject().put("goal_id","method").put("description","方法");plan.putObject("constraints").put("keep_explanation_complete",true).putArray("exclude").add("关注引导");var outputs=plan.putArray("outputs");var cause=outputs.addObject().put("output_id","out-cause").put("goal_id","cause").put("title","原因").put("status","FOUND").put("reason","有源语句证据").put("enabled",false);cause.putArray("parts").addObject().put("start_utterance_id","u1").put("end_utterance_id","u1").put("start_ms",0).put("end_ms",1000);((tools.jackson.databind.node.ArrayNode)cause.path("parts")).addObject().put("start_utterance_id","u3").put("end_utterance_id","u3").put("start_ms",2000).put("end_ms",3000);cause.putArray("excluded").addObject().put("utterance_id","u2").put("reason","关注引导");cause.putArray("evidence_utterance_ids").add("u1").add("u3");var method=outputs.addObject().put("output_id","out-method").put("goal_id","method").put("title","方法").put("status","FOUND").put("reason","有源语句证据").put("enabled",false);method.putArray("parts").addObject().put("start_utterance_id","u4").put("end_utterance_id","u4").put("start_ms",3000).put("end_ms",4000);method.putArray("excluded");method.putArray("evidence_utterance_ids").add("u4");plan.putObject("provenance").put("text_model_id","qwen-plus").put("prompt_version","intent-clips-v1").put("retrieval_mode","bm25").put("index_hash","a".repeat(64));if(mutate!=null)mutate.accept(plan);
        String prefix=prefix(l);byte[] data=JSON.writeValueAsBytes(plan);write(prefix+"clip-plan.json",data);ObjectNode manifest=manifest(l.jobId(),l.videoId(),l.stageId(),l.stageType(),l.runToken(),l.sourceHash());manifest.put("attempt",l.attempt());manifest.putArray("artifacts").addObject().put("kind","clip_plan").put("key",prefix+"clip-plan.json").put("sha256",sha(data));byte[] bytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",bytes);return new CompleteRequest(l.attempt(),l.runToken(),prefix+"manifest.json",sha(bytes));
    }
    private CompleteRequest publishExport(ClaimResult l,Map<?,?> config,boolean reverse)throws Exception{
        String prefix=prefix(l);byte[] clip=new byte[]{0,1,2,3};byte[] metadata=JSON.writeValueAsBytes(Map.of("duration_ms",config.get("duration_ms"),"video_codec","h264","audio_codec","aac","frame_rate",30,"width",640,"height",360));write(prefix+"clip.mp4",clip);write(prefix+"media-info.json",metadata);ObjectNode manifest=manifest(l.jobId(),l.videoId(),l.stageId(),l.stageType(),l.runToken(),l.sourceHash());manifest.put("attempt",l.attempt()).put("data_kind","reviewed_export").put("review_status","CONFIRMED");for(String field:List.of("export_id","clip_id","snapshot_hash","segment_id","encoding_profile","clip_request_id"))manifest.put(field,String.valueOf(config.get(field)));for(String field:List.of("approved_revision","start_ms","end_ms","duration_ms"))manifest.put(field,((Number)config.get(field)).longValue());manifest.put("stage_input_hash",l.inputHash());JsonNode parts=JSON.valueToTree(config.get("parts"));if(reverse){var reversed=JSON.createArrayNode();reversed.add(parts.get(1));reversed.add(parts.get(0));parts=reversed;}manifest.set("parts",parts);manifest.putArray("artifacts").addObject().put("kind","clip").put("key",prefix+"clip.mp4").put("sha256",sha(clip));((tools.jackson.databind.node.ArrayNode)manifest.path("artifacts")).addObject().put("kind","media_info").put("key",prefix+"media-info.json").put("sha256",sha(metadata));byte[] bytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",bytes);return new CompleteRequest(l.attempt(),l.runToken(),prefix+"manifest.json",sha(bytes));
    }
    private CompleteRequest publishCaptioned(ClaimResult lease,Map<?,?> config)throws Exception{
        JsonNode spec=JSON.valueToTree(config.get("caption_render"));String prefix=prefix(lease);byte[] video={1,2,3,4};byte[] media=JSON.writeValueAsBytes(Map.of("duration_ms",spec.path("input_media").path("duration_ms").longValue(),"video_codec","h264","audio_codec","aac","frame_rate",30,"width",spec.path("output").path("width").intValue(),"height",spec.path("output").path("height").intValue()));
        byte[] info=JSON.writeValueAsBytes(Map.of("schema_version",1,"caption_render_hash",config.get("caption_render_hash"),"caption_render",spec));write(prefix+"clip.mp4",video);write(prefix+"media-info.json",media);write(prefix+"render-info.json",info);
        ObjectNode manifest=manifest(lease.jobId(),lease.videoId(),lease.stageId(),lease.stageType(),lease.runToken(),lease.sourceHash()).put("attempt",lease.attempt()).put("data_kind","captioned_export").put("review_status","CONFIRMED").put("stage_input_hash",lease.inputHash()).put("render_mode","CAPTIONED").put("caption_render_hash",String.valueOf(config.get("caption_render_hash")));
        for(String field:List.of("export_id","clip_id","snapshot_hash","segment_id","encoding_profile"))manifest.put(field,String.valueOf(config.get(field)));for(String field:List.of("approved_revision","start_ms","end_ms"))manifest.put(field,((Number)config.get(field)).longValue());
        var refs=manifest.putArray("artifacts");refs.addObject().put("kind","clip").put("key",prefix+"clip.mp4").put("sha256",sha(video));refs.addObject().put("kind","media_info").put("key",prefix+"media-info.json").put("sha256",sha(media));refs.addObject().put("kind","caption_render_info").put("key",prefix+"render-info.json").put("sha256",sha(info));byte[] bytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",bytes);return new CompleteRequest(lease.attempt(),lease.runToken(),prefix+"manifest.json",sha(bytes));
    }
    private static ObjectNode manifest(String job,String video,String stage,String type,String token,String hash){return JSON.createObjectNode().put("schema_version",1).put("job_id",job).put("video_id",video).put("analysis_generation",1).put("stage_id",stage).put("stage_type",type).put("attempt",1).put("run_token",token).put("input_sha256",hash).put("data_kind","model_result").put("review_status","DRAFT");}
    private static String prefix(ClaimResult l){return "jobs/"+l.jobId()+"/stages/"+l.stageId()+"/attempts/"+l.attempt()+"/"+l.runToken()+"/";}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void write(String key,byte[] bytes)throws Exception{Path file=root.resolve(key);Files.createDirectories(file.getParent());Files.write(file,bytes);}
    private HttpResponse<byte[]> send(String method,String path,Object body,String key,boolean worker)throws Exception{var builder=HttpRequest.newBuilder(base.resolve(path));if(body!=null)builder.header("Content-Type","application/json");if(key!=null)builder.header("Idempotency-Key",key);if(worker)builder.header("X-Worker-Key",KEY);return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());}
}
