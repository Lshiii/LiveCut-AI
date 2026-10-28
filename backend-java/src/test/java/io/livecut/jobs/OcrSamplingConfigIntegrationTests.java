package io.livecut.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real API/MySQL contracts; header-only media never runs OCR or a model. */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OcrSamplingConfigIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WORKER_KEY = "ocr-sampling-test-worker-only";
    private static final List<String> FLOW = List.of("MEDIA", "ASR", "SEGMENT", "TIMELINE");
    private static final AtomicReference<String> MISS_RECEIPT_ONCE = new AtomicReference<>();
    private static final AtomicInteger HIDDEN_RECEIPTS = new AtomicInteger();
    @TempDir static Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Set<String> jobs = new LinkedHashSet<>(), videos = new LinkedHashSet<>();
    private ConfigurableApplicationContext context;
    private URI base;
    private record Legacy(String job, String video, String key) {}

    @BeforeAll void start() {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:\\?.*)?"), "Sampling tests require dedicated livecut_test");
        startContext();
    }
    private void startContext() {
        context = new SpringApplicationBuilder(LivecutBackendApplication.class, ReceiptRaceFixture.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--livecut.storage.root=" + root,
                "--livecut.worker.api-key=" + WORKER_KEY, "--livecut.jobs.scan-enabled=false",
                "--livecut.uploads.scan-enabled=false", "--livecut.events.scan-enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup() {
        MISS_RECEIPT_ONCE.set(null); HIDDEN_RECEIPTS.set(0);
        for (String job : jobs) jdbc().update("DELETE FROM analysis_job WHERE id=?", job);
        for (String video : videos) jdbc().update("DELETE FROM video_asset WHERE id=?", video);
        jobs.clear(); videos.clear();
    }
    @AfterAll void stop() { if (context != null) context.close(); }
    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }

    @Test void newDefaultIsPersistedAsUniformAndExplicitUniformReplaysTheSameNormalizedInput() throws Exception {
        String video = video(), key = key();
        JsonNode job = created(create(video, key, Map.of()));
        Map<String, Object> expected = new TreeMap<>(legacyConfig()); expected.put("ocr_sampling", "uniform");
        expected.put("ocr_max_frames", 120);
        assertEquals(JSON.valueToTree(expected), job.path("config"));
        assertEquals(job.path("config"), JSON.readTree(jdbc().queryForObject("SELECT config_json FROM analysis_job WHERE id=?", String.class, job.path("id").asText())));
        byte[] before = getBytes(job.path("id").asText());
        assertArrayEquals(before, create(video, key, Map.of("ocr_sampling", "uniform")).body());
        assertArrayEquals(before, create(video, key, Map.of("ocr_max_frames", 120)).body());
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM analysis_job WHERE video_id=?", Integer.class, video));
        error(create(video, key, Map.of("ocr_sampling", "prefix")), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test void explicitPrefixAndUniformParticipateInConfigAndEveryStageInputHash() throws Exception {
        String video = video();
        String prefixKey = key();
        JsonNode prefix = created(create(video, prefixKey, Map.of("ocr_sampling", "prefix")));
        JsonNode uniform = created(create(video, key(), Map.of("ocr_sampling", "uniform")));
        error(create(video, prefixKey, Map.of()), 409, "IDEMPOTENCY_CONFLICT");
        assertNotEquals(prefix.path("config_hash"), uniform.path("config_hash"));
        String source = jdbc().queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, video);
        for (JsonNode job : List.of(prefix, uniform)) {
            Map<String, Object> normalized = new TreeMap<>(legacyConfig());
            normalized.put("ocr_sampling", job.path("config").path("ocr_sampling").asText());
            normalized.put("ocr_max_frames", 120);
            assertEquals(hash(JSON.writeValueAsString(normalized)), job.path("config_hash").asText());
            for (JsonNode stage : job.path("stages")) {
                assertEquals(hash(source + "\n" + job.path("config_hash").asText() + "\n" + stage.path("stage_type").asText() + "\n0"), stage.path("input_hash").asText());
            }
        }
        assertNotEquals(inputHashes(prefix.path("id").asText()), inputHashes(uniform.path("id").asText()));
    }

    @Test void disabledVisualProviderStillFreezesPolicyAndClaimReturnsItWithoutAnOcrExecution() throws Exception {
        JsonNode job = created(create(video(), key(), Map.of("visual_provider", "none", "ocr_sampling", "uniform")));
        JsonNode claimed = claim(stage(job, "MEDIA").path("id").asText());
        assertEquals("none", claimed.path("config").path("visual_provider").asText());
        assertEquals("uniform", claimed.path("config").path("ocr_sampling").asText());
        assertEquals(120, claimed.path("config").path("ocr_max_frames").asInt());
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, job.path("id").asText()));
    }

    @Test void unsupportedPolicyTypesAndUnknownValuesCannotCreateAnyTask() throws Exception {
        String video = video();
        for (String value : List.of("null", "true", "0", "1.0", "[]", "{}", "\"UNIFORM\"", "\"random\"", "\"\"")) {
            error(create(video, key(), JSON.readTree("{\"ocr_sampling\":" + value + "}")), 400, "INVALID_ARGUMENT");
        }
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM analysis_job WHERE video_id=?", Integer.class, video));
    }

    @Test void legacyLostAcknowledgementReplaysByteForByteWithoutRewritingAnyFrozenMetadata() throws Exception {
        Legacy legacy = legacy();
        byte[] before = getBytes(legacy.job());
        var metadata = metadata(legacy.job()); var stages = stageRows(legacy.job());
        assertArrayEquals(before, create(legacy.video(), legacy.key(), Map.of()).body());
        // Omitting the entire config object was also a valid old creation request.
        var missingConfig = send("POST", "/api/jobs", Map.of("video_id", legacy.video()), legacy.key(), false);
        assertEquals(201, missingConfig.statusCode(), text(missingConfig)); assertArrayEquals(before, missingConfig.body());
        assertEquals(metadata, metadata(legacy.job())); assertEquals(stages, stageRows(legacy.job()));
        assertFalse(JSON.readTree(before).path("config").has("ocr_sampling"));
    }

    @Test void legacyPolicyIsNeverBackfilledByRestartReadsClaimOrRetry() throws Exception {
        Legacy legacy = legacy();
        byte[] before = getBytes(legacy.job()); var metadata = metadata(legacy.job()); var inputs = inputHashes(legacy.job());
        context.close(); startContext();
        assertArrayEquals(before, getBytes(legacy.job())); assertEquals(metadata, metadata(legacy.job()));
        JsonNode old = claim(stage(JSON.readTree(before), "MEDIA").path("id").asText());
        assertFalse(old.path("config").has("ocr_sampling"));
        ok(send("POST", action(old, "fail"), Map.of("attempt", old.path("attempt").asInt(), "run_token", old.path("run_token").asText(), "error_code", "SYNTHETIC_RETRY", "retryable", false), null, true));
        JsonNode ready = ok(send("POST", "/api/jobs/" + legacy.job() + "/retry", null, null, false));
        assertFalse(ready.path("config").has("ocr_sampling"));
        JsonNode next = claim(stage(ready, "MEDIA").path("id").asText());
        assertEquals(2, next.path("attempt").asInt()); assertFalse(next.path("config").has("ocr_sampling"));
        assertEquals(metadata.get("config_hash"), metadata(legacy.job()).get("config_hash"));
        assertEquals(metadata.get("request_hash"), metadata(legacy.job()).get("request_hash"));
        assertEquals(metadata.get("config_json"), metadata(legacy.job()).get("config_json"));
        assertEquals(inputs, inputHashes(legacy.job()));
    }

    @Test void legacyReceiptRejectsExplicitPoliciesChangedBudgetAndForeignVideo() throws Exception {
        Legacy legacy = legacy();
        byte[] before = getBytes(legacy.job()); var frozen = metadata(legacy.job()); var stages = stageRows(legacy.job());
        for (var config : List.of(Map.of("ocr_sampling", "prefix"), Map.of("ocr_sampling", "uniform"), Map.of("max_text_calls", 13))) {
            error(create(legacy.video(), legacy.key(), config), 409, "IDEMPOTENCY_CONFLICT");
        }
        error(create(video(), legacy.key(), Map.of()), 409, "IDEMPOTENCY_CONFLICT");
        assertArrayEquals(before, getBytes(legacy.job())); assertEquals(frozen, metadata(legacy.job())); assertEquals(stages, stageRows(legacy.job()));
    }

    @Test void duplicateKeyRecoveryUsesTheSameLegacyCompatibilityRuleWithoutMutatingTheReceipt() throws Exception {
        Legacy legacy = legacy();
        byte[] before = getBytes(legacy.job()); var frozen = metadata(legacy.job()); var stages = stageRows(legacy.job());
        MISS_RECEIPT_ONCE.set(legacy.key());
        var replay = create(legacy.video(), legacy.key(), Map.of());
        assertEquals(201, replay.statusCode(), text(replay)); assertArrayEquals(before, replay.body());
        assertEquals(1, HIDDEN_RECEIPTS.get(), "The stale lookup must reach a real MySQL duplicate INSERT and recovery transaction");
        MISS_RECEIPT_ONCE.set(legacy.key());
        error(create(legacy.video(), legacy.key(), Map.of("ocr_sampling", "prefix")), 409, "IDEMPOTENCY_CONFLICT");
        assertEquals(2, HIDDEN_RECEIPTS.get());
        assertEquals(frozen, metadata(legacy.job())); assertEquals(stages, stageRows(legacy.job()));
    }

    @Test void retryOfNewPrefixTaskKeepsTheFrozenExplicitPolicyAndItsInputHashes() throws Exception {
        JsonNode job = created(create(video(), key(), Map.of("ocr_sampling", "prefix", "ocr_max_frames", 72)));
        String id = job.path("id").asText(); var before = metadata(id); var inputs = inputHashes(id);
        JsonNode old = claim(stage(job, "MEDIA").path("id").asText());
        ok(send("POST", action(old, "fail"), Map.of("attempt", old.path("attempt").asInt(), "run_token", old.path("run_token").asText(), "error_code", "SYNTHETIC_RETRY", "retryable", false), null, true));
        JsonNode ready = ok(send("POST", "/api/jobs/" + id + "/retry", null, null, false));
        assertEquals("prefix", ready.path("config").path("ocr_sampling").asText());
        assertEquals(72, ready.path("config").path("ocr_max_frames").asInt());
        JsonNode next = claim(stage(ready, "MEDIA").path("id").asText());
        assertEquals("prefix", next.path("config").path("ocr_sampling").asText());
        assertEquals(72, next.path("config").path("ocr_max_frames").asInt());
        assertEquals(before.get("config_hash"), metadata(id).get("config_hash"));
        assertEquals(before.get("config_json"), metadata(id).get("config_json")); assertEquals(inputs, inputHashes(id));
    }

    @Test void frameBudgetsAreBoundedIntegersAndInvalidValuesDoNotCreateJobs() throws Exception {
        String video = video();
        for (String value : List.of("null", "true", "false", "0", "-1", "121", "1.0", "1.5", "[]", "{}", "\"16\"", "9223372036854775807")) {
            error(create(video, key(), JSON.readTree("{\"ocr_max_frames\":" + value + "}")), 400, "INVALID_ARGUMENT");
        }
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM analysis_job WHERE video_id=?", Integer.class, video));
    }

    @Test void explicitFrameBudgetsAffectTheFrozenConfigAndEveryInitialStageHash() throws Exception {
        String video = video(), source = jdbc().queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, video);
        Set<String> hashes = new LinkedHashSet<>();
        for (int frames : List.of(1, 16, 120)) {
            String key = key();
            JsonNode job = created(create(video, key, Map.of("ocr_max_frames", frames)));
            String id = job.path("id").asText();
            assertEquals(frames, job.path("config").path("ocr_max_frames").asInt());
            Map<String, Object> expected = new TreeMap<>(legacyConfig());
            expected.put("ocr_sampling", "uniform"); expected.put("ocr_max_frames", frames);
            String configHash = hash(JSON.writeValueAsString(expected));
            assertEquals(configHash, job.path("config_hash").asText());
            assertTrue(hashes.add(configHash));
            for (JsonNode stage : job.path("stages")) {
                assertEquals(hash(source + "\n" + configHash + "\n" + stage.path("stage_type").asText() + "\n0"), stage.path("input_hash").asText());
            }
            byte[] before = getBytes(id);
            assertArrayEquals(before, create(video, key, Map.of("ocr_max_frames", frames)).body());
            error(create(video, key, Map.of("ocr_max_frames", frames == 120 ? 16 : 120)), 409, "IDEMPOTENCY_CONFLICT");
            assertArrayEquals(before, getBytes(id));
        }
    }

    @Test void preBudgetPolicyReceiptsReplayOnlyWhenTheFrameFieldIsOmitted() throws Exception {
        for (String policy : List.of("uniform", "prefix")) {
            Legacy legacy = legacy(policy);
            byte[] before = getBytes(legacy.job()); var frozen = metadata(legacy.job()); var stages = stageRows(legacy.job());
            assertArrayEquals(before, create(legacy.video(), legacy.key(), Map.of("ocr_sampling", policy)).body());
            if (policy.equals("uniform")) {
                assertArrayEquals(before, create(legacy.video(), legacy.key(), Map.of()).body());
                var noConfig = send("POST", "/api/jobs", Map.of("video_id", legacy.video()), legacy.key(), false);
                assertEquals(201, noConfig.statusCode(), text(noConfig)); assertArrayEquals(before, noConfig.body());
            } else { error(create(legacy.video(), legacy.key(), Map.of()), 409, "IDEMPOTENCY_CONFLICT"); }
            for (int frames : List.of(16, 120)) {
                error(create(legacy.video(), legacy.key(), Map.of("ocr_sampling", policy, "ocr_max_frames", frames)), 409, "IDEMPOTENCY_CONFLICT");
            }
            error(create(legacy.video(), legacy.key(), Map.of("ocr_sampling", policy, "max_text_calls", 13)), 409, "IDEMPOTENCY_CONFLICT");
            error(create(video(), legacy.key(), Map.of("ocr_sampling", policy)), 409, "IDEMPOTENCY_CONFLICT");
            assertArrayEquals(before, getBytes(legacy.job())); assertEquals(frozen, metadata(legacy.job())); assertEquals(stages, stageRows(legacy.job()));
        }
    }

    @Test void preBudgetTaskNeverBackfillsFramesAcrossRestartClaimAndRetry() throws Exception {
        Legacy legacy = legacy("uniform");
        byte[] before = getBytes(legacy.job()); var frozen = metadata(legacy.job()); var inputs = inputHashes(legacy.job());
        context.close(); startContext();
        assertArrayEquals(before, getBytes(legacy.job()));
        JsonNode old = claim(stage(JSON.readTree(before), "MEDIA").path("id").asText());
        assertFalse(old.path("config").has("ocr_max_frames"));
        assertEquals("uniform", old.path("config").path("ocr_sampling").asText());
        ok(send("POST", action(old, "fail"), Map.of("attempt", old.path("attempt").asInt(), "run_token", old.path("run_token").asText(), "error_code", "SYNTHETIC_RETRY", "retryable", false), null, true));
        JsonNode ready = ok(send("POST", "/api/jobs/" + legacy.job() + "/retry", null, null, false));
        assertFalse(ready.path("config").has("ocr_max_frames"));
        JsonNode next = claim(stage(ready, "MEDIA").path("id").asText());
        assertEquals(2, next.path("attempt").asInt()); assertFalse(next.path("config").has("ocr_max_frames"));
        for (String field : List.of("config_hash", "request_hash", "config_json")) { assertEquals(frozen.get(field), metadata(legacy.job()).get(field)); }
        assertEquals(inputs, inputHashes(legacy.job()));
    }

    @Test void duplicateKeyRecoveryAlsoPreservesPreBudgetPolicyReceiptsAndRejectsExplicitFrames() throws Exception {
        Legacy legacy = legacy("uniform");
        byte[] before = getBytes(legacy.job()); var frozen = metadata(legacy.job()); var stages = stageRows(legacy.job());
        MISS_RECEIPT_ONCE.set(legacy.key());
        var replay = create(legacy.video(), legacy.key(), Map.of());
        assertEquals(201, replay.statusCode(), text(replay)); assertArrayEquals(before, replay.body());
        assertEquals(1, HIDDEN_RECEIPTS.get());
        MISS_RECEIPT_ONCE.set(legacy.key());
        error(create(legacy.video(), legacy.key(), Map.of("ocr_max_frames", 16)), 409, "IDEMPOTENCY_CONFLICT");
        assertEquals(2, HIDDEN_RECEIPTS.get());
        assertEquals(frozen, metadata(legacy.job())); assertEquals(stages, stageRows(legacy.job()));
    }

    private String video() {
        byte[] header = ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var result = context.getBean(VideoService.class).upload(new MockMultipartFile("file", "sampling.mp4", "video/mp4", header));
        videos.add(result.id()); return result.id();
    }
    private Legacy legacy() { return legacy(null); }
    private Legacy legacy(String policy) {
        String video = video(), job = UUID.randomUUID().toString(), key = key();
        Map<String, Object> values = legacyConfig();
        if (policy != null) { values.put("ocr_sampling", policy); }
        String config = JSON.writeValueAsString(values), configHash = hash(config);
        jdbc().update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,?,'QUEUED',12,1)", job, video, key, hash(video + "\n" + configHash), configHash, config);
        jobs.add(job);
        String source = jdbc().queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, video);
        for (String type : FLOW) jdbc().update("INSERT INTO job_stage (id,job_id,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,?,?,'0',?,?)", UUID.randomUUID().toString(), job, job, type, type.equals("MEDIA") ? "READY" : "BLOCKED", hash(source + "\n" + configHash + "\n" + type + "\n0"));
        return new Legacy(job, video, key);
    }
    private static Map<String, Object> legacyConfig() {
        return new TreeMap<>(Map.of("asr_model", "paraformer-v2", "text_model", "qwen-plus", "visual_provider", "ocr", "max_text_calls", 12, "max_asr_submissions", 1));
    }
    private Map<String, Object> metadata(String job) { return jdbc().queryForMap("SELECT request_hash,config_hash,config_json,state_version,status,created_at,updated_at FROM analysis_job WHERE id=?", job); }
    private List<Map<String, Object>> stageRows(String job) { return jdbc().queryForList("SELECT * FROM job_stage WHERE job_id=? ORDER BY id", job); }
    private Map<String, String> inputHashes(String job) {
        Map<String, String> result = new TreeMap<>();
        for (var row : jdbc().queryForList("SELECT stage_type,input_hash FROM job_stage WHERE job_id=?", job)) result.put((String) row.get("stage_type"), (String) row.get("input_hash"));
        return result;
    }
    private HttpResponse<byte[]> create(String video, String key, Object config) throws Exception { return send("POST", "/api/jobs", Map.of("video_id", video, "config", config), key, false); }
    private JsonNode created(HttpResponse<byte[]> response) { assertEquals(201, response.statusCode(), text(response)); JsonNode result = JSON.readTree(response.body()); jobs.add(result.path("id").asText()); return result; }
    private byte[] getBytes(String id) throws Exception { var response = send("GET", "/api/jobs/" + id, null, null, false); assertEquals(200, response.statusCode(), text(response)); return response.body(); }
    private JsonNode claim(String stage) throws Exception { return ok(send("POST", "/internal/stages/claim", Map.of("stage_id", stage, "worker_id", "sampling-fixture", "claim_request_id", UUID.randomUUID().toString()), null, true)); }
    private static String action(JsonNode claim, String action) { return "/internal/stages/" + claim.path("stage_id").asText() + "/" + action; }
    private static JsonNode stage(JsonNode job, String type) { for (JsonNode stage : job.path("stages")) if (type.equals(stage.path("stage_type").asText())) return stage; throw new AssertionError("Missing stage " + type); }
    private static String key() { return "ocr-sampling-" + UUID.randomUUID(); }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception failure) { throw new AssertionError(failure); } }
    private HttpResponse<byte[]> send(String method, String path, Object body, String key, boolean worker) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10));
        if (key != null) request.header("Idempotency-Key", key);
        if (worker) request.header("X-Worker-Key", WORKER_KEY);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static JsonNode ok(HttpResponse<byte[]> response) { assertEquals(200, response.statusCode(), text(response)); return JSON.readTree(response.body()); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    private static void error(HttpResponse<byte[]> response, int status, String code) { assertEquals(status, response.statusCode(), text(response)); assertEquals(code, JSON.readTree(response.body()).path("error_code").asText()); }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiptRaceFixture {
        @Bean @Primary JdbcTemplate samplingJdbcTemplate(DataSource source) { return new MissOnceJdbcTemplate(source); }
    }
    static class MissOnceJdbcTemplate extends JdbcTemplate {
        MissOnceJdbcTemplate(DataSource source) { super(source); }
        @Override public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            String target = MISS_RECEIPT_ONCE.get();
            if ("SELECT id FROM analysis_job WHERE idempotency_key=?".equals(sql) && args.length == 1 && target != null
                    && target.equals(args[0]) && MISS_RECEIPT_ONCE.compareAndSet(target, null)) {
                HIDDEN_RECEIPTS.incrementAndGet(); return List.of();
            }
            return super.queryForList(sql, elementType, args);
        }
    }
}
