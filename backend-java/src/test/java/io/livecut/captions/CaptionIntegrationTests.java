package io.livecut.captions;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipInputStream;
import io.livecut.LivecutBackendApplication;
import io.livecut.clips.ClipRequestModels;
import io.livecut.clips.ClipRequestService;
import io.livecut.exports.ExportModels;
import io.livecut.exports.ExportService;
import io.livecut.jobs.*;
import io.livecut.jobs.JobModels.*;
import io.livecut.topics.TopicReviewService;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real MySQL/HTTP with explicit synthetic ASR and already-completed synthetic MP4 artifacts; no provider calls. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaptionIntegrationTests {
    @TempDir static Path root;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private ConfigurableApplicationContext app;private JdbcTemplate jdbc;private JobService jobs;private ExportService exports;private CaptionService captions;
    private URI base;private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> ownJobs=new ArrayList<>(),ownVideos=new ArrayList<>();
    private record Parent(String job,String video,String hash,JsonNode utterances) {}
    private record Fixture(Parent parent,ExportModels.Snapshot batch) {
        String export(){return batch.id();}String clip(){return batch.clips().getFirst().id();}
        String path(){return "/api/exports/"+export()+"/clips/"+clip()+"/captions";}
    }
    @BeforeAll void start(){assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"));startContext();}
    private void startContext(){
        app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,
            "--livecut.workers.require-registration=false","--livecut.jobs.scan-enabled=false","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false",
            "--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);jobs=app.getBean(JobService.class);exports=app.getBean(ExportService.class);captions=app.getBean(CaptionService.class);
        base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup(){for(String id:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);for(String id:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);ownJobs.clear();ownVideos.clear();}
    @AfterAll void stop(){if(app!=null)app.close();}

    @Test void initializationIsExplicitIdempotentAndNeverChangesOriginalArtifactsOrBudgets()throws Exception{
        var f=fixture(500,3500,false);var before=jobs.get(f.parent().job());var exportBefore=exports.get(f.export());
        assertEquals(404,send("GET",f.path(),null).statusCode());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM caption_document WHERE clip_id=?",Integer.class,f.clip()));
        var first=send("POST",f.path(),null);assertEquals(200,first.statusCode());var original=captions.get(f.export(),f.clip(),null);
        assertEquals(original,captions.initialize(f.export(),f.clip()));assertEquals(0,original.revision());assertEquals("DRAFT",original.status());assertEquals(4,original.cues().size());
        assertEquals(3000,original.source().durationMs());assertEquals(3000,original.source().mediaDurationMs());assertEquals("SOURCE_EDL_SENTENCE_MS",original.source().timingBasis());
        assertEquals(2,original.cues().stream().filter(CaptionModels.Cue::requiresReview).count());
        assertEquals(before,jobs.get(f.parent().job()));assertEquals(exportBefore,exports.get(f.export()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.parent().job()));
        String publicJson=JSON.writeValueAsString(original);for(String hidden:List.of("run_token","transcript_key","basis_json","jobs/"))assertFalse(publicJson.contains(hidden));
    }
    @Test void partialCuesRequireExplicitReviewBeforeConfirmedFilesAndZipDelivery()throws Exception{
        var f=fixture(500,3500,false);var initial=captions.initialize(f.export(),f.clip());
        assertEquals(409,send("GET",f.path()+"/files/srt?revision=0",null).statusCode());
        assertEquals(409,send("GET",f.path()+"/package?revision=0",null).statusCode());
        assertEquals("CAPTION_REVIEW_REQUIRED",failure(()->captions.confirm(f.export(),f.clip(),confirmation(0,true))));
        ObjectNode changed=edit(initial);((ObjectNode)changed.path("cues").get(0)).put("text","仅保留实际听见的内容");
        var unreviewed=captions.save(f.export(),f.clip(),changed);assertTrue(unreviewed.cues().getFirst().requiresReview());
        assertEquals("CAPTION_REVIEW_REQUIRED",failure(()->captions.confirm(f.export(),f.clip(),confirmation(unreviewed.revision(),true))));
        ObjectNode reviewed=edit(unreviewed);for(JsonNode cue:reviewed.path("cues"))((ObjectNode)cue).put("reviewed",true);
        var saved=captions.save(f.export(),f.clip(),reviewed);var confirmed=captions.confirm(f.export(),f.clip(),confirmation(saved.revision(),true));
        assertEquals("CONFIRMED",confirmed.status());assertEquals(saved.contentHash(),confirmed.contentHash());assertTrue(confirmed.cues().getFirst().boundaryPartial());assertFalse(confirmed.cues().getFirst().requiresReview());
        for(String format:List.of("srt","vtt","txt","json")){
            var file=send("GET",f.path()+"/files/"+format+"?revision="+confirmed.revision(),null);assertEquals(200,file.statusCode());
            assertEquals(file.body().length,Long.parseLong(file.headers().firstValue("Content-Length").orElseThrow()));
            assertTrue(file.headers().firstValue("ETag").isPresent());
        }
        var json=send("GET",f.path()+"/files/json?revision="+confirmed.revision(),null);String text=new String(json.body(),StandardCharsets.UTF_8);
        assertFalse(text.contains("source_text"));assertFalse(text.contains("transcript_key"));assertEquals(confirmed.revision(),JSON.readTree(json.body()).path("revision").longValue());
        var zip=send("GET","/api/exports/"+f.export()+"/package?items="+f.clip()+"."+confirmed.revision(),null);assertEquals(200,zip.statusCode());
        assertEquals(zip.body().length,Long.parseLong(zip.headers().firstValue("Content-Length").orElseThrow()));
        int mp4=0,srt=0;try(var input=new ZipInputStream(new ByteArrayInputStream(zip.body()))){for(var entry=input.getNextEntry();entry!=null;entry=input.getNextEntry()){if(entry.getName().endsWith(".mp4"))mp4++;if(entry.getName().endsWith(".srt"))srt++;input.readAllBytes();}}
        assertEquals(1,mp4);assertEquals(1,srt);
    }
    @Test void oldConfirmedVersionsRemainFrozenWhenASeparateNewDraftChangesTextOrDeletesCues()throws Exception{
        var f=fixture(0,4000,false);var initial=captions.initialize(f.export(),f.clip());var confirmed=captions.confirm(f.export(),f.clip(),confirmation(0,true));
        var originalParent=jobs.get(f.parent().job());var originalExport=exports.get(f.export());String old=JSON.writeValueAsString(confirmed);
        ObjectNode input=edit(confirmed);((ObjectNode)input.path("cues").get(0)).put("text","人工校对");((tools.jackson.databind.node.ArrayNode)input.path("cues")).remove(3);
        var draft=captions.save(f.export(),f.clip(),input);assertEquals("DRAFT",draft.status());assertEquals(1,draft.revision());assertEquals(3,draft.cues().size());
        assertEquals(old,JSON.writeValueAsString(captions.readConfirmed(f.export(),f.clip(),0)));assertEquals(draft,captions.initialize(f.export(),f.clip()));
        var delivery=captions.readConfirmedForDelivery(f.export(),f.clip(),0);assertEquals(confirmed,delivery.snapshot());assertEquals(confirmed.source().clipSha256(),delivery.content().hash());
        assertEquals("CAPTION_REVISION_CONFLICT",failure(()->captions.save(f.export(),f.clip(),edit(initial))));
        assertEquals("CAPTION_CONFIRMATION_REQUIRED",failure(()->captions.readConfirmed(f.export(),f.clip(),1)));
        assertEquals(originalParent,jobs.get(f.parent().job()));assertEquals(originalExport,exports.get(f.export()));
    }
    @Test void emptyCaptionsNeedAcknowledgementAndHaveValidZeroByteHeadDelivery()throws Exception{
        var f=fixture(0,4000,false);var initial=captions.initialize(f.export(),f.clip());var input=edit(initial);input.putArray("cues");
        var empty=captions.save(f.export(),f.clip(),input);assertTrue(empty.cues().isEmpty());
        assertEquals("INVALID_CAPTION_REVIEW",failure(()->captions.confirm(f.export(),f.clip(),confirmation(empty.revision(),false))));
        captions.confirm(f.export(),f.clip(),confirmation(empty.revision(),true));
        for(String format:List.of("srt","txt")){
            var file=send("GET",f.path()+"/files/"+format+"?revision=1",null);assertEquals(200,file.statusCode());assertEquals(0,file.body().length);
            var head=send("HEAD",f.path()+"/files/"+format+"?revision=1",null);assertEquals(200,head.statusCode());assertEquals("0",head.headers().firstValue("Content-Length").orElseThrow());
        }
        assertTrue(new String(send("GET",f.path()+"/files/vtt?revision=1",null).body(),StandardCharsets.UTF_8).startsWith("WEBVTT"));
    }
    @Test void duplicateMissingAndUnconfirmedBatchSelectionsNeverProduceAPartialZip()throws Exception{
        var f=fixture(0,1000,true);String second=f.batch().clips().get(1).id();
        captions.initialize(f.export(),f.clip());captions.confirm(f.export(),f.clip(),confirmation(0,true));captions.initialize(f.export(),second);
        String base="/api/exports/"+f.export()+"/package?items=";
        assertEquals(400,send("GET",base+f.clip()+".0,"+f.clip()+".0",null).statusCode());
        assertNotEquals(200,send("GET",base+f.clip()+".0,"+UUID.randomUUID()+".0",null).statusCode());
        assertEquals(409,send("GET",base+f.clip()+".0,"+second+".0",null).statusCode());
        assertEquals(409,send("POST","/api/exports/"+f.export()+"/package",Map.of("items",List.of(Map.of("clip_id",f.clip(),"caption_revision",0),Map.of("clip_id",second,"caption_revision",0)))).statusCode());
    }
    @Test void cueIdentityMetadataTimesAndTypesCannotBeForged()throws Exception{
        var f=fixture(0,4000,false);var snapshot=captions.initialize(f.export(),f.clip());
        List<java.util.function.Consumer<ObjectNode>> invalid=List.of(
            a->((ObjectNode)a.path("cues").get(0)).put("requires_review",false),
            a->((ObjectNode)a.path("cues").get(0)).put("source_text","伪造来源"),
            a->((ObjectNode)a.path("cues").get(0)).put("id","invented"),
            a->((ObjectNode)a.path("cues").get(0)).put("end_ms",5000),
            a->((ObjectNode)a.path("cues").get(0)).put("start_ms",-1),
            a->((ObjectNode)a.path("cues").get(0)).put("start_ms",0.1),
            a->((ObjectNode)a.path("cues").get(0)).put("text",""),
            a->((ObjectNode)a.path("cues").get(1)).put("start_ms",500),
            a->((ObjectNode)a.path("cues").get(0)).put("reviewed","true"));
        for(var mutate:invalid){var input=edit(snapshot);mutate.accept(input);assertEquals("INVALID_CAPTION_REVIEW",failure(()->captions.save(f.export(),f.clip(),input)));}
        assertEquals(snapshot,captions.get(f.export(),f.clip(),null));
    }
    @Test void concurrentCaptionSavesCannotOverwriteEachOther()throws Exception{
        var f=fixture(0,4000,false);var snapshot=captions.initialize(f.export(),f.clip());var a=edit(snapshot);var b=edit(snapshot);
        ((ObjectNode)a.path("cues").get(0)).put("text","甲版");((ObjectNode)b.path("cues").get(0)).put("text","乙版");
        var first=CompletableFuture.supplyAsync(()->saveOutcome(f,a));var second=CompletableFuture.supplyAsync(()->saveOutcome(f,b));
        assertEquals(Set.of("SAVED","CAPTION_REVISION_CONFLICT"),Set.of(first.join(),second.join()));assertEquals(1,captions.get(f.export(),f.clip(),null).revision());
    }
    @Test void sourceAndStoredCaptionCorruptionFailClosedWithoutReinitializingHistory()throws Exception{
        var f=fixture(0,4000,false);captions.initialize(f.export(),f.clip());
        jdbc.update("UPDATE caption_document SET basis_hash=? WHERE clip_id=?","0".repeat(64),f.clip());
        assertEquals("CAPTION_SOURCE_INVALID",failure(()->captions.initialize(f.export(),f.clip())));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM caption_revision WHERE clip_id=?",Integer.class,f.clip()));
    }
    @Test void corruptRevisionAndSameSizeMp4TamperingAreDetected()throws Exception{
        var f=fixture(0,4000,false);captions.initialize(f.export(),f.clip());
        jdbc.update("UPDATE caption_revision SET content_hash=? WHERE clip_id=? AND revision=0","0".repeat(64),f.clip());
        assertEquals("CAPTION_CONTENT_INVALID",failure(()->captions.get(f.export(),f.clip(),null)));
        var other=fixture(0,4000,false);captions.initialize(other.export(),other.clip());Path media=exports.content(other.export(),other.clip()).path();Files.write(media,new byte[]{9,9,9,9});
        assertEquals("ARTIFACT_HASH_MISMATCH",failure(()->captions.get(other.export(),other.clip(),null)));
    }
    @Test void captionRevisionsAndConfirmationSurviveBackendRestart()throws Exception{
        var f=fixture(0,4000,false);captions.initialize(f.export(),f.clip());var before=captions.confirm(f.export(),f.clip(),confirmation(0,true));
        app.close();startContext();assertEquals(before,captions.readConfirmed(f.export(),f.clip(),0));
    }
    @Test void multiPartIntentCaptionsUseTheConcatenatedTimelineInsteadOfTheSourceEnvelope()throws Exception{
        var parent=parent();var clips=app.getBean(ClipRequestService.class);
        var request=clips.create(parent.job(),new ClipRequestModels.CreateRequest("保留第一句和第三句",4),"caption-intent-"+UUID.randomUUID());
        var lease=claim(request.stage().id());String prefix=prefix(lease);
        ObjectNode plan=JSON.createObjectNode().put("schema_version","1.0").put("request_id",request.id()).put("job_id",parent.job()).put("video_id",parent.video()).put("source_hash",parent.hash()).put("transcript_hash",request.source().transcriptHash()).put("duration_ms",4000).put("instruction",request.instruction());
        plan.putArray("goals").addObject().put("goal_id","selected").put("description","选中的原句");plan.putObject("constraints").put("keep_explanation_complete",true).putArray("exclude");
        var output=plan.putArray("outputs").addObject().put("output_id","out-selected").put("goal_id","selected").put("title","不连续片段").put("status","FOUND").put("reason","合成来源").put("enabled",false);
        var parts=output.putArray("parts");for(int index:List.of(0,2))parts.addObject().put("start_utterance_id","u"+(index+1)).put("end_utterance_id","u"+(index+1)).put("start_ms",index*1000).put("end_ms",(index+1)*1000);
        output.putArray("excluded");output.putArray("evidence_utterance_ids").add("u1").add("u3");plan.putObject("provenance").put("text_model_id","qwen-plus").put("prompt_version","intent-clips-v1").put("retrieval_mode","bm25").put("index_hash","a".repeat(64));
        byte[] bytes=JSON.writeValueAsBytes(plan);write(prefix+"clip-plan.json",bytes);var manifest=manifest(lease,"model_result","DRAFT");manifest.putArray("artifacts").addObject().put("kind","clip_plan").put("key",prefix+"clip-plan.json").put("sha256",sha(bytes));complete(lease,manifest);
        var ready=clips.get(request.id());ObjectNode selected=(ObjectNode)ready.plan().deepCopy();((ObjectNode)selected.path("outputs").get(0)).put("enabled",true);
        var saved=clips.save(request.id(),JSON.createObjectNode().put("base_revision",0).set("outputs",selected.path("outputs")));
        clips.confirm(saved.id(),JSON.valueToTree(Map.of("revision",saved.revision(),"plan_hash",saved.planHash())));
        var batch=exports.createClipRequest(saved.id(),JSON.valueToTree(Map.of("revision",saved.revision(),"plan_hash",saved.planHash(),"profile","h264-aac-v1")));publishExports(batch);batch=exports.get(batch.id());
        var caption=captions.initialize(batch.id(),batch.clips().getFirst().id());assertEquals(2000,caption.source().durationMs());assertEquals(2,caption.cues().size());
        assertEquals("u3",caption.cues().get(1).sourceUtteranceId());assertEquals(1000,caption.cues().get(1).startMs());assertEquals(1,caption.cues().get(1).partIndex());assertFalse(caption.cues().get(1).boundaryPartial());
    }
    private String saveOutcome(Fixture f,JsonNode input){try{captions.save(f.export(),f.clip(),input);return "SAVED";}catch(JobException e){return e.getCode();}}
    private static ObjectNode edit(CaptionModels.Snapshot snapshot){var input=JSON.createObjectNode().put("base_revision",snapshot.revision());var cues=input.putArray("cues");for(var cue:snapshot.cues())cues.addObject().put("id",cue.id()).put("start_ms",cue.startMs()).put("end_ms",cue.endMs()).put("text",cue.text()).put("reviewed",cue.reviewed());return input;}
    private static JsonNode confirmation(long revision,boolean acknowledged){return JSON.createObjectNode().put("revision",revision).put("acknowledge_review",acknowledged);}
    private static String failure(Runnable action){RuntimeException e=assertThrows(RuntimeException.class,action::run);if(e instanceof JobException j)return j.getCode();if(e instanceof VideoApiException v)return v.getCode();throw new AssertionError(e);}
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
    private ClaimResult claim(String stage){return jobs.claim(new ClaimRequest(stage,"caption-synthetic-worker",UUID.randomUUID().toString()));}
    private static ObjectNode manifest(ClaimResult lease,String kind,String review){return JSON.createObjectNode().put("schema_version",1).put("job_id",lease.jobId()).put("video_id",lease.videoId()).put("analysis_generation",lease.generation()).put("stage_id",lease.stageId()).put("stage_type",lease.stageType()).put("attempt",lease.attempt()).put("run_token",lease.runToken()).put("input_sha256",lease.sourceHash()).put("data_kind",kind).put("review_status",review);}
    private void complete(ClaimResult lease,ObjectNode manifest)throws Exception{byte[] bytes=JSON.writeValueAsBytes(manifest);String key=prefix(lease)+"manifest.json";write(key,bytes);jobs.complete(lease.stageId(),new CompleteRequest(lease.attempt(),lease.runToken(),key,sha(bytes)));}
    private static String prefix(ClaimResult lease){return "jobs/"+lease.jobId()+"/stages/"+lease.stageId()+"/attempts/"+lease.attempt()+"/"+lease.runToken()+"/";}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void write(String key,byte[] bytes)throws Exception{Path path=root.resolve(key);Files.createDirectories(path.getParent());Files.write(path,bytes);}
    private HttpResponse<byte[]> send(String method,String path,Object body)throws Exception{var request=HttpRequest.newBuilder(base.resolve(path));if(body!=null)request.header("Content-Type","application/json");return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());}
}
