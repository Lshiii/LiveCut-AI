package io.livecut.events;

import static org.junit.jupiter.api.Assertions.*;

import io.livecut.LivecutBackendApplication;
import io.livecut.video.VideoService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real SSE sockets and MySQL. Synthetic review/clip files do not represent model or media quality. */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskEventsIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "events-test-worker-only";
    private static final String HASH = "a".repeat(64);
    private static final AtomicBoolean SOCKET_WRITE_IN_TRANSACTION = new AtomicBoolean();
    @TempDir static Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Set<String> jobs = new LinkedHashSet<>();
    private final Set<String> videos = new LinkedHashSet<>();
    private final List<SseConnection> connections = new ArrayList<>();
    private ConfigurableApplicationContext context;
    private URI base;

    @BeforeAll void start() {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"),
                "SSE tests require the dedicated livecut_test database");
        startContext();
    }

    private void startContext() {
        startContext(10000);
    }

    private void startContext(long timeoutMs) {
        context = new SpringApplicationBuilder(LivecutBackendApplication.class, WriteBoundaryFixture.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--livecut.storage.root=" + root,
                "--livecut.worker.api-key=" + KEY, "--livecut.jobs.scan-enabled=false", "--livecut.workers.require-registration=false",
                "--livecut.uploads.scan-enabled=false", "--livecut.jobs.lease-seconds=30",
                "--livecut.events.poll-ms=100", "--livecut.events.heartbeat-ms=200",
                "--livecut.events.timeout-ms=" + timeoutMs, "--livecut.events.max-connections=4",
                "--livecut.events.max-per-resource=2", "--livecut.events.sender-threads=2",
                "--livecut.events.sender-queue=4", "--livecut.events.scan-enabled=true",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @AfterEach void cleanupOwnRows() throws Exception {
        for (SseConnection connection : connections) connection.close();
        connections.clear();
        try {
            if (context != null && context.isActive()) await(() -> events().activeConnections() == 0, 5000);
        } finally {
            // Only this suite's UUID parents are deleted; FK cascades remove its
            // stages, model reservations, reviews, exports and clips.
            for (String id : jobs) jdbc().update("DELETE FROM analysis_job WHERE id=?", id);
            for (String id : videos) jdbc().update("DELETE FROM video_asset WHERE id=?", id);
            jobs.clear(); videos.clear();
        }
        assertFalse(SOCKET_WRITE_IN_TRANSACTION.get(), "SSE send/flush must not run inside a database transaction");
    }

    @AfterAll void stop() { if (context != null) context.close(); }

    @Test void nonexistentAndMalformedResourcesReturnJsonBeforeStartingAStream() throws Exception {
        for (String kind : List.of("jobs", "exports")) {
            String code = kind.equals("jobs") ? "JOB" : "EXPORT";
            jsonError(send("GET", "/api/" + kind + "/" + UUID.randomUUID() + "/events", null, null, null), 404, code + "_NOT_FOUND");
            jsonError(send("GET", "/api/" + kind + "/bad-id/events", null, null, null), 400, "INVALID_" + code + "_ID");
        }
        assertEquals(0, events().activeConnections());
        assertEquals(0, events().resourceCount());
    }

    @Test void firstEventIsTheFullPublicSnapshotAndIdleTrafficIsOnlyHeartbeatComments() throws Exception {
        JsonNode job = newJob();
        String id = job.path("id").asText();
        try (SseConnection stream = open("jobs", id, null)) {
            assertStreamHeaders(stream.response);
            assertSnapshot(stream.snapshot(), "job", id, job);
            assertEquals(2000, stream.retryMs);
            stream.heartbeat();
            stream.noSnapshotFor(350);
            assertEquals(1, events().activeConnections());
            assertEquals(1, events().resourceCount());
        }
    }

    @Test void analysisClaimAndCancelPushNewVersionsAndCloseAfterTheTerminalSnapshot() throws Exception {
        JsonNode job = newJob();
        String id = job.path("id").asText();
        try (SseConnection stream = open("jobs", id, null)) {
            assertSnapshot(stream.snapshot(), "job", id, job);
            JsonNode claimed = claim(stage(job, "MEDIA").path("id").asText());
            JsonNode running = get("/api/jobs/" + id);
            assertEquals("RUNNING", running.path("status").asText());
            assertSnapshot(stream.snapshot(), "job", id, running);
            assertFalse(running.toString().contains(claimed.path("run_token").asText()));
            JsonNode cancelled = ok(send("POST", "/api/jobs/" + id + "/cancel", null, null, null));
            assertEquals("CANCELLED", cancelled.path("status").asText());
            assertSnapshot(stream.snapshot(), "job", id, cancelled);
            stream.end();
        }
        await(() -> events().activeConnections() == 0 && events().resourceCount() == 0, 3000);
    }

    @Test void aFencedFailurePushesItsPersistedDiagnosticAndReconnectRehydratesTheSameTerminalSnapshot() throws Exception {
        RunningStage fixture = runningTextStage();
        JsonNode initial = get("/api/jobs/" + fixture.jobId());
        JsonNode current;
        try (SseConnection stream = open("jobs", fixture.jobId(), null)) {
            assertSnapshot(stream.snapshot(), "job", fixture.jobId(), initial);
            Map<String, Object> diagnostic = Map.of("reason_code", "ASSIGNMENT_KEYS_MISMATCH", "window_index", 8,
                    "window_count", 10, "extra_context_id_count", 3, "format_repair", "FAILED_VALIDATION");
            ok(worker("POST", "/internal/stages/" + fixture.stageId() + "/fail", Map.of("attempt", 1,
                    "run_token", fixture.token(), "error_code", "INVALID_MODEL_OUTPUT", "retryable", false, "diagnostic", diagnostic)));
            current = get("/api/jobs/" + fixture.jobId());
            JsonNode summary = stage(current, "SEGMENT").path("diagnostic");
            assertEquals(8, summary.path("window_index").asInt());
            assertEquals("CHECK_FORMAT_THEN_RETRY", summary.path("suggestion_code").asText());
            assertEquals(summary, JSON.readTree(jdbc().queryForObject("SELECT diagnostic_json FROM job_stage WHERE id=?", String.class, fixture.stageId())));
            assertSnapshot(stream.snapshot(), "job", fixture.jobId(), current);
            stream.end();
        }
        String last = "job:" + fixture.jobId() + ":" + current.path("state_version").asLong();
        try (SseConnection replay = open("jobs", fixture.jobId(), last)) {
            assertSnapshot(replay.snapshot(), "job", fixture.jobId(), current);
            replay.end();
        }
        assertFalse(current.toString().contains(fixture.token()));
    }

    @Test void realBudgetReservationPushesAFullSnapshotWithoutCallingAModel() throws Exception {
        RunningStage fixture = runningTextStage();
        JsonNode initial = get("/api/jobs/" + fixture.jobId());
        try (SseConnection stream = open("jobs", fixture.jobId(), null)) {
            assertSnapshot(stream.snapshot(), "job", fixture.jobId(), initial);
            Map<String, Object> reservation = Map.of("stage_id", fixture.stageId(), "attempt", 1,
                    "run_token", fixture.token(), "request_id", "sse-" + UUID.randomUUID(), "input_hash", HASH,
                    "operation_key", "sse-budget-fixture", "model_id", "qwen-plus", "kind", "TEXT");
            assertEquals("RESERVED", ok(worker("POST", "/internal/model-calls/reserve", reservation)).path("outcome").asText());
            JsonNode changed = get("/api/jobs/" + fixture.jobId());
            assertEquals("RUNNING", changed.path("status").asText());
            assertEquals(1, changed.path("budget").path("text_calls_reserved").asInt());
            assertTrue(changed.path("state_version").asLong() > initial.path("state_version").asLong());
            assertSnapshot(stream.snapshot(), "job", fixture.jobId(), changed);
            assertEquals("EXISTING", ok(worker("POST", "/internal/model-calls/reserve", reservation)).path("outcome").asText());
            stream.noSnapshotFor(350);
        }
    }

    @Test void anyLastEventIdStillReceivesCurrentStateAndCannotSelectAnotherResource() throws Exception {
        JsonNode job = newJob();
        JsonNode foreign = newJob();
        String id = job.path("id").asText();
        String version = job.path("state_version").asText();
        for (String last : List.of("job:" + id + ":0", "job:" + id + ":" + version,
                "job:" + id + ":999999999999999999999999", "untrusted:not-a-version",
                "job:" + foreign.path("id").asText() + ":" + version, "export:" + id + ":" + version)) {
            try (SseConnection stream = open("jobs", id, last)) {
                assertSnapshot(stream.snapshot(), "job", id, get("/api/jobs/" + id));
            }
            await(() -> events().activeConnections() == 0, 3000);
        }
    }

    @Test void alreadyTerminalAnalysisStatesSendTheirFinalSnapshotBeforeEof() throws Exception {
        for (String status : List.of("REVIEW_READY", "FAILED", "CANCELLED")) {
            JsonNode job = newJob();
            String id = job.path("id").asText();
            // This suite tests event delivery; owned DB fixtures represent a
            // persisted terminal transition, not a model execution result.
            jdbc().update("UPDATE analysis_job SET status=?,state_version=state_version+1 WHERE id=?", status, id);
            JsonNode terminal = get("/api/jobs/" + id);
            try (SseConnection stream = open("jobs", id, "job:" + id + ":999")) {
                assertSnapshot(stream.snapshot(), "job", id, terminal);
                stream.end();
            }
        }
    }

    @Test void reconnectAndApplicationRestartRebuildFromDatabaseRatherThanAMemoryEventLog() throws Exception {
        JsonNode job = newJob();
        String id = job.path("id").asText();
        String last;
        try (SseConnection stream = open("jobs", id, null)) { last = stream.snapshot().id(); }
        await(() -> events().activeConnections() == 0, 3000);
        claim(stage(job, "MEDIA").path("id").asText());
        JsonNode current = get("/api/jobs/" + id);
        try (SseConnection stream = open("jobs", id, last)) { assertSnapshot(stream.snapshot(), "job", id, current); }
        await(() -> events().activeConnections() == 0, 3000);
        JsonNode batch = newExport(1);
        String exportId = batch.path("id").asText();
        String exportLast;
        try (SseConnection stream = open("exports", exportId, null)) { exportLast = stream.snapshot().id(); }
        await(() -> events().activeConnections() == 0, 3000);
        context.close(); startContext();
        assertEquals(current, get("/api/jobs/" + id));
        try (SseConnection stream = open("jobs", id, last)) { assertSnapshot(stream.snapshot(), "job", id, current); }
        assertEquals(batch, get("/api/exports/" + exportId));
        try (SseConnection stream = open("exports", exportId, exportLast)) { assertSnapshot(stream.snapshot(), "export", exportId, batch); }
    }

    @Test void perResourceLimitDoesNotBlockAnotherOwnerAndClientCloseReleasesItsSlot() throws Exception {
        String first = newJob().path("id").asText();
        String other = newJob().path("id").asText();
        SseConnection one = open("jobs", first, null); one.snapshot();
        SseConnection two = open("jobs", first, null); two.snapshot();
        assertEquals(429, refusedStream("jobs", first).statusCode());
        try (SseConnection independent = open("jobs", other, null)) {
            assertEquals(other, independent.snapshot().data().path("id").asText());
            one.close(); one.close();
            await(() -> events().activeConnections() == 2, 4000);
            try (SseConnection replacement = open("jobs", first, null)) {
                assertEquals(first, replacement.snapshot().data().path("id").asText());
            }
        }
        two.close();
    }

    @Test void globalLimitReturnsJson429AndAClosedConnectionMakesCapacityReusable() throws Exception {
        String first = newJob().path("id").asText();
        String second = newJob().path("id").asText();
        String third = newJob().path("id").asText();
        List<SseConnection> full = new ArrayList<>();
        for (String id : List.of(first, first, second, second)) {
            SseConnection stream = open("jobs", id, null); stream.snapshot(); full.add(stream);
        }
        var exhausted = refusedStream("jobs", third);
        assertEquals(429, exhausted.statusCode());
        assertTrue(exhausted.headers().firstValue("content-type").orElse("").startsWith("application/json"));
        assertFalse(JSON.readTree(exhausted.body()).path("error_code").asText().isBlank());
        assertEquals(third, get("/api/jobs/" + third).path("id").asText(), "Normal reads must remain available");
        full.getFirst().close();
        await(() -> events().activeConnections() == 3, 4000);
        try (SseConnection reused = open("jobs", third, null)) { assertEquals(third, reused.snapshot().data().path("id").asText()); }
    }

    @Test void serverTimeoutClosesAnIdleSocketAndReleasesItsConnectionQuota() throws Exception {
        String id = newJob().path("id").asText();
        context.close(); startContext(1000);
        try {
            try (SseConnection stream = open("jobs", id, null)) {
                assertEquals(id, stream.snapshot().data().path("id").asText());
                stream.end();
            }
            await(() -> events().activeConnections() == 0 && events().resourceCount() == 0, 3000);
            try (SseConnection replacement = open("jobs", id, null)) {
                assertEquals(id, replacement.snapshot().data().path("id").asText());
            }
            await(() -> events().activeConnections() == 0, 3000);
        } finally { context.close(); startContext(); }
    }

    @Test void exportClaimsAndMixedResultsPushTheirOwnPublicSnapshotAndCloseOnPartialFailure() throws Exception {
        JsonNode batch = newExport(2);
        String id = batch.path("id").asText();
        try (SseConnection stream = open("exports", id, "job:" + batch.path("job_id").asText() + ":99")) {
            assertStreamHeaders(stream.response);
            assertSnapshot(stream.snapshot(), "export", id, batch);
            JsonNode first = claim(batch.path("clips").get(0).path("stage_id").asText());
            JsonNode running = get("/api/exports/" + id);
            assertEquals("RUNNING", running.path("status").asText());
            assertSnapshot(stream.snapshot(), "export", id, running);
            ok(worker("POST", action(first, "complete"), exportCompletion(first)));
            // Await this intermediate persisted version before changing another
            // clip. Delivery coalesces rapid changes instead of promising a log.
            JsonNode partiallyDone = get("/api/exports/" + id);
            assertSnapshot(stream.snapshot(), "export", id, partiallyDone);
            JsonNode second = claim(batch.path("clips").get(1).path("stage_id").asText());
            JsonNode secondRunning = get("/api/exports/" + id);
            assertSnapshot(stream.snapshot(), "export", id, secondRunning);
            ok(worker("POST", action(second, "fail"), credentials(second, "error_code", "SYNTHETIC_CLIP_FAILURE", "retryable", false)));
            JsonNode terminal = get("/api/exports/" + id);
            assertEquals("PARTIAL_FAILED", terminal.path("status").asText());
            assertSnapshot(stream.snapshot(), "export", id, terminal);
            stream.end();
        }
        assertEquals("REVIEW_READY", get("/api/jobs/" + batch.path("job_id").asText()).path("status").asText());
    }

    @Test void allTerminalExportStatusesSendAFinalSnapshotAndPersistAcrossReconnect() throws Exception {
        for (String status : List.of("SUCCEEDED", "FAILED", "PARTIAL_FAILED", "CANCELLED")) {
            JsonNode batch = newExport(1);
            String id = batch.path("id").asText();
            // Synthetic terminal rows cover stream lifecycle for every contract
            // status; actual mixed-result protocol is exercised separately.
            jdbc().update("UPDATE export_job SET status=?,state_version=state_version+1 WHERE id=?", status, id);
            JsonNode terminal = get("/api/exports/" + id);
            for (String last : List.of("export:" + id + ":0", "export:" + id + ":99999")) {
                try (SseConnection stream = open("exports", id, last)) {
                    assertSnapshot(stream.snapshot(), "export", id, terminal);
                    stream.end();
                }
            }
        }
    }

    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }
    private TaskEventsService events() { return context.getBean(TaskEventsService.class); }
    private JsonNode newJob() throws Exception {
        String video = newVideo();
        var result = send("POST", "/api/jobs", Map.of("video_id", video, "config", Map.of("visual_provider", "ocr")),
                "events-" + UUID.randomUUID(), null);
        assertEquals(201, result.statusCode(), text(result));
        JsonNode job = JSON.readTree(result.body()); jobs.add(job.path("id").asText()); return job;
    }
    private String newVideo() {
        byte[] header = ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII))
                .putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var video = context.getBean(VideoService.class).upload(new MockMultipartFile("file", "synthetic-sse.mp4", "video/mp4", header));
        videos.add(video.id()); return video.id();
    }
    private record RunningStage(String jobId, String stageId, String token) {}
    private RunningStage runningTextStage() throws Exception {
        String video = newVideo(), job = UUID.randomUUID().toString(), stage = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc().update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,?,'RUNNING',3,1)",
                job, video, UUID.randomUUID().toString(), HASH, HASH, "{\"text_model\":\"qwen-plus\",\"asr_model\":\"paraformer-v2\",\"visual_provider\":\"ocr\"}");
        jobs.add(job);
        jdbc().update("INSERT INTO job_stage (id,job_id,owner_id,stage_type,status,attempt,run_token,worker_id,lease_until,hard_deadline_at,input_hash) VALUES (?,?,?,'SEGMENT','RUNNING',1,?,'sse-fixture',DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 300 SECOND),?)",
                stage, job, job, token, HASH);
        return new RunningStage(job, stage, token);
    }
    private JsonNode newExport(int count) throws Exception {
        String video = newVideo(), job = UUID.randomUUID().toString(), stage = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        String sourceHash = jdbc().queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, video);
        ObjectNode raw = JSON.createObjectNode().put("schema_version", "2.0").put("data_kind", "model_result").put("review_status", "DRAFT")
                .put("job_id", job).put("video_id", video).put("analysis_generation", 1).put("duration_ms", 20000);
        raw.putArray("products").addObject().put("product_id", "p1").put("name", "合成商品");
        raw.putArray("utterances").addObject().put("utterance_id", "u1").put("start_ms", 0).put("end_ms", 20000).put("text", "合成事件流证据").put("source_chunk_id", "c0");
        var segments = raw.putArray("segments");
        List<String> selection = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String segmentId = "segment-" + index; selection.add(segmentId);
            ObjectNode segment = segments.addObject().put("segment_id", segmentId).put("start_ms", index * 5000 + 500).put("end_ms", index * 5000 + 2500)
                    .put("content_kind", "product_talk").put("visual_status", "not_checked");
            segment.putObject("identity").put("status", "identified").put("product_id", "p1");
            segment.putArray("audio_evidence_utterance_ids").add("u1"); segment.putArray("review_reasons");
        }
        String prefix = "jobs/" + job + "/stages/" + stage + "/attempts/1/" + token + "/";
        byte[] rawBytes = JSON.writeValueAsBytes(raw); write(prefix + "analysis-result.json", rawBytes);
        ObjectNode manifest = JSON.createObjectNode().put("schema_version", 1).put("job_id", job).put("video_id", video).put("analysis_generation", 1)
                .put("stage_id", stage).put("stage_type", "TIMELINE").put("attempt", 1).put("run_token", token).put("input_sha256", sourceHash)
                .put("data_kind", "model_result").put("review_status", "DRAFT");
        manifest.putArray("artifacts").addObject().put("kind", "analysis_result").put("key", prefix + "analysis-result.json").put("sha256", sha(rawBytes));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest); write(prefix + "manifest.json", manifestBytes);
        jdbc().update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,'{}','REVIEW_READY',12,1)",
                job, video, UUID.randomUUID().toString(), sha(rawBytes), sha(rawBytes));
        jobs.add(job);
        jdbc().update("INSERT INTO job_stage (id,job_id,owner_id,stage_type,status,attempt,run_token,input_hash,manifest_key,manifest_hash) VALUES (?,?,?,'TIMELINE','SUCCEEDED',1,?,?,?,?)",
                stage, job, job, token, sourceHash, prefix + "manifest.json", sha(manifestBytes));
        get("/api/jobs/" + job + "/review");
        ok(send("POST", "/api/jobs/" + job + "/review/confirm", Map.of("revision", 0), null, null));
        var created = send("POST", "/api/exports", Map.of("job_id", job, "approved_revision", 0, "segment_ids", selection, "profile", "h264-aac-v1"), null, null);
        assertEquals(201, created.statusCode(), text(created)); return JSON.readTree(created.body());
    }
    private Map<String, Object> exportCompletion(JsonNode claim) throws Exception {
        String prefix = "jobs/" + claim.path("job_id").asText() + "/stages/" + claim.path("stage_id").asText() + "/attempts/" + claim.path("attempt").asInt() + "/" + claim.path("run_token").asText() + "/";
        byte[] clip = new byte[128]; write(prefix + "clip.mp4", clip);
        JsonNode export = claim.path("config").path("export");
        byte[] media = JSON.writeValueAsBytes(Map.of("duration_ms", export.path("end_ms").asLong() - export.path("start_ms").asLong(), "video_codec", "h264", "audio_codec", "aac", "frame_rate", 25));
        write(prefix + "media-info.json", media);
        ObjectNode manifest = JSON.createObjectNode().put("schema_version", 1).put("job_id", claim.path("job_id").asText()).put("video_id", claim.path("video_id").asText())
                .put("analysis_generation", claim.path("generation").asInt()).put("stage_id", claim.path("stage_id").asText()).put("stage_type", "EXPORT_CLIP")
                .put("attempt", claim.path("attempt").asInt()).put("run_token", claim.path("run_token").asText()).put("input_sha256", claim.path("source_hash").asText())
                .put("data_kind", "reviewed_export").put("review_status", "CONFIRMED").put("stage_input_hash", claim.path("input_hash").asText());
        manifest.setAll((ObjectNode) export);
        var artifacts = manifest.putArray("artifacts");
        artifacts.addObject().put("kind", "clip").put("key", prefix + "clip.mp4").put("sha256", sha(clip));
        artifacts.addObject().put("kind", "media_info").put("key", prefix + "media-info.json").put("sha256", sha(media));
        byte[] bytes = JSON.writeValueAsBytes(manifest); write(prefix + "manifest.json", bytes);
        return credentials(claim, "manifest_key", prefix + "manifest.json", "manifest_hash", sha(bytes));
    }
    private JsonNode claim(String stage) throws Exception {
        return ok(worker("POST", "/internal/stages/claim", Map.of("stage_id", stage, "worker_id", "sse-fixture-worker", "claim_request_id", UUID.randomUUID().toString())));
    }
    private static String action(JsonNode claim, String action) { return "/internal/stages/" + claim.path("stage_id").asText() + "/" + action; }
    private static Map<String, Object> credentials(JsonNode claim, String first, Object one, String second, Object two) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("attempt", claim.path("attempt").asInt()); body.put("run_token", claim.path("run_token").asText());
        body.put(first, one); body.put(second, two); return body;
    }
    private static JsonNode stage(JsonNode job, String type) {
        for (JsonNode stage : job.path("stages")) if (type.equals(stage.path("stage_type").asText())) return stage;
        throw new AssertionError("Missing fixture stage " + type);
    }
    private static void write(String key, byte[] bytes) throws Exception { Path path = root.resolve(key); Files.createDirectories(path.getParent()); Files.write(path, bytes); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private HttpResponse<byte[]> send(String method, String path, Object body, String idempotency, String workerKey) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10));
        if (path.endsWith("/events")) builder.header("Accept", "text/event-stream");
        if (body != null) builder.header("Content-Type", "application/json");
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        if (workerKey != null) builder.header("X-Worker-Key", workerKey);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpResponse<byte[]> worker(String method, String path, Object body) throws Exception { return send(method, path, body, null, KEY); }
    private JsonNode get(String path) throws Exception { return ok(send("GET", path, null, null, null)); }
    private static JsonNode ok(HttpResponse<byte[]> response) { assertEquals(200, response.statusCode(), text(response)); return JSON.readTree(response.body()); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    private static void jsonError(HttpResponse<byte[]> response, int status, String code) {
        assertEquals(status, response.statusCode(), text(response));
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"));
        JsonNode problem = JSON.readTree(response.body()); assertEquals(code, problem.path("error_code").asText());
        assertEquals(problem.path("request_id").asText(), response.headers().firstValue("X-Request-ID").orElseThrow());
    }
    private SseConnection open(String kind, String id, String last) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve("/api/" + kind + "/" + id + "/events")).header("Accept", "text/event-stream").timeout(Duration.ofSeconds(15));
        if (last != null) builder.header("Last-Event-ID", last);
        HttpResponse<InputStream> response = http.sendAsync(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream()).get(5, TimeUnit.SECONDS);
        if (response.statusCode() != 200) {
            try (var body = response.body()) { fail("SSE open returned " + response.statusCode() + ": " + new String(body.readAllBytes(), StandardCharsets.UTF_8)); }
        }
        SseConnection connection = new SseConnection(response); connections.add(connection); return connection;
    }
    private HttpResponse<byte[]> refusedStream(String kind, String id) throws Exception {
        return send("GET", "/api/" + kind + "/" + id + "/events", null, null, null);
    }
    private static void assertStreamHeaders(HttpResponse<?> response) {
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
        assertEquals("no-cache, no-transform", response.headers().firstValue("cache-control").orElseThrow());
        assertEquals("no", response.headers().firstValue("X-Accel-Buffering").orElseThrow());
    }
    private static void assertSnapshot(SnapshotEvent event, String kind, String id, JsonNode expected) {
        assertEquals(kind + ":" + id + ":" + expected.path("state_version").asLong(), event.id());
        assertEquals(expected, event.data(), "SSE data must be the existing complete public snapshot");
        String body = event.data().toString();
        for (String field : List.of("run_token", "source_key", "response_key", "worker_id")) assertFalse(body.contains("\"" + field + "\""), body);
        assertFalse(body.contains(root.toString())); assertFalse(body.contains(KEY));
        for (JsonNode stage : event.data().path("stages")) { assertTrue(stage.path("manifest_key").isNull()); assertTrue(stage.path("checkpoint_key").isNull()); }
    }
    private static void await(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "SSE lifecycle did not converge before timeout");
    }

    private record SnapshotEvent(String id, JsonNode data) {}
    private record Frame(String event, String id, String data, boolean heartbeat, boolean end, Throwable error) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class WriteBoundaryFixture {
        @Bean @Primary EventStreamFactory transactionObservingStreams() {
            return new EventStreamFactory() {
                @Override public Stream open(long timeoutMs) {
                    Stream actual = super.open(timeoutMs);
                    return new Stream(actual.emitter()) {
                        private void observe() {
                            if (TransactionSynchronizationManager.isActualTransactionActive()) SOCKET_WRITE_IN_TRANSACTION.set(true);
                        }
                        @Override public void snapshot(String id, String json) throws IOException { observe(); super.snapshot(id, json); }
                        @Override public void heartbeat() throws IOException { observe(); super.heartbeat(); }
                        @Override public void complete() { observe(); super.complete(); }
                        @Override public void abort() { observe(); super.abort(); }
                    };
                }
            };
        }
    }

    private static final class SseConnection implements AutoCloseable {
        final HttpResponse<InputStream> response;
        final LinkedBlockingQueue<Frame> frames = new LinkedBlockingQueue<>(64);
        final AtomicBoolean closed = new AtomicBoolean();
        final Thread reader;
        volatile int retryMs = -1;

        SseConnection(HttpResponse<InputStream> response) {
            this.response = response;
            reader = Thread.ofVirtual().name("sse-test-reader").start(this::read);
        }
        private void read() {
            try (var lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String event = "", id = ""; StringBuilder data = new StringBuilder(); boolean comment = false;
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!event.isEmpty() || !data.isEmpty() || comment) offer(new Frame(event, id, data.toString(), comment && data.isEmpty(), false, null));
                        event = ""; id = ""; data.setLength(0); comment = false; continue;
                    }
                    if (line.startsWith(":")) { comment = true; continue; }
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    switch (field) {
                        case "event" -> event = value;
                        case "id" -> id = value;
                        case "data" -> { if (!data.isEmpty()) data.append('\n'); data.append(value); }
                        case "retry" -> retryMs = Integer.parseInt(value);
                        default -> { }
                    }
                }
            } catch (Throwable failure) {
                if (!closed.get()) offer(new Frame("", "", "", false, false, failure));
            } finally { offer(new Frame("", "", "", false, true, null)); }
        }
        private void offer(Frame frame) {
            if (!frames.offer(frame)) throw new AssertionError("Test SSE reader queue overflowed");
        }
        private Frame next(long deadline) throws Exception {
            long remaining = deadline - System.nanoTime();
            assertTrue(remaining > 0, "Timed out waiting for SSE data");
            Frame frame = frames.poll(remaining, TimeUnit.NANOSECONDS);
            assertNotNull(frame, "Timed out waiting for SSE frame");
            if (frame.error() != null) throw new AssertionError("SSE socket failed", frame.error());
            return frame;
        }
        SnapshotEvent snapshot() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                Frame frame = next(deadline); assertFalse(frame.end(), "SSE ended before its required snapshot");
                if (frame.heartbeat()) continue;
                assertEquals("snapshot", frame.event());
                assertFalse(frame.id().isBlank()); return new SnapshotEvent(frame.id(), JSON.readTree(frame.data()));
            }
        }
        void heartbeat() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (true) { Frame frame = next(deadline); assertFalse(frame.end()); if (frame.heartbeat()) return; fail("Unchanged resource must not resend snapshots"); }
        }
        void noSnapshotFor(long durationMs) throws Exception {
            long deadline = System.nanoTime() + Duration.ofMillis(durationMs).toNanos();
            while (System.nanoTime() < deadline) {
                Frame frame = frames.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (frame == null) return;
                if (frame.error() != null) throw new AssertionError(frame.error());
                assertTrue(frame.heartbeat(), "Only heartbeat comments are allowed for an unchanged snapshot");
            }
        }
        void end() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) { Frame frame = next(deadline); if (frame.end()) return; assertTrue(frame.heartbeat(), "A terminal snapshot must be followed by EOF"); }
        }
        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            response.body().close(); reader.interrupt();
            try { reader.join(1000); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }
    }
}
