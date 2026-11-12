package io.livecut.dispatch;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short DB leases permit multiple relays without holding locks across Kafka I/O. */
@Service
public class StageWakeupOutbox {
    public record Delivery(String eventId, String jobId, String payload, String leaseToken, int attempt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public StageWakeupOutbox(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Delivery claim() {
        return tx.execute(status -> {
            var rows = jdbc.queryForList("SELECT event_id,job_id,payload_json,attempts FROM stage_wakeup_outbox WHERE (status='PENDING' AND available_at<=CURRENT_TIMESTAMP(6)) OR (status='INFLIGHT' AND lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if (rows.isEmpty()) return null;
            var row = rows.getFirst();
            String token = UUID.randomUUID().toString(), id = String.valueOf(row.get("event_id"));
            int attempt = Math.min(Integer.MAX_VALUE - 1, ((Number) row.get("attempts")).intValue()) + 1;
            jdbc.update("UPDATE stage_wakeup_outbox SET status='INFLIGHT',attempts=?,lease_token=?,lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE event_id=?", attempt, token, id);
            return new Delivery(id, String.valueOf(row.get("job_id")), String.valueOf(row.get("payload_json")), token, attempt);
        });
    }

    public boolean published(Delivery delivery) {
        return jdbc.update("UPDATE stage_wakeup_outbox SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(6),lease_until=NULL,lease_token=NULL,last_error_code=NULL WHERE event_id=? AND status='INFLIGHT' AND lease_token=?",
                delivery.eventId(), delivery.leaseToken()) == 1;
    }

    public boolean failed(Delivery delivery) {
        int delay = 1 << Math.min(6, Math.max(0, delivery.attempt() - 1));
        return jdbc.update("UPDATE stage_wakeup_outbox SET status='PENDING',available_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),lease_until=NULL,lease_token=NULL,last_error_code='KAFKA_SEND_FAILED' WHERE event_id=? AND status='INFLIGHT' AND lease_token=?",
                delay, delivery.eventId(), delivery.leaseToken()) == 1;
    }
}
