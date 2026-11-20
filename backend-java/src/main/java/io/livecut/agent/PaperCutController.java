package io.livecut.agent;

import io.livecut.clips.ClipRequestService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class PaperCutController {
    private final AgentService agents;
    private final ClipRequestService clips;
    public PaperCutController(AgentService agents, ClipRequestService clips) { this.agents=agents; this.clips=clips; }

    @GetMapping("/api/clip-capabilities/paper-cut-suggestions")
    public Map<String,Object> capability() {
        return Map.of("paper_cut_supported",true,"worker_protocol",PaperCutSuggestion.PROTOCOL);
    }
    @PostMapping("/api/clip-requests/{id}/paper-cut-suggestions")
    public Map<String,Object> create(@PathVariable String id, @RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return agents.createPaperCut(clips,id,body,key);
    }
    @GetMapping("/api/clip-requests/{id}/paper-cut-submissions/{key}")
    public Map<String,Object> submission(@PathVariable String id, @PathVariable String key) {
        return agents.paperSubmission(id,key);
    }
    @GetMapping("/api/paper-cut-suggestions/{id}")
    public Map<String,Object> get(@PathVariable String id) { return agents.paperCut(id,false); }
    @PostMapping("/api/paper-cut-suggestions/{id}/cancel")
    public Map<String,Object> cancel(@PathVariable String id) { return agents.paperCut(id,true); }
}
