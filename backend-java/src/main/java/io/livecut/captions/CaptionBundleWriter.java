package io.livecut.captions;

import io.livecut.exports.ExportModels.Content;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds a complete verified archive on disk before HTTP commits its headers. */
public final class CaptionBundleWriter implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OWNED = "caption-pack-[0-9a-f-]{36}\\.(part|zip)";
    private static final long OVERHEAD = 16L * 1024 * 1024;
    private final Path directory;
    private final long maxBytes, temporaryQuota, lifetimeSeconds, buildSeconds;
    private final Semaphore slots;
    private final ScheduledThreadPoolExecutor cleanup;
    private final ConcurrentMap<Path, Prepared> active = new ConcurrentHashMap<>();
    private final Set<Path> inFlight = ConcurrentHashMap.newKeySet();
    private final Object quotaGuard = new Object();
    private long reservedBytes;

    public record Sidecar(String extension, String contentType, byte[] bytes) {}
    public record Item(String clipId, long captionRevision, String title, String contentHash,
                       Content video, List<Sidecar> sidecars, Map<String, Object> source) {}
    public record Plan(String clipId, long captionRevision, long mediaBytes) {}
    private static final class Reservation {
        private long inputBytes, reserved;
        private Reservation(long mediaBytes) { inputBytes = mediaBytes; reserved = mediaBytes + OVERHEAD; }
    }

    public final class Prepared implements AutoCloseable {
        private final Path path;
        private final long sizeBytes;
        private final String hash;
        private final long reservation;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> expiry;
        private Prepared(Path path, long sizeBytes, String hash, long reservation) {
            this.path = path; this.sizeBytes = sizeBytes; this.hash = hash; this.reservation = reservation;
        }
        public Path path() { return path; }
        public long sizeBytes() { return sizeBytes; }
        public String hash() { return hash; }
        public String filename() { return "livecut-subtitle-materials.zip"; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ScheduledFuture<?> task = expiry;
            if (task != null) task.cancel(false);
            active.remove(path);
            deleteOwned(path);
            synchronized (quotaGuard) { inFlight.remove(path); reservedBytes -= reservation; }
            slots.release();
        }
    }

    public CaptionBundleWriter(Path storageRoot, long maxBytes, int concurrency,
                               long temporaryQuota, long buildSeconds, long lifetimeSeconds) {
        if (maxBytes < 1 || maxBytes > 8L * 1024 * 1024 * 1024 || concurrency < 1 || concurrency > 8
                || temporaryQuota < maxBytes + OVERHEAD || buildSeconds < 1 || buildSeconds > 900
                || lifetimeSeconds < buildSeconds || lifetimeSeconds > 3600) throw new IllegalArgumentException("Invalid delivery limits");
        this.maxBytes = maxBytes; this.temporaryQuota = temporaryQuota;
        this.buildSeconds = buildSeconds; this.lifetimeSeconds = lifetimeSeconds;
        slots = new Semaphore(concurrency);
        cleanup = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "caption-package-cleanup"); thread.setDaemon(true); return thread;
        });
        cleanup.setRemoveOnCancelPolicy(true);
        try {
            Files.createDirectories(storageRoot);
            Path root = storageRoot.toRealPath();
            directory = root.resolve("delivery-tmp");
            Files.createDirectories(directory);
            verifyDirectory(root);
        } catch (IOException invalid) { cleanup.shutdownNow(); throw storageFailure(); }
    }

    public Prepared prepare(String exportId, List<Item> items) {
        // Adapter for callers that already own small synthetic byte arrays; production uses the lazy loader.
        if (items == null || items.isEmpty() || items.size() > 100) throw invalidRequest();
        List<Plan> plans = new ArrayList<>();
        Map<String, Item> indexed = new HashMap<>();
        for (Item item : items) {
            if (item == null || item.video() == null) throw invalidRequest();
            plans.add(new Plan(item.clipId(), item.captionRevision(), item.video().sizeBytes()));
            indexed.put(item.clipId(), item);
        }
        return prepare(exportId, plans, plan -> indexed.get(plan.clipId()));
    }

    public Prepared prepare(String exportId, List<Plan> plans, Function<Plan, Item> loader) {
        uuid(exportId);
        if (loader == null) throw invalidRequest();
        if (plans == null) throw invalidRequest();
        List<Plan> frozenPlans = new ArrayList<>(plans);
        long mediaBytes = validatePlans(frozenPlans);
        if (!slots.tryAcquire()) throw failure(HttpStatus.TOO_MANY_REQUESTS, "CAPTION_PACKAGE_BUSY", "Other material packages are being prepared or downloaded; retry shortly");
        Path temporary = null, published = null;
        boolean transferred = false;
        Reservation reservation = null;
        try {
            verifyDirectory(directory.getParent());
            String stem = "caption-pack-" + UUID.randomUUID();
            temporary = directory.resolve(stem + ".part");
            published = directory.resolve(stem + ".zip");
            synchronized (quotaGuard) {
                reserve(mediaBytes + OVERHEAD);
                reservation = new Reservation(mediaBytes);
                inFlight.add(temporary); inFlight.add(published);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(buildSeconds);
            MessageDigest digest = digest();
            BoundedOutput output;
            List<Map<String, Object>> files = new ArrayList<>();
            List<Map<String, Object>> clips = new ArrayList<>();
            try (OutputStream raw = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW);
                 BoundedOutput bounded = new BoundedOutput(new DigestOutputStream(raw, digest), maxBytes + OVERHEAD, deadline);
                 ZipOutputStream archive = new ZipOutputStream(bounded, StandardCharsets.UTF_8)) {
                output = bounded;
                // MP4 is already compressed; stream without trying to recompress gigabytes.
                archive.setLevel(Deflater.NO_COMPRESSION);
                for (Plan plan : frozenPlans) {
                    checkDeadline(deadline);
                    // The helper releases its Item/sidecar references before the next loader is invoked.
                    appendItem(archive, plan, loader, reservation, deadline, files, clips);
                }
                byte[] readme = ("LiveCut AI subtitle materials\n\n"
                        + "Each clip folder contains the original clean MP4 and separate SRT, VTT, TXT and caption-review JSON files.\n"
                        + "Subtitles are not burned into the MP4. Import SRT/VTT into your editor or video platform.\n"
                        + "Times use the concatenated source edit list at sentence precision, not word alignment or measured per-seam PTS.\n"
                        + "ASR can contain recognition errors. Boundary-truncated sentences require explicit human review before confirmation.\n"
                        + "manifest.json records exact entry sizes and SHA-256 hashes. It does not claim an independent accuracy evaluation.\n").getBytes(StandardCharsets.UTF_8);
                addBytes(archive, "README.txt", readme, deadline);
                files.add(file("README.txt", readme.length, hash(readme), "text/plain; charset=UTF-8", null, null));
                Map<String, Object> manifest = new TreeMap<>();
                manifest.put("schema_version", "1.0"); manifest.put("data_kind", "caption_delivery_package");
                manifest.put("export_id", exportId); manifest.put("video_has_burned_captions", false);
                manifest.put("clips", clips); manifest.put("files", files);
                addBytes(archive, "manifest.json", JSON.writeValueAsBytes(manifest), deadline);
            }
            Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
            Prepared ready = new Prepared(published, output.count, HexFormat.of().formatHex(digest.digest()), reservation.reserved);
            inFlight.remove(temporary);
            active.put(published, ready);
            transferred = true;
            try { ready.expiry = cleanup.schedule(ready::close, lifetimeSeconds, TimeUnit.SECONDS); }
            catch (RejectedExecutionException stopped) { ready.close(); throw storageFailure(); }
            return ready;
        } catch (IOException invalid) {
            throw storageFailure();
        } catch (ArithmeticException invalid) {
            throw quotaFailure();
        } finally {
            if (!transferred) {
                deleteOwned(temporary); deleteOwned(published);
                if (reservation != null) synchronized (quotaGuard) {
                    inFlight.remove(temporary); inFlight.remove(published); reservedBytes -= reservation.reserved;
                }
                slots.release();
            }
        }
    }

    private long validatePlans(List<Plan> plans) {
        if (plans == null || plans.isEmpty() || plans.size() > 100) throw invalidRequest();
        long mediaBytes = 0;
        Set<String> ids = new HashSet<>();
        for (Plan plan : plans) {
            if (plan == null) throw invalidRequest();
            uuid(plan.clipId());
            if (!ids.add(plan.clipId()) || plan.captionRevision() < 0 || plan.mediaBytes() < 1) throw invalidRequest();
            mediaBytes = addSize(mediaBytes, plan.mediaBytes());
        }
        return mediaBytes;
    }

    private long validateItem(Plan plan, Item item) {
        if (item == null || !plan.clipId().equals(item.clipId()) || plan.captionRevision() != item.captionRevision()
                || item.title() == null || item.contentHash() == null || !item.contentHash().matches("[a-f0-9]{64}")
                || item.video() == null || item.video().path() == null || item.video().sizeBytes() != plan.mediaBytes()
                || item.video().hash() == null || !item.video().hash().matches("[a-f0-9]{64}") || item.source() == null
                || item.sidecars() == null || item.sidecars().size() != 4) throw invalidRequest();
        long sidecarBytes = 0;
        Set<String> extensions = new HashSet<>();
        for (Sidecar sidecar : item.sidecars()) {
            if (sidecar == null || sidecar.extension() == null || !Set.of("srt", "vtt", "txt", "json").contains(sidecar.extension())
                    || !extensions.add(sidecar.extension()) || sidecar.bytes() == null
                    || sidecar.bytes().length > 16 * 1024 * 1024 || sidecar.contentType() == null) throw invalidRequest();
            sidecarBytes = addSize(sidecarBytes, sidecar.bytes().length);
        }
        return sidecarBytes;
    }

    private void appendItem(ZipOutputStream archive, Plan plan, Function<Plan, Item> loader, Reservation reservation,
                            long deadline, List<Map<String, Object>> files, List<Map<String, Object>> clips) throws IOException {
        Item item = loader.apply(plan);
        checkDeadline(deadline);
        long sidecarBytes = validateItem(plan, item);
        long inputBytes = addSize(reservation.inputBytes, sidecarBytes);
        synchronized (quotaGuard) {
            reserve(sidecarBytes);
            reservation.reserved += sidecarBytes;
            reservation.inputBytes = inputBytes;
        }
        String stemName = "clips/" + item.clipId() + "/clip-r" + item.captionRevision();
        String mediaName = stemName + ".mp4";
        archive.putNextEntry(entry(mediaName));
        MessageDigest mediaDigest = digest();
        long copied = 0;
        try (InputStream input = Files.newInputStream(item.video().path())) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkDeadline(deadline);
                if (count == 0) continue;
                copied = Math.addExact(copied, count);
                if (copied > item.video().sizeBytes()) throw integrityFailure();
                mediaDigest.update(buffer, 0, count); archive.write(buffer, 0, count);
            }
        }
        String copiedHash = HexFormat.of().formatHex(mediaDigest.digest());
        if (copied != item.video().sizeBytes() || !copiedHash.equals(item.video().hash())) throw integrityFailure();
        archive.closeEntry();
        files.add(file(mediaName, copied, copiedHash, "video/mp4", item.clipId(), item.captionRevision()));
        for (Sidecar sidecar : item.sidecars()) {
            String name = stemName + "." + sidecar.extension();
            addBytes(archive, name, sidecar.bytes(), deadline);
            files.add(file(name, sidecar.bytes().length, hash(sidecar.bytes()), sidecar.contentType(), item.clipId(), item.captionRevision()));
        }
        Map<String, Object> clip = new TreeMap<>();
        clip.put("clip_id", item.clipId()); clip.put("title", item.title());
        clip.put("caption_revision", item.captionRevision()); clip.put("caption_content_hash", item.contentHash());
        clip.put("source", item.source()); clips.add(clip);
    }

    /** Caller holds quotaGuard; a loader must never execute while this monitor is held. */
    private void reserve(long incomingReservation) throws IOException {
        inventory(incomingReservation);
        if (Files.getFileStore(directory).getUsableSpace() < incomingReservation + reservedBytes) throw quotaFailure();
        reservedBytes += incomingReservation;
    }

    private long addSize(long sum, long value) {
        if (value > maxBytes || sum > maxBytes - value) throw quotaFailure();
        return sum + value;
    }

    private void inventory(long incomingReservation) throws IOException {
        long retained = 0;
        long cutoff = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(lifetimeSeconds);
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (Path path : paths) {
                if (!path.getFileName().toString().matches(OWNED) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (inFlight.contains(path)) continue;
                if (++count > 1000) throw quotaFailure();
                if (!active.containsKey(path) && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() < cutoff) deleteOwned(path);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    long size = Files.size(path);
                    if (retained > temporaryQuota - size) throw quotaFailure();
                    retained += size;
                }
            }
        }
        if (incomingReservation > temporaryQuota - retained - reservedBytes) throw quotaFailure();
    }

    private void verifyDirectory(Path root) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(root.resolve("delivery-tmp"))) throw storageFailure();
    }

    private void deleteOwned(Path path) {
        if (path == null || !path.toAbsolutePath().normalize().getParent().equals(directory)
                || !path.getFileName().toString().matches(OWNED)) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { /* Counted by the next disk-quota scan. */ }
    }

    private static Map<String, Object> file(String path, long size, String hash, String type, String clip, Long revision) {
        Map<String, Object> value = new TreeMap<>();
        value.put("path", path); value.put("size_bytes", size); value.put("sha256", hash); value.put("content_type", type);
        if (clip != null) { value.put("clip_id", clip); value.put("caption_revision", revision); }
        return value;
    }
    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name); entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)); return entry;
    }
    private static void addBytes(ZipOutputStream archive, String name, byte[] bytes, long deadline) throws IOException {
        checkDeadline(deadline); archive.putNextEntry(entry(name)); archive.write(bytes); archive.closeEntry();
    }
    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CAPTION_PACKAGE_TIMEOUT", "Material packaging exceeded its bounded preparation time; retry the download");
    }
    private static void uuid(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw invalidRequest();
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static JobException failure(HttpStatus status, String code, String message) { return new JobException(status, code, message); }
    private static JobException invalidRequest() { return failure(HttpStatus.BAD_REQUEST, "INVALID_CAPTION_PACKAGE", "Select 1..100 unique completed clips with explicit confirmed caption revisions"); }
    private static JobException integrityFailure() { return failure(HttpStatus.CONFLICT, "CAPTION_PACKAGE_INTEGRITY_ERROR", "A clip changed while the package was being copied; no archive was published"); }
    private static JobException quotaFailure() { return failure(HttpStatus.INSUFFICIENT_STORAGE, "CAPTION_PACKAGE_LIMIT", "Material package or temporary storage limit exceeded"); }
    private static JobException storageFailure() { return failure(HttpStatus.SERVICE_UNAVAILABLE, "CAPTION_PACKAGE_STORAGE_ERROR", "Material package storage is unavailable"); }

    private static final class BoundedOutput extends FilterOutputStream {
        private final long limit, deadline;
        private long count;
        private BoundedOutput(OutputStream output, long limit, long deadline) { super(output); this.limit = limit; this.deadline = deadline; }
        @Override public void write(int value) throws IOException {
            checkDeadline(deadline); if (++count > limit) throw quotaFailure(); out.write(value);
        }
        @Override public void write(byte[] bytes, int start, int length) throws IOException {
            checkDeadline(deadline); if (count > limit - length) throw quotaFailure(); count += length; out.write(bytes, start, length);
        }
    }

    @Override public void close() {
        cleanup.shutdownNow();
        active.values().forEach(Prepared::close);
    }
}
