package io.livecut.exports;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.livecut.LivecutBackendApplication;
import io.livecut.jobs.JobService;
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
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real HTTP/MySQL and hashed synthetic files; no claim of real decoding/model quality. */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "export-protocol-test-only-key";
    private static final String FIRST = "manual:one.1";
    private static final String SECOND = "m".repeat(128);
    private static final byte[] CLIP_BYTES = new byte[128];
    static { for (int index = 0; index < CLIP_BYTES.length; index++) { CLIP_BYTES[index] = (byte) index; } }
    @TempDir static Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<String> jobs = new ArrayList<>();
    private final List<String> videos = new ArrayList<>();
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private URI base;
    private record Fixture(String jobId, String videoId, Path rawPath, String rawHash) {}

    @BeforeAll
    void start() {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:\\?.*)?"), "Export tests require dedicated livecut_test");
        startContext();
    }

    private void startContext() {
        context = new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0", "--server.address=127.0.0.1",
                "--livecut.storage.root=" + root, "--livecut.worker.api-key=" + KEY, "--livecut.jobs.scan-enabled=false",
                "--livecut.jobs.retry-delay-seconds=0", "--livecut.jobs.lease-seconds=30");
        jdbc = context.getBean(JdbcTemplate.class);
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @AfterEach
    void cleanupOwnRows() {
        for (String id : jobs) { jdbc.update("DELETE FROM analysis_job WHERE id=?", id); }
        for (String id : videos) { jdbc.update("DELETE FROM video_asset WHERE id=?", id); }
        jobs.clear(); videos.clear();
    }

    @AfterAll void close() { if (context != null) { context.close(); } }

    @Test
    void simultaneousSameSelectionBuildsOneBatchAndDistinctSelectionsRemainIndependent() throws Exception {
        Fixture fixture = fixture(true);
        Map<String, Object> request = createBody(fixture, 0, List.of(SECOND, FIRST));
        CountDownLatch gate = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> after(gate, "POST", "/api/exports", request));
        var second = CompletableFuture.supplyAsync(() -> after(gate, "POST", "/api/exports", request));
        gate.countDown();
        JsonNode one = created(first.get(20, TimeUnit.SECONDS));
        JsonNode two = created(second.get(20, TimeUnit.SECONDS));
        assertEquals(one.path("id"), two.path("id"));
        assertEquals(one.path("id"), create(fixture, 0, List.of(FIRST, SECOND)).path("id"));
        JsonNode subset = create(fixture, 0, List.of(FIRST));
        assertNotEquals(one.path("id"), subset.path("id"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE job_id=?", Integer.class, fixture.jobId()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM job_stage WHERE job_id=? AND owner_type='EXPORT'", Integer.class, fixture.jobId()));
        assertEquals(1, get("/api/jobs/" + fixture.jobId()).path("stages").size(), "Public analysis snapshot must exclude export stages");
        JsonNode page = get("/api/exports?job_id=" + fixture.jobId() + "&limit=1");
        assertEquals(1, page.path("items").size());
        assertEquals(1, page.path("limit").intValue());
        assertTrue(one.path("clips").get(0).path("content_url").isNull());
    }

    @Test
    void exactHistoricalConfirmationAndNamesBoundsStayFrozenAfterNewDraftAndRestart() throws Exception {
        Fixture fixture = fixture(false);
        JsonNode initial = get(review(fixture));
        ObjectNode edit = edit(initial);
        ((ObjectNode) edit.path("products").get(0)).put("name", "已确认旧商品名");
        ((ObjectNode) edit.path("segments").get(0)).put("start_ms", 800);
        ok(send("PUT", review(fixture), edit));
        ok(send("POST", review(fixture) + "/confirm", Map.of("revision", 1)));
        JsonNode frozen = create(fixture, 1, List.of(FIRST));
        ObjectNode next = edit(get(review(fixture)));
        ((ObjectNode) next.path("products").get(0)).put("name", "新草稿商品名");
        ((ObjectNode) next.path("segments").get(0)).put("start_ms", 1000);
        ok(send("PUT", review(fixture), next));
        assertEquals("DRAFT", get(review(fixture)).path("status").stringValue());
        JsonNode reused = create(fixture, 1, List.of(FIRST));
        assertEquals(frozen.path("id"), reused.path("id"));
        assertEquals("已确认旧商品名", reused.path("clips").get(0).path("product_name").stringValue());
        assertEquals(800, reused.path("clips").get(0).path("start_ms").longValue());
        assertEquals("面膜\";evil.mp4", get(review(fixture) + "?revision=0").path("products").get(0).path("name").stringValue());
        assertEquals(fixture.rawHash(), sha(Files.readAllBytes(fixture.rawPath())));
        error(send("POST", "/api/exports", createBody(fixture, 2, List.of(FIRST))), 409, "EXPORT_REQUIRES_CONFIRMATION");
        context.close(); startContext();
        assertEquals(frozen.path("snapshot_hash"), get("/api/exports/" + frozen.path("id").stringValue()).path("snapshot_hash"));
        assertEquals(frozen.path("id"), create(fixture, 1, List.of(FIRST)).path("id"));
    }

    @Test
    void manifestDownloadListsCurrentStateAndOnlyVerifiedSuccessfulFiles() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode batch = create(fixture, 0, List.of(FIRST, SECOND));
        String batchId = batch.path("id").stringValue();
        String inventoryPath = "/api/exports/" + batchId + "/manifest";
        HttpResponse<byte[]> queuedResponse = send("GET", inventoryPath, null);
        JsonNode queued = ok(queuedResponse);
        assertAttachment(queuedResponse, "export-" + batchId + "-v" + queued.path("state_version").longValue() + ".json");
        assertEquals("1.0", queued.path("schema_version").stringValue());
        assertEquals("export_manifest", queued.path("data_kind").stringValue());
        assertEquals(batchId, queued.path("export_id").stringValue());
        assertEquals(fixture.jobId(), queued.path("job_id").stringValue());
        assertEquals(fixture.videoId(), queued.path("video_id").stringValue());
        assertEquals(1, queued.path("generation").intValue());
        assertEquals(0, queued.path("approved_revision").longValue());
        assertEquals("QUEUED", queued.path("status").stringValue());
        assertEquals("h264-aac-v1", queued.path("profile").stringValue());
        assertEquals(jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, fixture.videoId()),
                queued.path("source_video_sha256").stringValue());
        for (JsonNode clip : queued.path("clips")) { assertTrue(clip.path("file").isNull()); }

        JsonNode successClaim = claim(batch.path("clips").get(0));
        JsonNode failedClaim = claim(batch.path("clips").get(1));
        ok(worker("POST", action(successClaim, "complete"), completion(successClaim)));
        ok(worker("POST", action(failedClaim, "fail"), with(failedClaim, "error_code", "SYNTHETIC_BAD_CLIP", "retryable", false)));
        HttpResponse<byte[]> response = send("GET", inventoryPath, null);
        JsonNode partial = ok(response);
        assertAttachment(response, "export-" + batchId + "-v" + partial.path("state_version").longValue() + ".json");
        assertEquals("PARTIAL_FAILED", partial.path("status").stringValue());
        assertTrue(partial.path("state_version").longValue() > queued.path("state_version").longValue());
        assertEquals(batch.path("snapshot_hash"), partial.path("snapshot_hash"));
        assertEquals(get("/api/exports/" + batchId).path("updated_at"), partial.path("updated_at"));
        int files = 0;
        for (JsonNode clip : partial.path("clips")) {
            assertEquals(1, clip.path("attempt").intValue());
            if ("SUCCEEDED".equals(clip.path("status").stringValue())) {
                files++;
                JsonNode file = clip.path("file");
                assertEquals(clip.path("clip_id").stringValue() + ".mp4", file.path("filename").stringValue());
                assertEquals(CLIP_BYTES.length, file.path("size_bytes").longValue());
                assertEquals(sha(CLIP_BYTES), file.path("sha256").stringValue());
                assertEquals("/api/exports/" + batchId + "/clips/" + clip.path("clip_id").stringValue() + "/content?download=true", file.path("download_url").stringValue());
                assertArrayEquals(CLIP_BYTES, send("GET", file.path("download_url").stringValue(), null).body());
            } else {
                assertEquals("FAILED", clip.path("status").stringValue());
                assertEquals("SYNTHETIC_BAD_CLIP", clip.path("error_code").stringValue());
                assertTrue(clip.path("file").isNull());
            }
        }
        assertEquals(1, files);
        String text = new String(response.body(), StandardCharsets.UTF_8);
        for (String privateValue : List.of("run_token", "manifest_key", "source_key", "response_key", "stage_id", "jobs/", KEY,
                root.toAbsolutePath().toString(), successClaim.path("run_token").stringValue(), failedClaim.path("run_token").stringValue())) {
            assertFalse(text.contains(privateValue), "Public inventory must not expose private execution references");
        }
        assertArrayEquals(response.body(), send("GET", inventoryPath, null).body(), "An unchanged batch has stable inventory bytes");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, fixture.jobId()));
    }

    @Test
    void manifestDownloadKeepsHistoricalRevisionTwoAfterNewDraftAndRestart() throws Exception {
        Fixture fixture = fixture(false);
        ok(send("PUT", review(fixture), edit(get(review(fixture)))));
        ObjectNode second = edit(get(review(fixture)));
        ((ObjectNode) second.path("products").get(0)).put("name", "已确认r2商品名");
        ((ObjectNode) second.path("segments").get(0)).put("start_ms", 800);
        ok(send("PUT", review(fixture), second));
        ok(send("POST", review(fixture) + "/confirm", Map.of("revision", 2)));
        JsonNode batch = create(fixture, 2, List.of(FIRST));
        String path = "/api/exports/" + batch.path("id").stringValue() + "/manifest";
        byte[] before = send("GET", path, null).body();
        JsonNode inventory = JSON.readTree(before);
        assertEquals(2, inventory.path("approved_revision").longValue());
        assertEquals("已确认r2商品名", inventory.path("clips").get(0).path("product_name").stringValue());
        assertEquals(800, inventory.path("clips").get(0).path("start_ms").longValue());
        ObjectNode draft = edit(get(review(fixture)));
        ((ObjectNode) draft.path("products").get(0)).put("name", "r3未确认新商品名");
        ((ObjectNode) draft.path("segments").get(0)).put("start_ms", 1100);
        ok(send("PUT", review(fixture), draft));
        assertEquals("DRAFT", get(review(fixture)).path("status").stringValue());
        assertArrayEquals(before, send("GET", path, null).body());
        context.close(); startContext();
        assertArrayEquals(before, send("GET", path, null).body());
        assertEquals(fixture.rawHash(), sha(Files.readAllBytes(fixture.rawPath())));
    }

    @Test
    void manifestDownloadRejectsMissingTamperedFilesAndInvalidFrozenGeneration() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode batch = create(fixture, 0, List.of(FIRST));
        String batchId = batch.path("id").stringValue();
        String path = "/api/exports/" + batchId + "/manifest";
        JsonNode claim = claim(batch.path("clips").get(0));
        ok(worker("POST", action(claim, "complete"), completion(claim)));
        byte[] originalInventory = send("GET", path, null).body();
        Path clipPath = root.resolve(prefix(claim) + "clip.mp4");
        byte[] changed = CLIP_BYTES.clone(); changed[0] ^= 1;
        try {
            Files.write(clipPath, changed);
            error(send("GET", path, null), 409, "ARTIFACT_HASH_MISMATCH");
            Files.delete(clipPath);
            error(send("GET", path, null), 409, "ARTIFACT_NOT_FOUND");
        } finally { Files.write(clipPath, CLIP_BYTES); }
        String originalFrozen = jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?", String.class, batchId);
        try {
            for (String generation : List.of("0", "-1", "1.5", "\"1\"", "2147483648", "null")) {
                ObjectNode frozen = (ObjectNode) JSON.readTree(originalFrozen);
                frozen.set("generation", JSON.readTree(generation));
                jdbc.update("UPDATE export_job SET frozen_json=? WHERE id=?", JSON.writeValueAsString(frozen), batchId);
                error(send("GET", path, null), 409, "EXPORT_SNAPSHOT_INVALID");
            }
        } finally { jdbc.update("UPDATE export_job SET frozen_json=? WHERE id=?", originalFrozen, batchId); }
        assertArrayEquals(originalInventory, send("GET", path, null).body());
        assertEquals("SUCCEEDED", get("/api/exports/" + batchId).path("status").stringValue());
    }

    @Test
    void unconfirmedDisabledAndInvalidSelectionsCannotBeExported() throws Exception {
        Fixture fixture = fixture(false);
        error(send("POST", "/api/exports", createBody(fixture, 0, List.of(FIRST))), 409, "EXPORT_REQUIRES_CONFIRMATION");
        ObjectNode edited = edit(get(review(fixture)));
        ((ObjectNode) edited.path("segments").get(0)).put("enabled", false);
        ok(send("PUT", review(fixture), edited));
        ok(send("POST", review(fixture) + "/confirm", Map.of("revision", 1)));
        error(send("POST", "/api/exports", createBody(fixture, 1, List.of(FIRST))), 400, "INVALID_EXPORT_SEGMENT");
        error(send("POST", "/api/exports", createBody(fixture, 1, List.of("unknown"))), 400, "INVALID_EXPORT_SEGMENT");
        error(send("POST", "/api/exports", createBody(fixture, 1, List.of(SECOND, SECOND))), 400, "INVALID_EXPORT");
        error(send("POST", "/api/exports", Map.of("job_id", fixture.jobId(), "approved_revision", 1, "segment_ids", List.of(SECOND), "profile", "custom-ffmpeg")), 400, "INVALID_EXPORT_PROFILE");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE job_id=?", Integer.class, fixture.jobId()));
        assertEquals(0, get("/api/exports?job_id=" + fixture.jobId()).path("items").size());
    }

    @Test
    void failedClipDiagnosticIsPersistedAndClearedOnRetryWithoutChangingFrozenReviewOrOlderBatchBytes() throws Exception {
        Fixture fixture = fixture(true);
        byte[] approved = send("GET", review(fixture) + "/download?revision=0", null).body();
        byte[] analysis = send("GET", "/api/jobs/" + fixture.jobId(), null).body();
        JsonNode older = create(fixture, 0, List.of(FIRST));
        String olderPath = "/api/exports/" + older.path("id").asText();
        byte[] olderBytes = send("GET", olderPath, null).body();
        JsonNode batch = create(fixture, 0, List.of(SECOND));
        String batchId = batch.path("id").asText(), clipId = batch.path("clips").get(0).path("id").asText();
        String frozen = jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?", String.class, batchId);
        JsonNode first = claim(batch.path("clips").get(0));
        Map<String, Object> body = with(first, "error_code", "PROCESS_TIMEOUT", "retryable", false);
        body.put("diagnostic", Map.of());
        ok(worker("POST", action(first, "fail"), body));
        JsonNode failed = get("/api/exports/" + batchId);
        assertEquals("FAILED", failed.path("status").asText());
        JsonNode diagnostic = failed.path("clips").get(0).path("diagnostic");
        assertEquals("EXECUTION_TIMEOUT", diagnostic.path("reason_code").asText());
        assertEquals(diagnostic, JSON.readTree(jdbc.queryForObject("SELECT diagnostic_json FROM job_stage WHERE id=?", String.class, first.path("stage_id").asText())));
        JsonNode ready = ok(send("POST", "/api/exports/" + batchId + "/clips/" + clipId + "/retry", null));
        assertFalse(ready.path("clips").get(0).has("diagnostic"));
        assertNull(jdbc.queryForObject("SELECT diagnostic_json FROM job_stage WHERE id=?", String.class, first.path("stage_id").asText()));
        JsonNode second = claim(batch.path("clips").get(0));
        error(worker("POST", action(first, "fail"), body), 409, "STALE_ATTEMPT");
        ok(worker("POST", action(second, "complete"), completion(second)));
        assertFalse(get("/api/exports/" + batchId).path("clips").get(0).has("diagnostic"));
        assertEquals(frozen, jdbc.queryForObject("SELECT frozen_json FROM export_job WHERE id=?", String.class, batchId));
        assertArrayEquals(approved, send("GET", review(fixture) + "/download?revision=0", null).body());
        assertArrayEquals(analysis, send("GET", "/api/jobs/" + fixture.jobId(), null).body());
        assertArrayEquals(olderBytes, send("GET", olderPath, null).body());
        assertEquals(fixture.rawHash(), sha(Files.readAllBytes(fixture.rawPath())));
    }

    @Test
    void failedClipDoesNotAffectSiblingOrAnalysisAndOnlyFailedClipRetries() throws Exception {
        Fixture fixture = fixture(true);
        long analysisVersion = get("/api/jobs/" + fixture.jobId()).path("state_version").longValue();
        JsonNode batch = create(fixture, 0, List.of(FIRST, SECOND));
        JsonNode independent = create(fixture, 0, List.of(SECOND));
        JsonNode first = claim(batch.path("clips").get(0));
        JsonNode second = claim(batch.path("clips").get(1));
        assertEquals("CLAIMED", first.path("outcome").stringValue());
        ok(worker("POST", action(first, "fail"), with(first, "error_code", "SYNTHETIC_BAD_CLIP", "retryable", false)));
        assertEquals("RUNNING", get("/api/exports/" + batch.path("id").stringValue()).path("status").stringValue());
        ok(worker("POST", action(second, "complete"), completion(second)));
        JsonNode partial = get("/api/exports/" + batch.path("id").stringValue());
        assertEquals("PARTIAL_FAILED", partial.path("status").stringValue());
        assertEquals("QUEUED", get("/api/exports/" + independent.path("id").stringValue()).path("status").stringValue());
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?", String.class,
                independent.path("clips").get(0).path("stage_id").stringValue()));
        String failedId = first.path("config").path("export").path("clip_id").stringValue();
        String successfulId = second.path("config").path("export").path("clip_id").stringValue();
        ok(send("POST", "/api/exports/" + batch.path("id").stringValue() + "/clips/" + failedId + "/retry", null));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?", String.class, second.path("stage_id").stringValue()));
        JsonNode retried = claim(batch.path("clips").get(0));
        assertEquals(2, retried.path("attempt").intValue());
        error(worker("POST", action(first, "complete"), completion(first)), 409, "STALE_ATTEMPT");
        ok(worker("POST", action(retried, "complete"), completion(retried)));
        assertEquals("SUCCEEDED", get("/api/exports/" + batch.path("id").stringValue()).path("status").stringValue());
        error(send("POST", "/api/exports/" + batch.path("id").stringValue() + "/clips/" + successfulId + "/retry", null), 409, "EXPORT_CLIP_NOT_RETRYABLE");
        JsonNode analysis = get("/api/jobs/" + fixture.jobId());
        assertEquals("REVIEW_READY", analysis.path("status").stringValue());
        assertEquals(analysisVersion, analysis.path("state_version").longValue());
        assertEquals(fixture.rawHash(), sha(Files.readAllBytes(fixture.rawPath())));
        assertFalse(partial.toString().contains(first.path("run_token").stringValue()));
        assertFalse(partial.toString().contains("jobs/"));
    }

    @Test
    void claimReceiptsHeartbeatCheckpointLeaseRecoveryAndHardDeadlineUseSharedProtocol() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode batch = create(fixture, 0, List.of(FIRST));
        String stageId = batch.path("clips").get(0).path("stage_id").stringValue();
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> claimBody = Map.of("stage_id", stageId, "worker_id", "export-resume", "claim_request_id", requestId);
        JsonNode old = ok(worker("POST", "/internal/stages/claim", claimBody));
        assertEquals(old.path("run_token"), ok(worker("POST", "/internal/stages/claim", claimBody)).path("run_token"));
        JsonNode heartbeat = ok(worker("POST", action(old, "heartbeat"), credentials(old)));
        assertTrue(java.time.Instant.parse(heartbeat.path("stage").path("lease_until").stringValue()).compareTo(java.time.Instant.parse(old.path("hard_deadline_at").stringValue())) <= 0);
        String checkpoint = prefix(old) + "checkpoint.bin";
        write(checkpoint, new byte[] {1, 2, 3});
        ok(worker("POST", action(old, "checkpoint"), with(old, "checkpoint_key", checkpoint, "checkpoint_hash", sha(new byte[] {1, 2, 3}))));
        jdbc.update("UPDATE job_stage SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", stageId);
        context.getBean(JobService.class).recoverExpired();
        assertEquals("EXPIRED", ok(worker("POST", "/internal/stages/claim", claimBody)).path("outcome").stringValue());
        JsonNode resumed = claim(batch.path("clips").get(0));
        assertEquals(2, resumed.path("attempt").intValue());
        assertEquals(checkpoint, resumed.path("checkpoint_key").stringValue());
        error(worker("POST", action(old, "heartbeat"), credentials(old)), 409, "STALE_ATTEMPT");
        error(worker("POST", action(old, "complete"), completion(old)), 409, "STALE_ATTEMPT");
        Map<String, Object> complete = completion(resumed);
        jdbc.update("UPDATE job_stage SET hard_deadline_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", stageId);
        error(worker("POST", action(resumed, "complete"), complete), 409, "STALE_ATTEMPT");
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?", String.class, stageId), "No reaper ran before the hard-deadline rejection");
        context.getBean(JobService.class).recoverExpired();
        JsonNode last = claim(batch.path("clips").get(0));
        assertEquals(3, last.path("attempt").intValue());
        ok(worker("POST", action(last, "fail"), with(last, "error_code", "SYNTHETIC_FAILURE", "retryable", true)));
        error(send("POST", "/api/exports/" + batch.path("id").stringValue() + "/clips/" + batch.path("clips").get(0).path("id").stringValue() + "/retry", null), 409, "ATTEMPTS_EXHAUSTED");
    }

    @Test
    void exportNeverReceivesTextOrAsrProviderPermission() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode batch = create(fixture, 0, List.of(FIRST));
        JsonNode claim = claim(batch.path("clips").get(0));
        for (String kind : List.of("TEXT", "ASR")) {
            var request = new java.util.LinkedHashMap<String, Object>(credentials(claim));
            request.put("stage_id", claim.path("stage_id").stringValue()); request.put("request_id", UUID.randomUUID().toString());
            request.put("input_hash", claim.path("input_hash").stringValue()); request.put("operation_key", "export-must-not-call-model");
            request.put("model_id", kind.equals("TEXT") ? "qwen-plus" : "paraformer-v2"); request.put("kind", kind);
            error(worker("POST", "/internal/model-calls/reserve", request), 409, "MODEL_KIND_CONFLICT");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, fixture.jobId()));
        JsonNode budget = get("/api/jobs/" + fixture.jobId()).path("budget");
        assertEquals(0, budget.path("text_calls_reserved").intValue());
        assertEquals(0, budget.path("asr_calls_reserved").intValue());
    }

    @Test
    void concurrentClipCompletionsAggregateOnceAndRemainVisibleToDefaultWorkerDiscovery() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode batch = create(fixture, 0, List.of(FIRST, SECOND));
        JsonNode ready = ok(worker("GET", "/internal/stages/ready", null));
        boolean discovered = false;
        for (JsonNode stage : ready.path("items")) {
            if (stage.path("id").stringValue().equals(batch.path("clips").get(0).path("stage_id").stringValue())) { discovered = true; }
        }
        assertTrue(discovered, "Default discovery must include ready export stages");
        JsonNode one = claim(batch.path("clips").get(0));
        JsonNode two = claim(batch.path("clips").get(1));
        Map<String, Object> first = completion(one), second = completion(two);
        CountDownLatch gate = new CountDownLatch(1);
        var a = CompletableFuture.supplyAsync(() -> afterWorker(gate, action(one, "complete"), first));
        var b = CompletableFuture.supplyAsync(() -> afterWorker(gate, action(two, "complete"), second));
        gate.countDown(); ok(a.get(20, TimeUnit.SECONDS)); ok(b.get(20, TimeUnit.SECONDS));
        assertEquals("SUCCEEDED", get("/api/exports/" + batch.path("id").stringValue()).path("status").stringValue());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM job_stage WHERE owner_type='EXPORT' AND owner_id=? AND status='SUCCEEDED'", Integer.class, batch.path("id").stringValue()));
        assertEquals("REVIEW_READY", get("/api/jobs/" + fixture.jobId()).path("status").stringValue());
    }

    @Test
    void successfulCompletionIsImmutableAndDownloadStreamsRangeHeadAndRejectsSameSizeTampering() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode batch = create(fixture, 0, List.of(FIRST));
        JsonNode claim = claim(batch.path("clips").get(0));
        Map<String, Object> complete = completion(claim);
        assertEquals("ACCEPTED", ok(worker("POST", action(claim, "complete"), complete)).path("outcome").stringValue());
        long version = get("/api/exports/" + batch.path("id").stringValue()).path("state_version").longValue();
        assertEquals("ALREADY_APPLIED", ok(worker("POST", action(claim, "complete"), complete)).path("outcome").stringValue());
        assertEquals(version, get("/api/exports/" + batch.path("id").stringValue()).path("state_version").longValue());
        error(worker("POST", action(claim, "complete"), with(claim, "manifest_key", complete.get("manifest_key"), "manifest_hash", "b".repeat(64))), 409, "RESULT_CONFLICT");
        String url = get("/api/exports/" + batch.path("id").stringValue()).path("clips").get(0).path("content_url").stringValue();
        var head = send("HEAD", url, null);
        assertEquals(200, head.statusCode()); assertEquals(0, head.body().length);
        assertEquals("128", head.headers().firstValue("content-length").orElseThrow());
        assertEquals("bytes", head.headers().firstValue("accept-ranges").orElseThrow());
        assertEquals("video/mp4", head.headers().firstValue("content-type").orElseThrow());
        assertEquals("\"" + sha(CLIP_BYTES) + "\"", head.headers().firstValue("etag").orElseThrow());
        var first = range(url, "bytes=0-7");
        assertEquals(206, first.statusCode()); assertArrayEquals(java.util.Arrays.copyOfRange(CLIP_BYTES, 0, 8), first.body());
        var later = range(url, "bytes=96-111");
        assertEquals(206, later.statusCode()); assertArrayEquals(java.util.Arrays.copyOfRange(CLIP_BYTES, 96, 112), later.body());
        var multi = range(url, "bytes=0-3,100-103");
        assertEquals(206, multi.statusCode()); assertTrue(multi.headers().firstValue("content-type").orElseThrow().startsWith("multipart/byteranges"));
        var invalid = range(url, "bytes=128-");
        error(invalid, 416, "RANGE_NOT_SATISFIABLE"); assertEquals("bytes */128", invalid.headers().firstValue("content-range").orElseThrow());
        var downloaded = send("GET", url + "?download=true", null);
        assertArrayEquals(CLIP_BYTES, downloaded.body());
        String disposition = downloaded.headers().firstValue("content-disposition").orElseThrow();
        assertTrue(disposition.matches("attachment; filename=\"[0-9a-f-]{36}\\.mp4\""));
        assertFalse(disposition.contains("evil"));
        byte[] corrupt = CLIP_BYTES.clone(); corrupt[64] ^= 1;
        write(prefix(claim) + "clip.mp4", corrupt);
        error(send("GET", url, null), 409, "ARTIFACT_HASH_MISMATCH");
        error(send("HEAD", url, null), 409, null);
    }

    @Test
    void frozenManifestIdentityAndReportedMetadataAreCheckedBeforeActivation() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode batch = create(fixture, 0, List.of(FIRST, SECOND));
        JsonNode claim = claim(batch.path("clips").get(0));
        Map<String, Object> complete = completion(claim);
        Path manifestPath = root.resolve((String) complete.get("manifest_key"));
        byte[] original = Files.readAllBytes(manifestPath);
        for (String field : List.of("export_id", "clip_id", "snapshot_hash", "stage_input_hash", "segment_id", "encoding_profile")) {
            ObjectNode modified = (ObjectNode) JSON.readTree(original); modified.put(field, "wrong-frozen-value");
            Files.write(manifestPath, JSON.writeValueAsBytes(modified));
            error(worker("POST", action(claim, "complete"), with(claim, "manifest_key", complete.get("manifest_key"), "manifest_hash", sha(Files.readAllBytes(manifestPath)))), 409, "ARTIFACT_IDENTITY_MISMATCH");
        }
        Files.write(manifestPath, original);
        Path infoPath = root.resolve(prefix(claim) + "media-info.json");
        byte[] originalInfo = Files.readAllBytes(infoPath);
        for (String field : List.of("duration_ms", "audio_codec")) {
            ObjectNode info = (ObjectNode) JSON.readTree(originalInfo);
            if (field.equals("duration_ms")) { info.put(field, 999999); } else { info.put(field, "mp3"); }
            Files.write(infoPath, JSON.writeValueAsBytes(info));
            ObjectNode manifest = (ObjectNode) JSON.readTree(original);
            ((ObjectNode) manifest.path("artifacts").get(1)).put("sha256", sha(Files.readAllBytes(infoPath)));
            Files.write(manifestPath, JSON.writeValueAsBytes(manifest));
            error(worker("POST", action(claim, "complete"), with(claim, "manifest_key", complete.get("manifest_key"), "manifest_hash", sha(Files.readAllBytes(manifestPath)))), 400, "INVALID_MANIFEST");
        }
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?", String.class, claim.path("stage_id").stringValue()));
        assertEquals("REVIEW_READY", get("/api/jobs/" + fixture.jobId()).path("status").stringValue());
        error(send("POST", "/api/exports/" + batch.path("id").stringValue() + "/clips/" + UUID.randomUUID() + "/retry", null), 404, "EXPORT_CLIP_NOT_FOUND");
    }

    @Test
    void analysisCancellationAndRetryNeverMutateExportOwnedRows() throws Exception {
        Fixture fixture = fixture(true);
        JsonNode batch = create(fixture, 0, List.of(FIRST));
        String exportStage = batch.path("clips").get(0).path("stage_id").stringValue();
        // Fault injection is restricted to this test's UUID rows, exercising
        // the ownership boundary even for otherwise unreachable mixed states.
        jdbc.update("UPDATE analysis_job SET status='RUNNING' WHERE id=?", fixture.jobId());
        ok(send("POST", "/api/jobs/" + fixture.jobId() + "/cancel", null));
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?", String.class, exportStage));
        jdbc.update("UPDATE analysis_job SET status='FAILED' WHERE id=?", fixture.jobId());
        jdbc.update("UPDATE job_stage SET status='FAILED',attempt=1 WHERE job_id=? AND owner_type='ANALYSIS'", fixture.jobId());
        jdbc.update("UPDATE job_stage SET status='FAILED' WHERE id=?", exportStage);
        ok(send("POST", "/api/jobs/" + fixture.jobId() + "/retry", null));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?", String.class, exportStage));
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS'", String.class, fixture.jobId()));
    }

    private Fixture fixture(boolean confirmed) throws Exception {
        byte[] header = ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var video = context.getBean(VideoService.class).upload(new MockMultipartFile("file", "synthetic.mp4", "video/mp4", header));
        videos.add(video.id());
        String jobId = UUID.randomUUID().toString(), stageId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        ObjectNode raw = JSON.createObjectNode().put("schema_version", "2.0").put("data_kind", "model_result").put("review_status", "DRAFT")
                .put("job_id", jobId).put("video_id", video.id()).put("analysis_generation", 1).put("duration_ms", 20000);
        raw.putArray("products").addObject().put("product_id", "p1").put("name", "面膜\";evil.mp4");
        raw.putArray("utterances").addObject().put("utterance_id", "u1").put("start_ms", 0).put("end_ms", 20000).put("text", "合成协议证据").put("source_chunk_id", "c0");
        var segments = raw.putArray("segments");
        for (String id : List.of(FIRST, SECOND)) {
            int start = id.equals(FIRST) ? 500 : 5000, end = id.equals(FIRST) ? 2500 : 8000;
            ObjectNode segment = segments.addObject().put("segment_id", id).put("start_ms", start).put("end_ms", end).put("content_kind", "product_talk").put("visual_status", "not_checked");
            segment.putObject("identity").put("status", "identified").put("product_id", "p1");
            segment.putArray("audio_evidence_utterance_ids").add("u1"); segment.putArray("review_reasons");
        }
        String prefix = "jobs/" + jobId + "/stages/" + stageId + "/attempts/1/" + token + "/";
        byte[] rawBytes = JSON.writeValueAsBytes(raw); write(prefix + "analysis-result.json", rawBytes);
        ObjectNode manifest = JSON.createObjectNode().put("schema_version", 1).put("job_id", jobId).put("video_id", video.id()).put("analysis_generation", 1)
                .put("stage_id", stageId).put("stage_type", "TIMELINE").put("attempt", 1).put("run_token", token).put("input_sha256", video.contentHash()).put("data_kind", "model_result").put("review_status", "DRAFT");
        manifest.putArray("artifacts").addObject().put("kind", "analysis_result").put("key", prefix + "analysis-result.json").put("sha256", sha(rawBytes));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest); write(prefix + "manifest.json", manifestBytes);
        jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,'{}','REVIEW_READY',12,1)",
                jobId, video.id(), UUID.randomUUID().toString(), sha(rawBytes), sha(rawBytes));
        jobs.add(jobId);
        jdbc.update("INSERT INTO job_stage (id,job_id,owner_id,stage_type,status,attempt,run_token,input_hash,manifest_key,manifest_hash) VALUES (?,?,?,'TIMELINE','SUCCEEDED',1,?,?,?,?)",
                stageId, jobId, jobId, token, video.contentHash(), prefix + "manifest.json", sha(manifestBytes));
        Fixture fixture = new Fixture(jobId, video.id(), root.resolve(prefix + "analysis-result.json"), sha(rawBytes));
        get(review(fixture));
        if (confirmed) { ok(send("POST", review(fixture) + "/confirm", Map.of("revision", 0))); }
        return fixture;
    }

    private static Map<String, Object> createBody(Fixture fixture, long revision, List<String> segments) {
        return Map.of("job_id", fixture.jobId(), "approved_revision", revision, "segment_ids", segments, "profile", "h264-aac-v1");
    }
    private JsonNode create(Fixture fixture, long revision, List<String> segments) throws Exception { return created(send("POST", "/api/exports", createBody(fixture, revision, segments))); }
    private JsonNode claim(JsonNode clip) throws Exception {
        return ok(worker("POST", "/internal/stages/claim", Map.of("stage_id", clip.path("stage_id").stringValue(), "worker_id", "synthetic-export-worker", "claim_request_id", UUID.randomUUID().toString())));
    }
    private static String review(Fixture fixture) { return "/api/jobs/" + fixture.jobId() + "/review"; }
    private static String action(JsonNode claim, String action) { return "/internal/stages/" + claim.path("stage_id").stringValue() + "/" + action; }
    private static String prefix(JsonNode claim) { return "jobs/" + claim.path("job_id").stringValue() + "/stages/" + claim.path("stage_id").stringValue() + "/attempts/" + claim.path("attempt").intValue() + "/" + claim.path("run_token").stringValue() + "/"; }
    private static Map<String, Object> credentials(JsonNode claim) { return Map.of("attempt", claim.path("attempt").intValue(), "run_token", claim.path("run_token").stringValue()); }
    private static Map<String, Object> with(JsonNode claim, String a, Object av, String b, Object bv) {
        var request = new java.util.LinkedHashMap<String, Object>(credentials(claim)); request.put(a, av); request.put(b, bv); return request;
    }
    private static Map<String, Object> completion(JsonNode claim) throws Exception {
        String prefix = prefix(claim); write(prefix + "clip.mp4", CLIP_BYTES);
        JsonNode export = claim.path("config").path("export");
        byte[] info = JSON.writeValueAsBytes(Map.of("duration_ms", export.path("end_ms").longValue() - export.path("start_ms").longValue(), "video_codec", "h264", "audio_codec", "aac", "frame_rate", 25));
        write(prefix + "media-info.json", info);
        ObjectNode manifest = JSON.createObjectNode().put("schema_version", 1).put("job_id", claim.path("job_id").stringValue()).put("video_id", claim.path("video_id").stringValue())
                .put("analysis_generation", claim.path("generation").intValue()).put("stage_id", claim.path("stage_id").stringValue()).put("stage_type", "EXPORT_CLIP")
                .put("attempt", claim.path("attempt").intValue()).put("run_token", claim.path("run_token").stringValue()).put("input_sha256", claim.path("source_hash").stringValue())
                .put("data_kind", "reviewed_export").put("review_status", "CONFIRMED").put("stage_input_hash", claim.path("input_hash").stringValue());
        manifest.setAll((ObjectNode) export);
        var artifacts = manifest.putArray("artifacts");
        artifacts.addObject().put("kind", "clip").put("key", prefix + "clip.mp4").put("sha256", sha(CLIP_BYTES));
        artifacts.addObject().put("kind", "media_info").put("key", prefix + "media-info.json").put("sha256", sha(info));
        byte[] bytes = JSON.writeValueAsBytes(manifest); write(prefix + "manifest.json", bytes);
        return with(claim, "manifest_key", prefix + "manifest.json", "manifest_hash", sha(bytes));
    }
    private static ObjectNode edit(JsonNode snapshot) {
        ObjectNode edit = JSON.createObjectNode().put("base_revision", snapshot.path("revision").longValue());
        var products = edit.putArray("products");
        for (JsonNode product : snapshot.path("products")) { products.addObject().put("id", product.path("id").stringValue()).put("name", product.path("name").stringValue()); }
        var segments = edit.putArray("segments");
        for (JsonNode segment : snapshot.path("segments")) { segments.addObject().put("id", segment.path("id").stringValue()).put("start_ms", segment.path("start_ms").longValue()).put("end_ms", segment.path("end_ms").longValue()).put("product_id", segment.path("product_id").stringValue()).put("enabled", segment.path("enabled").booleanValue()); }
        return edit;
    }
    private static void write(String key, byte[] bytes) throws Exception { Path path = root.resolve(key); Files.createDirectories(path.getParent()); Files.write(path, bytes); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void assertAttachment(HttpResponse<byte[]> response, String filename) throws Exception {
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("attachment; filename=\"" + filename + "\"", response.headers().firstValue("Content-Disposition").orElseThrow());
        assertEquals(response.body().length, response.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertEquals("\"" + sha(response.body()) + "\"", response.headers().firstValue("ETag").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
    }
    private HttpResponse<byte[]> after(CountDownLatch gate, String method, String path, Object body) {
        try { gate.await(); return send(method, path, body); } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private HttpResponse<byte[]> afterWorker(CountDownLatch gate, String path, Object body) {
        try { gate.await(); return worker("POST", path, body); } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private HttpResponse<byte[]> send(String method, String path, Object body) throws Exception { return request(method, path, body, null, null); }
    private HttpResponse<byte[]> worker(String method, String path, Object body) throws Exception { return request(method, path, body, KEY, null); }
    private HttpResponse<byte[]> range(String path, String range) throws Exception { return request("GET", path, null, null, range); }
    private HttpResponse<byte[]> request(String method, String path, Object body, String workerKey, String range) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20));
        if (workerKey != null) { builder.header("X-Worker-Key", workerKey); }
        if (range != null) { builder.header("Range", range); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private JsonNode get(String path) throws Exception { return ok(send("GET", path, null)); }
    private static JsonNode created(HttpResponse<byte[]> response) { assertEquals(201, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8)); return JSON.readTree(response.body()); }
    private static JsonNode ok(HttpResponse<byte[]> response) { assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8)); return JSON.readTree(response.body()); }
    private static void error(HttpResponse<byte[]> response, int status, String code) {
        assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        if (code != null) { assertEquals(code, JSON.readTree(response.body()).path("error_code").stringValue()); }
    }
}
