package io.livecut.clips;

import io.livecut.video.VideoApiException;
import io.livecut.worker.LocalPython;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class ProductFrameRunner {
    private static final int MAX_REPLY = 12 * 1024 * 1024;
    private final String python;
    private final Semaphore slot = new Semaphore(1);

    public ProductFrameRunner(@Value("${LIVECUT_PRODUCT_FRAME_PYTHON:}") String configuredPython) {
        python = LocalPython.interpreter(configuredPython);
    }
    public JsonNode run(Map<String,Object> input) {
        if (!slot.tryAcquire()) throw new VideoApiException(HttpStatus.TOO_MANY_REQUESTS, "PRODUCT_FRAME_BUSY", "Another local visual ranking is running");
        Process child = null;
        try {
            child = new ProcessBuilder(python, "-m", "livecut.product_frame_review")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final Process process = child;
            try (var stdin = process.getOutputStream()) { stdin.write(ClipPlanValidator.JSON.writeValueAsBytes(input)); }
            var output = new FutureTask<byte[]>(() -> {
                try (var stdout = process.getInputStream()) { return stdout.readNBytes(MAX_REPLY + 1); }
            });
            Thread.startVirtualThread(output);
            if (!process.waitFor(150, TimeUnit.SECONDS)) throw error("PRODUCT_FRAME_TIMEOUT");
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length == 0) throw error("PRODUCT_FRAME_HELPER_UNAVAILABLE");
            if (bytes.length > MAX_REPLY) throw error("PRODUCT_FRAME_REVIEW_FAILED");
            var response = ClipPlanValidator.JSON.readTree(bytes);
            if (response.has("error_code")) throw error("PRODUCT_FRAME_MODEL_UNAVAILABLE".equals(response.get("error_code").asString())
                ? "PRODUCT_FRAME_MODEL_UNAVAILABLE" : "PRODUCT_FRAME_REVIEW_FAILED");
            if (!response.path("rows").isArray() || !response.path("model_id").isString()) throw error("PRODUCT_FRAME_REVIEW_FAILED");
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error("PRODUCT_FRAME_REVIEW_FAILED");
        } catch (IOException e) {
            throw error("PRODUCT_FRAME_HELPER_UNAVAILABLE");
        } catch (ExecutionException | TimeoutException e) {
            throw error("PRODUCT_FRAME_REVIEW_FAILED");
        } finally {
            if (child != null && child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
            slot.release();
        }
    }
    private static VideoApiException error(String code) {
        return new VideoApiException(HttpStatus.SERVICE_UNAVAILABLE, code, "Local product frame ranking could not finish");
    }
}
