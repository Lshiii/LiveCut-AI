package io.livecut.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import io.livecut.jobs.JobModels.*;
import io.livecut.exports.ExportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Fixed-stage database scheduler. All mutations lock the parent before stages. */
@Service
@EnableScheduling
public class JobService {
    private static final List<String> FLOW = List.of("MEDIA", "ASR", "SEGMENT", "TIMELINE");
    private static final Set<String> CONFIG_FIELDS = Set.of("asr_model", "text_model", "visual_provider", "max_text_calls", "max_asr_submissions");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RowMapper<StageRow> STAGE = (row, index) -> new StageRow(
            row.getString("id"), row.getString("job_id"), row.getString("stage_type"), row.getString("shard_key"),
            row.getString("status"), row.getInt("attempt"), row.getString("run_token"), row.getString("worker_id"),
            instant(row, "lease_until"), instant(row, "hard_deadline_at"), instant(row, "next_run_at"),
            row.getString("input_hash"), row.getString("checkpoint_key"), row.getString("checkpoint_hash"),
            row.getString("manifest_key"), row.getString("manifest_hash"), row.getString("error_code"),
            row.getString("owner_type"), row.getString("owner_id"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ArtifactVerifier artifactVerifier;
    private final ExportService exports;
    private final int leaseSeconds;
    private final int hardDeadlineSeconds;
    private final int maxAttempts;
    private final int retryDelaySeconds;
    private final boolean scanEnabled;

    public JobService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                      @Value("${livecut.jobs.lease-seconds:30}") int leaseSeconds,
                      @Value("${livecut.jobs.hard-deadline-seconds:1800}") int hardDeadlineSeconds,
                      @Value("${livecut.jobs.max-attempts:3}") int maxAttempts,
                      @Value("${livecut.jobs.retry-delay-seconds:2}") int retryDelaySeconds,
                      @Value("${livecut.jobs.scan-enabled:true}") boolean scanEnabled,
                      ArtifactVerifier artifactVerifier, ExportService exports) {
        if (leaseSeconds < 1 || leaseSeconds > 3600 || hardDeadlineSeconds < 1 || hardDeadlineSeconds > 86400
                || maxAttempts < 1 || maxAttempts > 10 || retryDelaySeconds < 0 || retryDelaySeconds > 3600) {
            throw new IllegalArgumentException("Invalid scheduler lease, deadline, attempt or retry settings");
        }
        this.jdbc = jdbc;
        this.artifactVerifier = artifactVerifier;
        this.exports = exports;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.leaseSeconds = leaseSeconds;
        this.hardDeadlineSeconds = hardDeadlineSeconds;
        this.maxAttempts = maxAttempts;
        this.retryDelaySeconds = retryDelaySeconds;
        this.scanEnabled = scanEnabled;
    }

    public JobSnapshot create(CreateJobRequest request, String idempotencyKey) {
        if (request == null) { throw bad("INVALID_ARGUMENT", "A job request is required"); }
        String videoId = uuid(request.videoId(), "INVALID_VIDEO_ID");
        String key = identifier(idempotencyKey, "idempotency key");
        Map<String, Object> config = config(request.config());
        String configJson = JSON.writeValueAsString(config);
        String configHash = sha256(configJson);
        String requestHash = sha256(videoId + "\n" + configHash);
        try {
            return transaction.execute(status -> {
                List<Map<String, Object>> videos = jdbc.queryForList("SELECT id, content_hash FROM video_asset WHERE id=? FOR UPDATE", videoId);
                if (videos.isEmpty()) { throw missing("VIDEO_NOT_FOUND", "Video was not found"); }
                List<String> existing = jdbc.queryForList("SELECT id FROM analysis_job WHERE idempotency_key=?", String.class, key);
                if (!existing.isEmpty()) { return existingCreate(existing.getFirst(), requestHash); }
                int generation = jdbc.queryForObject("SELECT COALESCE(MAX(generation),0)+1 FROM analysis_job WHERE video_id=?", Integer.class, videoId);
                String id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO analysis_job (id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,?,?,?,?,?,'QUEUED',?,?)",
                        id, videoId, generation, key, requestHash, configHash, configJson, config.get("max_text_calls"), config.get("max_asr_submissions"));
                String sourceHash = String.valueOf(videos.getFirst().get("content_hash"));
                for (String type : FLOW) {
                    jdbc.update("INSERT INTO job_stage (id,job_id,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,?,?,'0',?,?)",
                            UUID.randomUUID().toString(), id, id, type, type.equals("MEDIA") ? "READY" : "BLOCKED",
                            sha256(sourceHash + "\n" + configHash + "\n" + type + "\n0"));
                }
                return snapshot(job(id, true));
            });
        } catch (DuplicateKeyException concurrentCreate) {
            // A cross-video idempotency-key race can lose its INSERT. The whole
            // losing transaction rolls back before this fresh lookup.
            return transaction.execute(status -> {
                List<String> existing = jdbc.queryForList("SELECT id FROM analysis_job WHERE idempotency_key=?", String.class, key);
                if (existing.isEmpty()) { throw concurrentCreate; }
                return existingCreate(existing.getFirst(), requestHash);
            });
        }
    }

