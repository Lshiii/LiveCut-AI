package io.livecut.exports;

import static org.junit.jupiter.api.Assertions.*;
import io.livecut.LivecutBackendApplication;
import io.livecut.captions.*;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobService;
import io.livecut.jobs.JobModels.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real MySQL/HTTP and actual integrity checks of explicitly synthetic bytes; no encoder, worker or provider is run. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualClipExportIntegrationTests {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final byte[] CLIP={0,1,2,3,4,5,6,7};
    @TempDir static Path root;
    private static final List<Boolean> SOURCE_IO_TRANSACTIONS=new CopyOnWriteArrayList<>();
    private static final AtomicReference<Runnable> DURING_SOURCE_IO=new AtomicReference<>();
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private JobService jobs;
    private ExportService exports;
    private URI base;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> ownJobs=new ArrayList<>(),ownVideos=new ArrayList<>();
    private record Fixture(String job,String video,String asrId,Path transcript) {}
    private record Plan(Fixture fixture,String id,JsonNode detail) {}
    @TestConfiguration static class SourceRecording {
        @Bean @Primary ArtifactVerifier recordingVerifier(){return new ArtifactVerifier(root){
            @Override public VerifiedManifest verifyManifest(String key,String hash,ManifestIdentity identity){
                if(Set.of("ASR","TIMELINE").contains(identity.stageType())){
                    SOURCE_IO_TRANSACTIONS.add(TransactionSynchronizationManager.isActualTransactionActive());
                    VerifiedManifest verified=super.verifyManifest(key,hash,identity);
                    Runnable callback=DURING_SOURCE_IO.getAndSet(null);if(callback!=null)callback.run();return verified;
                }
                return super.verifyManifest(key,hash,identity);
            }
        };}
    }
    @BeforeAll void start(){
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:13307/livecut_test_topics(?:\\?.*)?"));
        Path font=Path.of("../data/fonts/noto-sans-cjk-sc-2.004/NotoSansCJKsc-Regular.otf").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(font),"Install the fixed caption font before render integration tests");
        app=new SpringApplicationBuilder(LivecutBackendApplication.class,SourceRecording.class).run("--server.port=0","--server.address=127.0.0.1",
                "--livecut.storage.root="+root,"--livecut.captions.render-font-path="+font,"--livecut.jobs.scan-enabled=false","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false",
                "--livecut.workers.require-registration=false","--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);jobs=app.getBean(JobService.class);exports=app.getBean(ExportService.class);
        base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup(){
        DURING_SOURCE_IO.set(null);SOURCE_IO_TRANSACTIONS.clear();
        for(String job:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",job);
        for(String video:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",video);
        ownJobs.clear();ownVideos.clear();
    }
    @AfterAll void stop(){if(app!=null)app.close();}

    @Test void confirmedManualRangeUsesUnchangedBaseEightClaimAndDeliversMediaCaptionsAndBothZipFormats()throws Exception{
        Plan plan=plan();ObjectNode input=exportInput(plan.detail());
        error(send("POST",planPath(plan)+"/exports",input,UUID.randomUUID().toString()),409,"MANUAL_CLIP_CONFIRMATION_REQUIRED");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE job_id=?",Integer.class,plan.fixture().job()));
        SOURCE_IO_TRANSACTIONS.clear();confirm(plan);assertSourceIoOutsideTransactions();
        SOURCE_IO_TRANSACTIONS.clear();JsonNode batch=ok(send("POST",planPath(plan)+"/exports",input,UUID.randomUUID().toString()));assertSourceIoOutsideTransactions();
        assertEquals(plan.id(),batch.path("manual_clip_plan_id").asText());assertFalse(batch.has("clip_request_id"));assertEquals(1,batch.path("approved_revision").asLong());assertEquals(1,batch.path("clips").size());
        String export=batch.path("id").asText(),clip=batch.path("clips").get(0).path("id").asText();
        ClaimResult lease=claim(batch.path("clips").get(0).path("stage_id").asText());
        Map<?,?> config=(Map<?,?>)lease.config().get("export");
        assertEquals(Set.of("export_id","clip_id","approved_revision","snapshot_hash","segment_id","start_ms","end_ms","encoding_profile"),config.keySet());
        assertEquals(1000L,config.get("start_ms"));assertEquals(9000L,config.get("end_ms"));
        assertEquals("ACCEPTED",jobs.complete(lease.stageId(),publish(lease,false)).outcome());
        assertArrayEquals(CLIP,send("GET","/api/exports/"+export+"/clips/"+clip+"/content",null,null).body());
        JsonNode manifest=get("/api/exports/"+export+"/manifest");assertEquals(plan.id(),manifest.path("manual_clip_plan_id").asText());assertFalse(manifest.has("clip_request_id"));
        assertEquals(0,exports.list(plan.fixture().job(),20,0).items().size(),"Manual exports cannot leak into the old topic list");
        assertEquals(1,get(planPath(plan)+"/exports").path("items").size());
        try(var ready=app.getBean(ExportVideoDeliveryService.class).bundle(export,List.of(clip));ZipFile zip=new ZipFile(ready.path().toFile())){
            JsonNode index=JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
            assertEquals(plan.id(),index.path("manual_clip_plan_id").asText());assertTrue(index.path("clip_request_id").isNull());assertEquals("CLEAN",index.path("render_mode").asText());
            assertArrayEquals(CLIP,zip.getInputStream(zip.getEntry("clips/001-"+clip+".mp4")).readAllBytes());assertEquals(8000,index.path("clips").get(0).path("duration_ms").asLong());
        }
        var captions=app.getBean(CaptionService.class);var initial=captions.initialize(export,clip);
        assertEquals(4,initial.cues().size());assertEquals(List.of("u1","u2","u3","u4"),initial.cues().stream().map(CaptionModels.Cue::sourceUtteranceId).toList());
        assertEquals(8000,initial.source().durationMs());assertEquals(0,initial.cues().getFirst().startMs());assertEquals(8000,initial.cues().getLast().endMs());
        captions.confirm(export,clip,JSON.createObjectNode().put("revision",0).put("acknowledge_review",true));
        try(var ready=app.getBean(CaptionDeliveryService.class).bundle(export,List.of(new CaptionDeliveryService.Selection(clip,0)));ZipFile zip=new ZipFile(ready.path().toFile())){
            JsonNode index=JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());assertEquals("caption_delivery_package",index.path("data_kind").asText());
            assertNotNull(zip.getEntry("clips/"+clip+"/clip-r0.srt"));assertArrayEquals(CLIP,zip.getInputStream(zip.getEntry("clips/"+clip+"/clip-r0.mp4")).readAllBytes());
        }
        assertNoPlanningWrites(plan.fixture().job());
    }

    @Test void exportSubmissionPinsOriginalRevisionAcrossHeadChangesAndDetectsReceiptTampering()throws Exception{
        Plan plan=plan();confirm(plan);ObjectNode first=exportInput(plan.detail());String key=UUID.randomUUID().toString();
        JsonNode batch=ok(send("POST",planPath(plan)+"/exports",first,key));String export=batch.path("id").asText();
        assertEquals(export,ok(send("POST",planPath(plan)+"/exports",first,key)).path("id").asText());
        assertEquals(export,ok(send("POST",planPath(plan)+"/exports",first,UUID.randomUUID().toString())).path("id").asText());
        assertEquals(export,get(planPath(plan)+"/export-submissions/"+key).path("id").asText());
        ObjectNode revision=JSON.createObjectNode().put("base_revision",1).put("base_plan_hash",plan.detail().path("plan_hash").asText()).put("source_fingerprint",plan.detail().path("source").path("source_fingerprint").asText())
                .put("title","第二人工版").put("start_utterance_id","u2").put("end_utterance_id","u3");
        JsonNode receipt=ok(send("POST",planPath(plan)+"/revisions",revision,UUID.randomUUID().toString()));assertEquals(2,receipt.path("revision").asLong());
        JsonNode current=get(planPath(plan));assertEquals("DRAFT",current.path("status").asText());assertEquals(2,current.path("revision").asLong());
        assertEquals(1,get(planPath(plan)+"/export-submissions/"+key).path("approved_revision").asLong());assertEquals(export,exports.get(export).id());
        error(send("POST",planPath(plan)+"/exports",exportInput(current),key),409,"IDEMPOTENCY_CONFLICT");
        error(send("POST",planPath(plan)+"/exports",exportInput(current),UUID.randomUUID().toString()),409,"MANUAL_CLIP_CONFIRMATION_REQUIRED");
        error(send("GET",planPath(plan)+"/export-submissions/"+UUID.randomUUID(),null,null),404,"MANUAL_CLIP_EXPORT_SUBMISSION_NOT_FOUND");
        for(String query:List.of("?limit=","?limit=0","?offset=-1","?limit=1&limit=2","?unknown=x"))error(send("GET",planPath(plan)+"/exports"+query,null,null),400,"MANUAL_CLIP_INVALID");
        JsonNode empty=get(planPath(plan)+"/exports?offset=9007199254740991");assertEquals(9007199254740991L,empty.path("offset").asLong());assertTrue(empty.path("items").isEmpty());
        String original=jdbc.queryForObject("SELECT input_json FROM manual_clip_export_submission WHERE idempotency_key=?",String.class,key);
        try{jdbc.update("UPDATE manual_clip_export_submission SET input_json=? WHERE idempotency_key=?",JSON.writeValueAsString(exportInput(current)),key);
            error(send("GET",planPath(plan)+"/export-submissions/"+key,null,null),409,"MANUAL_CLIP_SUBMISSION_INVALID");
        }finally{jdbc.update("UPDATE manual_clip_export_submission SET input_json=? WHERE idempotency_key=?",original,key);}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE manual_clip_plan_id=? AND render_mode='CLEAN'",Integer.class,plan.id()));assertNoPlanningWrites(plan.fixture().job());
    }

    @Test void firstExportRejectsSourceTicketRacesWithoutAnyBatchOrReceiptAndIgnoresUnrelatedProgress()throws Exception{
        Plan plan=plan();confirm(plan);String original=jdbc.queryForObject("SELECT input_hash FROM job_stage WHERE id=?",String.class,plan.fixture().asrId());
        String key=UUID.randomUUID().toString();DURING_SOURCE_IO.set(()->jdbc.update("UPDATE job_stage SET input_hash=? WHERE id=?","0".repeat(64),plan.fixture().asrId()));
        try{error(send("POST",planPath(plan)+"/exports",exportInput(plan.detail()),key),409,"MANUAL_CLIP_SOURCE_CHANGED");}
        finally{jdbc.update("UPDATE job_stage SET input_hash=? WHERE id=?",original,plan.fixture().asrId());}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE manual_clip_plan_id=?",Integer.class,plan.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM manual_clip_export_submission WHERE idempotency_key=?",Integer.class,key));
        DURING_SOURCE_IO.set(()->jdbc.update("UPDATE analysis_job SET state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",plan.fixture().job()));SOURCE_IO_TRANSACTIONS.clear();
        JsonNode accepted=ok(send("POST",planPath(plan)+"/exports",exportInput(plan.detail()),key));assertEquals(plan.id(),accepted.path("manual_clip_plan_id").asText());assertSourceIoOutsideTransactions();
    }

    @Test void sameKeyCommittedDuringSourceReadReplaysOriginalBatchInsteadOfRejectingAnOldTicket()throws Exception{
        Plan plan=plan();confirm(plan);ObjectNode input=exportInput(plan.detail());String key=UUID.randomUUID().toString();
        String original=jdbc.queryForObject("SELECT input_hash FROM job_stage WHERE id=?",String.class,plan.fixture().asrId());var committed=new AtomicReference<String>();
        DURING_SOURCE_IO.set(()->{
            committed.set(exports.createManualClip(plan.id(),input,key).id());
            jdbc.update("UPDATE job_stage SET input_hash=? WHERE id=?","0".repeat(64),plan.fixture().asrId());
        });
        try{
            JsonNode result=ok(send("POST",planPath(plan)+"/exports",input,key));assertNotNull(committed.get());assertEquals(committed.get(),result.path("id").asText());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE manual_clip_plan_id=?",Integer.class,plan.id()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM manual_clip_export_submission WHERE idempotency_key=?",Integer.class,key));
        }finally{jdbc.update("UPDATE job_stage SET input_hash=? WHERE id=?",original,plan.fixture().asrId());}
    }

    @Test void manualProofCannotBeRemovedOrDowngradedInAnyMediaCaptionZipOrDerivedConsumption()throws Exception{
        Plan plan=plan();confirm(plan);JsonNode batch=ok(send("POST",planPath(plan)+"/exports",exportInput(plan.detail()),UUID.randomUUID().toString()));String export=batch.path("id").asText(),clip=batch.path("clips").get(0).path("id").asText();
        ClaimResult lease=claim(batch.path("clips").get(0).path("stage_id").asText());jobs.complete(lease.stageId(),publish(lease,false));
        var captions=app.getBean(CaptionService.class);captions.initialize(export,clip);var caption=captions.confirm(export,clip,JSON.createObjectNode().put("revision",0).put("acknowledge_review",true));
        var renders=app.getBean(CaptionRenderService.class);JsonNode renderInput=JSON.valueToTree(Map.of("caption_revision",0,"caption_content_hash",caption.contentHash(),"style_preset","WHITE_OUTLINE_V1","frame_preset","SOURCE"));
        var render=renders.create(export,clip,UUID.randomUUID().toString(),renderInput);String derived=render.id();
        assertEquals(plan.id(),exports.get(derived).manualClipPlanId());assertEquals(1,exports.listManualClip(plan.id(),20,0).items().size());
        Map<String,Object> proof=jdbc.queryForMap("SELECT * FROM manual_clip_confirmation WHERE plan_id=? AND revision=1",plan.id());
        jdbc.update("DELETE FROM manual_clip_confirmation WHERE plan_id=? AND revision=1",plan.id());
        try{
            for(String path:List.of("/api/exports/"+export,"/api/exports/"+export+"/manifest","/api/exports/"+export+"/clips/"+clip+"/content","/api/exports/"+export+"/video-package?clips="+clip,"/api/exports/"+export+"/clips/"+clip+"/captions"))assertEquals(409,send("GET",path,null,null).statusCode(),path);
            assertThrows(RuntimeException.class,()->renders.get(derived));String stage=jdbc.queryForObject("SELECT stage_id FROM export_clip WHERE export_id=?",String.class,derived);
            assertThrows(RuntimeException.class,()->claim(stage));assertEquals("READY",jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?",String.class,stage));
        }finally{restore("manual_clip_confirmation",proof);}
        String derivedStage=jdbc.queryForObject("SELECT stage_id FROM export_clip WHERE export_id=?",String.class,derived);ClaimResult burned=claim(derivedStage);jobs.complete(burned.stageId(),publish(burned,true));assertEquals("SUCCEEDED",renders.get(derived).status());
        jdbc.update("UPDATE export_job SET manual_clip_plan_id=NULL WHERE id=?",export);
        try{
            for(String path:List.of("/api/exports/"+export,"/api/exports/"+export+"/clips/"+clip+"/content","/api/exports/"+export+"/video-package?clips="+clip))error(send("GET",path,null,null),409,"EXPORT_SNAPSHOT_INVALID");
            assertThrows(RuntimeException.class,()->renders.get(derived));
        }finally{jdbc.update("UPDATE export_job SET manual_clip_plan_id=? WHERE id=?",plan.id(),export);}
        byte[] raw=Files.readAllBytes(plan.fixture().transcript());Files.writeString(plan.fixture().transcript(),"tampered",StandardCharsets.UTF_8);
        try{assertEquals(409,send("GET","/api/exports/"+export+"/clips/"+clip+"/content",null,null).statusCode());assertThrows(RuntimeException.class,()->renders.get(derived));}
        finally{Files.write(plan.fixture().transcript(),raw);}
        assertEquals("SUCCEEDED",exports.get(export).status());assertEquals("SUCCEEDED",renders.get(derived).status());assertNoPlanningWrites(plan.fixture().job());
    }

    private Plan plan()throws Exception{
        Fixture fixture=fixture();String fingerprint=get("/api/jobs/"+fixture.job()+"/transcript").path("source").path("source_fingerprint").asText();
        ObjectNode input=JSON.createObjectNode().put("source_fingerprint",fingerprint).put("title"," 纯人工区间 · 保留中间全部内容 ").put("start_utterance_id","u1").put("end_utterance_id","u4");
        SOURCE_IO_TRANSACTIONS.clear();JsonNode receipt=ok(send("POST","/api/jobs/"+fixture.job()+"/manual-clip-plans",input,UUID.randomUUID().toString()));assertSourceIoOutsideTransactions();
        String id=receipt.path("plan_id").asText();JsonNode detail=get("/api/manual-clip-plans/"+id+"?revision=1");assertEquals(4,detail.path("range").path("utterance_count").asInt());return new Plan(fixture,id,detail);
    }
    private void confirm(Plan plan)throws Exception{ok(send("POST",planPath(plan)+"/confirm",JSON.createObjectNode().put("revision",1).put("plan_hash",plan.detail().path("plan_hash").asText()).put("accept_sentence_boundaries",true),null));}
    private static String planPath(Plan plan){return "/api/manual-clip-plans/"+plan.id();}
    private static ObjectNode exportInput(JsonNode plan){return JSON.createObjectNode().put("revision",plan.path("revision").asLong()).put("plan_hash",plan.path("plan_hash").asText()).put("profile","h264-aac-v1");}
    private static void assertSourceIoOutsideTransactions(){assertFalse(SOURCE_IO_TRANSACTIONS.isEmpty(),"The actual source verifier must run");assertTrue(SOURCE_IO_TRANSACTIONS.stream().noneMatch(Boolean::booleanValue),"New write source IO must finish before business locks");}
    private void assertNoPlanningWrites(String job){
        for(String table:List.of("clip_request","model_call","review_document","agent_session"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE job_id=?",Integer.class,job),table);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM job_stage WHERE job_id=? AND owner_type IN ('CLIP_REQUEST','AGENT_TURN')",Integer.class,job));
    }
    private Fixture fixture()throws Exception{
        String job=UUID.randomUUID().toString(),video=UUID.randomUUID().toString(),sourceKey="videos/"+video+"/source.mp4";byte[] original={3,2,1,0};write(sourceKey,original);String hash=sha(original);
        jdbc.update("INSERT INTO video_asset (id,content_hash,source_key,original_filename,size_bytes,media_validation_status,duration_ms) VALUES (?,?,?,?,?,'VALID',10000)",video,hash,sourceKey,"manual-protocol-synthetic.mp4",original.length);ownVideos.add(video);
        jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,'{\"analysis_mode\":\"topics\"}','REVIEW_READY',0,0)",job,video,UUID.randomUUID().toString(),"a".repeat(64),"b".repeat(64));ownJobs.add(job);
        var utterances=JSON.createArrayNode();long[] starts={1000,3000,5000,8000};for(int i=0;i<4;i++)utterances.addObject().put("utterance_id","u"+(i+1)).put("start_ms",starts[i]).put("end_ms",starts[i]+1000).put("text","第"+(i+1)+"句真实来源的合成原文").put("source_chunk_id","c0");
        var analysis=JSON.createObjectNode().put("schema_version","1.0").put("analysis_mode","topics").put("data_kind","model_result").put("review_status","DRAFT").put("job_id",job).put("video_id",video).put("analysis_generation",1).put("duration_ms",10000).put("transcript_version","synthetic").put("pipeline_version","topics-text-v1");analysis.set("utterances",utterances);analysis.putArray("segments");analysis.putArray("frames");
        String asrId=null;Path transcript=null;
        for(String type:List.of("ASR","TIMELINE")){
            String stage=UUID.randomUUID().toString(),token=UUID.randomUUID().toString(),prefix="jobs/"+job+"/stages/"+stage+"/attempts/1/"+token+"/",kind=type.equals("ASR")?"transcript":"analysis_result",key=prefix+kind+".json";
            byte[] bytes=JSON.writeValueAsBytes(type.equals("ASR")?utterances:analysis);write(key,bytes);
            ObjectNode manifest=JSON.createObjectNode().put("schema_version",1).put("job_id",job).put("video_id",video).put("analysis_generation",1).put("stage_id",stage).put("stage_type",type).put("attempt",1).put("run_token",token).put("input_sha256",hash).put("data_kind","model_result").put("review_status","DRAFT");manifest.putArray("artifacts").addObject().put("kind",kind).put("key",key).put("sha256",sha(bytes));
            byte[] manifestBytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",manifestBytes);
            jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,status,attempt,run_token,input_hash,manifest_key,manifest_hash) VALUES (?,?,'ANALYSIS',?,?,'SUCCEEDED',1,?,?,?,?)",stage,job,job,type,token,"c".repeat(64),prefix+"manifest.json",sha(manifestBytes));
            if(type.equals("ASR")){asrId=stage;transcript=root.resolve(key);}
        }
        return new Fixture(job,video,asrId,transcript);
    }
    private ClaimResult claim(String stage){return jobs.claim(new ClaimRequest(stage,"manual-protocol-test",UUID.randomUUID().toString()));}
    private CompleteRequest publish(ClaimResult lease,boolean captioned)throws Exception{
        Map<?,?> config=(Map<?,?>)lease.config().get("export");JsonNode render=captioned?JSON.valueToTree(config.get("caption_render")):null;
        long duration=captioned?render.path("input_media").path("duration_ms").asLong():((Number)config.get("end_ms")).longValue()-((Number)config.get("start_ms")).longValue();
        var media=new TreeMap<String,Object>(Map.of("duration_ms",duration,"video_codec","h264","audio_codec","aac","frame_rate",30,"width",captioned?render.path("output").path("width").asInt():640,"height",captioned?render.path("output").path("height").asInt():360));
        String prefix="jobs/"+lease.jobId()+"/stages/"+lease.stageId()+"/attempts/"+lease.attempt()+"/"+lease.runToken()+"/";write(prefix+"clip.mp4",CLIP);byte[] info=JSON.writeValueAsBytes(media);write(prefix+"media-info.json",info);
        ObjectNode manifest=JSON.createObjectNode().put("schema_version",1).put("job_id",lease.jobId()).put("video_id",lease.videoId()).put("analysis_generation",lease.generation()).put("stage_id",lease.stageId()).put("stage_type","EXPORT_CLIP").put("attempt",lease.attempt()).put("run_token",lease.runToken()).put("input_sha256",lease.sourceHash()).put("data_kind",captioned?"captioned_export":"reviewed_export").put("review_status","CONFIRMED").put("stage_input_hash",lease.inputHash());
        for(String field:List.of("export_id","clip_id","snapshot_hash","segment_id","encoding_profile"))manifest.put(field,String.valueOf(config.get(field)));
        for(String field:List.of("approved_revision","start_ms","end_ms"))manifest.put(field,((Number)config.get(field)).longValue());
        var refs=manifest.putArray("artifacts");refs.addObject().put("kind","clip").put("key",prefix+"clip.mp4").put("sha256",sha(CLIP));refs.addObject().put("kind","media_info").put("key",prefix+"media-info.json").put("sha256",sha(info));
        if(captioned){manifest.put("render_mode","CAPTIONED").put("caption_render_hash",String.valueOf(config.get("caption_render_hash")));byte[] extra=JSON.writeValueAsBytes(Map.of("schema_version",1,"caption_render_hash",config.get("caption_render_hash"),"caption_render",render));write(prefix+"render-info.json",extra);refs.addObject().put("kind","caption_render_info").put("key",prefix+"render-info.json").put("sha256",sha(extra));}
        byte[] bytes=JSON.writeValueAsBytes(manifest);write(prefix+"manifest.json",bytes);return new CompleteRequest(lease.attempt(),lease.runToken(),prefix+"manifest.json",sha(bytes));
    }
    private void restore(String table,Map<String,Object> row){jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    private static void write(String key,byte[] bytes)throws Exception{Path file=root.resolve(key);Files.createDirectories(file.getParent());Files.write(file,bytes);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private HttpResponse<byte[]> send(String method,String path,JsonNode input,String key)throws Exception{
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20));if(key!=null)builder.header("Idempotency-Key",key);if(input!=null)builder.header("Content-Type","application/json");
        return http.send(builder.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(input))).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private JsonNode get(String path)throws Exception{return ok(send("GET",path,null,null));}
    private static JsonNode ok(HttpResponse<byte[]> response){assertTrue(response.statusCode()>=200&&response.statusCode()<300,new String(response.body(),StandardCharsets.UTF_8));return JSON.readTree(response.body());}
    private static void error(HttpResponse<byte[]> response,int status,String code){assertEquals(status,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));assertEquals(code,JSON.readTree(response.body()).path("error_code").asText());}
}
