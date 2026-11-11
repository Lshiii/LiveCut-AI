package io.livecut.exports;

import io.livecut.captions.CaptionBundleWriter;
import io.livecut.downloads.DeliveryBundleWriter;
import io.livecut.exports.ExportModels.Content;
import io.livecut.jobs.ArtifactVerifier.SourcePart;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic archives verify delivery bytes and bounds, not MP4 decoding or model quality. */
class ExportVideoBundleWriterTests {
    private static final String EXPORT = "11111111-1111-4111-8111-111111111111";
    private static final String JOB = "22222222-2222-4222-8222-222222222222";
    private static final String VIDEO = "33333333-3333-4333-8333-333333333333";
    private static final String FIRST = "44444444-4444-4444-8444-444444444444";
    private static final String SECOND = "55555555-5555-4555-8555-555555555555";
    private static final String REQUEST = "66666666-6666-4666-8666-666666666666";
    private static final long OVERHEAD = 16L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path root;

    private CaptionBundleWriter writer(long maximum, int slots, long quota) {
        return new CaptionBundleWriter(root, maximum, slots, quota, 30, 60);
    }

    private ExportVideoModels.Clip clip(String id, byte[] bytes, List<SourcePart> parts) throws Exception {
        Files.write(root.resolve(id + ".mp4"), bytes);
        long duration = parts.stream().mapToLong(part -> part.endMs() - part.startMs()).sum();
        return new ExportVideoModels.Clip(id, "segment-" + id,
                "../../任意标题 / 不应成为 ZIP 路径", parts.getFirst().startMs(), parts.getLast().endMs(),
                duration, parts, bytes.length, DeliveryBundleWriter.hash(bytes), null);
    }

    private ExportVideoModels.Plan plan(long revision, String request, ExportVideoModels.Clip... clips) {
        return new ExportVideoModels.Plan(EXPORT, JOB, VIDEO, "a".repeat(64), "b".repeat(64),
                revision, "h264-aac-v1", request, List.of(clips));
    }

    private Content content(ExportVideoModels.Clip clip) {
        return new Content(root.resolve(clip.clipId() + ".mp4"), clip.sizeBytes(), clip.contentHash(), "ignored-title.mp4");
    }

    private CaptionBundleWriter.Item subtitleItem(ExportVideoModels.Clip clip) {
        return new CaptionBundleWriter.Item(clip.clipId(), 2, "字幕兼容回归", "c".repeat(64), content(clip),
                List.of(new CaptionBundleWriter.Sidecar("srt", "application/x-subrip", new byte[0]),
                        new CaptionBundleWriter.Sidecar("vtt", "text/vtt", "WEBVTT\n\n".getBytes(StandardCharsets.UTF_8)),
                        new CaptionBundleWriter.Sidecar("txt", "text/plain", new byte[0]),
                        new CaptionBundleWriter.Sidecar("json", "application/json", "{}".getBytes(StandardCharsets.UTF_8))),
                Map.of("timing_basis", "SOURCE_EDL_SENTENCE_MS"));
    }