    public JobPage list(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) { throw bad("INVALID_ARGUMENT", "limit must be 1..100 and offset nonnegative"); }
        return transaction.execute(status -> {
            List<String> ids = jdbc.queryForList("SELECT id FROM analysis_job ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", String.class, limit, offset);
            // Match detail reads: a stage cannot change between reading the
            // parent's state_version and computing the public action policy.
            return new JobPage(ids.stream().map(id -> snapshot(job(id, true))).toList(), limit, offset);
        });
    }

    public JobSnapshot get(String jobId) {
        String id = uuid(jobId, "INVALID_JOB_ID");
        return transaction.execute(status -> snapshot(job(id, true)));
    }

    public JobSnapshot cancel(String jobId) {
        String id = uuid(jobId, "INVALID_JOB_ID");
        return transaction.execute(status -> {
            Map<String, Object> job = job(id, true);
            if ("CANCELLED".equals(job.get("status"))) { return snapshot(job); }
            if (!actions(job, stages(id, true)).canCancel()) { throw conflict("JOB_NOT_CANCELLABLE", "A completed job cannot be cancelled"); }
            jdbc.update("UPDATE job_stage SET status='CANCELLED',run_token=NULL,worker_id=NULL,lease_until=NULL,hard_deadline_at=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE job_id=? AND owner_type='ANALYSIS' AND status NOT IN ('SUCCEEDED','SKIPPED')", id);
            touch(id, "CANCELLED");
            return snapshot(job(id, true));
        });
    }

    public JobSnapshot retry(String jobId) {
        String id = uuid(jobId, "INVALID_JOB_ID");
        return transaction.execute(status -> {
            Map<String, Object> job = job(id, true);
            JobActions actions = actions(job, stages(id, true));
            if (!actions.canRetry()) {
                String message = "ATTEMPTS_EXHAUSTED".equals(actions.retryBlockReason())
                        ? "Execution attempts are exhausted; create a new analysis job"
                        : "Only failed jobs with failed stages can be retried";
                throw conflict(actions.retryBlockReason(), message);
            }
            jdbc.update("UPDATE job_stage SET status='READY',run_token=NULL,worker_id=NULL,lease_until=NULL,hard_deadline_at=NULL,next_run_at=CURRENT_TIMESTAMP(6),error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE job_id=? AND owner_type='ANALYSIS' AND status='FAILED'", id);
            touch(id, "RUNNING");
            return snapshot(job(id, true));
        });
    }

    public List<ReadyStage> ready(String stageType, int limit) {
        if (limit < 1 || limit > 100) { throw bad("INVALID_ARGUMENT", "limit must be 1..100"); }
        if (stageType != null && !FLOW.contains(stageType) && !"EXPORT_CLIP".equals(stageType)) { throw bad("INVALID_ARGUMENT", "Unknown stage type"); }
        String predicate = stageType == null ? "" : " AND s.stage_type=?";
        List<Object> parameters = new ArrayList<>();
        if (stageType != null) { parameters.add(stageType); }
        parameters.add(limit);
        return jdbc.query("SELECT s.* FROM job_stage s JOIN analysis_job j ON j.id=s.job_id LEFT JOIN export_job e ON s.owner_type='EXPORT' AND e.id=s.owner_id AND e.job_id=s.job_id WHERE s.status='READY' AND s.next_run_at<=CURRENT_TIMESTAMP(6) AND ((s.owner_type='ANALYSIS' AND j.status IN ('QUEUED','RUNNING')) OR (s.owner_type='EXPORT' AND j.status='REVIEW_READY' AND e.status IN ('QUEUED','RUNNING')))" + predicate + " ORDER BY s.next_run_at,s.id LIMIT ?",
                (row, index) -> new ReadyStage(row.getString("id"), row.getString("job_id"), row.getString("stage_type"), row.getString("shard_key"), instant(row, "next_run_at")), parameters.toArray());
    }

    public ClaimResult claim(ClaimRequest request) {
        if (request == null) { throw bad("INVALID_ARGUMENT", "A claim request is required"); }
        String stageId = uuid(request.stageId(), "INVALID_STAGE_ID");
        String worker = identifier(request.workerId(), "worker_id");
        String claimId = identifier(request.claimRequestId(), "claim_request_id");
        String requestHash = sha256(stageId + "\n" + worker);
        return transaction.execute(status -> {
            StageParent parent = lockParent(stageId);
            Map<String, Object> job = parent.analysis();
            String jobId = String.valueOf(job.get("id"));
            StageRow stage = parent.stage();
            Instant now = dbNow();
            int inserted = jdbc.update("INSERT IGNORE INTO stage_claim_request (worker_id,claim_request_id,request_hash,stage_id,outcome) VALUES (?,?,?,?,'PENDING')", worker, claimId, requestHash, stageId);
            Map<String, Object> receipt = jdbc.queryForMap("SELECT * FROM stage_claim_request WHERE worker_id=? AND claim_request_id=? FOR UPDATE", worker, claimId);
            if (!requestHash.equals(receipt.get("request_hash"))) { throw conflict("IDEMPOTENCY_CONFLICT", "Claim request ID belongs to a different request"); }
            if (inserted == 0) {
                if (!"CLAIMED".equals(receipt.get("outcome"))) {
                    return noClaim("EXPIRED".equals(receipt.get("outcome")) ? "EXPIRED" : "NO_WORK", stageId);
                }
                boolean valid = stage.status().equals("RUNNING") && activeParent(parent)
                        && stage.attempt() == ((Number) receipt.get("attempt")).intValue()
                        && sameToken(stage.runToken(), String.valueOf(receipt.get("run_token")))
                        && stage.leaseUntil().isAfter(now) && stage.hardDeadlineAt().isAfter(now);
                if (!valid) {
                    jdbc.update("UPDATE stage_claim_request SET outcome='EXPIRED' WHERE worker_id=? AND claim_request_id=?", worker, claimId);
                    return noClaim("EXPIRED", stageId);
                }
                return claimed("ALREADY_CLAIMED", stage, job);
            }
            if (!activeParent(parent) || !stage.status().equals("READY") || stage.nextRunAt().isAfter(now)) {
                jdbc.update("UPDATE stage_claim_request SET outcome='NO_WORK' WHERE worker_id=? AND claim_request_id=?", worker, claimId);
                return noClaim("NO_WORK", stageId);
            }
            if (stage.attempt() >= maxAttempts) {
                terminalFailure(stage, "ATTEMPTS_EXHAUSTED");
                jdbc.update("UPDATE stage_claim_request SET outcome='NO_WORK' WHERE worker_id=? AND claim_request_id=?", worker, claimId);
                return noClaim("NO_WORK", stageId);
            }
            String token = UUID.randomUUID().toString();
            Instant deadline = now.plusSeconds(hardDeadlineSeconds);
            Instant lease = minimum(now.plusSeconds(leaseSeconds), deadline);
            jdbc.update("UPDATE job_stage SET status='RUNNING',attempt=attempt+1,run_token=?,worker_id=?,lease_until=?,hard_deadline_at=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='READY'",
                    token, worker, timestamp(lease), timestamp(deadline), stageId);
            StageRow current = stage(stageId, true);
            jdbc.update("UPDATE stage_claim_request SET outcome='CLAIMED',attempt=?,run_token=? WHERE worker_id=? AND claim_request_id=?", current.attempt(), token, worker, claimId);
            touchParent(current, "RUNNING");
            return claimed("CLAIMED", current, job(jobId, true));
        });
    }

    public StageActionResult heartbeat(String stageId, Credential credential) {
        return transaction.execute(status -> {
            LockedStage locked = lockActiveStage(stageId, credential);
            if (!locked.stage().hardDeadlineAt().isAfter(locked.dbNow())) { throw stale(); }
            Instant lease = minimum(locked.dbNow().plusSeconds(leaseSeconds), locked.stage().hardDeadlineAt());
            jdbc.update("UPDATE job_stage SET lease_until=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", timestamp(lease), locked.stage().id());
            touchParent(locked.stage(), null);
            return action("ACCEPTED", locked.stage().id());
        });
    }

    public StageActionResult checkpoint(String stageId, CheckpointRequest request) {
        if (request == null) { throw bad("INVALID_ARGUMENT", "A checkpoint request is required"); }
        return transaction.execute(status -> {
            LockedStage locked = lockActiveStage(stageId, request.credential());
            artifactKey(locked.stage(), request.checkpointKey(), false);
            hash(request.checkpointHash(), "INVALID_MANIFEST_HASH");
            if (request.checkpointKey().equals(locked.stage().checkpointKey())) {
                if (!request.checkpointHash().equals(locked.stage().checkpointHash())) { throw conflict("CHECKPOINT_CONFLICT", "An immutable checkpoint has a different hash"); }
                artifactVerifier.verifyCheckpoint(request.checkpointKey(), request.checkpointHash(),
                        artifactIdentity(locked.stage(), job(locked.jobId(), false)));
                return action("ALREADY_APPLIED", locked.stage().id());
            }
            artifactVerifier.verifyCheckpoint(request.checkpointKey(), request.checkpointHash(),
                    artifactIdentity(locked.stage(), job(locked.jobId(), false)));
            jdbc.update("UPDATE job_stage SET checkpoint_key=?,checkpoint_hash=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", request.checkpointKey(), request.checkpointHash(), locked.stage().id());
            touchParent(locked.stage(), null);
            return action("ACCEPTED", locked.stage().id());
        });
    }

    public StageActionResult complete(String stageId, CompleteRequest request) {
        if (request == null) { throw bad("INVALID_ARGUMENT", "A completion request is required"); }
        String id = uuid(stageId, "INVALID_STAGE_ID");
        credential(request.credential());
        return transaction.execute(status -> {
            StageParent parent = lockParent(id);
            Map<String, Object> job = parent.analysis();
            String jobId = String.valueOf(job.get("id"));
            StageRow stage = parent.stage();
            if (!isExport(stage) && "CANCELLED".equals(job.get("status"))) { throw stale(); }
            if (stage.status().equals("SUCCEEDED") && matches(stage, request.credential())) {
                artifactKey(stage, request.manifestKey(), true);
                hash(request.manifestHash(), "INVALID_MANIFEST_HASH");
                if (!request.manifestHash().equals(stage.manifestHash()) || !request.manifestKey().equals(stage.manifestKey())) {
                    throw conflict("RESULT_CONFLICT", "This completed attempt already has a different immutable result");
                }
                verifyResult(stage, job, request);
                return new StageActionResult("ALREADY_APPLIED", stageSnapshot(stage, false));
            }
            requireActive(parent, request.credential());
            if (!stage.hardDeadlineAt().isAfter(dbNow())) { throw stale(); }
            artifactKey(stage, request.manifestKey(), true);
            hash(request.manifestHash(), "INVALID_MANIFEST_HASH");
            var manifest = verifyResult(stage, job, request);
            jdbc.update("UPDATE job_stage SET status='SUCCEEDED',lease_until=NULL,manifest_key=?,manifest_hash=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", request.manifestKey(), request.manifestHash(), id);
            if (isExport(stage)) { exports.recordSuccess(stage, manifest); }
            else { advance(jobId, stage.stageType()); }
            touchParent(stage, null);
            return action("ACCEPTED", id);
        });
    }

    public StageActionResult fail(String stageId, FailRequest request) {
        if (request == null || request.errorCode() == null || !request.errorCode().matches("[A-Z0-9_]{1,64}")) {
            throw bad("INVALID_ARGUMENT", "A safe error_code is required");
        }
        return transaction.execute(status -> {
            LockedStage locked = lockActiveStage(stageId, request.credential());
            if (request.retryable() && locked.stage().attempt() < maxAttempts) {
                retryWait(locked.stage(), request.errorCode(), locked.dbNow());
                touchParent(locked.stage(), null);
            } else { terminalFailure(locked.stage(), request.errorCode()); }
            return action("ACCEPTED", locked.stage().id());
        });
    }

    /** Caller owns the transaction; returned locks live until that transaction ends. */
    public LockedStage lockActiveStage(String stageId, Credential execution) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("lockActiveStage requires an existing database transaction");
        }
        String id = uuid(stageId, "INVALID_STAGE_ID");
        credential(execution);
        StageParent parent = lockParent(id);
        StageRow row = parent.stage();
        requireActive(parent, execution);
        Instant now = dbNow();
        if (!row.hardDeadlineAt().isAfter(now)) { throw stale(); }
        return new LockedStage(row.jobId(), row, now);
    }

    @Scheduled(fixedDelayString = "${livecut.jobs.scan-delay-ms:1000}")
    public void scheduledRecovery() { if (scanEnabled) { recoverExpired(); } }

    public int recoverExpired() {
        List<String> ids = jdbc.queryForList("SELECT id FROM job_stage WHERE (status='RUNNING' AND (lease_until<=CURRENT_TIMESTAMP(6) OR hard_deadline_at<=CURRENT_TIMESTAMP(6))) OR (status='RETRY_WAIT' AND next_run_at<=CURRENT_TIMESTAMP(6)) ORDER BY next_run_at,id LIMIT 100", String.class);
        int changed = 0;
        for (String id : ids) {
            Integer count = transaction.execute(status -> {
                StageParent parent = lockParent(id);
                if (!activeParent(parent)) { return 0; }
                StageRow stage = parent.stage();
                Instant now = dbNow();
                int mutations = 0;
                if (stage.status().equals("RUNNING") && (!stage.leaseUntil().isAfter(now) || !stage.hardDeadlineAt().isAfter(now))) {
                    if (stage.attempt() >= maxAttempts) { terminalFailure(stage, "LEASE_EXPIRED"); return 1; }
                    retryWait(stage, "LEASE_EXPIRED", now);
                    mutations++;
                }
                mutations += jdbc.update("UPDATE job_stage SET status='READY',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RETRY_WAIT' AND next_run_at<=?", id, timestamp(now));
                if (mutations > 0) { touchParent(stage, null); }
                return mutations;
            });
            changed += count == null ? 0 : count;
        }
        return changed;
    }

    private void retryWait(StageRow stage, String code, Instant now) {
        jdbc.update("UPDATE job_stage SET status='RETRY_WAIT',run_token=NULL,worker_id=NULL,lease_until=NULL,hard_deadline_at=NULL,next_run_at=?,error_code=?,last_retryable=TRUE,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", timestamp(now.plusSeconds(retryDelaySeconds)), code, stage.id());
    }

    private void terminalFailure(StageRow stage, String code) {
        if (!isExport(stage)) { stages(stage.jobId(), true); }
        jdbc.update("UPDATE job_stage SET status='FAILED',run_token=NULL,worker_id=NULL,lease_until=NULL,hard_deadline_at=NULL,error_code=?,last_retryable=FALSE,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", code, stage.id());
        if (isExport(stage)) { exports.refreshLocked(stage.ownerId()); return; }
        // Fence sibling work when the parent is terminal; manual retry only
        // revives explicit FAILED rows, never a still-live previous token.
        jdbc.update("UPDATE job_stage SET status='FAILED',run_token=NULL,worker_id=NULL,lease_until=NULL,hard_deadline_at=NULL,error_code='PARENT_STAGE_FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE job_id=? AND owner_type='ANALYSIS' AND id<>? AND status IN ('RUNNING','READY','RETRY_WAIT')", stage.jobId(), stage.id());
        touch(stage.jobId(), "FAILED");
    }

    private void advance(String jobId, String completedType) {
        List<StageRow> rows = stages(jobId, true);
        if (rows.stream().filter(row -> row.stageType().equals(completedType)).anyMatch(row -> !Set.of("SUCCEEDED", "SKIPPED").contains(row.status()))) { return; }
        int index = FLOW.indexOf(completedType);
        if (index == FLOW.size() - 1) { touch(jobId, "REVIEW_READY"); return; }
        String next = FLOW.get(index + 1);
        String dependencies = rows.stream().filter(row -> row.stageType().equals(completedType))
                .sorted(java.util.Comparator.comparing(StageRow::shardKey))
                .map(row -> row.id() + ":" + row.manifestHash()).reduce("", (left, right) -> left + "\n" + right);
        for (StageRow row : rows) {
            if (row.stageType().equals(next) && row.status().equals("BLOCKED")) {
                jdbc.update("UPDATE job_stage SET status='READY',input_hash=?,next_run_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='BLOCKED'", sha256(row.inputHash() + dependencies), row.id());
            }
        }
    }

    private JobSnapshot existingCreate(String id, String requestHash) {
        Map<String, Object> row = job(id, true);
        if (!requestHash.equals(row.get("request_hash"))) { throw conflict("IDEMPOTENCY_CONFLICT", "Idempotency key belongs to different job input"); }
        return snapshot(row);
    }

    private Map<String, Object> job(String id, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM analysis_job WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) { throw missing("JOB_NOT_FOUND", "Analysis job was not found"); }
        return rows.getFirst();
    }

    private String stageJob(String id) {
        List<String> ids = jdbc.queryForList("SELECT job_id FROM job_stage WHERE id=?", String.class, id);
        if (ids.isEmpty()) { throw missing("STAGE_NOT_FOUND", "Stage was not found"); }
        return ids.getFirst();
    }

    private StageRow stage(String id, boolean lock) {
        List<StageRow> rows = jdbc.query("SELECT * FROM job_stage WHERE id=?" + (lock ? " FOR UPDATE" : ""), STAGE, id);
        if (rows.isEmpty()) { throw missing("STAGE_NOT_FOUND", "Stage was not found"); }
        return rows.getFirst();
    }

    private List<StageRow> stages(String jobId, boolean lock) {
        return jdbc.query("SELECT * FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' ORDER BY id" + (lock ? " FOR UPDATE" : ""), STAGE, jobId);
    }

    private JobSnapshot snapshot(Map<String, Object> row) {
        String id = String.valueOf(row.get("id"));
        List<StageRow> rows = stages(id, false);
        List<StageSnapshot> stages = rows.stream().map(stage -> stageSnapshot(stage, true)).toList();
        return new JobSnapshot(id, String.valueOf(row.get("video_id")), ((Number) row.get("generation")).intValue(),
                String.valueOf(row.get("status")), String.valueOf(row.get("config_hash")), readConfig(row),
                ((Number) row.get("state_version")).longValue(), stages,
                new BudgetSnapshot(((Number) row.get("max_text_calls")).intValue(),
                        ((Number) row.get("text_calls_reserved")).intValue(),
                        ((Number) row.get("max_asr_submissions")).intValue(),
                        ((Number) row.get("asr_calls_reserved")).intValue()), actions(row, rows),
                valueInstant(row.get("created_at")), valueInstant(row.get("updated_at")));
    }

    /** Advisory snapshot and mutation endpoints share the exact same policy.
     * Budgets are deliberately separate: a legal stage retry may use cached
     * artifacts, and never reserves or grants another provider request here.
     */
    private JobActions actions(Map<String, Object> job, List<StageRow> rows) {
        String status = String.valueOf(job.get("status"));
        boolean canCancel = !Set.of("CANCELLED", "REVIEW_READY").contains(status);
        List<StageRow> failed = rows.stream().filter(stage -> stage.status().equals("FAILED")).toList();
        String blocked = !"FAILED".equals(status) || failed.isEmpty() ? "JOB_NOT_RETRYABLE"
                : failed.stream().anyMatch(stage -> stage.attempt() >= maxAttempts) ? "ATTEMPTS_EXHAUSTED" : null;
        return new JobActions(canCancel, blocked == null, blocked, maxAttempts);
    }

    private static StageSnapshot stageSnapshot(StageRow row, boolean publicView) {
        return new StageSnapshot(row.id(), row.stageType(), row.shardKey(), row.status(), row.attempt(),
                row.leaseUntil(), row.hardDeadlineAt(), row.nextRunAt(), row.inputHash(),
                publicView ? null : row.checkpointKey(), publicView ? null : row.manifestKey(), row.manifestHash(), row.errorCode());
    }

    private StageActionResult action(String outcome, String stageId) { return new StageActionResult(outcome, stageSnapshot(stage(stageId, true), false)); }

    private ArtifactVerifier.ManifestIdentity artifactIdentity(StageRow stage, Map<String, Object> job) {
        if (isExport(stage)) { return exports.manifestIdentity(stage, job); }
        String sourceHash = jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, job.get("video_id"));
        return new ArtifactVerifier.ManifestIdentity(stage.jobId(), String.valueOf(job.get("video_id")),
                ((Number) job.get("generation")).intValue(), stage.id(), stage.stageType(),
                stage.attempt(), stage.runToken(), sourceHash);
    }

    private ClaimResult claimed(String outcome, StageRow stage, Map<String, Object> job) {
        Map<String, Object> video = jdbc.queryForMap("SELECT source_key,content_hash FROM video_asset WHERE id=?", job.get("video_id"));
        List<ArtifactRef> artifacts = isExport(stage) ? List.of() : stages(stage.jobId(), true).stream().filter(row -> row.status().equals("SUCCEEDED"))
                .map(row -> new ArtifactRef(row.id(), row.stageType(), row.shardKey(), row.manifestKey(), row.manifestHash())).toList();
        return new ClaimResult(outcome, stage.id(), stage.attempt(), stage.runToken(), stage.leaseUntil(), stage.hardDeadlineAt(),
                stage.jobId(), stage.stageType(), stage.shardKey(), String.valueOf(video.get("source_key")),
                isExport(stage) ? exports.manifestIdentity(stage, job).sourceHash() : String.valueOf(video.get("content_hash")),
                isExport(stage) ? exports.claimConfig(stage) : readConfig(job), artifacts,
                stage.checkpointKey(), stage.checkpointHash(), stage.inputHash(),
                String.valueOf(job.get("video_id")), ((Number) job.get("generation")).intValue());
    }

    private static ClaimResult noClaim(String outcome, String stageId) { return new ClaimResult(outcome, stageId, null, null, null, null, null, null, null, null, null, null, List.of(), null, null, null, null, 0); }

    private void touch(String id, String status) {
        if (status == null) { jdbc.update("UPDATE analysis_job SET state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id); }
        else { jdbc.update("UPDATE analysis_job SET status=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", status, id); }
    }

    private Instant dbNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant(); }
    private static boolean activeJob(Map<String, Object> job) { return Set.of("QUEUED", "RUNNING").contains(job.get("status")); }
    private static boolean matches(StageRow stage, Credential credential) { return stage.attempt() == credential.attempt() && sameToken(stage.runToken(), credential.runToken()); }
    private static boolean sameToken(String left, String right) { return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII)); }
    private record StageParent(Map<String, Object> analysis, Map<String, Object> owner, StageRow stage) {}
    private StageParent lockParent(String id) {
        StageRow hint = stage(id, false);
        Map<String, Object> analysis = job(hint.jobId(), true);
        Map<String, Object> owner = isExport(hint) ? exports.lockOwner(hint.ownerId(), hint.jobId()) : analysis;
        return new StageParent(analysis, owner, stage(id, true));
    }
    private boolean activeParent(StageParent parent) {
        return isExport(parent.stage()) ? "REVIEW_READY".equals(parent.analysis().get("status")) && exports.active(parent.owner()) : activeJob(parent.analysis());
    }
    private void requireActive(StageParent parent, Credential credential) {
        if (!activeParent(parent) || !parent.stage().status().equals("RUNNING") || !matches(parent.stage(), credential)) { throw stale(); }
    }
    private static boolean isExport(StageRow stage) { return "EXPORT".equals(stage.ownerType()); }
    private void touchParent(StageRow stage, String analysisStatus) {
        if (isExport(stage)) { exports.refreshLocked(stage.ownerId()); }
        else { touch(stage.jobId(), analysisStatus); }
    }
    private ArtifactVerifier.VerifiedManifest verifyResult(StageRow stage, Map<String, Object> job, CompleteRequest request) {
        return isExport(stage) ? exports.verifyResult(stage, job, request.manifestKey(), request.manifestHash())
                : artifactVerifier.verifyManifest(request.manifestKey(), request.manifestHash(), artifactIdentity(stage, job));
    }
    private static void credential(Credential value) { if (value == null || value.attempt() < 1 || value.runToken() == null || !value.runToken().matches("[A-Za-z0-9_-]{16,64}")) { throw stale(); } }

    private static void artifactKey(StageRow stage, String key, boolean manifest) {
        String prefix = "jobs/" + stage.jobId() + "/stages/" + stage.id() + "/attempts/" + stage.attempt() + "/" + stage.runToken() + "/";
        if (key == null || key.length() > 512 || !key.startsWith(prefix) || !key.matches("[A-Za-z0-9_./-]+")) { throw bad("INVALID_MANIFEST_KEY", "Artifact must be a relative reference inside the current attempt directory"); }
        String suffix = key.substring(prefix.length());
        if (suffix.isEmpty() || (manifest && !suffix.equals("manifest.json"))) { throw bad("INVALID_MANIFEST_KEY", "Invalid attempt manifest reference"); }
        for (String part : suffix.split("/", -1)) { if (part.isEmpty() || part.equals(".") || part.equals("..")) { throw bad("INVALID_MANIFEST_KEY", "Artifact reference contains an invalid path component"); } }
    }

    private static Map<String, Object> config(Map<String, Object> supplied) {
        Map<String, Object> result = new TreeMap<>();
        result.put("asr_model", "paraformer-v2"); result.put("text_model", "qwen-plus");
        result.put("visual_provider", "ocr"); result.put("max_text_calls", 12); result.put("max_asr_submissions", 1);
        if (supplied != null) {
            if (supplied.keySet().stream().anyMatch(key -> key == null || !CONFIG_FIELDS.contains(key))) { throw bad("INVALID_ARGUMENT", "Unknown analysis configuration field"); }
            result.putAll(supplied);
        }
        for (String field : List.of("asr_model", "text_model")) {
            if (!(result.get(field) instanceof String model) || !model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) { throw bad("INVALID_ARGUMENT", "Invalid model identifier"); }
        }
        if (!(result.get("visual_provider") instanceof String provider) || !Set.of("ocr", "none").contains(provider)) { throw bad("INVALID_ARGUMENT", "visual_provider must be ocr or none"); }
        result.put("max_text_calls", quota(result.get("max_text_calls"), 100));
        result.put("max_asr_submissions", quota(result.get("max_asr_submissions"), 10));
        return Map.copyOf(result).entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
    }

    private static int quota(Object value, int max) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0 || ((Number) value).longValue() > max) { throw bad("INVALID_ARGUMENT", "Model call limit is out of range"); }
        return ((Number) value).intValue();
    }
    private static Map<String, Object> readConfig(Map<String, Object> row) { return JSON.readValue(String.valueOf(row.get("config_json")), new TypeReference<Map<String, Object>>() {}); }
    private static String uuid(String value, String code) { try { String id = UUID.fromString(value).toString(); if (!id.equalsIgnoreCase(value)) { throw new IllegalArgumentException(); } return id; } catch (IllegalArgumentException | NullPointerException invalid) { throw bad(code, "Identifier must be a UUID"); } }
    private static String identifier(String value, String name) { if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) { throw bad("INVALID_ARGUMENT", "Invalid " + name); } return value; }
    private static void hash(String value, String code) { if (value == null || !value.matches("[0-9a-f]{64}")) { throw bad(code, "Expected a lowercase SHA256 hash"); } }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); } }
    private static Instant instant(ResultSet row, String field) throws SQLException { Timestamp value = row.getTimestamp(field); return value == null ? null : value.toInstant(); }
    private static Instant valueInstant(Object value) { if (value instanceof Timestamp timestamp) { return timestamp.toInstant(); } if (value instanceof java.time.LocalDateTime local) { return local.toInstant(java.time.ZoneOffset.UTC); } throw new IllegalStateException("Unsupported JDBC timestamp type"); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static Instant minimum(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
    private static JobException stale() { return conflict("STALE_ATTEMPT", "Execution credentials are no longer current"); }
    private static JobException bad(String code, String message) { return new JobException(HttpStatus.BAD_REQUEST, code, message); }
    private static JobException missing(String code, String message) { return new JobException(HttpStatus.NOT_FOUND, code, message); }
    private static JobException conflict(String code, String message) { return new JobException(HttpStatus.CONFLICT, code, message); }
}
