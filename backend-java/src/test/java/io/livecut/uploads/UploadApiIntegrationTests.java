package io.livecut.uploads;

import static org.junit.jupiter.api.Assertions.*;

import io.livecut.LivecutBackendApplication;
import io.livecut.video.VideoConfiguration;
import io.livecut.video.VideoStorage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;

/** Real HTTP and MySQL; synthetic MP4 headers are not media-quality evidence. */
@EnabledIfEnvironmentVariable(named = "LIVECUT_DB_URL", matches = "jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadApiIntegrationTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CHUNK = 16;
    private static final Faults FAULTS = new Faults();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Set<String> owned = new LinkedHashSet<>();
    @TempDir static Path storageRoot;
    private ConfigurableApplicationContext context;
    private URI base;

    @BeforeAll
    void start() {
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"),
                "Upload tests require the dedicated livecut_test database");
        startContext();
    }

    @AfterEach
    void removeOnlyOwnRows() throws Exception {
        FAULTS.reset();
        if (context == null || !context.isActive()) return;
        for (String id : List.copyOf(owned)) {
            // Cleanup fixtures independently of the behavior under test. The
            // same parent lock fences a late test merge before removing only
            // this class's UUID rows; no shared table or capacity is reset.
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
                jdbc().queryForMap("SELECT * FROM upload_capacity WHERE id=1 FOR UPDATE");
                var sessions = jdbc().queryForList("SELECT * FROM upload_session WHERE id=? FOR UPDATE", id);
                if (sessions.isEmpty()) return null;
                var session = sessions.getFirst();
                Object reserved = session.get("capacity_reserved");
                boolean charged = reserved instanceof Boolean flag ? flag : ((Number) reserved).intValue() != 0;
                if (charged) {
                    jdbc().update("UPDATE upload_capacity SET active_sessions=active_sessions-1,reserved_bytes=reserved_bytes-? WHERE id=1",
                            ((Number) session.get("reservation_bytes")).longValue());
                }
                jdbc().update("DELETE FROM upload_part WHERE upload_id=?", id);
                jdbc().update("DELETE FROM upload_session WHERE id=?", id);
                jdbc().update("DELETE FROM video_asset WHERE id=?", session.get("video_id"));
                return null;
            });
        }
        owned.clear();
    }

    @AfterAll
    void stop() { if (context != null) context.close(); }

    @Test
    void initializationBindsIdentityAndReplaysTheSameReceipt() throws Exception {
        byte[] bytes = mp4(81);
        String key = "upload-init-" + UUID.randomUUID();
        var first = initialize(key, "../../商品.mp4", bytes.length, hash(bytes));
        assertEquals(201, first.statusCode(), text(first));
        JsonNode upload = remember(first);
        assertEquals("商品.mp4", upload.path("original_filename").asText());
        assertEquals("UPLOADING", upload.path("status").asText());
        assertEquals(CHUNK, upload.path("chunk_size").asInt());
        assertEquals(6, upload.path("total_parts").asInt());
        assertEquals(0, upload.path("received_bytes").asLong());
        assertTrue(upload.path("received_parts").isArray());
        assertEquals(0, upload.path("received_parts").size());
        assertTrue(upload.path("video").isNull());
        assertTrue(upload.path("video_id").isNull());
        assertPublic(upload);
        var second = initialize(key, "商品.mp4", bytes.length, hash(bytes));
        assertTrue(second.statusCode() == 200 || second.statusCode() == 201, text(second));
        assertEquals(upload, json(second));
        error(initialize(key, "商品.mp4", bytes.length, "f".repeat(64)), 409, "IDEMPOTENCY_CONFLICT");
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM upload_session WHERE id=?", Integer.class, upload.path("id").asText()));
    }

    @Test
    void invalidInitializationNeverCreatesAnUpload() throws Exception {
        var missingKey = request("POST", "/api/uploads", JSON.writeValueAsBytes(Map.of(
                "original_filename", "x.mp4", "size_bytes", 48, "content_hash", "a".repeat(64))), "application/json");
        assertEquals(400, missingKey.statusCode(), text(missingKey));
        assertEquals(400, initialize("x-" + UUID.randomUUID(), "x.mp4", 0, "a".repeat(64)).statusCode());
        assertEquals(400, initialize("x-" + UUID.randomUUID(), "x.mp4", 48, "not-a-sha256").statusCode());
        assertEquals(415, initialize("x-" + UUID.randomUUID(), "x.txt", 48, "a".repeat(64)).statusCode());
        var wrongTypes = http.send(HttpRequest.newBuilder(base.resolve("/api/uploads"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "x-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString("{\"original_filename\":\"x.mp4\",\"size_bytes\":1.5,\"content_hash\":\"" + "a".repeat(64) + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(400, wrongTypes.statusCode(), text(wrongTypes));
    }

    @Test
    void concurrentOutOfOrderAndDuplicatePartsHaveOneDurableReceiptEach() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        List<CompletableFuture<HttpResponse<byte[]>>> writes = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(3)) {
            for (int index : new int[] {5, 2, 0, 4, 1, 3, 2, 0}) {
                writes.add(CompletableFuture.supplyAsync(() -> {
                    try { return sendPart(id, index, part(bytes, index), hash(part(bytes, index))); }
                    catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
                }, workers));
            }
            for (var future : writes) {
                var result = future.join();
                assertEquals(200, result.statusCode(), text(result));
            }
        }
        JsonNode snapshot = get(id);
        assertEquals(81, snapshot.path("received_bytes").asLong());
        assertEquals(6, snapshot.path("received_parts").size());
        assertEquals(6, jdbc().queryForObject("SELECT COUNT(*) FROM upload_part WHERE upload_id=?", Integer.class, id));
        for (int index = 0; index < 6; index++) {
            JsonNode receipt = snapshot.path("received_parts").get(index);
            assertEquals(index, receipt.path("index").asInt());
            assertEquals(part(bytes, index).length, receipt.path("size_bytes").asInt());
            assertEquals(hash(part(bytes, index)), receipt.path("sha256").asText());
        }
        long version = snapshot.path("state_version").asLong();
        assertEquals(200, sendPart(id, 0, part(bytes, 0), hash(part(bytes, 0))).statusCode());
        assertEquals(version, get(id).path("state_version").asLong(), "Duplicate receipt must not increment progress");
        byte[] changed = part(bytes, 0); changed[0] ^= 1;
        error(sendPart(id, 0, changed, hash(changed)), 409, "PART_CONFLICT");
        assertEquals(snapshot, get(id));
    }

    @Test
    void validatesPartIndexExactLengthAndBodyDigestBeforeAcceptingBytes() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        error(sendPart(id, 6, new byte[16], hash(new byte[16])), 400, "INVALID_PART_INDEX");
        error(sendPart(id, 0, new byte[15], hash(new byte[15])), 400, "PART_SIZE_MISMATCH");
        error(sendPart(id, 5, new byte[16], hash(new byte[16])), 400, "PART_SIZE_MISMATCH");
        error(sendPart(id, 0, part(bytes, 0), "f".repeat(64)), 400, "PART_HASH_MISMATCH");
        error(sendPart(id, 0, part(bytes, 0), "bad"), 400, "INVALID_CHUNK_HASH");
        assertEquals(0, get(id).path("received_bytes").asLong());
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM upload_part WHERE upload_id=?", Integer.class, id));
    }

    @Test
    void missingPartsCannotStartAMergeOrRegisterAVideo() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        sendPart(id, 0, part(bytes, 0), hash(part(bytes, 0)));
        error(request("POST", "/api/uploads/" + id + "/complete", null, null), 409, "UPLOAD_PARTS_MISSING");
        assertEquals("UPLOADING", get(id).path("status").asText());
        assertEquals(0, sourceCount(id));
    }

    @Test
    void completionRegistersOneImmutableSourceAndLatePartDoesNotRecreateTemporaryBytes() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        uploadAll(id, bytes);
        var complete = request("POST", "/api/uploads/" + id + "/complete", null, null);
        assertTrue(complete.statusCode() == 202 || complete.statusCode() == 200, text(complete));
        JsonNode done = awaitCompleted(id);
        String videoId = done.path("video_id").asText();
        assertEquals(videoId, done.path("video").path("id").asText());
        assertEquals(hash(bytes), done.path("video").path("content_hash").asText());
        assertEquals("HEADER_CHECKED", done.path("video").path("media_validation_status").asText());
        assertPublic(done);
        var content = request("GET", done.path("video").path("content_url").asText(), null, null);
        assertEquals(200, content.statusCode());
        assertArrayEquals(bytes, content.body());
        var repeat = request("POST", "/api/uploads/" + id + "/complete", null, null);
        assertEquals(200, repeat.statusCode(), text(repeat));
        assertEquals(done, json(repeat));
        assertEquals(1, sourceCount(id));
        await(() -> !capacityReserved(id), 5000);
        long fileCount = fileCount();
        try (WatchService changes = watchTemporaryFiles(id)) {
            byte[] original = part(bytes, 0);
            assertEquals(200, sendPart(id, 0, original, hash(original)).statusCode());
            byte[] altered = original.clone(); altered[0] ^= 1;
            error(sendPart(id, 0, altered, hash(original)), 400, "PART_HASH_MISMATCH");
            error(sendPart(id, 0, Arrays.copyOf(original, original.length - 1), hash(original)),
                    400, "PART_SIZE_MISMATCH");
            assertTrue(temporaryCreations(changes).isEmpty(),
                    "Completed replay must stream-validate the actual body without creating even short-lived temporary copies");
        }
        assertEquals(fileCount, fileCount(), "Completed upload must not create late temporary part objects");
        assertEquals(done, get(id));
        error(request("DELETE", "/api/uploads/" + id, null, null), 409, "UPLOAD_ALREADY_COMPLETED");
    }

    @Test
    void simultaneousCompleteRequestsShareTheSameFinalVideo() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        uploadAll(id, bytes);
        var one = http.sendAsync(HttpRequest.newBuilder(base.resolve("/api/uploads/" + id + "/complete")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        var two = http.sendAsync(HttpRequest.newBuilder(base.resolve("/api/uploads/" + id + "/complete")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        for (var response : List.of(one.join(), two.join())) {
            assertTrue(response.statusCode() == 202 || response.statusCode() == 200, text(response));
            assertEquals(id, json(response).path("id").asText());
        }
        JsonNode done = awaitCompleted(id);
        assertEquals(1, sourceCount(id));
        assertEquals(done, json(request("POST", "/api/uploads/" + id + "/complete", null, null)));
    }

    @Test
    void receivedPartsAndCompletedReceiptsSurviveApplicationRestart() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        assertEquals(200, sendPart(id, 4, part(bytes, 4), hash(part(bytes, 4))).statusCode());
        JsonNode partial = get(id);
        restart();
        assertEquals(partial, get(id));
        uploadAll(id, bytes);
        request("POST", "/api/uploads/" + id + "/complete", null, null);
        JsonNode done = awaitCompleted(id);
        await(() -> !capacityReserved(id), 5000);
        restart();
        assertEquals(done, get(id));
        assertEquals(done, json(request("POST", "/api/uploads/" + id + "/complete", null, null)));
        assertEquals(1, sourceCount(id));
    }

    @Test
    void mismatchedWholeFileAndInvalidMp4NeverRegisterASource() throws Exception {
        for (boolean wrongHash : List.of(true, false)) {
            byte[] bytes = wrongHash ? mp4(81) : new byte[81];
            var initialized = initialize("bad-final-" + UUID.randomUUID(), "sample.mp4", bytes.length,
                    wrongHash ? "f".repeat(64) : hash(bytes));
            String id = remember(initialized).path("id").asText();
            uploadAll(id, bytes);
            var response = request("POST", "/api/uploads/" + id + "/complete", null, null);
            assertTrue(response.statusCode() == 202 || response.statusCode() == 200, text(response));
            await(() -> !"MERGING".equals(getUnchecked(id).path("status").asText()), 5000);
            JsonNode failed = get(id);
            assertEquals("FAILED", failed.path("status").asText());
            assertFalse(failed.path("error_code").asText().isBlank());
            assertTrue(failed.path("video").isNull());
            assertEquals(0, sourceCount(id));
            assertPublic(failed);
        }
    }

    @Test
    void activeSessionAndReservedByteLimitsDoNotOversubscribe() throws Exception {
        byte[] bytes = mp4(81);
        for (int index = 0; index < 8; index++) newUpload(bytes);
        var ninth = initialize("capacity-" + UUID.randomUUID(), "limit.mp4", bytes.length, hash(bytes));
        error(ninth, 429, "UPLOAD_CAPACITY_EXCEEDED");
        String first = owned.iterator().next();
        assertEquals(200, request("DELETE", "/api/uploads/" + first, null, null).statusCode());
        await(() -> !capacityReserved(first), 5000);
        remember(initialize("capacity-released-" + UUID.randomUUID(), "limit.mp4", bytes.length, hash(bytes)));
        // Only this test's rows are abandoned; no blanket reset of capacity.
    }

    @Test
    void abandonedAndExpiredSessionsReleaseOnlyTheirOwnReservation() throws Exception {
        byte[] bytes = mp4(81);
        String abandoned = newUpload(bytes);
        String expired = newUpload(bytes);
        String kept = newUpload(bytes);
        sendPart(abandoned, 0, part(bytes, 0), hash(part(bytes, 0)));
        var deleted = request("DELETE", "/api/uploads/" + abandoned, null, null);
        assertEquals(200, deleted.statusCode(), text(deleted));
        assertEquals("EXPIRED", get(abandoned).path("status").asText());
        jdbc().update("UPDATE upload_session SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", expired);
        context.getBean(UploadService.class).scan();
        await(() -> "EXPIRED".equals(getUnchecked(expired).path("status").asText()) && !capacityReserved(expired), 5000);
        assertEquals("UPLOADING", get(kept).path("status").asText());
        assertTrue(capacityReserved(kept));
        assertFalse(capacityReserved(abandoned));
        error(sendPart(expired, 0, part(bytes, 0), hash(part(bytes, 0))), 410, "UPLOAD_EXPIRED");
    }

    @Test
    void abandonKeepsItsReservationUntilTheSlowHttpReceiverDiscardsItsBytes() throws Exception {
        expiryDuringSlowReceive(false);
    }

    @Test
    void ttlScanKeepsItsReservationUntilTheSlowHttpReceiverDiscardsItsBytes() throws Exception {
        expiryDuringSlowReceive(true);
    }

    private void expiryDuringSlowReceive(boolean ttl) throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        byte[] piece = part(bytes, 0);
        Path incoming = storageRoot.resolve("uploads/sessions/" + id + "/incoming-0.tmp");
        try (SlowPart receiver = new SlowPart(base.resolve("/api/uploads/" + id + "/parts/0"), piece, hash(piece))) {
            // A real client has sent only half of Content-Length. Observing the
            // server's written bytes proves this is an active receive rather
            // than a request still waiting in the client or servlet queue.
            await(() -> hasSize(incoming, piece.length / 2), 5000);
            assertTrue(capacityReserved(id));
            assertEquals(0, get(id).path("received_bytes").asLong());
            if (ttl) {
                jdbc().update("UPDATE upload_session SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", id);
                context.getBean(UploadService.class).scan();
            } else {
                var abandon = http.send(HttpRequest.newBuilder(base.resolve("/api/uploads/" + id))
                        .timeout(Duration.ofSeconds(2)).DELETE().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, abandon.statusCode(), text(abandon));
                assertEquals("EXPIRED", json(abandon).path("status").asText());
            }
            context.getBean(UploadService.class).scan();
            assertEquals("EXPIRED", get(id).path("status").asText());
            assertTrue(capacityReserved(id), "A blocked network receiver still owns bytes charged to this reservation");
            assertEquals(piece.length / 2, Files.size(incoming), "Cleanup cannot remove an active receiver's half-written bytes");
            assertEquals(0, sourceCount(id));

            SlowResponse rejected = receiver.finish();
            assertEquals(410, rejected.status(), new String(rejected.body(), StandardCharsets.UTF_8));
            JsonNode problem = JSON.readTree(rejected.body());
            assertEquals("UPLOAD_EXPIRED", problem.path("error_code").asText());
            assertFalse(problem.path("request_id").asText().isBlank());
            assertEquals(problem.path("request_id").asText(), rejected.requestId());
        }
        awaitWithScan(() -> !capacityReserved(id) && !Files.exists(incoming), 5000);
        JsonNode expired = get(id);
        assertEquals("EXPIRED", expired.path("status").asText());
        assertEquals(0, expired.path("received_bytes").asLong());
        assertEquals(0, expired.path("received_parts").size());
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM upload_part WHERE upload_id=?", Integer.class, id));
        assertEquals(0, sourceCount(id), "A receiver admitted before expiry must not publish a late source or receipt");
    }

    @Test
    void mergeFreezesPartsWithoutHoldingTheMetadataTransactionDuringFileIo() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        uploadAll(id, bytes);
        FAULTS.holdPreparation(id);
        var complete = request("POST", "/api/uploads/" + id + "/complete", null, null);
        assertEquals(202, complete.statusCode(), text(complete));
        assertTrue(FAULTS.prepareEntered.await(3, TimeUnit.SECONDS), "Merge did not enter the file I/O seam");
        var statusRequest = HttpRequest.newBuilder(base.resolve("/api/uploads/" + id)).timeout(Duration.ofSeconds(2)).GET().build();
        var whileBlocked = http.send(statusRequest, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, whileBlocked.statusCode(), text(whileBlocked));
        assertEquals("MERGING", json(whileBlocked).path("status").asText());
        assertFalse(FAULTS.prepareRelease.getCount() == 0, "File I/O must remain blocked during the status read");
        // An unrelated session must also be usable while this file merge waits.
        String other = newUpload(mp4(65));
        assertEquals(200, sendPart(other, 0, part(bytes, 0), hash(part(bytes, 0))).statusCode());
        error(sendPart(id, 0, part(bytes, 0), hash(part(bytes, 0))), 409, "UPLOAD_NOT_WRITABLE");
        assertEquals(0, sourceCount(id));
        FAULTS.prepareRelease.countDown();
        awaitCompleted(id);
    }

    @Test
    void anExpiredMergeCannotPublishAndTheNextAttemptRecoversItsImmutableParts() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        uploadAll(id, bytes);
        FAULTS.holdPreparation(id);
        assertEquals(202, request("POST", "/api/uploads/" + id + "/complete", null, null).statusCode());
        assertTrue(FAULTS.prepareEntered.await(3, TimeUnit.SECONDS));
        String oldToken = jdbc().queryForObject("SELECT merge_token FROM upload_session WHERE id=?", String.class, id);
        int oldAttempt = jdbc().queryForObject("SELECT merge_attempt FROM upload_session WHERE id=?", Integer.class, id);
        jdbc().update("UPDATE upload_session SET merge_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", id);
        context.getBean(UploadService.class).scan();
        assertEquals(0, sourceCount(id));
        // The old process still owns its filesystem guard. A second merge must
        // not bypass that guard just because the database lease has expired.
        assertEquals(oldAttempt, jdbc().queryForObject("SELECT merge_attempt FROM upload_session WHERE id=?", Integer.class, id));
        FAULTS.prepareRelease.countDown();
        awaitWithScan(() -> "COMPLETED".equals(getUnchecked(id).path("status").asText()), 8000);
        assertEquals(1, sourceCount(id));
        assertTrue(jdbc().queryForObject("SELECT merge_attempt FROM upload_session WHERE id=?", Integer.class, id) > oldAttempt);
        assertNotEquals(oldToken, jdbc().queryForObject("SELECT merge_token FROM upload_session WHERE id=?", String.class, id));
    }

    @Test
    void publishedFileSurvivesRollbackAndIsAdoptedUnderTheSameReservedVideoId() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        uploadAll(id, bytes);
        String reserved = jdbc().queryForObject("SELECT video_id FROM upload_session WHERE id=?", String.class, id);
        FAULTS.failAfterPublish(id);
        assertEquals(202, request("POST", "/api/uploads/" + id + "/complete", null, null).statusCode());
        assertTrue(FAULTS.published.await(3, TimeUnit.SECONDS));
        await(() -> jdbc().queryForObject("SELECT merge_token FROM upload_session WHERE id=?", String.class, id) == null, 5000);
        assertEquals(0, sourceCount(id), "Source registration must have rolled back with the failed publication transaction");
        Path source = storageRoot.resolve("videos/" + reserved + ".mp4");
        assertTrue(Files.isRegularFile(source), "Published source must be retained for reconciliation");
        assertArrayEquals(bytes, Files.readAllBytes(source));
        awaitWithScan(() -> "COMPLETED".equals(getUnchecked(id).path("status").asText()), 8000);
        JsonNode done = get(id);
        assertEquals(reserved, done.path("video_id").asText());
        assertEquals(1, sourceCount(id));
        assertArrayEquals(bytes, Files.readAllBytes(source));
    }

    @Test
    void aLostDatabaseCommitAcknowledgementDoesNotDeleteTheCommittedVideoOrDuplicateRegistration() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        uploadAll(id, bytes);
        FAULTS.loseCommitAcknowledgement(id);
        assertEquals(202, request("POST", "/api/uploads/" + id + "/complete", null, null).statusCode());
        JsonNode done = awaitCompleted(id);
        await(FAULTS.commitFailureDelivered::get, 3000);
        assertTrue(FAULTS.commitFailureDelivered.get(), "The real database commit must have succeeded before its injected acknowledgement failure");
        assertEquals(1, sourceCount(id));
        await(() -> !capacityReserved(id), 5000);
        done = get(id);
        String contentUrl = done.path("video").path("content_url").asText();
        assertArrayEquals(bytes, request("GET", contentUrl, null, null).body());
        assertEquals(done, json(request("POST", "/api/uploads/" + id + "/complete", null, null)));
        restart();
        assertEquals(done, get(id));
        assertEquals(1, sourceCount(id));
    }

    @Test
    void expiryCannotCleanFilesOutFromUnderAnActiveMergerOrPublishAnExpiredSource() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        uploadAll(id, bytes);
        FAULTS.holdPreparation(id);
        assertEquals(202, request("POST", "/api/uploads/" + id + "/complete", null, null).statusCode());
        assertTrue(FAULTS.prepareEntered.await(3, TimeUnit.SECONDS));
        jdbc().update("UPDATE upload_session SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", id);
        context.getBean(UploadService.class).scan();
        assertEquals(0, sourceCount(id));
        assertTrue(capacityReserved(id), "An active filesystem owner must keep its capacity reservation until safe cleanup");
        FAULTS.prepareRelease.countDown();
        awaitWithScan(() -> "EXPIRED".equals(getUnchecked(id).path("status").asText()) && !capacityReserved(id), 8000);
        assertEquals(0, sourceCount(id));
    }

    @Test
    void mergeRechecksStoredPartBytesInsteadOfTrustingDatabaseReceiptsAlone() throws Exception {
        byte[] bytes = mp4(81);
        String id = newUpload(bytes);
        uploadAll(id, bytes);
        Path piece = storageRoot.resolve("uploads/sessions/" + id + "/part-0-" + hash(part(bytes, 0)) + ".part");
        assertTrue(Files.isRegularFile(piece));
        byte[] changed = part(bytes, 0); changed[0] ^= 1;
        Files.write(piece, changed);
        assertEquals(202, request("POST", "/api/uploads/" + id + "/complete", null, null).statusCode());
        await(() -> !"MERGING".equals(getUnchecked(id).path("status").asText()), 5000);
        assertEquals("FAILED", get(id).path("status").asText());
        assertEquals("PART_HASH_MISMATCH", get(id).path("error_code").asText());
        assertEquals(0, sourceCount(id));
    }

    private void startContext() {
        // Command-line properties override application.properties and the local
        // development environment, keeping the port, quota and files isolated.
        context = new SpringApplicationBuilder(LivecutBackendApplication.class, FixtureConfiguration.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--livecut.storage.root=" + storageRoot,
                "--livecut.uploads.chunk-size=" + CHUNK, "--livecut.uploads.scan-enabled=false",
                "--livecut.uploads.max-active=8", "--livecut.uploads.max-reserved-bytes=4096",
                "--livecut.uploads.lease-seconds=30", "--livecut.uploads.ttl-seconds=86400",
                "--livecut.jobs.scan-enabled=false", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
    }
    private void restart() { context.close(); startContext(); }
    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }
    private String newUpload(byte[] bytes) throws Exception {
        var response = initialize("upload-" + UUID.randomUUID(), "sample.mp4", bytes.length, hash(bytes));
        assertEquals(201, response.statusCode(), text(response));
        return remember(response).path("id").asText();
    }
    private HttpResponse<byte[]> initialize(String key, String name, long size, String hash) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve("/api/uploads"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(Map.of(
                        "original_filename", name, "size_bytes", size, "content_hash", hash))))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private JsonNode remember(HttpResponse<byte[]> response) throws Exception {
        JsonNode node = json(response);
        if (node.has("id")) owned.add(node.path("id").asText());
        return node;
    }
    private JsonNode get(String id) throws Exception {
        var response = request("GET", "/api/uploads/" + id, null, null);
        assertEquals(200, response.statusCode(), text(response));
        return json(response);
    }
    private JsonNode getUnchecked(String id) {
        try { return get(id); } catch (Exception exception) { throw new AssertionError(exception); }
    }
    private void uploadAll(String id, byte[] bytes) throws Exception {
        for (int index = 0; index < (bytes.length + CHUNK - 1) / CHUNK; index++) {
            var response = sendPart(id, index, part(bytes, index), hash(part(bytes, index)));
            assertEquals(200, response.statusCode(), text(response));
        }
    }
    private HttpRequest partRequest(String id, int index, byte[] bytes, String digest) {
        return HttpRequest.newBuilder(base.resolve("/api/uploads/" + id + "/parts/" + index))
                .header("Content-Type", "application/octet-stream").header("X-Chunk-SHA256", digest)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).timeout(Duration.ofSeconds(15)).build();
    }
    private HttpResponse<byte[]> sendPart(String id, int index, byte[] bytes, String digest) throws Exception {
        return http.send(partRequest(id, index, bytes, digest), HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpResponse<byte[]> request(String method, String path, byte[] body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15));
        if (contentType != null) builder.header("Content-Type", contentType);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private int sourceCount(String uploadId) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM video_asset WHERE id=(SELECT video_id FROM upload_session WHERE id=?)", Integer.class, uploadId);
    }
    private boolean capacityReserved(String id) {
        return jdbc().queryForObject("SELECT capacity_reserved FROM upload_session WHERE id=?", Boolean.class, id);
    }
    private JsonNode awaitCompleted(String id) throws Exception {
        await(() -> !"MERGING".equals(getUnchecked(id).path("status").asText()), 8000);
        JsonNode value = get(id);
        assertEquals("COMPLETED", value.path("status").asText(), value.toString());
        return value;
    }
    private long fileCount() throws Exception {
        try (var paths = Files.walk(storageRoot)) { return paths.filter(Files::isRegularFile).count(); }
    }
    private WatchService watchTemporaryFiles(String id) throws Exception {
        WatchService watcher = storageRoot.getFileSystem().newWatchService();
        try {
            storageRoot.resolve("uploads/sessions/" + id).register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            Path legacyIncoming = storageRoot.resolve("uploads/incoming");
            if (Files.isDirectory(legacyIncoming)) legacyIncoming.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            return watcher;
        } catch (Exception failure) {
            watcher.close();
            throw failure;
        }
    }
    private static Set<String> temporaryCreations(WatchService watcher) throws Exception {
        Set<String> created = new LinkedHashSet<>();
        WatchKey key = watcher.poll(1, TimeUnit.SECONDS);
        while (key != null) {
            for (var event : key.pollEvents()) {
                assertNotEquals(StandardWatchEventKinds.OVERFLOW, event.kind(), "File events overflowed; cannot prove replay avoided temporary copies");
                String name = event.context().toString();
                if (name.endsWith(".tmp") || name.endsWith(".part")) created.add(name);
            }
            assertTrue(key.reset(), "Upload directory disappeared while observing completed replay");
            key = watcher.poll();
        }
        return created;
    }
    private static boolean hasSize(Path path, long expected) {
        try { return Files.isRegularFile(path) && Files.size(path) == expected; }
        catch (IOException notReady) { return false; }
    }
    private void awaitWithScan(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            context.getBean(UploadService.class).scan();
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "Upload recovery scan did not converge");
    }
    private static void await(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Asynchronous upload condition did not complete");
    }
    private static JsonNode json(HttpResponse<byte[]> response) throws Exception { return JSON.readTree(response.body()); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    private static void error(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), text(response));
        JsonNode body = json(response);
        assertEquals(code, body.path("error_code").asText());
        assertFalse(body.path("request_id").asText().isBlank());
        assertEquals(body.path("request_id").asText(), response.headers().firstValue("X-Request-ID").orElseThrow());
    }
    private static void assertPublic(JsonNode snapshot) {
        String body = snapshot.toString();
        for (String forbidden : List.of("merge_token", "request_key", "reservation_bytes", "source_key", "storage_root", "run_token")) {
            assertFalse(body.contains("\"" + forbidden + "\""), body);
        }
        assertFalse(body.contains(storageRoot.toString()), "Public response leaks local path");
    }
    private static byte[] part(byte[] bytes, int index) {
        return Arrays.copyOfRange(bytes, index * CHUNK, Math.min(bytes.length, (index + 1) * CHUNK));
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static byte[] mp4(int length) {
        return ByteBuffer.allocate(length).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII))
                .putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII)).putInt(length - 24)
                .put("free".getBytes(StandardCharsets.US_ASCII)).array();
    }

    private record SlowResponse(int status, byte[] body, String requestId) {}

    /** Fixed-length streaming sends half a real HTTP body and keeps its socket open. */
    private static final class SlowPart implements AutoCloseable {
        private final HttpURLConnection connection;
        private final OutputStream output;
        private final byte[] bytes;
        private boolean finished;

        SlowPart(URI target, byte[] bytes, String hash) throws Exception {
            this.bytes = bytes.clone();
            connection = (HttpURLConnection) target.toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-Chunk-SHA256", hash);
            output = connection.getOutputStream();
            output.write(bytes, 0, bytes.length / 2);
            output.flush();
        }

        SlowResponse finish() throws IOException {
            if (finished) throw new IllegalStateException("Slow HTTP request already finished");
            finished = true;
            output.write(bytes, bytes.length / 2, bytes.length - bytes.length / 2);
            output.close();
            int status = connection.getResponseCode();
            try (var body = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                return new SlowResponse(status, body == null ? new byte[0] : body.readAllBytes(),
                        connection.getHeaderField("X-Request-ID"));
            }
        }

        @Override public void close() {
            // Even if an assertion fails, finish or disconnect the socket so a
            // test receiver cannot stay blocked into another test's cleanup.
            try { if (!finished) finish(); }
            catch (IOException ignored) { }
            finally { connection.disconnect(); }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean @Primary
        UploadStorage controlledUploadStorage(VideoConfiguration.StorageProperties properties, VideoStorage videos) {
            return new ControlledStorage(properties, videos);
        }
        @Bean @Primary
        PlatformTransactionManager uploadFixtureTransactions(DataSource source) { return new CommitFailureTransactions(source); }
    }

    static class ControlledStorage extends UploadStorage {
        ControlledStorage(VideoConfiguration.StorageProperties properties, VideoStorage videos) { super(properties, videos); }
        @Override
        public Prepared prepare(UploadModels.Source source, List<UploadModels.Part> parts, Runnable guard) {
            if (source.uploadId().equals(FAULTS.target) && FAULTS.blockPreparation.compareAndSet(true, false)) {
                FAULTS.prepareEntered.countDown();
                try {
                    if (!FAULTS.prepareRelease.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test preparation gate timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Test preparation was interrupted", failure);
                }
            }
            return super.prepare(source, parts, guard);
        }
        @Override
        public void publish(Prepared prepared) {
            super.publish(prepared);
            if (!prepared.source().uploadId().equals(FAULTS.target)) return;
            FAULTS.published.countDown();
            if (FAULTS.failPublication.compareAndSet(true, false)) throw new IllegalStateException("Injected failure after source publication");
            if (FAULTS.failCommitAcknowledgement.compareAndSet(true, false)) FAULTS.failCurrentCommit.set(true);
        }
    }

    static class CommitFailureTransactions extends JdbcTransactionManager {
        CommitFailureTransactions(DataSource source) { super(source); }
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (Boolean.TRUE.equals(FAULTS.failCurrentCommit.get())) {
                FAULTS.failCurrentCommit.remove();
                FAULTS.commitFailureDelivered.set(true);
                throw new TransactionSystemException("Injected acknowledgement loss after the real database commit");
            }
        }
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            try { super.doRollback(status); }
            finally { FAULTS.failCurrentCommit.remove(); }
        }
    }

    static class Faults {
        volatile String target;
        volatile CountDownLatch prepareEntered = new CountDownLatch(0);
        volatile CountDownLatch prepareRelease = new CountDownLatch(0);
        volatile CountDownLatch published = new CountDownLatch(0);
        final AtomicBoolean blockPreparation = new AtomicBoolean();
        final AtomicBoolean failPublication = new AtomicBoolean();
        final AtomicBoolean failCommitAcknowledgement = new AtomicBoolean();
        final AtomicBoolean commitFailureDelivered = new AtomicBoolean();
        final ThreadLocal<Boolean> failCurrentCommit = new ThreadLocal<>();
        void reset() {
            prepareRelease.countDown();
            target = null;
            blockPreparation.set(false); failPublication.set(false); failCommitAcknowledgement.set(false);
            commitFailureDelivered.set(false); failCurrentCommit.remove();
        }
        void holdPreparation(String id) {
            target = id; prepareEntered = new CountDownLatch(1); prepareRelease = new CountDownLatch(1);
            blockPreparation.set(true);
        }
        void failAfterPublish(String id) { target = id; published = new CountDownLatch(1); failPublication.set(true); }
        void loseCommitAcknowledgement(String id) { target = id; failCommitAcknowledgement.set(true); }
    }
}
