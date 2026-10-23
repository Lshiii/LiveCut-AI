package io.livecut.video;

import static org.junit.jupiter.api.Assertions.*;

import io.livecut.LivecutBackendApplication;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in tests against a dedicated, real MySQL database. No H2 substitutions.
 * Each test removes only the rows whose UUIDs it created.
 */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VideoApiIntegrationTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<String> createdIds = new ArrayList<>();

    @TempDir
    static Path storageRoot;

    private ConfigurableApplicationContext context;
    private URI baseUri;

    @BeforeAll
    void startApplication() {
        startContext();
    }

    @AfterEach
    void removeOwnDatabaseRows() {
        if (context != null && context.isActive()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            for (String id : createdIds) {
                jdbc.update("DELETE FROM video_asset WHERE id = ?", id);
            }
        }
        createdIds.clear();
    }

    @AfterAll
    void stopApplication() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void listsAnEmptyDedicatedDatabaseAndValidatesPagination() throws Exception {
        var response = send("GET", "/api/videos?limit=5&offset=0", null);

        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.path("items").isArray());
        assertEquals(0, body.path("items").size(),
                "LIVECUT_DB_URL must point at a dedicated empty test database");
        assertEquals(5, body.path("limit").asInt());
        assertEquals(0, body.path("offset").asInt());

        for (String query : List.of("limit=0", "limit=101", "offset=-1", "limit=nope", "offset=nope")) {
            assertError(send("GET", "/api/videos?" + query, null), 400, "INVALID_PAGINATION");
        }
    }

    @Test
    void uploadPersistsMetadataAndListsWithPaginationWithoutLeakingStoragePaths() throws Exception {
        byte[] original = VideoStorageTests.mp4Header();
        long initialFileCount = countFiles();
        var response = upload("../../商品.mp4", original);

        assertEquals(201, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        JsonNode uploaded = rememberUpload(response);
        String id = uploaded.path("id").asText();
        assertEquals("商品.mp4", uploaded.path("original_filename").asText());
        assertEquals(original.length, uploaded.path("size_bytes").asLong());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)),
                uploaded.path("content_hash").asText());
        assertEquals("HEADER_CHECKED", uploaded.path("media_validation_status").asText());
        assertEquals("/api/videos/" + id + "/content", uploaded.path("content_url").asText());
        assertDoesNotThrow(() -> Instant.parse(uploaded.path("created_at").asText()));
        assertPublicMetadata(response.body());

        var jdbc = context.getBean(JdbcTemplate.class);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM video_asset WHERE id = ?", Integer.class, id));
        var fetched = send("GET", "/api/videos/" + id, null);
        assertEquals(200, fetched.statusCode());
        assertEquals(uploaded, JSON.readTree(fetched.body()));

        JsonNode list = JSON.readTree(send("GET", "/api/videos?limit=1&offset=0", null).body());
        assertEquals(1, list.path("items").size());
        assertEquals(id, list.path("items").get(0).path("id").asText());
        assertPublicMetadata(JSON.writeValueAsBytes(list));
        JsonNode beyond = JSON.readTree(send("GET", "/api/videos?limit=1&offset=1", null).body());
        assertEquals(0, beyond.path("items").size());
        try (var files = Files.walk(storageRoot)) {
            assertEquals(initialFileCount + 1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void servesCompleteHeadPrefixSuffixAndUnsatisfiableRanges() throws Exception {
        byte[] bytes = VideoStorageTests.mp4Header();
        String id = rememberUpload(upload("sample.mp4", bytes)).path("id").asText();
        String contentPath = "/api/videos/" + id + "/content";

        var complete = send("GET", contentPath, null);
        assertEquals(200, complete.statusCode());
        assertArrayEquals(bytes, complete.body());
        assertEquals("video/mp4", complete.headers().firstValue("content-type").orElseThrow());

        var head = send("HEAD", contentPath, null);
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals(Integer.toString(bytes.length), head.headers().firstValue("content-length").orElseThrow());

        var prefix = send("GET", contentPath, "bytes=0-15");
        assertEquals(206, prefix.statusCode());
        assertArrayEquals(Arrays.copyOfRange(bytes, 0, 16), prefix.body());
        assertEquals("bytes 0-15/" + bytes.length, prefix.headers().firstValue("content-range").orElseThrow());

        var suffix = send("GET", contentPath, "bytes=-8");
        assertEquals(206, suffix.statusCode());
        assertArrayEquals(Arrays.copyOfRange(bytes, bytes.length - 8, bytes.length), suffix.body());
        assertEquals("bytes " + (bytes.length - 8) + "-" + (bytes.length - 1) + "/" + bytes.length,
                suffix.headers().firstValue("content-range").orElseThrow());

        var beyond = send("GET", contentPath, "bytes=999-1000");
        assertEquals(416, beyond.statusCode());
        assertEquals("bytes */" + bytes.length, beyond.headers().firstValue("content-range").orElseThrow());
    }

    @Test
    void sameIdAndContentRemainAvailableAfterApplicationRestart() throws Exception {
        byte[] bytes = VideoStorageTests.mp4Header();
        JsonNode uploaded = rememberUpload(upload("restart.mp4", bytes));
        String id = uploaded.path("id").asText();

        context.close();
        startContext();

        var metadata = send("GET", "/api/videos/" + id, null);
        assertEquals(200, metadata.statusCode());
        assertEquals(uploaded, JSON.readTree(metadata.body()));
        var content = send("GET", "/api/videos/" + id + "/content", null);
        assertEquals(200, content.statusCode());
        assertArrayEquals(bytes, content.body());
    }

    @Test
    void rejectsInvalidUploadsAndIdsWithoutCreatingDatabaseRowsOrFiles() throws Exception {
        int rowCount = context.getBean(JdbcTemplate.class)
                .queryForObject("SELECT COUNT(*) FROM video_asset", Integer.class);
        long fileCount = countFiles();

        assertError(upload("empty.mp4", new byte[0]), 400, "EMPTY_VIDEO");
        assertError(upload("video.exe", VideoStorageTests.mp4Header()), 415, "UNSUPPORTED_VIDEO");
        assertError(upload("fake.mp4", "not a video".getBytes(StandardCharsets.UTF_8)), 415, "INVALID_VIDEO_HEADER");
        assertError(upload("large.mp4", Arrays.copyOf(VideoStorageTests.mp4Header(), 129)), 413, "VIDEO_TOO_LARGE");
        assertError(send("GET", "/api/videos/not-a-uuid", null), 400, "INVALID_VIDEO_ID");
        assertError(send("GET", "/api/videos/" + UUID.randomUUID(), null), 404, "VIDEO_NOT_FOUND");
        assertError(send("GET", "/api/videos/" + UUID.randomUUID() + "/content", null), 404, "VIDEO_NOT_FOUND");

        assertEquals(rowCount, context.getBean(JdbcTemplate.class)
                .queryForObject("SELECT COUNT(*) FROM video_asset", Integer.class));
        assertEquals(fileCount, countFiles());
    }

    @Test
    void retainsCommittedSourceWhenCommitAcknowledgementBecomesDataAccessException() throws Exception {
        var failure = new DataAccessResourceFailureException("injected lost commit acknowledgement");
        assertCommittedSourceSurvives(failure, "commit-data-access");
    }

    @Test
    void retainsCommittedSourceWhenCommitAcknowledgementBecomesTransactionException() throws Exception {
        var failure = new TransactionSystemException("injected uncertain commit acknowledgement");
        assertCommittedSourceSurvives(failure, "commit-transaction");
    }

    private void assertCommittedSourceSurvives(RuntimeException failure, String directory) throws Exception {
        var dataSource = context.getBean(DataSource.class);
        var manager = new JdbcTransactionManager(dataSource) {
            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // Real MySQL commit succeeds before the caller loses its result.
                super.doCommit(status);
                throw failure;
            }
        };
        var storage = new TrackingStorage(storageRoot.resolve(directory), false);
        var repository = context.getBean(VideoRepository.class);
        var service = new VideoService(storage, repository, manager);
        RuntimeException actual = assertThrows(failure.getClass(), () -> service.upload(sampleFile()));
        assertSame(failure, actual);
        String id = storage.lastStored.id();
        createdIds.add(id);
        assertTrue(repository.find(id).isPresent(), "Fault injection must really commit its MySQL row");
        assertArrayEquals(VideoStorageTests.mp4Header(), Files.readAllBytes(storage.resolve(storage.lastStored.sourceKey())));
        assertEquals(0, storage.deleteCalls, "A commit error must not delete the committed row's only source");
    }

    @Test
    void confirmedRollbackCleansSourceAndCleanupFailureDoesNotMaskOriginalError() {
        for (boolean failCleanup : List.of(false, true)) {
            var storage = new TrackingStorage(storageRoot.resolve("rollback-" + failCleanup), failCleanup);
            var jdbc = context.getBean(JdbcTemplate.class);
            var failure = new DataAccessResourceFailureException("injected statement failure after insert");
            var repository = new VideoRepository(jdbc) {
                @Override
                public void insert(VideoStorage.StoredVideo video) {
                    super.insert(video);
                    throw failure;
                }
            };
            var service = new VideoService(storage, repository, new JdbcTransactionManager(context.getBean(DataSource.class)));
            assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.upload(sampleFile())));
            createdIds.add(storage.lastStored.id());
            assertTrue(repository.find(storage.lastStored.id()).isEmpty(), "The real insert must have been rolled back");
            assertEquals(1, storage.deleteCalls);
            assertEquals(failCleanup, Files.exists(storage.resolve(storage.lastStored.sourceKey())));
            assertEquals(failCleanup ? 1 : 0, failure.getSuppressed().length);
        }
    }

    @Test
    void failedTransactionStartCleansUnregisteredSourceWithoutMaskingStartFailure() {
        var failure = new CannotCreateTransactionException("injected transaction startup failure");
        var manager = new JdbcTransactionManager(context.getBean(DataSource.class)) {
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) { throw failure; }
        };
        var storage = new TrackingStorage(storageRoot.resolve("begin-failure"), false);
        var repository = context.getBean(VideoRepository.class);
        var service = new VideoService(storage, repository, manager);
        assertSame(failure, assertThrows(CannotCreateTransactionException.class, () -> service.upload(sampleFile())));
        assertTrue(repository.find(storage.lastStored.id()).isEmpty());
        assertEquals(1, storage.deleteCalls);
        assertFalse(Files.exists(storage.resolve(storage.lastStored.sourceKey())));
    }

    @Test
    void unknownRollbackCompletionRetainsSourceForReconciliation() {
        var rollbackFailure = new DataAccessResourceFailureException("injected uncertain rollback acknowledgement");
        var manager = new JdbcTransactionManager(context.getBean(DataSource.class)) {
            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                super.doRollback(status);
                throw rollbackFailure;
            }
        };
        var storage = new TrackingStorage(storageRoot.resolve("unknown-rollback"), false);
        var repository = new VideoRepository(context.getBean(JdbcTemplate.class)) {
            @Override
            public void insert(VideoStorage.StoredVideo video) {
                super.insert(video);
                throw new DataAccessResourceFailureException("injected operation failure");
            }
        };
        var service = new VideoService(storage, repository, manager);
        assertSame(rollbackFailure, assertThrows(DataAccessResourceFailureException.class, () -> service.upload(sampleFile())));
        createdIds.add(storage.lastStored.id());
        assertTrue(Files.exists(storage.resolve(storage.lastStored.sourceKey())));
        assertEquals(0, storage.deleteCalls, "Unknown completion must retain the source");
    }

    private static MultipartFile sampleFile() {
        return new org.springframework.mock.web.MockMultipartFile("file", "fault.mp4", "video/mp4", VideoStorageTests.mp4Header());
    }

    private static class TrackingStorage extends VideoStorage {
        private final boolean failCleanup;
        private StoredVideo lastStored;
        private int deleteCalls;

        TrackingStorage(Path root, boolean failCleanup) { super(root, 128); this.failCleanup = failCleanup; }

        @Override
        public StoredVideo store(MultipartFile file) {
            lastStored = super.store(file);
            return lastStored;
        }

        @Override
        public void delete(StoredVideo video) {
            deleteCalls++;
            if (failCleanup) { throw new IllegalStateException("injected cleanup failure"); }
            super.delete(video);
        }
    }

    private void startContext() {
        context = new SpringApplicationBuilder(LivecutBackendApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=" + System.getenv("LIVECUT_DB_URL"),
                "--spring.datasource.username=" + env("LIVECUT_DB_USER", "livecut"),
                "--spring.datasource.password=" + env("LIVECUT_DB_PASSWORD", ""),
                "--spring.datasource.hikari.maximum-pool-size=3",
                "--livecut.storage.root=" + storageRoot.toAbsolutePath(),
                "--livecut.storage.max-video-bytes=128",
                "--spring.servlet.multipart.max-file-size=4KB",
                "--spring.servlet.multipart.max-request-size=8KB");
        baseUri = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private HttpResponse<byte[]> upload(String filename, byte[] bytes) throws Exception {
        String boundary = "LiveCutTest" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return http.send(HttpRequest.newBuilder(baseUri.resolve("/api/videos"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> send(String method, String path, String range) throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(20))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (range != null) {
            request.header("Range", range);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode rememberUpload(HttpResponse<byte[]> response) {
        assertEquals(201, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        JsonNode uploaded = JSON.readTree(response.body());
        String id = uploaded.path("id").asText();
        UUID.fromString(id);
        createdIds.add(id);
        return uploaded;
    }

    private void assertError(HttpResponse<byte[]> response, int status, String code) {
        assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        JsonNode error = JSON.readTree(response.body());
        assertEquals(code, error.path("error_code").asText());
        assertTrue(error.path("message").isString());
        assertTrue(error.path("retryable").isBoolean());
        assertFalse(error.path("request_id").asText().isBlank());
        assertPublicMetadata(response.body());
    }

    private void assertPublicMetadata(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        assertFalse(text.contains("source_key"), text);
        assertFalse(text.contains("sourceKey"), text);
        assertFalse(text.contains(storageRoot.toString()), text);
        assertFalse(text.contains(storageRoot.toString().replace("\\", "\\\\")), text);
    }

    private long countFiles() throws Exception {
        try (var paths = Files.walk(storageRoot)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
