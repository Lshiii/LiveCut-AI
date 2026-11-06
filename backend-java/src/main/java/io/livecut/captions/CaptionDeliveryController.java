package io.livecut.captions;

import io.livecut.jobs.JobException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

@RestController
@RequestMapping("/api/exports")
public final class CaptionDeliveryController {
    private final CaptionDeliveryService delivery;
    public CaptionDeliveryController(CaptionDeliveryService delivery) { this.delivery = delivery; }

    @GetMapping("/{exportId}/clips/{clipId}/captions/files/{format}")
    public ResponseEntity<byte[]> file(@PathVariable String exportId, @PathVariable String clipId,
            @PathVariable String format, @RequestParam String revision) {
        var file = delivery.file(exportId, clipId, revision(revision), format);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore())
                .eTag("\"" + file.hash() + "\"").contentLength(file.bytes().length).body(file.bytes());
    }

    @GetMapping("/{exportId}/clips/{clipId}/captions/package")
    public ResponseEntity<StreamingResponseBody> clipPackage(@PathVariable String exportId, @PathVariable String clipId,
            @RequestParam String revision, HttpServletRequest request) {
        rejectHead(request);
        return response(delivery.bundle(exportId, List.of(new CaptionDeliveryService.Selection(clipId, revision(revision)))), request);
    }

    /** Explicit revision identities fit in a bounded native-download URL; no ZIP-sized JS Blob. */
    @GetMapping("/{exportId}/package")
    public ResponseEntity<StreamingResponseBody> batchPackage(@PathVariable String exportId,
            @RequestParam String items, HttpServletRequest request) {
        rejectHead(request);
        if (items.length() > 6500) throw CaptionDeliveryService.invalidSelection();
        List<CaptionDeliveryService.Selection> selections = new ArrayList<>();
        for (String item : items.split(",", -1)) {
            int separator = item.indexOf('.');
            if (separator != 36 || item.lastIndexOf('.') != separator) throw CaptionDeliveryService.invalidSelection();
            selections.add(new CaptionDeliveryService.Selection(item.substring(0, separator), revision(item.substring(separator + 1))));
        }
        return response(delivery.bundle(exportId, selections), request);
    }

    @PostMapping("/{exportId}/package")
    public ResponseEntity<StreamingResponseBody> batchPackage(@PathVariable String exportId,
            @RequestBody JsonNode request, HttpServletRequest servletRequest) {
        fields(request, Set.of("items"));
        JsonNode items = request.path("items");
        if (!items.isArray() || items.isEmpty() || items.size() > 100) throw CaptionDeliveryService.invalidSelection();
        List<CaptionDeliveryService.Selection> selections = new ArrayList<>();
        for (JsonNode item : items) {
            fields(item, Set.of("clip_id", "caption_revision"));
            if (!item.path("clip_id").isString() || !item.path("caption_revision").isIntegralNumber()
                    || !item.path("caption_revision").canConvertToLong()) throw CaptionDeliveryService.invalidSelection();
            selections.add(new CaptionDeliveryService.Selection(item.path("clip_id").asText(), item.path("caption_revision").longValue()));
        }
        return response(delivery.bundle(exportId, selections), servletRequest);
    }

    private ResponseEntity<StreamingResponseBody> response(CaptionBundleWriter.Prepared prepared, HttpServletRequest request) {
        try {
            request.setAttribute(CaptionDeliveryAsyncConfig.PREPARED, prepared);
            StreamingResponseBody body = output -> {
                // Bound slow-client streams and release file + capacity on disconnect.
                try (prepared; InputStream input = Files.newInputStream(prepared.path())) {
                    input.transferTo(output);
                }
            };
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + prepared.filename() + "\"")
                    .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore())
                    .eTag("\"" + prepared.hash() + "\"").contentLength(prepared.sizeBytes()).body(body);
        } catch (RuntimeException failed) { prepared.close(); throw failed; }
    }

    private static void rejectHead(HttpServletRequest request) {
        if ("HEAD".equals(request.getMethod()))
            throw new JobException(HttpStatus.METHOD_NOT_ALLOWED, "CAPTION_PACKAGE_GET_REQUIRED", "Download archives with GET");
    }
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw CaptionDeliveryService.invalidSelection();
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { throw CaptionDeliveryService.invalidSelection(); }
    }
    private static void fields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(fields)) throw CaptionDeliveryService.invalidSelection();
    }
}
