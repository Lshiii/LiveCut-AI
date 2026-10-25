package io.livecut.events;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Injectable writer seam; production writes use only the bounded sender executor. */
@Component
public class EventStreamFactory {
    public Stream open(long timeoutMs) { return new Stream(new ManagedEmitter(timeoutMs)); }

    public static class Stream {
        private final SseEmitter emitter;
        public Stream(SseEmitter emitter) { this.emitter = emitter; }
        public SseEmitter emitter() { return emitter; }
        public void snapshot(String id, String json) throws IOException {
            emitter.send(SseEmitter.event().id(id).name("snapshot").reconnectTime(2000).data(json, MediaType.APPLICATION_JSON));
        }
        public void heartbeat() throws IOException { emitter.send(SseEmitter.event().comment("heartbeat")); }
        public void complete() { emitter.complete(); }
        public void abort() {
            if (emitter instanceof ManagedEmitter managed) managed.abort();
            else emitter.completeWithError(new IllegalStateException("Event stream closed"));
        }
    }

    private static final class ManagedEmitter extends SseEmitter {
        ManagedEmitter(long timeoutMs) { super(timeoutMs); }
        void abort() {
            // complete() flushes the socket. Rejection/shutdown must never wait
            // for that flush or for a blocked sender's emitter write lock.
            if (!writeLock.tryLock()) return;
            try { super.completeWithError(new IllegalStateException("Event stream closed")); }
            finally { writeLock.unlock(); }
        }
    }
}
