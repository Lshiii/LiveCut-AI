package io.livecut.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import io.livecut.LivecutBackendApplication;
import io.livecut.video.VideoService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/MySQL transactions and hashed synthetic files; no provider/model execution. */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StageDiagnosticIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "diagnostic-test-worker-only";
    private static final AtomicBoolean FAIL_COMMIT = new AtomicBoolean(), COMMIT_INTERCEPTED = new AtomicBoolean();
    @TempDir static Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Set<String> jobs = new LinkedHashSet<>(), videos = new LinkedHashSet<>();
    private ConfigurableApplicationContext context;
    private URI base;
    private record Fixture(String job, String stage, JsonNode claim) {}

    @BeforeAll void start() {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"), "Diagnostics tests require dedicated livecut_test");
        startContext();
    }
    private void startContext() {
        context = new SpringApplicationBuilder(LivecutBackendApplication.class, CommitFixture.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--livecut.storage.root=" + root,
                "--livecut.worker.api-key=" + KEY, "--livecut.jobs.scan-enabled=false", "--livecut.uploads.scan-enabled=false",
                "--livecut.events.scan-enabled=false", "--livecut.jobs.lease-seconds=30", "--livecut.jobs.retry-delay-seconds=0",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup() {
        FAIL_COMMIT.set(false); COMMIT_INTERCEPTED.set(false);
        for (String job : jobs) jdbc().update("DELETE FROM analysis_job WHERE id=?", job);
        for (String video : videos) jdbc().update("DELETE FROM video_asset WHERE id=?", video);
        jobs.clear(); videos.clear();
    }
    @AfterAll void stop() { if (context != null) context.close(); }
    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }

    @Test void validFailurePersistsWithItsStateAndSurvivesRestartAndTerminalSseRead() throws Exception {
        Fixture fixture = fixture("SEGMENT");
        JsonNode accepted = ok(worker(action(fixture.claim(), "fail"), fail(fixture.claim(), "INVALID_MODEL_OUTPUT", false, classification())));
        assertEquals("FAILED", accepted.path("stage").path("status").asText());
        JsonNode diagnostic = accepted.path("stage").path("diagnostic");
        assertEquals(1, diagnostic.path("attempt").asInt());
        assertEquals(8, diagnostic.path("window_index").asInt());
        assertEquals("ASSIGNMENT_KEYS_MISMATCH", diagnostic.path("reason_code").asText());
        assertEquals(diagnostic, JSON.readTree(stored(fixture.stage())));
        JsonNode current = get(fixture.job());
        assertEquals(diagnostic, stage(current, "SEGMENT").path("diagnostic"));
        assertFalse(current.toString().contains(fixture.claim().path("run_token").asText()));
        byte[] before = send("GET", "/api/jobs/" + fixture.job(), null, false).body();
        context.close(); startContext();
        assertArrayEquals(before, send("GET", "/api/jobs/" + fixture.job(), null, false).body());
        var stream = send("GET", "/api/jobs/" + fixture.job() + "/events", null, false);
        assertEquals(200, stream.statusCode(), text(stream));
        String wire = text(stream);
        assertTrue(wire.contains("event:snapshot"), wire);
        assertTrue(wire.contains("id:job:" + fixture.job() + ":" + current.path("state_version").asLong()), wire);
        String data = wire.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).stripLeading()).reduce("", String::concat);
        assertEquals(current, JSON.readTree(data));
    }

    @Test void invalidDiagnosticsRejectBeforeAnyStateOrVersionChangeAndOldFourFieldFailureStillWorks() throws Exception {
        Fixture fixture = fixture("SEGMENT");
        byte[] original = send("GET", "/api/jobs/" + fixture.job(), null, false).body();
        for (Object invalid : List.of(Map.of("raw_model_text", "private-response"), Map.of("run_token", "private-token"),
                Map.of("window_index", 1), Map.of("missing_core_id_count", true), Map.of("schema_version", 99),
                Map.of("reason_code", "REQUEST_BUDGET_EXHAUSTED"))) {
            error(worker(action(fixture.claim(), "fail"), fail(fixture.claim(), "INVALID_MODEL_OUTPUT", false, invalid)), 400, "INVALID_DIAGNOSTIC");
            assertArrayEquals(original, send("GET", "/api/jobs/" + fixture.job(), null, false).body());
            assertNull(stored(fixture.stage()));
            assertEquals(fixture.claim().path("run_token").asText(), jdbc().queryForObject("SELECT run_token FROM job_stage WHERE id=?", String.class, fixture.stage()));
        }
        ok(worker(action(fixture.claim(), "fail"), fail(fixture.claim(), "0_LEGACY", false, null)));
        assertEquals("0_LEGACY", stage(get(fixture.job()), "SEGMENT").path("diagnostic").path("error_code").asText());
    }

    @Test void aRealRollbackCannotLeaveEitherDiagnosticsOrFailureStatePartiallyCommitted() throws Exception {
        Fixture fixture = fixture("SEGMENT");
        byte[] before = send("GET", "/api/jobs/" + fixture.job(), null, false).body();
        FAIL_COMMIT.set(true);
        error(worker(action(fixture.claim(), "fail"), fail(fixture.claim(), "INVALID_MODEL_OUTPUT", false, classification())), 503, "DATABASE_UNAVAILABLE");
        assertTrue(COMMIT_INTERCEPTED.get(), "The injected rollback must run after the transaction attempted its writes");
        assertArrayEquals(before, send("GET", "/api/jobs/" + fixture.job(), null, false).body());
        assertNull(stored(fixture.stage()));
        assertEquals("RUNNING", jdbc().queryForObject("SELECT status FROM job_stage WHERE id=?", String.class, fixture.stage()));
        ok(worker(action(fixture.claim(), "fail"), fail(fixture.claim(), "INVALID_MODEL_OUTPUT", false, classification())));
        assertEquals("FAILED", get(fixture.job()).path("status").asText());
        assertNotNull(stored(fixture.stage()));
    }

    @Test void manualRetryClearsFailureAndAnOldAttemptCannotOverwriteTheNewDiagnostic() throws Exception {
        Fixture fixture = fixture("SEGMENT");
        ok(worker(action(fixture.claim(), "fail"), fail(fixture.claim(), "INVALID_MODEL_OUTPUT", false, classification())));
        JsonNode retried = ok(send("POST", "/api/jobs/" + fixture.job() + "/retry", null, false));
        assertFalse(stage(retried, "SEGMENT").has("diagnostic")); assertNull(stored(fixture.stage()));
        JsonNode next = claim(fixture.stage());
        assertEquals(2, next.path("attempt").asInt());
        ok(worker(action(next, "fail"), fail(next, "OCR_CACHE_CORRUPT", false, null)));
        byte[] current = send("GET", "/api/jobs/" + fixture.job(), null, false).body();
        error(worker(action(fixture.claim(), "fail"), fail(fixture.claim(), "UNKNOWN_OUTCOME", false, null)), 409, "STALE_ATTEMPT");
        assertArrayEquals(current, send("GET", "/api/jobs/" + fixture.job(), null, false).body());
        assertEquals("INSPECT_CACHE_INTEGRITY", stage(get(fixture.job()), "SEGMENT").path("diagnostic").path("suggestion_code").asText());
    }

    @Test void automaticRetryRetainsItsReasonUntilClaimAndSuccessfulPublicationClearsIt() throws Exception {
        Fixture fixture = fixture("MEDIA");
        ok(worker(action(fixture.claim(), "fail"), fail(fixture.claim(), "PROCESS_TIMEOUT", true, null)));
        assertEquals("RETRY_WAIT", stage(get(fixture.job()), "MEDIA").path("status").asText());
        assertNotNull(stored(fixture.stage()));
        context.getBean(JobService.class).recoverExpired();
        JsonNode next = claim(fixture.stage());
        assertEquals(2, next.path("attempt").asInt());
        assertNull(stored(fixture.stage()));
        // Seed a bounded legacy carry-over to independently exercise success's
        // defensive clear, rather than only observing the preceding claim clear.
        jdbc().update("UPDATE job_stage SET error_code='PROCESS_TIMEOUT',diagnostic_json=? WHERE id=?",
                StageDiagnostics.json(StageDiagnostics.create(null, "PROCESS_TIMEOUT", 2)), fixture.stage());
        ok(worker(action(next, "complete"), mediaCompletion(next)));
        assertEquals("SUCCEEDED", stage(get(fixture.job()), "MEDIA").path("status").asText());
        assertFalse(stage(get(fixture.job()), "MEDIA").has("diagnostic")); assertNull(stored(fixture.stage()));
    }

    @Test void schedulerTimeoutsAndParentFailureCreateTheirOwnSafeAttemptBoundSummaries() throws Exception {
        Fixture fixture = fixture("MEDIA");
        jdbc().update("UPDATE job_stage SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", fixture.stage());
        context.getBean(JobService.class).recoverExpired();
        JsonNode timed = stage(get(fixture.job()), "MEDIA").path("diagnostic");
        assertEquals("LEASE_EXPIRED", timed.path("error_code").asText()); assertEquals(1, timed.path("attempt").asInt());
        JsonNode next = claim(fixture.stage()); assertNull(stored(fixture.stage()));
        jdbc().update("UPDATE job_stage SET attempt=3,lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", fixture.stage());
        jdbc().update("UPDATE job_stage SET status='READY' WHERE job_id=? AND stage_type='ASR'", fixture.job());
        context.getBean(JobService.class).recoverExpired();
        JsonNode failed = get(fixture.job()); assertEquals("FAILED", failed.path("status").asText());
        assertEquals(3, stage(failed, "MEDIA").path("diagnostic").path("attempt").asInt());
        JsonNode sibling = stage(failed, "ASR").path("diagnostic");
        assertEquals("UPSTREAM_STAGE_FAILED", sibling.path("reason_code").asText()); assertEquals(0, sibling.path("attempt").asInt());
        error(worker(action(next, "fail"), fail(next, "UNKNOWN_OUTCOME", false, null)), 409, "STALE_ATTEMPT");
    }

    @Test void nullAndCorruptStoredDiagnosticsAreReadWithoutBackfillMutationOrSensitiveEcho() throws Exception {
        Fixture fixture = fixture("MEDIA");
        byte[] initial = send("GET", "/api/jobs/" + fixture.job(), null, false).body();
        assertNull(stored(fixture.stage())); assertArrayEquals(initial, send("GET", "/api/jobs/" + fixture.job(), null, false).body());
        jdbc().update("UPDATE job_stage SET diagnostic_json=? WHERE id=?", "{\"message\":\"PRIVATE_MODEL_RESPONSE\",\"path\":\"C:/private\"}", fixture.stage());
        String stored = stored(fixture.stage());
        byte[] hidden = send("GET", "/api/jobs/" + fixture.job(), null, false).body();
        assertArrayEquals(initial, hidden); assertEquals(stored, stored(fixture.stage()), "Read cannot rewrite historical metadata");
        assertFalse(new String(hidden, StandardCharsets.UTF_8).contains("PRIVATE_MODEL_RESPONSE"));
    }

    private Fixture fixture(String type) throws Exception {
        byte[] header = ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var video = context.getBean(VideoService.class).upload(new MockMultipartFile("file", "diagnostic.mp4", "video/mp4", header));
        videos.add(video.id());
        var response = send("POST", "/api/jobs", Map.of("video_id", video.id(), "config", Map.of("visual_provider", "none")), false);
        assertEquals(201, response.statusCode(), text(response));
        JsonNode job = JSON.readTree(response.body()); jobs.add(job.path("id").asText());
        String jobId = job.path("id").asText(), stageId = stage(job, type).path("id").asText();
        if (!"MEDIA".equals(type)) {
            // Only establish protocol preconditions; no upstream computation is simulated.
            jdbc().update("UPDATE job_stage SET status='SKIPPED' WHERE job_id=? AND stage_type IN ('MEDIA','ASR')", jobId);
            jdbc().update("UPDATE job_stage SET status='READY' WHERE id=?", stageId);
        }
        return new Fixture(jobId, stageId, claim(stageId));
    }
    private JsonNode claim(String stage) throws Exception {
        return ok(worker("/internal/stages/claim", Map.of("stage_id", stage, "worker_id", "diagnostic-fixture", "claim_request_id", UUID.randomUUID().toString())));
    }
    private static Map<String, Object> classification() { return Map.of("reason_code", "ASSIGNMENT_KEYS_MISMATCH", "window_index", 8, "window_count", 10, "extra_context_id_count", 3, "unknown_key_count", 0, "non_core_key_count", 3, "format_repair", "FAILED_VALIDATION"); }
    private static Map<String, Object> fail(JsonNode claim, String code, boolean retryable, Object diagnostic) {
        Map<String, Object> result = credentials(claim); result.put("error_code", code); result.put("retryable", retryable);
        if (diagnostic != null) result.put("diagnostic", diagnostic); return result;
    }
    private static Map<String, Object> credentials(JsonNode claim) { Map<String, Object> result = new LinkedHashMap<>(); result.put("attempt", claim.path("attempt").asInt()); result.put("run_token", claim.path("run_token").asText()); return result; }
    private static String action(JsonNode claim, String action) { return "/internal/stages/" + claim.path("stage_id").asText() + "/" + action; }
    private JsonNode get(String job) throws Exception { return ok(send("GET", "/api/jobs/" + job, null, false)); }
    private String stored(String stage) { return jdbc().queryForObject("SELECT diagnostic_json FROM job_stage WHERE id=?", String.class, stage); }
    private static JsonNode stage(JsonNode job, String type) { for (JsonNode stage : job.path("stages")) if (type.equals(stage.path("stage_type").asText())) return stage; throw new AssertionError("Missing stage " + type); }
    private Map<String, Object> mediaCompletion(JsonNode claim) throws Exception {
        String prefix = "jobs/" + claim.path("job_id").asText() + "/stages/" + claim.path("stage_id").asText() + "/attempts/" + claim.path("attempt").asInt() + "/" + claim.path("run_token").asText() + "/";
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (String kind : List.of("media_info", "audio")) {
            String key = prefix + kind + ".bin"; byte[] bytes = ("synthetic protocol " + kind).getBytes(StandardCharsets.UTF_8); write(key, bytes);
            artifacts.add(Map.of("kind", kind, "key", key, "sha256", sha(bytes)));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema_version", 1); manifest.put("job_id", claim.path("job_id").asText()); manifest.put("video_id", claim.path("video_id").asText());
        manifest.put("analysis_generation", claim.path("generation").asInt()); manifest.put("stage_id", claim.path("stage_id").asText()); manifest.put("stage_type", "MEDIA");
        manifest.put("attempt", claim.path("attempt").asInt()); manifest.put("run_token", claim.path("run_token").asText()); manifest.put("input_sha256", claim.path("source_hash").asText());
        manifest.put("data_kind", "model_result"); manifest.put("review_status", "DRAFT"); manifest.put("artifacts", artifacts);
        byte[] bytes = JSON.writeValueAsBytes(manifest); write(prefix + "manifest.json", bytes);
        Map<String, Object> result = credentials(claim); result.put("manifest_key", prefix + "manifest.json"); result.put("manifest_hash", sha(bytes)); return result;
    }
    private static void write(String key, byte[] bytes) throws Exception { Path path = root.resolve(key); Files.createDirectories(path.getParent()); Files.write(path, bytes); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private HttpResponse<byte[]> worker(String path, Object body) throws Exception { return send("POST", path, body, true); }
    private HttpResponse<byte[]> send(String method, String path, Object body, boolean worker) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10));
        if (worker) builder.header("X-Worker-Key", KEY);
        if (body != null) builder.header("Content-Type", "application/json");
        if (path.equals("/api/jobs") && method.equals("POST")) builder.header("Idempotency-Key", "diagnostic-" + UUID.randomUUID());
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static JsonNode ok(HttpResponse<byte[]> response) { assertEquals(200, response.statusCode(), text(response)); return JSON.readTree(response.body()); }
    private static void error(HttpResponse<byte[]> response, int status, String code) { assertEquals(status, response.statusCode(), text(response)); assertEquals(code, JSON.readTree(response.body()).path("error_code").asText()); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }

    @TestConfiguration(proxyBeanMethods = false)
    static class CommitFixture {
        @Bean @Primary PlatformTransactionManager diagnosticTransactions(DataSource source) { return new FailingTransactions(source); }
    }
    static class FailingTransactions extends JdbcTransactionManager {
        FailingTransactions(DataSource source) { super(source); setRollbackOnCommitFailure(true); }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (FAIL_COMMIT.compareAndSet(true, false)) { COMMIT_INTERCEPTED.set(true); throw new TransactionSystemException("Synthetic diagnostic pre-commit failure"); }
            super.doCommit(status);
        }
    }
}
