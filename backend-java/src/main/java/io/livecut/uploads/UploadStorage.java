package io.livecut.uploads;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.livecut.video.VideoApiException;
import io.livecut.video.VideoConfiguration;
import io.livecut.video.VideoStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bounded streamed files. No method recursively deletes a directory or touches another source. */
@Component
public class UploadStorage {
    // A second channel close can release another channel's native lock on some
    // platforms. Reserve the normalized path before opening any locking channel.
    private static final Set<Path> LOCAL_LOCKS = ConcurrentHashMap.newKeySet();
    private final Path root;
    private final Path sessions;
    private final Path incoming;
    private final VideoStorage videos;

    public record Incoming(Path path, long sizeBytes, String sha256) {}
    public record FileStamp(long size, FileTime modified, Object key) {}
    public record Prepared(UploadModels.Source source, Path candidate, boolean existing, FileStamp stamp) {}

    /** Stable guard inode stays in the session directory after its payload is moved/deleted. */
    public static final class IncomingLease implements AutoCloseable {
        private final Path path;
        private final SessionLock guard;
        private IncomingLease(Path path, SessionLock guard) { this.path = path; this.guard = guard; }
        @Override public void close() { guard.close(); }
    }

    public static final class SessionLock implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final FileLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();
        private SessionLock(Path path, FileChannel channel, FileLock lock) { this.path = path; this.channel = channel; this.lock = lock; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { lock.release(); } catch (IOException ignored) { }
            try { channel.close(); } catch (IOException ignored) { }
            finally { LOCAL_LOCKS.remove(path); }
        }
    }

    public UploadStorage(VideoConfiguration.StorageProperties properties, VideoStorage videos) {
        this.videos = videos;
        try {
            root = properties.root().toRealPath();
            Path uploads = directory(root.resolve("uploads"));
            sessions = directory(uploads.resolve("sessions"));
            incoming = directory(uploads.resolve("incoming"));
        } catch (IOException failure) { throw unavailable(); }
    }

    public SessionLock trySessionLock(String id) {
        try {
            return tryFileLock(session(id).resolve("guard.lock"));
        } catch (IOException failure) { throw unavailable(); }
    }

    private SessionLock tryFileLock(Path path) throws IOException {
        return tryFileLock(path, true);
    }

    private SessionLock tryFileLock(Path path, boolean create) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw unavailable();
        if (!LOCAL_LOCKS.add(normalized)) return null;
        FileChannel channel = null;
        boolean acquired = false;
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) checked(normalized);
            Set<java.nio.file.OpenOption> options = new java.util.HashSet<>(Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            if (create) options.add(StandardOpenOption.CREATE);
            channel = FileChannel.open(normalized, options);
            FileLock lock = channel.tryLock();
            if (lock == null) return null;
            acquired = true;
            return new SessionLock(normalized, channel, lock);
        } catch (OverlappingFileLockException busy) {
            return null;
        } finally {
            if (!acquired) {
                try { if (channel != null) channel.close(); }
                finally { LOCAL_LOCKS.remove(normalized); }
            }
        }
    }

    /** Called under the session DB lock, before any network read. One payload per index. */
    public IncomingLease beginIncoming(String id, int index) {
        if (index < 0) throw unavailable();
        SessionLock guard = null;
        try {
            Path folder = session(id);
            guard = tryFileLock(folder.resolve("receive-" + index + ".lock"));
            if (guard == null) throw new VideoApiException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_RECEIVE_BUSY", "This part already has an active receiver");
            Path path = folder.resolve("incoming-" + index + ".tmp");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(checked(path));
            Files.createFile(path);
            IncomingLease result = new IncomingLease(path, guard);
            guard = null;
            return result;
        } catch (IOException failure) { throw unavailable(); }
        finally { if (guard != null) guard.close(); }
    }

    /** Network I/O is outside DB transactions; the receiver guard remains held through registration. */
    public Incoming receive(IncomingLease lease, InputStream body, long expected, String expectedHash) {
        try (FileChannel channel = FileChannel.open(checked(lease.path), StandardOpenOption.WRITE)) {
            Incoming result = consume(body, Channels.newOutputStream(channel), lease.path, expected, expectedHash);
            channel.force(true);
            return result;
        } catch (IOException failure) { throw unavailable(); }
    }

    /** A receipt replay validates its actual bytes without consuming temporary disk capacity. */
    public Incoming validateReplay(InputStream body, long expected, String expectedHash) {
        try { return consume(body, OutputStream.nullOutputStream(), null, expected, expectedHash); }
        catch (IOException failure) { throw unavailable(); }
    }

    private Incoming consume(InputStream body, OutputStream output, Path path, long expected, String expectedHash) throws IOException {
        MessageDigest digest = digest();
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = body.read(buffer)) != -1) {
            if (count == 0) continue;
            size += count;
            if (size > expected) throw bad("PART_SIZE_MISMATCH", "Part length does not match its declared position");
            digest.update(buffer, 0, count);
            output.write(buffer, 0, count);
        }
        if (size != expected) throw bad("PART_SIZE_MISMATCH", "Part length does not match its declared position");
        String hash = HexFormat.of().formatHex(digest.digest());
        if (!hash.equals(expectedHash)) throw bad("PART_HASH_MISMATCH", "Part bytes do not match X-Chunk-SHA256");
        return new Incoming(path, size, hash);
    }

    public void discardIncoming(IncomingLease lease) {
        if (lease == null) return;
        try { deleteOwned(lease.path); }
        finally { lease.close(); }
    }

    /** Called under the upload row lock. The registered part is never replaced by different bytes. */
    public void publishPart(String id, int index, Incoming value) {
        try {
            Path target = partPath(id, index, value.sha256());
            checked(value.path());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                checked(target);
                if (Files.size(target) != value.sizeBytes() || !hash(target, () -> {}).equals(value.sha256())) {
                    throw conflict("PART_CONFLICT", "An existing part has different bytes");
                }
                return;
            }
            Files.move(value.path(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) { throw unavailable(); }
    }

    public boolean partPresent(String id, UploadModels.Part part) {
        try {
            Path path = partPath(id, part.index(), part.sha256());
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS) && checked(path) != null && Files.size(path) == part.sizeBytes();
        } catch (IOException failure) { throw unavailable(); }
    }

    /** Caller has locked the session and proved this index has no durable receipt. */
    public void discardUnregisteredPart(String id, int index) {
        if (index < 0) throw unavailable();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(session(id), "part-" + index + "-*.part")) {
            for (Path path : files) {
                if (!path.getFileName().toString().matches("part-" + index + "-[a-f0-9]{64}\\.part")) throw unavailable();
                Files.deleteIfExists(checked(path));
            }
        } catch (IOException failure) { throw unavailable(); }
    }

    /** Runs outside DB transactions while the merger holds the per-session file lock. */
    public Prepared prepare(UploadModels.Source source, List<UploadModels.Part> parts, Runnable guard) {
        guard.run();
        Path target = videos.resolve("videos/" + source.videoId() + ".mp4");
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                checked(target);
                validateFile(target, source, guard);
                return new Prepared(source, target, true, stamp(target));
            }
            removeOldMerges(source.uploadId());
            Path merged = Files.createTempFile(session(source.uploadId()), "merge-", ".tmp");
            boolean keep = false;
            try {
                MessageDigest whole = digest();
                byte[] prefix = new byte[64];
                int prefixCount = 0;
                long total = 0;
                try (FileChannel channel = FileChannel.open(merged, StandardOpenOption.WRITE)) {
                    OutputStream output = Channels.newOutputStream(channel);
                    byte[] buffer = new byte[64 * 1024];
                    for (int index = 0; index < parts.size(); index++) {
                        UploadModels.Part part = parts.get(index);
                        if (part.index() != index || part.sizeBytes() != expectedPartSize(source, index)) {
                            throw bad("UPLOAD_PARTS_MISSING", "Complete ordered part receipts are required");
                        }
                        Path path = checked(partPath(source.uploadId(), index, part.sha256()));
                        MessageDigest piece = digest();
                        long pieceSize = 0;
                        try (InputStream input = Files.newInputStream(path)) {
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                guard.run();
                                if (count == 0) continue;
                                pieceSize += count;
                                total += count;
                                if (pieceSize > part.sizeBytes() || total > source.sizeBytes()) {
                                    throw bad("PART_SIZE_MISMATCH", "Stored part length has changed");
                                }
                                int copied = Math.min(count, prefix.length - prefixCount);
                                if (copied > 0) { System.arraycopy(buffer, 0, prefix, prefixCount, copied); prefixCount += copied; }
                                piece.update(buffer, 0, count);
                                whole.update(buffer, 0, count);
                                output.write(buffer, 0, count);
                            }
                        }
                        if (pieceSize != part.sizeBytes() || !HexFormat.of().formatHex(piece.digest()).equals(part.sha256())) {
                            throw bad("PART_HASH_MISMATCH", "A stored part no longer matches its receipt");
                        }
                    }
                    if (parts.size() != source.totalParts() || total != source.sizeBytes()) {
                        throw bad("UPLOAD_PARTS_MISSING", "Complete ordered part receipts are required");
                    }
                    if (!HexFormat.of().formatHex(whole.digest()).equals(source.contentHash())) {
                        throw bad("UPLOAD_HASH_MISMATCH", "Merged video does not match its declared SHA256");
                    }
                    VideoStorage.validateHeader(prefix, prefixCount, total);
                    channel.force(true);
                }
                guard.run();
                Prepared prepared = new Prepared(source, merged, false, stamp(merged));
                keep = true;
                return prepared;
            } finally { if (!keep) deleteMerge(merged, source.uploadId()); }
        } catch (IOException failure) { throw unavailable(); }
    }

    /** Only fast stat checks and atomic move run under the final metadata transaction. */
    public void publish(Prepared prepared) {
        try {
            Path candidate = checked(prepared.candidate());
            if (!prepared.stamp().equals(stamp(candidate))) throw conflict("UPLOAD_SOURCE_CHANGED", "Prepared source changed before publication");
            Path target = videos.resolve("videos/" + prepared.source().videoId() + ".mp4");
            if (prepared.existing()) {
                if (!candidate.equals(target)) throw unavailable();
                return;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw conflict("UPLOAD_SOURCE_CONFLICT", "Reserved source already exists and needs recovery verification");
            Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) { throw unavailable(); }
    }

    public void discardPrepared(Prepared prepared) {
        if (prepared != null && !prepared.existing()) deleteMerge(prepared.candidate(), prepared.source().uploadId());
    }

    /** Caller holds the per-session file lock, and the DB lock prevents a part publish racing cleanup. */
    public boolean cleanupSession(String id) {
        List<SessionLock> receivers = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(session(id))) {
            // Admission takes the same session DB lock. Once all existing guards
            // are owned here, no new receiver can appear before cleanup commits.
            List<Path> payloads = new ArrayList<>();
            for (Path path : files) {
                String name = path.getFileName().toString();
                if (name.equals("guard.lock")) continue;
                if (name.matches("receive-[0-9]+\\.lock")) {
                    SessionLock receiver = tryFileLock(checked(path));
                    if (receiver == null) return false;
                    receivers.add(receiver);
                } else {
                    if (!name.matches("part-[0-9]+-[a-f0-9]{64}\\.part|merge-[A-Za-z0-9_-]+\\.tmp|incoming-[0-9]+\\.tmp")) return false;
                    payloads.add(path);
                }
            }
            for (Path path : payloads) Files.deleteIfExists(checked(path));
            return true;
        } catch (IOException | VideoApiException failure) { return false; }
        finally { for (SessionLock receiver : receivers) receiver.close(); }
    }

    public void cleanupIncoming() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory(incoming), "incoming-*.tmp")) {
            Instant cutoff = Instant.now().minusSeconds(300);
            for (Path path : files) {
                try {
                    checked(path);
                    if (Files.getLastModifiedTime(path).toInstant().isAfter(cutoff)) continue;
                    try (SessionLock lock = tryFileLock(path, false)) {
                        if (lock == null) continue;
                        Files.deleteIfExists(path);
                    }
                } catch (IOException | VideoApiException ignored) { /* retain an unverified/locked file */ }
            }
        } catch (IOException ignored) { /* retried by the next bounded scan */ }
    }

    public static long expectedPartSize(UploadModels.Source source, int index) {
        return Math.min(source.chunkSize(), source.sizeBytes() - (long) index * source.chunkSize());
    }

    private void validateFile(Path path, UploadModels.Source source, Runnable guard) throws IOException {
        if (Files.size(path) != source.sizeBytes()) throw conflict("UPLOAD_SOURCE_CONFLICT", "Reserved source does not match the upload");
        byte[] prefix;
        try (InputStream input = Files.newInputStream(path)) { prefix = input.readNBytes(64); }
        VideoStorage.validateHeader(prefix, prefix.length, source.sizeBytes());
        if (!hash(path, guard).equals(source.contentHash())) throw conflict("UPLOAD_SOURCE_CONFLICT", "Reserved source does not match the upload");
    }

    private String hash(Path path, Runnable guard) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(checked(path))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) { guard.run(); if (count > 0) digest.update(buffer, 0, count); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void removeOldMerges(String id) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(session(id), "merge-*.tmp")) {
            for (Path path : files) Files.deleteIfExists(checked(path));
        }
    }

    private FileStamp stamp(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(checked(path), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    private Path partPath(String id, int index, String hash) throws IOException {
        if (index < 0 || !hash.matches("[a-f0-9]{64}")) throw unavailable();
        return session(id).resolve("part-" + index + "-" + hash + ".part");
    }

    private Path session(String id) throws IOException {
        if (!UUID.fromString(id).toString().equals(id)) throw unavailable();
        return directory(sessions.resolve(id));
    }

    private Path directory(Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(root)) throw unavailable();
        if (!path.equals(root) && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(path); } catch (FileAlreadyExistsException raced) { }
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        return checked(path);
    }

    private Path checked(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized) || !normalized.toRealPath().equals(normalized)) throw unavailable();
        return normalized;
    }

    private void deleteMerge(Path path, String id) {
        try {
            if (!path.getParent().equals(session(id)) || !path.getFileName().toString().matches("merge-[A-Za-z0-9_-]+\\.tmp")) throw unavailable();
            deleteOwned(path);
        } catch (IOException failure) { throw unavailable(); }
    }

    private void deleteOwned(Path path) {
        try { if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(checked(path)); }
        catch (IOException failure) { /* retain for a later confined cleanup */ }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("JDK SHA-256 is required"); }
    }
    private static VideoApiException bad(String code, String message) { return new VideoApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static VideoApiException conflict(String code, String message) { return new VideoApiException(HttpStatus.CONFLICT, code, message); }
    private static VideoApiException unavailable() { return new VideoApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Upload storage is unavailable"); }
}
