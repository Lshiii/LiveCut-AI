package io.livecut.sourceimports;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/source-imports")
public class SourceImportController {
    private final SourceImportService service;
    public SourceImportController(SourceImportService service) { this.service = service; }
    @PostMapping public ResponseEntity<SourceImportModels.Snapshot> create(@RequestBody JsonNode input,
        @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var result=service.create(input,key);
        return ResponseEntity.created(URI.create("/api/source-imports/"+result.id())).body(result);
    }
    @GetMapping public SourceImportModels.Page list(@RequestParam(defaultValue="20") String limit) { return service.list(limit); }
    @GetMapping("/by-key/{key}") public SourceImportModels.Snapshot byKey(@PathVariable String key) { return service.byKey(key); }
    @GetMapping("/{id}") public SourceImportModels.Snapshot get(@PathVariable String id) { return service.get(id); }
    @DeleteMapping("/{id}") public SourceImportModels.Snapshot cancel(@PathVariable String id) { return service.cancel(id); }
    @PostMapping("/{id}/retry") public SourceImportModels.Snapshot retry(@PathVariable String id,@RequestBody JsonNode input) { return service.retry(id,input); }
}
