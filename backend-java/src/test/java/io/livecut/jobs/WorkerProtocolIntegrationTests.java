package io.livecut.jobs;

import static io.livecut.jobs.JobModels.*;
import static io.livecut.jobs.WorkerModels.*;
import static org.junit.jupiter.api.Assertions.*;
import io.livecut.LivecutBackendApplication;
import io.livecut.video.VideoApiException;
import io.livecut.video.VideoService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Disposable MySQL and synthetic stages; protocol mismatches never execute or reserve a model call. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerProtocolIntegrationTests {
    @TempDir static Path root;
    private static final String KEY="synthetic-protocol-worker-key";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> ownJobs=new ArrayList<>(),ownWorkers=new ArrayList<>();
    private ConfigurableApplicationContext app;private JdbcTemplate jdbc;private JobService jobs;private WorkerCapacityService workers;private String base,video;
    private record Fixture(String job,String stage) {}
    private record Worker(String id,String session) {}

    @BeforeAll void start(){
        URI db=URI.create(System.getenv("LIVECUT_DB_URL").substring(5));
        assertEquals("127.0.0.1",db.getHost());assertTrue(db.getPort()==13307&&db.getPath().equals("/livecut_test_topics")||db.getPort()==3306&&db.getPath().equals("/livecut_test"));
        app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+root,
            "--livecut.worker.api-key="+KEY,"--livecut.workers.require-registration=false","--livecut.workers.ffmpeg-slots=1","--livecut.workers.text-slots=1",
            "--livecut.jobs.scan-enabled=false","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false","--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);jobs=app.getBean(JobService.class);workers=app.getBean(WorkerCapacityService.class);
        base="http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port");
        byte[] bytes=ByteBuffer.allocate(48).putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII)).put("isom".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(24).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        video=app.getBean(VideoService.class).upload(new MockMultipartFile("file","synthetic-protocol.mp4","video/mp4",bytes)).id();
    }
    @AfterEach void cleanup(){for(String job:ownJobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",job);for(String worker:ownWorkers)jdbc.update("DELETE FROM worker_instance WHERE worker_id=?",worker);ownJobs.clear();ownWorkers.clear();}
    @AfterAll void stop(){if(app!=null){cleanup();if(video!=null)jdbc.update("DELETE FROM video_asset WHERE id=?",video);app.close();}}
    private Fixture fixture(String type,int protocol){
        var job=jobs.create(new CreateJobRequest(video,Map.of()),"protocol-job-"+UUID.randomUUID());ownJobs.add(job.id());
        String stage=job.stages().stream().filter(row->row.stageType().equals(type)).findFirst().orElseThrow().id();
        jdbc.update("UPDATE job_stage SET status='BLOCKED' WHERE job_id=?",job.id());jdbc.update("UPDATE job_stage SET status='READY',required_worker_protocol=? WHERE id=?",protocol,stage);return new Fixture(job.id(),stage);
    }
    private Worker identity(){var worker=new Worker("protocol-"+UUID.randomUUID(),UUID.randomUUID().toString());ownWorkers.add(worker.id());return worker;}
    private Worker worker(List<String> types,List<Integer> protocols,String target){var worker=identity();workers.register(new Registration(worker.id(),worker.session(),types,1,target,protocols));return worker;}
    private ClaimResult claim(Fixture fixture,Worker worker,String key){return jobs.claim(new ClaimRequest(fixture.stage(),worker.id(),key,fixture.job(),worker.session()));}
    private HttpResponse<String> send(String method,String path,String body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base+path)).header("X-Worker-Key",KEY);if(body!=null)request.header("Content-Type","application/json");
        return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void code(String expected,Runnable action){var error=assertThrows(RuntimeException.class,action::run);if(error instanceof JobException job)assertEquals(expected,job.getCode());else if(error instanceof VideoApiException api)assertEquals(expected,api.getCode());else fail("Expected a controlled protocol error",error);}
    private void untouched(Fixture fixture,String claimKey,Worker worker){
        var stage=jdbc.queryForMap("SELECT status,attempt,run_token FROM job_stage WHERE id=?",fixture.stage());assertEquals("READY",stage.get("status"));assertEquals(0,((Number)stage.get("attempt")).intValue());assertNull(stage.get("run_token"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM stage_claim_request WHERE worker_id=? AND claim_request_id=?",Integer.class,worker.id(),claimKey));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?",Integer.class,fixture.job()));
    }

    @Test void registrationPreservesMissingVersionOneAndRejectsMalformedExplicitLists()throws Exception{
        var old=identity();var body=JSON.createObjectNode().put("worker_id",old.id()).put("session_id",old.session()).put("slots",1);body.putArray("stage_types").add("MEDIA");
        assertEquals(200,send("POST","/internal/workers/register",JSON.writeValueAsString(body)).statusCode());assertEquals(JSON.readTree("[1]"),JSON.readTree(jdbc.queryForObject("SELECT supported_protocol_versions FROM worker_instance WHERE worker_id=?",String.class,old.id())));
        for(String invalid:List.of("null","true","1","\"1\"","[]","[null]","[true]","[1.0]","[\"1\"]","[1,1]","[3]","[0]","[-1]","[1,2,3]")){
            var worker=identity();body.put("worker_id",worker.id()).put("session_id",worker.session());body.set("supported_protocol_versions",JSON.readTree(invalid));
            assertEquals(400,send("POST","/internal/workers/register",JSON.writeValueAsString(body)).statusCode(),invalid);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM worker_instance WHERE worker_id=?",Integer.class,worker.id()),invalid);
        }
    }

    @Test void explicitVersionsAreASetButAreImmutableWithinTheRegisteredSession(){
        Worker worker=worker(List.of("MEDIA"),List.of(2,1),null);assertEquals("REGISTERED",workers.register(new Registration(worker.id(),worker.session(),List.of("MEDIA"),1,null,List.of(1,2))).outcome());
        code("WORKER_REGISTRATION_CONFLICT",()->workers.register(new Registration(worker.id(),worker.session(),List.of("MEDIA"),1,null,List.of(1))));
        assertEquals(JSON.readTree("[1,2]"),JSON.readTree(jdbc.queryForObject("SELECT supported_protocol_versions FROM worker_instance WHERE worker_id=?",String.class,worker.id())));
    }

    @Test void readyFiltersVersionsTypesAndTargetsBeforeTheLimitAndOldQueriesOnlySeeVersionOne()throws Exception{
        Fixture newest=fixture("MEDIA",2),legacy=fixture("MEDIA",1);jdbc.update("UPDATE job_stage SET next_run_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE id=?",newest.stage());
        assertEquals(legacy.stage(),jobs.ready("MEDIA",1,null).getFirst().id());assertNull(jobs.ready("MEDIA",1,null).getFirst().requiredWorkerProtocol());
        Worker onlyTwo=worker(List.of("MEDIA"),List.of(2),null);var ready=jobs.ready(null,1,null,onlyTwo.id(),onlyTwo.session());assertEquals(newest.stage(),ready.getFirst().id());assertEquals(2,ready.getFirst().requiredWorkerProtocol());
        Worker scoped=worker(List.of("MEDIA"),List.of(1,2),legacy.job());assertEquals(legacy.stage(),jobs.ready(null,1,null,scoped.id(),scoped.session()).getFirst().id());code("WORKER_TARGET_MISMATCH",()->jobs.ready(null,1,newest.job(),scoped.id(),scoped.session()));
        Worker otherType=worker(List.of("ASR"),List.of(1,2),null);assertTrue(jobs.ready("MEDIA",1,null,otherType.id(),otherType.session()).isEmpty());
        var response=send("GET","/internal/stages/ready?stage_type=MEDIA&limit=1&worker_id="+onlyTwo.id()+"&session_id="+onlyTwo.session(),null);assertEquals(200,response.statusCode());assertEquals(2,JSON.readTree(response.body()).path("items").get(0).path("required_worker_protocol").intValue());
        assertEquals(400,send("GET","/internal/stages/ready?worker_id="+onlyTwo.id(),null).statusCode());assertEquals(400,send("GET","/internal/stages/ready?session_id="+onlyTwo.session(),null).statusCode());
    }

    @Test void oldWorkersAndAnonymousCompatibilityCannotClaimVersionTwoOrLeaveReceipts(){
        Fixture latest=fixture("MEDIA",2);Worker old=worker(List.of("MEDIA"),List.of(1),null);String key=UUID.randomUUID().toString();code("WORKER_PROTOCOL_MISMATCH",()->claim(latest,old,key));untouched(latest,key,old);
        Worker anonymous=identity();String anonymousKey=UUID.randomUUID().toString();code("WORKER_NOT_REGISTERED",()->jobs.claim(new ClaimRequest(latest.stage(),anonymous.id(),anonymousKey,latest.job())));untouched(latest,anonymousKey,anonymous);
        Fixture legacy=fixture("MEDIA",1);assertEquals("CLAIMED",jobs.claim(new ClaimRequest(legacy.stage(),anonymous.id(),UUID.randomUUID().toString(),legacy.job())).outcome());
    }

    @Test void advertisingOnlyVersionTwoDoesNotImplyVersionOneAndReplayRechecksTheFrozenProtocol(){
        Fixture legacy=fixture("MEDIA",1),latest=fixture("MEDIA",2);Worker onlyTwo=worker(List.of("MEDIA"),List.of(2),null);String oldKey=UUID.randomUUID().toString();code("WORKER_PROTOCOL_MISMATCH",()->claim(legacy,onlyTwo,oldKey));untouched(legacy,oldKey,onlyTwo);
        assertEquals(0,workers.snapshot(null).onlineWorkers());assertEquals(1,workers.snapshot(null,2).onlineWorkers());
        String key=UUID.randomUUID().toString();ClaimResult first=claim(latest,onlyTwo,key);assertEquals("CLAIMED",first.outcome());assertEquals(2,first.requiredWorkerProtocol());assertEquals("ALREADY_CLAIMED",claim(latest,onlyTwo,key).outcome());
        jdbc.update("UPDATE worker_instance SET supported_protocol_versions=JSON_ARRAY(1) WHERE worker_id=?",onlyTwo.id());
        code("WORKER_PROTOCOL_MISMATCH",()->claim(latest,onlyTwo,key));assertEquals(first.runToken(),jdbc.queryForObject("SELECT run_token FROM job_stage WHERE id=?",String.class,latest.stage()));assertEquals(1,jdbc.queryForObject("SELECT attempt FROM job_stage WHERE id=?",Integer.class,latest.stage()));
    }

    @Test void filteredExecutorViewsKeepTheirShapeAndStillUseGlobalResourceOccupancy()throws Exception{
        Fixture occupied=fixture("MEDIA",1),target=fixture("MEDIA",2);Worker old=worker(List.of("MEDIA"),List.of(1),null),latest=worker(List.of("MEDIA"),List.of(1,2),target.job());assertEquals("CLAIMED",claim(occupied,old,UUID.randomUUID().toString()).outcome());
        var filtered=workers.snapshot(target.job(),2);assertEquals(1,filtered.onlineWorkers());assertEquals(latest.id(),filtered.workers().getFirst().workerId());assertEquals(1,filtered.resources().stream().filter(resource->resource.name().equals("FFMPEG")).findFirst().orElseThrow().inUse());assertEquals("RESOURCE_BUSY",filtered.stages().stream().filter(stage->stage.stageType().equals("MEDIA")).findFirst().orElseThrow().reason());
        JsonNode before=JSON.readTree(send("GET","/api/executors",null).body()),after=JSON.readTree(send("GET","/api/executors?required_worker_protocol=2&job_id="+target.job(),null).body());assertEquals(before.propertyNames(),after.propertyNames());assertEquals(before.path("workers").get(0).propertyNames(),after.path("workers").get(0).propertyNames());assertFalse(after.toString().contains("session_id"));
        assertEquals(400,send("GET","/api/executors?required_worker_protocol=3",null).statusCode());
    }

    @Test void capabilityAndLegacyWireShapesAreExact()throws Exception{
        assertEquals(JSON.readTree("{\"speech_lookup_supported\":true,\"worker_protocol\":2}"),JSON.readTree(send("GET","/api/agent-capabilities/speech-lookup",null).body()));
        assertEquals(JSON.readTree("{\"structured_evidence_supported\":true}"),JSON.readTree(send("GET","/api/agent-capabilities/evidence",null).body()));
        Fixture legacy=fixture("MEDIA",1);Worker worker=worker(List.of("MEDIA"),List.of(1,2),legacy.job());
        assertFalse(JSON.valueToTree(jobs.ready("MEDIA",1,legacy.job()).getFirst()).has("required_worker_protocol"));assertFalse(JSON.valueToTree(claim(legacy,worker,UUID.randomUUID().toString())).has("required_worker_protocol"));
    }
}
