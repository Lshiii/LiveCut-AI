package io.livecut.video;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class VideoStorageTests {

    @TempDir
    Path root;

    @Test
    void storesUnderGeneratedKeyAndHashesExactUploadedBytes() throws Exception {
        var storage = new VideoStorage(root, 1024);
        byte[] bytes = mp4Header();

        var video = storage.store(file("../../商品.mp4", bytes));

        UUID.fromString(video.id());
        assertEquals("商品.mp4", video.originalFilename());
        assertEquals(bytes.length, video.sizeBytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                video.contentHash());
        Path stored = storage.resolve(video.sourceKey());
        assertTrue(stored.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));
        assertFalse(video.sourceKey().contains("商品"));
        assertArrayEquals(bytes, Files.readAllBytes(stored));
    }

    @Test
    void handlesWindowsClientBasenameWithoutTrustingClientDirectory() {
        var video = new VideoStorage(root, 1024).store(file("C:\\fakepath\\直播.MP4", mp4Header()));

        assertEquals("直播.MP4", video.originalFilename());
    }

    @Test
    void repeatedNamesAndContentCannotOverwriteAnEarlierUpload() throws Exception {
        var storage = new VideoStorage(root, 1024);
        byte[] bytes = mp4Header();

        var first = storage.store(file("same.mp4", bytes));
        var second = storage.store(file("same.mp4", bytes));

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.sourceKey(), second.sourceKey());
        assertEquals(first.contentHash(), second.contentHash());
        assertArrayEquals(bytes, Files.readAllBytes(storage.resolve(first.sourceKey())));
        assertArrayEquals(bytes, Files.readAllBytes(storage.resolve(second.sourceKey())));
    }

    @Test
    void rejectsEmptyOversizedAndNonMp4ContentWithoutLeavingFiles() throws Exception {
        var storage = new VideoStorage(root, 64);

        assertCode("EMPTY_VIDEO", () -> storage.store(file("empty.mp4", new byte[0])));
        assertCode("VIDEO_TOO_LARGE", () -> storage.store(file("large.mp4", new byte[65])));
        assertCode("INVALID_VIDEO_HEADER", () -> storage.store(file("fake.mp4", "not a video".getBytes())));
        assertCode("UNSUPPORTED_VIDEO", () -> storage.store(file("video.exe", mp4Header())));
        assertNoFiles();
    }

    @Test
    void rejectsMissingAndDirectoryOnlyFilenames() throws Exception {
        var storage = new VideoStorage(root, 1024);

        assertCode("INVALID_FILENAME", () -> storage.store(file("", mp4Header())));
        assertCode("INVALID_FILENAME", () -> storage.store(file("../", mp4Header())));
        assertNoFiles();
    }

    @Test
    void resolverRejectsAbsoluteTraversalAndMalformedStorageKeys() {
        var storage = new VideoStorage(root, 1024);

        for (String key : new String[] {"../escape.mp4", "..\\escape.mp4", "/tmp/escape.mp4",
                "C:\\escape.mp4", "video.mp4", "", "./video.mp4"}) {
            assertThrows(RuntimeException.class, () -> storage.resolve(key), key);
        }
    }

    @Test
    void failedReadCleansPartialUpload() throws Exception {
        var storage = new VideoStorage(root, 1024);
        var brokenFile = new MockMultipartFile("file", "broken.mp4", "video/mp4", mp4Header()) {
            @Override
            public InputStream getInputStream() {
                return new FilterInputStream(new ByteArrayInputStream(mp4Header())) {
                    private boolean returnedBytes;

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        if (returnedBytes) {
                            throw new IOException("synthetic interrupted upload");
                        }
                        returnedBytes = true;
                        return super.read(bytes, offset, Math.min(length, 16));
                    }
                };
            }
        };

        assertThrows(RuntimeException.class, () -> storage.store(brokenFile));
        assertNoFiles();
    }

    @Test
    void deletesOnlyTheGeneratedUpload() {
        var storage = new VideoStorage(root, 1024);
        var first = storage.store(file("first.mp4", mp4Header()));
        var second = storage.store(file("second.mp4", mp4Header()));

        storage.delete(first);

        assertFalse(Files.exists(storage.resolve(first.sourceKey())));
        assertTrue(Files.exists(storage.resolve(second.sourceKey())));
    }

    @Test
    void resolverRejectsGeneratedKeyReplacedWithExternalSymbolicLink(@TempDir Path externalRoot) throws Exception {
        var storage = new VideoStorage(root, 1024);
        var video = storage.store(file("sample.mp4", mp4Header()));
        Path stored = storage.resolve(video.sourceKey());
        Path external = Files.write(externalRoot.resolve("external.mp4"), mp4Header());
        Files.delete(stored);
        try {
            Files.createSymbolicLink(stored, external);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic link creation unavailable on this platform: " + exception.getClass().getSimpleName());
        }

        assertThrows(RuntimeException.class, () -> storage.resolve(video.sourceKey()));
        assertArrayEquals(mp4Header(), Files.readAllBytes(external));
    }

    private void assertNoFiles() throws IOException {
        try (var paths = Files.walk(root)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }

    static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "video/mp4", bytes);
    }

    private static void assertCode(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(VideoApiException.class, action).getCode());
    }

    /** A synthetic ISO BMFF header, deliberately not represented as a playable video. */
    static byte[] mp4Header() {
        return ByteBuffer.allocate(48)
                .putInt(32).put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .put("isom".getBytes(StandardCharsets.US_ASCII)).putInt(512)
                .put("isomiso2avc1mp41".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).put("free".getBytes(StandardCharsets.US_ASCII)).putLong(123456789L)
                .array();
    }
}
