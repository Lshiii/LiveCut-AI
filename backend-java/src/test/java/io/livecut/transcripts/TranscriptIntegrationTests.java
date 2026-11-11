package io.livecut.transcripts;

import static org.junit.jupiter.api.Assertions.*;

import io.livecut.LivecutBackendApplication;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Isolated real MySQL/HTTP with directly registered synthetic ASR/TIMELINE artifacts; no business POST or worker. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TranscriptIntegrationTests {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> SOURCE_FIELDS=Set.of("job_id","video_id","generation","source_video_sha256","transcript_sha256","analysis_sha256","duration_ms","source_fingerprint");
    private static final Set<String> ITEM_FIELDS=Set.of("utterance_id","start_ms","end_ms","text");
    private static final Set<String> PAGE_FIELDS=Set.of("schema_version","data_kind","source","q","offset","limit","total_utterances","matched_utterances","next_offset","items");
    @TempDir static Path root;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> ownJobs=new ArrayList<>(),ownVideos=new ArrayList<>();
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private URI base;
    private record Sentence(String id,long start,long end,String text) {}
    private record Stage(String id,String type,String token,String manifestKey,String manifestHash,String artifactKey,String artifactHash) {}
    private record Fixture(String job,String video,int generation,String videoHash,long duration,List<Sentence> sentences,Stage media,Stage asr,Stage timeline) {}

    @BeforeAll void start(){
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"),"A dedicated test schema is required");
        app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,
            "--livecut.worker.api-key=transcript-synthetic-test-only","--livecut.jobs.scan-enabled=false","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false",
            "--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup(){
        for(String id:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);
        for(String id:ownVideos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);
        ownJobs.clear();ownVideos.clear();
    }
    @AfterAll void stop(){if(app!=null)app.close();}

    @Test void ordinaryPageAndContextExposeExactPublicSourceWithoutAnyBusinessWrites()throws Exception{
        var f=fixture(example());var before=databaseState();
        JsonNode page=page(f,"");assertEquals(PAGE_FIELDS,page.propertyNames());assertEquals("1.0",page.path("schema_version").asText());assertEquals("transcript_page",page.path("data_kind").asText());
        assertSource(f,page.path("source"));assertEquals("",page.path("q").asText());assertEquals(0,page.path("offset").asInt());assertEquals(20,page.path("limit").asInt());
        assertEquals(5,page.path("total_utterances").asInt());assertEquals(5,page.path("matched_utterances").asInt());assertTrue(page.path("next_offset").isNull());
        assertEquals(f.sentences().size(),page.path("items").size());for(int i=0;i<f.sentences().size();i++)assertSentence(f.sentences().get(i),page.path("items").get(i));
        String fingerprint=page.path("source").path("source_fingerprint").asText();
        JsonNode context=context(f,"u2",fingerprint);assertEquals(Set.of("schema_version","data_kind","source","center","previous","next"),context.propertyNames());
        assertEquals("1.0",context.path("schema_version").asText());assertEquals("transcript_context",context.path("data_kind").asText());assertEquals(page.path("source"),context.path("source"));
        assertSentence(f.sentences().get(0),context.path("previous"));assertSentence(f.sentences().get(1),context.path("center"));assertSentence(f.sentences().get(2),context.path("next"));
        String serialized=JSON.writeValueAsString(page)+JSON.writeValueAsString(context);
        for(String privateValue:List.of("manifest_key","run_token","source_key","source_chunk_id","jobs/",root.toAbsolutePath().toString(),f.asr().token()))assertFalse(serialized.contains(privateValue));
        assertEquals(before,databaseState(),"Reading original sentences must not create reviews, agent turns, clips, calls or change source rows");
        for(String table:List.of("review_document","review_revision","review_confirmation","model_call","agent_session","agent_turn","clip_request","export_job","caption_document")){
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE job_id=?",Integer.class,f.job()));
        }
    }

    @Test void originalSourceReadsDoNotWaitForBusinessRowLocks()throws Exception{
        var f=fixture(example());page(f,"");
        try(Connection lock=app.getBean(DataSource.class).getConnection()){
            lock.setAutoCommit(false);
            try(var statement=lock.prepareStatement("SELECT id FROM video_asset WHERE id=? FOR UPDATE")){statement.setString(1,f.video());try(var ignored=statement.executeQuery()){assertTrue(ignored.next());}}
            try(var statement=lock.prepareStatement("SELECT id FROM analysis_job WHERE id=? FOR UPDATE")){statement.setString(1,f.job());try(var ignored=statement.executeQuery()){assertTrue(ignored.next());}}
            try(var statement=lock.prepareStatement("SELECT id FROM job_stage WHERE job_id=? FOR UPDATE")){statement.setString(1,f.job());try(var ignored=statement.executeQuery()){assertTrue(ignored.next());}}
            var pending=http.sendAsync(request(path(f)+"?limit=1"),HttpResponse.BodyHandlers.ofByteArray());
            try{assertEquals(200,pending.get(5,TimeUnit.SECONDS).statusCode(),"Read-only source GET cannot acquire business FOR UPDATE locks");}
            finally{lock.rollback();pending.get(5,TimeUnit.SECONDS);}
        }
    }

    @Test void literalSearchPreservesOriginalTextAndUsesNfkcRootCaseAndEightAndTerms()throws Exception{
        var f=fixture(example());
        assertIds(page(f,"?q="+query("ｈｅｌｌｏ ｗｏｒｌｄ")),"u1");
        String raw=" \u3000ｈｅｌｌｏ\tｗｏｒｌｄ\n ";JsonNode echoed=page(f,"?q="+query(raw));assertIds(echoed,"u1");assertEquals(raw,echoed.path("q").asText(),"Normalization is for matching only; echo every original UTF-16 unit");
        assertEquals(f.sentences().getFirst().text(),page(f,"?q=world").path("items").get(0).path("text").asText());
        assertIds(page(f,"?q="+query("\u3000缓存\t失效\n")),"u2");
        Locale previous=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertIds(page(f,"?q=indigo"),"u1");}finally{Locale.setDefault(previous);}
        String eight="eight one two three four five six seven";JsonNode result=page(f,"?q="+query(eight));assertIds(result,"u4");assertEquals(eight,result.path("q").asText());
        for(String literal:List.of("<script>","%","_","$&","[a-z]+","\\",".*"))assertIds(page(f,"?q="+query(literal)),"u3");
        assertIds(page(f,"?q=00%3A01"),"u2");assertIds(page(f,"?q=00%3A02"),"u3");assertIds(page(f,"?q=01%3A00%3A00"),"u5");
        JsonNode missing=page(f,"?q="+query("不存在的完整词"));assertEquals(0,missing.path("matched_utterances").asInt());assertTrue(missing.path("items").isEmpty());assertTrue(missing.path("next_offset").isNull());
        assertTrue(page(f,"?q="+query("😀".repeat(100))).path("items").isEmpty(),"The query limit counts UTF-16 units and allows exactly 200");
    }

    @Test void offsetAndLimitPageTheEntireMatchOrderWithoutClampingOrSkipping()throws Exception{
        List<Sentence> sentences=new ArrayList<>();for(int i=0;i<53;i++)sentences.add(new Sentence("p"+i,i*1000L,i*1000L+900,"分页 原句 "+i));var f=fixture(sentences);
        JsonNode first=page(f,"");assertEquals(20,first.path("items").size());assertEquals(20,first.path("next_offset").asInt());
        String fingerprint=first.path("source").path("source_fingerprint").asText();List<String> observed=new ArrayList<>();int offset=0;
        while(true){JsonNode current=page(f,"?limit=20&offset="+offset+"&source_fingerprint="+fingerprint);for(JsonNode item:current.path("items"))observed.add(item.path("utterance_id").asText());
            if(current.path("next_offset").isNull())break;assertEquals(offset+current.path("items").size(),current.path("next_offset").asInt());offset=current.path("next_offset").asInt();}
        assertEquals(sentences.stream().map(Sentence::id).toList(),observed);assertEquals(53,new HashSet<>(observed).size());
        JsonNode maximum=page(f,"?limit=50");assertEquals(50,maximum.path("items").size());assertEquals(50,maximum.path("next_offset").asInt());
        for(int end:List.of(53,1000)){JsonNode empty=page(f,"?offset="+end);assertEquals(end,empty.path("offset").asInt());assertTrue(empty.path("items").isEmpty());assertTrue(empty.path("next_offset").isNull());}
        JsonNode far=page(f,"?offset=9007199254740991");assertEquals(9007199254740991L,far.path("offset").asLong());assertTrue(far.path("items").isEmpty());assertTrue(far.path("next_offset").isNull());
        JsonNode filtered=page(f,"?q="+query("分页 52")+"&offset=0&limit=1");assertIds(filtered,"p52");assertEquals(53,filtered.path("total_utterances").asInt());assertEquals(1,filtered.path("matched_utterances").asInt());
    }

    @Test void textBudgetKeepsWholeLongSentencesAndAdvancesOnlyByActuallyReturnedItems()throws Exception{
        var f=fixture(List.of(new Sentence("long1",0,900,"a".repeat(100000)),new Sentence("long2",1000,1900,"b".repeat(100000)),new Sentence("long3",2000,2900,"c".repeat(100000)),new Sentence("tail",3000,3900,"最后一句")));
        JsonNode first=page(f,"?limit=50");assertIds(first,"long1","long2");assertEquals(200000,first.path("items").valueStream().mapToInt(item->item.path("text").asText().length()).sum());assertEquals(2,first.path("next_offset").asInt());
        JsonNode next=page(f,"?offset=2&limit=50&source_fingerprint="+first.path("source").path("source_fingerprint").asText());assertIds(next,"long3","tail");assertEquals(100000,next.path("items").get(0).path("text").asText().length());assertTrue(next.path("next_offset").isNull());
        JsonNode one=page(f,"?limit=1");assertIds(one,"long1");assertEquals(1,one.path("next_offset").asInt());
    }

    @Test void contextHasExactFirstLastAndSingleSentenceBoundariesAndRejectsForeignSource()throws Exception{
        var f=fixture(example());String fingerprint=page(f,"").path("source").path("source_fingerprint").asText();
        JsonNode first=context(f,"u1",fingerprint);assertTrue(first.path("previous").isNull());assertSentence(f.sentences().get(1),first.path("next"));
        JsonNode last=context(f,"u5",fingerprint);assertTrue(last.path("next").isNull());assertSentence(f.sentences().get(3),last.path("previous"));
        var foreign=fixture(List.of(new Sentence("foreign-only",0,1000,"另一任务原文")));String foreignFingerprint=page(foreign,"").path("source").path("source_fingerprint").asText();
        JsonNode singleton=context(foreign,"foreign-only",foreignFingerprint);assertTrue(singleton.path("previous").isNull());assertTrue(singleton.path("next").isNull());
        error(get(path(f)+"/context?utterance_id=foreign-only&source_fingerprint="+fingerprint),404,"TRANSCRIPT_UTTERANCE_NOT_FOUND");
        error(get(path(f)+"/context?utterance_id=u1&source_fingerprint="+foreignFingerprint),409,"TRANSCRIPT_SOURCE_CHANGED");
        error(get(path(f)+"?source_fingerprint="+foreignFingerprint),409,"TRANSCRIPT_SOURCE_CHANGED");
    }

    @Test void malformedDuplicateUnknownAndEmptyParametersFailBeforeMissingArtifactReads()throws Exception{
        var f=fixture(example());Files.delete(root.resolve(f.asr().artifactKey()));
        for(String suffix:List.of("?unexpected=1","?limit=1&limit=2","?q=x&q=y","?offset=0&offset=0","?source_fingerprint="+"a".repeat(64)+"&source_fingerprint="+"a".repeat(64),
            "?limit=","?limit=0","?limit=51","?limit=01","?limit=-1","?limit=1.0","?offset=","?offset=-1","?offset=01","?offset=1e2","?offset=9007199254740992","?offset=9999999999999999999999999",
            "?source_fingerprint=","?source_fingerprint="+"A".repeat(64),"?source_fingerprint=bad","?q="+query("x".repeat(201)),"?q="+query("😀".repeat(101)),"?q="+query("one two three four five six seven eight nine"))){
            error(get(path(f)+suffix),400,"INVALID_TRANSCRIPT_QUERY");
        }
        for(String suffix:List.of("","?utterance_id=u1","?source_fingerprint="+"a".repeat(64),"?utterance_id=&source_fingerprint="+"a".repeat(64),
            "?utterance_id=u1&source_fingerprint=","?utterance_id=u1&source_fingerprint="+"a".repeat(64)+"&q=x","?utterance_id=u1&utterance_id=u2&source_fingerprint="+"a".repeat(64),
            "?utterance_id=u1&source_fingerprint="+"a".repeat(64)+"&source_fingerprint="+"a".repeat(64),"?utterance_id="+query("../x")+"&source_fingerprint="+"a".repeat(64))){
            error(get(path(f)+"/context"+suffix),400,"INVALID_TRANSCRIPT_QUERY");
        }
    }

    @Test void unavailableJobsModesStagesMissingAndTamperedArtifactsNeverReturnUnverifiedText()throws Exception{
        error(get("/api/jobs/"+UUID.randomUUID()+"/transcript"),404,"JOB_NOT_FOUND");
        var f=fixture(example());
        for(String state:List.of("QUEUED","RUNNING","FAILED","CANCELLED")){jdbc.update("UPDATE analysis_job SET status=? WHERE id=?",state,f.job());error(get(path(f)),409,"TRANSCRIPT_NOT_READY");}
        jdbc.update("UPDATE analysis_job SET status='REVIEW_READY',config_json='{}' WHERE id=?",f.job());error(get(path(f)),409,"TRANSCRIPT_NOT_READY");
        jdbc.update("UPDATE analysis_job SET config_json=? WHERE id=?",config(),f.job());
        byte[] transcript=Files.readAllBytes(root.resolve(f.asr().artifactKey()));Files.write(root.resolve(f.asr().artifactKey()),"tampered".getBytes(StandardCharsets.UTF_8));error(get(path(f)),409,"TRANSCRIPT_SOURCE_INVALID");
        Files.delete(root.resolve(f.asr().artifactKey()));error(get(path(f)),409,"TRANSCRIPT_SOURCE_INVALID");Files.write(root.resolve(f.asr().artifactKey()),transcript);
        byte[] manifest=Files.readAllBytes(root.resolve(f.timeline().manifestKey()));Files.write(root.resolve(f.timeline().manifestKey()),"tampered".getBytes(StandardCharsets.UTF_8));error(get(path(f)),409,"TRANSCRIPT_SOURCE_INVALID");Files.write(root.resolve(f.timeline().manifestKey()),manifest);
        jdbc.update("UPDATE job_stage SET status='FAILED' WHERE id=?",f.asr().id());error(get(path(f)),409,"TRANSCRIPT_SOURCE_INVALID");
        jdbc.update("DELETE FROM job_stage WHERE id=?",f.asr().id());error(get(path(f)),409,"TRANSCRIPT_SOURCE_INVALID");
    }

    @Test void correctlyResealedSourceMismatchDuplicateIdsAndInvalidChronologyAreStillRejected()throws Exception{
        var mismatched=fixture(example());ObjectNode analysis=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(mismatched.timeline().artifactKey())));((ObjectNode)analysis.path("utterances").get(0)).put("text","与ASR不符");replaceAnalysis(mismatched,analysis);
        error(get(path(mismatched)),409,"TRANSCRIPT_SOURCE_INVALID");
        for(List<Sentence> invalid:List.of(List.of(new Sentence("same",0,900,"first"),new Sentence("same",1000,1900,"second")),
            List.of(new Sentence("u1",0,1900,"first"),new Sentence("u2",1000,2900,"overlap")),List.of(new Sentence("u1",0,0,"empty interval")),
            List.of(new Sentence("u1",0,900,"x".repeat(100001))))){var fixture=fixture(invalid);error(get(path(fixture)),409,"TRANSCRIPT_SOURCE_INVALID");}
        var wrongIdentity=fixture(example());ObjectNode manifest=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(wrongIdentity.asr().manifestKey())));manifest.put("job_id",UUID.randomUUID().toString());replaceManifest(wrongIdentity.asr(),manifest);error(get(path(wrongIdentity)),409,"TRANSCRIPT_SOURCE_INVALID");
    }

    @Test void sourceFingerprintChangesWithVerifiedAnalysisBytesAndPinsBothReadKinds()throws Exception{
        var f=fixture(example());JsonNode initial=page(f,"");String expected=initial.path("source").path("source_fingerprint").asText();
        ObjectNode changed=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(f.timeline().artifactKey())));((ObjectNode)changed.path("segments").get(0)).put("title","仍合法的新自动章节标题");replaceAnalysis(f,changed);
        error(get(path(f)+"?source_fingerprint="+expected),409,"TRANSCRIPT_SOURCE_CHANGED");error(get(path(f)+"/context?utterance_id=u1&source_fingerprint="+expected),409,"TRANSCRIPT_SOURCE_CHANGED");
        JsonNode fresh=page(f,"");assertNotEquals(expected,fresh.path("source").path("source_fingerprint").asText());assertEquals(initial.path("source").path("transcript_sha256"),fresh.path("source").path("transcript_sha256"));
    }

    @Test void unrelatedProgressTouchDuringRealArtifactVerificationKeepsTheSameSourceFingerprint()throws Exception{
        var f=fixture(example());long before=jdbc.queryForObject("SELECT state_version FROM analysis_job WHERE id=?",Long.class,f.job());AtomicBoolean touched=new AtomicBoolean();
        ArtifactVerifier realChecksWithProgress=new ArtifactVerifier(root){
            @Override public VerifiedManifest verifyManifest(String key,String hash,ManifestIdentity expected){
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                VerifiedManifest verified=super.verifyManifest(key,hash,expected);
                if(touched.compareAndSet(false,true))jdbc.update("UPDATE analysis_job SET state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",f.job());
                return verified;
            }
        };
        var service=new TranscriptService(jdbc,realChecksWithProgress,app.getBean(PlatformTransactionManager.class));
        var result=service.page(f.job(),new LinkedMultiValueMap<>());
        assertTrue(touched.get());assertEquals(before+1,jdbc.queryForObject("SELECT state_version FROM analysis_job WHERE id=?",Long.class,f.job()).longValue());
        assertEquals(f.sentences().size(),result.items().size());assertSource(f,JSON.valueToTree(result.source()));
    }

    @Test void artifactIoSuspendsAnOuterTransactionAndReturningRechecksStageAndVideoIdentity()throws Exception{
        var manager=app.getBean(PlatformTransactionManager.class);
        for(String mutation:List.of("stage","attempt","manifest","video")){
            var f=fixture(example());String oldKey=jdbc.queryForObject("SELECT source_key FROM video_asset WHERE id=?",String.class,f.video());AtomicBoolean mutated=new AtomicBoolean();
            ArtifactVerifier realChecksWithRace=new ArtifactVerifier(root){
                @Override public VerifiedManifest verifyManifest(String key,String hash,ManifestIdentity expected){
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Real artifact IO must run outside even a caller's transaction");
                    VerifiedManifest result=super.verifyManifest(key,hash,expected);
                    if(mutated.compareAndSet(false,true)){
                        switch(mutation){
                            case "stage" -> jdbc.update("UPDATE job_stage SET run_token=? WHERE id=?",UUID.randomUUID().toString(),f.asr().id());
                            case "attempt" -> jdbc.update("UPDATE job_stage SET attempt=attempt+1 WHERE id=?",f.asr().id());
                            case "manifest" -> jdbc.update("UPDATE job_stage SET manifest_hash=? WHERE id=?","0".repeat(64),f.asr().id());
                            default -> jdbc.update("UPDATE video_asset SET source_key=? WHERE id=?","videos/"+f.video()+"/replaced.mp4",f.video());
                        }
                    }
                    return result;
                }
            };
            var service=new TranscriptService(jdbc,realChecksWithRace,manager);
            try{
                new TransactionTemplate(manager).execute(ignored->{
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    JobException changed=assertThrows(JobException.class,()->service.page(f.job(),new LinkedMultiValueMap<>()));
                    assertEquals("TRANSCRIPT_SOURCE_CHANGED",changed.getCode());assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),"The caller transaction must be restored");
                    return null;
                });assertTrue(mutated.get());
            }finally{
                jdbc.update("UPDATE job_stage SET run_token=?,attempt=1,manifest_hash=? WHERE id=?",f.asr().token(),f.asr().manifestHash(),f.asr().id());
                jdbc.update("UPDATE video_asset SET source_key=? WHERE id=?",oldKey,f.video());
            }
            assertSource(f,page(f,"").path("source"));
        }
    }

    @Test void legalPreparationCopyUsesCurrentJobStageIdentityWithoutNewProviderOrReviewState()throws Exception{
        var original=fixture(example());jdbc.update("UPDATE analysis_job SET status='FAILED' WHERE id=?",original.job());
        var reused=fixture(original.sentences(),original,2);var before=databaseState();JsonNode page=page(reused,"");assertSource(reused,page.path("source"));assertIds(page,"u1","u2","u3","u4","u5");
        assertEquals(original.asr().artifactHash(),page.path("source").path("transcript_sha256").asText());assertEquals(2,page.path("source").path("generation").asInt());assertNotEquals(original.job(),page.path("source").path("job_id").asText());
        context(reused,"u3",page.path("source").path("source_fingerprint").asText());assertEquals(before,databaseState());
        ObjectNode forged=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(reused.asr().manifestKey())));forged.put("job_id",original.job()).put("analysis_generation",original.generation());replaceManifest(reused.asr(),forged);
        error(get(path(reused)),409,"TRANSCRIPT_SOURCE_INVALID");
    }

    private static List<Sentence> example(){return List.of(new Sentence("u1",0,900,"HELLO ＷＯＲＬＤ INDIGO 原句A"),new Sentence("u2",1000,1900,"缓存 失效 中文"),
        new Sentence("u3",2000,2900,"literal <script> % _ $& [a-z]+ \\ .*"),new Sentence("u4",60000,60900,"Eight one two three four five six seven"),new Sentence("u5",3600000,3600900,"跨章节 最后一段 ABC"));}
    private Fixture fixture(List<Sentence> sentences)throws Exception{return fixture(sentences,null,1);}
    private Fixture fixture(List<Sentence> sentences,Fixture reuse,int generation)throws Exception{
        String video=reuse==null?UUID.randomUUID().toString():reuse.video(),job=UUID.randomUUID().toString();byte[] raw={1,2,3,4};String videoHash=reuse==null?sha(raw):reuse.videoHash();long duration=Math.max(1000,sentences.stream().mapToLong(Sentence::end).max().orElse(0)+1000);
        if(reuse==null){String sourceKey="videos/"+video+"/source.mp4";write(sourceKey,raw);jdbc.update("INSERT INTO video_asset (id,content_hash,source_key,original_filename,size_bytes,media_validation_status,duration_ms) VALUES (?,?,?,?,?,'VALID',?)",video,videoHash,sourceKey,"transcript-synthetic.mp4",raw.length,duration);ownVideos.add(video);}
        jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,?,?,?,?,?,'REVIEW_READY',4,?)",job,video,generation,UUID.randomUUID().toString(),"a".repeat(64),"b".repeat(64),config(),reuse==null?1:0);ownJobs.add(job);
        JsonNode utterances=utterances(sentences);ObjectNode analysis=analysis(job,video,generation,duration,utterances);
        Map<String,byte[]> mediaFiles=new LinkedHashMap<>();mediaFiles.put("audio",new byte[]{7,8,9});mediaFiles.put("media_info",JSON.writeValueAsBytes(Map.of("duration_ms",duration)));
        Stage media=publish(job,video,generation,videoHash,"MEDIA",mediaFiles,reuse),asr=publish(job,video,generation,videoHash,"ASR",Map.of("transcript",JSON.writeValueAsBytes(utterances)),reuse),timeline=publish(job,video,generation,videoHash,"TIMELINE",Map.of("analysis_result",JSON.writeValueAsBytes(analysis)),null);
        if(reuse!=null){Map<String,Object> preparation=new TreeMap<>();preparation.put("schema_version",1);preparation.put("source_job_id",reuse.job());preparation.put("video_id",video);preparation.put("source_generation",reuse.generation());preparation.put("source_hash",videoHash);preparation.put("asr_model","synthetic-asr");
            preparation.put("media",reference(reuse.media()));preparation.put("asr",reference(reuse.asr()));jdbc.update("UPDATE analysis_job SET resumed_from_job_id=?,preparation_source_json=? WHERE id=?",reuse.job(),JSON.writeValueAsString(preparation),job);}
        return new Fixture(job,video,generation,videoHash,duration,List.copyOf(sentences),media,asr,timeline);
    }
    private Stage publish(String job,String video,int generation,String sourceHash,String type,Map<String,byte[]> files,Fixture reuse)throws Exception{
        String stage=UUID.randomUUID().toString(),token=UUID.randomUUID().toString(),prefix="jobs/"+job+"/stages/"+stage+"/attempts/1/"+token+"/",folder=prefix+(reuse==null?"":"preparation/");
        ObjectNode manifest=JSON.createObjectNode().put("schema_version",1).put("job_id",job).put("video_id",video).put("analysis_generation",generation).put("stage_id",stage).put("stage_type",type).put("attempt",1).put("run_token",token).put("input_sha256",sourceHash).put("data_kind","model_result").put("review_status","DRAFT");
        var artifacts=manifest.putArray("artifacts");String primaryKey=null,primaryHash=null;
        for(var entry:files.entrySet()){String key=folder+entry.getKey()+".json";write(key,entry.getValue());String hash=sha(entry.getValue());artifacts.addObject().put("kind",entry.getKey()).put("key",key).put("sha256",hash);if(primaryKey==null){primaryKey=key;primaryHash=hash;}}
        if(reuse!=null){Map<String,Object> provenance=new TreeMap<>();provenance.put("schema_version",1);provenance.put("reuse_mode","published_preparation_copy");provenance.put("stage_type",type);provenance.put("source_job_id",reuse.job());provenance.put("source_video_id",reuse.video());provenance.put("source_generation",reuse.generation());provenance.put("source_hash",sourceHash);provenance.put("asr_model","synthetic-asr");provenance.put("source_media_stage_id",reuse.media().id());provenance.put("source_asr_stage_id",reuse.asr().id());provenance.put("source_media_manifest_sha256",reuse.media().manifestHash());provenance.put("source_asr_manifest_sha256",reuse.asr().manifestHash());provenance.put("audio_sha256",reuse.media().artifactHash());provenance.put("source_transcript_sha256",reuse.asr().artifactHash());provenance.put("provider_submissions",0);
            byte[] bytes=JSON.writeValueAsBytes(provenance);String key=folder+"cache-provenance.json";write(key,bytes);artifacts.addObject().put("kind","cache_provenance").put("key",key).put("sha256",sha(bytes));}
        byte[] bytes=JSON.writeValueAsBytes(manifest);String key=prefix+"manifest.json";write(key,bytes);
        jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,shard_key,status,attempt,run_token,input_hash,manifest_key,manifest_hash) VALUES (?,?,'ANALYSIS',?,?,'0','SUCCEEDED',1,?,?,?,?)",stage,job,job,type,token,"c".repeat(64),key,sha(bytes));
        return new Stage(stage,type,token,key,sha(bytes),primaryKey,primaryHash);
    }
    private static Map<String,Object> reference(Stage stage){return Map.of("id",stage.id(),"attempt",1,"manifest_key",stage.manifestKey(),"manifest_hash",stage.manifestHash());}
    private static String config(){return "{\"analysis_mode\":\"topics\",\"asr_model\":\"synthetic-asr\",\"text_model\":\"synthetic-text\"}";}
    private static JsonNode utterances(List<Sentence> sentences){var rows=JSON.createArrayNode();for(var sentence:sentences)rows.addObject().put("utterance_id",sentence.id()).put("start_ms",sentence.start()).put("end_ms",sentence.end()).put("text",sentence.text()).put("source_chunk_id","c0");return rows;}
    private static ObjectNode analysis(String job,String video,int generation,long duration,JsonNode utterances){
        ObjectNode analysis=JSON.createObjectNode().put("schema_version","1.0").put("analysis_mode","topics").put("data_kind","model_result").put("review_status","DRAFT").put("job_id",job).put("video_id",video).put("analysis_generation",generation).put("duration_ms",duration).put("transcript_version","synthetic-asr-v1").put("pipeline_version","topics-text-v1");
        analysis.set("utterances",utterances);analysis.putArray("frames");analysis.putObject("provenance").put("asr_model_id","synthetic-asr").put("text_model_id","synthetic-text").put("prompt_version","synthetic-topics-v1").put("config_hash","b".repeat(64));
        var segments=analysis.putArray("segments");int index=0;for(JsonNode row:utterances){var segment=segments.addObject().put("segment_id","topic-"+(++index)).put("title","合成章节 "+index).put("summary","仅用于协议测试").put("start_ms",row.path("start_ms").asLong()).put("end_ms",row.path("end_ms").asLong()).put("start_utterance_id",row.path("utterance_id").asText()).put("end_utterance_id",row.path("utterance_id").asText());segment.putArray("keywords").add("合成");segment.putArray("audio_evidence_utterance_ids").add(row.path("utterance_id").asText());}
        return analysis;
    }
    private void replaceAnalysis(Fixture fixture,ObjectNode value)throws Exception{
        byte[] bytes=JSON.writeValueAsBytes(value);write(fixture.timeline().artifactKey(),bytes);ObjectNode manifest=(ObjectNode)JSON.readTree(Files.readAllBytes(root.resolve(fixture.timeline().manifestKey())));
        for(JsonNode artifact:manifest.path("artifacts"))if(artifact.path("kind").asText().equals("analysis_result"))((ObjectNode)artifact).put("sha256",sha(bytes));replaceManifest(fixture.timeline(),manifest);
    }
    private void replaceManifest(Stage stage,ObjectNode manifest)throws Exception{byte[] bytes=JSON.writeValueAsBytes(manifest);write(stage.manifestKey(),bytes);jdbc.update("UPDATE job_stage SET manifest_hash=? WHERE id=?",sha(bytes),stage.id());}
    private Map<String,List<Map<String,Object>>> databaseState(){
        Map<String,List<Map<String,Object>>> state=new TreeMap<>();
        for(String table:List.of("analysis_job","video_asset","job_stage","review_document","review_revision","review_confirmation","model_call","clip_request","agent_session","agent_turn","export_job","caption_document")){
            state.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        }
        return state;
    }
    private static String path(Fixture fixture){return "/api/jobs/"+fixture.job()+"/transcript";}
    private static String query(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static void write(String key,byte[] bytes)throws Exception{Path file=root.resolve(key);Files.createDirectories(file.getParent());Files.write(file,bytes);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private HttpRequest request(String path){return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).GET().build();}
    private HttpResponse<byte[]> get(String path)throws Exception{return http.send(request(path),HttpResponse.BodyHandlers.ofByteArray());}
    private JsonNode page(Fixture fixture,String suffix)throws Exception{HttpResponse<byte[]> response=get(path(fixture)+suffix);assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return JSON.readTree(response.body());}
    private JsonNode context(Fixture fixture,String utterance,String fingerprint)throws Exception{HttpResponse<byte[]> response=get(path(fixture)+"/context?utterance_id="+query(utterance)+"&source_fingerprint="+fingerprint);assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return JSON.readTree(response.body());}
    private static void error(HttpResponse<byte[]> response,int status,String code){assertEquals(status,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));assertEquals(code,JSON.readTree(response.body()).path("error_code").asText());}
    private static void assertIds(JsonNode page,String... expected){assertEquals(List.of(expected),page.path("items").valueStream().map(item->item.path("utterance_id").asText()).toList());}
    private static void assertSentence(Sentence expected,JsonNode actual){assertEquals(ITEM_FIELDS,actual.propertyNames());assertEquals(expected.id(),actual.path("utterance_id").asText());assertEquals(expected.start(),actual.path("start_ms").asLong());assertEquals(expected.end(),actual.path("end_ms").asLong());assertEquals(expected.text(),actual.path("text").asText());}
    private static void assertSource(Fixture expected,JsonNode actual)throws Exception{
        assertEquals(SOURCE_FIELDS,actual.propertyNames());assertEquals(expected.job(),actual.path("job_id").asText());assertEquals(expected.video(),actual.path("video_id").asText());assertEquals(expected.generation(),actual.path("generation").asInt());assertEquals(expected.videoHash(),actual.path("source_video_sha256").asText());assertEquals(expected.asr().artifactHash(),actual.path("transcript_sha256").asText());assertEquals(expected.timeline().artifactHash(),actual.path("analysis_sha256").asText());assertEquals(expected.duration(),actual.path("duration_ms").asLong());
        String fingerprint="transcript-source-v1\n"+expected.job()+"\n"+expected.video()+"\n"+expected.generation()+"\n"+expected.videoHash()+"\n"+expected.asr().artifactHash()+"\n"+expected.timeline().artifactHash()+"\n"+expected.duration()+"\n";
        assertEquals(sha(fingerprint.getBytes(StandardCharsets.UTF_8)),actual.path("source_fingerprint").asText());
    }
}
