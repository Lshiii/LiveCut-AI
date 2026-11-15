package io.livecut.sourceimports;

import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Covered by the existing /internal/** X-Worker-Key filter. */
@RestController
@RequestMapping("/internal/source-imports")
public class InternalSourceImportController {
    private final SourceImportService service;
    public InternalSourceImportController(SourceImportService service) { this.service=service; }
    @PostMapping("/claim") public SourceImportModels.ClaimResponse claim(@RequestBody JsonNode input) { return service.claim(input); }
    @PostMapping("/{id}/heartbeat") public SourceImportModels.Heartbeat heartbeat(@PathVariable String id,@RequestBody JsonNode input) { return service.heartbeat(id,input); }
    @PostMapping("/{id}/complete") public SourceImportModels.Snapshot complete(@PathVariable String id,@RequestBody JsonNode input) { return service.complete(id,input); }
    @PostMapping("/{id}/fail") public SourceImportModels.Snapshot fail(@PathVariable String id,@RequestBody JsonNode input) { return service.fail(id,input); }
}
