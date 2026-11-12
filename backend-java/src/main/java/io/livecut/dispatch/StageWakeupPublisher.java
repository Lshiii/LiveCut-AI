package io.livecut.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "livecut.dispatch.mode", havingValue = "kafka")
public class StageWakeupPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(StageWakeupPublisher.class);
    private final StageWakeupOutbox outbox;
    private final KafkaStageWakeupSender sender;

    public StageWakeupPublisher(StageWakeupOutbox outbox, KafkaStageWakeupSender sender) {
        this.outbox = outbox;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${livecut.kafka.relay-delay-ms:200}", scheduler = "stageWakeupScheduler")
    public void publishPending() { publishBatch(20); }

    int publishBatch(int maximum) {
        int published = 0;
        for (int i = 0; i < maximum; i++) {
            var delivery = outbox.claim();
            if (delivery == null) break;
            try {
                sender.send(delivery);
                if (outbox.published(delivery)) published++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outbox.failed(delivery);
                break;
            } catch (Exception failed) {
                // Never log a broker URL, credentials, payload or arbitrary exception text.
                outbox.failed(delivery);
                LOG.warn("KAFKA_SEND_FAILED event_id={} attempt={}", delivery.eventId(), delivery.attempt());
                break;
            }
        }
        return published;
    }
}
