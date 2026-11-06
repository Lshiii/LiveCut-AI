package io.livecut.captions;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import io.livecut.LivecutBackendApplication;
import io.livecut.clips.ClipRequestService;
import io.livecut.exports.ExportModels;
import io.livecut.exports.ExportService;
import io.livecut.jobs.*;
import io.livecut.jobs.JobModels.*;
import io.livecut.topics.TopicReviewService;
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

/** Dedicated MySQL + real HTTP with synthetic, hashed source media; never calls providers or FFmpeg. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaptionRenderIntegrationTests {
    @TempDir static Path root;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private ConfigurableApplicationContext app;private JdbcTemplate jdbc;private JobService jobs;private ExportService exports;private CaptionService captions;private CaptionRenderService renders;
    private URI base;private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> ownJobs=new ArrayList<>(),ownVideos=new ArrayList<>();
    private record Parent(String job,String video,String hash,JsonNode utterances) {}
    private record Fixture(Parent parent,ExportModels.Snapshot batch) {
        String export(){return batch.id();}String clip(){return batch.clips().getFirst().id();}
        String path(){return "/api/exports/"+export()+"/clips/"+clip()+"/caption-renders";}
    }
    @BeforeAll void start(){
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"));
        String font=System.getenv("LIVECUT_CAPTION_FONT_PATH");if(font==null||font.isBlank())font=Path.of("../data/fonts/noto-sans-cjk-sc-2.004/NotoSansCJKsc-Regular.otf").toAbsolutePath().normalize().toString();
        assertTrue(Files.isRegularFile(Path.of(font)),"Install the fixed caption font before render integration tests");
        app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,"--livecut.captions.render-font-path="+font,
            "--livecut.worker.api-key=caption-render-test-key","--livecut.workers.require-registration=false","--livecut.jobs.scan-enabled=false","--livecut.jobs.retry-delay-seconds=0","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false",
            "--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);jobs=app.getBean(JobService.class);exports=app.getBean(ExportService.class);captions=app.getBean(CaptionService.class);renders=app.getBean(CaptionRenderService.class);
        base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup(){for(String id:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);for(String id:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);ownJobs.clear();ownVideos.clear();}
    @AfterAll void stop(){if(app!=null)app.close();}
    private CaptionModels.Snapshot confirm(Fixture f){captions.initialize(f.export(),f.clip());return captions.confirm(f.export(),f.clip(),JSON.valueToTree(Map.of("revision",0,"acknowledge_review",true)));}
    private ObjectNode body(CaptionModels.Snapshot caption){return JSON.createObjectNode().put("caption_revision",caption.revision()).put("caption_content_hash",caption.contentHash()).put("style_preset","WHITE_OUTLINE_V1").put("frame_preset","SOURCE");}
    private CaptionRenderModels.Snapshot create(Fixture f,CaptionModels.Snapshot caption,String key){return renders.create(f.export(),f.clip(),key,body(caption));}
    private String stage(CaptionRenderModels.Snapshot render){return jdbc.queryForObject("SELECT stage_id FROM export_clip WHERE export_id=?",String.class,render.id());}
    private static String failure(Runnable action){return assertThrows(JobException.class,action::run).getCode();}
    private static ObjectNode progress(ClaimResult lease,long sequence,String phase,long processed,long total){return JSON.createObjectNode().put("attempt",lease.attempt()).put("run_token",lease.runToken()).put("sequence",sequence).put("phase",phase).put("processed_ms",processed).put("total_ms",total);}

    @Test void strictConfirmationIdentityIdempotencyAndCleanResourcesStayIndependent()throws Exception{
        var f=fixture(0,4000,false);var initial=captions.initialize(f.export(),f.clip());
        assertEquals("CAPTION_RENDER_REQUIRES_CONFIRMATION",failure(()->create(f,initial,"unconfirmed")));
        var confirmed=confirm(f);var parent=jobs.get(f.parent().job());var clean=exports.get(f.export());
        assertEquals("CAPTION_RENDER_INVALID_INPUT",failure(()->renders.create(f.export(),f.clip(),null,body(confirmed))));
        assertEquals("CAPTION_RENDER_INVALID_INPUT",failure(()->renders.create(f.export(),f.clip(),"bad",body(confirmed).put("extra",true))));
        assertEquals("CAPTION_RENDER_REVISION_MISMATCH",failure(()->renders.create(f.export(),f.clip(),"mismatch",body(confirmed).put("caption_content_hash","0".repeat(64)))));
        var first=create(f,confirmed,"one");assertEquals(first,create(f,confirmed,"one"));assertEquals("QUEUED",first.status());assertEquals(0,first.attempt());assertEquals(1,first.stateVersion());assertNull(first.contentUrl());assertNull(first.media());assertTrue(first.actions().canCancel());
        assertEquals("IDEMPOTENCY_CONFLICT",failure(()->renders.create(f.export(),f.clip(),"one",body(confirmed).put("frame_preset","PORTRAIT_720"))));
        assertEquals(first.id(),renders.list(f.export(),f.clip()).items().getFirst().id());assertEquals(clean,exports.list(f.parent().job(),20,0).items().getFirst());
        assertEquals(parent,jobs.get(f.parent().job()));assertEquals(clean,exports.get(f.export()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.parent().job()));
        String json=JSON.writeValueAsString(first);for(String secret:List.of("jobs/","cues_key","run_token","NotoSansCJKsc-Regular.otf"))assertFalse(json.contains(secret));
        var draft=JSON.createObjectNode().put("base_revision",0);var cues=draft.putArray("cues");for(var cue:confirmed.cues())cues.addObject().put("id",cue.id()).put("start_ms",cue.startMs()).put("end_ms",cue.endMs()).put("text","新草稿").put("reviewed",true);captions.save(f.export(),f.clip(),draft);
        assertEquals(0,create(f,confirmed,"old-confirmed").captionRevision());
    }
    @Test void concurrentSameKeyCreatesOneExportAndDifferentKeysRemainIndependent()throws Exception{
        var f=fixture(0,4000,false);var caption=confirm(f);
        var one=CompletableFuture.supplyAsync(()->create(f,caption,"concurrent"));var two=CompletableFuture.supplyAsync(()->create(f,caption,"concurrent"));
        assertEquals(one.get().id(),two.get().id());assertNotEquals(one.get().id(),create(f,caption,"new-key").id());assertEquals(2,renders.list(f.export(),f.clip()).items().size());
    }
    @Test void allStylesAndFramesFreezeExactGeometryAndPrivateContentAddressedInputs()throws Exception{
        var f=fixture(0,4000,false);var caption=confirm(f);
        Map<String,List<Integer>> sizes=Map.of("SOURCE",List.of(640,360),"LANDSCAPE_720",List.of(1280,720),"PORTRAIT_720",List.of(720,1280),"PORTRAIT_BLUR_720",List.of(720,1280));
        assertEquals(sizes.keySet(),CaptionRenderSpec.FRAMES);
        for(String style:CaptionRenderSpec.STYLES)for(String frame:CaptionRenderSpec.FRAMES){var render=renders.create(f.export(),f.clip(),style+frame,body(caption).put("style_preset",style).put("frame_preset",frame));
            var frozen=JSON.readTree(jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?",String.class,render.id())).path("caption_render");CaptionRenderSpec.validate(frozen);
            assertEquals(sizes.get(frame).get(0).intValue(),frozen.path("output").path("width").intValue());assertEquals(sizes.get(frame).get(1).intValue(),frozen.path("output").path("height").intValue());
            for(String part:List.of("font","captions")){var node=frozen.path(part);String key=node.path(part.equals("font")?"key":"cues_key").asText(),hash=node.path(part.equals("font")?"sha256":"cues_sha256").asText();assertEquals(hash,sha(Files.readAllBytes(root.resolve(key))));}
        }
    }
    @Test void portraitBlurIsAnIndependentFrozenRenderAndKeepsLegacyGeometryAndReadsUnchanged()throws Exception{
        var f=fixture(0,4000,false);var caption=confirm(f);var clean=exports.get(f.export());var parent=jobs.get(f.parent().job());
        Map<String,List<Integer>> sizes=Map.of("SOURCE",List.of(640,360),"LANDSCAPE_720",List.of(1280,720),"PORTRAIT_720",List.of(720,1280));
        Map<String,CaptionRenderModels.Snapshot> old=new HashMap<>();Map<String,String> frozenBefore=new HashMap<>();Map<String,byte[]> httpBefore=new HashMap<>();
        for(String frame:sizes.keySet()){
            var created=renders.create(f.export(),f.clip(),"legacy-"+frame,body(caption).put("frame_preset",frame));var lease=claim(stage(created));complete(lease,renderManifest(lease));
            var done=renders.get(created.id());assertEquals("SUCCEEDED",done.status());assertEquals(sizes.get(frame).get(0).intValue(),done.media().width());assertEquals(sizes.get(frame).get(1).intValue(),done.media().height());old.put(frame,done);
            frozenBefore.put(frame,jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?",String.class,done.id()));
            var response=send("GET","/api/caption-renders/"+done.id(),null,null);assertEquals(200,response.statusCode());httpBefore.put(frame,response.body());
        }
        var portrait=old.get("PORTRAIT_720");var blurBody=body(caption).put("frame_preset","PORTRAIT_BLUR_720");
        assertEquals("IDEMPOTENCY_CONFLICT",failure(()->renders.create(f.export(),f.clip(),"legacy-PORTRAIT_720",blurBody)));
        var conflictingRequest=HttpRequest.newBuilder(base.resolve(f.path())).header("Content-Type","application/json").header("Idempotency-Key","legacy-PORTRAIT_720").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(blurBody))).build();
        var conflict=http.send(conflictingRequest,HttpResponse.BodyHandlers.ofByteArray());assertEquals(409,conflict.statusCode());assertEquals("IDEMPOTENCY_CONFLICT",JSON.readTree(conflict.body()).path("error_code").asText());
        var blur=renders.create(f.export(),f.clip(),"new-blur",blurBody);assertEquals(blur,renders.create(f.export(),f.clip(),"new-blur",blurBody));assertNotEquals(portrait.id(),blur.id());
        var portraitFrozen=JSON.readTree(frozenBefore.get("PORTRAIT_720"));var blurFrozen=JSON.readTree(jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?",String.class,blur.id()));
        assertNotEquals(portraitFrozen.path("caption_render_hash").asText(),blurFrozen.path("caption_render_hash").asText());
        assertNotEquals(jdbc.queryForObject("SELECT snapshot_hash FROM export_job WHERE id=?",String.class,portrait.id()),jdbc.queryForObject("SELECT snapshot_hash FROM export_job WHERE id=?",String.class,blur.id()));
        assertNotEquals(jdbc.queryForObject("SELECT input_hash FROM job_stage WHERE id=?",String.class,stage(portrait)),jdbc.queryForObject("SELECT input_hash FROM job_stage WHERE id=?",String.class,stage(blur)));
        var expectedSpec=(ObjectNode)portraitFrozen.path("caption_render").deepCopy();expectedSpec.put("frame_preset","PORTRAIT_BLUR_720");
        assertEquals(ClipRequestService.canonicalJson(expectedSpec),ClipRequestService.canonicalJson(blurFrozen.path("caption_render")));
        assertEquals("h264-aac-burn-v1",blurFrozen.path("encoding_profile").asText());assertEquals("caption-burn-v1",blurFrozen.path("caption_render").path("profile_version").asText());
        var lease=claim(stage(blur));var wrong=renderManifest(lease);byte[] wrongMedia=JSON.writeValueAsBytes(Map.of("duration_ms",4000,"video_codec","h264","audio_codec","aac","frame_rate",30,"width",640,"height",360));write(prefix(lease)+"media-info.json",wrongMedia);
        for(var ref:wrong.path("artifacts"))if(ref.path("kind").asText().equals("media_info"))((ObjectNode)ref).put("sha256",sha(wrongMedia));assertThrows(JobException.class,()->complete(lease,wrong));
        complete(lease,renderManifest(lease));var done=renders.get(blur.id());assertEquals("SUCCEEDED",done.status());assertEquals(720,done.media().width());assertEquals(1280,done.media().height());assertEquals(200,send("GET",done.contentUrl(),null,null).statusCode());
        for(String frame:sizes.keySet()){
            var legacy=old.get(frame);assertEquals(legacy,renders.get(legacy.id()));assertEquals(frozenBefore.get(frame),jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?",String.class,legacy.id()));
            var response=send("GET","/api/caption-renders/"+legacy.id(),null,null);assertEquals(200,response.statusCode());assertArrayEquals(httpBefore.get(frame),response.body());
        }
        assertEquals(4,renders.list(f.export(),f.clip()).items().size());assertEquals(clean,exports.get(f.export()));assertEquals(parent,jobs.get(f.parent().job()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,f.parent().job()));
    }
    @Test void emptyAndSubCentisecondConfirmedCaptionsAreRejectedWithoutRenderRows()throws Exception{
        var f=fixture(0,4000,false);var caption=confirm(f);var edit=JSON.createObjectNode().put("base_revision",0);edit.putArray("cues");var empty=captions.save(f.export(),f.clip(),edit);captions.confirm(f.export(),f.clip(),JSON.valueToTree(Map.of("revision",1,"acknowledge_review",true)));
        assertEquals("CAPTION_RENDER_EMPTY",failure(()->create(f,empty,"empty")));
        var tiny=JSON.createObjectNode().put("base_revision",1);tiny.putArray("cues").addObject().put("id",caption.cues().getFirst().id()).put("start_ms",1).put("end_ms",9).put("text","短句").put("reviewed",true);
        var shortCue=captions.save(f.export(),f.clip(),tiny);captions.confirm(f.export(),f.clip(),JSON.valueToTree(Map.of("revision",2,"acknowledge_review",true)));
        assertEquals("CAPTION_RENDER_TIMING_UNSUPPORTED",failure(()->create(f,shortCue,"tiny")));assertTrue(renders.list(f.export(),f.clip()).items().isEmpty());
    }
    @Test void queuedCancellationIsIdempotentAndNewKeyRebuildsWithoutRevivingCancelledOwner()throws Exception{
        var f=fixture(0,4000,false);var caption=confirm(f);var clean=exports.get(f.export());var parent=jobs.get(f.parent().job());var render=create(f,caption,"cancel");
        var cancelled=renders.cancel(render.id());assertEquals("CANCELLED",cancelled.status());assertEquals(cancelled,renders.cancel(render.id()));assertEquals(cancelled,create(f,caption,"cancel"));assertFalse(cancelled.actions().canRetry());
        assertEquals("CAPTION_RENDER_NOT_RETRYABLE",failure(()->renders.retry(render.id())));assertEquals("NO_WORK",claim(stage(render)).outcome());exports.refreshLocked(render.id());assertEquals("CANCELLED",renders.get(render.id()).status());
        assertEquals("QUEUED",create(f,caption,"after-cancel").status());assertEquals(clean,exports.get(f.export()));assertEquals(parent,jobs.get(f.parent().job()));
    }
    @Test void runningCancelFencesAllOldCallbacksAndPreservesLastRealProgress()throws Exception{
        var f=fixture(0,4000,false);var render=create(f,confirm(f),"running");var lease=claim(stage(render));jobs.renderProgress(lease.stageId(),progress(lease,1,"ENCODING",1000,4000));
        var cancelled=renders.cancel(render.id());assertEquals(1000,cancelled.progress().processedMs());assertNull(cancelled.contentUrl());
        assertEquals("STALE_ATTEMPT",failure(()->jobs.heartbeat(lease.stageId(),new Credential(lease.attempt(),lease.runToken()))));assertEquals("STALE_ATTEMPT",failure(()->jobs.fail(lease.stageId(),new FailRequest(lease.attempt(),lease.runToken(),"MEDIA_FAILED",false))));
        assertEquals("STALE_ATTEMPT",failure(()->jobs.renderProgress(lease.stageId(),progress(lease,2,"PUBLISHING",4000,4000))));assertEquals("STALE_ATTEMPT",failure(()->jobs.complete(lease.stageId(),new CompleteRequest(lease.attempt(),lease.runToken(),prefix(lease)+"manifest.json","a".repeat(64)))));
        assertEquals(cancelled,renders.get(render.id()));
    }
    @Test void progressIsMonotonicAndAttemptScopedAcrossBoundedRetry()throws Exception{
        var f=fixture(0,4000,false);var render=create(f,confirm(f),"progress");var lease=claim(stage(render));long version=renders.get(render.id()).stateVersion();
        assertEquals("ACCEPTED",jobs.renderProgress(lease.stageId(),progress(lease,1,"PREPARING",0,4000)).outcome());assertTrue(renders.get(render.id()).stateVersion()>version);
        assertEquals("ACCEPTED",jobs.renderProgress(lease.stageId(),progress(lease,3,"ENCODING",2000,4000)).outcome());long current=renders.get(render.id()).stateVersion();
        assertEquals("ALREADY_APPLIED",jobs.renderProgress(lease.stageId(),progress(lease,2,"PREPARING",0,4000)).outcome());assertEquals(current,renders.get(render.id()).stateVersion());
        assertEquals("INVALID_RENDER_PROGRESS",failure(()->jobs.renderProgress(lease.stageId(),progress(lease,4,"PREPARING",2000,4000))));assertEquals("INVALID_RENDER_PROGRESS",failure(()->jobs.renderProgress(lease.stageId(),progress(lease,4,"ENCODING",2001,5000))));
        jobs.fail(lease.stageId(),new FailRequest(lease.attempt(),lease.runToken(),"MEDIA_FAILED",false));assertTrue(renders.get(render.id()).actions().canRetry());assertNull(renders.retry(render.id()).progress());var next=claim(stage(render));assertEquals(2,next.attempt());
        assertEquals("STALE_ATTEMPT",failure(()->jobs.renderProgress(lease.stageId(),progress(lease,99,"PUBLISHING",4000,4000))));assertNull(renders.get(render.id()).progress());jobs.renderProgress(next.stageId(),progress(next,1,"ENCODING",100,4000));
        jobs.fail(next.stageId(),new FailRequest(next.attempt(),next.runToken(),"MEDIA_FAILED",false));renders.retry(render.id());var last=claim(stage(render));jobs.fail(last.stageId(),new FailRequest(last.attempt(),last.runToken(),"MEDIA_FAILED",false));
        assertFalse(renders.get(render.id()).actions().canRetry());assertEquals("ATTEMPTS_EXHAUSTED",failure(()->renders.retry(render.id())));
    }
    @Test void verifiedCaptionedCompletionSupportsHeadRangeAndRejectsTamperingOrRecursiveCaptions()throws Exception{
        var f=fixture(0,4000,false);var render=create(f,confirm(f),"complete");var lease=claim(stage(render));var manifest=renderManifest(lease);complete(lease,manifest);
        var done=renders.get(render.id());assertEquals("SUCCEEDED",done.status());assertNotNull(done.media());assertEquals(640,done.media().width());assertEquals(4,done.sizeBytes());
        assertEquals(200,send("HEAD",done.contentUrl(),null,null).statusCode());var range=send("GET",done.contentUrl(),null,"bytes=1-2");assertEquals(206,range.statusCode());assertArrayEquals(new byte[]{2,3},range.body());
        String clip=jdbc.queryForObject("SELECT derived_clip_id FROM caption_render WHERE id=?",String.class,render.id());assertEquals("CAPTION_SOURCE_INVALID",failure(()->captions.initialize(render.id(),clip)));
        Files.write(root.resolve(prefix(lease)+"clip.mp4"),new byte[]{4,3,2,1});assertEquals(409,send("GET",done.contentUrl(),null,null).statusCode());assertEquals("CAPTION_RENDER_NOT_CANCELLABLE",failure(()->renders.cancel(render.id())));
    }
    @Test void oldCleanManifestOrMismatchedFrozenRenderMetadataCannotPublishSuccess()throws Exception{
        var f=fixture(0,4000,false);var render=create(f,confirm(f),"identity");var lease=claim(stage(render));
        var clean=renderManifest(lease).put("data_kind","reviewed_export");assertThrows(JobException.class,()->complete(lease,clean));assertEquals("RUNNING",renders.get(render.id()).status());
        var wrong=renderManifest(lease).put("caption_render_hash","0".repeat(64));assertThrows(JobException.class,()->complete(lease,wrong));
        var valid=renderManifest(lease);byte[] bad=JSON.writeValueAsBytes(Map.of("duration_ms",4000,"video_codec","h264","audio_codec","aac","frame_rate",30,"width",720,"height",1280));write(prefix(lease)+"media-info.json",bad);
        for(var ref:valid.path("artifacts"))if(ref.path("kind").asText().equals("media_info"))((ObjectNode)ref).put("sha256",sha(bad));assertThrows(JobException.class,()->complete(lease,valid));
        var noAudio=renderManifest(lease);var audioMetadata=JSON.createObjectNode().put("duration_ms",4000).put("video_codec","h264").put("frame_rate",30).put("width",640).put("height",360);audioMetadata.putNull("audio_codec");byte[] audioBytes=JSON.writeValueAsBytes(audioMetadata);write(prefix(lease)+"media-info.json",audioBytes);
        for(var ref:noAudio.path("artifacts"))if(ref.path("kind").asText().equals("media_info"))((ObjectNode)ref).put("sha256",sha(audioBytes));assertThrows(JobException.class,()->complete(lease,noAudio));
        var wrongInfo=renderManifest(lease);var claimSpec=JSON.valueToTree(((Map<?,?>)lease.config().get("export")).get("caption_render")).deepCopy();((ObjectNode)claimSpec).put("style_preset","DARK_BOX_V1");
        byte[] infoBytes=JSON.writeValueAsBytes(Map.of("schema_version",1,"caption_render_hash",((Map<?,?>)lease.config().get("export")).get("caption_render_hash"),"caption_render",claimSpec));write(prefix(lease)+"render-info.json",infoBytes);
        for(var ref:wrongInfo.path("artifacts"))if(ref.path("kind").asText().equals("caption_render_info"))((ObjectNode)ref).put("sha256",sha(infoBytes));assertThrows(JobException.class,()->complete(lease,wrongInfo));
        complete(lease,renderManifest(lease));assertEquals("SUCCEEDED",renders.get(render.id()).status());
    }
    @Test void publicHttpCreationPrivateProgressAndCancelRoundTripNeverExposeKeys()throws Exception{
        var f=fixture(0,4000,false);assertEquals(200,send("GET",f.path(),null,null).statusCode());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM caption_document WHERE clip_id=?",Integer.class,f.clip()));
        var caption=confirm(f);assertEquals(400,send("POST",f.path(),body(caption),null).statusCode());
        var create=HttpRequest.newBuilder(base.resolve(f.path())).header("Content-Type","application/json").header("Idempotency-Key","http").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body(caption)))).build();
        var created=http.send(create,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,created.statusCode());var snapshot=JSON.readTree(created.body());String id=snapshot.path("id").asText();
        assertTrue(snapshot.has("font_profile"));for(String field:List.of("error_code","diagnostic","progress","content_url","download_url","size_bytes","media"))assertTrue(snapshot.path(field).isNull());
        var render=renders.get(id);var lease=claim(stage(render));String route="/internal/stages/"+lease.stageId()+"/render-progress";var payload=progress(lease,1,"ENCODING",1000,4000);
        assertEquals(401,send("POST",route,payload,null).statusCode());var update=HttpRequest.newBuilder(base.resolve(route)).header("Content-Type","application/json").header("X-Worker-Key","caption-render-test-key").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload))).build();
        assertEquals(200,http.send(update,HttpResponse.BodyHandlers.ofByteArray()).statusCode());assertEquals(1000,renders.get(id).progress().processedMs());
        assertEquals(200,send("POST","/api/caption-renders/"+id+"/cancel",null,null).statusCode());assertEquals("CANCELLED",renders.get(id).status());assertEquals(409,send("POST","/api/caption-renders/"+id+"/retry",null,null).statusCode());
    }
    @Test void deterministicCaptionErrorsCannotWasteRetryThroughEitherPublicRoute()throws Exception{
        var f=fixture(0,4000,false);var caption=confirm(f);
        for(String code:CaptionRenderSpec.INPUT_REQUIRES_NEW_RENDER){var render=create(f,caption,code);var lease=claim(stage(render));jobs.fail(lease.stageId(),new FailRequest(lease.attempt(),lease.runToken(),code,true));
            var failed=renders.get(render.id());assertEquals("FAILED",failed.status());assertFalse(failed.actions().canRetry());assertEquals("INPUT_REQUIRES_NEW_RENDER",failed.actions().retryBlockReason());
            assertEquals("CAPTION_RENDER_NOT_RETRYABLE",failure(()->renders.retry(render.id())));String clip=jdbc.queryForObject("SELECT derived_clip_id FROM caption_render WHERE id=?",String.class,render.id());
            assertEquals("CAPTION_RENDER_NOT_RETRYABLE",failure(()->exports.retry(render.id(),clip)));assertEquals(1,renders.get(render.id()).attempt());}
    }
    private ObjectNode renderManifest(ClaimResult lease)throws Exception{
        var config=(Map<?,?>)lease.config().get("export");var spec=JSON.valueToTree(config.get("caption_render"));String prefix=prefix(lease);byte[] video={1,2,3,4};
        byte[] media=JSON.writeValueAsBytes(Map.of("duration_ms",spec.path("input_media").path("duration_ms").longValue(),"video_codec","h264","audio_codec","aac","frame_rate",30,"width",spec.path("output").path("width").intValue(),"height",spec.path("output").path("height").intValue()));
        byte[] info=JSON.writeValueAsBytes(Map.of("schema_version",1,"caption_render_hash",config.get("caption_render_hash"),"caption_render",spec));write(prefix+"clip.mp4",video);write(prefix+"media-info.json",media);write(prefix+"render-info.json",info);
        var manifest=manifest(lease,"captioned_export","CONFIRMED").put("stage_input_hash",lease.inputHash()).put("render_mode","CAPTIONED").put("caption_render_hash",String.valueOf(config.get("caption_render_hash")));
        for(String field:List.of("export_id","clip_id","snapshot_hash","segment_id","encoding_profile"))manifest.put(field,String.valueOf(config.get(field)));for(String field:List.of("approved_revision","start_ms","end_ms"))manifest.put(field,((Number)config.get(field)).longValue());
        var refs=manifest.putArray("artifacts");refs.addObject().put("kind","clip").put("key",prefix+"clip.mp4").put("sha256",sha(video));refs.addObject().put("kind","media_info").put("key",prefix+"media-info.json").put("sha256",sha(media));refs.addObject().put("kind","caption_render_info").put("key",prefix+"render-info.json").put("sha256",sha(info));return manifest;
    }
    private HttpResponse<byte[]> send(String method,String path,Object body,String range)throws Exception{var request=HttpRequest.newBuilder(base.resolve(path));if(body!=null)request.header("Content-Type","application/json");if(range!=null)request.header("Range",range);return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());}
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
            byte[] media=JSON.writeValueAsBytes(Map.of("duration_ms",duration,"video_codec","h264","audio_codec","aac","frame_rate",30,"width",640,"height",360));write(prefix+"clip.mp4",video);write(prefix+"media-info.json",media);
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
}
