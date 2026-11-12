package io.livecut.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class StageWakeupPublisherTests {
    private static final StageWakeupOutbox.Delivery FIRST = new StageWakeupOutbox.Delivery("event", "job", "{\"schema_version\":\"1.0\"}", "token", 1);

    @Test void brokerAckPrecedesPublishedMarkerAndMessageIsKeyedByJob() throws Exception {
        var producer = new MockProducer<String,String>(true, null, new StringSerializer(), new StringSerializer());
        var sender = new KafkaStageWakeupSender(producer, "livecut.stage-wakeup.v1");
        var outbox = mock(StageWakeupOutbox.class);
        when(outbox.claim()).thenReturn(FIRST, null);
        when(outbox.published(FIRST)).thenAnswer(invocation -> {
            assertEquals(1, producer.history().size());
            return true;
        });
        assertEquals(1, new StageWakeupPublisher(outbox, sender).publishBatch(20));
        assertEquals("job", producer.history().getFirst().key());
        assertEquals(FIRST.payload(), producer.history().getFirst().value());
        verify(outbox, never()).failed(any());
        sender.close();
    }

    @Test void sendFailureLeavesDurableRowRetryableWithoutPublishedMarker() throws Exception {
        var sender = mock(KafkaStageWakeupSender.class);
        doThrow(new ExecutionException(new IllegalStateException("broker unavailable"))).when(sender).send(FIRST);
        var outbox = mock(StageWakeupOutbox.class);
        when(outbox.claim()).thenReturn(FIRST);
        assertEquals(0, new StageWakeupPublisher(outbox, sender).publishBatch(20));
        verify(outbox).failed(FIRST);
        verify(outbox, never()).published(any());
        verify(outbox, times(1)).claim();
    }

    @Test void acknowledgementLossCanRepeatSameEventIdentity() throws Exception {
        var sender = mock(KafkaStageWakeupSender.class);
        var outbox = mock(StageWakeupOutbox.class);
        var reclaimed = new StageWakeupOutbox.Delivery(FIRST.eventId(), FIRST.jobId(), FIRST.payload(), "new-token", 2);
        when(outbox.claim()).thenReturn(FIRST, null, reclaimed, null);
        when(outbox.published(FIRST)).thenReturn(false);
        when(outbox.published(reclaimed)).thenReturn(true);
        var relay = new StageWakeupPublisher(outbox, sender);
        assertEquals(0, relay.publishBatch(20));
        assertEquals(1, relay.publishBatch(20));
        verify(sender).send(FIRST);
        verify(sender).send(reclaimed);
        assertEquals(FIRST.eventId(), reclaimed.eventId());
    }

    @Test void interruptedPublisherPreservesInterruptAndRetryableRow() throws Exception {
        var sender = mock(KafkaStageWakeupSender.class);
        doThrow(new InterruptedException()).when(sender).send(FIRST);
        var outbox = mock(StageWakeupOutbox.class);
        when(outbox.claim()).thenReturn(FIRST);
        try {
            assertEquals(0, new StageWakeupPublisher(outbox, sender).publishBatch(20));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(outbox).failed(FIRST);
        } finally { Thread.interrupted(); }
    }

    @Test void producerRequiresDurableAcknowledgementsAndBoundedPhysicalRetries() {
        var config = KafkaStageWakeupSender.configuration("127.0.0.1:9092");
        assertEquals("all", config.get("acks"));
        assertEquals(true, config.get("enable.idempotence"));
        assertEquals(3, config.get("retries"));
        assertEquals(10000, config.get("delivery.timeout.ms"));
        assertEquals(5000, config.get("max.block.ms"));
        assertThrows(IllegalArgumentException.class, () -> KafkaStageWakeupSender.configuration(" "));
    }

    @Test void pollModeDoesNotReadOrWriteOutboxAndUnknownModeIsRejected() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var poll = new StageWakeups(jdbc, "poll");
        poll.ready("anything"); poll.released("anything"); poll.readyForJob("anything"); poll.releasedForOwner("ANALYSIS", "anything");
        verifyNoInteractions(jdbc);
        assertThrows(IllegalArgumentException.class, () -> new StageWakeups(jdbc, "unknown"));
        assertThrows(IllegalStateException.class, () -> new StageWakeups(jdbc, "kafka").ready("anything"));
    }

    @Test void relayHasDedicatedSchedulerAndDoesNotBecomeTheOnlyDefaultScheduler() throws Exception {
        var configuration = new StageWakeupScheduling();
        var relay = configuration.stageWakeupScheduler();
        var maintenance = configuration.maintenanceScheduler();
        assertNotSame(relay, maintenance);
        assertNotEquals(relay.getThreadNamePrefix(), maintenance.getThreadNamePrefix());
        var annotation = StageWakeupPublisher.class.getMethod("publishPending").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertEquals("stageWakeupScheduler", annotation.scheduler());
    }
}
