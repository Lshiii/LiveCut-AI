package io.livecut.captions;

import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/exports/{exportId}/clips/{clipId}/captions")
public class CaptionController {
    private final CaptionService captions;
    public CaptionController(CaptionService captions){this.captions=captions;}
    @PostMapping
    public CaptionModels.Snapshot initialize(@PathVariable String exportId,@PathVariable String clipId){return captions.initialize(exportId,clipId);}
    @GetMapping
    public CaptionModels.Snapshot get(@PathVariable String exportId,@PathVariable String clipId,@RequestParam(required=false) Long revision){return captions.get(exportId,clipId,revision);}
    @PutMapping
    public CaptionModels.Snapshot save(@PathVariable String exportId,@PathVariable String clipId,@RequestBody JsonNode input){return captions.save(exportId,clipId,input);}
    @PostMapping("/confirm")
    public CaptionModels.Snapshot confirm(@PathVariable String exportId,@PathVariable String clipId,@RequestBody JsonNode input){return captions.confirm(exportId,clipId,input);}
}
