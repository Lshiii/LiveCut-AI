package io.livecut.captions;

import io.livecut.exports.ExportModels.Content;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic byte archives test integrity and resource bounds, not video decoding or ASR quality. */
class CaptionBundleWriterTests {
    private static final String EXPORT = "11111111-1111-4111-8111-111111111111";
    private static final String CLIP = "22222222-2222-4222-8222-222222222222";
    private static final String SECOND = "33333333-3333-4333-8333-333333333333";
    private static final long OVERHEAD = 16L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path root;

    private CaptionBundleWriter writer(long maxBytes, int slots, long quota) {
        return new CaptionBundleWriter(root, maxBytes, slots, quota, 30, 60);
    }
    private CaptionBundleWriter.Item item(String id, byte[] bytes, boolean emptySubtitles) throws Exception {
        Path path = root.resolve(id + ".mp4"); Files.write(path, bytes);
        byte[] srt = emptySubtitles ? new byte[0] : "1\n00:00:00,000 --> 00:00:01,000\n核对后的字幕\n\n".getBytes(StandardCharsets.UTF_8);
        return new CaptionBundleWriter.Item(id, 2, "知识视频 / 不用于文件路径", "a".repeat(64),
                new Content(path, bytes.length, CaptionBundleWriter.hash(bytes), id + ".mp4"),
                List.of(new CaptionBundleWriter.Sidecar("srt", "application/x-subrip", srt),
                        new CaptionBundleWriter.Sidecar("vtt", "text/vtt", "WEBVTT\n\n".getBytes(StandardCharsets.UTF_8)),
                        new CaptionBundleWriter.Sidecar("txt", "text/plain", emptySubtitles ? new byte[0] : "核对后的字幕\n".getBytes(StandardCharsets.UTF_8)),
                        new CaptionBundleWriter.Sidecar("json", "application/json", "{}".getBytes(StandardCharsets.UTF_8))),
                Map.of("timing_basis", "SOURCE_EDL_SENTENCE_MS", "duration_ms", 1000, "clip_sha256", CaptionBundleWriter.hash(bytes)));
    }

