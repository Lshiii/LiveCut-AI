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

    @Test void structuredModeHasItsOwnCapabilityWhileNoneKeepsLegacyWireAndFingerprints()throws Exception{
        var f=fixture();configureVisual();var session=session(f);String before=scopedState(f.job());
        var capability=send("GET","/api/agent-capabilities/evidence",null,null);assertEquals(200,capability.statusCode());assertEquals(JSON.readTree("{\"structured_evidence_supported\":true}"),JSON.readTree(capability.body()));assertEquals(before,scopedState(f.job()));
        var legacy=agents.createTurn(session.id(),JSON.valueToTree(Map.of("question","旧画面","max_text_calls",4,"visual_mode","frames","evidence_mode","none")),"evidence-legacy");
        assertNull(legacy.evidenceMode());assertFalse(JSON.valueToTree(legacy).has("evidence_mode"));assertEquals(legacy.id(),visualTurn(session.id(),"旧画面",4,"evidence-legacy").id());
        assertEquals(io.livecut.clips.ClipRequestService.sha("agent-turn\n"+session.id()+"\n旧画面\n4\nvisual_mode=frames"),jdbc.queryForObject("SELECT request_hash FROM agent_turn WHERE id=?",String.class,legacy.id()));
        assertFalse(((Map<?,?>)claimVisual(legacy).config().get("agent_turn")).containsKey("evidence_mode"));agents.cancel(legacy.id());
        for(Long focus:Arrays.asList(null,0L)){
            var request=evidenceTurn(session.id(),"分项问题",4,focus,"evidence-mode-"+focus);assertEquals(AgentEvidence.STAGE,request.stage().stageType());assertEquals("structured",request.evidenceMode());
            String material="agent-turn\n"+session.id()+"\n分项问题\n4\nvisual_mode=frames"+(focus==null?"":"\nfocus_timestamp_ms="+focus)+"\nevidence_mode=structured";
            assertEquals(io.livecut.clips.ClipRequestService.sha(material),jdbc.queryForObject("SELECT request_hash FROM agent_turn WHERE id=?",String.class,request.id()));
            var config=(Map<?,?>)claimEvidence(request).config().get("agent_turn");var expected=new HashSet<>(Set.of("session_id","question","duration_ms","turn_id","source_job_id","source_transcript_key","source_transcript_hash","source_analysis_key","source_analysis_hash","max_tool_steps","history","visual_mode","evidence_mode"));if(focus!=null)expected.add("focus_timestamp_ms");assertEquals(expected,config.keySet());assertEquals("structured",config.get("evidence_mode"));
            assertEquals("IDEMPOTENCY_CONFLICT",failure(()->visualTurn(session.id(),request.question(),4,"evidence-mode-"+focus)));agents.cancel(request.id());
        }
    }
    @Test void structuredInvalidModeInputsLeaveNoTurnStageOrBudgetAndOriginalModeRecoversFirst()throws Exception{
        var f=fixture();configureVisual();var session=session(f);String before=scopedState(f.job());
        for(Object mode:List.of(true,1,"unknown")){assertError(send("POST","/api/agent-sessions/"+session.id()+"/turns",Map.of("question","x","visual_mode","frames","evidence_mode",mode),UUID.randomUUID().toString()),400,"INVALID_AGENT_TURN");}
        var nullMode=JSON.createObjectNode().put("question","x").put("visual_mode","frames").putNull("evidence_mode");assertEquals("INVALID_AGENT_TURN",failure(()->agents.createTurn(session.id(),nullMode,"evidence-null")));
        assertEquals("INVALID_AGENT_TURN",failure(()->agents.createTurn(session.id(),JSON.valueToTree(Map.of("question","x","evidence_mode","structured")),"evidence-text")));assertEquals(before,scopedState(f.job()));
        var original=evidenceTurn(session.id(),"原模式",2,0L,"evidence-recover");org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredVisualModel","");jdbc.update("UPDATE analysis_job SET status='RUNNING' WHERE id=?",f.job());
        assertEquals(original.id(),evidenceTurn(session.id(),original.question(),2,0L,"evidence-recover").id());assertEquals(original.id(),agents.findTurnSubmission(session.id(),"evidence-recover").id());
        jdbc.update("UPDATE agent_turn SET evidence_mode='none' WHERE id=?",original.id());assertEquals("AGENT_SUBMISSION_INVALID",failure(()->agents.findTurnSubmission(session.id(),"evidence-recover")));jdbc.update("UPDATE agent_turn SET evidence_mode='structured' WHERE id=?",original.id());
    }
    @Test void evidenceCapabilityRejectsOldVisualWorkersAndOccupiesBothConservativeResources()throws Exception{
        var f=fixture();configureVisual();var request=evidenceTurn(session(f).id(),"分项执行器",1,null,"evidence-capacity");var workers=app.getBean(WorkerCapacityService.class);
        String old="evidence-old-"+UUID.randomUUID(),oldSession=UUID.randomUUID().toString();ownWorkers.add(old);workers.register(new WorkerModels.Registration(old,oldSession,List.of("AGENT_VISUAL",AgentFocus.STAGE,"SEGMENT"),2,f.job()));
        assertEquals("WORKER_CAPABILITY_MISMATCH",failure(()->jobs.claim(new ClaimRequest(request.stage().id(),old,UUID.randomUUID().toString(),f.job(),oldSession))));
        String worker="evidence-new-"+UUID.randomUUID(),sid=UUID.randomUUID().toString();ownWorkers.add(worker);workers.register(new WorkerModels.Registration(worker,sid,List.of(AgentEvidence.STAGE),1,f.job()));assertEquals("CLAIMED",jobs.claim(new ClaimRequest(request.stage().id(),worker,UUID.randomUUID().toString(),f.job(),sid)).outcome());
        var snapshot=workers.snapshot(f.job());for(String resource:List.of("TEXT","FFMPEG"))assertEquals(1,snapshot.resources().stream().filter(r->r.name().equals(resource)).findFirst().orElseThrow().inUse());
        var second=evidenceTurn(session(f).id(),"另一分项",1,null,"evidence-capacity-next");assertEquals("NO_CAPACITY",jobs.claim(new ClaimRequest(second.stage().id(),worker,UUID.randomUUID().toString(),f.job(),sid)).outcome());
    }
    @Test void evidenceKeepsOneFrozenVisualLedgerAndCanRecoverAtZeroRemainingBudget()throws Exception{
        var f=fixture();configureVisual();var request=evidenceTurn(session(f).id(),"分项账本",1,0L,"evidence-ledger");var lease=claimEvidence(request);
        var reserve=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"a".repeat(64),"evidence-decision","qwen-vl-synthetic","TEXT");assertEquals("RESERVED",calls.reserve(reserve).outcome());
        calls.update(reserve.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of()));calls.update(reserve.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"UNKNOWN_OUTCOME",null,Map.of()));
        assertEquals("EXISTING",calls.lookup(reserve).outcome());assertEquals("UNKNOWN_OUTCOME",calls.reserve(reserve).call().status());
        assertEquals("MODEL_BUDGET_EXHAUSTED",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"b".repeat(64),"evidence-retry","qwen-vl-synthetic","TEXT"))));
        assertEquals("MODEL_CONFIG_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"c".repeat(64),"evidence-fallback","deepseek-chat","TEXT"))));
        assertEquals("MODEL_KIND_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"d".repeat(64),"evidence-asr","paraformer-v2","ASR"))));
        assertEquals(0,jobs.get(f.job()).budget().textCallsReserved());assertEquals(1,agents.getTurn(request.id()).budget().textCallsReserved());jobs.complete(lease.stageId(),publishVisual(f,request,lease,a->structuredAnswer(a,f)));
        assertEquals("SUCCEEDED",agents.getTurn(request.id()).status());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.job()));
    }
    @Test void evidenceCompleteReadJpegNotesHistoryAndAnchoredOriginalsRetainBothFocusPaths()throws Exception{
        for(Long focus:Arrays.asList(null,0L)){
            var f=fixture();configureVisual();var request=evidenceTurn(session(f).id(),"分项原句画面",4,focus,"evidence-consume-"+focus);var lease=claimEvidence(request);var completion=publishVisual(f,request,lease,a->structuredAnswer(a,f));jobs.complete(lease.stageId(),completion);
            var result=agents.getTurn(request.id()).result();assertEquals("1.3",result.path("schema_version").asText());assertEquals(2,result.path("answer_blocks").size());assertEquals(focus!=null,result.has("focus_timestamp_ms"));String before=scopedState(f.job());
            assertEquals(200,send("GET","/api/agent-turns/"+request.id(),null,null).statusCode());assertTrue(agents.visualFrame(request.id(),"f0001").length>4);
            var notes=app.getBean(AgentNoteService.class);var preview=notes.preview(request.id());assertTrue(preview.markdown().contains("【画面观察 b01】"));assertTrue(preview.markdown().contains("【原句依据 b02】"));assertTrue(preview.markdown().contains("## 证据核查摘要"));assertTrue(preview.markdown().contains(AgentEvidenceReview.LIMITATION));var files=noteZip(notes.download(request.id()).bytes());var publicSource=JSON.readTree(files.get("source.json"));assertEquals("structured",publicSource.path("evidence_mode").asText());assertEquals(result.path("answer_blocks"),publicSource.path("answer_blocks"));assertFalse(publicSource.has("evidence_review"));assertTrue(files.containsKey("images/f0001.jpg"));assertEquals(before,scopedState(f.job()));
            var clips=app.getBean(io.livecut.clips.ClipRequestService.class);var selected=JSON.valueToTree(Map.of("turn_id",request.id(),"utterance_ids",List.of("u1")));var clip=clips.create(f.job(),new io.livecut.clips.ClipRequestModels.CreateRequest("保留原句",4,selected),"evidence-anchor-"+focus);assertEquals("ANCHOR_CLIP",clip.stage().stageType());assertNotNull(clips.get(clip.id()).sourceAnswer());
            assertEquals("INVALID_ANSWER_SOURCE",failure(()->clips.create(f.job(),new io.livecut.clips.ClipRequestModels.CreateRequest("画面不是原句",4,JSON.valueToTree(Map.of("turn_id",request.id(),"utterance_ids",List.of("f0001")))),"evidence-no-frame-"+focus)));
            var next=evidenceTurn(request.sessionId(),"后续分项问题",4,focus,"evidence-next-"+focus);var history=JSON.readTree(jdbc.queryForObject("SELECT history_json FROM agent_turn WHERE id=?",String.class,next.id()));assertTrue(history.get(0).path("answer").asText().contains("【画面观察 b01】"));agents.cancel(next.id());
            String prefix=attemptPrefix(lease);byte[] jpeg=Files.readAllBytes(root.resolve(prefix+"visual-frames/f0001.jpg"));Files.write(root.resolve(prefix+"visual-frames/f0001.jpg"),new byte[]{1,2,3,4});try{assertThrows(JobException.class,()->agents.visualFrame(request.id(),"f0001"));assertThrows(JobException.class,()->notes.preview(request.id()));}finally{Files.write(root.resolve(prefix+"visual-frames/f0001.jpg"),jpeg);}
        }
    }
    @Test void structuredCompletionRejectsDowngradesFreeTextAndExtraEvidenceWithoutPublishing()throws Exception{
        var f=fixture();configureVisual();var request=evidenceTurn(session(f).id(),"拒绝旁路",4,null,"evidence-invalid");var lease=claimEvidence(request);
        assertEquals("INVALID_AGENT_ANSWER",failure(()->jobs.complete(lease.stageId(),assertDoesNotThrow(()->publishVisual(f,request,lease,null)))));
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->a.put("answer",a.path("answer").asText()+"无依据结论"),a->a.put("evidence_mode","none"),a->a.putArray("answer_blocks"),a->((ObjectNode)a.path("visual_citations").get(0)).put("explanation","无关解释"),a->((ObjectNode)a.path("answer_blocks").get(1)).putArray("frame_ids").add("f0001"))){
            var completion=publishVisual(f,request,lease,a->{structuredAnswer(a,f);change.accept(a);});assertEquals("INVALID_AGENT_ANSWER",failure(()->jobs.complete(lease.stageId(),completion)));assertNull(agents.getTurn(request.id()).result());
        }
        jobs.complete(lease.stageId(),publishVisual(f,request,lease,a->structuredAnswer(a,f)));assertEquals("SUCCEEDED",agents.getTurn(request.id()).status());
    }
    private AgentModels.Turn evidenceTurn(String session,String question,int budget,Long focus,String key){var input=JSON.createObjectNode().put("question",question).put("max_text_calls",budget).put("visual_mode","frames").put("evidence_mode","structured");if(focus!=null)input.put("focus_timestamp_ms",focus);return agents.createTurn(session,input,key);}
    private ClaimResult claimEvidence(AgentModels.Turn turn){assertTrue(jobs.ready(AgentEvidence.STAGE,100,turn.jobId()).stream().anyMatch(s->s.id().equals(turn.stage().id())));return jobs.claim(new ClaimRequest(turn.stage().id(),"agent-synthetic",UUID.randomUUID().toString()));}
    private static void structuredAnswer(ObjectNode answer,Fixture f){
        answer.put("schema_version","1.3").put("evidence_mode","structured").put("answer","【画面观察 b01】\n合成测试画面。\n\n【原句依据 b02】\n修改前缀会使缓存失效");((ObjectNode)answer.path("provenance")).put("prompt_version",AgentEvidence.PROMPT);((ObjectNode)answer.path("visual_citations").get(0)).put("explanation","关联分项：b01");
        var blocks=answer.putArray("answer_blocks");var frame=blocks.addObject().put("block_id","b01").put("kind","FRAME").put("text","合成测试画面。");frame.putArray("utterance_ids");frame.putArray("frame_ids").add("f0001");var speech=blocks.addObject().put("block_id","b02").put("kind","SPEECH").put("text","修改前缀会使缓存失效");speech.putArray("utterance_ids").add("u1");speech.putArray("frame_ids");
        var tool=answer.withArray("tool_calls").addObject().put("step",2).put("tool_name","read_context");if(answer.has("focus_timestamp_ms"))tool.put("origin","MODEL");tool.putObject("arguments").put("utterance_id","u1").put("before",0).put("after",0);tool.putArray("result_frame_ids");tool.putArray("result_utterance_ids").add("u1");var citation=answer.withArray("citations").addObject();for(String field:List.of("utterance_id","start_ms","end_ms","text"))citation.set(field,f.utterances().get(0).path(field));
    }

    @Test void textNotePreviewAndDeterministicZipPreserveEvidenceWithoutAnyWrites()throws Exception{
        var f=fixture();var request=turn(session(f).id(),"请解释缓存失效",4,"note-text");var lease=claim(request);jobs.complete(lease.stageId(),publish(f,request,lease,null));String before=scopedState(f.job());
        var response=send("GET","/api/agent-turns/"+request.id()+"/note",null,null);assertEquals(200,response.statusCode());JsonNode preview=JSON.readTree(response.body());
        assertEquals(Set.of("schema_version","turn_id","session_id","job_id","video_id","source_hash","transcript_hash","result_hash","source_filename","answer_scope","citation_count","image_count","markdown","download_filename"),preview.propertyNames());
        assertEquals(1,preview.path("schema_version").intValue());assertEquals(request.id(),preview.path("turn_id").asText());assertEquals(request.sessionId(),preview.path("session_id").asText());assertEquals(f.hash(),preview.path("source_hash").asText());
        assertEquals(1,preview.path("citation_count").intValue());assertEquals(0,preview.path("image_count").intValue());assertEquals("agent-synthetic.mp4",preview.path("source_filename").asText());assertTrue(preview.path("markdown").asText().contains("修改前缀会使缓存失效"));
        var download=send("GET","/api/agent-turns/"+request.id()+"/note/download",null,null);assertEquals(200,download.statusCode());assertEquals("application/zip",download.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("attachment; filename=\"video-note-"+request.id()+".zip\"",download.headers().firstValue("Content-Disposition").orElseThrow());assertEquals(request.id(),download.headers().firstValue("X-LiveCut-Note-Turn-Id").orElseThrow());
        assertEquals(preview.path("result_hash").asText(),download.headers().firstValue("X-LiveCut-Note-Result-Hash").orElseThrow());assertEquals(download.body().length,download.headers().firstValueAsLong("Content-Length").orElseThrow());
        var files=noteZip(download.body());assertEquals(Set.of("note.md","source.json"),files.keySet());assertTrue(new String(files.get("note.md"),StandardCharsets.UTF_8).startsWith(preview.path("markdown").asText()));
        assertNotePublicOnly(JSON.readTree(files.get("source.json")));assertArrayEquals(download.body(),send("GET","/api/agent-turns/"+request.id()+"/note/download",null,null).body());assertEquals(before,scopedState(f.job()));
    }
    @Test void generalAndInsufficientNotesKeepTheirScopeAndDoNotInventTranscriptQuotes()throws Exception{
        var f=fixture();var owner=session(f);
        for(String scope:List.of("GENERAL","INSUFFICIENT")){
            var request=turn(owner.id(),"证据范围",4,"note-scope-"+scope);var lease=claim(request);jobs.complete(lease.stageId(),publish(f,request,lease,a->{a.put("answer_scope",scope).put("answer",scope.equals("GENERAL")?"这是通用知识补充。":"当前证据不足以回答。");a.putArray("citations");}));
            String before=scopedState(f.job());var preview=app.getBean(AgentNoteService.class).preview(request.id());assertEquals(scope,preview.answerScope());assertEquals(0,preview.citationCount());assertEquals(0,preview.imageCount());
            var files=noteZip(app.getBean(AgentNoteService.class).download(request.id()).bytes());assertFalse(new String(files.get("source.json"),StandardCharsets.UTF_8).contains("修改前缀会使缓存失效"));assertNotePublicOnly(JSON.readTree(files.get("source.json")));assertEquals(before,scopedState(f.job()));
        }
    }
    @Test void visualAndFocusNotesIncludeOnlyActuallyCitedImagesEvenWhenIdsHaveGaps()throws Exception{
        for(boolean focus:List.of(false,true)){
            var f=fixture();configureVisual();var owner=session(f);var request=focus?focusTurn(owner.id(),"当前画面",4,0,"note-focus"):visualTurn(owner.id(),"画面说明",4,"note-visual");var lease=focus?claimFocus(request):claimVisual(request);
            var complete=publishVisual(f,request,lease,a->{var frame=((ObjectNode)a.path("visual_evidence").get(0)).deepCopy();frame.put("frame_id","f0002").put("requested_ms",2000).put("timestamp_ms",2000).put("source_pts",2000);a.withArray("visual_evidence").add(frame);
                if(focus){var call=a.withArray("tool_calls").addObject().put("step",2).put("tool_name","inspect_frames").put("origin","MODEL");call.putObject("arguments").putArray("timestamps_ms").add(2000);call.putArray("result_utterance_ids");call.putArray("result_frame_ids").add("f0002");}
                else{((ObjectNode)a.path("tool_calls").get(0).path("arguments")).withArray("timestamps_ms").add(2000);((ObjectNode)a.path("tool_calls").get(0)).withArray("result_frame_ids").add("f0002");}
                a.putArray("visual_citations").addObject().put("frame_id","f0002").put("explanation","只引用第二张已看过的合成图");});
            String prefix=attemptPrefix(lease);byte[] jpeg=Files.readAllBytes(root.resolve(prefix+"visual-frames/f0001.jpg"));write(prefix+"visual-frames/f0002.jpg",jpeg);
            var manifest=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(complete.manifestKey())));manifest.withArray("artifacts").addObject().put("kind","agent_visual_frame").put("key",prefix+"visual-frames/f0002.jpg").put("sha256",sha(jpeg));byte[] sealed=JSON.writeValueAsBytes(manifest);write(complete.manifestKey(),sealed);jobs.complete(lease.stageId(),new CompleteRequest(lease.attempt(),lease.runToken(),complete.manifestKey(),sha(sealed)));
            String before=scopedState(f.job());var notes=app.getBean(AgentNoteService.class);var preview=notes.preview(request.id());var files=noteZip(notes.download(request.id()).bytes());assertEquals(0,preview.citationCount());assertEquals(1,preview.imageCount());
            assertEquals(Set.of("note.md","source.json","images/f0002.jpg"),files.keySet());assertArrayEquals(jpeg,files.get("images/f0002.jpg"));assertEquals(sha(jpeg),agents.getTurn(request.id()).result().path("visual_evidence").get(1).path("sha256").asText());
            String source=new String(files.get("source.json"),StandardCharsets.UTF_8);assertTrue(source.contains("f0002"));assertFalse(source.contains("f0001"));assertNotePublicOnly(JSON.readTree(files.get("source.json")));assertEquals(before,scopedState(f.job()));
            write(prefix+"visual-frames/f0001.jpg",new byte[]{1,2,3});assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->notes.download(request.id())));write(prefix+"visual-frames/f0001.jpg",jpeg);assertEquals(before,scopedState(f.job()));
        }
    }
    @Test void notesRejectQueuedRunningFailedCancelledAndNonVideoOwnersWithoutWrites()throws Exception{
        var f=fixture();var owner=session(f);var request=turn(owner.id(),"不可导出的状态",4,"note-status");var notes=app.getBean(AgentNoteService.class);
        for(String state:List.of("QUEUED","RUNNING","FAILED","CANCELLED")){
            jdbc.update("UPDATE agent_turn SET status=? WHERE id=?",state,request.id());String before=scopedState(f.job());assertEquals("AGENT_NOTE_NOT_READY",failure(()->notes.preview(request.id())));assertEquals("AGENT_NOTE_NOT_READY",failure(()->notes.download(request.id())));assertEquals(before,scopedState(f.job()));
        }
        jdbc.update("UPDATE agent_session SET kind='CAPTION',caption_export_id=?,caption_clip_id=? WHERE id=?",UUID.randomUUID().toString(),UUID.randomUUID().toString(),owner.id());String before=scopedState(f.job());var response=send("GET","/api/agent-turns/"+request.id()+"/note",null,null);assertError(response,404,"AGENT_SESSION_NOT_FOUND");assertFalse(new String(response.body(),StandardCharsets.UTF_8).contains(request.question()));assertEquals(before,scopedState(f.job()));
    }
    @Test void noteSnapshotRejectsTamperedStoredHashAnswerSourceAndManifestWithoutCachedFallback()throws Exception{
        var f=fixture();var request=turn(session(f).id(),"核对来源",4,"note-tamper");var lease=claim(request);var complete=publish(f,request,lease,null);jobs.complete(lease.stageId(),complete);var notes=app.getBean(AgentNoteService.class);var original=notes.preview(request.id());
        String resultHash=original.resultHash(),resultJson=jdbc.queryForObject("SELECT result_json FROM agent_turn WHERE id=?",String.class,request.id());jdbc.update("UPDATE agent_turn SET result_hash=? WHERE id=?","0".repeat(64),request.id());assertEquals("AGENT_RESULT_INVALID",failure(()->notes.preview(request.id())));assertEquals("AGENT_RESULT_INVALID",failure(()->notes.download(request.id())));jdbc.update("UPDATE agent_turn SET result_hash=? WHERE id=?",resultHash,request.id());
        var altered=(ObjectNode)JSON.readTree(resultJson);altered.put("answer","已被修改但仍然结构有效的答案");String changed=io.livecut.clips.ClipRequestService.canonicalJson(altered);jdbc.update("UPDATE agent_turn SET result_json=?,result_hash=? WHERE id=?",changed,io.livecut.clips.ClipRequestService.sha(changed),request.id());assertEquals("AGENT_RESULT_INVALID",failure(()->notes.download(request.id())));jdbc.update("UPDATE agent_turn SET result_json=?,result_hash=? WHERE id=?",resultJson,resultHash,request.id());
        String sourceKey=jdbc.queryForObject("SELECT manifest_key FROM job_stage WHERE job_id=? AND stage_type='ASR'",String.class,f.job());byte[] sourceBytes=Files.readAllBytes(root.resolve(sourceKey));write(sourceKey,"tamper".getBytes(StandardCharsets.UTF_8));assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->notes.preview(request.id())));write(sourceKey,sourceBytes);
        byte[] manifestBytes=Files.readAllBytes(root.resolve(complete.manifestKey()));write(complete.manifestKey(),"tamper".getBytes(StandardCharsets.UTF_8));assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->notes.download(request.id())));write(complete.manifestKey(),manifestBytes);
        String answerKey=attemptPrefix(lease)+"agent-answer.json";byte[] answerBytes=Files.readAllBytes(root.resolve(answerKey));write(answerKey,"tamper".getBytes(StandardCharsets.UTF_8));assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->notes.preview(request.id())));write(answerKey,answerBytes);
        String before=scopedState(f.job());assertEquals(original,notes.preview(request.id()));assertTrue(notes.download(request.id()).bytes().length>0);assertEquals(before,scopedState(f.job()));
    }
    @Test void visualNoteRejectsCorruptFrozenEvidenceAndDetachedSnapshotDoesNotRereadIt()throws Exception{
        var f=fixture();configureVisual();var request=visualTurn(session(f).id(),"截图原始字节",4,"note-jpeg");var lease=claimVisual(request);jobs.complete(lease.stageId(),publishVisual(f,request,lease,null));var notes=app.getBean(AgentNoteService.class);var snapshot=agents.noteSnapshot(request.id());assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        String image=attemptPrefix(lease)+"visual-frames/f0001.jpg";byte[] jpeg=Files.readAllBytes(root.resolve(image));var imageView=snapshot.images();imageView.get("f0001")[0]=0;((ObjectNode)snapshot.result()).put("answer","mutated detached caller view");assertFalse(snapshot.result().path("answer").asText().contains("mutated"));assertArrayEquals(jpeg,snapshot.images().get("f0001"));
        write(image,new byte[]{1,2,3});String before=scopedState(f.job());assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->notes.preview(request.id())));assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->notes.download(request.id())));assertEquals(before,scopedState(f.job()));assertTrue(AgentNoteRenderer.render(snapshot).sourceJson().length>0);write(image,jpeg);assertEquals(1,notes.preview(request.id()).imageCount());
    }
    private static Map<String,byte[]> noteZip(byte[] bytes)throws Exception{
        Map<String,byte[]> result=new LinkedHashMap<>();try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertEquals(java.time.LocalDateTime.of(1980,1,1,0,0),entry.getTimeLocal());assertFalse(entry.isDirectory());assertNull(result.put(entry.getName(),zip.readAllBytes()));}}
        return result;
    }
    private static void assertNotePublicOnly(JsonNode source){String text=source.toString();for(String privateField:List.of("run_token","manifest_key","source_key","transcript_key","analysis_key","provider_response","operation_key","idempotency_key","tool_calls"))assertFalse(text.contains(privateField));}

    @Test void focusCapabilityIsSeparateReadOnlyAndZeroIsAnImmutableRequestIdentity()throws Exception{
        var f=fixture();configureVisual();var session=session(f);String before=scopedState(f.job());
        var capability=send("GET","/api/agent-capabilities/focus",null,null);assertEquals(200,capability.statusCode());assertEquals(Set.of("focus_timestamp_supported"),JSON.readTree(capability.body()).propertyNames());assertTrue(JSON.readTree(capability.body()).path("focus_timestamp_supported").booleanValue());assertEquals(5,JSON.readTree(send("GET","/api/agent-capabilities",null,null).body()).size());assertEquals(before,scopedState(f.job()));
        var request=focusTurn(session.id(),"这一层是什么",1,0,"focus-zero");assertEquals(0L,request.focusTimestampMs());assertEquals(AgentFocus.STAGE,request.stage().stageType());assertEquals(0,JSON.valueToTree(request).path("focus_timestamp_ms").intValue());
        String expected=io.livecut.clips.ClipRequestService.sha("agent-turn\n"+session.id()+"\n这一层是什么\n1\nvisual_mode=frames\nfocus_timestamp_ms=0");assertEquals(expected,jdbc.queryForObject("SELECT request_hash FROM agent_turn WHERE id=?",String.class,request.id()));
        org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredVisualModel","");assertEquals(request.id(),focusTurn(session.id(),request.question(),1,0,"focus-zero").id());assertEquals(request.id(),agents.findTurnSubmission(session.id(),"focus-zero").id());assertEquals("IDEMPOTENCY_CONFLICT",failure(()->focusTurn(session.id(),request.question(),1,1,"focus-zero")));assertEquals("IDEMPOTENCY_CONFLICT",failure(()->visualTurn(session.id(),request.question(),1,"focus-zero")));
        agents.cancel(request.id());assertEquals(0L,agents.findTurnSubmission(session.id(),"focus-zero").focusTimestampMs());assertEquals("CANCELLED",agents.findTurnSubmission(session.id(),"focus-zero").status());
    }
    @Test void focusRejectsInvalidTypesNoneAndTheFrozenVideoEndWithoutCreatingWork()throws Exception{
        var f=fixture();configureVisual();var session=session(f);String before=scopedState(f.job());
        for(String value:List.of("null","true","0.0","-1","9007199254740992","4000","4001","\"0\"")){
            var input=JSON.readTree("{\"question\":\"图\",\"visual_mode\":\"frames\",\"focus_timestamp_ms\":"+value+"}");assertEquals("INVALID_AGENT_TURN",failure(()->agents.createTurn(session.id(),input,"invalid-focus")));
        }
        for(String mode:List.of("none","")){var body=JSON.createObjectNode().put("question","图").put("focus_timestamp_ms",0);if(!mode.isEmpty())body.put("visual_mode",mode);assertEquals("INVALID_AGENT_TURN",failure(()->agents.createTurn(session.id(),body,"focus-none")));}
        assertEquals(before,scopedState(f.job()));assertEquals(3999L,focusTurn(session.id(),"最后一帧",1,3999,"focus-last").focusTimestampMs());
    }
    @Test void omittedFocusRetainsThePreviousVisualFingerprintSnapshotAndClaimShape()throws Exception{
        var f=fixture();configureVisual();var session=session(f);var request=visualTurn(session.id(),"原来的视觉问题",4,"focus-omitted");assertNull(request.focusTimestampMs());assertFalse(JSON.valueToTree(request).has("focus_timestamp_ms"));
        assertEquals(io.livecut.clips.ClipRequestService.sha("agent-turn\n"+session.id()+"\n原来的视觉问题\n4\nvisual_mode=frames"),jdbc.queryForObject("SELECT request_hash FROM agent_turn WHERE id=?",String.class,request.id()));
        var config=(Map<?,?>)claimVisual(request).config().get("agent_turn");assertEquals(Set.of("session_id","question","duration_ms","turn_id","source_job_id","source_transcript_key","source_transcript_hash","source_analysis_key","source_analysis_hash","max_tool_steps","history","visual_mode"),config.keySet());assertFalse(config.containsKey("focus_timestamp_ms"));
    }
    @Test void visualWorkerWithoutFocusCapabilityCannotClaimTheNewDualResourceStage()throws Exception{
        var f=fixture();configureVisual();var request=focusTurn(session(f).id(),"这一层",1,0,"focus-capacity");var workers=app.getBean(WorkerCapacityService.class);String worker="focus-old-"+UUID.randomUUID(),sid=UUID.randomUUID().toString();ownWorkers.add(worker);workers.register(new WorkerModels.Registration(worker,sid,List.of("AGENT_VISUAL","SEGMENT"),2,f.job()));
        assertEquals("WORKER_CAPABILITY_MISMATCH",failure(()->jobs.claim(new ClaimRequest(request.stage().id(),worker,UUID.randomUUID().toString(),f.job(),sid))));String fresh="focus-new-"+UUID.randomUUID(),newSession=UUID.randomUUID().toString();ownWorkers.add(fresh);workers.register(new WorkerModels.Registration(fresh,newSession,List.of(AgentFocus.STAGE),1,f.job()));
        assertEquals("CLAIMED",jobs.claim(new ClaimRequest(request.stage().id(),fresh,UUID.randomUUID().toString(),f.job(),newSession)).outcome());var snapshot=workers.snapshot(f.job());for(String name:List.of("TEXT","FFMPEG"))assertEquals(1,snapshot.resources().stream().filter(r->r.name().equals(name)).findFirst().orElseThrow().inUse());
    }
    @Test void focusedAnswerPersistsActualJpegAndCanAnchorOnlyItsRealASRCitation()throws Exception{
        var f=fixture();configureVisual();var request=focusTurn(session(f).id(),"图与原句",3,0,"focus-answer");var lease=claimFocus(request);assertEquals(0L,((Map<?,?>)lease.config().get("agent_turn")).get("focus_timestamp_ms"));assertEquals(4,((Map<?,?>)lease.config().get("agent_turn")).get("max_tool_steps"));
        var complete=publishVisual(f,request,lease,a->{var tool=a.withArray("tool_calls").addObject().put("step",2).put("tool_name","read_context").put("origin","MODEL");tool.putObject("arguments").put("utterance_id","u1").put("before",0).put("after",0);tool.putArray("result_frame_ids");tool.putArray("result_utterance_ids").add("u1");var citation=a.withArray("citations").addObject();for(String field:List.of("utterance_id","start_ms","end_ms","text"))citation.set(field,f.utterances().get(0).path(field));});jobs.complete(lease.stageId(),complete);var result=agents.getTurn(request.id()).result();assertEquals("1.2",result.path("schema_version").asText());assertEquals(0,result.path("focus_timestamp_ms").intValue());assertEquals("USER_FOCUS",result.path("tool_calls").get(0).path("origin").asText());assertTrue(agents.visualFrame(request.id(),"f0001").length>4);
        var clips=app.getBean(io.livecut.clips.ClipRequestService.class);var selected=JSON.valueToTree(Map.of("turn_id",request.id(),"utterance_ids",List.of("u1")));var clip=clips.create(f.job(),new io.livecut.clips.ClipRequestModels.CreateRequest("保留真实原句",4,selected),"focus-anchor");assertEquals("ANCHOR_CLIP",clip.stage().stageType());assertNotNull(clips.get(clip.id()).sourceAnswer());assertEquals("INVALID_ANSWER_SOURCE",failure(()->clips.create(f.job(),new io.livecut.clips.ClipRequestModels.CreateRequest("位置不是锚点",4,JSON.valueToTree(Map.of("turn_id",request.id(),"utterance_ids",List.of("f0001")))),"focus-no-frame-anchor")));
    }
    @Test void focusedResultCannotForgetChangeOrPretendToObserveTheUserLocator()throws Exception{
        var f=fixture();configureVisual();var request=focusTurn(session(f).id(),"当前图",2,0,"focus-validation");var lease=claimFocus(request);
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->a.remove("focus_timestamp_ms"),a->a.put("focus_timestamp_ms",1),a->a.put("schema_version","1.1"),a->((ObjectNode)a.path("provenance")).put("prompt_version","video-agent-visual-v1"),a->((ObjectNode)a.path("tool_calls").get(0)).remove("origin"),a->((ObjectNode)a.path("tool_calls").get(0)).put("origin","MODEL"),a->((ObjectNode)a.path("tool_calls").get(0)).putArray("result_frame_ids"),a->((ObjectNode)a.path("tool_calls").get(0).path("arguments")).putArray("timestamps_ms").add(1),a->a.putArray("tool_calls"),a->{var next=a.withArray("tool_calls").addObject().put("step",2).put("tool_name","inspect_frames").put("origin","USER_FOCUS");next.putObject("arguments").putArray("timestamps_ms").add(0);next.putArray("result_frame_ids");next.putArray("result_utterance_ids");})){
            var complete=publishVisual(f,request,lease,change);assertEquals("INVALID_AGENT_ANSWER",failure(()->jobs.complete(lease.stageId(),complete)));assertNull(agents.getTurn(request.id()).result());
        }
        jobs.complete(lease.stageId(),publishVisual(f,request,lease,null));
    }
    @Test void focusUsesTheSameSingleTextReservationAndCanLookupItAfterBudgetIsExhausted()throws Exception{
        var f=fixture();configureVisual();var request=focusTurn(session(f).id(),"首帧",1,0,"focus-ledger");var lease=claimFocus(request);var reserve=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"a".repeat(64),"focus-first-image","qwen-vl-synthetic","TEXT");assertEquals("RESERVED",calls.reserve(reserve).outcome());assertEquals(1,agents.getTurn(request.id()).budget().textCallsReserved());
        calls.update(reserve.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"DISPATCHING",null,Map.of()));calls.update(reserve.requestId(),new UpdateRequest(lease.stageId(),lease.attempt(),lease.runToken(),"UNKNOWN_OUTCOME",null,Map.of()));assertEquals("EXISTING",calls.lookup(reserve).outcome());assertEquals("UNKNOWN_OUTCOME",calls.lookup(reserve).call().status());assertEquals("UNKNOWN_OUTCOME",calls.reserve(reserve).call().status());
        assertEquals("MODEL_BUDGET_EXHAUSTED",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"b".repeat(64),"focus-retry","qwen-vl-synthetic","TEXT"))));assertEquals("MODEL_KIND_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"c".repeat(64),"focus-asr","paraformer-v2","ASR"))));assertEquals(0,jobs.get(f.job()).budget().textCallsReserved());
        var complete=publishVisual(f,request,lease,null);jobs.complete(lease.stageId(),complete);assertEquals("SUCCEEDED",agents.getTurn(request.id()).status());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.job()));
    }
    @Test void changedStoredFocusCannotBeRecognizedByTheOriginalSubmissionKey()throws Exception{
        var f=fixture();configureVisual();var session=session(f);var request=focusTurn(session.id(),"位置",2,0,"focus-corrupt");jdbc.update("UPDATE agent_turn SET focus_timestamp_ms=1 WHERE id=?",request.id());assertEquals("AGENT_SUBMISSION_INVALID",failure(()->agents.findTurnSubmission(session.id(),"focus-corrupt")));jdbc.update("UPDATE agent_turn SET focus_timestamp_ms=0 WHERE id=?",request.id());assertEquals(0L,agents.findTurnSubmission(session.id(),"focus-corrupt").focusTimestampMs());
    }

    @Test void visualConfigurationIsReadOnlyAndOldNoneFingerprintsRemainIdentical()throws Exception{
        org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredVisualModel","");
        var f=fixture();var session=session(f);String before=scopedState(f.job());
        var unavailable=send("GET","/api/agent-capabilities",null,null);assertEquals(200,unavailable.statusCode());assertTrue(JSON.readTree(unavailable.body()).path("visual_mode").isNull());assertEquals(before,scopedState(f.job()));
        assertEquals("AGENT_VISUAL_NOT_CONFIGURED",failure(()->visualTurn(session.id(),"看架构图",4,"visual-disabled")));assertEquals(before,scopedState(f.job()));
        var legacy=turn(session.id(),"原问题",4,"legacy-none");assertEquals("none",legacy.visualMode());assertEquals("SEGMENT",legacy.stage().stageType());
        var explicit=agents.createTurn(session.id(),JSON.valueToTree(Map.of("question","原问题","max_text_calls",4,"visual_mode","none")),"legacy-none");assertEquals(legacy.id(),explicit.id());
        assertEquals(io.livecut.clips.ClipRequestService.sha("agent-turn\n"+session.id()+"\n原问题\n4"),jdbc.queryForObject("SELECT request_hash FROM agent_turn WHERE id=?",String.class,legacy.id()));
        for(Object mode:List.of("video",true,1))assertEquals("INVALID_AGENT_TURN",failure(()->agents.createTurn(session.id(),JSON.valueToTree(Map.of("question","x","visual_mode",mode)),"invalid-mode")));
    }
    @Test void visualModelAndStageFreezeWhileOriginalKeyResolverIgnoresLaterConfiguration()throws Exception{
        var f=fixture();var session=session(f);configureVisual();org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredFallbackModels","deepseek-chat");
        var request=visualTurn(session.id(),"看图",4,"visual-frozen");assertEquals("frames",request.visualMode());assertEquals("AGENT_VISUAL",request.stage().stageType());
        org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredVisualModel","");assertEquals(request.id(),visualTurn(session.id(),"看图",4,"visual-frozen").id());assertEquals(request.id(),agents.findTurnSubmission(session.id(),"visual-frozen").id());
        assertEquals("IDEMPOTENCY_CONFLICT",failure(()->turn(session.id(),"看图",4,"visual-frozen")));var lease=claimVisual(request);
        assertEquals("qwen-vl-synthetic",lease.config().get("text_model"));assertEquals(List.of(),lease.config().get("text_fallback_models"));assertEquals("frames",((Map<?,?>)lease.config().get("agent_turn")).get("visual_mode"));assertEquals("none",lease.config().get("visual_provider"));
        agents.cancel(request.id());assertEquals("CANCELLED",agents.findTurnSubmission(session.id(),"visual-frozen").status());org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredFallbackModels","");
    }
    @Test void legacyWorkerCannotClaimVisualAndVisualStageOccupiesBothResourceSlots()throws Exception{
        var f=fixture();configureVisual();var request=visualTurn(session(f).id(),"图示",4,"visual-capability");
        var workers=app.getBean(WorkerCapacityService.class);String old="old-"+UUID.randomUUID(),oldSession=UUID.randomUUID().toString();ownWorkers.add(old);workers.register(new WorkerModels.Registration(old,oldSession,List.of("SEGMENT","MEDIA"),2,f.job()));
        assertEquals("WORKER_CAPABILITY_MISMATCH",failure(()->jobs.claim(new ClaimRequest(request.stage().id(),old,UUID.randomUUID().toString(),f.job(),oldSession))));
        String worker="vl-"+UUID.randomUUID(),sid=UUID.randomUUID().toString();ownWorkers.add(worker);workers.register(new WorkerModels.Registration(worker,sid,List.of("AGENT_VISUAL","SEGMENT","MEDIA"),2,f.job()));
        var lease=jobs.claim(new ClaimRequest(request.stage().id(),worker,UUID.randomUUID().toString(),f.job(),sid));assertEquals("CLAIMED",lease.outcome());
        var snapshot=workers.snapshot(f.job());assertEquals(1,snapshot.resources().stream().filter(r->r.name().equals("TEXT")).findFirst().orElseThrow().inUse());assertEquals(1,snapshot.resources().stream().filter(r->r.name().equals("FFMPEG")).findFirst().orElseThrow().inUse());
        assertEquals("RESOURCE_BUSY",snapshot.stages().stream().filter(s->s.stageType().equals("AGENT_VISUAL")).findFirst().orElseThrow().reason());
        var second=visualTurn(session(f).id(),"另页图示",4,"visual-capacity-second");assertEquals("NO_CAPACITY",jobs.claim(new ClaimRequest(second.stage().id(),worker,UUID.randomUUID().toString(),f.job(),sid)).outcome());
    }
    @Test void visualCompletionUsesTheOriginalTextLedgerAndFiniteTurnBudget()throws Exception{
        var f=fixture();configureVisual();var request=visualTurn(session(f).id(),"看图",1,"visual-ledger");var lease=claimVisual(request);
        var reserve=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"a".repeat(64),"visual-plan-1","qwen-vl-synthetic","TEXT");assertEquals("RESERVED",calls.reserve(reserve).outcome());assertEquals("EXISTING",calls.lookup(reserve).outcome());assertEquals("EXISTING",calls.reserve(reserve).outcome());
        assertEquals("MODEL_BUDGET_EXHAUSTED",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"b".repeat(64),"visual-retry-2","qwen-vl-synthetic","TEXT"))));
        assertEquals("MODEL_CONFIG_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"c".repeat(64),"text-fallback","deepseek-chat","TEXT"))));
        assertEquals("MODEL_KIND_CONFLICT",failure(()->calls.reserve(new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),UUID.randomUUID().toString(),"d".repeat(64),"asr","paraformer-v2","ASR"))));
        assertEquals(1,agents.getTurn(request.id()).budget().textCallsReserved());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=? AND kind='TEXT'",Integer.class,f.job()));assertEquals(0,jobs.get(f.job()).budget().textCallsReserved());
    }
    @Test void actualSyntheticJpegPersistsAndDownloadsOnlyFromItsSuccessfulFencedTurn()throws Exception{
        var f=fixture();configureVisual();var request=visualTurn(session(f).id(),"图里有什么",4,"visual-jpeg");assertEquals("AGENT_VISUAL_FRAME_NOT_FOUND",failure(()->agents.visualFrame(request.id(),"f0001")));var lease=claimVisual(request);
        var completion=publishVisual(f,request,lease,null);jobs.complete(lease.stageId(),completion);var answer=agents.getTurn(request.id()).result();assertEquals("1.1",answer.path("schema_version").asText());
        var response=send("GET","/api/agent-turns/"+request.id()+"/visual-evidence/f0001/content",null,null);assertEquals(200,response.statusCode());assertEquals("image/jpeg",response.headers().firstValue("Content-Type").orElseThrow());assertEquals(answer.path("visual_evidence").get(0).path("sha256").asText(),sha(response.body()));
        assertEquals("AGENT_VISUAL_FRAME_NOT_FOUND",failure(()->agents.visualFrame(request.id(),"f0002")));String prefix=attemptPrefix(lease);byte[] original=Files.readAllBytes(root.resolve(prefix+"visual-frames/f0001.jpg"));Files.write(root.resolve(prefix+"visual-frames/f0001.jpg"),new byte[]{1,2,3});assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->agents.visualFrame(request.id(),"f0001")));Files.write(root.resolve(prefix+"visual-frames/f0001.jpg"),original);
        String hash=jdbc.queryForObject("SELECT result_hash FROM agent_turn WHERE id=?",String.class,request.id());jdbc.update("UPDATE agent_turn SET result_hash=? WHERE id=?","0".repeat(64),request.id());assertEquals("AGENT_RESULT_INVALID",failure(()->agents.visualFrame(request.id(),"f0001")));jdbc.update("UPDATE agent_turn SET result_hash=? WHERE id=?",hash,request.id());
        String asr=jdbc.queryForObject("SELECT manifest_key FROM job_stage WHERE job_id=? AND stage_type='ASR'",String.class,f.job());byte[] asrBytes=Files.readAllBytes(root.resolve(asr));Files.writeString(root.resolve(asr),"tamper");assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->agents.visualFrame(request.id(),"f0001")));Files.write(root.resolve(asr),asrBytes);
        assertArrayEquals(original,agents.visualFrame(request.id(),"f0001"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.job()));
    }
    @Test void invalidVisualMetadataJpegDimensionsAndExtraFilesCannotPublish()throws Exception{
        var f=fixture();configureVisual();var request=visualTurn(session(f).id(),"看图",4,"visual-invalid");var lease=claimVisual(request);
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->a.put("schema_version","1.0"),a->((ObjectNode)a.path("visual_evidence").get(0)).put("timestamp_ms",999),a->((ObjectNode)a.path("visual_evidence").get(0)).put("sha256","0".repeat(64)),a->((ObjectNode)a.path("visual_evidence").get(0)).put("bytes",4),a->((ObjectNode)a.path("visual_evidence").get(0)).put("width",319),a->a.putArray("visual_evidence"),a->((ObjectNode)a.path("tool_calls").get(0)).putArray("result_frame_ids"))){var completion=publishVisual(f,request,lease,change);assertThrows(JobException.class,()->jobs.complete(lease.stageId(),completion));assertNull(agents.getTurn(request.id()).result());}
        var completion=publishVisual(f,request,lease,null);String prefix=attemptPrefix(lease);ObjectNode manifest=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(completion.manifestKey())));manifest.withArray("artifacts").addObject().put("kind","agent_visual_frame").put("key",prefix+"visual-frames/f0002.jpg").put("sha256",sha(Files.readAllBytes(root.resolve(prefix+"visual-frames/f0001.jpg"))));write(prefix+"visual-frames/f0002.jpg",Files.readAllBytes(root.resolve(prefix+"visual-frames/f0001.jpg")));byte[] altered=JSON.writeValueAsBytes(manifest);write(completion.manifestKey(),altered);var extra=new CompleteRequest(lease.attempt(),lease.runToken(),completion.manifestKey(),sha(altered));assertEquals("AGENT_VISUAL_EVIDENCE_INVALID",failure(()->jobs.complete(lease.stageId(),extra)));
    }
    @Test void mixedVisualAnswerCanAnchorOnlyItsActualASRCitations()throws Exception{
        var f=fixture();configureVisual();var request=visualTurn(session(f).id(),"说明图和原句",4,"visual-anchor");var lease=claimVisual(request);var completion=publishVisual(f,request,lease,a->{var t=a.withArray("tool_calls").addObject().put("step",2).put("tool_name","read_context");t.putObject("arguments").put("utterance_id","u1").put("before",0).put("after",0);t.putArray("result_utterance_ids").add("u1");t.putArray("result_frame_ids");var c=a.withArray("citations").addObject();for(String field:List.of("utterance_id","start_ms","end_ms","text"))c.set(field,f.utterances().get(0).path(field));});jobs.complete(lease.stageId(),completion);
        var clips=app.getBean(io.livecut.clips.ClipRequestService.class);var selected=JSON.valueToTree(Map.of("turn_id",request.id(),"utterance_ids",List.of("u1")));var clip=clips.create(f.job(),new io.livecut.clips.ClipRequestModels.CreateRequest("保留原句",4,selected),"visual-anchor-clip");assertEquals("ANCHOR_CLIP",clip.stage().stageType());
        assertEquals("INVALID_ANSWER_SOURCE",failure(()->clips.create(f.job(),new io.livecut.clips.ClipRequestModels.CreateRequest("假画面锚点",4,JSON.valueToTree(Map.of("turn_id",request.id(),"utterance_ids",List.of("f0001")))),"visual-no-fake-anchor")));
    }
    @Test void severalFrameArtifactsUseTheSameKindButRemainOneToOneWithObservationMetadata()throws Exception{
        var f=fixture();configureVisual();var request=visualTurn(session(f).id(),"两张图",4,"visual-multiple");var lease=claimVisual(request);var complete=publishVisual(f,request,lease,a->{var frame=((ObjectNode)a.path("visual_evidence").get(0)).deepCopy();frame.put("frame_id","f0002").put("requested_ms",2000).put("timestamp_ms",2000).put("source_pts",2000);a.withArray("visual_evidence").add(frame);((ObjectNode)a.path("tool_calls").get(0).path("arguments")).withArray("timestamps_ms").add(2000);((ObjectNode)a.path("tool_calls").get(0)).withArray("result_frame_ids").add("f0002");});
        String prefix=attemptPrefix(lease);byte[] jpeg=Files.readAllBytes(root.resolve(prefix+"visual-frames/f0001.jpg"));write(prefix+"visual-frames/f0002.jpg",jpeg);var manifest=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(complete.manifestKey())));manifest.withArray("artifacts").addObject().put("kind","agent_visual_frame").put("key",prefix+"visual-frames/f0002.jpg").put("sha256",sha(jpeg));byte[] bytes=JSON.writeValueAsBytes(manifest);write(complete.manifestKey(),bytes);jobs.complete(lease.stageId(),new CompleteRequest(lease.attempt(),lease.runToken(),complete.manifestKey(),sha(bytes)));assertArrayEquals(jpeg,agents.visualFrame(request.id(),"f0002"));assertEquals(2,agents.getTurn(request.id()).result().path("visual_evidence").size());
    }
    @Test void correctHashesCannotMakeNonJpegBytesIntoVisualEvidence()throws Exception{
        var f=fixture();configureVisual();var request=visualTurn(session(f).id(),"图像字节",4,"visual-invalid-jpeg");var lease=claimVisual(request);var complete=publishVisual(f,request,lease,null);String prefix=attemptPrefix(lease);byte[] fake={(byte)255,(byte)216,1,2,3,4,(byte)255,(byte)217};write(prefix+"visual-frames/f0001.jpg",fake);
        var answer=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(prefix+"agent-answer.json")));((ObjectNode)answer.path("visual_evidence").get(0)).put("sha256",sha(fake)).put("bytes",fake.length);byte[] answerBytes=JSON.writeValueAsBytes(answer);write(prefix+"agent-answer.json",answerBytes);
        var manifest=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(complete.manifestKey())));for(var artifact:manifest.path("artifacts"))((ObjectNode)artifact).put("sha256",artifact.path("kind").asText().equals("agent_answer")?sha(answerBytes):sha(fake));byte[] bytes=JSON.writeValueAsBytes(manifest);write(complete.manifestKey(),bytes);String manifestHash=sha(bytes);assertEquals("AGENT_VISUAL_EVIDENCE_INVALID",failure(()->jobs.complete(lease.stageId(),new CompleteRequest(lease.attempt(),lease.runToken(),complete.manifestKey(),manifestHash))));assertNull(agents.getTurn(request.id()).result());
    }
    private void configureVisual(){org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredVisualModel","qwen-vl-synthetic");org.springframework.test.util.ReflectionTestUtils.setField(agents,"configuredFallbackModels","");}
    private AgentModels.Turn visualTurn(String session,String question,int budget,String key){return agents.createTurn(session,JSON.valueToTree(Map.of("question",question,"max_text_calls",budget,"visual_mode","frames")),key);}
    private AgentModels.Turn focusTurn(String session,String question,int budget,long focus,String key){return agents.createTurn(session,JSON.valueToTree(Map.of("question",question,"max_text_calls",budget,"visual_mode","frames","focus_timestamp_ms",focus)),key);}
    private ClaimResult claimFocus(AgentModels.Turn turn){assertTrue(jobs.ready(AgentFocus.STAGE,100,turn.jobId()).stream().anyMatch(s->s.id().equals(turn.stage().id())));return jobs.claim(new ClaimRequest(turn.stage().id(),"agent-synthetic",UUID.randomUUID().toString()));}
    private ClaimResult claimVisual(AgentModels.Turn turn){assertTrue(jobs.ready("AGENT_VISUAL",100,turn.jobId()).stream().anyMatch(s->s.id().equals(turn.stage().id())));return jobs.claim(new ClaimRequest(turn.stage().id(),"agent-synthetic",UUID.randomUUID().toString()));}
    private static String attemptPrefix(ClaimResult lease){return "jobs/"+lease.jobId()+"/stages/"+lease.stageId()+"/attempts/"+lease.attempt()+"/"+lease.runToken()+"/";}
    private CompleteRequest publishVisual(Fixture f,AgentModels.Turn turn,ClaimResult lease,Consumer<ObjectNode> change)throws Exception{
        var answer=JSON.createObjectNode().put("schema_version","1.1").put("session_id",turn.sessionId()).put("turn_id",turn.id()).put("job_id",f.job()).put("video_id",f.video()).put("source_hash",f.hash()).put("transcript_hash",String.valueOf(((Map<?,?>)lease.config().get("agent_turn")).get("source_transcript_hash"))).put("question",turn.question()).put("answer","合成截图是测试用色块，未调用真实模型。").put("answer_scope","VIDEO");answer.putArray("citations");answer.putNull("suggested_clip_instruction");answer.putObject("provenance").put("text_model_id","qwen-vl-synthetic").put("prompt_version","video-agent-visual-v1").put("retrieval_mode","none");
        var tool=answer.putArray("tool_calls").addObject().put("step",1).put("tool_name","inspect_frames");tool.putObject("arguments").putArray("timestamps_ms").add(1000);tool.putArray("result_utterance_ids");tool.putArray("result_frame_ids").add("f0001");
        var image=new java.awt.image.BufferedImage(320,180,java.awt.image.BufferedImage.TYPE_INT_RGB);var output=new java.io.ByteArrayOutputStream();assertTrue(javax.imageio.ImageIO.write(image,"JPEG",output));byte[] jpeg=output.toByteArray();String prefix=attemptPrefix(lease);write(prefix+"visual-frames/f0001.jpg",jpeg);
        answer.putArray("visual_evidence").addObject().put("frame_id","f0001").put("requested_ms",1000).put("timestamp_ms",1000).put("source_pts",1000).put("source_time_base","1/1000").put("sha256",sha(jpeg)).put("bytes",jpeg.length).put("width",320).put("height",180);answer.putArray("visual_citations").addObject().put("frame_id","f0001").put("explanation","合成色块截图");
        if(turn.focusTimestampMs()!=null){long focus=turn.focusTimestampMs();answer.put("schema_version","1.2").put("focus_timestamp_ms",focus);((ObjectNode)answer.path("provenance")).put("prompt_version","video-agent-visual-focus-v1");tool.put("origin","USER_FOCUS");tool.putObject("arguments").putArray("timestamps_ms").add(focus);((ObjectNode)answer.path("visual_evidence").get(0)).put("requested_ms",focus).put("timestamp_ms",focus).put("source_pts",focus);}
        if(change!=null)change.accept(answer);
        byte[] bytes=JSON.writeValueAsBytes(answer);write(prefix+"agent-answer.json",bytes);var manifest=manifest(lease.jobId(),lease.videoId(),lease.stageId(),lease.stageType(),lease.attempt(),lease.runToken(),lease.sourceHash());var refs=manifest.putArray("artifacts");refs.addObject().put("kind","agent_answer").put("key",prefix+"agent-answer.json").put("sha256",sha(bytes));refs.addObject().put("kind","agent_visual_frame").put("key",prefix+"visual-frames/f0001.jpg").put("sha256",sha(jpeg));byte[] data=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",data);return new CompleteRequest(lease.attempt(),lease.runToken(),prefix+"manifest.json",sha(data));
    }

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
    @Test void sessionSubmissionGetResolvesOriginalKeyWithoutCreatingOrChangingAnyRows()throws Exception{
        var f=fixture();String key="recover-session_"+UUID.randomUUID(),path="/api/jobs/"+f.job()+"/agent-session-submissions/"+key;
        String before=scopedState(f.job());assertError(send("GET",path,null,null),404,"AGENT_SESSION_SUBMISSION_NOT_FOUND");assertEquals(before,scopedState(f.job()));
        var created=send("POST","/api/jobs/"+f.job()+"/agent-sessions",Map.of(),key);assertEquals(200,created.statusCode());var session=JSON.readTree(created.body());before=scopedState(f.job());
        for(int i=0;i<2;i++){var found=send("GET",path,null,null);assertEquals(200,found.statusCode());assertEquals(session,JSON.readTree(found.body()));assertNoPrivateSubmissionFields(found.body());}
        assertEquals(before,scopedState(f.job()));assertEquals(session.path("id").asText(),agents.createSession(f.job(),JSON.createObjectNode(),key).id());assertEquals(before,scopedState(f.job()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_session WHERE job_id=?",Integer.class,f.job()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE job_id=?",Integer.class,f.job()));
    }
    @Test void turnSubmissionGetPreservesOriginalWhitespaceDefaultBudgetAndExistingReservation()throws Exception{
        var f=fixture();var session=session(f);String key="recover-turn_"+UUID.randomUUID(),question="  原问题的空格必须保留  ",path="/api/agent-sessions/"+session.id()+"/turn-submissions/"+key;
        String before=scopedState(f.job());assertError(send("GET",path,null,null),404,"AGENT_TURN_SUBMISSION_NOT_FOUND");assertEquals(before,scopedState(f.job()));
        var posted=send("POST","/api/agent-sessions/"+session.id()+"/turns",Map.of("question",question),key);assertEquals(200,posted.statusCode());String id=JSON.readTree(posted.body()).path("id").asText();var turn=agents.getTurn(id);var lease=claim(turn);
        var reserve=new ReserveRequest(lease.stageId(),lease.attempt(),lease.runToken(),"1".repeat(64),"2".repeat(64),"submission-query-existing","qwen-plus","TEXT");assertEquals("RESERVED",calls.reserve(reserve).outcome());var original=agents.getTurn(id);before=scopedState(f.job());
        for(int i=0;i<2;i++){var found=send("GET",path,null,null);assertEquals(200,found.statusCode());assertEquals(JSON.readTree(JSON.writeValueAsBytes(original)),JSON.readTree(found.body()));assertNoPrivateSubmissionFields(found.body());}
        assertEquals(before,scopedState(f.job()));assertEquals(question,agents.findTurnSubmission(session.id(),key).question());assertEquals(8,agents.findTurnSubmission(session.id(),key).budget().maxTextCalls());assertEquals(1,agents.findTurnSubmission(session.id(),key).budget().textCallsReserved());
        assertEquals(id,turn(session.id(),question,8,key).id());assertEquals(before,scopedState(f.job()));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.job()));
    }
    @Test void submissionResolversRejectInvalidKeysAndDoNotTreatOtherOwnersAsAbsence()throws Exception{
        var f=fixture();var other=fixture();String sessionKey="owner-session-"+UUID.randomUUID();var session=agents.createSession(f.job(),JSON.createObjectNode(),sessionKey);var otherSession=session(other);var sameJobSession=session(f);String turnKey="owner-turn-"+UUID.randomUUID();var turn=turn(session.id(),"不向其他会话暴露",6,turnKey);
        assertError(send("GET","/api/jobs/"+other.job()+"/agent-session-submissions/"+sessionKey,null,null),409,"IDEMPOTENCY_CONFLICT");
        for(var owner:List.of(otherSession,sameJobSession)){var response=send("GET","/api/agent-sessions/"+owner.id()+"/turn-submissions/"+turnKey,null,null);assertError(response,409,"IDEMPOTENCY_CONFLICT");String body=new String(response.body(),StandardCharsets.UTF_8);assertFalse(body.contains(turn.id()));assertFalse(body.contains(turn.question()));}
        for(String key:List.of("", "bad.key","bad+key","x".repeat(101))){assertEquals("INVALID_AGENT_REQUEST",failure(()->agents.findSessionSubmission(f.job(),key)));assertEquals("INVALID_AGENT_REQUEST",failure(()->agents.findTurnSubmission(session.id(),key)));}
        for(String key:List.of("bad.key","bad+key","x".repeat(101))){assertError(send("GET","/api/jobs/"+f.job()+"/agent-session-submissions/"+key,null,null),400,"INVALID_AGENT_REQUEST");assertError(send("GET","/api/agent-sessions/"+session.id()+"/turn-submissions/"+key,null,null),400,"INVALID_AGENT_REQUEST");}
    }
    @Test void captionSubmissionKeysCannotBeRecoveredAsVideoConversations()throws Exception{
        var f=fixture();String captionKey="caption-shaped-session-"+UUID.randomUUID();var caption=agents.createSession(f.job(),JSON.createObjectNode(),captionKey);String turnKey="caption-shaped-turn-"+UUID.randomUUID();var hidden=turn(caption.id(),"仅用于隔离kind反例",4,turnKey);var video=session(f);
        jdbc.update("UPDATE agent_session SET kind='CAPTION',caption_export_id=?,caption_clip_id=? WHERE id=?",UUID.randomUUID().toString(),UUID.randomUUID().toString(),caption.id());String before=scopedState(f.job());
        var sessionResponse=send("GET","/api/jobs/"+f.job()+"/agent-session-submissions/"+captionKey,null,null);assertError(sessionResponse,409,"IDEMPOTENCY_CONFLICT");assertFalse(new String(sessionResponse.body(),StandardCharsets.UTF_8).contains(caption.id()));
        assertError(send("GET","/api/agent-sessions/"+video.id()+"/turn-submissions/"+turnKey,null,null),409,"IDEMPOTENCY_CONFLICT");var response=send("GET","/api/agent-sessions/"+caption.id()+"/turn-submissions/"+turnKey,null,null);assertError(response,404,"AGENT_SESSION_NOT_FOUND");assertFalse(new String(response.body(),StandardCharsets.UTF_8).contains(hidden.question()));assertEquals(before,scopedState(f.job()));
    }
    @Test void submissionResolversRecomputeStoredFingerprintsAndNeverRewriteCorruption()throws Exception{
        var f=fixture();String sessionKey="invalid-session-"+UUID.randomUUID();var invalid=agents.createSession(f.job(),JSON.createObjectNode(),sessionKey);String sessionHash=jdbc.queryForObject("SELECT request_hash FROM agent_session WHERE id=?",String.class,invalid.id());
        jdbc.update("UPDATE agent_session SET request_hash=? WHERE id=?","0".repeat(64),invalid.id());String before=scopedState(f.job());assertError(send("GET","/api/jobs/"+f.job()+"/agent-session-submissions/"+sessionKey,null,null),409,"AGENT_SUBMISSION_INVALID");assertError(send("GET","/api/agent-sessions/"+invalid.id()+"/turn-submissions/not-created",null,null),409,"AGENT_SUBMISSION_INVALID");assertEquals(before,scopedState(f.job()));jdbc.update("UPDATE agent_session SET request_hash=? WHERE id=?",sessionHash,invalid.id());
        for(String field:List.of("request_hash","question","max_text_calls")){var owner=session(f);String key="invalid-turn-"+UUID.randomUUID();var turn=turn(owner.id(),"冻结问题",8,key);switch(field){case "request_hash"->jdbc.update("UPDATE agent_turn SET request_hash=? WHERE id=?","0".repeat(64),turn.id());case "question"->jdbc.update("UPDATE agent_turn SET question=? WHERE id=?","篡改的问题",turn.id());case "max_text_calls"->jdbc.update("UPDATE agent_turn SET max_text_calls=9 WHERE id=?",turn.id());}before=scopedState(f.job());assertError(send("GET","/api/agent-sessions/"+owner.id()+"/turn-submissions/"+key,null,null),409,"AGENT_SUBMISSION_INVALID");assertEquals(before,scopedState(f.job()));}
    }
    @Test void submissionResolverReturnsQueuedRunningSucceededFailedAndCancelledOriginalTurns()throws Exception{
        for(String state:List.of("QUEUED","RUNNING","SUCCEEDED","FAILED","CANCELLED")){
            var f=fixture();var session=session(f);String key="terminal-query-"+UUID.randomUUID();var first=turn(session.id(),"旧提交保留原结果",4,key);
            if(state.equals("CANCELLED"))agents.cancel(first.id());else if(!state.equals("QUEUED")){var lease=claim(first);if(state.equals("SUCCEEDED"))jobs.complete(lease.stageId(),publish(f,first,lease,null));else if(state.equals("FAILED"))jobs.fail(lease.stageId(),new FailRequest(lease.attempt(),lease.runToken(),"TEXT_PROVIDER_ERROR",false));}
            var original=agents.getTurn(first.id());assertEquals(state,original.status());String before=scopedState(f.job());assertEquals(original,agents.findTurnSubmission(session.id(),key));assertEquals(first.id(),turn(session.id(),first.question(),4,key).id());assertEquals(before,scopedState(f.job()));assertEquals(1,agents.getSession(session.id()).turns().size());
        }
    }
    @Test void submissionResolverFindsOldReceiptsAfterBusyStateAndSourceReadinessChange()throws Exception{
        var f=fixture();String sessionKey="old-session-"+UUID.randomUUID();var session=agents.createSession(f.job(),JSON.createObjectNode(),sessionKey);String turnKey="old-turn-"+UUID.randomUUID();var first=turn(session.id(),"旧问题",4,turnKey);agents.cancel(first.id());turn(session.id(),"新轮次已占用会话",4,"busy-"+UUID.randomUUID());jdbc.update("UPDATE analysis_job SET status='RUNNING' WHERE id=?",f.job());String before=scopedState(f.job());
        assertEquals(session.id(),agents.findSessionSubmission(f.job(),sessionKey).id());assertEquals("CANCELLED",agents.findTurnSubmission(session.id(),turnKey).status());assertEquals(before,scopedState(f.job()));
    }
    @Test void submissionResolversWaitForOriginalTransactionsThenFindCommittedKeys(){
        var f=fixtureUnchecked();var template=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        String sessionKey="commit-session-"+UUID.randomUUID();var sessionFuture=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<AgentModels.Session>>();var committed=template.execute(status->{var created=agents.createSession(f.job(),JSON.createObjectNode(),sessionKey);var started=new java.util.concurrent.CountDownLatch(1);sessionFuture.set(CompletableFuture.supplyAsync(()->{started.countDown();return agents.findSessionSubmission(f.job(),sessionKey);}));assertDoesNotThrow(()->assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS)));assertThrows(java.util.concurrent.TimeoutException.class,()->sessionFuture.get().get(100,java.util.concurrent.TimeUnit.MILLISECONDS));return created;});assertEquals(committed,sessionFuture.get().join());
        String turnKey="commit-turn-"+UUID.randomUUID();var turnFuture=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<AgentModels.Turn>>();var turn=template.execute(status->{var created=turn(committed.id(),"提交事务尚未返回",8,turnKey);var started=new java.util.concurrent.CountDownLatch(1);turnFuture.set(CompletableFuture.supplyAsync(()->{started.countDown();return agents.findTurnSubmission(committed.id(),turnKey);}));assertDoesNotThrow(()->assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS)));assertThrows(java.util.concurrent.TimeoutException.class,()->turnFuture.get().get(100,java.util.concurrent.TimeUnit.MILLISECONDS));return created;});assertEquals(turn,turnFuture.get().join());assertEquals(1,agents.getSession(committed.id()).turns().size());
    }
    @Test void fullSessionStillResolvesOriginalKeysWhileUncommittedKeysRemainAbsentAfterLimit()throws Exception{
        var f=fixture();String sessionKey="full-session-"+UUID.randomUUID();var owner=agents.createSession(f.job(),JSON.createObjectNode(),sessionKey);String existingKey="full-original-"+UUID.randomUUID();var original=turn(owner.id(),"原轮次已落库",4,existingKey);agents.cancel(original.id());
        // Synthetic terminal rows represent prior completed attempts; neither a provider nor a Worker is started.
        String fingerprint=io.livecut.clips.ClipRequestService.sha("agent-turn\n"+owner.id()+"\n"+original.question()+"\n4");
        for(int sequence=2;sequence<=100;sequence++){
            String id=UUID.randomUUID().toString(),status=sequence%2==0?"FAILED":"CANCELLED";
            jdbc.update("INSERT INTO agent_turn (id,session_id,job_id,sequence_number,question,idempotency_key,request_hash,text_model_id,text_fallback_models_json,max_text_calls,history_json,status) SELECT ?,session_id,job_id,?,question,?,?,text_model_id,text_fallback_models_json,max_text_calls,history_json,? FROM agent_turn WHERE id=?",id,sequence,"full-filled-"+UUID.randomUUID(),fingerprint,status,original.id());
            jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,shard_key,status,input_hash,error_code) SELECT ?,job_id,'AGENT_TURN',?,'SEGMENT',?,?,input_hash,? FROM job_stage WHERE id=?",UUID.randomUUID().toString(),id,"agent-"+id,status,status.equals("FAILED")?"TEXT_PROVIDER_ERROR":"USER_CANCELLED",original.stage().id());
        }
        String before=scopedState(f.job());assertEquals(100,agents.findSessionSubmission(f.job(),sessionKey).turns().size());assertEquals("CANCELLED",agents.findTurnSubmission(owner.id(),existingKey).status());assertEquals(original.id(),turn(owner.id(),original.question(),4,existingKey).id());
        String missing="full-missing-"+UUID.randomUUID(),path="/api/agent-sessions/"+owner.id()+"/turn-submissions/"+missing;assertError(send("GET",path,null,null),404,"AGENT_TURN_SUBMISSION_NOT_FOUND");
        assertError(send("POST","/api/agent-sessions/"+owner.id()+"/turns",Map.of("question","未落库的原提交","max_text_calls",4),missing),409,"AGENT_SESSION_LIMIT");assertError(send("GET",path,null,null),404,"AGENT_TURN_SUBMISSION_NOT_FOUND");
        assertEquals(before,scopedState(f.job()));assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE session_id=?",Integer.class,owner.id()));assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM job_stage WHERE job_id=? AND owner_type='AGENT_TURN'",Integer.class,f.job()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.job()));
    }
    private String scopedState(String job){Map<String,Object> rows=new TreeMap<>();for(String table:List.of("analysis_job","agent_session","agent_turn","job_stage","model_call"))rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE "+(table.equals("analysis_job")?"id":"job_id")+"=? ORDER BY "+(table.equals("model_call")?"request_id":"id"),job));return JSON.writeValueAsString(rows);}
    private static void assertError(HttpResponse<byte[]> response,int status,String code){assertEquals(status,response.statusCode(),()->new String(response.body(),StandardCharsets.UTF_8));assertEquals(code,JSON.readTree(response.body()).path("error_code").asText());}
    private static void assertNoPrivateSubmissionFields(byte[] body){String json=new String(body,StandardCharsets.UTF_8);for(String field:List.of("idempotency_key","request_hash","run_token","transcript_key","analysis_key","history_json","source_json"))assertFalse(json.contains('"'+field+'"'));}
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
            Map<String,byte[]> files=new LinkedHashMap<>();if(stage.stageType().equals("ASR"))files.put("transcript",JSON.writeValueAsBytes(utterances));else if(stage.stageType().equals("MEDIA")){files.put("audio",new byte[]{1,2,3});files.put("media_info",JSON.writeValueAsBytes(Map.of("duration_ms",4000,"width",640,"height",360,"rotation_degrees",0,"format_start_time_seconds",0,"video_start_time_seconds",0)));}else files.put("analysis_result",JSON.writeValueAsBytes(result));
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
