package io.livecut.captions;

import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import io.livecut.worker.LocalPython;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class CaptionFramePreview {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_JPEG = 16 * 1024 * 1024;
    private static final Set<String> INPUT_ERRORS = Set.of("CAPTION_RENDER_TEXT_TOO_LARGE",
        "CAPTION_RENDER_TIMING_UNSUPPORTED", "CAPTION_RENDER_GLYPH_UNSUPPORTED", "CAPTION_RENDER_SOURCE_HASH_MISMATCH");
    private final Semaphore slot = new Semaphore(1);
    private final CaptionRenderService renders;
    private final String python;
    private final Path storage;

    public CaptionFramePreview(CaptionRenderService renders,
                               @Value("${LIVECUT_CAPTION_PREVIEW_PYTHON:${LIVECUT_PRODUCT_FRAME_PYTHON:}}") String configuredPython,
                               @Value("${livecut.storage.root:../storage}") Path storage) {
        this.renders = renders;
        this.python = LocalPython.interpreter(configuredPython);
        this.storage = storage.toAbsolutePath().normalize();
    }

    public byte[] render(String exportId, String clipId, JsonNode options, long atMs) {
        if (atMs < 0) throw invalidTime();
        if (!slot.tryAcquire()) throw new JobException(HttpStatus.TOO_MANY_REQUESTS, "CAPTION_PREVIEW_BUSY", "Preview is busy");
        try {
            JsonNode spec = renders.previewSpec(exportId, clipId, options);
            if (atMs >= spec.path("input_media").path("duration_ms").asLong()) throw invalidTime();
            return renderFrame(spec, atMs);
        } finally {
            slot.release();
        }
    }

    private byte[] renderFrame(JsonNode spec, long atMs) {
        Path directory = null;
        Process child = null;
        try {
            directory = Files.createTempDirectory("livecut-caption-preview-");
            child = new ProcessBuilder(python, "-m", "livecut.caption_frame_preview").directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var input = child.getOutputStream()) {
                String hash = ClipRequestService.sha(ClipRequestService.canonicalJson(spec));
                input.write(JSON.writeValueAsBytes(Map.of("spec", spec, "spec_hash", hash, "storage_root", storage.toString(), "at_ms", atMs)));
            }
            if (!child.waitFor(50, TimeUnit.SECONDS)) throw unavailable();
            if (child.exitValue() != 0) {
                String code = new String(child.getInputStream().readNBytes(100), StandardCharsets.US_ASCII).strip();
                if (INPUT_ERRORS.contains(code)) throw new JobException(HttpStatus.CONFLICT, code, "Caption inputs could not be rendered");
                throw unavailable();
            }
            Path image = directory.resolve("preview.jpg");
            if (!Files.isRegularFile(image) || Files.size(image) > MAX_JPEG) throw unavailable();
            byte[] bytes = Files.readAllBytes(image);
            if (bytes.length < 4 || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8
                || bytes[bytes.length - 2] != (byte) 0xff || bytes[bytes.length - 1] != (byte) 0xd9) throw unavailable();
            return bytes;
        } catch (IOException ex) {
            throw unavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            if (child != null && child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
            if (directory != null) FileSystemUtils.deleteRecursively(directory.toFile());
        }
    }

    private static JobException invalidTime() {
        return new JobException(HttpStatus.BAD_REQUEST, "CAPTION_PREVIEW_TIME_INVALID", "Time is outside the clean clip");
    }
    private static JobException unavailable() {
        return new JobException(HttpStatus.SERVICE_UNAVAILABLE, "CAPTION_PREVIEW_FRAME_UNAVAILABLE", "Frame could not be rendered");
    }
}
