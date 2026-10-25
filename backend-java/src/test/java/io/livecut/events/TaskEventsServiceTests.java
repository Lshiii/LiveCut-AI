package io.livecut.events;

import static org.junit.jupiter.api.Assertions.*;

import io.livecut.events.EventSnapshotReader.Kind;
import io.livecut.events.EventSnapshotReader.Resource;
import io.livecut.events.EventSnapshotReader.Snapshot;
import io.livecut.video.VideoApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic sender/read seams exercise backpressure without relying on OS socket-buffer sizes. */
class TaskEventsServiceTests {
    private final FakeReader reader = new FakeReader();
    private final FakeStreams streams = new FakeStreams();
    private TaskEventsService service;

    @AfterEach void stop() {
        for (FakeStream stream : streams.opened) stream.release.countDown();
        if (service != null) service.close();
    }

    @Test void aScanReadsEachResourceOnceAndFansOutWithoutMixingJobAndExportIdentities() throws Exception {
        service = service(6, 3, 2, 8, 10000);
        Resource job = resource(Kind.JOB), export = new Resource(Kind.EXPORT, job.id());
        reader.put(job, 1, "RUNNING"); reader.put(export, 4, "RUNNING");
        var first = service.subscribeJob(job.id()); first.start();
        var second = service.subscribeJob(job.id()); second.start();
        var otherKind = service.subscribeExport(export.id()); otherKind.start();
        await(() -> streams.opened.stream().allMatch(stream -> stream.deliveries.size() == 1));
        assertEquals(2, service.resourceCount());
        reader.reads.clear();
        reader.put(job, 2, "RUNNING"); reader.put(export, 5, "RUNNING");
        service.scan();
        await(() -> streams.opened.get(0).lastVersion() == 2 && streams.opened.get(1).lastVersion() == 2
                && streams.opened.get(2).lastVersion() == 5);
        assertEquals(1, reader.reads.get(job).get(), "Job read count must not grow with subscriber count");
        assertEquals(1, reader.reads.get(export).get());
        assertTrue(streams.opened.get(0).deliveries.stream().allMatch(value -> value.id().startsWith("job:" + job.id() + ":")));
        assertTrue(streams.opened.get(2).deliveries.stream().allMatch(value -> value.id().startsWith("export:" + export.id() + ":")));
        service.scan();
        await(() -> service.statistics().activeSends() == 0 && service.statistics().queuedSends() == 0);
        assertEquals(List.of(2, 2, 2), streams.opened.stream().map(stream -> stream.deliveries.size()).toList(),
                "An unchanged snapshot must not be emitted again");
        first.close(); second.close(); otherKind.close();
        assertEmptyRegistry();
    }

    @Test void aSlowWriterKeepsOnlyTheLatestPendingSnapshotWhileAFastSubscriberContinues() throws Exception {
        service = service(4, 3, 2, 4, 10000);
        Resource job = resource(Kind.JOB); reader.put(job, 1, "RUNNING");
        streams.blockNext.set(true);
        var slow = service.subscribeJob(job.id()); slow.start();
        FakeStream blocked = streams.opened.getFirst();
        assertTrue(blocked.entered.await(2, TimeUnit.SECONDS));
        var fast = service.subscribeJob(job.id()); fast.start();
        FakeStream responsive = streams.opened.get(1);
        await(() -> responsive.lastVersion() == 1);
        for (int version = 2; version <= 20; version++) {
            int expected = version;
            reader.put(job, version, "RUNNING"); service.scan();
            await(() -> responsive.lastVersion() == expected);
            assertTrue(service.statistics().queuedSends() <= 4);
            assertTrue(service.statistics().activeSends() <= 2);
        }
        assertEquals(0, blocked.deliveries.size());
        assertEquals(1, service.statistics().pendingSnapshots(), "The slow client must retain one newest snapshot, not a growing history");
        blocked.release.countDown();
        await(() -> blocked.lastVersion() == 20);
        assertEquals(List.of(1L, 20L), blocked.deliveries.stream().map(Delivery::version).toList());
        assertEquals(1, blocked.maxConcurrentWrites.get(), "One connection must never run concurrent socket writes");
        slow.close(); fast.close(); assertEmptyRegistry();
    }

