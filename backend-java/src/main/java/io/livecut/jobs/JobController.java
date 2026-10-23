package io.livecut.jobs;

import java.net.URI;

import io.livecut.jobs.JobModels.CreateJobRequest;
import io.livecut.jobs.JobModels.JobPage;
import io.livecut.jobs.JobModels.JobSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService service;

    public JobController(JobService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<JobSnapshot> create(@RequestBody CreateJobRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        JobSnapshot job = service.create(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/jobs/" + job.id())).body(job);
    }

    @GetMapping
    public JobPage list(@RequestParam(defaultValue = "20") String limit,
                        @RequestParam(defaultValue = "0") String offset) {
        return service.list(queryNumber(limit, 1, 100, "limit"), queryNumber(offset, 0, Integer.MAX_VALUE, "offset"));
    }

    @GetMapping("/{id}")
    public JobSnapshot get(@PathVariable String id) { return service.get(id); }

    @PostMapping("/{id}/cancel")
    public JobSnapshot cancel(@PathVariable String id) { return service.cancel(id); }

    @PostMapping("/{id}/retry")
    public JobSnapshot retry(@PathVariable String id) { return service.retry(id); }

    static int queryNumber(String value, int minimum, int maximum, String name) {
        try {
            int number = Integer.parseInt(value);
            if (number < minimum || number > maximum) { throw new NumberFormatException(); }
            return number;
        } catch (NumberFormatException exception) {
            throw new JobException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", name + " is outside its supported integer range");
        }
    }
}