    @Test void exactEntryBytesManifestAndCleanupSurviveRepeatedClose() throws Exception {
        byte[] first = new byte[64 * 1024 + 13]; new Random(77).nextBytes(first);
        byte[] second = new byte[] { 9, 8, 7, 6 };
        try (CaptionBundleWriter writer = writer(1_000_000, 2, OVERHEAD * 3)) {
            var ready = writer.prepare(EXPORT, List.of(item(CLIP, first, false), item(SECOND, second, true)));
            Path zipPath = ready.path();
            assertEquals(Files.size(zipPath), ready.sizeBytes());
            assertEquals(CaptionBundleWriter.hash(Files.readAllBytes(zipPath)), ready.hash());
            try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
                assertEquals(12, zip.size());
                var manifest = JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
                assertEquals("caption_delivery_package", manifest.path("data_kind").asText());
                assertFalse(manifest.path("video_has_burned_captions").booleanValue());
                assertEquals(2, manifest.path("clips").size());
                assertEquals(11, manifest.path("files").size());
                for (var file : manifest.path("files")) {
                    String entry = file.path("path").asText();
                    assertFalse(entry.contains(".."));
                    byte[] actual = zip.getInputStream(zip.getEntry(entry)).readAllBytes();
                    assertEquals(file.path("size_bytes").longValue(), actual.length);
                    assertEquals(file.path("sha256").asText(), CaptionBundleWriter.hash(actual));
                }
                assertArrayEquals(first, zip.getInputStream(zip.getEntry("clips/" + CLIP + "/clip-r2.mp4")).readAllBytes());
                assertEquals(0, zip.getEntry("clips/" + SECOND + "/clip-r2.srt").getSize());
                assertEquals(0, zip.getEntry("clips/" + SECOND + "/clip-r2.txt").getSize());
            }
            ready.close(); ready.close(); assertFalse(Files.exists(zipPath));
            try (var another = writer.prepare(EXPORT, List.of(item(CLIP, first, false)))) {
                assertTrue(Files.exists(another.path()));
            }
        }
        try (var paths = Files.list(root.resolve("delivery-tmp"))) { assertEquals(0, paths.count()); }
    }

    @Test void sameSizeTamperingNeverPublishesAndCapacityIsRecovered() throws Exception {
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            var item = item(CLIP, new byte[]{1, 2, 3}, false);
            Files.write(item.video().path(), new byte[]{3, 2, 1});
            JobException error = assertThrows(JobException.class, () -> writer.prepare(EXPORT, List.of(item)));
            assertEquals("CAPTION_PACKAGE_INTEGRITY_ERROR", error.getCode());
            try (var paths = Files.list(root.resolve("delivery-tmp"))) { assertEquals(0, paths.count()); }
            try (var ready = writer.prepare(EXPORT, List.of(item(CLIP, new byte[]{1, 2, 3}, false)))) {
                assertTrue(Files.exists(ready.path()));
            }
        }
    }

    @Test void slowDownloadHoldsCapacityUntilClosed() throws Exception {
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            var item = item(CLIP, new byte[]{1, 2, 3}, false);
            try (var ready = writer.prepare(EXPORT, List.of(item))) {
                JobException busy = assertThrows(JobException.class, () -> writer.prepare(EXPORT, List.of(item)));
                assertEquals("CAPTION_PACKAGE_BUSY", busy.getCode());
            }
            try (var ready = writer.prepare(EXPORT, List.of(item))) { assertTrue(Files.exists(ready.path())); }
        }
    }

    @Test void quotaReservationsCoverConcurrentPreparedArchives() throws Exception {
        long max = 100_000;
        try (CaptionBundleWriter writer = writer(max, 2, max + OVERHEAD)) {
            var item = item(CLIP, new byte[]{1, 2, 3}, false);
            try (var ready = writer.prepare(EXPORT, List.of(item))) {
                JobException full = assertThrows(JobException.class, () -> writer.prepare(EXPORT, List.of(item)));
                assertEquals("CAPTION_PACKAGE_LIMIT", full.getCode());
            }
            try (var ready = writer.prepare(EXPORT, List.of(item))) { assertTrue(Files.exists(ready.path())); }
        }
    }

    @Test void oversizedOrDuplicateSelectionsFailBeforeOpeningFiles() throws Exception {
        try (CaptionBundleWriter writer = writer(32, 1, OVERHEAD * 2)) {
            var item = item(CLIP, new byte[33], true);
            assertEquals("CAPTION_PACKAGE_LIMIT", assertThrows(JobException.class, () -> writer.prepare(EXPORT, List.of(item))).getCode());
            assertEquals("INVALID_CAPTION_PACKAGE", assertThrows(JobException.class, () -> writer.prepare(EXPORT, List.of())).getCode());
        }
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            var item = item(CLIP, new byte[]{1}, true);
            assertEquals("INVALID_CAPTION_PACKAGE", assertThrows(JobException.class, () -> writer.prepare(EXPORT, List.of(item, item))).getCode());
            assertEquals("INVALID_CAPTION_PACKAGE", assertThrows(JobException.class, () -> writer.prepare("../escape", List.of(item))).getCode());
        }
    }

    @Test void serviceShutdownRemovesPendingDownloadAndReturnsIdempotently() throws Exception {
        CaptionBundleWriter writer = writer(100_000, 2, OVERHEAD * 3);
        var ready = writer.prepare(EXPORT, List.of(item(CLIP, new byte[]{1, 2}, false)));
        Path zip = ready.path();
        writer.close(); assertFalse(Files.exists(zip)); ready.close();
    }

    @Test void rejectedAdmissionNeverInvokesTheExpensiveLoader() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            var item = item(CLIP, new byte[]{1}, true);
            try (var ready = writer.prepare(EXPORT, List.of(item))) {
                assertEquals("CAPTION_PACKAGE_BUSY", assertThrows(JobException.class,
                        () -> writer.prepare(EXPORT, List.of(new CaptionBundleWriter.Plan(SECOND, 2, 1)), plan -> {
                            loads.incrementAndGet(); throw new AssertionError("Busy admission must not load media or captions");
                        })).getCode());
            }
            assertEquals("CAPTION_PACKAGE_LIMIT", assertThrows(JobException.class,
                    () -> writer.prepare(EXPORT, List.of(new CaptionBundleWriter.Plan(CLIP, 2, 60_000), new CaptionBundleWriter.Plan(SECOND, 2, 60_000)), plan -> {
                        loads.incrementAndGet(); throw new AssertionError("Oversized plan must not load media or captions");
                    })).getCode());
            assertEquals("INVALID_CAPTION_PACKAGE", assertThrows(JobException.class,
                    () -> writer.prepare(EXPORT, List.of(new CaptionBundleWriter.Plan(CLIP, 2, 1), new CaptionBundleWriter.Plan(CLIP, 3, 1)), plan -> {
                        loads.incrementAndGet(); throw new AssertionError("Duplicate plans must not load media or captions");
                    })).getCode());
        }
        assertEquals(0, loads.get());
    }

    @Test void eachSidecarIsWrittenBeforeLoadingTheNextClip() throws Exception {
        byte[] firstCaption = "FIRST".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> priorBytes = new AtomicReference<>();
        AtomicInteger loads = new AtomicInteger();
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            var plans = List.of(new CaptionBundleWriter.Plan(CLIP, 2, 1), new CaptionBundleWriter.Plan(SECOND, 2, 1));
            try (var ready = writer.prepare(EXPORT, plans, plan -> {
                int index = loads.getAndIncrement();
                if (index == 1) Arrays.fill(priorBytes.get(), (byte)'X');
                var item = itemUnchecked(plan.clipId(), new byte[]{1}, true);
                byte[] caption = index == 0 ? firstCaption : "SECOND".getBytes(StandardCharsets.UTF_8);
                priorBytes.set(caption);
                return withSrt(item, caption);
            }); ZipFile zip = new ZipFile(ready.path().toFile(), StandardCharsets.UTF_8)) {
                assertEquals(2, loads.get());
                assertEquals("FIRST", new String(zip.getInputStream(zip.getEntry("clips/" + CLIP + "/clip-r2.srt")).readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("SECOND", new String(zip.getInputStream(zip.getEntry("clips/" + SECOND + "/clip-r2.srt")).readAllBytes(), StandardCharsets.UTF_8));
                var manifest = JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
                var first = manifest.path("files").valueStream().filter(file -> file.path("path").asText().endsWith(CLIP + "/clip-r2.srt")).findFirst().orElseThrow();
                assertEquals(CaptionBundleWriter.hash("FIRST".getBytes(StandardCharsets.UTF_8)), first.path("sha256").asText());
            }
        }
    }

    @Test void loadedItemMustMatchItsAdmittedClipRevisionAndMediaSize() throws Exception {
        List<UnaryOperator<CaptionBundleWriter.Item>> mismatches = List.of(
                item -> new CaptionBundleWriter.Item(SECOND, item.captionRevision(), item.title(), item.contentHash(), item.video(), item.sidecars(), item.source()),
                item -> new CaptionBundleWriter.Item(item.clipId(), 3, item.title(), item.contentHash(), item.video(), item.sidecars(), item.source()),
                item -> new CaptionBundleWriter.Item(item.clipId(), item.captionRevision(), item.title(), item.contentHash(),
                        new Content(item.video().path(), 2, item.video().hash(), item.video().filename()), item.sidecars(), item.source()));
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD * 2)) {
            var item = item(CLIP, new byte[]{1}, true);
            var plan = new CaptionBundleWriter.Plan(CLIP, 2, 1);
            for (var mismatch : mismatches) {
                assertEquals("INVALID_CAPTION_PACKAGE", assertThrows(JobException.class,
                        () -> writer.prepare(EXPORT, List.of(plan), ignored -> mismatch.apply(item))).getCode());
                try (var files = Files.list(root.resolve("delivery-tmp"))) {assertEquals(0, files.count());}
            }
            try (var ready = writer.prepare(EXPORT, List.of(plan), ignored -> item)) {assertTrue(Files.exists(ready.path()));}
        }
    }

    @Test void sidecarGrowthReservesQuotaBeforeWritingAndFailureReleasesEverything() throws Exception {
        try (CaptionBundleWriter writer = writer(100_000, 1, OVERHEAD + 100_000)) {
            Path retained = root.resolve("delivery-tmp/caption-pack-44444444-4444-4444-8444-444444444444.zip");
            Files.write(retained, new byte[50_000]);
            AtomicInteger loads = new AtomicInteger();
            var plan = new CaptionBundleWriter.Plan(CLIP, 2, 10_000);
            assertEquals("CAPTION_PACKAGE_LIMIT", assertThrows(JobException.class,
                    () -> writer.prepare(EXPORT, List.of(plan), ignored -> {
                        loads.incrementAndGet(); return withSrt(itemUnchecked(CLIP, new byte[10_000], true), new byte[45_000]);
                    })).getCode());
            assertEquals(1, loads.get());
            try (var files = Files.list(root.resolve("delivery-tmp"))) {assertEquals(List.of(retained), files.toList());}
            Files.delete(retained);
            try (var ready = writer.prepare(EXPORT, List.of(plan), ignored -> itemUnchecked(CLIP, new byte[10_000], true))) {assertTrue(Files.exists(ready.path()));}
        }
    }

    @Test void cumulativeSidecarInputLimitAndLoaderFailuresCleanPartialArchives() throws Exception {
        try (CaptionBundleWriter writer = writer(100, 1, OVERHEAD * 2)) {
            var plans = List.of(new CaptionBundleWriter.Plan(CLIP, 2, 1), new CaptionBundleWriter.Plan(SECOND, 2, 1));
            AtomicInteger loads = new AtomicInteger();
            assertEquals("CAPTION_PACKAGE_LIMIT", assertThrows(JobException.class,
                    () -> writer.prepare(EXPORT, plans, plan -> {
                        loads.incrementAndGet();return withSrt(itemUnchecked(plan.clipId(), new byte[]{1}, true), new byte[70]);
                    })).getCode());
            assertEquals(2, loads.get());
            try (var files = Files.list(root.resolve("delivery-tmp"))) {assertEquals(0, files.count());}
            assertThrows(IllegalStateException.class, () -> writer.prepare(EXPORT, plans, plan -> {throw new IllegalStateException("Synthetic loader failure");}));
            try (var files = Files.list(root.resolve("delivery-tmp"))) {assertEquals(0, files.count());}
            try (var ready = writer.prepare(EXPORT, List.of(plans.getFirst()), plan -> itemUnchecked(CLIP, new byte[]{1}, true))) {assertTrue(Files.exists(ready.path()));}
        }
    }

    @Test void loaderDoesNotHoldTheGlobalQuotaMonitorDuringSlowPreparation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (CaptionBundleWriter writer = writer(100_000, 2, OVERHEAD * 3)) {
            Future<CaptionBundleWriter.Prepared> slow = executor.submit(() -> writer.prepare(EXPORT, List.of(new CaptionBundleWriter.Plan(CLIP, 2, 1)), plan -> {
                entered.countDown();
                try {if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic loader gate timed out");}
                catch (InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                return itemUnchecked(CLIP, new byte[]{1}, true);
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // An independent small package can reserve/grow/release while another loader is suspended.
            try (var fast = writer.prepare(EXPORT, List.of(new CaptionBundleWriter.Plan(SECOND, 2, 1)), plan -> itemUnchecked(SECOND, new byte[]{2}, true))) {
                assertTrue(Files.exists(fast.path()));
            } finally {release.countDown();}
            try (var ready = slow.get(5, TimeUnit.SECONDS)) {assertTrue(Files.exists(ready.path()));}
        } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));}
    }

    private CaptionBundleWriter.Item itemUnchecked(String id, byte[] bytes, boolean empty) {
        try {return item(id, bytes, empty);} catch (Exception failed) {throw new AssertionError(failed);}
    }
    private static CaptionBundleWriter.Item withSrt(CaptionBundleWriter.Item item, byte[] srt) {
        List<CaptionBundleWriter.Sidecar> sidecars = new ArrayList<>();
        for (var sidecar : item.sidecars()) sidecars.add(sidecar.extension().equals("srt")
                ? new CaptionBundleWriter.Sidecar("srt", sidecar.contentType(), srt) : sidecar);
        return new CaptionBundleWriter.Item(item.clipId(), item.captionRevision(), item.title(), item.contentHash(), item.video(), List.copyOf(sidecars), item.source());
    }
}