    @Test void aFailedWriterAndRepeatedCloseCannotLeakOrOverReleaseConnectionQuota() throws Exception {
        service = service(2, 2, 2, 4, 10000);
        Resource job = resource(Kind.JOB); reader.put(job, 1, "RUNNING");
        streams.failNext.set(true);
        var broken = service.subscribeJob(job.id()); broken.start();
        await(() -> service.activeConnections() == 0 && streams.opened.getFirst().aborts.get() == 1);
        assertEquals(1, streams.opened.getFirst().aborts.get());
        broken.close(); broken.close(); assertEmptyRegistry();

        var current = service.subscribeJob(job.id()); current.start();
        var sibling = service.subscribeJob(job.id()); sibling.start();
        await(() -> streams.opened.get(1).lastVersion() == 1 && streams.opened.get(2).lastVersion() == 1);
        broken.close();
        assertEquals(2, service.activeConnections(), "A stale close must not return another connection's slot");
        VideoApiException exceeded = assertThrows(VideoApiException.class, () -> service.subscribeJob(job.id()));
        assertEquals("EVENT_CAPACITY_EXCEEDED", exceeded.getCode());
        current.close(); current.close();
        assertEquals(1, service.activeConnections());
        sibling.close(); assertEmptyRegistry();
    }

    @Test void aFullSenderQueueRejectsOneStreamWithoutBlockingOrBreakingTheQueuedOwner() throws Exception {
        service = service(6, 2, 1, 1, 10000);
        Resource slowJob = resource(Kind.JOB), queuedJob = resource(Kind.JOB), rejectedJob = resource(Kind.JOB);
        for (Resource job : List.of(slowJob, queuedJob, rejectedJob)) reader.put(job, 1, "RUNNING");
        streams.blockNext.set(true);
        var slow = service.subscribeJob(slowJob.id()); slow.start();
        assertTrue(streams.opened.getFirst().entered.await(2, TimeUnit.SECONDS));
        var queued = service.subscribeJob(queuedJob.id()); queued.start();
        assertEquals(1, service.statistics().queuedSends());
        var rejected = service.subscribeJob(rejectedJob.id()); rejected.start();
        await(() -> streams.opened.get(2).aborts.get() == 1);
        assertEquals(2, service.activeConnections());
        assertEquals(0, service.subscribers(Kind.JOB, rejectedJob.id()));
        assertEquals(1, service.statistics().queuedSends());
        rejected.close(); rejected.close();
        CompletableFuture.runAsync(slow::close).get(1, TimeUnit.SECONDS);
        assertEquals(1, service.activeConnections(), "Cancelling a blocked sender must not wait for its socket write");
        streams.opened.getFirst().release.countDown();
        await(() -> streams.opened.get(1).lastVersion() == 1);
        assertEquals(1, service.subscribers(Kind.JOB, queuedJob.id()));
        queued.close(); assertEmptyRegistry();
        await(() -> service.statistics().queuedSends() == 0 && service.statistics().activeSends() == 0);
    }

    @Test void aTimedOutBlockedWriterCannotRetainQuotaOrDamageANewSubscriberOnLateClose() throws Exception {
        service = service(2, 2, 2, 2, 100);
        Resource job = resource(Kind.JOB); reader.put(job, 1, "RUNNING");
        streams.blockNext.set(true);
        var timedOut = service.subscribeJob(job.id()); timedOut.start();
        assertTrue(streams.opened.getFirst().entered.await(2, TimeUnit.SECONDS));
        Thread.sleep(120);
        service.scan();
        assertEmptyRegistry();
        var replacement = service.subscribeJob(job.id()); replacement.start();
        await(() -> streams.opened.get(1).lastVersion() == 1);
        timedOut.close(); timedOut.close();
        assertEquals(1, service.activeConnections());
        streams.opened.getFirst().release.countDown();
        await(() -> service.statistics().activeSends() == 0);
        assertEquals(1, service.subscribers(Kind.JOB, job.id()));
        replacement.close(); assertEmptyRegistry();
    }

    @Test void oneResourceReadFailureClosesOnlyItsSubscribersAndFailedInitialReadsReturnTheirSlots() throws Exception {
        service = service(4, 2, 2, 4, 10000);
        Resource broken = resource(Kind.JOB), healthy = resource(Kind.EXPORT);
        reader.put(broken, 1, "RUNNING"); reader.put(healthy, 1, "RUNNING");
        var bad = service.subscribeJob(broken.id()); bad.start();
        var good = service.subscribeExport(healthy.id()); good.start();
        await(() -> streams.opened.stream().allMatch(stream -> stream.deliveries.size() == 1));
        reader.failures.add(broken); reader.put(healthy, 2, "RUNNING"); service.scan();
        await(() -> streams.opened.get(1).lastVersion() == 2);
        assertEquals(1, service.activeConnections());
        assertEquals(0, service.subscribers(Kind.JOB, broken.id()));
        assertEquals(1, service.subscribers(Kind.EXPORT, healthy.id()));
        assertThrows(IllegalStateException.class, () -> service.subscribeJob(broken.id()));
        assertEquals(1, service.activeConnections(), "Failed initial reads must return both connection and resource reservations");
        assertEquals(1, service.resourceCount());
        bad.close(); good.close(); assertEmptyRegistry();
    }

