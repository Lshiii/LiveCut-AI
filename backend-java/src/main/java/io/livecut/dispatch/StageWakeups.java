package io.livecut.dispatch;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Notifications are committed with stage state; a notification never grants execution rights. */
@Service
public class StageWakeups {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public StageWakeups(JdbcTemplate jdbc, @Value("${livecut.dispatch.mode:poll}") String mode) {
        if (!Set.of("poll", "kafka").contains(mode)) throw new IllegalArgumentException("Invalid dispatch mode");
        this.jdbc = jdbc;
        this.enabled = "kafka".equals(mode);
    }

    public void ready(String stageId) { enqueue(stageId, "READY"); }

    public void readyForJob(String jobId) {
        if (!enabled) return;
        requireTransaction();
        for (String id : jdbc.queryForList("SELECT id FROM job_stage WHERE job_id=? AND status='READY'", String.class, jobId)) ready(id);
    }

    public void released(String stageId) { enqueue(stageId, "CAPACITY_RELEASED"); }

    public void releasedForOwner(String ownerType, String ownerId) {
        if (!enabled) return;
        requireTransaction();
        // One release is a broadcast wakeup, regardless of how many sibling slots were freed.
        var rows = jdbc.queryForList("SELECT id FROM job_stage WHERE owner_type=? AND owner_id=? ORDER BY id LIMIT 1", String.class, ownerType, ownerId);
        if (!rows.isEmpty()) released(rows.getFirst());
    }

    private void enqueue(String stageId, String reason) {
        if (!enabled) return;
        requireTransaction();
        var rows = jdbc.queryForList("SELECT id,job_id,stage_type,status,required_worker_protocol,UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6)) AS observed_epoch FROM job_stage WHERE id=?", stageId);
        if (rows.size() != 1) throw new IllegalStateException("Wakeup stage missing");
        var row = rows.getFirst();
        if ("READY".equals(reason) && !"READY".equals(row.get("status"))) throw new IllegalStateException("Wakeup stage is not ready");
        String event = UUID.randomUUID().toString();
        // An epoch avoids Connector/J returning DATETIME as LocalDateTime, and is
        // independent of the JDBC/session timezone used by a particular caller.
        long micros = new java.math.BigDecimal(row.get("observed_epoch").toString()).movePointRight(6).longValueExact();
        String occurredAt = java.time.Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000).toString();
        String payload = JSON.writeValueAsString(Map.of(
                "schema_version", "1.0", "event_id", event, "event_type", "STAGE_WAKEUP",
                "stage_id", stageId, "job_id", row.get("job_id"), "stage_type", row.get("stage_type"),
                "required_worker_protocol", row.get("required_worker_protocol"), "reason", reason,
                "occurred_at", occurredAt));
        jdbc.update("INSERT INTO stage_wakeup_outbox (event_id,job_id,stage_id,payload_json) VALUES (?,?,?,?)",
                event, row.get("job_id"), stageId, payload);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Stage notification requires the stage transaction");
    }
}
