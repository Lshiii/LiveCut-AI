package io.livecut.agent;

import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class AgentController {
    private final AgentService agents;
    public AgentController(AgentService agents){this.agents=agents;}
    @PostMapping("/api/jobs/{jobId}/agent-sessions")
    public AgentModels.Session createSession(@PathVariable String jobId,@RequestBody JsonNode input,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return agents.createSession(jobId,input,key);}
    @GetMapping("/api/jobs/{jobId}/agent-sessions")
    public AgentModels.Page sessions(@PathVariable String jobId){return agents.list(jobId);}
    @GetMapping("/api/jobs/{jobId}/agent-session-submissions/{key}")
    public AgentModels.Session sessionSubmission(@PathVariable String jobId,@PathVariable String key){return agents.findSessionSubmission(jobId,key);}
    @GetMapping("/api/agent-sessions/{id}")
    public AgentModels.Session session(@PathVariable String id){return agents.getSession(id);}
    @GetMapping("/api/agent-sessions/{id}/turn-submissions/{key}")
    public AgentModels.Turn turnSubmission(@PathVariable String id,@PathVariable String key){return agents.findTurnSubmission(id,key);}
    @PostMapping("/api/agent-sessions/{id}/turns")
    public AgentModels.Turn createTurn(@PathVariable String id,@RequestBody JsonNode input,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return agents.createTurn(id,input,key);}
    @GetMapping("/api/agent-turns/{id}")
    public AgentModels.Turn turn(@PathVariable String id){return agents.getTurn(id);}
    @PostMapping("/api/agent-turns/{id}/cancel")
    public AgentModels.Turn cancel(@PathVariable String id){return agents.cancel(id);}
}
