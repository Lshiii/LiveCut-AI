package io.livecut.sourceimports;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import io.livecut.jobs.InternalWorkerAuthFilter;
import io.livecut.jobs.JobException;
import io.livecut.video.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real MySQL and production MVC/auth; synthetic MP4 headers, no socket or provider calls. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceImportIntegrationTests {
    private static final String KEY="source-import-test-only";
    private static final String URL="https://www.youtube.com/watch?v=abcdefghijk";
    @TempDir Path temporary;
    private JdbcTemplate jdbc;private DataSourceTransactionManager manager;private SourceImportService service;
    private SourceImportStorage storage;private VideoRepository videos;private MockMvc mvc;private ObjectMapper json;
    private AnnotationConfigWebApplicationContext context;
    private final List<String> owned=new ArrayList<>(),workers=new ArrayList<>();
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Import({WebMvcAutoConfiguration.class,JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,
        ApiErrorHandler.class,SourceImportController.class,InternalSourceImportController.class,VideoController.class})
    static class WebOnly {
        @Bean InternalWorkerAuthFilter auth(ObjectMapper json){return new InternalWorkerAuthFilter(KEY,json);}
    }
    @BeforeAll void database(){
        String url=System.getenv("LIVECUT_DB_URL");
        assertTrue(url.matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"),"Only the dedicated test database is permitted");
        var ds=new DriverManagerDataSource(url,System.getenv("LIVECUT_DB_USER"),System.getenv("LIVECUT_DB_PASSWORD"));
        // Match production Hikari's two MySQL connection properties. A serverTimezone
        // URL alone does not change a SYSTEM(+08) database session to UTC.
        var options=new Properties();options.setProperty("connectionTimeZone","UTC");options.setProperty("forceConnectionTimeZoneToSession","true");ds.setConnectionProperties(options);
        Flyway.configure().dataSource(ds).load().migrate();jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
    }
    @BeforeEach void setup() {
        var properties=new VideoConfiguration.StorageProperties(temporary,1024);var videoStorage=new VideoStorage(temporary,1024);
        storage=spy(new SourceImportStorage(properties,videoStorage));videos=new VideoRepository(jdbc);
        service=new SourceImportService(jdbc,manager,storage,videos,properties,90,3600,3,false);
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(factory->{factory.registerSingleton("sourceImportService",service);factory.registerSingleton("videoStorage",videoStorage);factory.registerSingleton("videoService",new VideoService(videoStorage,videos,manager));});
        context.register(WebOnly.class);context.refresh();json=context.getBean(ObjectMapper.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(InternalWorkerAuthFilter.class)).build();
    }
    @AfterEach void cleanup(){
        if(context!=null)context.close();
        for(String worker:workers)jdbc.update("DELETE FROM source_import_claim WHERE worker_id=?",worker);
        for(String id:owned){var refs=jdbc.queryForList("SELECT reserved_video_id FROM source_import WHERE id=?",String.class,id);jdbc.update("DELETE FROM source_import WHERE id=?",id);for(String video:refs)jdbc.update("DELETE FROM video_asset WHERE id=?",video);}
        workers.clear();owned.clear();
    }
    @Test void createBindsCanonicalUrlAndExactIdempotencyReceipt() throws Exception {
        String key="create-"+UUID.randomUUID();var first=request(post("/api/source-imports").header("Idempotency-Key",key),Map.of("url","https://youtu.be/abcdefghijk?si=tracking"),201);
        remember(first);assertEquals(URL,first.path("url").asText());assertEquals(0,first.path("attempt").asInt());assertTrue(first.path("video_id").isNull());
        assertEquals(first,request(post("/api/source-imports").header("Idempotency-Key",key),Map.of("url",URL),201));
        assertEquals(first,request(get("/api/source-imports/by-key/"+key),null,200));
        error(post("/api/source-imports").header("Idempotency-Key",key),Map.of("url","https://youtu.be/12345678901"),409,"IDEMPOTENCY_CONFLICT");
        error(get("/api/source-imports/by-key/missing-"+UUID.randomUUID()),null,404,"SOURCE_IMPORT_NOT_FOUND");
        assertFalse(first.has("run_token"));assertFalse(first.has("reserved_video_id"));assertFalse(first.has("source_key"));
    }
    @Test void invalidBodyAndMissingKeyNeverCreateTasks() throws Exception {
        error(post("/api/source-imports"),Map.of("url",URL),400,"INVALID_ARGUMENT");
        error(post("/api/source-imports").header("Idempotency-Key","x"),Map.of("url",URL,"path","C:/secret"),400,"INVALID_ARGUMENT");
        error(post("/api/source-imports").header("Idempotency-Key","x"),Map.of("url","https://127.0.0.1/private"),400,"INVALID_SOURCE_URL");
    }
    @Test void internalAuthenticationCoversRealMvcEndpoints() throws Exception {
        error(post("/internal/source-imports/claim"),Map.of("worker_id","test","claim_request_id","x"),401,"WORKER_UNAUTHORIZED");
        request(post("/internal/source-imports/claim").header("X-Worker-Key","wrong"),Map.of("worker_id","test","claim_request_id","x"),401);
        String worker=worker();var claim=request(post("/internal/source-imports/claim").header("X-Worker-Key",KEY),Map.of("worker_id",worker,"claim_request_id","empty"),200);
        assertTrue(claim.path("claim").isNull());
    }
    @Test void noWorkClaimReceiptCannotAcquireFutureTask() {
        String worker=worker();var input=node(Map.of("worker_id",worker,"claim_request_id","original-empty"));
        assertNull(service.claim(input).claim());var task=create();assertNull(service.claim(input).claim());
        var lease=claim(worker,"new-request");assertEquals(task.id(),lease.id());
        var replay=claim(worker,"new-request");assertEquals(lease,replay);assertEquals(1,replay.attempt());
    }
    @Test void concurrentWorkersAcquireOnlyOneExecution() throws Exception {
        create();String a=worker(),b=worker();
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->claim(a,"one"));var second=pool.submit(()->claim(b,"two"));
            int claimed=(first.get(10,TimeUnit.SECONDS)==null?0:1)+(second.get(10,TimeUnit.SECONDS)==null?0:1);assertEquals(1,claimed);
        }
    }
    @Test void aClaimReplaysTheFrozenSizeLimitAfterDeploymentConfigurationChanges() {
        var task=create();
        var smaller=new SourceImportService(jdbc,manager,storage,videos,new VideoConfiguration.StorageProperties(temporary,512),90,3600,3,false);
        var lease=smaller.claim(node(Map.of("worker_id",worker(),"claim_request_id","config-changed"))).claim();
        assertEquals(task.id(),lease.id());assertEquals(1024,lease.maxBytes());
    }
    @Test void productionUtcConnectionsKeepLeasesAndPublicDatesOnTheActualWallClock() {
        assertEquals(0,jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW())",Integer.class));
        var task=create();var lease=claim(worker(),"wall-clock");var now=java.time.Instant.now();
        long leaseRemaining=java.time.Duration.between(now,lease.leaseUntil()).toSeconds();
        long deadlineRemaining=java.time.Duration.between(now,lease.hardDeadlineAt()).toSeconds();
        assertTrue(leaseRemaining>=80&&leaseRemaining<=90,"Lease must be about 90 real seconds, not shifted by the server timezone");
        assertTrue(deadlineRemaining>=3590&&deadlineRemaining<=3600,"Hard deadline must be about one real hour");
        assertTrue(Math.abs(java.time.Duration.between(now,task.createdAt()).toSeconds())<5);
        assertTrue(Math.abs(java.time.Duration.between(now,service.get(task.id()).updatedAt()).toSeconds())<5);
    }
    @Test void progressCannotMoveBackwardsOrExceedItsFrozenLimit() {
        var task=create();var lease=claim(worker(),"progress");var update=new HashMap<>(credentials(lease));
        update.put("phase","NORMALIZING");update.put("progress_percent",80);update.put("downloaded_bytes",48);service.heartbeat(task.id(),node(update));
        update.put("phase","DOWNLOADING");assertCode("SOURCE_IMPORT_PROGRESS_CONFLICT",()->service.heartbeat(task.id(),node(update)));
        update.put("phase","VERIFYING");update.put("downloaded_bytes",47);assertCode("SOURCE_IMPORT_PROGRESS_CONFLICT",()->service.heartbeat(task.id(),node(update)));
        update.put("downloaded_bytes",1025);assertCode("INVALID_ARGUMENT",()->service.heartbeat(task.id(),node(update)));
    }
    @Test void heartbeatAndCancellationFenceTheOldExecution() throws Exception {
        var task=create();var lease=claim(worker(),"claim");
        var heartbeat=new LinkedHashMap<String,Object>(credentials(lease));heartbeat.put("phase","VERIFYING");heartbeat.put("progress_percent",99);heartbeat.put("downloaded_bytes",48);
        var response=request(post("/internal/source-imports/"+task.id()+"/heartbeat").header("X-Worker-Key",KEY),heartbeat,200);
        assertEquals(Set.of("lease_until","hard_deadline_at"),response.propertyNames());
        request(delete("/api/source-imports/"+task.id()),null,200);
        error(post("/internal/source-imports/"+task.id()+"/heartbeat").header("X-Worker-Key",KEY),heartbeat,409,"STALE_IMPORT_ATTEMPT");
        assertEquals("CANCELLED",service.get(task.id()).status());assertNull(service.get(task.id()).videoId());
    }
    @Test void expiredLeaseFailsAndOnlyExplicitRetryStartsANewAttempt() {
        var task=create();var first=claim(worker(),"first");
        jdbc.update("UPDATE source_import SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",task.id());
        var failed=service.get(task.id());assertEquals("FAILED",failed.status());assertEquals("SOURCE_IMPORT_LEASE_EXPIRED",failed.errorCode());
        assertNull(claim(worker(),"no-auto-retry"));service.retry(task.id(),node(Map.of("expected_attempt",1)));
        var second=claim(worker(),"second");assertEquals(2,second.attempt());assertNotEquals(first.runToken(),second.runToken());
        assertCode("STALE_IMPORT_ATTEMPT",()->service.fail(task.id(),fail(first,"OLD_FAILURE")));
    }
    @Test void hardDeadlineCannotBeExtendedByHeartbeat() {
        var task=create();var lease=claim(worker(),"hard");jdbc.update("UPDATE source_import SET hard_deadline_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",task.id());
        assertEquals("SOURCE_IMPORT_DEADLINE_EXCEEDED",service.get(task.id()).errorCode());
        assertCode("STALE_IMPORT_ATTEMPT",()->service.heartbeat(task.id(),heartbeat(lease)));
    }
    @Test void retryIsBoundToExpectedAttemptAndStopsAtThree() {
        var task=create();String worker=worker();
        for(int attempt=1;attempt<=3;attempt++){
            var lease=claim(worker,"try-"+attempt);assertEquals(attempt,lease.attempt());service.fail(task.id(),fail(lease,"DOWNLOAD_FAILED"));
            if(attempt<3){var queued=service.retry(task.id(),node(Map.of("expected_attempt",attempt)));assertEquals("QUEUED",queued.status());assertEquals(attempt,queued.attempt());assertEquals(queued,service.retry(task.id(),node(Map.of("expected_attempt",attempt))));}
        }
        assertEquals("FAILED",service.retry(task.id(),node(Map.of("expected_attempt",1))).status());
        assertCode("SOURCE_IMPORT_NOT_RETRYABLE",()->service.retry(task.id(),node(Map.of("expected_attempt",3))));
    }
    @Test void completionPublishesExactBytesAndReplaysSameReceipt() throws Exception {
        var task=create();var lease=claim(worker(),"complete");byte[] bytes=mp4();source(lease,bytes);
        var input=completion(lease,bytes);var first=request(post("/internal/source-imports/"+task.id()+"/complete").header("X-Worker-Key",KEY),input,200);
        assertEquals("COMPLETED",first.path("status").asText());String video=first.path("video_id").asText();UUID.fromString(video);
        assertArrayEquals(bytes,Files.readAllBytes(temporary.resolve("videos/"+video+".mp4")));
        assertEquals(first,request(post("/internal/source-imports/"+task.id()+"/complete").header("X-Worker-Key",KEY),input,200));
        assertEquals("HEADER_CHECKED",videos.find(video).orElseThrow().mediaValidationStatus());
        assertEquals(video,request(get("/api/videos/"+video),null,200).path("id").asText());
        var different=new HashMap<>(input);different.put("sha256","0".repeat(64));
        assertCode("SOURCE_IMPORT_RESULT_CONFLICT",()->service.complete(task.id(),node(different)));
    }
    @Test void badHashOrHeaderNeverRegistersAVideoAndCleansItsOwnTemporary() throws Exception {
        var task=create();var lease=claim(worker(),"bad");byte[] bytes=mp4();source(lease,bytes);
        var input=new HashMap<>(completion(lease,bytes));input.put("sha256","0".repeat(64));
        assertCode("SOURCE_IMPORT_CONTENT_INVALID",()->service.complete(task.id(),node(input)));
        source(lease,new byte[48]);assertCode("SOURCE_IMPORT_CONTENT_INVALID",()->service.complete(task.id(),node(completion(lease,new byte[48]))));
        assertNull(service.get(task.id()).videoId());
        try(var files=Files.list(temporary.resolve("videos"))){assertEquals(0,files.count());}
    }
    @Test void clientCannotSupplyAnArbitraryPath() {
        var task=create();var lease=claim(worker(),"path");var input=new HashMap<>(completion(lease,mp4()));input.put("path","C:/private.mp4");
        assertCode("INVALID_ARGUMENT",()->service.complete(task.id(),node(input)));
    }
    @Test void cancellationWinsDuringSlowPreparationWithoutHoldingTheBusinessLock() throws Exception {
        var task=create();var lease=claim(worker(),"cancel-copy");byte[] bytes=mp4();source(lease,bytes);
        var prepared=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(invocation->{Object result=invocation.callRealMethod();prepared.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return result;}).when(storage).prepare(anyString(),anyInt(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        try(var pool=Executors.newFixedThreadPool(2)){
            var pending=pool.submit(()->service.complete(task.id(),node(completion(lease,bytes))));assertTrue(prepared.await(10,TimeUnit.SECONDS));
            assertEquals("CANCELLED",pool.submit(()->service.cancel(task.id())).get(2,TimeUnit.SECONDS).status());release.countDown();
            var failure=assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));assertEquals("STALE_IMPORT_ATTEMPT",((JobException)failure.getCause()).getCode());
        }finally{release.countDown();}
        try(var files=Files.list(temporary.resolve("videos"))){assertEquals(0,files.count());}
    }
    @Test void concurrentIdenticalCompletionsPublishOneVideoAndBothSucceed() throws Exception {
        var task=create();var lease=claim(worker(),"same-result");byte[] bytes=mp4();source(lease,bytes);var barrier=new CyclicBarrier(2);
        doAnswer(invocation->{Object result=invocation.callRealMethod();if(!((SourceImportStorage.Prepared)result).existing())barrier.await(10,TimeUnit.SECONDS);return result;}).when(storage).prepare(anyString(),anyInt(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<SourceImportModels.Snapshot> complete=()->service.complete(task.id(),node(completion(lease,bytes)));
            var a=pool.submit(complete);var b=pool.submit(complete);assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        try(var files=Files.list(temporary.resolve("videos"))){assertEquals(1,files.count());}
    }
    @Test void symlinkSourceCannotImportBytesOutsideTheStorageRoot() throws Exception {
        var task=create();var lease=claim(worker(),"symlink");Path target=source(lease,mp4());Files.delete(target);Path outside=Files.write(temporary.resolve("outside.mp4"),mp4());
        try{Files.createSymbolicLink(target,outside);}catch(Exception unsupported){Assumptions.assumeTrue(false,"Symbolic links unavailable on this Windows account");return;}
        assertCode("SOURCE_IMPORT_UNSAFE_PATH",()->service.complete(task.id(),node(completion(lease,mp4()))));
        assertArrayEquals(mp4(),Files.readAllBytes(outside));
    }
    private SourceImportModels.Snapshot create(){var result=service.create(node(Map.of("url",URL)),"test-"+UUID.randomUUID());owned.add(result.id());return result;}
    private String worker(){String id="test-"+UUID.randomUUID();workers.add(id);return id;}
    private SourceImportModels.Claim claim(String worker,String key){return service.claim(node(Map.of("worker_id",worker,"claim_request_id",key))).claim();}
    private Map<String,Object> credentials(SourceImportModels.Claim lease){return Map.of("attempt",lease.attempt(),"run_token",lease.runToken());}
    private JsonNode fail(SourceImportModels.Claim lease,String code){var input=new HashMap<>(credentials(lease));input.put("error_code",code);return node(input);}
    private JsonNode heartbeat(SourceImportModels.Claim lease){var input=new HashMap<>(credentials(lease));input.put("phase","DOWNLOADING");input.put("progress_percent",null);input.put("downloaded_bytes",0);return node(input);}
    private Map<String,Object> completion(SourceImportModels.Claim lease,byte[] bytes){var input=new HashMap<>(credentials(lease));input.put("sha256",hash(bytes));input.put("size_bytes",bytes.length);return input;}
    private Path source(SourceImportModels.Claim lease,byte[] bytes)throws Exception{Path path=temporary.resolve("imports/"+lease.id()+"/attempts/"+lease.attempt()+"/"+lease.runToken()+"/source.mp4");Files.createDirectories(path.getParent());return Files.write(path,bytes);}
    private JsonNode node(Object value){return json.valueToTree(value);}
    private void remember(JsonNode node){owned.add(node.path("id").asText());}
    private JsonNode request(MockHttpServletRequestBuilder request,Object body,int expected)throws Exception{
        if(body!=null)request.contentType("application/json").content(json.writeValueAsBytes(body));
        MvcResult result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus(),result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
    private void error(MockHttpServletRequestBuilder request,Object body,int status,String code)throws Exception{assertEquals(code,request(request,body,status).path("error_code").asText());}
    private static void assertCode(String code,org.junit.jupiter.api.function.Executable action){assertEquals(code,assertThrows(JobException.class,action).getCode());}
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static byte[] mp4(){return ByteBuffer.allocate(48).putInt(32).put("ftyp".getBytes(StandardCharsets.US_ASCII)).put("isom".getBytes(StandardCharsets.US_ASCII)).putInt(512).put("isomiso2avc1mp41".getBytes(StandardCharsets.US_ASCII)).putInt(16).put("free".getBytes(StandardCharsets.US_ASCII)).putLong(123456789).array();}
}
