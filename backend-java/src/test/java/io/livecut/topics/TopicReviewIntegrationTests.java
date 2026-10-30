package io.livecut.topics;

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
import java.util.function.Consumer;

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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic protocol fixtures verify persistence, not provider or media quality. */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicReviewIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WORKER_KEY = "topic-protocol-test-worker-only";
    @TempDir static Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> jobs = new ArrayList<>(), videos = new ArrayList<>();
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private URI base;
    private record Fixture(String job, String video, String sourceHash, Path raw) {
        String review() { return "/api/jobs/" + job + "/topic-review"; }
    }

    @BeforeAll void start() {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"), "Topics require a dedicated test schema");
        startContext();
    }
    private void startContext() {
        context = new SpringApplicationBuilder(LivecutBackendApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--livecut.storage.root=" + root,
                "--livecut.worker.api-key=" + WORKER_KEY, "--livecut.jobs.scan-enabled=false", "--livecut.workers.require-registration=false",
                "--livecut.uploads.scan-enabled=false", "--livecut.events.scan-enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
        jdbc = context.getBean(JdbcTemplate.class);
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanupOwnRows() {
        for (String job : jobs) jdbc.update("DELETE FROM analysis_job WHERE id=?", job);
        for (String video : videos) jdbc.update("DELETE FROM video_asset WHERE id=?", video);
        jobs.clear(); videos.clear();
    }
    @AfterAll void stop() { if (context != null) context.close(); }

    @Test void predictionsStartUnselectedAndOnlyExposePublicTimeAnchors() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode draft = get(fixture.review());
        assertEquals("topics", draft.path("analysis_mode").asText());
        assertEquals("model_result", draft.path("data_kind").asText());
        assertEquals("DRAFT", draft.path("status").asText());
        assertEquals(0, draft.path("revision").longValue());
        assertEquals(fixture.sourceHash(), draft.path("source_hash").asText());
        assertEquals(2, draft.path("segments").size());
        for (JsonNode segment : draft.path("segments")) assertFalse(segment.path("enabled").booleanValue());
        assertEquals("缓存失效策略", draft.path("segments").get(0).path("title").asText());
        assertEquals(0, draft.path("segments").get(0).path("start_ms").longValue());
        assertEquals(10000, draft.path("segments").get(0).path("end_ms").longValue());
        String body = JSON.writeValueAsString(draft);
        for (String privateField : List.of("products", "product_id", "object_key", "manifest_key", "run_token", "jobs/")) assertFalse(body.contains(privateField));
        error(send("POST", fixture.review() + "/confirm", Map.of("revision", 0), false), 409, "REVIEW_EMPTY_SELECTION");
        error(send("GET", fixture.review() + "/download?revision=0", null, false), 409, "REVIEW_DOWNLOAD_REQUIRES_CONFIRMATION");
        error(send("GET", "/api/jobs/" + fixture.job() + "/review", null, false), 409, "REVIEW_MODE_UNSUPPORTED");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review_revision WHERE job_id=?", Integer.class, fixture.job()));
    }

    @Test void editsPreserveSourceAndConfirmationIsAnImmutableDownloadableRevision() throws Exception {
        Fixture fixture = fixture(null);
        ObjectNode edit = edit(get(fixture.review()), true);
        ObjectNode one = (ObjectNode) edit.path("segments").get(0);
        one.put("title", "人工标题").put("start_ms", 500).put("end_ms", 9000);
        JsonNode saved = ok(send("PUT", fixture.review(), edit, false));
        assertEquals(1, saved.path("revision").longValue());
        assertEquals("model", saved.path("segments").get(0).path("origin").asText());
        assertEquals("manual", saved.path("segments").get(0).path("boundary_source").asText());
        assertEquals("manual", saved.path("segments").get(0).path("content_source").asText());
        ok(send("POST", fixture.review() + "/confirm", Map.of("revision", 1), false));
        var download = send("GET", fixture.review() + "/download?revision=1", null, false);
        assertEquals(200, download.statusCode());
        assertEquals("attachment; filename=\"topic-review-" + fixture.job() + "-r1.json\"", download.headers().firstValue("content-disposition").orElseThrow());
        assertEquals("\"" + sha(download.body()) + "\"", download.headers().firstValue("etag").orElseThrow());
        JsonNode frozen = JSON.readTree(download.body());
        assertEquals("topic_review_revision", frozen.path("data_kind").asText());
        assertEquals("CONFIRMED", frozen.path("review_status").asText());
        assertEquals(fixture.sourceHash(), frozen.path("prediction_source_sha256").asText());
        ObjectNode next = edit(saved, true); ((ObjectNode) next.path("segments").get(0)).put("title", "第二次修改");
        ok(send("PUT", fixture.review(), next, false));
        assertEquals("DRAFT", get(fixture.review()).path("status").asText());
        assertEquals("缓存失效策略", get(fixture.review() + "?revision=0").path("segments").get(0).path("title").asText());
        assertArrayEquals(download.body(), send("GET", fixture.review() + "/download?revision=1", null, false).body());
        assertEquals(fixture.sourceHash(), sha(Files.readAllBytes(fixture.raw())));
        context.close(); startContext();
        assertArrayEquals(download.body(), send("GET", fixture.review() + "/download?revision=1", null, false).body());
        assertEquals(2, get(fixture.review()).path("revision").longValue());
    }

    @Test void invalidEditsDoNotConsumeRevisionsAndManualSplitRetainsItsOrigin() throws Exception {
        Fixture fixture = fixture(null); JsonNode initial = get(fixture.review());
        List<Consumer<ObjectNode>> invalid = List.of(
                body -> body.put("extra", true),
                body -> ((ObjectNode) body.path("segments").get(0)).put("enabled", "true"),
                body -> ((ObjectNode) body.path("segments").get(0)).put("title", " "),
                body -> ((ObjectNode) body.path("segments").get(0)).put("start_ms", -1),
                body -> ((ObjectNode) body.path("segments").get(0)).put("end_ms", 30001),
                body -> ((ObjectNode) body.path("segments").get(1)).put("id", "t1"),
                body -> ((ObjectNode) body.path("segments").get(1)).put("start_ms", 9000),
                body -> ((ObjectNode) body.path("segments").get(0)).putArray("keywords").add("重复").add("重复"));
        for (var mutation : invalid) {
            ObjectNode body = edit(initial, true); mutation.accept(body);
            error(send("PUT", fixture.review(), body, false), 400, "INVALID_TOPIC_REVIEW");
        }
        assertEquals(0, get(fixture.review()).path("revision").longValue());
        ObjectNode split = edit(initial, true);
        ((ObjectNode) split.path("segments").get(0)).put("end_ms", 5000);
        ObjectNode manual = ((ObjectNode) split.path("segments").get(0)).deepCopy();
        manual.put("id", "manual:split.1").put("start_ms", 5000).put("end_ms", 10000);
        ((tools.jackson.databind.node.ArrayNode) split.path("segments")).add(manual);
        JsonNode saved = ok(send("PUT", fixture.review(), split, false));
        JsonNode added = saved.path("segments").get(2);
        assertEquals("manual", added.path("origin").asText());
        assertEquals("manual", added.path("boundary_source").asText());
        assertTrue(added.path("original_prediction_id").isNull());
        error(send("PUT", fixture.review(), split, false), 409, "REVIEW_REVISION_CONFLICT");
        error(send("POST", fixture.review() + "/confirm", Map.of("revision", 0), false), 409, "REVIEW_REVISION_CONFLICT");
    }

    @Test void concurrentEditorsHaveOneWinnerAndCannotOverwriteARevision() throws Exception {
        Fixture fixture = fixture(null); ObjectNode edit = edit(get(fixture.review()), true);
        var one = CompletableFuture.supplyAsync(() -> uncheckedSave(fixture.review(), edit));
        var two = CompletableFuture.supplyAsync(() -> uncheckedSave(fixture.review(), edit));
        assertEquals(List.of(200, 409), List.of(one.join().statusCode(), two.join().statusCode()).stream().sorted().toList());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM review_revision WHERE job_id=?", Integer.class, fixture.job()));
    }

    @Test void publishedResultsMustBindIdentityStructureAndCompleteContinuousAsrCoverage() throws Exception {
        List<Consumer<ObjectNode>> invalid = List.of(
                raw -> raw.put("schema_version", 1.0),
                raw -> raw.put("job_id", UUID.randomUUID().toString()),
                raw -> raw.put("analysis_generation", 2),
                raw -> raw.put("pipeline_version", "unknown"),
                raw -> raw.put("products", "fake"),
                raw -> raw.remove("provenance"),
                raw -> ((ObjectNode) raw.path("provenance")).put("config_hash", "wrong"),
                raw -> ((ObjectNode) raw.path("segments").get(0)).put("start_ms", 1),
                raw -> ((ObjectNode) raw.path("segments").get(0)).put("end_ms", 9999),
                raw -> ((ObjectNode) raw.path("segments").get(0)).putArray("audio_evidence_utterance_ids").add("foreign"),
                raw -> ((ObjectNode) raw.path("segments").get(1)).put("segment_id", "t1"),
                raw -> ((tools.jackson.databind.node.ArrayNode) raw.path("segments")).remove(1),
                raw -> ((ObjectNode) raw.path("utterances").get(1)).put("utterance_id", "u1"),
                raw -> ((ObjectNode) raw.path("utterances").get(1)).put("source_chunk_id", " c0 "),
                raw -> ((ObjectNode) raw.path("utterances").get(1)).put("start_ms", 30001),
                raw -> {
                    ((ObjectNode) raw.path("utterances").get(1)).put("start_ms", 9000);
                    ((ObjectNode) raw.path("segments").get(1)).put("start_ms", 9000);
                },
                raw -> ((ObjectNode) raw.path("frames").get(0)).put("timestamp_ms", 30000));
        for (var mutation : invalid) {
            Fixture fixture = fixture(mutation);
            error(send("GET", fixture.review(), null, false), 409, "REVIEW_SOURCE_INVALID");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review_document WHERE job_id=?", Integer.class, fixture.job()));
        }
        Fixture tampered = fixture(null); byte[] bytes = Files.readAllBytes(tampered.raw()); bytes[bytes.length - 2] ^= 1; Files.write(tampered.raw(), bytes);
        error(send("GET", tampered.review(), null, false), 409, "ARTIFACT_HASH_MISMATCH");
    }

    @Test void confirmedTopicsExportTitlesWithoutFabricatedProductsAndFreezeOldSelections() throws Exception {
        Fixture fixture = fixture(null); JsonNode draft = get(fixture.review());
        error(send("POST", "/api/exports", export(fixture, 0, List.of("t1")), false), 409, "EXPORT_REQUIRES_CONFIRMATION");
        JsonNode saved = ok(send("PUT", fixture.review(), edit(draft, true), false));
        ok(send("POST", fixture.review() + "/confirm", Map.of("revision", 1), false));
        JsonNode batch = created(send("POST", "/api/exports", export(fixture, 1, List.of("t2", "t1")), false));
        assertEquals(2, batch.path("clips").size());
        JsonNode clip = batch.path("clips").get(0);
        assertEquals("缓存失效策略", clip.path("title").asText());
        assertTrue(clip.path("product_id").isNull()); assertTrue(clip.path("product_name").isNull());
        assertEquals(batch.path("id"), created(send("POST", "/api/exports", export(fixture, 1, List.of("t1", "t2")), false)).path("id"));
        ObjectNode next = edit(saved, true); ((ObjectNode) next.path("segments").get(0)).put("title", "新的讨论标题");
        ok(send("PUT", fixture.review(), next, false));
        assertEquals(batch, get("/api/exports/" + batch.path("id").asText()));
        assertEquals(batch.path("id"), created(send("POST", "/api/exports", export(fixture, 1, List.of("t1", "t2")), false)).path("id"));
        error(send("POST", "/api/exports", export(fixture, 2, List.of("t1")), false), 409, "EXPORT_REQUIRES_CONFIRMATION");
        JsonNode claim = ok(send("POST", "/internal/stages/claim", Map.of("stage_id", clip.path("stage_id").asText(), "worker_id", "topic-synthetic", "claim_request_id", UUID.randomUUID().toString()), true));
        assertEquals(0, claim.path("config").path("export").path("start_ms").longValue());
        assertEquals(10000, claim.path("config").path("export").path("end_ms").longValue());
        assertFalse(claim.path("config").path("export").has("product_id"));
        var manifest = get("/api/exports/" + batch.path("id").asText() + "/manifest");
        assertEquals("缓存失效策略", manifest.path("clips").get(0).path("title").asText());
        assertTrue(manifest.path("clips").get(0).path("product_name").isNull());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, fixture.job()));
    }

    private Fixture fixture(Consumer<ObjectNode> mutate) throws Exception {
        byte[] header = ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var video = context.getBean(VideoService.class).upload(new MockMultipartFile("file", "topic-synthetic.mp4", "video/mp4", header));
        videos.add(video.id()); String job = UUID.randomUUID().toString(), stage = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        String prefix = "jobs/" + job + "/stages/" + stage + "/attempts/1/" + token + "/";
        ObjectNode raw = JSON.createObjectNode().put("schema_version", "1.0").put("analysis_mode", "topics").put("data_kind", "model_result")
                .put("job_id", job).put("video_id", video.id()).put("analysis_generation", 1).put("duration_ms", 30000)
                .put("transcript_version", "synthetic-v1").put("pipeline_version", "topics-text-v1").put("review_status", "DRAFT");
        raw.putObject("provenance").put("asr_model_id", "synthetic-asr").put("text_model_id", "synthetic-text").put("prompt_version", "synthetic-v1").put("config_hash", "a".repeat(64));
        var utterances = raw.putArray("utterances");
        for (int i = 1; i <= 3; i++) utterances.addObject().put("utterance_id", "u" + i).put("start_ms", (i - 1) * 10000).put("end_ms", i * 10000).put("text", "合成协议语句" + i).put("source_chunk_id", "c0");
        var segments = raw.putArray("segments");
        var first = segments.addObject().put("segment_id", "t1").put("title", "缓存失效策略").put("summary", "讨论缓存失效的处理方式").put("start_ms", 0).put("end_ms", 10000).put("start_utterance_id", "u1").put("end_utterance_id", "u1");
        first.putArray("keywords").add("缓存"); first.putArray("audio_evidence_utterance_ids").add("u1");
        var second = segments.addObject().put("segment_id", "t2").put("title", "缓存击穿处理").put("summary", "讨论热点失效和并发控制").put("start_ms", 10000).put("end_ms", 30000).put("start_utterance_id", "u2").put("end_utterance_id", "u3");
        second.putArray("keywords").add("并发"); second.putArray("audio_evidence_utterance_ids").add("u2").add("u3");
        raw.putArray("frames").addObject().put("frame_id", "f1").put("timestamp_ms", 5000).put("object_key", prefix + "private-frame.jpg");
        if (mutate != null) mutate.accept(raw);
        byte[] bytes = JSON.writeValueAsBytes(raw); write(prefix + "analysis-result.json", bytes);
        ObjectNode manifest = JSON.createObjectNode().put("schema_version", 1).put("job_id", job).put("video_id", video.id()).put("analysis_generation", 1)
                .put("stage_id", stage).put("stage_type", "TIMELINE").put("attempt", 1).put("run_token", token)
                .put("input_sha256", video.contentHash()).put("data_kind", "model_result").put("review_status", "DRAFT");
        manifest.putArray("artifacts").addObject().put("kind", "analysis_result").put("key", prefix + "analysis-result.json").put("sha256", sha(bytes));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest); write(prefix + "manifest.json", manifestBytes);
        jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,?,'REVIEW_READY',24,1)",
                job, video.id(), UUID.randomUUID().toString(), sha(bytes), sha(bytes), "{\"analysis_mode\":\"topics\"}");
        jobs.add(job);
        jdbc.update("INSERT INTO job_stage (id,job_id,owner_id,stage_type,status,attempt,run_token,input_hash,manifest_key,manifest_hash) VALUES (?,?,?,'TIMELINE','SUCCEEDED',1,?,?,?,?)",
                stage, job, job, token, video.contentHash(), prefix + "manifest.json", sha(manifestBytes));
        return new Fixture(job, video.id(), sha(bytes), root.resolve(prefix + "analysis-result.json"));
    }
    private static ObjectNode edit(JsonNode snapshot, boolean enabled) {
        ObjectNode body = JSON.createObjectNode().put("base_revision", snapshot.path("revision").longValue());
        var segments = body.putArray("segments");
        for (JsonNode row : snapshot.path("segments")) {
            ObjectNode value = segments.addObject().put("id", row.path("id").asText()).put("start_ms", row.path("start_ms").longValue()).put("end_ms", row.path("end_ms").longValue())
                    .put("title", row.path("title").asText()).put("summary", row.path("summary").asText()).put("enabled", enabled);
            value.set("keywords", row.path("keywords").deepCopy());
        }
        return body;
    }
    private static Map<String, Object> export(Fixture fixture, long revision, List<String> ids) { return Map.of("job_id", fixture.job(), "approved_revision", revision, "segment_ids", ids, "profile", "h264-aac-v1"); }
    private JsonNode get(String path) throws Exception { return ok(send("GET", path, null, false)); }
    private HttpResponse<byte[]> uncheckedSave(String path, Object body) { try { return send("PUT", path, body, false); } catch (Exception failure) { throw new RuntimeException(failure); } }
    private HttpResponse<byte[]> send(String method, String path, Object body, boolean worker) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15));
        if (worker) request.header("X-Worker-Key", WORKER_KEY);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static JsonNode ok(HttpResponse<byte[]> response) { assertEquals(200, response.statusCode(), text(response)); return JSON.readTree(response.body()); }
    private static JsonNode created(HttpResponse<byte[]> response) { assertEquals(201, response.statusCode(), text(response)); return JSON.readTree(response.body()); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    private static void error(HttpResponse<byte[]> response, int status, String code) { assertEquals(status, response.statusCode(), text(response)); assertEquals(code, JSON.readTree(response.body()).path("error_code").asText()); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void write(String key, byte[] bytes) throws Exception { Path path = root.resolve(key); Files.createDirectories(path.getParent()); Files.write(path, bytes); }
}
