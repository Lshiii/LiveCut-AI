package io.livecut.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.livecut.jobs.JobService;
import io.livecut.jobs.JobModels;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.WorkerCapacityService;
import io.livecut.exports.ExportService;
import io.livecut.clips.ClipRequestService;
import io.livecut.agent.AgentService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** Real MySQL only, no HTTP listener, Kafka process or model provider. Own UUID rows only. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL", matches="jdbc:mysql:.*")
class StageWakeupOutboxIntegrationTests {
    private JdbcTemplate jdbc;
    private StageWakeups wakeups;
    private StageWakeupOutbox outbox;
    private TransactionTemplate tx;
    private DataSourceTransactionManager manager;
    private final List<String> extraJobs = new ArrayList<>();
    private String video, job, stage;

    @BeforeEach void create() {
        String url = System.getenv("LIVECUT_DB_URL");
        assertTrue(url.matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"), "Only the dedicated test database is permitted");
        var ds = new DriverManagerDataSource(url, System.getenv("LIVECUT_DB_USER"), System.getenv("LIVECUT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        wakeups = new StageWakeups(jdbc, "kafka");
        outbox = new StageWakeupOutbox(jdbc, manager);
        video = UUID.randomUUID().toString(); job = UUID.randomUUID().toString(); stage = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO video_asset (id,content_hash,source_key,original_filename,size_bytes,media_validation_status) VALUES (?,?,?,'outbox-synthetic.mp4',48,'HEADER_VALIDATED')", video, "a".repeat(64), "videos/"+video+".mp4");
        jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,'{}','QUEUED',0,0)", job, video, "outbox-"+job, "a".repeat(64), "b".repeat(64));
        jdbc.update("INSERT INTO job_stage (id,job_id,owner_id,stage_type,status,input_hash) VALUES (?,?,?,'MEDIA','BLOCKED',?)", stage, job, job, "c".repeat(64));
    }

    @AfterEach void cleanup() {
        if (jdbc != null && job != null) {
            for (String id : extraJobs) {
                jdbc.update("DELETE FROM stage_wakeup_outbox WHERE job_id=?", id);
                jdbc.update("DELETE FROM analysis_job WHERE id=?", id);
            }
            jdbc.update("DELETE FROM stage_wakeup_outbox WHERE job_id=?", job);
            jdbc.update("DELETE FROM analysis_job WHERE id=?", job);
            jdbc.update("DELETE FROM video_asset WHERE id=?", video);
        }
    }

    private String enqueue() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE job_stage SET status='READY' WHERE id=?", stage);
            wakeups.ready(stage);
        });
        return jdbc.queryForObject("SELECT event_id FROM stage_wakeup_outbox WHERE job_id=?", String.class, job);
    }

    @Test void rollbackRemovesStageTransitionAndNotificationTogether() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE job_stage SET status='READY' WHERE id=?", stage);
            wakeups.ready(stage);
            throw new IllegalStateException("force transaction rollback");
        }));
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?", String.class, stage));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stage_wakeup_outbox WHERE job_id=?", Integer.class, job));
    }

    @Test void committedReadyEventBindsExactStageAndProtocol() {
        String event = enqueue();
        var payload = JsonMapper.builder().build().readTree(jdbc.queryForObject("SELECT payload_json FROM stage_wakeup_outbox WHERE event_id=?", String.class, event));
        assertEquals("1.0", payload.path("schema_version").asText());
        assertEquals(event, payload.path("event_id").asText());
        assertEquals("STAGE_WAKEUP", payload.path("event_type").asText());
        assertEquals(stage, payload.path("stage_id").asText());
        assertEquals(job, payload.path("job_id").asText());
        assertEquals("MEDIA", payload.path("stage_type").asText());
        assertEquals("READY", payload.path("reason").asText());
        assertEquals(1, payload.path("required_worker_protocol").asInt());
        assertDoesNotThrow(() -> java.time.Instant.parse(payload.path("occurred_at").asText()));
    }

    @Test void expiredLeaseReclaimsSamePayloadAndRejectsOldAcknowledgement() {
        String event = enqueue();
        var first = outbox.claim();
        assertEquals(event, first.eventId());
        assertNull(outbox.claim());
        jdbc.update("UPDATE stage_wakeup_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE event_id=?", event);
        var second = outbox.claim();
        assertEquals(first.eventId(), second.eventId());
        assertEquals(first.payload(), second.payload());
        assertNotEquals(first.leaseToken(), second.leaseToken());
        assertFalse(outbox.published(first));
        assertFalse(outbox.failed(first));
        assertTrue(outbox.published(second));
        assertNull(outbox.claim());
    }

    @Test void sendFailureSchedulesRetryAndPreservesIdentity() {
        String event = enqueue();
        var first = outbox.claim();
        assertTrue(outbox.failed(first));
        assertNull(outbox.claim());
        jdbc.update("UPDATE stage_wakeup_outbox SET available_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE event_id=?", event);
        var second = outbox.claim();
        assertEquals(first.eventId(), second.eventId());
        assertEquals(2, second.attempt());
        assertTrue(outbox.published(second));
    }

    @Test void releaseEventIsCommittedForCancelledStageWithoutRevivingIt() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE job_stage SET status='CANCELLED' WHERE id=?", stage);
            wakeups.releasedForOwner("ANALYSIS", job);
        });
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM job_stage WHERE id=?", String.class, stage));
        var payload = JsonMapper.builder().build().readTree(jdbc.queryForObject("SELECT payload_json FROM stage_wakeup_outbox WHERE job_id=?", String.class, job));
        assertEquals("CAPACITY_RELEASED", payload.path("reason").asText());
    }

    private JobService jobs() {
        var jobs = new JobService(jdbc, manager, 30, 1800, 3, 0, false,
                mock(ArtifactVerifier.class), mock(ExportService.class), mock(WorkerCapacityService.class),
                mock(ClipRequestService.class), mock(AgentService.class), wakeups);
        ReflectionTestUtils.setField(jobs, "defaultTextModel", "synthetic-text");
        ReflectionTestUtils.setField(jobs, "defaultFallbackModels", "");
        return jobs;
    }

    @Test void actualAnalysisCreateRetryAndCancelCommitWakeupsWithoutGrantingBudget() {
        var jobs = jobs();
        String key = "wakeup-create-" + UUID.randomUUID();
        var request = new JobModels.CreateJobRequest(video, Map.of("max_text_calls", 0, "max_asr_submissions", 0));
        var created = jobs.create(request, key);
        extraJobs.add(created.id());
        assertEquals(created.id(), jobs.create(request, key).id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stage_wakeup_outbox WHERE job_id=?", Integer.class, created.id()));
        String media = created.stages().stream().filter(s -> s.stageType().equals("MEDIA")).findFirst().orElseThrow().id();
        jdbc.update("UPDATE job_stage SET status='FAILED' WHERE id=?", media);
        jdbc.update("UPDATE analysis_job SET status='FAILED' WHERE id=?", created.id());
        jobs.retry(created.id());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stage_wakeup_outbox WHERE job_id=?", Integer.class, created.id()));
        var cancelled = jobs.cancel(created.id());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM stage_wakeup_outbox WHERE job_id=?", Integer.class, created.id()));
        assertEquals(0, cancelled.budget().maxTextCalls());
        assertEquals(0, cancelled.budget().maxAsrSubmissions());
    }

    @Test void actualLeaseRecoveryCommitsReleaseAndReadyWithFreshExecutionCredentials() {
        var jobs = jobs();
        jdbc.update("UPDATE job_stage SET status='RUNNING',attempt=1,run_token='obsolete-token-123456',worker_id='old-worker',lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),hard_deadline_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", stage);
        assertEquals(2, jobs.recoverExpired());
        var row = jdbc.queryForMap("SELECT status,attempt,run_token,worker_id,lease_until FROM job_stage WHERE id=?", stage);
        assertEquals("READY", row.get("status"));
        assertEquals(1, ((Number) row.get("attempt")).intValue());
        assertNull(row.get("run_token")); assertNull(row.get("worker_id")); assertNull(row.get("lease_until"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stage_wakeup_outbox WHERE job_id=?", Integer.class, job));
        var reasons = jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.reason')) FROM stage_wakeup_outbox WHERE job_id=?", String.class, job);
        assertEquals(java.util.Set.of("READY", "CAPACITY_RELEASED"), java.util.Set.copyOf(reasons));
    }
}
