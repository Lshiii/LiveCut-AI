package io.livecut.jobs;

import static io.livecut.jobs.ModelCallModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.livecut.LivecutBackendApplication;
import io.livecut.video.VideoApiException;
import io.livecut.video.VideoService;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real MySQL transaction/capacity tests; no model requests or recognition claims. */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelCallIntegrationTests {
    private static final String TOKEN = "11111111-1111-4111-8111-111111111111";
    private static final String NEXT_TOKEN = "22222222-2222-4222-8222-222222222222";
    private static final String INPUT_HASH = "a".repeat(64);
    @TempDir static Path storageRoot;
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private ModelCallService calls;
    private final List<String> videoIds = new ArrayList<>();
    private final List<String> jobIds = new ArrayList<>();
    private record Fixture(String jobId, String stageId) {}

    @BeforeAll
    void start() {
        context = new SpringApplicationBuilder(LivecutBackendApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1",
                "--livecut.jobs.scan-enabled=false",
                "--livecut.storage.root=" + storageRoot,
                "--spring.datasource.url=" + System.getenv("LIVECUT_DB_URL"),
                "--spring.datasource.username=" + System.getenv("LIVECUT_DB_USER"),
                "--spring.datasource.password=" + System.getenv("LIVECUT_DB_PASSWORD"));
        jdbc = context.getBean(JdbcTemplate.class);
        calls = context.getBean(ModelCallService.class);
    }

    @AfterEach
    void cleanupOwnRows() {
        for (String id : jobIds) { jdbc.update("DELETE FROM analysis_job WHERE id = ?", id); }
        for (String id : videoIds) { jdbc.update("DELETE FROM video_asset WHERE id = ?", id); }
        jobIds.clear(); videoIds.clear();
    }

    @AfterAll
    void stop() { if (context != null) { context.close(); } }

    @Test
    void repeatedReservationAndSameOperationDoNotConsumeAnotherSlot() {
        Fixture fixture = fixture("SEGMENT", 2);
        ReserveRequest request = reserve(fixture, "same-request", "text-op", "TEXT");
        assertEquals("RESERVED", calls.reserve(request).outcome());
        assertEquals("EXISTING", calls.reserve(request).outcome());
        var alias = new ReserveRequest(fixture.stageId(), 1, TOKEN, "other-request", INPUT_HASH, "text-op", "qwen-plus", "TEXT");
        assertEquals("same-request", calls.reserve(alias).call().requestId());
        assertEquals(1, reserved(fixture));
        var changed = new ReserveRequest(fixture.stageId(), 1, TOKEN, "same-request", "b".repeat(64), "text-op", "qwen-plus", "TEXT");
        assertCode("MODEL_CALL_INPUT_CONFLICT", () -> calls.reserve(changed));
        assertEquals(1, reserved(fixture));
    }

    @Test
    void concurrentLastSlotReservationsCannotOverspend() throws Exception {
        Fixture fixture = fixture("SEGMENT", 1);
        Callable<String> first = () -> reserveOutcome(reserve(fixture, "last-one", "op-one", "TEXT"));
        Callable<String> second = () -> reserveOutcome(reserve(fixture, "last-two", "op-two", "TEXT"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(first, second));
            List<String> outcomes = List.of(futures.get(0).get(), futures.get(1).get());
            assertTrue(outcomes.contains("RESERVED"));
            assertTrue(outcomes.contains("MODEL_BUDGET_EXHAUSTED"));
        }
        assertEquals(1, reserved(fixture));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id = ?", Integer.class, fixture.jobId()));
    }

    @Test
    void differentJobsCanReserveTheirFirstCallsConcurrently() throws Exception {
        Fixture first = fixture("SEGMENT", 1);
        Fixture second = fixture("SEGMENT", 1);
        String prefix = "first-" + UUID.randomUUID();
        String firstId = prefix + "-a";
        String secondId = prefix + "-b";
        ModelCallService overlapping = serviceWithOverlappingIdReads(Set.of(firstId, secondId), false);
        Callable<String> reserveFirst = () -> overlapping.reserve(reserve(first, firstId, "op-a", "TEXT")).outcome();
        Callable<String> reserveSecond = () -> overlapping.reserve(reserve(second, secondId, "op-b", "TEXT")).outcome();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(reserveFirst, reserveSecond), 30, TimeUnit.SECONDS);
            for (var future : futures) {
                assertFalse(future.isCancelled(), "Concurrent reservations must finish without a lock timeout");
                assertEquals("RESERVED", future.get());
            }
        }
        assertEquals(1, reserved(first));
        assertEquals(1, reserved(second));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id IN (?, ?)",
                Integer.class, first.jobId(), second.jobId()));
    }

    @Test
    void crossSwappedRequestIdsFailWithoutAcquiringAnotherJobsOperationLock() throws Exception {
        Fixture first = fixture("SEGMENT", 2);
        Fixture second = fixture("SEGMENT", 2);
        String prefix = "swapped-" + UUID.randomUUID();
        String firstId = prefix + "-a";
        String secondId = prefix + "-b";
        calls.reserve(reserve(first, firstId, "op-a", "TEXT"));
        calls.reserve(reserve(second, secondId, "op-b", "TEXT"));
        ModelCallService overlapping = serviceWithOverlappingIdReads(Set.of(firstId, secondId), true);
        Callable<String> reserveFirst = () -> reserveOutcome(overlapping, reserve(first, secondId, "op-a", "TEXT"));
        Callable<String> reserveSecond = () -> reserveOutcome(overlapping, reserve(second, firstId, "op-b", "TEXT"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(reserveFirst, reserveSecond), 30, TimeUnit.SECONDS);
            for (var future : futures) {
                assertFalse(future.isCancelled(), "Conflicting request IDs must return without a lock timeout");
                assertEquals("MODEL_CALL_INPUT_CONFLICT", future.get());
            }
        }
        assertEquals(1, reserved(first));
        assertEquals(1, reserved(second));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id IN (?, ?)",
                Integer.class, first.jobId(), second.jobId()));
    }

    @Test
    void dispatchGrantIsGivenOnceAndUnknownDoesNotReleaseCapacity() {
        Fixture fixture = fixture("SEGMENT", 1);
        calls.reserve(reserve(fixture, "dispatch-once", "op", "TEXT"));
        assertEquals("UPDATED", update(fixture, "dispatch-once", "DISPATCHING", null, Map.of()).outcome());
        assertEquals("ALREADY_APPLIED", update(fixture, "dispatch-once", "DISPATCHING", null, Map.of()).outcome());
        update(fixture, "dispatch-once", "UNKNOWN_OUTCOME", null, Map.of());
        assertEquals("UNKNOWN_OUTCOME", calls.reserve(reserve(fixture, "dispatch-once", "op", "TEXT")).call().status());
        assertCode("MODEL_CALL_STATE_CONFLICT", () -> update(fixture, "dispatch-once", "DISPATCHING", null, Map.of()));
        assertCode("MODEL_BUDGET_EXHAUSTED", () -> calls.reserve(reserve(fixture, "another-call", "another-op", "TEXT")));
        assertEquals(1, reserved(fixture));
    }

    @Test
    void expiredOwnerCanSettleItsCallWithoutMutatingNewAttempt() {
        Fixture fixture = fixture("SEGMENT", 2);
        calls.reserve(reserve(fixture, "late-accounting", "op", "TEXT"));
        update(fixture, "late-accounting", "DISPATCHING", null, Map.of());
        update(fixture, "late-accounting", "UNKNOWN_OUTCOME", null, Map.of());
        reclaim(fixture);
        assertCode("STALE_ATTEMPT", () -> calls.reserve(reserve(fixture, "illegal-new", "new-op", "TEXT")));
        assertCode("STALE_ATTEMPT", () -> update(fixture, "late-accounting", "DISPATCHING", null, Map.of()));
        var settled = update(fixture, "late-accounting", "SUCCEEDED", null, Map.of("input_tokens", 10, "output_tokens", 2));
        assertEquals("SUCCEEDED", settled.call().status());
        assertEquals(10L, settled.call().usage().get("input_tokens"));
        assertEquals(2, jdbc.queryForObject("SELECT attempt FROM job_stage WHERE id = ?", Integer.class, fixture.stageId()));
        assertEquals(NEXT_TOKEN, jdbc.queryForObject("SELECT run_token FROM job_stage WHERE id = ?", String.class, fixture.stageId()));
        assertCode("MODEL_CALL_STATE_CONFLICT", () -> update(fixture, "late-accounting", "FAILED", null, Map.of()));
    }

    @Test
    void existingAsrTaskCanBeRecoveredByNewAttemptWithoutResubmission() {
        Fixture fixture = fixture("ASR", 2);
        calls.reserve(reserve(fixture, "asr-existing", "asr-op", "ASR"));
        update(fixture, "asr-existing", "DISPATCHING", null, Map.of());
        update(fixture, "asr-existing", "SUBMITTED", "provider-task-1", Map.of());
        reclaim(fixture);
        var resumed = new ReserveRequest(fixture.stageId(), 2, NEXT_TOKEN, "asr-existing", INPUT_HASH, "asr-op", "paraformer-v2", "ASR");
        assertEquals("provider-task-1", calls.reserve(resumed).call().providerTaskId());
        var success = calls.update("asr-existing", new UpdateRequest(fixture.stageId(), 2, NEXT_TOKEN, "SUCCEEDED", "provider-task-1", Map.of("audio_ms", 60000)));
        assertEquals("SUCCEEDED", success.call().status());
        assertEquals(1, jdbc.queryForObject("SELECT asr_calls_reserved FROM analysis_job WHERE id = ?", Integer.class, fixture.jobId()));
    }

    @Test
    void usageAndProviderIdentityCannotBeOverwritten() {
        Fixture fixture = fixture("ASR", 2);
        calls.reserve(reserve(fixture, "identity", "asr-op", "ASR"));
        update(fixture, "identity", "DISPATCHING", null, Map.of());
        update(fixture, "identity", "SUBMITTED", "provider-original", Map.of("input_tokens", 5));
        assertCode("MODEL_TASK_CONFLICT", () -> update(fixture, "identity", "SUBMITTED", "provider-different", Map.of()));
        assertCode("MODEL_USAGE_CONFLICT", () -> update(fixture, "identity", "SUBMITTED", null, Map.of("input_tokens", 6)));
        assertCode("INVALID_MODEL_CALL", () -> update(fixture, "identity", "SUBMITTED", null, Map.of("input_tokens", 1.5)));
        assertCode("INVALID_MODEL_CALL", () -> update(fixture, "identity", "SUBMITTED", null, Map.of("input_tokens", -1)));
        var added = update(fixture, "identity", "SUCCEEDED", null, Map.of("audio_ms", 1000));
        assertEquals(5L, added.call().usage().get("input_tokens"));
        assertEquals(1000L, added.call().usage().get("audio_ms"));
        assertEquals("ALREADY_APPLIED", update(fixture, "identity", "SUCCEEDED", null, Map.of()).outcome());
    }

    @Test
    void dispatchMetadataOrANewAttemptCannotObtainAnotherNetworkGrant() {
        Fixture fixture = fixture("SEGMENT", 2);
        calls.reserve(reserve(fixture, "one-network-grant", "op", "TEXT"));
        assertEquals("UPDATED", update(fixture, "one-network-grant", "DISPATCHING", null, Map.of()).outcome());
        assertCode("INVALID_MODEL_CALL", () -> update(fixture, "one-network-grant", "DISPATCHING", null, Map.of("input_tokens", 0)));
        assertCode("INVALID_MODEL_CALL", () -> update(fixture, "one-network-grant", "DISPATCHING", "invented-task", Map.of()));
        reclaim(fixture);
        var replay = new UpdateRequest(fixture.stageId(), 2, NEXT_TOKEN, "DISPATCHING", null, Map.of());
        assertEquals("ALREADY_APPLIED", calls.update("one-network-grant", replay).outcome());
        assertEquals(1, jdbc.queryForObject("SELECT dispatch_attempt FROM model_call WHERE request_id = 'one-network-grant'", Integer.class));
        assertEquals(1, reserved(fixture));
    }

    @Test
    void stageAndModelMustMatchImmutableConfiguration() {
        Fixture fixture = fixture("SEGMENT", 2);
        assertCode("MODEL_KIND_CONFLICT", () -> calls.reserve(reserve(fixture, "bad-kind", "op", "ASR")));
        var badModel = new ReserveRequest(fixture.stageId(), 1, TOKEN, "bad-model", INPUT_HASH, "op", "another-model", "TEXT");
        assertCode("MODEL_CONFIG_CONFLICT", () -> calls.reserve(badModel));
        assertEquals(0, reserved(fixture));
    }

    @Test
    void providerResponseBytesAreCheckedBeforeSettlementAndRemainRecoverableAcrossAttempts() throws Exception {
        Fixture fixture = fixture("SEGMENT", 2);
        String requestId = "response-" + UUID.randomUUID();
        calls.reserve(reserve(fixture, requestId, "response-op", "TEXT"));
        update(fixture, requestId, "DISPATCHING", null, Map.of());
        String key = "jobs/" + fixture.jobId() + "/stages/" + fixture.stageId()
                + "/attempts/1/" + TOKEN + "/provider-responses/" + requestId + ".json";
        var missing = new UpdateRequest(fixture.stageId(), 1, TOKEN, "SUCCEEDED", null, Map.of(), key, INPUT_HASH);
        assertCode("ARTIFACT_NOT_FOUND", () -> calls.update(requestId, missing));
        assertEquals("DISPATCHING", jdbc.queryForObject("SELECT status FROM model_call WHERE request_id=?", String.class, requestId));
        assertNull(jdbc.queryForObject("SELECT response_key FROM model_call WHERE request_id=?", String.class, requestId));

        // This intentionally opaque binary body proves byte integrity only.
        byte[] raw = new byte[] {0, -1, 7, 31};
        Path response = storageRoot.resolve(key);
        Files.createDirectories(response.getParent());
        Files.write(response, raw);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        var corrupt = new UpdateRequest(fixture.stageId(), 1, TOKEN, "SUCCEEDED", null, Map.of(), key, INPUT_HASH);
        assertCode("ARTIFACT_HASH_MISMATCH", () -> calls.update(requestId, corrupt));
        String otherAttempt = key.replace("/attempts/1/", "/attempts/2/");
        var misplaced = new UpdateRequest(fixture.stageId(), 1, TOKEN, "SUCCEEDED", null, Map.of(), otherAttempt, digest);
        assertCode("INVALID_RESPONSE_REFERENCE", () -> calls.update(requestId, misplaced));

        var valid = new UpdateRequest(fixture.stageId(), 1, TOKEN, "SUCCEEDED", null, Map.of("input_tokens", 2), key, digest);
        assertEquals("UPDATED", calls.update(requestId, valid).outcome());
        reclaim(fixture);
        var resumed = new ReserveRequest(fixture.stageId(), 2, NEXT_TOKEN, requestId, INPUT_HASH, "response-op", "qwen-plus", "TEXT");
        var saved = calls.reserve(resumed);
        assertEquals("EXISTING", saved.outcome());
        assertEquals(key, saved.call().responseKey());
        assertEquals(digest, saved.call().responseHash());
        assertEquals(1, reserved(fixture));
        assertEquals("ALREADY_APPLIED", calls.update(requestId, valid).outcome());
    }

    private Fixture fixture(String stageType, int textLimit) {
        byte[] header = ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII))
                .putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8)
                .put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var asset = context.getBean(VideoService.class).upload(new MockMultipartFile("file", "synthetic.mp4", "video/mp4", header));
        videoIds.add(asset.id());
        String jobId = UUID.randomUUID().toString();
        String stageId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?, ?, 1, ?, ?, ?, ?, 'RUNNING', ?, 1)",
                jobId, asset.id(), UUID.randomUUID().toString(), INPUT_HASH, INPUT_HASH,
                "{\"asr_model\":\"paraformer-v2\",\"text_model\":\"qwen-plus\",\"visual_provider\":\"ocr\"}", textLimit);
        jobIds.add(jobId);
        jdbc.update("INSERT INTO job_stage (id,job_id,stage_type,status,attempt,run_token,worker_id,lease_until,hard_deadline_at,input_hash) VALUES (?, ?, ?, 'RUNNING', 1, ?, 'budget-tests', DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 60 SECOND), DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1800 SECOND), ?)",
                stageId, jobId, stageType, TOKEN, INPUT_HASH);
        return new Fixture(jobId, stageId);
    }

    private ReserveRequest reserve(Fixture fixture, String id, String operation, String kind) {
        return new ReserveRequest(fixture.stageId(), 1, TOKEN, id, INPUT_HASH, operation, "ASR".equals(kind) ? "paraformer-v2" : "qwen-plus", kind);
    }
    private CallResponse update(Fixture fixture, String id, String state, String taskId, Map<String, Object> usage) {
        return calls.update(id, new UpdateRequest(fixture.stageId(), 1, TOKEN, state, taskId, usage));
    }
    private int reserved(Fixture fixture) {
        return jdbc.queryForObject("SELECT text_calls_reserved FROM analysis_job WHERE id = ?", Integer.class, fixture.jobId());
    }
    private String reserveOutcome(ReserveRequest request) {
        return reserveOutcome(calls, request);
    }
    private String reserveOutcome(ModelCallService service, ReserveRequest request) {
        try { return service.reserve(request).outcome(); }
        catch (VideoApiException failure) { return failure.getCode(); }
    }

    private ModelCallService serviceWithOverlappingIdReads(Set<String> requestIds, boolean expectExisting) {
        CountDownLatch bothReads = new CountDownLatch(2);
        Set<String> observed = ConcurrentHashMap.newKeySet();
        JdbcTemplate overlapping = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
                List<T> rows = super.query(sql, mapper, arguments);
                if (sql.equals("SELECT * FROM model_call WHERE request_id = ? FOR UPDATE")
                        && arguments.length == 1 && requestIds.contains(arguments[0])
                        && expectExisting == !rows.isEmpty() && observed.add((String) arguments[0])) {
                    // Pause only after the real database read has acquired its locks.
                    // Both transactions must overlap to exercise gap locks and cross-job cycles.
                    bothReads.countDown();
                    try {
                        if (!bothReads.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Both model-call transactions did not reach their locked ID read");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while overlapping model-call transactions", failure);
                    }
                }
                return rows;
            }
        };
        return new ModelCallService(overlapping, context.getBean(PlatformTransactionManager.class),
                context.getBean(JobService.class), context.getBean(ObjectMapper.class), context.getBean(ArtifactVerifier.class));
    }
    private void reclaim(Fixture fixture) {
        jdbc.update("UPDATE job_stage SET attempt = 2, run_token = ? WHERE id = ?", NEXT_TOKEN, fixture.stageId());
    }
    private static void assertCode(String code, Runnable action) {
        RuntimeException exception = assertThrows(RuntimeException.class, action::run);
        String actual = exception instanceof VideoApiException video ? video.getCode()
                : exception instanceof JobException job ? job.getCode() : exception.getClass().getSimpleName();
        assertEquals(code, actual);
    }
}
