package io.livecut.uploads;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadService uploads;

    public UploadController(UploadService uploads) { this.uploads = uploads; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadModels.Snapshot> create(@RequestBody JsonNode input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var snapshot = uploads.initialize(input, key);
        return ResponseEntity.created(URI.create("/api/uploads/" + snapshot.id())).body(snapshot);
    }

    @GetMapping("/{id}")
    public UploadModels.Snapshot get(@PathVariable String id) { return uploads.get(id); }

    @PutMapping(value = "/{id}/parts/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public UploadModels.Snapshot part(@PathVariable String id, @PathVariable String index,
            @RequestHeader(value = "X-Chunk-SHA256", required = false) String hash,
            HttpServletRequest request) throws IOException {
        return uploads.part(id, index, hash, request.getInputStream());
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<UploadModels.Snapshot> complete(@PathVariable String id) {
        var snapshot = uploads.complete(id);
        return ResponseEntity.status("COMPLETED".equals(snapshot.status()) ? 200 : 202).body(snapshot);
    }

    @DeleteMapping("/{id}")
    public UploadModels.Snapshot abandon(@PathVariable String id) { return uploads.abandon(id); }
}
