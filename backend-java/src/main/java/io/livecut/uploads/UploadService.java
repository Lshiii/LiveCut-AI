package io.livecut.uploads;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.livecut.video.VideoApiException;
import io.livecut.video.VideoAsset;
import io.livecut.video.VideoAssetResponse;
import io.livecut.video.VideoConfiguration;
import io.livecut.video.VideoRepository;
import io.livecut.video.VideoStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Durable resumable upload state. Large stream I/O never runs in a DB transaction. */
@Service
public class UploadService {
    private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);
    private static final Set<String> CREATE_FIELDS = Set.of("original_filename", "size_bytes", "content_hash");
    private static final Set<String> CLEANABLE = Set.of("COMPLETED", "FAILED", "EXPIRED");
    private static final RowMapper<UploadModels.Part> PART = (row, index) ->
            new UploadModels.Part(row.getInt("part_index"), row.getLong("size_bytes"), row.getString("sha256"));
    private record Session(UploadModels.Source source, String requestHash, String status, long version,
                           Instant expiresAt, int mergeAttempt, String mergeToken, Instant leaseUntil,
                           String errorCode, long reservationBytes, boolean capacityReserved) {}
    private static final RowMapper<Session> SESSION = (row, index) -> new Session(
            new UploadModels.Source(row.getString("id"), row.getString("video_id"), row.getString("original_filename"),
                    row.getLong("size_bytes"), row.getString("content_hash"), row.getInt("chunk_size"), row.getInt("total_parts")),
            row.getString("request_hash"), row.getString("status"), row.getLong("state_version"),
            row.getTimestamp("expires_at").toInstant(), row.getInt("merge_attempt"), row.getString("merge_token"),
            row.getTimestamp("merge_lease_until") == null ? null : row.getTimestamp("merge_lease_until").toInstant(),
            row.getString("error_code"), row.getLong("reservation_bytes"), row.getBoolean("capacity_reserved"));
    private record Merge(Session session, String token, List<UploadModels.Part> parts) {}
    private static final class LostLease extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final UploadStorage storage;
    private final VideoRepository videos;
    private final long maxVideoBytes;
    private final int chunkSize, maxParts, ttlSeconds, leaseSeconds, maxActive, maxMergeAttempts;
    private final long maxReservedBytes, scanDelayMs;
    private final boolean scanEnabled;
    private final Semaphore receivers, mergers;
    private final Set<String> executing = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService mergeWorkers;
    private final ScheduledExecutorService scanner;

    public UploadService(JdbcTemplate jdbc, PlatformTransactionManager manager, UploadStorage storage,
                         VideoRepository videos, VideoConfiguration.StorageProperties videoProperties,
                         @Value("${livecut.uploads.chunk-size:8388608}") int chunkSize,
                         @Value("${livecut.uploads.max-parts:4096}") int maxParts,
                         @Value("${livecut.uploads.ttl-seconds:86400}") int ttlSeconds,
                         @Value("${livecut.uploads.lease-seconds:90}") int leaseSeconds,
                         @Value("${livecut.uploads.max-active:8}") int maxActive,
                         @Value("${livecut.uploads.max-reserved-bytes:4294967296}") long maxReservedBytes,
                         @Value("${livecut.uploads.receive-slots:6}") int receiveSlots,
                         @Value("${livecut.uploads.merge-slots:1}") int mergeSlots,
                         @Value("${livecut.uploads.max-merge-attempts:3}") int maxMergeAttempts,
                         @Value("${livecut.uploads.scan-enabled:true}") boolean scanEnabled,
                         @Value("${livecut.uploads.scan-delay-ms:1000}") long scanDelayMs) {
        if (chunkSize < 16 || chunkSize > 8388608 || maxParts < 1 || maxParts > 4096
                || ttlSeconds < 1 || ttlSeconds > 604800 || leaseSeconds < 1 || leaseSeconds > 3600
                || maxActive < 1 || maxActive > 128 || maxReservedBytes < 1 || receiveSlots < 1 || receiveSlots > 32
                || mergeSlots < 1 || mergeSlots > 4 || maxMergeAttempts < 1 || maxMergeAttempts > 10
                || scanDelayMs < 100 || scanDelayMs > 60000) throw new IllegalArgumentException("Invalid bounded upload configuration");
        this.jdbc = jdbc; this.storage = storage; this.videos = videos;
        this.maxVideoBytes = videoProperties.maxVideoBytes();
        this.chunkSize = chunkSize; this.maxParts = maxParts; this.ttlSeconds = ttlSeconds;
        this.leaseSeconds = leaseSeconds; this.maxActive = maxActive; this.maxReservedBytes = maxReservedBytes;
        this.maxMergeAttempts = maxMergeAttempts; this.scanEnabled = scanEnabled; this.scanDelayMs = scanDelayMs;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        receivers = new Semaphore(receiveSlots); mergers = new Semaphore(mergeSlots);
        mergeWorkers = new ThreadPoolExecutor(mergeSlots, mergeSlots, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), threads("livecut-upload-merge"), new ThreadPoolExecutor.AbortPolicy());
        scanner = Executors.newSingleThreadScheduledExecutor(threads("livecut-upload-scan"));
    }

    @PostConstruct void start() {
        if (scanEnabled) scanner.scheduleWithFixedDelay(() -> {
            try { scan(); }
            catch (RuntimeException error) { LOG.warn("Upload scan deferred ({})", error.getClass().getSimpleName()); }
        }, scanDelayMs, scanDelayMs, TimeUnit.MILLISECONDS);
    }

    public UploadModels.Snapshot initialize(JsonNode input, String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw bad("INVALID_IDEMPOTENCY_KEY", "Provide a bounded Idempotency-Key");
        if (input == null || !input.isObject() || input.size() != CREATE_FIELDS.size()
                || !CREATE_FIELDS.containsAll(input.propertyNames())) throw bad("INVALID_ARGUMENT", "Provide exactly original_filename, size_bytes and content_hash");
        JsonNode filenameNode = input.path("original_filename"), sizeNode = input.path("size_bytes"), hashNode = input.path("content_hash");
        if (!filenameNode.isString() || !hashNode.isString() || !sizeNode.isIntegralNumber() || !sizeNode.canConvertToLong()) throw bad("INVALID_ARGUMENT", "Upload fields have invalid types");
        String filename = VideoStorage.displayFilename(filenameNode.stringValue());
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".mp4")) throw new VideoApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_VIDEO", "Only MP4 uploads are supported");
        long size = sizeNode.longValue();
        if (size <= 0) throw bad("EMPTY_VIDEO", "Select a nonempty MP4 file");
        if (size > maxVideoBytes || size > 1073741824L) throw new VideoApiException(HttpStatus.PAYLOAD_TOO_LARGE, "VIDEO_TOO_LARGE", "Video exceeds the configured upload size limit");
        String hash = validHash(hashNode.stringValue(), "INVALID_CONTENT_HASH");
        int count = (int) ((size + chunkSize - 1) / chunkSize);
        if (count > maxParts) throw bad("UPLOAD_TOO_MANY_PARTS", "File requires more than the supported part count");
        String requestHash = sha256(filename + "\n" + size + "\n" + hash);
        UploadModels.Snapshot result = tx.execute(status -> {
            capacityLock();
            List<Session> existing = jdbc.query("SELECT * FROM upload_session WHERE request_key=? FOR UPDATE", SESSION, key);
            if (!existing.isEmpty()) {
                Session session = existing.getFirst();
                if (!session.requestHash().equals(requestHash)) throw conflict("IDEMPOTENCY_CONFLICT", "This key belongs to different upload input");
                return snapshot(expireIfNeeded(session));
            }
            var capacity = jdbc.queryForMap("SELECT active_sessions,reserved_bytes FROM upload_capacity WHERE id=1");
            long reservation = Math.multiplyExact(size, 3L);
            if (((Number) capacity.get("active_sessions")).intValue() >= maxActive
                    || reservation > maxReservedBytes - ((Number) capacity.get("reserved_bytes")).longValue()) {
                throw new VideoApiException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_CAPACITY_EXCEEDED", "Temporary upload capacity is currently reserved");
            }
            String id = UUID.randomUUID().toString(), videoId = UUID.randomUUID().toString();
            Instant expires = now().plusSeconds(ttlSeconds);
            jdbc.update("INSERT INTO upload_session (id,request_key,request_hash,video_id,original_filename,size_bytes,content_hash,chunk_size,total_parts,status,expires_at,reservation_bytes) VALUES (?,?,?,?,?,?,?,?,?,'UPLOADING',?,?)",
                    id, key, requestHash, videoId, filename, size, hash, chunkSize, count, Timestamp.from(expires), reservation);
            jdbc.update("UPDATE upload_capacity SET active_sessions=active_sessions+1,reserved_bytes=reserved_bytes+? WHERE id=1", reservation);
            return snapshot(session(id, true));
        });
        if ("EXPIRED".equals(result.status())) throw expired();
        return result;
    }

    public UploadModels.Snapshot get(String rawId) {
        String id = id(rawId);
        return tx.execute(status -> { capacityLock(); return snapshot(expireIfNeeded(session(id, true))); });
    }

    public UploadModels.Snapshot part(String rawId, String rawIndex, String rawHash, InputStream body) {
        String id = id(rawId), hash = validHash(rawHash, "INVALID_CHUNK_HASH");
        int index = index(rawIndex);
        if (!receivers.tryAcquire()) throw new VideoApiException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_RECEIVE_BUSY", "Concurrent part receivers are busy");
        // The holder is assigned inside admission so even a lost COMMIT ACK
        // cannot strand a receiver guard that was already created by the callback.
        UploadStorage.IncomingLease[] receiving = { null };
        try {
            Session initial = tx.execute(status -> {
                capacityLock();
                Session current = expireIfNeeded(session(id, true));
                requirePartIndex(current.source(), index);
                if ("EXPIRED".equals(current.status())) return current;
                writable(current);
                List<UploadModels.Part> prior = jdbc.query("SELECT * FROM upload_part WHERE upload_id=? AND part_index=?", PART, id, index);
                if (!prior.isEmpty()) {
                    var part = prior.getFirst();
                    if (!part.sha256().equals(hash)) throw conflict("PART_CONFLICT", "This part index is already bound to different bytes");
                    if ("COMPLETED".equals(current.status()) || storage.partPresent(id, part)) return current;
                } else if (!"UPLOADING".equals(current.status())) {
                    throw conflict("PART_CONFLICT", "Completed upload does not have this part receipt");
                }
                // Capacity is still reserved and cleanup cannot pass this DB lock
                // before it can observe the cross-process receiver guard.
                receiving[0] = storage.beginIncoming(id, index);
                return current;
            });
            writable(initial);
            long expected = UploadStorage.expectedPartSize(initial.source(), index);
            UploadStorage.Incoming accepted = receiving[0] == null
                    ? storage.validateReplay(body, expected, hash)
                    : storage.receive(receiving[0], body, expected, hash);
            return tx.execute(status -> {
                capacityLock();
                Session current = expireIfNeeded(session(id, true));
                writable(current);
                List<UploadModels.Part> prior = jdbc.query("SELECT * FROM upload_part WHERE upload_id=? AND part_index=?", PART, id, index);
                if (!prior.isEmpty()) {
                    var part = prior.getFirst();
                    if (part.sizeBytes() != accepted.sizeBytes() || !part.sha256().equals(accepted.sha256())) throw conflict("PART_CONFLICT", "This part index is already bound to different bytes");
                    if ("UPLOADING".equals(current.status()) && accepted.path() != null) storage.publishPart(id, index, accepted);
                    return snapshot(current);
                }
                if (!"UPLOADING".equals(current.status()) || accepted.path() == null) throw conflict("PART_CONFLICT", "Upload does not have this part receipt");
                // The row lock proves a former unknown commit did not register this index.
                // Remove only its unregistered crash residue before creating one replacement.
                storage.discardUnregisteredPart(id, index);
                storage.publishPart(id, index, accepted);
                jdbc.update("INSERT INTO upload_part (upload_id,part_index,size_bytes,sha256) VALUES (?,?,?,?)", id, index, accepted.sizeBytes(), accepted.sha256());
                touch(id);
                return snapshot(session(id, false));
            });
        } finally {
            try { storage.discardIncoming(receiving[0]); }
            finally {
                receivers.release();
                if (receiving[0] != null) {
                    try { cleanup(id); }
                    catch (RuntimeException deferred) { LOG.warn("Upload receiver cleanup deferred for {} ({})", id, deferred.getClass().getSimpleName()); }
                }
            }
        }
    }

    public UploadModels.Snapshot complete(String rawId) {
        String id = id(rawId);
        UploadModels.Snapshot result = tx.execute(status -> {
            capacityLock();
            Session current = expireIfNeeded(session(id, true));
            if ("COMPLETED".equals(current.status()) || "MERGING".equals(current.status())) return snapshot(current);
            if ("EXPIRED".equals(current.status())) return snapshot(current);
            if ("FAILED".equals(current.status())) throw conflict("UPLOAD_FAILED", "Upload failed integrity validation; start a new upload");
            List<UploadModels.Part> parts = parts(id);
            requireCompleteParts(current.source(), parts);
            for (var part : parts) if (!storage.partPresent(id, part)) throw conflict("UPLOAD_PARTS_MISSING", "A received part is unavailable; resume or restart the upload");
            jdbc.update("UPDATE upload_session SET status='MERGING',merge_token=NULL,merge_lease_until=NULL,error_code=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
            return snapshot(session(id, false));
        });
        if ("EXPIRED".equals(result.status())) throw expired();
        if ("MERGING".equals(result.status())) dispatch(id);
        return result;
    }

    public UploadModels.Snapshot abandon(String rawId) {
        String id = id(rawId);
        UploadModels.Snapshot result = tx.execute(status -> {
            capacityLock();
            Session current = session(id, true);
            if ("COMPLETED".equals(current.status())) throw conflict("UPLOAD_ALREADY_COMPLETED", "Completed video sources cannot be deleted through upload cleanup");
            if (!"EXPIRED".equals(current.status())) {
                jdbc.update("UPDATE upload_session SET status='EXPIRED',merge_token=NULL,merge_lease_until=NULL,error_code=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
            }
            return snapshot(session(id, false));
        });
        cleanup(id);
        return result;
    }

    /** Public for deterministic maintenance/integration checks; it never dispatches a model. */
    public void scan() {
        if (closed.get()) return;
        List<String> ids = jdbc.queryForList("SELECT id FROM upload_session WHERE capacity_reserved=TRUE OR status='MERGING' ORDER BY updated_at,id LIMIT 128", String.class);
        for (String id : ids) {
            try {
                Session current = tx.execute(status -> { capacityLock(); return expireIfNeeded(session(id, true)); });
                if (CLEANABLE.contains(current.status())) cleanup(id);
                // Lease ownership is decided against DB time after the file lock,
                // never by a potentially skewed maintenance host clock.
                else if ("MERGING".equals(current.status())) dispatch(id);
            } catch (RuntimeException failure) { LOG.warn("Upload maintenance deferred for {} ({})", id, failure.getClass().getSimpleName()); }
        }
        storage.cleanupIncoming();
    }

    private void dispatch(String id) {
        if (closed.get() || !executing.add(id)) return;
        if (!mergers.tryAcquire()) { executing.remove(id); return; }
        try { mergeWorkers.execute(() -> {
            try { merge(id); }
            finally { executing.remove(id); mergers.release(); }
        }); } catch (RuntimeException rejected) { executing.remove(id); mergers.release(); }
    }

    private void merge(String id) {
        Merge work = null;
        UploadStorage.Prepared prepared = null;
        try (UploadStorage.SessionLock lock = storage.trySessionLock(id)) {
            if (lock == null || closed.get()) return;
            work = tx.execute(status -> {
                capacityLock();
                Session current = expireIfNeeded(session(id, true));
                Instant time = now();
                if (!"MERGING".equals(current.status()) || (current.leaseUntil() != null && current.leaseUntil().isAfter(time))) return null;
                if (current.mergeAttempt() >= maxMergeAttempts) {
                    failed(id, "UPLOAD_MERGE_ATTEMPTS_EXHAUSTED"); return null;
                }
                List<UploadModels.Part> parts = parts(id);
                requireCompleteParts(current.source(), parts);
                String token = UUID.randomUUID().toString();
                jdbc.update("UPDATE upload_session SET merge_attempt=merge_attempt+1,merge_token=?,merge_lease_until=?,error_code=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                        token, Timestamp.from(time.plusSeconds(leaseSeconds)), id);
                return new Merge(session(id, false), token, parts);
            });
            if (work == null) return;
            Merge currentWork = work;
            Runnable guard = new LeaseGuard(id, work.token());
            prepared = storage.prepare(work.session().source(), work.parts(), guard);
            UploadStorage.Prepared ready = prepared;
            tx.executeWithoutResult(status -> {
                capacityLock();
                Session current = session(id, true);
                requireLease(current, currentWork.token());
                storage.publish(ready);
                VideoStorage.StoredVideo value = new VideoStorage.StoredVideo(current.source().videoId(), current.source().originalFilename(),
                        current.source().contentHash(), "videos/" + current.source().videoId() + ".mp4", current.source().sizeBytes());
                var registered = videos.find(value.id());
                if (registered.isEmpty()) videos.insert(value);
                else if (!sameVideo(registered.get(), value)) throw conflict("UPLOAD_SOURCE_CONFLICT", "Reserved video identity is bound to different content");
                jdbc.update("UPDATE upload_session SET status='COMPLETED',merge_token=NULL,merge_lease_until=NULL,error_code=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
            });
        } catch (LostLease ignored) {
            if (work != null) retryLater(id, work.token(), "UPLOAD_LEASE_LOST");
        } catch (VideoApiException failure) {
            if (work != null) {
                if (failure.getStatus().is4xxClientError()) failOwned(id, work.token(), failure.getCode());
                else retryLater(id, work.token(), failure.getCode());
            }
        } catch (RuntimeException failure) {
            if (work != null) retryLater(id, work.token(), "UPLOAD_RECOVERY_PENDING");
            LOG.warn("Upload merge deferred for {} ({})", id, failure.getClass().getSimpleName());
        } finally {
            storage.discardPrepared(prepared);
            // The try-with-resources session lock has been released here. Cleanup
            // reacquires it and rechecks the committed state, including lost COMMIT ACK.
            try { cleanup(id); }
            catch (RuntimeException deferred) { LOG.warn("Upload cleanup deferred for {} ({})", id, deferred.getClass().getSimpleName()); }
        }
    }

    private final class LeaseGuard implements Runnable {
        private final String id, token;
        private final long intervalNanos;
        private long nextCheck;
        LeaseGuard(String id, String token) {
            this.id = id; this.token = token;
            intervalNanos = Math.max(100_000_000L, Math.min(5_000_000_000L, leaseSeconds * 1_000_000_000L / 3));
        }
        @Override public void run() {
            if (closed.get() || Thread.currentThread().isInterrupted()) throw new LostLease();
            long clock = System.nanoTime();
            if (clock < nextCheck) return;
            tx.executeWithoutResult(status -> {
                capacityLock();
                Session current = session(id, true);
                requireLease(current, token);
                jdbc.update("UPDATE upload_session SET merge_lease_until=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", Timestamp.from(now().plusSeconds(leaseSeconds)), id);
            });
            nextCheck = clock + intervalNanos;
        }
    }

    private void requireLease(Session current, String token) {
        Instant time = now();
        if (!"MERGING".equals(current.status()) || !token.equals(current.mergeToken())
                || current.leaseUntil() == null || !current.leaseUntil().isAfter(time) || !current.expiresAt().isAfter(time)
                || closed.get()) throw new LostLease();
    }

    private void retryLater(String id, String token, String code) {
        try { tx.executeWithoutResult(status -> {
            capacityLock(); Session current = session(id, true);
            if (!"MERGING".equals(current.status()) || !token.equals(current.mergeToken())) return;
            if (current.mergeAttempt() >= maxMergeAttempts) failed(id, code);
            else jdbc.update("UPDATE upload_session SET merge_token=NULL,merge_lease_until=NULL,error_code=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", code, id);
        }); } catch (RuntimeException ignored) { /* committed state is read by the next recovery scan */ }
    }

    private void failOwned(String id, String token, String code) {
        try { tx.executeWithoutResult(status -> {
            capacityLock(); Session current = session(id, true);
            if ("MERGING".equals(current.status()) && token.equals(current.mergeToken())) failed(id, code);
        }); } catch (RuntimeException ignored) { /* retain bytes and lease for reconciliation */ }
    }

    private void cleanup(String id) {
        try (UploadStorage.SessionLock lock = storage.trySessionLock(id)) {
            if (lock == null) return;
            tx.executeWithoutResult(status -> {
                capacityLock();
                Session current = expireIfNeeded(session(id, true));
                if (!CLEANABLE.contains(current.status()) || !current.capacityReserved()) return;
                if (!storage.cleanupSession(id)) return;
                int released = jdbc.update("UPDATE upload_capacity SET active_sessions=active_sessions-1,reserved_bytes=reserved_bytes-? WHERE id=1 AND active_sessions>0 AND reserved_bytes>=?", current.reservationBytes(), current.reservationBytes());
                if (released != 1) throw new IllegalStateException("Upload capacity reservation is inconsistent");
                jdbc.update("UPDATE upload_session SET capacity_reserved=FALSE,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
            });
        }
    }

    private Session expireIfNeeded(Session value) {
        if (("UPLOADING".equals(value.status()) || "MERGING".equals(value.status())) && !value.expiresAt().isAfter(now())) {
            jdbc.update("UPDATE upload_session SET status='EXPIRED',merge_token=NULL,merge_lease_until=NULL,error_code=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", value.source().uploadId());
            return session(value.source().uploadId(), false);
        }
        return value;
    }

    private UploadModels.Snapshot snapshot(Session current) {
        List<UploadModels.Part> parts = parts(current.source().uploadId());
        long bytes = parts.stream().mapToLong(UploadModels.Part::sizeBytes).sum();
        VideoAssetResponse video = null;
        if ("COMPLETED".equals(current.status())) video = VideoAssetResponse.from(videos.find(current.source().videoId())
                .orElseThrow(() -> conflict("UPLOAD_SOURCE_MISSING", "Completed source metadata is unavailable")));
        var source = current.source();
        return new UploadModels.Snapshot(source.uploadId(), current.status(), source.originalFilename(), source.sizeBytes(), source.contentHash(),
                source.chunkSize(), source.totalParts(), parts, bytes, current.version(), current.expiresAt(), video == null ? null : video.id(), video, current.errorCode());
    }

    private List<UploadModels.Part> parts(String id) { return jdbc.query("SELECT * FROM upload_part WHERE upload_id=? ORDER BY part_index", PART, id); }
    private Session session(String id, boolean lock) {
        List<Session> rows = jdbc.query("SELECT * FROM upload_session WHERE id=?" + (lock ? " FOR UPDATE" : ""), SESSION, id);
        if (rows.isEmpty()) throw new VideoApiException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "Upload was not found");
        return rows.getFirst();
    }
    private void capacityLock() { jdbc.queryForObject("SELECT id FROM upload_capacity WHERE id=1 FOR UPDATE", Integer.class); }
    private Instant now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant(); }
    private void touch(String id) { jdbc.update("UPDATE upload_session SET state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id); }
    private void failed(String id, String code) { jdbc.update("UPDATE upload_session SET status='FAILED',merge_token=NULL,merge_lease_until=NULL,error_code=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", code, id); }

    private static void writable(Session current) {
        if ("EXPIRED".equals(current.status())) throw expired();
        if ("FAILED".equals(current.status())) throw conflict("UPLOAD_FAILED", "Upload failed validation; start a new upload");
        if ("MERGING".equals(current.status())) throw conflict("UPLOAD_NOT_WRITABLE", "Upload input is frozen while merging");
    }
    private static void requirePartIndex(UploadModels.Source source, int index) {
        if (index >= source.totalParts()) throw bad("INVALID_PART_INDEX", "Part index is outside this upload");
    }
    private static void requireCompleteParts(UploadModels.Source source, List<UploadModels.Part> parts) {
        if (parts.size() != source.totalParts()) throw conflict("UPLOAD_PARTS_MISSING", "All parts must be received before merging");
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).index() != i || parts.get(i).sizeBytes() != UploadStorage.expectedPartSize(source, i)) {
            throw conflict("UPLOAD_PARTS_MISSING", "Complete ordered part receipts are required");
        }
    }
    private static boolean sameVideo(VideoAsset asset, VideoStorage.StoredVideo value) {
        return asset.id().equals(value.id()) && asset.sourceKey().equals(value.sourceKey()) && asset.contentHash().equals(value.contentHash())
                && asset.sizeBytes() == value.sizeBytes() && asset.originalFilename().equals(value.originalFilename());
    }
    private static int index(String value) {
        try { if (value == null || !value.matches("0|[1-9][0-9]{0,9}")) throw new IllegalArgumentException(); return Integer.parseInt(value); }
        catch (IllegalArgumentException invalid) { throw bad("INVALID_PART_INDEX", "Part index must be a nonnegative integer"); }
    }
    private static String id(String value) {
        try { String normalized = UUID.fromString(value).toString(); if (!normalized.equalsIgnoreCase(value)) throw new IllegalArgumentException(); return normalized; }
        catch (IllegalArgumentException | NullPointerException invalid) { throw bad("INVALID_UPLOAD_ID", "Upload ID must be a UUID"); }
    }
    private static String validHash(String hash, String code) { if (hash == null || !hash.matches("[a-f0-9]{64}")) throw bad(code, "Provide a lowercase SHA256 digest"); return hash; }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("JDK SHA-256 is required"); }
    }
    private static ThreadFactory threads(String prefix) {
        return runnable -> { Thread thread = new Thread(runnable, prefix + "-" + UUID.randomUUID()); thread.setDaemon(true); return thread; };
    }
    private static VideoApiException bad(String code, String message) { return new VideoApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static VideoApiException conflict(String code, String message) { return new VideoApiException(HttpStatus.CONFLICT, code, message); }
    private static VideoApiException expired() { return new VideoApiException(HttpStatus.GONE, "UPLOAD_EXPIRED", "Upload session has expired"); }

    @PreDestroy public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scanner.shutdownNow(); mergeWorkers.shutdownNow();
        try { mergeWorkers.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
