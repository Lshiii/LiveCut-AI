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
import io.livecut.captions.*;
import io.livecut.exports.*;
import io.livecut.jobs.*;
import io.livecut.jobs.JobModels.*;
import io.livecut.jobs.ModelCallModels.*;
import io.livecut.topics.TopicReviewService;
import io.livecut.video.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real MySQL/HTTP and hash-checked synthetic media; never contacts a model provider. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaptionProofreadIntegrationTests {
    @TempDir static Path root;
    private ConfigurableApplicationContext app;private JdbcTemplate jdbc;private JobService jobs;private ExportService exports;private CaptionService captions;private AgentService agents;private ModelCallService calls;
    private URI base;private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> ownJobs=new ArrayList<>(),ownVideos=new ArrayList<>(),ownWorkers=new ArrayList<>();
    private record Parent(String job,String video,String hash,JsonNode utterances) {}
    private record Fixture(Parent parent,ExportModels.Snapshot batch){String export(){return batch.id();}String clip(){return batch.clips().getFirst().id();}String path(){return "/api/exports/"+export()+"/clips/"+clip()+"/caption-proofread-sessions";}}
    @BeforeAll void start(){assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"));startContext();}
    private void startContext(){app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,"--livecut.workers.require-registration=false","--livecut.workers.text-slots=1","--livecut.jobs.scan-enabled=false","--livecut.jobs.retry-delay-seconds=0","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false","--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);jobs=app.getBean(JobService.class);exports=app.getBean(ExportService.class);captions=app.getBean(CaptionService.class);agents=app.getBean(AgentService.class);calls=app.getBean(ModelCallService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(agents,"preferredTextModel","qwen-plus");org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredFallbackModels","");base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));}
    @AfterEach void cleanup(){for(String id:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);for(String id:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);for(String id:ownWorkers)jdbc.update("DELETE FROM worker_instance WHERE worker_id=?",id);ownJobs.clear();ownVideos.clear();ownWorkers.clear();}
    @AfterAll void stop(){if(app!=null)app.close();}

    @Test void captionCreationIsExplicitIdempotentAndVideoEndpointsRemainIsolated()throws Exception{
        var f=fixture(0,4000,false);var original=captions.initialize(f.export(),f.clip());var before=jobs.get(f.parent().job());var exported=exports.get(f.export());String key="caption-session-"+UUID.randomUUID();
        var request=Map.of("caption_revision",0,"caption_content_hash",original.contentHash());var response=send("POST",f.path(),request,key);assertEquals(200,response.statusCode());String id=JSON.readTree(response.body()).path("id").asText();
        assertEquals(id,JSON.readTree(send("POST",f.path(),request,key).body()).path("id").asText());
        assertEquals(400,send("POST",f.path(),request,null).statusCode());assertEquals(409,send("POST",f.path(),Map.of("caption_revision",0,"caption_content_hash","f".repeat(64)),"wrong-"+UUID.randomUUID()).statusCode());
        var session=agents.getCaptionSession(id);assertEquals(4,session.source().totalCues());assertEquals(original.source().clipSha256(),session.source().captionSource().path("clip_sha256").asText());assertEquals(0,session.turnsTotal());
        assertEquals(404,send("GET","/api/agent-sessions/"+id,null,null).statusCode());assertTrue(agents.list(f.parent().job()).items().isEmpty());assertEquals(1,agents.listCaptionSessions(f.export(),f.clip()).items().size());
        String publicJson=JSON.writeValueAsString(session);for(String hidden:List.of("run_token","source_json","transcript_key","analysis_key","jobs/"))assertFalse(publicJson.contains(hidden));
        assertEquals(before,jobs.get(f.parent().job()));assertEquals(exported,exports.get(f.export()));assertEquals(original,captions.get(f.export(),f.clip(),null));
    }
    @Test void captionTurnReceiptsAreIdempotentBeforeBusyAndNeverCrossTheVideoApi()throws Exception{
        var f=fixture(0,4000,false);var session=session(f);String key="batch-"+UUID.randomUUID();var input=JSON.valueToTree(Map.of("offset",0));var first=agents.createCaptionTurn(session.id(),input,key);
        assertEquals(4,first.budget().maxTextCalls());assertEquals(CaptionProofreadValidator.DEFAULT_INSTRUCTION,first.question());assertEquals(first.id(),agents.createCaptionTurn(session.id(),input,key).id());
        assertEquals("IDEMPOTENCY_CONFLICT",failure(()->agents.createCaptionTurn(session.id(),JSON.valueToTree(Map.of("offset",1)),key)));
        assertEquals("AGENT_SESSION_BUSY",failure(()->turn(session.id(),0,4)));
        assertEquals(404,send("GET","/api/agent-turns/"+first.id(),null,null).statusCode());assertEquals(404,send("POST","/api/agent-turns/"+first.id()+"/cancel",null,null).statusCode());
        assertEquals(404,send("POST","/api/agent-sessions/"+session.id()+"/turns",Map.of("question","wrong API"),"wrong-"+UUID.randomUUID()).statusCode());
        assertEquals(CaptionProofreadValidator.STAGE,first.stage().stageType());assertEquals(4,first.batch().count());assertNull(first.batch().nextOffset());assertTrue(first.evidence().isEmpty());
    }
    @Test void aChangedConfirmationOrNewCaptionHeadDoesNotInvalidateTheNamedOldRevision()throws Exception{
        var f=fixture(0,4000,false);var original=captions.initialize(f.export(),f.clip());var session=session(f);
        captions.confirm(f.export(),f.clip(),JSON.valueToTree(Map.of("revision",0,"acknowledge_review",true)));var first=turn(session.id(),0,4);agents.cancelCaptionTurn(first.id());
        var edit=edit(original);((ObjectNode)edit.path("cues").get(0)).put("text","新修订人工字幕");captions.save(f.export(),f.clip(),edit);
        var second=turn(session.id(),0,4);var lease=claim(second.stage().id());JsonNode context=JSON.valueToTree(((Map<?,?>)lease.config().get("agent_turn")).get("caption_context"));
        assertEquals(0,context.path("revision").intValue());assertEquals(original.cues().getFirst().text(),context.path("cues").get(0).path("text").asText());
        assertEquals("新修订人工字幕",captions.get(f.export(),f.clip(),null).cues().getFirst().text());
    }
    @Test void anOlderSegmentOnlyExecutorCannotConsumeNewProofreadingAndTextCapacityIsShared()throws Exception{
        var f=fixture(0,4000,false);var session=session(f);var first=turn(session.id(),0,4);String worker="old-caption-"+UUID.randomUUID(),workerSession=UUID.randomUUID().toString();ownWorkers.add(worker);
        var capacity=app.getBean(WorkerCapacityService.class);capacity.register(new WorkerModels.Registration(worker,workerSession,List.of("SEGMENT"),2,null));
        assertEquals("WORKER_CAPABILITY_MISMATCH",failure(()->jobs.claim(new ClaimRequest(first.stage().id(),worker,UUID.randomUUID().toString(),null,workerSession))));
        assertTrue(jobs.ready("SEGMENT",100,f.parent().job()).stream().noneMatch(s->s.id().equals(first.stage().id())));assertTrue(jobs.ready(CaptionProofreadValidator.STAGE,100,f.parent().job()).stream().anyMatch(s->s.id().equals(first.stage().id())));
        String capable="caption-capacity-"+UUID.randomUUID(),capableSession=UUID.randomUUID().toString();ownWorkers.add(capable);capacity.register(new WorkerModels.Registration(capable,capableSession,List.of(CaptionProofreadValidator.STAGE,"SEGMENT"),2,null));
        assertEquals("CLAIMED",jobs.claim(new ClaimRequest(first.stage().id(),capable,UUID.randomUUID().toString(),null,capableSession)).outcome());
        var other=fixture(0,4000,false);var next=turn(session(other).id(),0,4);assertEquals("NO_CAPACITY",jobs.claim(new ClaimRequest(next.stage().id(),capable,UUID.randomUUID().toString(),null,capableSession)).outcome());
        agents.cancelCaptionTurn(first.id());assertEquals("CLAIMED",jobs.claim(new ClaimRequest(next.stage().id(),capable,UUID.randomUUID().toString(),null,capableSession)).outcome());
    }
    @Test void independentTextBudgetUnknownAccountingAndCancellationPreserveAllSourceRecords()throws Exception{
        var f=fixture(0,4000,false);var session=session(f);var original=captions.get(f.export(),f.clip(),null);var before=jobs.get(f.parent().job());var turn=turn(session.id(),0,1);var lease=claim(turn.stage().id());
        assertEquals(0,((Map<?,?>)lease.config().get("agent_turn")).get("max_tool_steps"));
        var request=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"a".repeat(64),"b".repeat(64),"proofread-first","qwen-plus","TEXT");assertEquals("NOT_FOUND",calls.lookup(request).outcome());assertEquals("RESERVED",calls.reserve(request).outcome());assertEquals("EXISTING",calls.reserve(request).outcome());
        calls.update(request.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of()));calls.update(request.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"UNKNOWN_OUTCOME",null,Map.of()));
        assertEquals("MODEL_BUDGET_EXHAUSTED",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"c".repeat(64),"d".repeat(64),"proofread-second","qwen-plus","TEXT"))));
        assertEquals("MODEL_KIND_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"e".repeat(64),"f".repeat(64),"proofread-asr","paraformer-v2","ASR"))));
        assertEquals("CANCELLED",agents.cancelCaptionTurn(turn.id()).status());assertEquals("UNKNOWN_OUTCOME",jdbc.queryForObject("SELECT status FROM model_call WHERE request_id=?",String.class,request.requestId()));
        assertEquals("STALE_ATTEMPT",failure(()->jobs.complete(lease.stageId(),publishUnchecked(turn,lease,null))));assertEquals(before,jobs.get(f.parent().job()));assertEquals(original,captions.get(f.export(),f.clip(),null));
    }
    @Test void successfulSuggestionsPublishAnIndependentArtifactAndPublicExactEvidence()throws Exception{
        var f=fixture(0,4000,false);var session=session(f);var before=captions.get(f.export(),f.clip(),null);var exported=exports.get(f.export());var original=jobs.get(f.parent().job());var turn=turn(session.id(),0,4);var lease=claim(turn.stage().id());
        assertEquals(3,((Map<?,?>)lease.config().get("agent_turn")).get("max_tool_steps"));
        var complete=publish(turn,lease,null);assertEquals("ACCEPTED",jobs.complete(lease.stageId(),complete).outcome());assertEquals("ALREADY_APPLIED",jobs.complete(lease.stageId(),complete).outcome());
        var done=agents.getCaptionTurn(turn.id());assertEquals("SUCCEEDED",done.status());assertEquals(1,done.result().path("suggestions").size());assertEquals(1,done.evidence().size());assertEquals("u1",done.evidence().getFirst().utteranceId());assertEquals("第1句原始转写",done.evidence().getFirst().text());
        assertEquals(before,captions.get(f.export(),f.clip(),null));assertEquals(exported,exports.get(f.export()));assertEquals(original,jobs.get(f.parent().job()));
        String result=jdbc.queryForObject("SELECT result_json FROM agent_turn WHERE id=?",String.class,turn.id());jdbc.update("UPDATE agent_turn SET result_json=? WHERE id=?",result.replace("合成纠错建议","被篡改建议"),turn.id());assertEquals("CAPTION_PROOFREAD_RESULT_INVALID",failure(()->agents.getCaptionTurn(turn.id())));
    }
    @Test void wrongCoverageOrARegularVideoAnswerNeverBecomesCaptionSuggestions()throws Exception{
        var f=fixture(0,4000,false);var session=session(f);var turn=turn(session.id(),0,4);var lease=claim(turn.stage().id());
        var bad=publish(turn,lease,r->((ObjectNode)r.path("coverage")).put("count",3));assertThrows(JobException.class,()->jobs.complete(lease.stageId(),bad));assertNull(agents.getCaptionTurn(turn.id()).result());
        var good=publish(turn,lease,null);jobs.complete(lease.stageId(),good);assertEquals("SUCCEEDED",agents.getCaptionTurn(turn.id()).status());
    }
    @Test void longSessionCanPassOneHundredBatchesAndHistoryIsBoundedAndPaged()throws Exception{
        var f=fixture(0,4000,false);var session=session(f);String first="";for(int i=0;i<101;i++){var turn=turn(session.id(),0,1);if(i==0)first=turn.id();agents.cancelCaptionTurn(turn.id());}
        var current=agents.getCaptionSession(session.id());assertEquals(101,current.turnsTotal());assertEquals(81,current.turnsOffset());assertEquals(20,current.turns().size());assertEquals(82,current.turns().getFirst().sequence());assertEquals(101,current.turns().getLast().sequence());
        var page=agents.captionTurns(session.id(),0,1);assertEquals(101,page.total());assertEquals(first,page.items().getFirst().id());assertEquals(400,send("GET","/api/caption-proofread-sessions/"+session.id()+"/turns?limit=101",null,null).statusCode());
    }
    @Test void sourceChangesAreRejectedBeforeNewTextReservationsAndSessionsSurviveRestart()throws Exception{
        var f=fixture(0,4000,false);var session=session(f);var turn=turn(session.id(),0,4);agents.cancelCaptionTurn(turn.id());var before=agents.getCaptionSession(session.id());app.close();startContext();assertEquals(before,agents.getCaptionSession(session.id()));
        String manifest=jdbc.queryForObject("SELECT manifest_key FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type='ASR'",String.class,f.parent().job());Files.writeString(root.resolve(manifest),"tampered");assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->turn(session.id(),0,4)));
        assertEquals(1,agents.getCaptionSession(session.id()).turnsTotal());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.parent().job()));
    }
    private CaptionProofreadModels.Session session(Fixture f){var caption=captions.initialize(f.export(),f.clip());return agents.createCaptionSession(f.export(),f.clip(),JSON.valueToTree(Map.of("caption_revision",caption.revision(),"caption_content_hash",caption.contentHash())),"proof-session-"+UUID.randomUUID());}
    private CaptionProofreadModels.Turn turn(String session,int offset,int budget){return agents.createCaptionTurn(session,JSON.valueToTree(Map.of("offset",offset,"max_text_calls",budget)),"proof-turn-"+UUID.randomUUID());}
    private CompleteRequest publishUnchecked(CaptionProofreadModels.Turn turn,ClaimResult lease,Consumer<ObjectNode> mutate){try{return publish(turn,lease,mutate);}catch(Exception e){throw new RuntimeException(e);}}
    private CompleteRequest publish(CaptionProofreadModels.Turn turn,ClaimResult lease,Consumer<ObjectNode> mutate)throws Exception{
        var answer=CaptionProofreadValidatorTests.result(agents.lockOwner(turn.id(),turn.jobId()));if(mutate!=null)mutate.accept(answer);String prefix=prefix(lease);byte[] bytes=JSON.writeValueAsBytes(answer);write(prefix+"caption-proofread.json",bytes);
        var manifest=manifest(lease,"model_result","DRAFT");manifest.putArray("artifacts").addObject().put("kind","caption_proofread").put("key",prefix+"caption-proofread.json").put("sha256",sha(bytes));byte[] data=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",data);return new CompleteRequest(lease.attempt(),lease.runToken(),prefix+"manifest.json",sha(data));
    }
    private static ObjectNode edit(CaptionModels.Snapshot snapshot){var body=JSON.createObjectNode().put("base_revision",snapshot.revision());var cues=body.putArray("cues");for(var cue:snapshot.cues())cues.addObject().put("id",cue.id()).put("start_ms",cue.startMs()).put("end_ms",cue.endMs()).put("text",cue.text()).put("reviewed",cue.reviewed());return body;}
    private static String failure(Runnable action){RuntimeException error=assertThrows(RuntimeException.class,action::run);if(error instanceof JobException e)return e.getCode();if(error instanceof VideoApiException e)return e.getCode();throw new AssertionError(error);}
    private Fixture fixture(long start,long end,boolean two)throws Exception{
        var parent=parent();var reviews=app.getBean(TopicReviewService.class);var original=reviews.get(parent.job(),null);
        var edit=JSON.createObjectNode().put("base_revision",original.revision());var segments=edit.putArray("segments");segment(segments.addObject(),"topic-1",start,end);
        if(two)segment(segments.addObject(),"manual-two",2000,3000);
        var saved=reviews.save(parent.job(),edit);reviews.confirm(parent.job(),JSON.createObjectNode().put("revision",saved.revision()));
        var batch=exports.create(new ExportModels.CreateRequest(parent.job(),saved.revision(),two?List.of("topic-1","manual-two"):List.of("topic-1"),"h264-aac-v1"));
        publishExports(batch);return new Fixture(parent,exports.get(batch.id()));
    }
    private static void segment(ObjectNode segment,String id,long start,long end){segment.put("id",id).put("start_ms",start).put("end_ms",end).put("title","字幕测试片段").put("summary","合成字幕来源").put("enabled",true).putArray("keywords").add("字幕");}
    private Parent parent()throws Exception{
        byte[] header=ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var video=app.getBean(VideoService.class).upload(new MockMultipartFile("file","caption-synthetic.mp4","video/mp4",header));ownVideos.add(video.id());
        var job=jobs.create(new CreateJobRequest(video.id(),Map.of("analysis_mode","topics","visual_provider","none","text_model","qwen-plus","max_text_calls",0,"max_asr_submissions",0)),"caption-source-"+UUID.randomUUID());ownJobs.add(job.id());
        var utterances=JSON.createArrayNode();for(int i=1;i<=4;i++)utterances.addObject().put("utterance_id","u"+i).put("start_ms",(i-1)*1000).put("end_ms",i*1000).put("text","第"+i+"句原始转写").put("source_chunk_id","c0");
        var result=JSON.createObjectNode().put("schema_version","1.0").put("analysis_mode","topics").put("data_kind","model_result").put("job_id",job.id()).put("video_id",video.id()).put("analysis_generation",1).put("duration_ms",4000).put("transcript_version","synthetic").put("pipeline_version","topics-text-v1").put("review_status","DRAFT");result.set("utterances",utterances);result.putArray("frames");
        result.putObject("provenance").put("asr_model_id","paraformer-v2").put("text_model_id","qwen-plus").put("prompt_version","synthetic").put("config_hash","a".repeat(64));
        var topic=result.putArray("segments").addObject().put("segment_id","topic-1").put("title","合成字幕").put("summary","合成字幕来源").put("start_ms",0).put("end_ms",4000).put("start_utterance_id","u1").put("end_utterance_id","u4");topic.putArray("keywords").add("字幕");topic.putArray("audio_evidence_utterance_ids").add("u1").add("u2").add("u3").add("u4");
        for(var stage:job.stages()){String token=UUID.randomUUID().toString(),prefix="jobs/"+job.id()+"/stages/"+stage.id()+"/attempts/1/"+token+"/";var manifest=JSON.createObjectNode().put("schema_version",1).put("job_id",job.id()).put("video_id",video.id()).put("analysis_generation",1).put("stage_id",stage.id()).put("stage_type",stage.stageType()).put("attempt",1).put("run_token",token).put("input_sha256",video.contentHash()).put("data_kind","model_result").put("review_status","DRAFT");
            var files=new LinkedHashMap<String,byte[]>();if(stage.stageType().equals("ASR"))files.put("transcript",JSON.writeValueAsBytes(utterances));else if(stage.stageType().equals("MEDIA")){files.put("audio",new byte[]{1,2,3});files.put("media_info",JSON.writeValueAsBytes(Map.of("duration_ms",4000)));}else files.put("analysis_result",JSON.writeValueAsBytes(result));
            var refs=manifest.putArray("artifacts");for(var file:files.entrySet()){String key=prefix+file.getKey()+".json";write(key,file.getValue());refs.addObject().put("kind",file.getKey()).put("key",key).put("sha256",sha(file.getValue()));}
            byte[] bytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",bytes);jdbc.update("UPDATE job_stage SET status='SUCCEEDED',attempt=1,run_token=?,manifest_key=?,manifest_hash=? WHERE id=?",token,prefix+"manifest.json",sha(bytes),stage.id());}
        jdbc.update("UPDATE analysis_job SET status='REVIEW_READY' WHERE id=?",job.id());return new Parent(job.id(),video.id(),video.contentHash(),utterances);
    }
    private void publishExports(ExportModels.Snapshot batch)throws Exception{
        for(var clip:batch.clips()){
            var lease=claim(clip.stageId());var config=(Map<?,?>)lease.config().get("export");String prefix=prefix(lease);
            byte[] video={1,2,3,4};long duration=config.containsKey("duration_ms")?((Number)config.get("duration_ms")).longValue():((Number)config.get("end_ms")).longValue()-((Number)config.get("start_ms")).longValue();
            byte[] media=JSON.writeValueAsBytes(Map.of("duration_ms",duration,"video_codec","h264","audio_codec","aac","frame_rate",30));write(prefix+"clip.mp4",video);write(prefix+"media-info.json",media);
            var manifest=manifest(lease,"reviewed_export","CONFIRMED");manifest.put("stage_input_hash",lease.inputHash());
            for(String field:List.of("export_id","clip_id","snapshot_hash","segment_id","encoding_profile"))manifest.put(field,String.valueOf(config.get(field)));
            for(String field:List.of("approved_revision","start_ms","end_ms"))manifest.put(field,((Number)config.get(field)).longValue());
            if(config.containsKey("parts")){manifest.set("parts",JSON.valueToTree(config.get("parts")));manifest.put("duration_ms",duration).put("clip_request_id",String.valueOf(config.get("clip_request_id")));}
            var refs=manifest.putArray("artifacts");refs.addObject().put("kind","clip").put("key",prefix+"clip.mp4").put("sha256",sha(video));refs.addObject().put("kind","media_info").put("key",prefix+"media-info.json").put("sha256",sha(media));complete(lease,manifest);
        }
    }
    private ClaimResult claim(String stage){return jobs.claim(new ClaimRequest(stage,"proofread-synthetic-worker",UUID.randomUUID().toString()));}
    private static ObjectNode manifest(ClaimResult lease,String kind,String review){return JSON.createObjectNode().put("schema_version",1).put("job_id",lease.jobId()).put("video_id",lease.videoId()).put("analysis_generation",lease.generation()).put("stage_id",lease.stageId()).put("stage_type",lease.stageType()).put("attempt",lease.attempt()).put("run_token",lease.runToken()).put("input_sha256",lease.sourceHash()).put("data_kind",kind).put("review_status",review);}
    private void complete(ClaimResult lease,ObjectNode manifest)throws Exception{byte[] bytes=JSON.writeValueAsBytes(manifest);String key=prefix(lease)+"manifest.json";write(key,bytes);jobs.complete(lease.stageId(),new CompleteRequest(lease.attempt(),lease.runToken(),key,sha(bytes)));}
    private static String prefix(ClaimResult lease){return "jobs/"+lease.jobId()+"/stages/"+lease.stageId()+"/attempts/"+lease.attempt()+"/"+lease.runToken()+"/";}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void write(String key,byte[] bytes)throws Exception{Path path=root.resolve(key);Files.createDirectories(path.getParent());Files.write(path,bytes);}
    private HttpResponse<byte[]> send(String method,String path,Object body,String key)throws Exception{var request=HttpRequest.newBuilder(base.resolve(path));if(body!=null)request.header("Content-Type","application/json");if(key!=null)request.header("Idempotency-Key",key);return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());}
}
