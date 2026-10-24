package io.livecut.review;

import io.livecut.jobs.JobException;
import io.livecut.review.ReviewModels.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/jobs/{id}/review")
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) { this.service = service; }

    @GetMapping
    public Snapshot get(@PathVariable String id, @RequestParam(required = false) String revision) {
        if (revision == null) { return service.get(id, null); }
        try {
            if (!revision.matches("0|[1-9][0-9]*")) { throw new NumberFormatException(); }
            return service.get(id, Long.parseLong(revision));
        } catch (NumberFormatException failure) {
            throw new JobException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "revision must be a nonnegative integer");
        }
    }

    @PutMapping
    public Snapshot save(@PathVariable String id, @RequestBody JsonNode request) { return service.save(id, request); }

    @PostMapping("/confirm")
    public Snapshot confirm(@PathVariable String id, @RequestBody JsonNode request) { return service.confirm(id, request); }
}
