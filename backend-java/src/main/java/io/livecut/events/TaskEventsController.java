package io.livecut.events;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class TaskEventsController {
    static final String CONNECTION_ATTRIBUTE = TaskEventsController.class.getName() + ".connection";
    private final TaskEventsService events;
    public TaskEventsController(TaskEventsService events) { this.events = events; }

    @GetMapping("/api/jobs/{id}/events")
    public ResponseEntity<SseEmitter> job(@PathVariable String id, HttpServletRequest request) {
        return response(events.subscribeJob(id), request);
    }
    @GetMapping("/api/exports/{id}/events")
    public ResponseEntity<SseEmitter> export(@PathVariable String id, HttpServletRequest request) {
        return response(events.subscribeExport(id), request);
    }
    private ResponseEntity<SseEmitter> response(TaskEventsService.Connection connection, HttpServletRequest request) {
        // Last-Event-ID intentionally does not suppress a fresh MySQL snapshot:
        // this endpoint has no durable event log or historical replay promise.
        request.setAttribute(CONNECTION_ATTRIBUTE, connection);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(connection.emitter());
    }
}
