package io.livecut.events;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import io.livecut.events.EventSnapshotReader.Kind;
import io.livecut.events.EventSnapshotReader.Resource;
import io.livecut.events.EventSnapshotReader.Snapshot;
import io.livecut.video.VideoApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Current-state fanout, not an event log. MySQL remains the sole business-state source. */
@Service
public class TaskEventsService {
    private static final Logger LOG = LoggerFactory.getLogger(TaskEventsService.class);
    private final Object registry = new Object();
    private final Map<Resource, Channel> channels = new LinkedHashMap<>();
    private final EventSnapshotReader reader;
    private final EventStreamFactory streams;
    private final int maxConnections, maxPerResource;
    private final long pollMs, heartbeatNanos, timeoutNanos, timeoutMs;
    private final boolean scanEnabled;
    private final ThreadPoolExecutor senders;
    private final ScheduledExecutorService scanner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int connections;

    public record Statistics(int connections, int resources, int queuedSends, int activeSends, int pendingSnapshots) {}

    private static final class Channel {
        final Resource resource;
        final ReentrantLock reading = new ReentrantLock();
        final Set<Connection> clients = new LinkedHashSet<>();
        Snapshot last;
        Channel(Resource resource) { this.resource = resource; }
    }

    public TaskEventsService(EventSnapshotReader reader, EventStreamFactory streams,
                             @Value("${livecut.events.max-connections:32}") int maxConnections,
                             @Value("${livecut.events.max-per-resource:8}") int maxPerResource,
                             @Value("${livecut.events.sender-threads:4}") int senderThreads,
                             @Value("${livecut.events.sender-queue:32}") int senderQueue,
                             @Value("${livecut.events.poll-ms:1000}") long pollMs,
                             @Value("${livecut.events.heartbeat-ms:15000}") long heartbeatMs,
                             @Value("${livecut.events.timeout-ms:120000}") long timeoutMs,
                             @Value("${livecut.events.scan-enabled:true}") boolean scanEnabled) {
        if (maxConnections < 1 || maxConnections > 1024 || maxPerResource < 1 || maxPerResource > maxConnections
                || senderThreads < 1 || senderThreads > 32 || senderQueue < 1 || senderQueue > 4096
                || pollMs < 20 || pollMs > 60000 || heartbeatMs < 20 || heartbeatMs > 120000
                || timeoutMs < 100 || timeoutMs > 3600000) throw new IllegalArgumentException("Invalid bounded event-stream configuration");
        this.reader = reader; this.streams = streams;
        this.maxConnections = maxConnections; this.maxPerResource = maxPerResource;
        this.pollMs = pollMs; this.heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatMs);
        this.timeoutMs = timeoutMs; this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs); this.scanEnabled = scanEnabled;
        senders = new ThreadPoolExecutor(senderThreads, senderThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(senderQueue), runnable -> daemon(runnable, "livecut-event-send"), new ThreadPoolExecutor.AbortPolicy());
        scanner = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "livecut-event-scan"));
    }

    @PostConstruct void start() {
        if (scanEnabled) scanner.scheduleWithFixedDelay(() -> {
            try { scan(); }
            catch (RuntimeException failure) { LOG.warn("Event scan deferred ({})", failure.getClass().getSimpleName()); }
        }, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    public Connection subscribeJob(String id) { return subscribe(new Resource(Kind.JOB, id)); }
    public Connection subscribeExport(String id) { return subscribe(new Resource(Kind.EXPORT, id)); }

    private Connection subscribe(Resource resource) {
        Connection connection;
        synchronized (registry) {
            if (closed.get()) throw unavailable();
            Channel channel = channels.get(resource);
            if (connections >= maxConnections || (channel != null && channel.clients.size() >= maxPerResource)) {
                throw new VideoApiException(HttpStatus.TOO_MANY_REQUESTS, "EVENT_CAPACITY_EXCEEDED", "Event-stream connection capacity is currently occupied");
            }
            if (channel == null) channel = new Channel(resource);
            connection = new Connection(channel, streams.open(timeoutMs));
            channels.put(resource, channel);
            channel.clients.add(connection); connections++;
        }
        // Register before reading, but do not write until MVC has initialized the emitter.
        try {
            connection.stream.emitter().onCompletion(() -> connection.terminate(false));
            connection.stream.emitter().onTimeout(() -> connection.terminate(false));
            connection.stream.emitter().onError(error -> connection.terminate(false));
            refresh(connection.channel, connection);
            if (connection.closed.get()) throw unavailable();
            return connection;
        } catch (RuntimeException failure) {
            connection.terminate(false); connection.stream.abort();
            throw failure;
        }
    }

    /** One read per subscribed resource per scan, irrespective of its number of clients. */
    public void scan() {
        if (closed.get()) return;
        long time = System.nanoTime();
        for (Connection client : clients()) if (time - client.createdNanos >= timeoutNanos) client.expire();
        List<Channel> resources;
        synchronized (registry) { resources = List.copyOf(channels.values()); }
        for (Channel channel : resources) {
            try { refresh(channel, null); }
            catch (RuntimeException failure) {
                List<Connection> affected;
                synchronized (registry) { affected = List.copyOf(channel.clients); }
                for (Connection client : affected) client.close();
                LOG.warn("Event snapshot deferred for {} ({})", channel.resource.kind(), failure.getClass().getSimpleName());
            }
        }
        time = System.nanoTime();
        for (Connection client : clients()) client.heartbeat(time);
    }

    private void refresh(Channel channel, Connection newcomer) {
        channel.reading.lock();
        try {
            synchronized (registry) { if (channels.get(channel.resource) != channel || channel.clients.isEmpty()) return; }
            Snapshot value = reader.read(channel.resource);
            List<Connection> targets;
            synchronized (registry) {
                if (channels.get(channel.resource) != channel) return;
                boolean changed = channel.last == null || !channel.last.json().equals(value.json());
                channel.last = value;
                targets = changed ? List.copyOf(channel.clients) : newcomer == null ? List.of() : List.of(newcomer);
            }
            // Keep this order under reading so a slower initial read can never enqueue
            // an older snapshot after a newer poll, especially a terminal snapshot.
            for (Connection client : targets) client.offer(value);
        } finally { channel.reading.unlock(); }
    }

    private List<Connection> clients() {
        synchronized (registry) {
            List<Connection> result = new ArrayList<>(connections);
            for (Channel channel : channels.values()) result.addAll(channel.clients);
            return result;
        }
    }

    public int activeConnections() { synchronized (registry) { return connections; } }
    public int resourceCount() { synchronized (registry) { return channels.size(); } }
    public int subscribers(Kind kind, String id) {
        Resource resource = new Resource(kind, id);
        synchronized (registry) { Channel channel = channels.get(resource); return channel == null ? 0 : channel.clients.size(); }
    }
    public Statistics statistics() {
        List<Connection> clients = clients();
        int pending = 0;
        for (Connection client : clients) synchronized (client) { if (client.pending != null) pending++; }
        return new Statistics(activeConnections(), resourceCount(), senders.getQueue().size(), senders.getActiveCount(), pending);
    }

    public final class Connection implements AutoCloseable {
        private final Channel channel;
        private final EventStreamFactory.Stream stream;
        private final long createdNanos = System.nanoTime();
        private final AtomicBoolean closed = new AtomicBoolean();
        private Snapshot pending;
        private boolean ready, scheduled, heartbeat, finishRequested;
        private long lastWrite = createdNanos;
        private Connection(Channel channel, EventStreamFactory.Stream stream) { this.channel = channel; this.stream = stream; }
        public SseEmitter emitter() { return stream.emitter(); }

        /** Called only after MVC initialized the emitter; tests may call this directly. */
        public void start() { synchronized (this) { ready = true; } schedule(); }

        private void offer(Snapshot value) {
            synchronized (this) {
                if (closed.get()) return;
                pending = value; heartbeat = false;
            }
            schedule();
        }

        private void heartbeat(long time) {
            synchronized (this) {
                if (closed.get() || pending != null || time - lastWrite < heartbeatNanos) return;
                heartbeat = true;
            }
            schedule();
        }

        private void schedule() {
            synchronized (this) {
                if (!ready || scheduled || (closed.get() ? !finishRequested : pending == null && !heartbeat)) return;
                scheduled = true;
            }
            try { senders.execute(this::sendOne); }
            catch (RuntimeException rejected) {
                synchronized (this) { scheduled = false; }
                terminate(false); stream.abort();
            }
        }

        private void sendOne() {
            Snapshot value;
            boolean pulse, finish;
            synchronized (this) {
                finish = closed.get();
                value = finish ? null : pending;
                pending = null;
                pulse = !finish && value == null && heartbeat;
                heartbeat = false;
            }
            try {
                if (finish) {
                    synchronized (this) { finishRequested = false; }
                    stream.complete();
                } else if (value != null) {
                    stream.snapshot(value.eventId(), value.json());
                    synchronized (this) { lastWrite = System.nanoTime(); }
                    if (value.terminal()) close();
                } else if (pulse) {
                    stream.heartbeat();
                    synchronized (this) { lastWrite = System.nanoTime(); }
                }
            } catch (IOException | RuntimeException failure) {
                terminate(false); stream.abort();
            } finally {
                synchronized (this) { scheduled = false; }
                schedule();
            }
        }

        private void terminate(boolean finish) {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (this) { pending = null; heartbeat = false; finishRequested = finish; }
            synchronized (registry) {
                if (channel.clients.remove(this)) {
                    connections--;
                    if (channel.clients.isEmpty()) channels.remove(channel.resource, channel);
                }
            }
            if (finish) schedule();
        }

        private void expire() { terminate(false); stream.abort(); }
        @Override public void close() { terminate(true); }
    }

    @PreDestroy public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scanner.shutdownNow();
        for (Connection client : clients()) { client.terminate(false); client.stream.abort(); }
        senders.shutdownNow();
    }

    private static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
    private static VideoApiException unavailable() { return new VideoApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVENTS_UNAVAILABLE", "Event streams are temporarily unavailable"); }
}
