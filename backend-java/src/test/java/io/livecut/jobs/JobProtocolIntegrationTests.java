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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.livecut.LivecutBackendApplication;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real MySQL + real HTTP tests of the execution protocol. FakeWorker manifests
 * contain real hashed synthetic files, not proof of media/model output quality.
 * Cleanup deletes this class's UUID rows only, never truncates a shared table.
 */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobProtocolIntegrationTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WORKER_KEY = "protocol-test-only-worker-key";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    @TempDir
    static Path storageRoot;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Set<String> jobs = new LinkedHashSet<>();
    private ConfigurableApplicationContext context;
    private URI base;
    private String videoId;

    @BeforeAll
    void start() throws Exception {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:\\?.*)?"),
                "Protocol recovery tests require the dedicated livecut_test database");
        startContext();
        // A header-only synthetic source registers the MEDIA work to be done;
        // this fixture deliberately has no real decodable video/model output.
        byte[] header = ByteBuffer.allocate(48).putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .put("isom".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(24)
                .put("free".getBytes(StandardCharsets.US_ASCII)).array();
        String boundary = "JobProtocol" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"protocol.mp4\"\r\n"
                + "Content-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(header);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        var uploaded = http.send(HttpRequest.newBuilder(base.resolve("/api/videos"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, uploaded.statusCode(), text(uploaded));
        videoId = json(uploaded).path("id").asText();
    }

    @AfterEach
    void cleanupOwnJobs() {
        if (context != null && context.isActive()) {
            for (String id : jobs) { jdbc().update("DELETE FROM analysis_job WHERE id = ?", id); }
        }
        jobs.clear();
    }

    @AfterAll
    void stop() {
        if (context != null && context.isActive()) {
            cleanupOwnJobs();
            if (videoId != null) { jdbc().update("DELETE FROM video_asset WHERE id = ?", videoId); }
            context.close();
        }
    }

    @Test
    void jobCreationIsPersistentAndIdempotentWhileConflictingInputIsRejected() {
        String key = "job-test-" + UUID.randomUUID();
        JsonNode first = create(key, Map.of());
        JsonNode same = create(key, Map.of());
        assertEquals(first.path("id").asText(), same.path("id").asText());
        assertEquals(first.path("generation"), same.path("generation"));
        assertEquals(4, first.path("stages").size());
        assertEquals("READY", stage(first, "MEDIA").path("status").asText());
        assertEquals("BLOCKED", stage(first, "ASR").path("status").asText());
        error(send("POST", "/api/jobs", Map.of("video_id", videoId, "config", Map.of("max_text_calls", 13)), key, null), 409, "IDEMPOTENCY_CONFLICT");
        JsonNode list = json(send("GET", "/api/jobs?limit=1&offset=0", null, null, null));
        assertEquals(1, list.path("limit").asInt());
        assertEquals(1, list.path("items").size());
        assertEquals(first.path("id").asText(), list.path("items").get(0).path("id").asText());
        error(send("GET", "/api/jobs?limit=101", null, null, null), 400, "INVALID_ARGUMENT");
    }

    @Test
    void workerRoutesAreAuthenticatedAndPublicSnapshotsNeverContainExecutionCredentials() {
        String jobId = create().path("id").asText();
        String stageId = stage(get(jobId), "MEDIA").path("id").asText();
        error(send("GET", "/internal/stages/ready", null, null, null), 401, "WORKER_UNAUTHORIZED");
        error(send("GET", "/%69nternal/stages/ready", null, null, null), 401, "WORKER_UNAUTHORIZED");
        error(send("GET", "/internal;version=1/stages/ready", null, null, null), 401, "WORKER_UNAUTHORIZED");
        error(send("GET", "/internal/stages/ready", null, null, "wrong-key"), 401, "WORKER_UNAUTHORIZED");
        error(send("GET", "/internal/model-calls/not-an-endpoint", null, null, null), 401, "WORKER_UNAUTHORIZED");
        var ready = send("GET", "/internal/stages/ready?stage_type=MEDIA&limit=100", null, null, WORKER_KEY);
        assertEquals(200, ready.statusCode());
        assertEquals("no-store", ready.headers().firstValue("cache-control").orElseThrow());
        assertTrue(json(ready).path("items").size() >= 1);
        JsonNode claim = claim(stageId, "auth-worker", UUID.randomUUID().toString());
        String token = claim.path("run_token").asText();
        assertFalse(token.isBlank());
        assertEquals(200, worker("POST", stagePath(claim, "checkpoint"), checkpoint(claim, "checkpoint.json")).statusCode());
        var snapshot = send("GET", "/api/jobs/" + jobId, null, null, null);
        assertFalse(text(snapshot).contains(token));
        assertFalse(text(snapshot).contains(WORKER_KEY));
        assertFalse(text(snapshot).contains("run_token"));
        assertTrue(stage(json(snapshot), "MEDIA").path("checkpoint_key").isNull());
    }

    @Test
    void twoWorkersRaceForOneStageAndLostClaimResponseRecoversTheSameCredential() throws Exception {
        JsonNode job = create();
        String stageId = stage(job, "MEDIA").path("id").asText();
        String firstRequest = UUID.randomUUID().toString();
        String secondRequest = UUID.randomUUID().toString();
        var responses = concurrently(
                () -> worker("POST", "/internal/stages/claim", claimBody(stageId, "race-a", firstRequest)),
                () -> worker("POST", "/internal/stages/claim", claimBody(stageId, "race-b", secondRequest)));
        assertEquals(List.of(200, 200), responses.stream().map(HttpResponse::statusCode).toList());
        long winners = responses.stream().map(JobProtocolIntegrationTests::json)
                .filter(result -> "CLAIMED".equals(result.path("outcome").asText())).count();
        assertEquals(1, winners);
        int winningIndex = "CLAIMED".equals(json(responses.get(0)).path("outcome").asText()) ? 0 : 1;
        JsonNode won = json(responses.get(winningIndex));
        String winningWorker = winningIndex == 0 ? "race-a" : "race-b";
        String winningRequest = winningIndex == 0 ? firstRequest : secondRequest;
        JsonNode restored = claim(stageId, winningWorker, winningRequest);
        assertEquals("ALREADY_CLAIMED", restored.path("outcome").asText());
        assertEquals(won.path("run_token"), restored.path("run_token"));
        assertEquals(won.path("attempt"), restored.path("attempt"));
        assertEquals(1, jdbc().queryForObject("SELECT attempt FROM job_stage WHERE id = ?", Integer.class, stageId));
    }

    @Test
    void reclaimedStageRejectsOldClaimRequestAndExposesTheExistingCheckpointToANewAttempt() {
        JsonNode job = create();
        String id = stage(job, "MEDIA").path("id").asText();
        String request = UUID.randomUUID().toString();
        JsonNode old = claim(id, "resume-worker", request);
        String checkpointKey = attemptPrefix(old) + "asr/checkpoint.json";
        Map<String, Object> checkpoint = checkpoint(old, "asr/checkpoint.json");
        assertEquals(200, worker("POST", stagePath(old, "checkpoint"), checkpoint).statusCode());
        expireAndRecover(id);
        JsonNode staleRequest = claim(id, "resume-worker", request);
        assertEquals("EXPIRED", staleRequest.path("outcome").asText());
        assertTrue(staleRequest.path("run_token").isNull());
        assertEquals(1, jdbc().queryForObject("SELECT attempt FROM job_stage WHERE id = ?", Integer.class, id));
        JsonNode next = claim(id, "resume-worker", UUID.randomUUID().toString());
        assertEquals("CLAIMED", next.path("outcome").asText());
        assertEquals(2, next.path("attempt").asInt());
        assertNotEquals(old.path("run_token"), next.path("run_token"));
        assertEquals(checkpointKey, next.path("checkpoint_key").asText());
        assertEquals(checkpoint.get("checkpoint_hash"), next.path("checkpoint_hash").asText());
        error(worker("POST", stagePath(old, "heartbeat"), credentials(old)), 409, "STALE_ATTEMPT");
        error(worker("POST", stagePath(old, "complete"), completion(old)), 409, "STALE_ATTEMPT");
    }

    @Test
    void heartbeatsPreserveTokenAndCannotExtendPastHardDeadline() {
        JsonNode job = create();
        JsonNode claimed = claim(stage(job, "MEDIA").path("id").asText(), "heartbeat-worker", UUID.randomUUID().toString());
        String stageId = claimed.path("stage_id").asText();
        var beat = worker("POST", stagePath(claimed, "heartbeat"), credentials(claimed));
        assertEquals(200, beat.statusCode());
        assertEquals(claimed.path("run_token").asText(), jdbc().queryForObject("SELECT run_token FROM job_stage WHERE id = ?", String.class, stageId));
        Instant lease = Instant.parse(json(beat).path("stage").path("lease_until").asText());
        Instant hard = Instant.parse(claimed.path("hard_deadline_at").asText());
        assertFalse(lease.isAfter(hard));
        jdbc().update("UPDATE job_stage SET hard_deadline_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", stageId);
        error(worker("POST", stagePath(claimed, "heartbeat"), credentials(claimed)), 409, "STALE_ATTEMPT");
        context.getBean(JobService.class).recoverExpired();
        assertNotEquals("RUNNING", jdbc().queryForObject("SELECT status FROM job_stage WHERE id = ?", String.class, stageId));
    }

    @Test
    void completionAndCheckpointAreIdempotentButDifferentHashesCannotOverwrite() throws Exception {
        JsonNode job = create();
        JsonNode claimed = claim(stage(job, "MEDIA").path("id").asText(), "complete-worker", UUID.randomUUID().toString());
        Map<String, Object> checkpoint = checkpoint(claimed, "checkpoint.json");
        assertEquals("ACCEPTED", json(worker("POST", stagePath(claimed, "checkpoint"), checkpoint)).path("outcome").asText());
        assertEquals("ALREADY_APPLIED", json(worker("POST", stagePath(claimed, "checkpoint"), checkpoint)).path("outcome").asText());
        Path checkpointPath = storageRoot.resolve((String) checkpoint.get("checkpoint_key"));
        byte[] savedCheckpoint = Files.readAllBytes(checkpointPath);
        Files.writeString(checkpointPath, "tampered synthetic checkpoint", StandardCharsets.UTF_8);
        error(worker("POST", stagePath(claimed, "checkpoint"), checkpoint), 409, "ARTIFACT_HASH_MISMATCH");
        Files.write(checkpointPath, savedCheckpoint);
        error(worker("POST", stagePath(claimed, "checkpoint"), withCredential(claimed,
                "checkpoint_key", attemptPrefix(claimed) + "checkpoint.json", "checkpoint_hash", OTHER_HASH)), 409, "CHECKPOINT_CONFLICT");
        error(worker("POST", stagePath(claimed, "complete"), withCredential(claimed,
                "manifest_key", "../../outside.json", "manifest_hash", HASH)), 400, "INVALID_MANIFEST_KEY");
        error(worker("POST", stagePath(claimed, "complete"), completion(claimed, "not-a-hash")), 400, "INVALID_MANIFEST_HASH");
        Map<String, Object> complete = completion(claimed);
        assertEquals("ACCEPTED", json(worker("POST", stagePath(claimed, "complete"), complete)).path("outcome").asText());
        Path manifestPath = storageRoot.resolve((String) complete.get("manifest_key"));
        byte[] savedManifest = Files.readAllBytes(manifestPath);
        Files.writeString(manifestPath, "tampered synthetic manifest", StandardCharsets.UTF_8);
        error(worker("POST", stagePath(claimed, "complete"), complete), 409, "ARTIFACT_HASH_MISMATCH");
        Files.write(manifestPath, savedManifest);
        assertEquals("ALREADY_APPLIED", json(worker("POST", stagePath(claimed, "complete"), complete)).path("outcome").asText());
        error(worker("POST", stagePath(claimed, "complete"), completion(claimed, OTHER_HASH)), 409, "RESULT_CONFLICT");
        JsonNode snapshot = get(job.path("id").asText());
        assertEquals("SUCCEEDED", stage(snapshot, "MEDIA").path("status").asText());
        assertEquals("READY", stage(snapshot, "ASR").path("status").asText());
        assertFalse(snapshot.toString().contains(claimed.path("run_token").asText()));
        assertTrue(stage(snapshot, "MEDIA").path("manifest_key").isNull());
    }

    @Test
    void cancelRevokesTokensAndManualRetryPreservesAttemptLimits() {
        JsonNode canceled = create();
        String canceledId = canceled.path("id").asText();
        JsonNode canceledClaim = claim(stage(canceled, "MEDIA").path("id").asText(), "cancel-worker", UUID.randomUUID().toString());
        assertEquals("CANCELLED", json(send("POST", "/api/jobs/" + canceledId + "/cancel", null, null, null)).path("status").asText());
        error(worker("POST", stagePath(canceledClaim, "heartbeat"), credentials(canceledClaim)), 409, "STALE_ATTEMPT");
        error(send("POST", "/api/jobs/" + canceledId + "/retry", null, null, null), 409, "JOB_NOT_RETRYABLE");

        JsonNode retried = create();
        String jobId = retried.path("id").asText();
        String stageId = stage(retried, "MEDIA").path("id").asText();
        for (int attempt = 1; attempt <= 3; attempt++) {
            JsonNode current = claim(stageId, "retry-worker", UUID.randomUUID().toString());
            assertEquals(attempt, current.path("attempt").asInt());
            assertEquals(200, worker("POST", stagePath(current, "fail"), withCredential(current,
                    "error_code", "FIXTURE_FAILURE", "retryable", false)).statusCode());
            assertEquals("FAILED", get(jobId).path("status").asText());
            var retry = send("POST", "/api/jobs/" + jobId + "/retry", null, null, null);
            if (attempt < 3) {
                assertEquals(200, retry.statusCode(), text(retry));
                assertEquals("READY", stage(json(retry), "MEDIA").path("status").asText());
                assertEquals(attempt, stage(json(retry), "MEDIA").path("attempt").asInt());
            } else {
                error(retry, 409, "ATTEMPTS_EXHAUSTED");
            }
        }
    }

    @Test
    void completionRejectsMissingTamperedOrWrongIdentityFilesWithoutAdvancingState() throws Exception {
        JsonNode job = create();
        String jobId = job.path("id").asText();
        JsonNode claimed = claim(stage(job, "MEDIA").path("id").asText(), "integrity-worker", UUID.randomUUID().toString());
        String manifestKey = attemptPrefix(claimed) + "manifest.json";
        error(worker("POST", stagePath(claimed, "complete"), completion(claimed, HASH)), 409, "ARTIFACT_NOT_FOUND");

        Map<String, Object> payload = completion(claimed);
        Path manifestPath = storageRoot.resolve(manifestKey);
        byte[] originalManifest = Files.readAllBytes(manifestPath);
        JsonNode parsed = JSON.readTree(originalManifest);
        Path artifact = storageRoot.resolve(parsed.path("artifacts").get(0).path("key").asText());
        byte[] originalArtifact = Files.readAllBytes(artifact);
        Files.writeString(artifact, "tampered synthetic artifact", StandardCharsets.UTF_8);
        error(worker("POST", stagePath(claimed, "complete"), payload), 409, "ARTIFACT_HASH_MISMATCH");
        Files.write(artifact, originalArtifact);

        var wrongIdentity = (tools.jackson.databind.node.ObjectNode) JSON.readTree(originalManifest);
        wrongIdentity.put("analysis_generation", claimed.path("generation").asInt() + 1);
        Files.write(manifestPath, JSON.writeValueAsBytes(wrongIdentity));
        error(worker("POST", stagePath(claimed, "complete"), completion(claimed, fileHash(manifestPath))),
                409, "ARTIFACT_IDENTITY_MISMATCH");

        String missingCheckpoint = attemptPrefix(claimed) + "missing-checkpoint.bin";
        error(worker("POST", stagePath(claimed, "checkpoint"), withCredential(claimed,
                "checkpoint_key", missingCheckpoint, "checkpoint_hash", HASH)), 409, "ARTIFACT_NOT_FOUND");
        var checkpoint = checkpoint(claimed, "checkpoint.bin");
        error(worker("POST", stagePath(claimed, "checkpoint"), withCredential(claimed,
                "checkpoint_key", checkpoint.get("checkpoint_key"), "checkpoint_hash", OTHER_HASH)), 409, "ARTIFACT_HASH_MISMATCH");

        JsonNode afterFailures = get(jobId);
        assertEquals("RUNNING", stage(afterFailures, "MEDIA").path("status").asText());
        assertEquals("BLOCKED", stage(afterFailures, "ASR").path("status").asText());
        assertTrue(stage(afterFailures, "MEDIA").path("manifest_hash").isNull());
        assertNull(jdbc().queryForObject("SELECT checkpoint_key FROM job_stage WHERE id=?", String.class,
                claimed.path("stage_id").asText()));
        Files.write(manifestPath, originalManifest);
        assertEquals(200, worker("POST", stagePath(claimed, "complete"), payload).statusCode());
    }

    @Test
    void completionAndLeaseReaperRaceCannotProduceAnOldSuccessOrDuplicateActivation() throws Exception {
        for (int index = 0; index < 4; index++) {
            JsonNode job = create();
            String jobId = job.path("id").asText();
            JsonNode claimed = claim(stage(job, "MEDIA").path("id").asText(), "reaper-race", UUID.randomUUID().toString());
            String id = claimed.path("stage_id").asText();
            jdbc().update("UPDATE job_stage SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", id);
            var results = concurrently(
                    () -> worker("POST", stagePath(claimed, "complete"), completion(claimed)),
                    () -> { context.getBean(JobService.class).recoverExpired(); return send("GET", "/api/jobs/" + jobId, null, null, null); });
            int completeStatus = results.get(0).statusCode();
            assertTrue(completeStatus == 200 || completeStatus == 409, text(results.get(0)));
            JsonNode snapshot = get(jobId);
            String finalStatus = stage(snapshot, "MEDIA").path("status").asText();
            if (completeStatus == 200) {
                assertEquals("SUCCEEDED", finalStatus);
                assertEquals("READY", stage(snapshot, "ASR").path("status").asText());
            } else {
                assertTrue(Set.of("RETRY_WAIT", "READY").contains(finalStatus));
                assertEquals("BLOCKED", stage(snapshot, "ASR").path("status").asText());
                error(worker("POST", stagePath(claimed, "complete"), completion(claimed)), 409, "STALE_ATTEMPT");
            }
            assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM job_stage WHERE job_id = ? AND stage_type = 'ASR'", Integer.class, jobId));
        }
    }

    @Test
    void lastTwoAsrShardsCompleteConcurrentlyAndActivateExactlyOneNextStage() throws Exception {
        JsonNode job = create();
        String jobId = job.path("id").asText();
        JsonNode media = claim(stage(job, "MEDIA").path("id").asText(), "fan-in-media", UUID.randomUUID().toString());
        assertEquals(200, worker("POST", stagePath(media, "complete"), completion(media)).statusCode());
        String firstId = stage(get(jobId), "ASR").path("id").asText();
        String secondId = UUID.randomUUID().toString();
        // Inject only this test job's second shard to exercise the fan-in gate;
        // production M0 creation still uses one ASR shard by default.
        jdbc().update("INSERT INTO job_stage (id, job_id, owner_type, owner_id, stage_type, shard_key, status, attempt, next_run_at, input_hash) "
                + "SELECT ?, job_id, owner_type, owner_id, stage_type, '1', 'READY', 0, UTC_TIMESTAMP(6), input_hash FROM job_stage WHERE id = ? AND job_id = ?",
                secondId, firstId, jobId);
        JsonNode first = claim(firstId, "fan-in-first", UUID.randomUUID().toString());
        JsonNode second = claim(secondId, "fan-in-second", UUID.randomUUID().toString());
        assertEquals("CLAIMED", first.path("outcome").asText());
        assertEquals("CLAIMED", second.path("outcome").asText());
        var responses = concurrently(
                () -> worker("POST", stagePath(first, "complete"), completion(first)),
                () -> worker("POST", stagePath(second, "complete"), completion(second)));
        assertEquals(List.of(200, 200), responses.stream().map(HttpResponse::statusCode).toList());
        assertEquals(2, jdbc().queryForObject("SELECT COUNT(*) FROM job_stage WHERE job_id = ? AND stage_type = 'ASR' AND status = 'SUCCEEDED'", Integer.class, jobId));
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM job_stage WHERE job_id = ? AND stage_type = 'SEGMENT' AND status = 'READY'", Integer.class, jobId));
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM job_stage WHERE job_id = ? AND stage_type = 'SEGMENT'", Integer.class, jobId));
        JsonNode segment = claim(stage(get(jobId), "SEGMENT").path("id").asText(), "fan-in-segment", UUID.randomUUID().toString());
        int asrArtifacts = 0;
        for (JsonNode artifact : segment.path("artifacts")) {
            if ("ASR".equals(artifact.path("stage_type").asText())) { asrArtifacts++; }
        }
        assertEquals(2, asrArtifacts, "Both ASR shard outputs must be supplied to the next worker");
        assertEquals(200, worker("POST", stagePath(segment, "complete"), completion(segment)).statusCode());
        JsonNode timeline = claim(stage(get(jobId), "TIMELINE").path("id").asText(), "fan-in-timeline", UUID.randomUUID().toString());
        assertEquals(200, worker("POST", stagePath(timeline, "complete"), completion(timeline)).statusCode());
        assertEquals("REVIEW_READY", get(jobId).path("status").asText());
    }

    @Test
    void sameClaimAndCheckpointSurviveApplicationRestart() {
        JsonNode job = create();
        String jobId = job.path("id").asText();
        String stageId = stage(job, "MEDIA").path("id").asText();
        String request = UUID.randomUUID().toString();
        JsonNode claimed = claim(stageId, "restart-worker", request);
        String checkpoint = attemptPrefix(claimed) + "checkpoint.json";
        assertEquals(200, worker("POST", stagePath(claimed, "checkpoint"), checkpoint(claimed, "checkpoint.json")).statusCode());
        context.close();
        startContext();
        JsonNode restored = claim(stageId, "restart-worker", request);
        assertEquals("ALREADY_CLAIMED", restored.path("outcome").asText());
        assertEquals(claimed.path("run_token"), restored.path("run_token"));
        assertEquals(claimed.path("hard_deadline_at"), restored.path("hard_deadline_at"));
        assertEquals(checkpoint, restored.path("checkpoint_key").asText());
        assertEquals("RUNNING", stage(get(jobId), "MEDIA").path("status").asText());
        assertEquals(200, worker("POST", stagePath(restored, "complete"), completion(restored)).statusCode());
    }

    @Test
    void malformedJsonAndUnsupportedMethodsHaveStableClientErrors() {
        try {
            var response = http.send(HttpRequest.newBuilder(base.resolve("/api/jobs")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{bad-json")).build(), HttpResponse.BodyHandlers.ofByteArray());
            error(response, 400, "INVALID_JSON");
        } catch (Exception exception) { throw new RuntimeException(exception); }
        error(send("PUT", "/api/jobs", Map.of(), null, null), 405, "METHOD_NOT_ALLOWED");
        JsonNode job = create();
        JsonNode claimed = claim(stage(job, "MEDIA").path("id").asText(), "json-worker", UUID.randomUUID().toString());
        error(worker("POST", stagePath(claimed, "heartbeat"), Map.of("attempt", 1.5, "run_token", claimed.path("run_token").asText())), 400, "INVALID_JSON");
    }

    private void startContext() {
        context = new SpringApplicationBuilder(LivecutBackendApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--livecut.storage.root=" + storageRoot.toAbsolutePath(),
                "--livecut.worker.api-key=" + WORKER_KEY, "--livecut.jobs.scan-enabled=false", "--livecut.jobs.lease-seconds=30",
                "--livecut.jobs.hard-deadline-seconds=1800", "--livecut.jobs.max-attempts=3", "--livecut.jobs.retry-delay-seconds=2");
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }

    private JsonNode create() { return create("job-test-" + UUID.randomUUID(), Map.of()); }

    private JsonNode create(String key, Map<String, Object> config) {
        var response = send("POST", "/api/jobs", Map.of("video_id", videoId, "config", config), key, null);
        assertEquals(201, response.statusCode(), text(response));
        JsonNode job = json(response);
        jobs.add(job.path("id").asText());
        return job;
    }

    private JsonNode get(String id) {
        var response = send("GET", "/api/jobs/" + id, null, null, null);
        assertEquals(200, response.statusCode(), text(response));
        return json(response);
    }

    private JsonNode claim(String stageId, String worker, String request) {
        var response = worker("POST", "/internal/stages/claim", claimBody(stageId, worker, request));
        assertEquals(200, response.statusCode(), text(response));
        return json(response);
    }

    private static Map<String, Object> claimBody(String stage, String worker, String request) {
        return Map.of("stage_id", stage, "worker_id", worker, "claim_request_id", request);
    }

    private static JsonNode stage(JsonNode job, String type) {
        for (JsonNode stage : job.path("stages")) {
            if (type.equals(stage.path("stage_type").asText())) { return stage; }
        }
        throw new AssertionError("Missing stage type " + type);
    }

    private void expireAndRecover(String stageId) {
        jdbc().update("UPDATE job_stage SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", stageId);
        context.getBean(JobService.class).recoverExpired();
        jdbc().update("UPDATE job_stage SET next_run_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", stageId);
        context.getBean(JobService.class).recoverExpired();
    }

    private static Map<String, Object> credentials(JsonNode claim) {
        return Map.of("attempt", claim.path("attempt").asInt(), "run_token", claim.path("run_token").asText());
    }

    private static Map<String, Object> withCredential(JsonNode claim, String firstKey, Object firstValue, String secondKey, Object secondValue) {
        var values = new java.util.LinkedHashMap<String, Object>(credentials(claim));
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    private static String attemptPrefix(JsonNode claim) {
        return "jobs/" + claim.path("job_id").asText() + "/stages/" + claim.path("stage_id").asText()
                + "/attempts/" + claim.path("attempt").asInt() + "/" + claim.path("run_token").asText() + "/";
    }

    private static Map<String, Object> completion(JsonNode claim, String hash) {
        return withCredential(claim, "manifest_key", attemptPrefix(claim) + "manifest.json", "manifest_hash", hash);
    }

    private static Map<String, Object> checkpoint(JsonNode claim, String suffix) {
        try {
            String key = attemptPrefix(claim) + suffix;
            Path path = storageRoot.resolve(key);
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) { Files.writeString(path, "{\"synthetic_fixture\":true}", StandardCharsets.UTF_8); }
            return withCredential(claim, "checkpoint_key", key, "checkpoint_hash", fileHash(path));
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }

    /** Real local bytes for protocol integrity only; these do not simulate model accuracy. */
    private static Map<String, Object> completion(JsonNode claim) {
        try {
            String prefix = attemptPrefix(claim);
            Path manifestPath = storageRoot.resolve(prefix + "manifest.json");
            if (!Files.exists(manifestPath)) {
                Files.createDirectories(manifestPath.getParent());
                List<String> kinds = switch (claim.path("stage_type").asText()) {
                    case "MEDIA" -> List.of("media_info", "audio");
                    case "ASR" -> List.of("transcript");
                    default -> List.of("analysis_result");
                };
                var artifacts = new ArrayList<Map<String, Object>>();
                for (String kind : kinds) {
                    String key = prefix + "synthetic-" + kind + ".bin";
                    Path artifact = storageRoot.resolve(key);
                    Files.writeString(artifact, "synthetic protocol fixture: " + kind, StandardCharsets.UTF_8);
                    artifacts.add(Map.of("kind", kind, "key", key, "sha256", fileHash(artifact)));
                }
                var manifest = new java.util.LinkedHashMap<String, Object>();
                manifest.put("schema_version", 1);
                manifest.put("job_id", claim.path("job_id").asText());
                manifest.put("video_id", claim.path("video_id").asText());
                manifest.put("analysis_generation", claim.path("generation").asInt());
                manifest.put("stage_id", claim.path("stage_id").asText());
                manifest.put("stage_type", claim.path("stage_type").asText());
                manifest.put("attempt", claim.path("attempt").asInt());
                manifest.put("run_token", claim.path("run_token").asText());
                manifest.put("input_sha256", claim.path("source_hash").asText());
                manifest.put("data_kind", "model_result");
                manifest.put("review_status", "DRAFT");
                manifest.put("artifacts", artifacts);
                Files.write(manifestPath, JSON.writeValueAsBytes(manifest));
            }
            return completion(claim, fileHash(manifestPath));
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private static String fileHash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String stagePath(JsonNode claim, String action) { return "/internal/stages/" + claim.path("stage_id").asText() + "/" + action; }

    private HttpResponse<byte[]> worker(String method, String path, Object body) { return send(method, path, body, null, WORKER_KEY); }

    private HttpResponse<byte[]> send(String method, String path, Object body, String idempotency, String workerKey) {
        try {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20));
            if (idempotency != null) { request.header("Idempotency-Key", idempotency); }
            if (workerKey != null) { request.header("X-Worker-Key", workerKey); }
            if (body != null) { request.header("Content-Type", "application/json"); }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private static List<HttpResponse<byte[]>> concurrently(Supplier<HttpResponse<byte[]>> first, Supplier<HttpResponse<byte[]>> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        var firstFuture = CompletableFuture.supplyAsync(() -> { ready.countDown(); await(start); return first.get(); });
        var secondFuture = CompletableFuture.supplyAsync(() -> { ready.countDown(); await(start); return second.get(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "Concurrent workers must both reach the barrier");
        start.countDown();
        return List.of(firstFuture.get(30, TimeUnit.SECONDS), secondFuture.get(30, TimeUnit.SECONDS));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Test barrier timed out"); } }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
    }

    private static JsonNode json(HttpResponse<byte[]> response) { return JSON.readTree(response.body()); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    private static void error(HttpResponse<byte[]> response, int status, String code) {
        assertEquals(status, response.statusCode(), text(response));
        JsonNode error = json(response);
        assertEquals(code, error.path("error_code").asText());
        assertFalse(error.path("request_id").asText().isBlank());
        assertFalse(text(response).contains(WORKER_KEY));
    }
}

/** Auth failure paths need no database, including the explicitly unconfigured case. */
class InternalWorkerAuthFilterTests {
    @Test
    void missingConfigurationFailsClosedAndOtherRoutesRemainPublic() throws Exception {
        var json = new ObjectMapper();
        var filter = new InternalWorkerAuthFilter("", json);
        var response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("GET", "/internal/stages/ready"), response,
                (request, result) -> reached.set(true));
        assertEquals(503, response.getStatus());
        assertEquals("WORKER_AUTH_NOT_CONFIGURED", json.readTree(response.getContentAsByteArray()).path("error_code").asText());
        assertFalse(reached.get());
        response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/jobs"), response, (request, result) -> reached.set(true));
        assertTrue(reached.get());
    }

    @Test
    void wrongCredentialNeverReachesWorkerHandlerAndExactCredentialDoes() throws Exception {
        String secret = "unit-only-worker-key";
        var filter = new InternalWorkerAuthFilter(secret, new ObjectMapper());
        for (String value : List.of("", "wrong", secret)) {
            var request = new MockHttpServletRequest("POST", "/internal/stages/claim");
            request.addHeader("X-Worker-Key", value);
            var response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, response, (input, output) -> reached.set(true));
            assertEquals(value.equals(secret), reached.get());
            if (!value.equals(secret)) {
                assertEquals(401, response.getStatus());
                assertFalse(response.getContentAsString().contains(secret));
                assertFalse(response.getContentAsString().contains(value.isEmpty() ? "not-a-secret" : value));
            }
        }
    }
}
