package io.livecut.agent;

import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Explicit saved-caption requests; these endpoints never save, confirm or render a caption. */
@RestController
public class CaptionProofreadController {
    private final AgentService agents;
    public CaptionProofreadController(AgentService agents){this.agents=agents;}
    @PostMapping("/api/exports/{exportId}/clips/{clipId}/caption-proofread-sessions")
    public CaptionProofreadModels.Session create(@PathVariable String exportId,@PathVariable String clipId,
            @RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key){return agents.createCaptionSession(exportId,clipId,body,key);}
    @GetMapping("/api/exports/{exportId}/clips/{clipId}/caption-proofread-sessions")
    public CaptionProofreadModels.Page list(@PathVariable String exportId,@PathVariable String clipId){return agents.listCaptionSessions(exportId,clipId);}
    @GetMapping("/api/caption-proofread-sessions/{id}")
    public CaptionProofreadModels.Session get(@PathVariable String id){return agents.getCaptionSession(id);}
    @GetMapping("/api/caption-proofread-sessions/{id}/turns")
    public CaptionProofreadModels.TurnPage turns(@PathVariable String id,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit){return agents.captionTurns(id,offset,limit);}
    @PostMapping("/api/caption-proofread-sessions/{id}/turns")
    public CaptionProofreadModels.Turn createTurn(@PathVariable String id,@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return agents.createCaptionTurn(id,body,key);}
    @GetMapping("/api/caption-proofread-turns/{id}")
    public CaptionProofreadModels.Turn getTurn(@PathVariable String id){return agents.getCaptionTurn(id);}
    @PostMapping("/api/caption-proofread-turns/{id}/cancel")
    public CaptionProofreadModels.Turn cancel(@PathVariable String id){return agents.cancelCaptionTurn(id);}
}