    @Test void shutdownDoesNotWaitForABlockedWriterAndRejectsNewSubscriptions() throws Exception {
        service = service(2, 2, 1, 1, 10000);
        Resource job = resource(Kind.JOB); reader.put(job, 1, "RUNNING");
        streams.blockNext.set(true);
        var connection = service.subscribeJob(job.id()); connection.start();
        assertTrue(streams.opened.getFirst().entered.await(2, TimeUnit.SECONDS));
        CompletableFuture.runAsync(service::close).get(1, TimeUnit.SECONDS);
        service.close(); connection.close(); assertEmptyRegistry();
        VideoApiException unavailable = assertThrows(VideoApiException.class, () -> service.subscribeJob(job.id()));
        assertEquals("EVENTS_UNAVAILABLE", unavailable.getCode());
    }

    private TaskEventsService service(int max, int perResource, int threads, int queue, long timeoutMs) {
        return new TaskEventsService(reader, streams, max, perResource, threads, queue, 100, 60000, timeoutMs, false);
    }
    private static Resource resource(Kind kind) { return new Resource(kind, UUID.randomUUID().toString()); }
    private void assertEmptyRegistry() {
        assertEquals(0, service.activeConnections()); assertEquals(0, service.resourceCount());
        assertEquals(0, service.statistics().pendingSnapshots());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Controlled event-stream operation did not complete");
    }

    private static final class FakeReader extends EventSnapshotReader {
        final Map<Resource, Snapshot> snapshots = new ConcurrentHashMap<>();
        final Map<Resource, AtomicInteger> reads = new ConcurrentHashMap<>();
        final Set<Resource> failures = ConcurrentHashMap.newKeySet();
        FakeReader() { super(null, null, JsonMapper.builder().build()); }
        void put(Resource resource, long version, String status) {
            snapshots.put(resource, new Snapshot(resource, version, status,
                    "{\"id\":\"" + resource.id() + "\",\"state_version\":" + version + ",\"status\":\"" + status + "\"}"));
        }
        @Override public Snapshot read(Resource resource) {
            reads.computeIfAbsent(resource, ignored -> new AtomicInteger()).incrementAndGet();
            if (failures.contains(resource)) throw new IllegalStateException("Injected read failure for owned fixture");
            return snapshots.get(resource);
        }
    }
    private static final class FakeStreams extends EventStreamFactory {
        final List<FakeStream> opened = new CopyOnWriteArrayList<>();
        final AtomicBoolean blockNext = new AtomicBoolean();
        final AtomicBoolean failNext = new AtomicBoolean();
        @Override public Stream open(long timeoutMs) {
            FakeStream stream = new FakeStream(timeoutMs, blockNext.getAndSet(false), failNext.getAndSet(false));
            opened.add(stream); return stream;
        }
    }
    private record Delivery(String id, long version) {}
    private static final class FakeStream extends EventStreamFactory.Stream {
        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release;
        final AtomicBoolean fail;
        final AtomicBoolean aborted = new AtomicBoolean();
        final AtomicInteger aborts = new AtomicInteger();
        final AtomicInteger activeWrites = new AtomicInteger(), maxConcurrentWrites = new AtomicInteger();
        FakeStream(long timeoutMs, boolean blocked, boolean fail) {
            super(new SseEmitter(timeoutMs)); release = new CountDownLatch(blocked ? 1 : 0); this.fail = new AtomicBoolean(fail);
        }
        @Override public void snapshot(String id, String json) throws IOException {
            int active = activeWrites.incrementAndGet(); maxConcurrentWrites.accumulateAndGet(active, Math::max); entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Controlled writer gate timed out");
                if (aborted.get() || fail.compareAndSet(true, false)) throw new IOException("Injected socket write failure");
                deliveries.add(new Delivery(id, Long.parseLong(id.substring(id.lastIndexOf(':') + 1))));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); throw new IOException("Controlled write interrupted", failure);
            } finally { activeWrites.decrementAndGet(); }
        }
        @Override public void heartbeat() { }
        @Override public void complete() { }
        @Override public void abort() { if (aborted.compareAndSet(false, true)) aborts.incrementAndGet(); }
        long lastVersion() { return deliveries.isEmpty() ? -1 : deliveries.getLast().version(); }
    }
}
