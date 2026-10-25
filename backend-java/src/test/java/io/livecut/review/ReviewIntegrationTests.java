package io.livecut.review;

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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Real HTTP + MySQL revision tests using synthetic bytes, without provider calls. */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir static Path storageRoot;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<String> jobIds = new ArrayList<>();
    private final List<String> videoIds = new ArrayList<>();
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private URI base;
    private record Fixture(String jobId, String videoId, Path rawPath, String rawHash, String token) {
        String reviewPath() { return "/api/jobs/" + jobId + "/review"; }
    }

    @BeforeAll
    void start() {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:\\?.*)?"),
                "Review tests require the dedicated livecut_test database");
        context = new SpringApplicationBuilder(LivecutBackendApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--livecut.jobs.scan-enabled=false",
                "--livecut.storage.root=" + storageRoot,
                "--spring.datasource.url=" + System.getenv("LIVECUT_DB_URL"),
                "--spring.datasource.username=" + System.getenv("LIVECUT_DB_USER"),
                "--spring.datasource.password=" + System.getenv("LIVECUT_DB_PASSWORD"));
        jdbc = context.getBean(JdbcTemplate.class);
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
    }

    @AfterEach
    void cleanupOwnRows() {
        for (String id : jobIds) { jdbc.update("DELETE FROM analysis_job WHERE id=?", id); }
        for (String id : videoIds) { jdbc.update("DELETE FROM video_asset WHERE id=?", id); }
        jobIds.clear(); videoIds.clear();
    }

    @AfterAll
    void stop() { if (context != null) { context.close(); } }

    @Test
    void firstReadCreatesOneDraftAndOnlyReturnsPublicEvidence() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode first = ok(send("GET", fixture.reviewPath(), null));
        assertEquals(0, first.path("revision").longValue());
        assertEquals("DRAFT", first.path("status").stringValue());
        assertEquals("model_result", first.path("data_kind").stringValue());
        assertEquals(fixture.rawHash(), first.path("source_hash").stringValue());
        assertEquals(fixture.jobId(), first.path("job_id").stringValue());
        assertEquals(fixture.videoId(), first.path("video_id").stringValue());
        assertEquals(30000, first.path("duration_ms").longValue());
        assertEquals(3, first.path("utterances").size());
        assertEquals("一句可核查证据", first.path("utterances").get(0).path("text").stringValue());
        assertFalse(first.path("segments").get(2).path("enabled").booleanValue());
        assertEquals("IDENTITY_AMBIGUOUS", first.path("predictions").get(1).path("review_reasons").get(0).stringValue());
        String publicJson = JSON.writeValueAsString(first);
        assertFalse(publicJson.contains(fixture.token()));
        assertFalse(publicJson.contains("object_key"));
        assertFalse(publicJson.contains("manifest_key"));
        assertFalse(publicJson.contains("private-frame.jpg"));
        assertEquals(first, ok(send("GET", fixture.reviewPath(), null)));
        assertEquals(1, count("review_document", fixture.jobId()));
        assertEquals(1, count("review_revision", fixture.jobId()));
    }

    @Test
    void fullReplacementSupportsRenamingSplittingDeletingAndManualMissedSegments() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode original = ok(send("GET", fixture.reviewPath(), null));
        ObjectNode update = edit(original);
        ((ObjectNode) update.path("products").get(0)).put("name", "人工修正面膜");
        ((ArrayNode) update.path("products")).add(product("manual_bag", "垃圾袋"));
        ArrayNode segments = (ArrayNode) update.path("segments");
        segments.removeAll();
        segments.add(segment("s1", 1000, 5000, "p1", true));
        segments.add(segment("split_second", 5000, 10000, "p1", true));
        segments.add(segment("missed_bag", 20000, 24000, "manual_bag", true));
        JsonNode saved = ok(send("PUT", fixture.reviewPath(), update));
        assertEquals(1, saved.path("revision").longValue());
        assertEquals("manual", saved.path("products").get(0).path("identity_source").stringValue());
        assertEquals("manual", saved.path("products").get(2).path("identity_source").stringValue());
        assertEquals("model", saved.path("segments").get(0).path("origin").stringValue());
        assertEquals("manual", saved.path("segments").get(0).path("boundary_source").stringValue());
        assertEquals("manual", saved.path("segments").get(1).path("origin").stringValue());
        assertTrue(saved.path("segments").get(1).path("original_prediction_id").isNull());
        assertEquals(original, ok(send("GET", fixture.reviewPath() + "?revision=0", null)));
        assertEquals(fixture.rawHash(), sha(Files.readAllBytes(fixture.rawPath())), "Human edits must not rewrite model output");
    }

    @Test
    void rejectsInvalidMillisecondsReferencesDuplicateIdsAndFieldTypes() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode first = ok(send("GET", fixture.reviewPath(), null));
        List<Consumer<ObjectNode>> invalidEdits = List.of(
                body -> firstSegment(body).put("start_ms", -1),
                body -> firstSegment(body).put("start_ms", 10000),
                body -> firstSegment(body).put("end_ms", 30001),
                body -> firstSegment(body).put("start_ms", 1.25),
                body -> firstSegment(body).put("start_ms", "1000"),
                body -> firstSegment(body).put("enabled", "true"),
                body -> firstSegment(body).put("product_id", "unknown"),
                body -> firstSegment(body).remove("product_id"),
                body -> ((ArrayNode) body.path("products")).add(product("p1", "重复")),
                body -> ((ArrayNode) body.path("segments")).add(segment("s1", 21000, 22000, "p1", true)),
                body -> ((ObjectNode) body.path("products").get(0)).put("name", "  "),
                body -> body.put("base_revision", 0.0),
                body -> body.put("base_revision", "0"),
                body -> body.put("unexpected", true));
        for (Consumer<ObjectNode> mutation : invalidEdits) {
            ObjectNode body = edit(first);
            mutation.accept(body);
            error(send("PUT", fixture.reviewPath(), body), 400, "INVALID_REVIEW");
        }
        assertEquals(1, count("review_revision", fixture.jobId()));
    }

    @Test
    void enabledOverlapIsRejectedWhileDisabledOverlapAndAdjacentIntervalsAreAllowed() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode first = ok(send("GET", fixture.reviewPath(), null));
        ObjectNode edit = edit(first);
        ArrayNode segments = (ArrayNode) edit.path("segments");
        segments.add(segment("overlap", 5000, 6000, "p1", true));
        error(send("PUT", fixture.reviewPath(), edit), 400, "INVALID_REVIEW");
        ((ObjectNode) segments.get(3)).put("enabled", false);
        segments.add(segment("adjacent", 10000, 12000, "p1", true));
        JsonNode saved = ok(send("PUT", fixture.reviewPath(), edit));
        assertEquals(5, saved.path("segments").size());
    }

    @Test
    void concurrentSavesCannotClobberTheSameBaseRevision() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode first = ok(send("GET", fixture.reviewPath(), null));
        ObjectNode a = edit(first);
        ObjectNode b = edit(first);
        ((ObjectNode) a.path("products").get(0)).put("name", "名称A");
        ((ObjectNode) b.path("products").get(0)).put("name", "名称B");
        var responseA = http.sendAsync(request("PUT", fixture.reviewPath(), a), HttpResponse.BodyHandlers.ofByteArray());
        var responseB = http.sendAsync(request("PUT", fixture.reviewPath(), b), HttpResponse.BodyHandlers.ofByteArray());
        CompletableFuture.allOf(responseA, responseB).join();
        List<Integer> statuses = List.of(responseA.join().statusCode(), responseB.join().statusCode());
        assertTrue(statuses.contains(200));
        assertTrue(statuses.contains(409));
        error(responseA.join().statusCode() == 409 ? responseA.join() : responseB.join(), 409, "REVIEW_REVISION_CONFLICT");
        assertEquals(2, count("review_revision", fixture.jobId()));
        assertEquals(1, ok(send("GET", fixture.reviewPath(), null)).path("revision").longValue());
    }

    @Test
    void confirmationBindsExactVersionAndLaterEditsPreserveConfirmedHistory() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode first = ok(send("GET", fixture.reviewPath(), null));
        error(send("POST", fixture.reviewPath() + "/confirm", Map.of("revision", 0)), 409, "REVIEW_UNASSIGNED_SEGMENT");
        ObjectNode edit = edit(first);
        ((ObjectNode) edit.path("segments").get(1)).put("product_id", "p2");
        JsonNode revisionOne = ok(send("PUT", fixture.reviewPath(), edit));
        JsonNode confirmed = ok(send("POST", fixture.reviewPath() + "/confirm", Map.of("revision", 1)));
        assertEquals("CONFIRMED", confirmed.path("status").stringValue());
        assertEquals(confirmed, ok(send("POST", fixture.reviewPath() + "/confirm", Map.of("revision", 1))));
        ObjectNode changed = edit(revisionOne);
        ((ObjectNode) changed.path("products").get(0)).put("name", "确认后新草稿");
        JsonNode draft = ok(send("PUT", fixture.reviewPath(), changed));
        assertEquals(2, draft.path("revision").longValue());
        assertEquals("DRAFT", draft.path("status").stringValue());
        assertEquals(1, draft.path("confirmed_revisions").get(0).longValue());
        assertEquals(confirmed, ok(send("GET", fixture.reviewPath() + "?revision=1", null)));
        error(send("POST", fixture.reviewPath() + "/confirm", Map.of("revision", 1)), 409, "REVIEW_REVISION_CONFLICT");
        error(send("PUT", fixture.reviewPath(), changed), 409, "REVIEW_REVISION_CONFLICT");
        assertEquals(1, count("review_confirmation", fixture.jobId()));
    }

    @Test
    void reviewDownloadRequiresAnExplicitExistingConfirmedRevision() throws Exception {
        Fixture fixture = fixture(null);
        ok(send("GET", fixture.reviewPath(), null));
        error(send("GET", fixture.reviewPath() + "/download", null), 400, "INVALID_ARGUMENT");
        for (String number : List.of("-1", "1.0", "01", "9223372036854775808")) {
            error(send("GET", fixture.reviewPath() + "/download?revision=" + number, null), 400, "INVALID_ARGUMENT");
        }
        error(send("GET", fixture.reviewPath() + "/download?revision=999", null), 404, "REVIEW_REVISION_NOT_FOUND");
        error(send("GET", fixture.reviewPath() + "/download?revision=0", null), 409, "REVIEW_DOWNLOAD_REQUIRES_CONFIRMATION");
        assertEquals(1, count("review_revision", fixture.jobId()));
        assertEquals(0, count("review_confirmation", fixture.jobId()));
    }

    @Test
    void confirmedDownloadPreservesExactHistoricalBytesAndDistinctSourceHashes() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode original = ok(send("GET", fixture.reviewPath(), null));
        ObjectNode edit = edit(original);
        ((ObjectNode) edit.path("products").get(0)).put("name", "审核面膜");
        firstSegment(edit).put("start_ms", 1500);
        ((ObjectNode) edit.path("segments").get(1)).put("product_id", "p2");
        ((ArrayNode) edit.path("products")).add(product("manual_bag", "人工补充垃圾袋"));
        ((ArrayNode) edit.path("segments")).add(segment("manual_added", 21000, 24000, "manual_bag", true));
        JsonNode saved = ok(send("PUT", fixture.reviewPath(), edit));
        ok(send("POST", fixture.reviewPath() + "/confirm", Map.of("revision", 1)));
        String path = fixture.reviewPath() + "/download?revision=1";
        HttpResponse<byte[]> first = send("GET", path, null);
        JsonNode document = ok(first);
        String expectedName = "review-" + fixture.jobId() + "-r1.json";
        assertEquals("attachment; filename=\"" + expectedName + "\"", first.headers().firstValue("Content-Disposition").orElseThrow());
        assertTrue(first.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertEquals("nosniff", first.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertTrue(first.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals(first.body().length, first.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertEquals("\"" + sha(first.body()) + "\"", first.headers().firstValue("ETag").orElseThrow());
        assertEquals("1.0", document.path("schema_version").asText());
        assertEquals("review_revision", document.path("data_kind").asText());
        assertEquals("CONFIRMED", document.path("review_status").asText());
        assertEquals(fixture.jobId(), document.path("job_id").asText());
        assertEquals(fixture.videoId(), document.path("video_id").asText());
        assertEquals(1, document.path("analysis_generation").asInt());
        assertEquals(1, document.path("revision").asLong());
        java.time.Instant.parse(document.path("confirmed_at").asText());
        String sourceVideo = jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, fixture.videoId());
        assertEquals(sourceVideo, document.path("source_video_sha256").asText());
        assertEquals(fixture.rawHash(), document.path("prediction_source_sha256").asText());
        assertNotEquals(sourceVideo, fixture.rawHash());
        assertEquals(saved.path("products"), document.path("products"));
        assertEquals(saved.path("segments"), document.path("segments"));
        String bytes = new String(first.body(), StandardCharsets.UTF_8);
        for (String privateValue : List.of(fixture.token(), "source_json", "manifest_key", "object_key", "private-frame.jpg", "confirmed_revisions", "latest_revision")) {
            assertFalse(bytes.contains(privateValue));
        }
        ObjectNode next = edit(saved);
        ((ObjectNode) next.path("products").get(0)).put("name", "下一版草稿名称");
        firstSegment(next).put("start_ms", 2500);
        ok(send("PUT", fixture.reviewPath(), next));
        assertArrayEquals(first.body(), send("GET", path, null).body());
        error(send("GET", fixture.reviewPath() + "/download?revision=2", null), 409, "REVIEW_DOWNLOAD_REQUIRES_CONFIRMATION");
        ok(send("POST", fixture.reviewPath() + "/confirm", Map.of("revision", 2)));
        assertArrayEquals(first.body(), send("GET", path, null).body(), "Other confirmation metadata must not enter this revision document");
        assertEquals(first.headers().firstValue("ETag"), send("GET", path, null).headers().firstValue("ETag"));
        assertEquals(fixture.rawHash(), sha(Files.readAllBytes(fixture.rawPath())));
    }

    @Test
    void disabledUnassignedSegmentsMayBeConfirmedAndEmptySelectionRemainsExplicit() throws Exception {
        Fixture fixture = fixture(null);
        JsonNode first = ok(send("GET", fixture.reviewPath(), null));
        ObjectNode edit = edit(first);
        ((ObjectNode) edit.path("segments").get(1)).put("enabled", false);
        ok(send("PUT", fixture.reviewPath(), edit));
        assertEquals("CONFIRMED", ok(send("POST", fixture.reviewPath() + "/confirm", Map.of("revision", 1))).path("status").stringValue());
    }

    @Test
    void concurrentInitialReadsCreateOnlyOneRevisionZero() throws Exception {
        Fixture fixture = fixture(null);
        var a = http.sendAsync(request("GET", fixture.reviewPath(), null), HttpResponse.BodyHandlers.ofByteArray());
        var b = http.sendAsync(request("GET", fixture.reviewPath(), null), HttpResponse.BodyHandlers.ofByteArray());
        CompletableFuture.allOf(a, b).join();
        assertEquals(ok(a.join()), ok(b.join()));
        assertEquals(1, count("review_document", fixture.jobId()));
        assertEquals(1, count("review_revision", fixture.jobId()));
    }

    @Test
    void rejectsNonReadyJobWrongIdentityAndSyntheticProvenanceWithoutInitializingReview() throws Exception {
        Fixture queued = fixture(null);
        jdbc.update("UPDATE analysis_job SET status='RUNNING' WHERE id=?", queued.jobId());
        error(send("GET", queued.reviewPath(), null), 409, "REVIEW_NOT_READY");
        assertEquals(0, count("review_document", queued.jobId()));
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                raw -> raw.put("job_id", UUID.randomUUID().toString()),
                raw -> raw.put("video_id", UUID.randomUUID().toString()),
                raw -> raw.put("analysis_generation", 99),
                raw -> raw.put("data_kind", "synthetic_fixture"),
                raw -> raw.put("review_status", "CONFIRMED"))) {
            Fixture invalid = fixture(mutation);
            error(send("GET", invalid.reviewPath(), null), 409, "INVALID_REVIEW_SOURCE");
            assertEquals(0, count("review_document", invalid.jobId()));
        }
    }

    @Test
    void sourceHashIsVerifiedBeforeInitializingAndStoredSnapshotDoesNotFollowLaterFileMutation() throws Exception {
        Fixture tampered = fixture(null);
        Files.writeString(tampered.rawPath(), "{}", StandardCharsets.UTF_8);
        error(send("GET", tampered.reviewPath(), null), 409, "ARTIFACT_HASH_MISMATCH");
        assertEquals(0, count("review_document", tampered.jobId()));
        Fixture initialized = fixture(null);
        JsonNode first = ok(send("GET", initialized.reviewPath(), null));
        Files.writeString(initialized.rawPath(), "{}", StandardCharsets.UTF_8);
        assertEquals(first, ok(send("GET", initialized.reviewPath(), null)), "Review uses the preserved verified source snapshot");
    }

    @Test
    void missingHistoryMalformedRevisionAndUnknownJobsAreExplicit() throws Exception {
        Fixture fixture = fixture(null);
        error(send("GET", fixture.reviewPath() + "?revision=999", null), 404, "REVIEW_REVISION_NOT_FOUND");
        error(send("GET", fixture.reviewPath() + "?revision=-1", null), 400, "INVALID_ARGUMENT");
        error(send("GET", fixture.reviewPath() + "?revision=1.0", null), 400, "INVALID_ARGUMENT");
        error(send("POST", fixture.reviewPath() + "/confirm", Map.of("revision", "0")), 400, "INVALID_REVIEW");
        error(send("GET", "/api/jobs/" + UUID.randomUUID() + "/review", null), 404, "JOB_NOT_FOUND");
        error(send("GET", "/api/jobs/not-a-uuid/review", null), 400, "INVALID_REVIEW");
    }

    private Fixture fixture(Consumer<ObjectNode> mutate) throws Exception {
        byte[] header = ByteBuffer.allocate(32).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII))
                .putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        var asset = context.getBean(VideoService.class).upload(new MockMultipartFile("file", "review-synthetic.mp4", "video/mp4", header));
        videoIds.add(asset.id());
        String jobId = UUID.randomUUID().toString();
        String stageId = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        String prefix = "jobs/" + jobId + "/stages/" + stageId + "/attempts/1/" + token + "/";
        String rawKey = prefix + "analysis-result.json";
        String manifestKey = prefix + "manifest.json";
        ObjectNode raw = JSON.createObjectNode();
        raw.put("schema_version", "2.0").put("data_kind", "model_result").put("review_status", "DRAFT")
                .put("job_id", jobId).put("video_id", asset.id()).put("analysis_generation", 1).put("duration_ms", 30000);
        raw.set("products", JSON.valueToTree(List.of(Map.of("product_id", "p1", "name", "面膜"), Map.of("product_id", "p2", "name", "面霜"))));
        ArrayNode utterances = raw.putArray("utterances");
        for (int i = 1; i <= 3; i++) {
            utterances.addObject().put("utterance_id", "u" + i).put("start_ms", (i - 1) * 10000)
                    .put("end_ms", i * 10000).put("text", "一句可核查证据").put("source_chunk_id", "c0");
        }
        ArrayNode segments = raw.putArray("segments");
        segments.add(originalSegment("s1", 1000, 10000, "product_talk", "p1", "identified", "u1"));
        segments.add(originalSegment("s2", 12000, 20000, "product_talk", null, "ambiguous", "u2"));
        segments.add(originalSegment("s3", 25000, 30000, "non_product", null, "not_applicable", "u3"));
        raw.putArray("frames").addObject().put("frame_id", "f1").put("timestamp_ms", 5000)
                .put("object_key", prefix + "private-frame.jpg");
        ((ObjectNode) segments.get(0)).putArray("visual_observations").addObject().put("frame_id", "f1")
                .put("object_key", prefix + "private-frame.jpg");
        if (mutate != null) { mutate.accept(raw); }
        byte[] rawBytes = JSON.writeValueAsBytes(raw);
        Path rawPath = storageRoot.resolve(rawKey);
        Files.createDirectories(rawPath.getParent());
        Files.write(rawPath, rawBytes);
        String rawHash = sha(rawBytes);
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schema_version", 1).put("job_id", jobId).put("video_id", asset.id()).put("analysis_generation", 1)
                .put("stage_id", stageId).put("stage_type", "TIMELINE").put("attempt", 1).put("run_token", token)
                .put("input_sha256", asset.contentHash()).put("data_kind", "model_result").put("review_status", "DRAFT");
        manifest.putArray("artifacts").addObject().put("kind", "analysis_result").put("key", rawKey).put("sha256", rawHash);
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
        Files.write(storageRoot.resolve(manifestKey), manifestBytes);
        String hash = "a".repeat(64);
        jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,'{}','REVIEW_READY',12,1)",
                jobId, asset.id(), UUID.randomUUID().toString(), hash, hash);
        jobIds.add(jobId);
        jdbc.update("INSERT INTO job_stage (id,job_id,stage_type,status,attempt,run_token,input_hash,manifest_key,manifest_hash) VALUES (?,?,'TIMELINE','SUCCEEDED',1,?,?,?,?)",
                stageId, jobId, token, hash, manifestKey, sha(manifestBytes));
        return new Fixture(jobId, asset.id(), rawPath, rawHash, token);
    }

    private static ObjectNode originalSegment(String id, long start, long end, String kind, String product, String identity, String utterance) {
        ObjectNode result = JSON.createObjectNode();
        result.put("segment_id", id).put("start_ms", start).put("end_ms", end).put("content_kind", kind).put("visual_status", "not_checked");
        ObjectNode identityNode = result.putObject("identity").put("status", identity);
        if (product == null) { identityNode.putNull("product_id"); } else { identityNode.put("product_id", product); }
        result.putArray("audio_evidence_utterance_ids").add(utterance);
        ArrayNode reasons = result.putArray("review_reasons");
        if ("ambiguous".equals(identity)) { reasons.add("IDENTITY_AMBIGUOUS"); }
        return result;
    }

    private static ObjectNode edit(JsonNode snapshot) {
        ObjectNode result = JSON.createObjectNode().put("base_revision", snapshot.path("revision").longValue());
        ArrayNode products = result.putArray("products");
        for (JsonNode value : snapshot.path("products")) { products.add(product(value.path("id").stringValue(), value.path("name").stringValue())); }
        ArrayNode segments = result.putArray("segments");
        for (JsonNode value : snapshot.path("segments")) {
            segments.add(segment(value.path("id").stringValue(), value.path("start_ms").longValue(), value.path("end_ms").longValue(),
                    value.path("product_id").isNull() ? null : value.path("product_id").stringValue(), value.path("enabled").booleanValue()));
        }
        return result;
    }

    private static ObjectNode product(String id, String name) { return JSON.createObjectNode().put("id", id).put("name", name); }
    private static ObjectNode segment(String id, long start, long end, String product, boolean enabled) {
        ObjectNode result = JSON.createObjectNode().put("id", id).put("start_ms", start).put("end_ms", end).put("enabled", enabled);
        if (product == null) { result.putNull("product_id"); } else { result.put("product_id", product); }
        return result;
    }
    private static ObjectNode firstSegment(ObjectNode body) { return (ObjectNode) body.path("segments").get(0); }
    private int count(String table, String jobId) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE job_id=?", Integer.class, jobId); }
    private HttpRequest request(String method, String path, Object body) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
    }
    private HttpResponse<byte[]> send(String method, String path, Object body) throws Exception { return http.send(request(method, path, body), HttpResponse.BodyHandlers.ofByteArray()); }
    private static JsonNode ok(HttpResponse<byte[]> response) {
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        return JSON.readTree(response.body());
    }
    private static void error(HttpResponse<byte[]> response, int status, String code) {
        assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(code, JSON.readTree(response.body()).path("error_code").stringValue());
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
