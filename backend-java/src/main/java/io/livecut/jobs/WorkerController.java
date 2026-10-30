package io.livecut.jobs;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import static io.livecut.jobs.WorkerModels.*;

@RestController
public class WorkerController {
    private final WorkerCapacityService service;
    public WorkerController(WorkerCapacityService service) { this.service = service; }
    @PostMapping("/internal/workers/register")
    public Receipt register(@RequestBody Registration request) { return service.register(request); }
    @PostMapping("/internal/workers/heartbeat")
    public Receipt heartbeat(@RequestBody Session request) { return service.heartbeat(request); }
    @PostMapping("/internal/workers/unregister")
    public Receipt unregister(@RequestBody Session request) { return service.unregister(request); }
    @GetMapping("/api/executors")
    public Snapshot snapshot(@RequestParam(value="job_id", required=false) String jobId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.snapshot(jobId);
    }
}
