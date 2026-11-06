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
import io.livecut.LivecutBackendApplication;
import io.livecut.exports.ExportService;
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
    private record Fixture(String job,String video,String hash,JsonNode utterances) {}
    @BeforeAll void start(){assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"));startContext();}
    private void startContext(){app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,"--livecut.worker.api-key="+KEY,"--livecut.workers.require-registration=false","--livecut.jobs.scan-enabled=false","--livecut.jobs.retry-delay-seconds=0","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false","--spring.main.banner-mode=off","--logging.level.root=WARN");jdbc=app.getBean(JdbcTemplate.class);jobs=app.getBean(JobService.class);clips=app.getBean(ClipRequestService.class);exports=app.getBean(ExportService.class);calls=app.getBean(ModelCallService.class);base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));}
    @AfterEach void cleanup(){for(String id:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);for(String id:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);ownJobs.clear();ownVideos.clear();}
    @AfterAll void stop(){if(app!=null)app.close();}

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
        String prefix=prefix(l);byte[] clip=new byte[]{0,1,2,3};byte[] metadata=JSON.writeValueAsBytes(Map.of("duration_ms",2000,"video_codec","h264","audio_codec","aac","frame_rate",30));write(prefix+"clip.mp4",clip);write(prefix+"media-info.json",metadata);ObjectNode manifest=manifest(l.jobId(),l.videoId(),l.stageId(),l.stageType(),l.runToken(),l.sourceHash());manifest.put("attempt",l.attempt()).put("data_kind","reviewed_export").put("review_status","CONFIRMED");for(String field:List.of("export_id","clip_id","snapshot_hash","segment_id","encoding_profile","clip_request_id"))manifest.put(field,String.valueOf(config.get(field)));for(String field:List.of("approved_revision","start_ms","end_ms","duration_ms"))manifest.put(field,((Number)config.get(field)).longValue());manifest.put("stage_input_hash",l.inputHash());JsonNode parts=JSON.valueToTree(config.get("parts"));if(reverse){var reversed=JSON.createArrayNode();reversed.add(parts.get(1));reversed.add(parts.get(0));parts=reversed;}manifest.set("parts",parts);manifest.putArray("artifacts").addObject().put("kind","clip").put("key",prefix+"clip.mp4").put("sha256",sha(clip));((tools.jackson.databind.node.ArrayNode)manifest.path("artifacts")).addObject().put("kind","media_info").put("key",prefix+"media-info.json").put("sha256",sha(metadata));byte[] bytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",bytes);return new CompleteRequest(l.attempt(),l.runToken(),prefix+"manifest.json",sha(bytes));
    }
    private static ObjectNode manifest(String job,String video,String stage,String type,String token,String hash){return JSON.createObjectNode().put("schema_version",1).put("job_id",job).put("video_id",video).put("analysis_generation",1).put("stage_id",stage).put("stage_type",type).put("attempt",1).put("run_token",token).put("input_sha256",hash).put("data_kind","model_result").put("review_status","DRAFT");}
    private static String prefix(ClaimResult l){return "jobs/"+l.jobId()+"/stages/"+l.stageId()+"/attempts/"+l.attempt()+"/"+l.runToken()+"/";}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void write(String key,byte[] bytes)throws Exception{Path file=root.resolve(key);Files.createDirectories(file.getParent());Files.write(file,bytes);}
    private HttpResponse<byte[]> send(String method,String path,Object body,String key,boolean worker)throws Exception{var builder=HttpRequest.newBuilder(base.resolve(path));if(body!=null)builder.header("Content-Type","application/json");if(key!=null)builder.header("Idempotency-Key",key);if(worker)builder.header("X-Worker-Key",KEY);return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());}
}
