package io.livecut.jobs;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import io.livecut.video.VideoApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static io.livecut.jobs.WorkerModels.*;

@RestController
public class WorkerController {
    private final WorkerCapacityService service;
    private final ObjectMapper json;
    public WorkerController(WorkerCapacityService service,ObjectMapper json) { this.service = service;this.json=json; }
    @PostMapping("/internal/workers/register")
    public Receipt register(@RequestBody JsonNode input) {
        // Inspect the new field before DTO binding: string/float coercion must not advertise a version.
        if(input==null||!input.isObject())throw new VideoApiException(HttpStatus.BAD_REQUEST,"INVALID_WORKER","Worker registration is required");
        if(input.has("supported_protocol_versions")){
            JsonNode versions=input.path("supported_protocol_versions");
            if(!versions.isArray())throw new VideoApiException(HttpStatus.BAD_REQUEST,"INVALID_WORKER","Worker protocol versions must be an explicit integer list");
            for(JsonNode version:versions)if(!version.isIntegralNumber()||!version.canConvertToInt())throw new VideoApiException(HttpStatus.BAD_REQUEST,"INVALID_WORKER","Worker protocol versions must be integers");
        }
        return service.register(json.treeToValue(input,Registration.class));
    }
    @PostMapping("/internal/workers/heartbeat")
    public Receipt heartbeat(@RequestBody Session request) { return service.heartbeat(request); }
    @PostMapping("/internal/workers/unregister")
    public Receipt unregister(@RequestBody Session request) { return service.unregister(request); }
    @GetMapping("/api/executors")
    public Snapshot snapshot(@RequestParam(value="job_id", required=false) String jobId,
            @RequestParam(value="required_worker_protocol",required=false) String requiredProtocol,HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        Integer protocol=requiredProtocol==null?null:JobController.queryNumber(requiredProtocol,1,4,"required_worker_protocol");
        return service.snapshot(jobId,protocol);
    }
}
