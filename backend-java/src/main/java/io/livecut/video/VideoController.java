package io.livecut.video;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/videos")
public class VideoController {
    private final VideoService service;
    private final VideoStorage storage;

    public record VideoPage(List<VideoAssetResponse> items, int limit, int offset) {}

    public VideoController(VideoService service, VideoStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VideoAssetResponse> upload(@RequestParam("file") MultipartFile file) {
        VideoAssetResponse response = VideoAssetResponse.from(service.upload(file));
        return ResponseEntity.created(URI.create("/api/videos/" + response.id())).body(response);
    }

    @GetMapping
    public VideoPage list(@RequestParam(defaultValue = "20") String limit, @RequestParam(defaultValue = "0") String offset) {
        int parsedLimit = pagination(limit, true);
        int parsedOffset = pagination(offset, false);
        return new VideoPage(service.list(parsedLimit, parsedOffset).stream().map(VideoAssetResponse::from).toList(), parsedLimit, parsedOffset);
    }

    @GetMapping("/{id}")
    public VideoAssetResponse metadata(@PathVariable String id) { return VideoAssetResponse.from(service.get(id)); }

    @RequestMapping(value = "/{id}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Resource> content(@PathVariable String id, HttpServletRequest request) {
        VideoAsset asset = service.get(id);
        Path path = storage.resolve(asset.sourceKey());
        try {
            if (!Files.isRegularFile(path)) {
                throw new VideoApiException(HttpStatus.NOT_FOUND, "VIDEO_CONTENT_NOT_FOUND", "Video content is unavailable");
            }
            if (Files.size(path) != asset.sizeBytes()) {
                throw new VideoApiException(HttpStatus.CONFLICT, "CONTENT_INTEGRITY_ERROR", "Stored video content no longer matches its registration");
            }
        } catch (IOException exception) {
            throw new VideoApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Video storage is unavailable");
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + asset.id() + ".mp4\"")
                .header("X-Content-Type-Options", "nosniff")
                .eTag("\"" + asset.contentHash() + "\"");
        if ("HEAD".equals(request.getMethod())) {
            return response.contentLength(asset.sizeBytes()).build();
        }
        Resource resource = new FileSystemResource(path);
        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null) {
            try {
                // Validate here for a stable JSON 416; successful regions are
                // streamed by Spring MVC's ResourceRegion message converter.
                HttpRange.toResourceRegions(HttpRange.parseRanges(range), resource);
            } catch (IllegalArgumentException exception) {
                throw new VideoApiException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "RANGE_NOT_SATISFIABLE",
                        "Requested byte range is outside the video", asset.sizeBytes());
            }
        }
        // Spring MVC requires a non-InputStreamResource and initial 200 status
        // for its transparent single/multipart Range handling.
        return response.body(resource);
    }

    private static int pagination(String value, boolean isLimit) {
        try {
            int number = Integer.parseInt(value);
            if ((isLimit && (number < 1 || number > 100)) || (!isLimit && number < 0)) { throw new NumberFormatException(); }
            return number;
        } catch (NumberFormatException exception) {
            throw new VideoApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "limit must be 1..100 and offset must be nonnegative");
        }
    }
}