    @Test void archiveContainsOnlySelectedMp4sReadmeAndExactPublicManifest() throws Exception {
        byte[] firstBytes = new byte[64 * 1024 + 7]; new Random(81).nextBytes(firstBytes);
        byte[] secondBytes = {9, 8, 7, 6};
        var first = clip(FIRST, firstBytes, List.of(new SourcePart(1000, 3500)));
        var second = clip(SECOND, secondBytes, List.of(new SourcePart(5000, 6000), new SourcePart(9000, 11000)));
        AtomicInteger loads = new AtomicInteger();
        try (CaptionBundleWriter writer = writer(1_000_000, 2, OVERHEAD * 3);
             var ready = writer.prepareVideos(plan(7, REQUEST, first, second), selected -> {
                 loads.incrementAndGet(); return content(selected);
             }); ZipFile zip = new ZipFile(ready.path().toFile(), StandardCharsets.UTF_8)) {
            assertEquals(2, loads.get());
            assertEquals("export-" + EXPORT + "-videos.zip", ready.filename());
            assertEquals(Files.size(ready.path()), ready.sizeBytes());
            assertEquals(DeliveryBundleWriter.hash(Files.readAllBytes(ready.path())), ready.hash());
            assertEquals(Set.of("clips/001-" + FIRST + ".mp4", "clips/002-" + SECOND + ".mp4", "README.txt", "manifest.json"),
                    new HashSet<>(zip.stream().map(entry -> entry.getName()).toList()));
            assertArrayEquals(firstBytes, zip.getInputStream(zip.getEntry("clips/001-" + FIRST + ".mp4")).readAllBytes());
            assertArrayEquals(secondBytes, zip.getInputStream(zip.getEntry("clips/002-" + SECOND + ".mp4")).readAllBytes());

            String manifestText = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
            JsonNode manifest = JSON.readTree(manifestText);
            assertEquals(Set.of("schema_version", "data_kind", "export_id", "job_id", "video_id", "source_video_sha256",
                    "approved_revision", "snapshot_hash", "profile", "render_mode", "clip_request_id", "clips", "files"),
                    Set.copyOf(manifest.propertyNames()));
            assertEquals("1.0", manifest.path("schema_version").asText());
            assertEquals("export_video_package", manifest.path("data_kind").asText());
            assertEquals(EXPORT, manifest.path("export_id").asText());
            assertEquals(JOB, manifest.path("job_id").asText());
            assertEquals(VIDEO, manifest.path("video_id").asText());
            assertEquals("a".repeat(64), manifest.path("source_video_sha256").asText());
            assertEquals("b".repeat(64), manifest.path("snapshot_hash").asText());
            assertEquals(7, manifest.path("approved_revision").longValue());
            assertEquals("h264-aac-v1", manifest.path("profile").asText());
            assertEquals("CLEAN", manifest.path("render_mode").asText());
            assertEquals(REQUEST, manifest.path("clip_request_id").asText());
            assertEquals(2, manifest.path("clips").size());
            assertEquals(FIRST, manifest.path("clips").get(0).path("clip_id").asText());
            assertEquals(first.title(), manifest.path("clips").get(0).path("title").asText());
            assertEquals(2500, manifest.path("clips").get(0).path("duration_ms").longValue());
            assertEquals(1, manifest.path("clips").get(0).path("parts").size());
            assertEquals(3000, manifest.path("clips").get(1).path("duration_ms").longValue());
            assertEquals(5000, manifest.path("clips").get(1).path("start_ms").longValue());
            assertEquals(11000, manifest.path("clips").get(1).path("end_ms").longValue());
            assertEquals(2, manifest.path("clips").get(1).path("parts").size());
            assertEquals(9000, manifest.path("clips").get(1).path("parts").get(1).path("start_ms").longValue());
            assertEquals(11000, manifest.path("clips").get(1).path("parts").get(1).path("end_ms").longValue());
            assertFalse(manifestText.contains("caption_revision"));
            assertFalse(manifestText.contains("storage_key"));
            assertFalse(manifestText.contains("run_token"));
            assertFalse(manifestText.contains(root.toString()));
            assertEquals(3, manifest.path("files").size());
            for (var file : manifest.path("files")) {
                String name = file.path("path").asText();
                byte[] actual = zip.getInputStream(zip.getEntry(name)).readAllBytes();
                assertEquals(actual.length, file.path("size_bytes").longValue());
                assertEquals(DeliveryBundleWriter.hash(actual), file.path("sha256").asText());
                assertEquals(name.endsWith(".mp4") ? "video/mp4" : "text/plain; charset=UTF-8", file.path("content_type").asText());
            }
        }
        assertNoArchives();
    }

