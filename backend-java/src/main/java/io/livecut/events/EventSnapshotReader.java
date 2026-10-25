package io.livecut.events;

import java.util.Set;
import java.util.UUID;

import io.livecut.exports.ExportService;
import io.livecut.jobs.JobException;
import io.livecut.jobs.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Reads the existing public DTOs; it does not read or publish private worker rows. */
@Component
public class EventSnapshotReader {
    public enum Kind { JOB, EXPORT }

    public record Resource(Kind kind, String id) {
        public Resource {
            if (kind == null) throw new IllegalArgumentException("Resource kind is required");
            try {
                String normalized = UUID.fromString(id).toString();
                if (!normalized.equalsIgnoreCase(id)) throw new IllegalArgumentException();
                id = normalized;
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new JobException(HttpStatus.BAD_REQUEST, kind == Kind.JOB ? "INVALID_JOB_ID" : "INVALID_EXPORT_ID", "Identifier must be a UUID");
            }
        }
    }

    public record Snapshot(Resource resource, long stateVersion, String status, String json) {
        public String eventId() { return (resource.kind() == Kind.JOB ? "job:" : "export:") + resource.id() + ":" + stateVersion; }
        public boolean terminal() {
            return (resource.kind() == Kind.JOB ? Set.of("REVIEW_READY", "FAILED", "CANCELLED")
                    : Set.of("SUCCEEDED", "FAILED", "PARTIAL_FAILED", "CANCELLED")).contains(status);
        }
    }

    private final JobService jobs;
    private final ExportService exports;
    private final ObjectMapper json;

    public EventSnapshotReader(JobService jobs, ExportService exports, ObjectMapper json) {
        this.jobs = jobs; this.exports = exports; this.json = json;
    }

    public Snapshot read(Resource resource) {
        // get() commits its short snapshot transaction before serialization or socket I/O.
        if (resource.kind() == Kind.JOB) {
            var value = jobs.get(resource.id());
            return new Snapshot(resource, value.stateVersion(), value.status(), json.writeValueAsString(value));
        }
        var value = exports.get(resource.id());
        return new Snapshot(resource, value.stateVersion(), value.status(), json.writeValueAsString(value));
    }
}
