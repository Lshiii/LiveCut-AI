package io.livecut.exports;

import java.net.URI;

import io.livecut.downloads.JsonAttachment;
import io.livecut.video.VideoApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exports")
public class ExportController {
    private final ExportService exports;
    public ExportController(ExportService exports) { this.exports = exports; }

    @PostMapping
    public ResponseEntity<ExportModels.Snapshot> create(@RequestBody ExportModels.CreateRequest request) {
        var result = exports.create(request);
        return ResponseEntity.created(URI.create("/api/exports/" + result.id())).body(result);
    }

    @GetMapping
    public ExportModels.Page list(@RequestParam("job_id") String jobId,
                                   @RequestParam(defaultValue = "20") String limit,
                                   @RequestParam(defaultValue = "0") String offset) {
        return exports.list(jobId, number(limit), number(offset));
    }

    @GetMapping("/{id}")
    public ExportModels.Snapshot get(@PathVariable String id) { return exports.get(id); }

    @GetMapping("/{id}/manifest")
    public ResponseEntity<byte[]> manifest(@PathVariable String id) {
        var inventory = exports.manifest(id);
        return JsonAttachment.response("export-" + inventory.exportId() + "-v" + inventory.stateVersion() + ".json", inventory);
    }

    @PostMapping("/{id}/clips/{clipId}/retry")
    public ExportModels.Snapshot retry(@PathVariable String id, @PathVariable String clipId) { return exports.retry(id, clipId); }

    @RequestMapping(value = "/{id}/clips/{clipId}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Resource> content(@PathVariable String id, @PathVariable String clipId,
                                             @RequestParam(defaultValue = "false") boolean download,
                                             HttpServletRequest request) {
        // Every GET/HEAD rechecks the immutable manifest and exact clip bytes,
        // including same-size tampering; the Resource is then streamed by MVC.
        var file = exports.content(id, clipId);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, (download ? "attachment" : "inline") + "; filename=\"" + file.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff").eTag("\"" + file.hash() + "\"");
        if ("HEAD".equals(request.getMethod())) { return response.contentLength(file.sizeBytes()).build(); }
        Resource resource = new FileSystemResource(file.path());
        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null) {
            try { HttpRange.toResourceRegions(HttpRange.parseRanges(range), resource); }
            catch (IllegalArgumentException failure) {
                throw new VideoApiException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "RANGE_NOT_SATISFIABLE", "Requested byte range is outside the clip", file.sizeBytes());
            }
        }
        return response.body(resource);
    }

    private static int number(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException failure) { throw new VideoApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "limit must be 1..100 and offset nonnegative"); }
    }
}