    @Test void legalExportRevisionZeroNeedsNoSyntheticCaptionRevisionOrSidecars() throws Exception {
        var source = clip(FIRST, new byte[]{1, 2, 3}, List.of(new SourcePart(0, 1000)));
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2);
             var ready = writer.prepareVideos(plan(0, null, source), this::content);
             ZipFile zip = new ZipFile(ready.path().toFile(), StandardCharsets.UTF_8)) {
            assertEquals(3, zip.size());
            var manifest = JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
            assertEquals(0, manifest.path("approved_revision").longValue());
            assertTrue(manifest.path("clip_request_id").isNull());
            assertFalse(manifest.path("clips").get(0).has("caption_revision"));
            assertTrue(zip.stream().noneMatch(entry -> entry.getName().endsWith(".srt") || entry.getName().endsWith(".vtt")
                    || entry.getName().endsWith(".txt") && !entry.getName().equals("README.txt")));
        }
        assertNoArchives();
    }

    @Test void subtitleAndVideoDownloadsShareTheSameTwoSlots() throws Exception {
        var source = clip(FIRST, new byte[]{1, 2, 3}, List.of(new SourcePart(0, 1000)));
        try (CaptionBundleWriter writer = writer(100_000, 2, OVERHEAD * 4)) {
            var video = writer.prepareVideos(plan(1, null, source), this::content);
            try (var subtitle = writer.prepare(EXPORT, List.of(subtitleItem(source)))) {
                assertEquals("VIDEO_PACKAGE_BUSY", assertThrows(JobException.class,
                        () -> writer.prepareVideos(plan(1, null, source), ignored -> {throw new AssertionError("busy loader");})).getCode());
                assertEquals("CAPTION_PACKAGE_BUSY", assertThrows(JobException.class,
                        () -> writer.prepare(EXPORT, List.of(subtitleItem(source)))).getCode());
                video.close(); video.close();
                try (var replacement = writer.prepareVideos(plan(1, null, source), this::content)) {
                    assertTrue(Files.exists(replacement.path()));
                    assertTrue(Files.exists(subtitle.path()));
                }
            } finally { video.close(); }
        }
        assertNoArchives();
    }

    @Test void subtitleAndVideoDownloadsShareTemporaryQuotaAndReleaseItBothWays() throws Exception {
        var source = clip(FIRST, new byte[]{1, 2, 3}, List.of(new SourcePart(0, 1000)));
        try (CaptionBundleWriter writer = writer(100_000, 2, OVERHEAD + 100_000)) {
            try (var video = writer.prepareVideos(plan(1, null, source), this::content)) {
                assertEquals("CAPTION_PACKAGE_LIMIT", assertThrows(JobException.class,
                        () -> writer.prepare(EXPORT, List.of(subtitleItem(source)))).getCode());
            }
            try (var subtitle = writer.prepare(EXPORT, List.of(subtitleItem(source)))) {
                assertEquals("VIDEO_PACKAGE_LIMIT", assertThrows(JobException.class,
                        () -> writer.prepareVideos(plan(1, null, source), ignored -> {throw new AssertionError("quota loader");})).getCode());
            }
            try (var ready = writer.prepareVideos(plan(1, null, source), this::content)) { assertTrue(Files.exists(ready.path())); }
        }
        assertNoArchives();
    }

    @Test void changingVerifiedSourceBeforeCopyRejectsEntireArchiveAndRecoversCapacity() throws Exception {
        var first = clip(FIRST, new byte[]{1, 2, 3}, List.of(new SourcePart(0, 1000)));
        var second = clip(SECOND, new byte[]{4, 5, 6}, List.of(new SourcePart(2000, 3000)));
        AtomicInteger loads = new AtomicInteger();
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            assertEquals("VIDEO_PACKAGE_INTEGRITY_ERROR", assertThrows(JobException.class,
                    () -> writer.prepareVideos(plan(1, null, first, second), selected -> {
                        loads.incrementAndGet();
                        Content verified = content(selected);
                        if (selected.clipId().equals(SECOND)) {
                            try { Files.write(verified.path(), new byte[]{6, 5, 4}); }
                            catch (Exception failed) { throw new AssertionError(failed); }
                        }
                        return verified;
                    })).getCode());
            assertEquals(2, loads.get());
            assertNoArchives();
            try (var ready = writer.prepareVideos(plan(1, null, first), this::content)) {assertTrue(Files.exists(ready.path()));}
        }
        assertNoArchives();
    }

    @Test void missingSelectedFileAndLoaderFailureNeverPublishPartialArchive() throws Exception {
        var source = clip(FIRST, new byte[]{1, 2, 3}, List.of(new SourcePart(0, 1000)));
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            Files.delete(content(source).path());
            assertEquals("VIDEO_PACKAGE_STORAGE_ERROR", assertThrows(JobException.class,
                    () -> writer.prepareVideos(plan(1, null, source), this::content)).getCode());
            assertNoArchives();
            assertThrows(IllegalStateException.class,
                    () -> writer.prepareVideos(plan(1, null, source), ignored -> {throw new IllegalStateException("synthetic failure");}));
            assertNoArchives();
            Files.write(content(source).path(), new byte[]{1, 2, 3});
            try (var ready = writer.prepareVideos(plan(1, null, source), this::content)) {assertTrue(Files.exists(ready.path()));}
        }
        assertNoArchives();
    }

    @Test void oversizedDuplicateEmptyAndTooManySelectionsNeverInvokeLoader() throws Exception {
        var source = clip(FIRST, new byte[]{1, 2, 3}, List.of(new SourcePart(0, 1000)));
        var oversized = new ExportVideoModels.Clip(FIRST, source.segmentId(), source.title(), 0, 1000, 1000,
                source.parts(), 101, source.contentHash(), null);
        AtomicInteger loads = new AtomicInteger();
        java.util.function.Function<ExportVideoModels.Clip, Content> forbiddenLoader = ignored -> {
            loads.incrementAndGet(); throw new AssertionError("Invalid admission must not load media");
        };
        try (CaptionBundleWriter writer = writer(100, 1, OVERHEAD * 2)) {
            assertEquals("VIDEO_PACKAGE_LIMIT", assertThrows(JobException.class,
                    () -> writer.prepareVideos(plan(1, null, oversized), forbiddenLoader)).getCode());
            assertEquals("INVALID_VIDEO_PACKAGE", assertThrows(JobException.class,
                    () -> writer.prepareVideos(plan(1, null, source, source), forbiddenLoader)).getCode());
            assertEquals("INVALID_VIDEO_PACKAGE", assertThrows(JobException.class,
                    () -> writer.prepareVideos(plan(1, null), forbiddenLoader)).getCode());
            List<ExportVideoModels.Clip> tooMany = new ArrayList<>();
            for (int index = 0; index < 101; index++) {
                tooMany.add(new ExportVideoModels.Clip(new UUID(0, index + 1).toString(), source.segmentId(), source.title(),
                        0, 1000, 1000, source.parts(), 1, source.contentHash(), null));
            }
            var manyPlan = new ExportVideoModels.Plan(EXPORT, JOB, VIDEO, "a".repeat(64), "b".repeat(64),
                    1, "h264-aac-v1", null, tooMany);
            assertEquals("INVALID_VIDEO_PACKAGE", assertThrows(JobException.class,
                    () -> writer.prepareVideos(manyPlan, forbiddenLoader)).getCode());
            assertEquals(0, loads.get());
        }
        assertNoArchives();
    }

    @Test void closingSharedWriterRemovesPendingVideoDownloadIdempotently() throws Exception {
        var source = clip(FIRST, new byte[]{1, 2, 3}, List.of(new SourcePart(0, 1000)));
        CaptionBundleWriter writer = writer(100_000, 2, OVERHEAD * 3);
        var prepared = writer.prepareVideos(plan(1, null, source), this::content);
        Path archive = prepared.path();
        writer.close(); writer.close(); prepared.close();
        assertFalse(Files.exists(archive));
        assertNoArchives();
    }

    @Test void interruptedPreparationReturnsVideoTimeoutAndReleasesCapacity() throws Exception {
        var source = clip(FIRST, new byte[]{1, 2, 3}, List.of(new SourcePart(0, 1000)));
        AtomicInteger loads = new AtomicInteger();
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            try {
                Thread.currentThread().interrupt();
                assertEquals("VIDEO_PACKAGE_TIMEOUT", assertThrows(JobException.class,
                        () -> writer.prepareVideos(plan(1, null, source), selected -> {
                            loads.incrementAndGet(); return content(selected);
                        })).getCode());
            } finally { Thread.interrupted(); }
            assertEquals(0, loads.get());
            assertNoArchives();
            try (var ready = writer.prepareVideos(plan(1, null, source), this::content)) {assertTrue(Files.exists(ready.path()));}
        }
        assertNoArchives();
    }

    private void assertNoArchives() throws Exception {
        try (var paths = Files.list(root.resolve("delivery-tmp"))) {assertEquals(0, paths.count());}
    }
}
