package io.livecut.jobs;

import java.util.List;

import io.livecut.jobs.JobModels.CheckpointRequest;
import io.livecut.jobs.JobModels.ClaimRequest;
import io.livecut.jobs.JobModels.ClaimResult;
import io.livecut.jobs.JobModels.CompleteRequest;
import io.livecut.jobs.JobModels.Credential;
import io.livecut.jobs.JobModels.FailRequest;
import io.livecut.jobs.JobModels.ReadyStage;
import io.livecut.jobs.JobModels.StageActionResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/stages")
public class StageController {
    private final JobService service;

    public record ReadyPage(List<ReadyStage> items, int limit) {}

    public StageController(JobService service) { this.service = service; }

    @GetMapping("/ready")
    public ReadyPage ready(@RequestParam(value = "stage_type", required = false) String stageType,
                           @RequestParam(defaultValue = "20") String limit) {
        int count = JobController.queryNumber(limit, 1, 100, "limit");
        return new ReadyPage(service.ready(stageType, count), count);
    }

    @PostMapping("/claim")
    public ClaimResult claim(@RequestBody ClaimRequest request) { return service.claim(request); }

    @PostMapping("/{id}/heartbeat")
    public StageActionResult heartbeat(@PathVariable String id, @RequestBody Credential request) {
        return service.heartbeat(id, request);
    }

    @PostMapping("/{id}/checkpoint")
    public StageActionResult checkpoint(@PathVariable String id, @RequestBody CheckpointRequest request) {
        return service.checkpoint(id, request);
    }

    @PostMapping("/{id}/complete")
    public StageActionResult complete(@PathVariable String id, @RequestBody CompleteRequest request) {
        return service.complete(id, request);
    }

    @PostMapping("/{id}/fail")
    public StageActionResult fail(@PathVariable String id, @RequestBody FailRequest request) {
        return service.fail(id, request);
    }
}
