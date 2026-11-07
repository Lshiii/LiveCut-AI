package io.livecut.agent;

import static org.junit.jupiter.api.Assertions.*;
import static io.livecut.agent.AgentAnswerValidator.JSON;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import io.livecut.LivecutBackendApplication;
import io.livecut.jobs.*;
import io.livecut.jobs.JobModels.*;
import io.livecut.jobs.ModelCallModels.*;
import io.livecut.video.VideoApiException;
import io.livecut.video.VideoService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real MySQL and HTTP; all media and model artifacts in this suite are explicitly synthetic. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentIntegrationTests {
    @TempDir static Path root;
    private static final String KEY="agent-synthetic-worker-key";
    private ConfigurableApplicationContext app;private JdbcTemplate jdbc;private JobService jobs;private AgentService agents;private ModelCallService calls;
    private URI base;private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> ownJobs=new ArrayList<>(),ownVideos=new ArrayList<>(),ownWorkers=new ArrayList<>();
    private record Fixture(String job,String video,String hash,JsonNode utterances) {}
    @BeforeAll void start(){assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"));startContext();}
    private void startContext(){
        app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,
            "--livecut.worker.api-key="+KEY,"--livecut.workers.require-registration=false","--livecut.workers.text-slots=1","--livecut.jobs.scan-enabled=false",
            "--livecut.jobs.retry-delay-seconds=0","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false","--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);jobs=app.getBean(JobService.class);agents=app.getBean(AgentService.class);calls=app.getBean(ModelCallService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(agents,"preferredTextModel","qwen-plus");
        org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredFallbackModels","");
        base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup(){for(String id:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);for(String id:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);for(String id:ownWorkers)jdbc.update("DELETE FROM worker_instance WHERE worker_id=?",id);ownJobs.clear();ownVideos.clear();ownWorkers.clear();}
    @AfterAll void stop(){if(app!=null)app.close();}

    @Test void sessionHttpIdentityAndIdempotencyDoNotMutateTheOriginalAnalysis()throws Exception{
        var f=fixture();var before=jobs.get(f.job());String key="session-"+UUID.randomUUID();
        var created=send("POST","/api/jobs/"+f.job()+"/agent-sessions",Map.of(),key);assertEquals(200,created.statusCode());String id=JSON.readTree(created.body()).path("id").asText();
        assertEquals(id,JSON.readTree(send("POST","/api/jobs/"+f.job()+"/agent-sessions",Map.of(),key).body()).path("id").asText());
        assertEquals(400,send("POST","/api/jobs/"+f.job()+"/agent-sessions",Map.of("unexpected",true),"other-"+UUID.randomUUID()).statusCode());
        assertEquals(400,send("POST","/api/jobs/"+f.job()+"/agent-sessions",Map.of(),null).statusCode());
        assertEquals(200,send("GET","/api/agent-sessions/"+id,null,null).statusCode());assertEquals(1,agents.list(f.job()).items().size());
        var session=agents.getSession(id);assertEquals(f.hash(),session.source().videoSha256());assertEquals(4000,session.source().durationMs());assertTrue(session.turns().isEmpty());
        String publicJson=JSON.writeValueAsString(session);for(String hidden:List.of("run_token","source_json","transcript_key","analysis_key","jobs/"))assertFalse(publicJson.contains(hidden));
        assertEquals(before,jobs.get(f.job()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM review_document WHERE job_id=?",Integer.class,f.job()));
        var other=fixture();assertEquals("IDEMPOTENCY_CONFLICT",failure(()->agents.createSession(other.job(),JSON.createObjectNode(),key)));
    }
    @Test void everyNewTurnRevalidatesTheFrozenPublishedEvidence()throws Exception{
        var f=fixture();jdbc.update("UPDATE analysis_job SET status='RUNNING' WHERE id=?",f.job());
        assertEquals("AGENT_SOURCE_NOT_READY",failure(()->session(f)));jdbc.update("UPDATE analysis_job SET status='REVIEW_READY' WHERE id=?",f.job());
        var session=session(f);String manifest=jdbc.queryForObject("SELECT manifest_key FROM job_stage WHERE job_id=? AND stage_type='ASR'",String.class,f.job());
        Files.writeString(root.resolve(manifest),"tampered");assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->turn(session.id(),"问原视频",8,"turn-"+UUID.randomUUID())));
        assertTrue(agents.getSession(session.id()).turns().isEmpty());
    }
    @Test void turnHttpReceiptSurvivesBusyStateAndRejectsChangedInputs()throws Exception{
        var f=fixture();var session=session(f);String key="turn-"+UUID.randomUUID();String question="  为什么缓存失效？  ";
        var created=send("POST","/api/agent-sessions/"+session.id()+"/turns",Map.of("question",question,"max_text_calls",8),key);
        assertEquals(200,created.statusCode());String id=JSON.readTree(created.body()).path("id").asText();
        assertEquals(question,agents.getTurn(id).question());assertEquals(1,agents.getTurn(id).sequence());
        assertEquals(id,turn(session.id(),question,8,key).id());
        assertEquals("IDEMPOTENCY_CONFLICT",failure(()->turn(session.id(),"换个问题",8,key)));
        assertEquals("AGENT_SESSION_BUSY",failure(()->turn(session.id(),"并发追问",8,"next-"+UUID.randomUUID())));
        assertEquals(400,send("POST","/api/agent-sessions/"+session.id()+"/turns",Map.of("question","x","max_text_calls",1.5),"invalid-"+UUID.randomUUID()).statusCode());
        assertEquals(1,agents.getSession(session.id()).turns().size());
    }
    @Test void concurrentTurnsAdmitOnlyOneActiveOwner(){
        var f=fixtureUnchecked();var session=session(f);
        var a=CompletableFuture.supplyAsync(()->createOutcome(session.id()));var b=CompletableFuture.supplyAsync(()->createOutcome(session.id()));
        assertEquals(Set.of("QUEUED","AGENT_SESSION_BUSY"),Set.of(a.join(),b.join()));assertEquals(1,agents.getSession(session.id()).turns().size());
    }
    @Test void successfulAnswerPersistsExactCitationsAndFreezesFollowupHistory()throws Exception{
        var f=fixture();var original=jobs.get(f.job());var session=session(f);var first=turn(session.id(),"缓存为什么失效？",8,"first-"+UUID.randomUUID());var lease=claim(first);
        assertEquals(0,lease.config().get("max_asr_submissions"));assertTrue(lease.config().containsKey("agent_turn"));assertEquals(4,((Map<?,?>)lease.config().get("agent_turn")).get("max_tool_steps"));
        var completion=publish(f,first,lease,null);jobs.complete(lease.stageId(),completion);assertEquals("ALREADY_APPLIED",jobs.complete(lease.stageId(),completion).outcome());
        var done=agents.getTurn(first.id());assertEquals("SUCCEEDED",done.status());assertEquals("修改前缀会使缓存失效",done.result().path("citations").get(0).path("text").asText());
        String publicJson=JSON.writeValueAsString(done);for(String hidden:List.of("run_token","source_json","source_transcript_key","jobs/"))assertFalse(publicJson.contains(hidden));
        var second=turn(session.id(),"那应该怎么处理？",8,"followup-"+UUID.randomUUID());var next=claim(second);var config=(Map<?,?>)next.config().get("agent_turn");
        assertEquals(2,second.sequence());var history=(List<?>)config.get("history");assertEquals(1,history.size());assertEquals(first.question(),((Map<?,?>)history.getFirst()).get("question"));
        assertEquals(done.result().path("answer").asText(),((Map<?,?>)history.getFirst()).get("answer"));assertEquals(original,jobs.get(f.job()));
    }
    @Test void invalidToolCallsCitationsAndIdentitiesCannotPublish()throws Exception{
        var f=fixture();var request=turn(session(f).id(),"缓存原因",8,"validate-"+UUID.randomUUID());var lease=claim(request);var original=jobs.get(f.job());
        List<Consumer<ObjectNode>> invalid=List.of(
            a->a.put("turn_id",UUID.randomUUID().toString()),a->a.put("transcript_hash","0".repeat(64)),
            a->a.put("question","另一个问题"),a->a.put("answer",""),a->a.putArray("citations"),
            a->((ObjectNode)a.path("citations").get(0)).put("start_ms",1),a->((ObjectNode)a.path("citations").get(0)).put("text","编造内容"),
            a->((ObjectNode)a.path("tool_calls").get(0)).put("tool_name","execute_shell"),
            a->((ObjectNode)a.path("tool_calls").get(0)).put("step",2),
            a->((ObjectNode)a.path("tool_calls").get(0).path("arguments")).put("unexpected",true),
            a->((ObjectNode)a.path("tool_calls").get(0)).putArray("result_utterance_ids").add("foreign"),
            a->((ObjectNode)a.path("tool_calls").get(0)).putArray("result_utterance_ids"),
            a->((ObjectNode)a.path("provenance")).put("retrieval_mode","bm25"));
        for(var mutate:invalid){var completion=publish(f,request,lease,mutate);assertEquals("INVALID_AGENT_ANSWER",failure(()->jobs.complete(lease.stageId(),completion)));assertNull(agents.getTurn(request.id()).result());}
        assertEquals(original,jobs.get(f.job()));jobs.complete(lease.stageId(),publish(f,request,lease,null));assertEquals("SUCCEEDED",agents.getTurn(request.id()).status());
    }
    @Test void generalKnowledgeAndInsufficientEvidenceCanAnswerWithoutInventedCitations()throws Exception{
        var f=fixture();var session=session(f);
        for(String scope:List.of("GENERAL","INSUFFICIENT")){
            var turn=turn(session.id(),"这个视频之外的信息",8,"scope-"+UUID.randomUUID());var lease=claim(turn);
            jobs.complete(lease.stageId(),publish(f,turn,lease,a->{a.put("answer_scope",scope);a.putArray("citations");a.putArray("tool_calls");}));
            assertEquals(scope,agents.getTurn(turn.id()).result().path("answer_scope").asText());
        }
    }
    @Test void independentBudgetUnknownOutcomeAndCancellationNeverSpendParentAsr()throws Exception{
        var f=fixture();var original=jobs.get(f.job());var turn=turn(session(f).id(),"预算测试",1,"budget-"+UUID.randomUUID());var lease=claim(turn);
        var reserve=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"a".repeat(64),"b".repeat(64),"agent-first","qwen-plus","TEXT");
        assertEquals("NOT_FOUND",calls.lookup(reserve).outcome());assertEquals("RESERVED",calls.reserve(reserve).outcome());assertEquals("EXISTING",calls.reserve(reserve).outcome());
        assertEquals(1,agents.getTurn(turn.id()).budget().textCallsReserved());assertEquals("AGENT_TURN",jdbc.queryForObject("SELECT owner_type FROM model_call WHERE request_id=?",String.class,reserve.requestId()));
        calls.update(reserve.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of()));
        calls.update(reserve.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"UNKNOWN_OUTCOME",null,Map.of()));
        assertEquals("MODEL_CALL_STATE_CONFLICT",failure(()->calls.update(reserve.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of()))));
        assertEquals("MODEL_BUDGET_EXHAUSTED",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"c".repeat(64),"d".repeat(64),"agent-second","qwen-plus","TEXT"))));
        assertEquals("MODEL_KIND_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"e".repeat(64),"f".repeat(64),"forbidden-asr","paraformer-v2","ASR"))));
        agents.cancel(turn.id());calls.update(reserve.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"FAILED",null,Map.of()));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM model_call WHERE request_id=?",String.class,reserve.requestId()));assertEquals(original,jobs.get(f.job()));
    }
    @Test void runningCancellationFencesLateResultsAndAllowsANewIndependentTurn()throws Exception{
        var f=fixture();var session=session(f);var turn=turn(session.id(),"取消测试",8,"cancel-"+UUID.randomUUID());var lease=claim(turn);var before=jobs.get(f.job());
        assertEquals("CANCELLED",agents.cancel(turn.id()).status());assertEquals(agents.getTurn(turn.id()),agents.cancel(turn.id()));
        assertEquals("STALE_ATTEMPT",failure(()->jobs.heartbeat(lease.stageId(),new Credential(lease.attempt(),lease.runToken()))));
        var late=publish(f,turn,lease,null);assertEquals("STALE_ATTEMPT",failure(()->jobs.complete(lease.stageId(),late)));assertNull(agents.getTurn(turn.id()).result());
        var next=turn(session.id(),"重新问",8,"after-cancel-"+UUID.randomUUID());assertEquals(2,next.sequence());assertEquals(before,jobs.get(f.job()));
    }
    @Test void reclaimedAndFailedAgentStagesLeaveTheSourceStagesUntouched()throws Exception{
        var f=fixture();var turn=turn(session(f).id(),"恢复测试",8,"recover-"+UUID.randomUUID());var original=jobs.get(f.job());var old=claim(turn);
        jdbc.update("UPDATE job_stage SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",old.stageId());jobs.recoverExpired();
        var fresh=claim(agents.getTurn(turn.id()));assertEquals(old.attempt()+1,fresh.attempt());
        assertEquals("STALE_ATTEMPT",failure(()->jobs.heartbeat(old.stageId(),new Credential(old.attempt(),old.runToken()))));
        jobs.fail(fresh.stageId(),new FailRequest(fresh.attempt(),fresh.runToken(),"AGENT_TEST_FAILURE",false));assertEquals("FAILED",agents.getTurn(turn.id()).status());
        assertEquals(original,jobs.get(f.job()));assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND status='SUCCEEDED'",Integer.class,f.job()));
    }
    @Test void aRegisteredAgentTurnSharesTheExistingTextResourceCapacity()throws Exception{
        var first=fixture();var second=fixture();var a=turn(session(first).id(),"任务A",8,"capacity-a-"+UUID.randomUUID());var b=turn(session(second).id(),"任务B",8,"capacity-b-"+UUID.randomUUID());
        var workers=app.getBean(WorkerCapacityService.class);String worker="agent-capacity-"+UUID.randomUUID(),session=UUID.randomUUID().toString();ownWorkers.add(worker);
        workers.register(new WorkerModels.Registration(worker,session,List.of("SEGMENT"),2,null));
        var accepted=jobs.claim(new ClaimRequest(a.stage().id(),worker,UUID.randomUUID().toString(),null,session));assertEquals("CLAIMED",accepted.outcome());
        assertEquals("NO_CAPACITY",jobs.claim(new ClaimRequest(b.stage().id(),worker,UUID.randomUUID().toString(),null,session)).outcome());
        agents.cancel(a.id());assertEquals("CLAIMED",jobs.claim(new ClaimRequest(b.stage().id(),worker,UUID.randomUUID().toString(),null,session)).outcome());
    }
    @Test void persistedConversationSurvivesBackendRestart()throws Exception{
        var f=fixture();var session=session(f);var turn=turn(session.id(),"保留对话",8,"restart-"+UUID.randomUUID());var lease=claim(turn);jobs.complete(lease.stageId(),publish(f,turn,lease,null));
        var before=agents.getSession(session.id());app.close();startContext();assertEquals(before,agents.getSession(session.id()));assertEquals("REVIEW_READY",jobs.get(f.job()).status());
    }
    @Test void agentAnswersLeaveAnExistingConfirmedReviewAndExportUnchanged()throws Exception{
        var f=fixture();var reviews=app.getBean(io.livecut.topics.TopicReviewService.class);var original=reviews.get(f.job(),null);var topic=original.segments().getFirst();
        var edit=JSON.createObjectNode().put("base_revision",original.revision());edit.putArray("segments").addObject().put("id",topic.id()).put("start_ms",topic.startMs()).put("end_ms",topic.endMs()).put("title","已人工审核的标题").put("summary",topic.summary()).put("enabled",true).putArray("keywords").add("缓存");
        var saved=reviews.save(f.job(),edit);var confirmed=reviews.confirm(f.job(),JSON.createObjectNode().put("revision",saved.revision()));
        var exports=app.getBean(io.livecut.exports.ExportService.class);var exported=exports.create(new io.livecut.exports.ExportModels.CreateRequest(f.job(),confirmed.revision(),List.of(topic.id()),"h264-aac-v1"));
        var before=jobs.get(f.job());var turn=turn(session(f).id(),"视频讲了什么",8,"preserve-"+UUID.randomUUID());var lease=claim(turn);jobs.complete(lease.stageId(),publish(f,turn,lease,null));
        assertEquals(confirmed,reviews.get(f.job(),null));assertEquals(exported,exports.get(exported.id()));assertEquals(before,jobs.get(f.job()));
    }
    @Test void followupHistoryIsLimitedToRecentSuccessfulPairsAndTwelveThousandCharacters()throws Exception{
        var f=fixture();var session=session(f);
        for(int i=0;i<8;i++){var turn=turn(session.id(),"问题-"+i,8,"history-"+UUID.randomUUID());var lease=claim(turn);jobs.complete(lease.stageId(),publish(f,turn,lease,a->a.put("answer","答".repeat(2100))));}
        var next=turn(session.id(),"最后追问",8,"history-final-"+UUID.randomUUID());var lease=claim(next);var history=(List<?>)((Map<?,?>)lease.config().get("agent_turn")).get("history");
        assertTrue(history.size()<=6);int characters=0;for(var row:history){var pair=(Map<?,?>)row;characters+=String.valueOf(pair.get("question")).length()+String.valueOf(pair.get("answer")).length();}
        assertTrue(characters<=12000);assertEquals("问题-7",((Map<?,?>)history.getLast()).get("question"));assertFalse(history.stream().anyMatch(row->"问题-0".equals(((Map<?,?>)row).get("question"))));
    }
    @Test void eachTurnFreezesCurrentModelsAndAllowsOnlyItsOwnFallbackList()throws Exception{
        var f=fixture();var session=session(f);var original=jobs.get(f.job());
        org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredFallbackModels","deepseek-flash");
        try{
            var first=turn(session.id(),"使用已允许备用",8,"model-first-"+UUID.randomUUID());
            org.springframework.test.util.ReflectionTestUtils.setField(agents,"preferredTextModel","deepseek-flash");
            org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredFallbackModels","");
            var lease=claim(first);assertEquals("qwen-plus",lease.config().get("text_model"));assertEquals(List.of("deepseek-flash"),lease.config().get("text_fallback_models"));
            assertEquals("RESERVED",calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"5".repeat(64),"6".repeat(64),"fallback-test","deepseek-flash","TEXT")).outcome());
            assertEquals("MODEL_CONFIG_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"7".repeat(64),"8".repeat(64),"foreign-model","other-model","TEXT"))));
            agents.cancel(first.id());var next=turn(session.id(),"新一轮配置",8,"model-next-"+UUID.randomUUID());var fresh=claim(next);assertEquals("deepseek-flash",fresh.config().get("text_model"));assertEquals(List.of(),fresh.config().get("text_fallback_models"));
            assertEquals(original,jobs.get(f.job()));
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(agents,"preferredTextModel","qwen-plus");org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredFallbackModels","");}
    }
    private AgentModels.Session session(Fixture f){return agents.createSession(f.job(),JSON.createObjectNode(),"session-"+UUID.randomUUID());}
    private AgentModels.Turn turn(String session,String question,int budget,String key){return agents.createTurn(session,JSON.valueToTree(Map.of("question",question,"max_text_calls",budget)),key);}
    private String createOutcome(String session){try{return turn(session,"并发问题",8,"concurrent-"+UUID.randomUUID()).status();}catch(JobException e){return e.getCode();}}
    private ClaimResult claim(AgentModels.Turn turn){assertTrue(jobs.ready("SEGMENT",100,turn.jobId()).stream().anyMatch(s->s.id().equals(turn.stage().id())));return jobs.claim(new ClaimRequest(turn.stage().id(),"agent-synthetic",UUID.randomUUID().toString()));}
    private static String failure(Runnable action){RuntimeException error=assertThrows(RuntimeException.class,action::run);if(error instanceof JobException e)return e.getCode();if(error instanceof VideoApiException e)return e.getCode();throw new AssertionError(error);}
    private Fixture fixtureUnchecked(){try{return fixture();}catch(Exception e){throw new RuntimeException(e);}}
    private Fixture fixture()throws Exception{
        byte[] header=ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var video=app.getBean(VideoService.class).upload(new MockMultipartFile("file","agent-synthetic.mp4","video/mp4",header));ownVideos.add(video.id());
        var job=jobs.create(new CreateJobRequest(video.id(),Map.of("analysis_mode","topics","visual_provider","none","text_model","qwen-plus","max_text_calls",0,"max_asr_submissions",0)),"agent-source-"+UUID.randomUUID());ownJobs.add(job.id());
        var utterances=JSON.createArrayNode();for(int i=1;i<=4;i++)utterances.addObject().put("utterance_id","u"+i).put("start_ms",(i-1)*1000).put("end_ms",i*1000).put("text",i==1?"修改前缀会使缓存失效":i==2?"关注点赞":i==3?"缓存按历史前缀匹配":"追加历史可以继续复用").put("source_chunk_id","c0");
        ObjectNode result=JSON.createObjectNode().put("schema_version","1.0").put("analysis_mode","topics").put("data_kind","model_result").put("job_id",job.id()).put("video_id",video.id()).put("analysis_generation",1).put("duration_ms",4000).put("transcript_version","synthetic").put("pipeline_version","topics-text-v1").put("review_status","DRAFT");result.set("utterances",utterances);result.putArray("frames");
        result.putObject("provenance").put("asr_model_id","paraformer-v2").put("text_model_id","qwen-plus").put("prompt_version","synthetic").put("config_hash","a".repeat(64));
        var topic=result.putArray("segments").addObject().put("segment_id","topic-1").put("title","缓存").put("summary","合成缓存示例").put("start_ms",0).put("end_ms",4000).put("start_utterance_id","u1").put("end_utterance_id","u4");topic.putArray("keywords").add("缓存");topic.putArray("audio_evidence_utterance_ids").add("u1").add("u2").add("u3").add("u4");
        for(var stage:job.stages()){
            String token=UUID.randomUUID().toString(),prefix="jobs/"+job.id()+"/stages/"+stage.id()+"/attempts/1/"+token+"/";
            Map<String,byte[]> files=new LinkedHashMap<>();if(stage.stageType().equals("ASR"))files.put("transcript",JSON.writeValueAsBytes(utterances));else if(stage.stageType().equals("MEDIA")){files.put("audio",new byte[]{1,2,3});files.put("media_info",JSON.writeValueAsBytes(Map.of("duration_ms",4000)));}else files.put("analysis_result",JSON.writeValueAsBytes(result));
            ObjectNode manifest=manifest(job.id(),video.id(),stage.id(),stage.stageType(),1,token,video.contentHash());var refs=manifest.putArray("artifacts");for(var file:files.entrySet()){String key=prefix+file.getKey()+".json";write(key,file.getValue());refs.addObject().put("kind",file.getKey()).put("key",key).put("sha256",sha(file.getValue()));}
            byte[] bytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",bytes);jdbc.update("UPDATE job_stage SET status='SUCCEEDED',attempt=1,run_token=?,manifest_key=?,manifest_hash=? WHERE id=?",token,prefix+"manifest.json",sha(bytes),stage.id());
        }
        jdbc.update("UPDATE analysis_job SET status='REVIEW_READY' WHERE id=?",job.id());return new Fixture(job.id(),video.id(),video.contentHash(),utterances);
    }
    private CompleteRequest publish(Fixture f,AgentModels.Turn turn,ClaimResult lease,Consumer<ObjectNode> mutate)throws Exception{
        String transcriptHash=String.valueOf(((Map<?,?>)lease.config().get("agent_turn")).get("source_transcript_hash"));
        ObjectNode answer=JSON.createObjectNode().put("schema_version","1.0").put("session_id",turn.sessionId()).put("turn_id",turn.id()).put("job_id",f.job()).put("video_id",f.video()).put("source_hash",f.hash()).put("transcript_hash",transcriptHash).put("question",turn.question()).put("answer","视频说明修改前缀会使缓存失效。").put("answer_scope","VIDEO");
        var citation=answer.putArray("citations").addObject();for(String field:List.of("utterance_id","start_ms","end_ms","text"))citation.set(field,f.utterances().get(0).path(field).deepCopy());
        var tool=answer.putArray("tool_calls").addObject().put("step",1).put("tool_name","read_context");tool.putObject("arguments").put("utterance_id","u1").put("before",0).put("after",0);tool.putArray("result_utterance_ids").add("u1");
        answer.putObject("provenance").put("text_model_id","qwen-plus").put("prompt_version","video-agent-v1").put("retrieval_mode","none");answer.putNull("suggested_clip_instruction");if(mutate!=null)mutate.accept(answer);
        String prefix="jobs/"+lease.jobId()+"/stages/"+lease.stageId()+"/attempts/"+lease.attempt()+"/"+lease.runToken()+"/";byte[] bytes=JSON.writeValueAsBytes(answer);write(prefix+"agent-answer.json",bytes);
        ObjectNode manifest=manifest(lease.jobId(),lease.videoId(),lease.stageId(),lease.stageType(),lease.attempt(),lease.runToken(),lease.sourceHash());manifest.putArray("artifacts").addObject().put("kind","agent_answer").put("key",prefix+"agent-answer.json").put("sha256",sha(bytes));
        byte[] data=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",data);return new CompleteRequest(lease.attempt(),lease.runToken(),prefix+"manifest.json",sha(data));
    }
    private static ObjectNode manifest(String job,String video,String stage,String type,int attempt,String token,String hash){return JSON.createObjectNode().put("schema_version",1).put("job_id",job).put("video_id",video).put("analysis_generation",1).put("stage_id",stage).put("stage_type",type).put("attempt",attempt).put("run_token",token).put("input_sha256",hash).put("data_kind","model_result").put("review_status","DRAFT");}
    private static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static void write(String key,byte[] value)throws Exception{Path path=root.resolve(key);Files.createDirectories(path.getParent());Files.write(path,value);}
    private HttpResponse<byte[]> send(String method,String path,Object body,String key)throws Exception{
        var request=HttpRequest.newBuilder(base.resolve(path));if(body!=null)request.header("Content-Type","application/json");if(key!=null)request.header("Idempotency-Key",key);
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
}
