package io.livecut.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static io.livecut.jobs.JobModels.*;
import static io.livecut.jobs.WorkerModels.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import io.livecut.LivecutBackendApplication;
import io.livecut.video.VideoApiException;
import io.livecut.video.VideoService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

/** Real MySQL transactions and HTTP; synthetic stages never call a provider. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL", matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerCapacityIntegrationTests {
    @TempDir static Path root;
    private static final String KEY = "worker-capacity-test-only-key";
    private static final ObjectMapper JSON = new ObjectMapper();
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private JobService jobs;
    private WorkerCapacityService workers;
    private String video;
    private final List<String> ownJobs = new ArrayList<>(), ownWorkers = new ArrayList<>();
    private record Fixture(String job, String stage) {}
    private record Agent(String id, String session) {}

    @BeforeAll void start() {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"), "Only the dedicated test database is permitted");
        startContext();
        byte[] source = ByteBuffer.allocate(48).putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII))
            .put("isom".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII))
            .putInt(24).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        video = context.getBean(VideoService.class).upload(new MockMultipartFile("file", "synthetic-capacity.mp4", "video/mp4", source)).id();
    }
    private ConfigurableApplicationContext newContext() {
        return new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0", "--server.address=127.0.0.1",
            "--livecut.storage.root=" + root, "--livecut.worker.api-key=" + KEY,
            "--livecut.jobs.scan-enabled=false", "--livecut.uploads.scan-enabled=false", "--livecut.events.scan-enabled=false",
            "--livecut.workers.require-registration=true", "--livecut.workers.heartbeat-timeout-seconds=35",
            "--livecut.workers.ffmpeg-slots=1", "--livecut.workers.asr-slots=1", "--livecut.workers.text-slots=1",
            "--livecut.jobs.lease-seconds=90", "--livecut.jobs.retry-delay-seconds=0",
            "--spring.datasource.url=" + System.getenv("LIVECUT_DB_URL"),
            "--spring.datasource.username=" + System.getenv("LIVECUT_DB_USER"),
            "--spring.datasource.password=" + System.getenv("LIVECUT_DB_PASSWORD"));
    }
    private void startContext() { context = newContext(); jdbc = context.getBean(JdbcTemplate.class); jobs = context.getBean(JobService.class); workers = context.getBean(WorkerCapacityService.class); }
    @AfterEach void cleanup() {
        for (String id : ownJobs) { jdbc.update("DELETE FROM analysis_job WHERE id=?", id); }
        for (String id : ownWorkers) { jdbc.update("DELETE FROM worker_instance WHERE worker_id=?", id); }
        ownJobs.clear(); ownWorkers.clear();
    }
    @AfterAll void stop() { if (context != null && context.isActive()) { cleanup(); if (video != null) { jdbc.update("DELETE FROM video_asset WHERE id=?", video); } context.close(); } }

    private Fixture fixture(String type) {
        JobSnapshot job = jobs.create(new CreateJobRequest(video, Map.of()), "capacity-job-" + UUID.randomUUID()); ownJobs.add(job.id());
        String stage = job.stages().stream().filter(s -> s.stageType().equals(type)).findFirst().orElseThrow().id();
        if (!type.equals("MEDIA")) { jdbc.update("UPDATE job_stage SET status='BLOCKED' WHERE job_id=?", job.id()); jdbc.update("UPDATE job_stage SET status='READY' WHERE id=?", stage); }
        return new Fixture(job.id(), stage);
    }
    private Agent agent(List<String> types, int slots, String target) {
        Agent agent = new Agent("capacity-" + UUID.randomUUID(), UUID.randomUUID().toString()); ownWorkers.add(agent.id());
        workers.register(new Registration(agent.id(), agent.session(), types, slots, target)); return agent;
    }
    private ClaimResult claim(Fixture fixture, Agent agent, String request) { return jobs.claim(new ClaimRequest(fixture.stage(), agent.id(), request, fixture.job(), agent.session())); }
    private ClaimResult claim(Fixture fixture, Agent agent) { return claim(fixture, agent, UUID.randomUUID().toString()); }
    private static Session session(Agent agent) { return new Session(agent.id(), agent.session()); }
    private void expireWorker(Agent agent) { jdbc.update("UPDATE worker_instance SET last_seen_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE worker_id=?", agent.id()); }
    private static void code(String expected, Runnable action) {
        RuntimeException error = assertThrows(RuntimeException.class, action::run);
        if (error instanceof VideoApiException api) { assertEquals(expected, api.getCode()); }
        else if (error instanceof JobException job) { assertEquals(expected, job.getCode()); }
        else { fail("Expected a controlled worker/job error", error); }
    }
    private StageView stageView(String type) { return workers.snapshot(null).stages().stream().filter(s -> s.stageType().equals(type)).findFirst().orElseThrow(); }

    @Test void registrationIsIdempotentAndSessionCapabilitiesAreImmutable() {
        Agent agent = agent(List.of("SEGMENT", "MEDIA"), 2, null);
        assertEquals("REGISTERED", workers.register(new Registration(agent.id(), agent.session(), List.of("MEDIA", "SEGMENT"), 2, null)).outcome());
        code("WORKER_REGISTRATION_CONFLICT", () -> workers.register(new Registration(agent.id(), agent.session(), List.of("MEDIA"), 2, null)));
        code("WORKER_SESSION_CONFLICT", () -> workers.register(new Registration(agent.id(), UUID.randomUUID().toString(), List.of("MEDIA"), 2, null)));
        assertEquals(1, workers.snapshot(null).onlineWorkers());
    }
    @Test void expiredAndRetiredSessionsCannotResurrect() {
        Agent old = agent(List.of("MEDIA"), 1, null); expireWorker(old);
        code("WORKER_SESSION_EXPIRED", () -> workers.heartbeat(session(old)));
        code("WORKER_SESSION_EXPIRED", () -> workers.register(new Registration(old.id(), old.session(), List.of("MEDIA"), 1, null)));
        Agent replacement = new Agent(old.id(), UUID.randomUUID().toString());
        workers.register(new Registration(replacement.id(), replacement.session(), List.of("MEDIA"), 1, null));
        code("WORKER_SESSION_EXPIRED", () -> workers.heartbeat(session(old)));
        code("WORKER_SESSION_EXPIRED", () -> workers.unregister(session(old)));
        expireWorker(replacement);
        code("WORKER_SESSION_EXPIRED", () -> workers.register(new Registration(old.id(), old.session(), List.of("MEDIA"), 1, null)));
    }
    @Test void gracefulShutdownIsIdempotentAndAllowsNewSessionImmediately() {
        Agent old = agent(List.of("MEDIA"), 1, null);
        assertEquals("UNREGISTERED", workers.unregister(session(old)).outcome());
        assertEquals("UNREGISTERED", workers.unregister(session(old)).outcome());
        assertEquals(0, workers.snapshot(null).onlineWorkers());
        code("WORKER_SESSION_EXPIRED", () -> workers.heartbeat(session(old)));
        workers.register(new Registration(old.id(), UUID.randomUUID().toString(), List.of("MEDIA"), 1, null));
        assertEquals(1, workers.snapshot(null).onlineWorkers());
    }
    @Test void productionClaimsRequireRegisteredMatchingCapabilitiesAndTarget() {
        Fixture first = fixture("MEDIA"), other = fixture("MEDIA");
        code("WORKER_NOT_REGISTERED", () -> jobs.claim(new ClaimRequest(first.stage(), "unregistered", UUID.randomUUID().toString())));
        Agent wrongType = agent(List.of("ASR"), 1, null);
        code("WORKER_CAPABILITY_MISMATCH", () -> claim(first, wrongType));
        Agent scoped = agent(List.of("MEDIA"), 1, first.job());
        code("WORKER_TARGET_MISMATCH", () -> claim(other, scoped));
        assertEquals("CLAIMED", claim(first, scoped).outcome());
        assertEquals(0, workers.snapshot(other.job()).workers().stream().filter(w -> w.workerId().equals(scoped.id())).count());
    }
    @Test void twoConcurrentClaimsCannotSpendOneWorkerSlotTwice() throws Exception {
        Fixture a = fixture("TIMELINE"), b = fixture("TIMELINE"); Agent agent = agent(List.of("TIMELINE"), 1, null);
        List<String> outcomes = concurrent(() -> claim(a, agent).outcome(), () -> claim(b, agent).outcome());
        assertEquals(1, Collections.frequency(outcomes, "CLAIMED")); assertEquals(1, Collections.frequency(outcomes, "NO_CAPACITY"));
        assertEquals(1, jdbc.queryForObject("SELECT SUM(attempt) FROM job_stage WHERE job_id IN (?,?)", Integer.class, a.job(), b.job()));
        assertEquals(1, workers.snapshot(null).busySlots());
    }
    @Test void twoWorkersShareOneFfmpegPoolAndCancelReleasesCapacity() throws Exception {
        Fixture a = fixture("MEDIA"), b = fixture("MEDIA"); Agent first = agent(List.of("MEDIA"), 1, null), second = agent(List.of("MEDIA"), 1, null);
        List<String> outcomes = concurrent(() -> claim(a, first).outcome(), () -> claim(b, second).outcome());
        assertEquals(1, Collections.frequency(outcomes, "CLAIMED")); assertEquals(1, Collections.frequency(outcomes, "NO_CAPACITY"));
        assertEquals("RESOURCE_BUSY", stageView("MEDIA").reason());
        String running = jdbc.queryForObject("SELECT job_id FROM job_stage WHERE job_id IN (?,?) AND status='RUNNING'", String.class, a.job(), b.job());
        jobs.cancel(running);
        Fixture waiting = running.equals(a.job()) ? b : a; Agent waitingAgent = running.equals(a.job()) ? second : first;
        assertEquals("CLAIMED", claim(waiting, waitingAgent).outcome());
    }
    @Test void idempotentClaimReplaysLeaseWithoutConsumingAnotherSlot() {
        Fixture fixture = fixture("MEDIA"); Agent agent = agent(List.of("MEDIA"), 1, null); String request = UUID.randomUUID().toString();
        ClaimResult first = claim(fixture, agent, request), replay = claim(fixture, agent, request);
        assertEquals("ALREADY_CLAIMED", replay.outcome()); assertEquals(first.runToken(), replay.runToken()); assertEquals(first.attempt(), replay.attempt());
        assertEquals(1, workers.snapshot(null).busySlots());
    }
    @Test void workerTtlDoesNotReleaseTaskLeaseAndNewSessionCannotReplayOldClaim() {
        Fixture first = fixture("TIMELINE"), waiting = fixture("TIMELINE"); Agent old = agent(List.of("TIMELINE"), 1, null); String request = UUID.randomUUID().toString();
        ClaimResult lease = claim(first, old, request); expireWorker(old);
        Snapshot offline = workers.snapshot(null); assertEquals(0, offline.onlineWorkers()); assertEquals(1, offline.busySlots()); assertEquals("NO_EXECUTOR", stageView("TIMELINE").reason());
        Agent replacement = new Agent(old.id(), UUID.randomUUID().toString()); workers.register(new Registration(old.id(), replacement.session(), List.of("TIMELINE"), 1, null));
        assertEquals("EXPIRED", claim(first, replacement, request).outcome());
        assertEquals("NO_CAPACITY", claim(waiting, replacement).outcome());
        assertEquals("ACCEPTED", jobs.heartbeat(first.stage(), new Credential(lease.attempt(), lease.runToken())).outcome());
    }
    @Test void expiredTaskLeaseIsReclaimedAndLateAttemptIsRejected() {
        Fixture fixture = fixture("TIMELINE"); Agent old = agent(List.of("TIMELINE"), 1, null); ClaimResult first = claim(fixture, old);
        workers.unregister(session(old)); Agent replacement = new Agent(old.id(), UUID.randomUUID().toString());
        workers.register(new Registration(old.id(), replacement.session(), List.of("TIMELINE"), 1, null));
        jdbc.update("UPDATE job_stage SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", fixture.stage());
        jobs.recoverExpired(); jobs.recoverExpired();
        ClaimResult second = claim(fixture, replacement); assertEquals("CLAIMED", second.outcome()); assertEquals(2, second.attempt());
        code("STALE_ATTEMPT", () -> jobs.fail(fixture.stage(), new FailRequest(first.attempt(), first.runToken(), "TEST_FAILURE", false)));
    }
    @Test void independentJavaInstancesShareGlobalCapacity() throws Exception {
        Fixture a = fixture("MEDIA"), b = fixture("MEDIA"); Agent first = agent(List.of("MEDIA"), 1, null), second = agent(List.of("MEDIA"), 1, null);
        try (ConfigurableApplicationContext other = newContext()) {
            JobService otherJobs = other.getBean(JobService.class);
            List<String> outcomes = concurrent(() -> claim(a, first).outcome(), () -> otherJobs.claim(new ClaimRequest(b.stage(), second.id(), UUID.randomUUID().toString(), b.job(), second.session())).outcome());
            assertEquals(1, Collections.frequency(outcomes, "CLAIMED")); assertEquals(1, Collections.frequency(outcomes, "NO_CAPACITY"));
        }
    }
    @Test void registryAndClaimSurviveJavaRestartWithoutNewAttempt() {
        Fixture fixture = fixture("MEDIA"); Agent agent = agent(List.of("MEDIA"), 1, null); String request = UUID.randomUUID().toString(); ClaimResult first = claim(fixture, agent, request);
        context.close(); startContext();
        assertEquals("ACCEPTED", workers.heartbeat(session(agent)).outcome());
        ClaimResult replay = claim(fixture, agent, request); assertEquals("ALREADY_CLAIMED", replay.outcome()); assertEquals(first.runToken(), replay.runToken()); assertEquals(1, replay.attempt());
    }
    @Test void endpointsProtectSessionsAndPublicViewDoesNotCacheOrLeakCredentials() throws Exception {
        Agent agent = agent(List.of("MEDIA"), 1, null);
        HttpClient http = HttpClient.newHttpClient(); String base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
        for (String endpoint : List.of("register", "heartbeat", "unregister")) {
            HttpResponse<String> denied = http.send(HttpRequest.newBuilder(URI.create(base + "/internal/workers/" + endpoint)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
        }
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/executors")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.body().contains(agent.session())); assertFalse(response.body().contains("session_id")); assertFalse(response.body().contains("run_token"));
        assertEquals(5, JSON.readTree(response.body()).path("stages").size()); assertEquals(3, JSON.readTree(response.body()).path("resources").size());
        HttpResponse<String> accepted = http.send(HttpRequest.newBuilder(URI.create(base + "/internal/workers/heartbeat")).header("Content-Type", "application/json").header("X-Worker-Key", KEY)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(session(agent)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, accepted.statusCode());
    }
    private static List<String> concurrent(Callable<String> a, Callable<String> b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> { start.await(); return a.call(); });
            Future<String> second = executor.submit(() -> { start.await(); return b.call(); }); start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }
}
