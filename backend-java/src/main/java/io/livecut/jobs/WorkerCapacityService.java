package io.livecut.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.livecut.video.VideoApiException;
import tools.jackson.databind.ObjectMapper;
import static io.livecut.jobs.WorkerModels.*;

/** Instance liveness and conservative stage capacity; not a provider-side concurrency guarantee. */
@Service
public class WorkerCapacityService {
    static final List<String> TYPES = List.of("MEDIA", "ASR", "SEGMENT", "TIMELINE", "EXPORT_CLIP");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int timeout;
    private final boolean required;
    private final Map<String, Integer> limits;
    private record Instance(String id, String session, List<String> types, int slots, String target,
                            boolean accepting, Instant seen) {}
    private record Occupancy(String worker, String type) {}
    private static final RowMapper<Instance> ROW = (row, index) -> new Instance(row.getString("worker_id"),
        row.getString("session_id"), Arrays.asList(JSON.readValue(row.getString("stage_types"), String[].class)),
        row.getInt("slots"), row.getString("target_job_id"), row.getBoolean("accepting"), row.getTimestamp("last_seen_at").toInstant());

    public WorkerCapacityService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${livecut.workers.heartbeat-timeout-seconds:35}") int timeout,
            @Value("${livecut.workers.require-registration:true}") boolean required,
            @Value("${livecut.workers.ffmpeg-slots:1}") int ffmpeg,
            @Value("${livecut.workers.asr-slots:2}") int asr,
            @Value("${livecut.workers.text-slots:2}") int text) {
        if (timeout < 5 || timeout > 3600 || ffmpeg < 1 || ffmpeg > 1024 || asr < 1 || asr > 1024 || text < 1 || text > 1024) {
            throw new IllegalArgumentException("Invalid executor heartbeat timeout or capacity");
        }
        this.jdbc = jdbc; this.timeout = timeout; this.required = required;
        this.limits = Map.of("FFMPEG", ffmpeg, "ASR", asr, "TEXT", text);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Receipt register(Registration request) {
        if (request == null) { throw bad("INVALID_WORKER", "Worker registration is required"); }
        String id = identifier(request.workerId()), session = uuid(request.sessionId());
        String target = request.targetJobId() == null ? null : uuid(request.targetJobId());
        if (request.slots() == null || request.slots() < 1 || request.slots() > 32
                || request.stageTypes() == null || request.stageTypes().isEmpty()
                || request.stageTypes().size() > TYPES.size() || new HashSet<>(request.stageTypes()).size() != request.stageTypes().size()
                || request.stageTypes().stream().anyMatch(type -> type == null || !TYPES.contains(type))) {
            throw bad("INVALID_WORKER", "Worker capabilities and slots are invalid");
        }
        List<String> types = TYPES.stream().filter(request.stageTypes()::contains).toList();
        return transaction.execute(tx -> {
            lockCapacity();
            Instant now = now();
            Instance old = instance(id);
            if (old != null && old.session().equals(session)) {
                if (!online(old, now)) { throw expired(); }
                if (!old.types().equals(types) || old.slots() != request.slots() || !Objects.equals(old.target(), target)) {
                    throw conflict("WORKER_REGISTRATION_CONFLICT", "A session's capabilities are immutable");
                }
            } else if (old != null) {
                if (online(old, now)) { throw conflict("WORKER_SESSION_CONFLICT", "This worker identity already has an online session"); }
                if (jdbc.queryForObject("SELECT COUNT(*) FROM worker_session_history WHERE worker_id=? AND session_id=?", Integer.class, id, session) != 0) {
                    throw expired();
                }
            }
            jdbc.update("INSERT INTO worker_instance (worker_id,session_id,stage_types,slots,target_job_id,accepting,last_seen_at) VALUES (?,?,?,?,?,TRUE,?) ON DUPLICATE KEY UPDATE session_id=VALUES(session_id),stage_types=VALUES(stage_types),slots=VALUES(slots),target_job_id=VALUES(target_job_id),accepting=TRUE,last_seen_at=VALUES(last_seen_at)",
                id, session, JSON.writeValueAsString(types), request.slots(), target, Timestamp.from(now));
            jdbc.update("INSERT IGNORE INTO worker_session_history (worker_id,session_id) VALUES (?,?)", id, session);
            return receipt("REGISTERED", id, session, now);
        });
    }

    public Receipt heartbeat(Session request) { return sessionAction(request, false); }
    public Receipt unregister(Session request) { return sessionAction(request, true); }
    private Receipt sessionAction(Session request, boolean unregister) {
        if (request == null) { throw bad("INVALID_WORKER", "Worker session is required"); }
        String id = identifier(request.workerId()), session = uuid(request.sessionId());
        return transaction.execute(tx -> {
            lockCapacity();
            Instant now = now();
            Instance worker = instance(id);
            if (worker == null) { throw conflict("WORKER_NOT_REGISTERED", "Worker is not registered"); }
            if (!worker.session().equals(session)) { throw expired(); }
            if (unregister) {
                if (worker.accepting()) { jdbc.update("UPDATE worker_instance SET accepting=FALSE WHERE worker_id=?", id); }
                return receipt("UNREGISTERED", id, session, worker.seen());
            }
            if (!online(worker, now)) { throw expired(); }
            jdbc.update("UPDATE worker_instance SET last_seen_at=? WHERE worker_id=?", Timestamp.from(now), id);
            return receipt("ACCEPTED", id, session, now);
        });
    }

    /** Caller holds job -> stage locks; then acquire global -> instance in this order. */
    boolean admit(String workerId, String sessionId, String type, String jobId, boolean additionalSlot) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Capacity admission requires the stage transaction"); }
        // Explicit compatibility mode is used by old protocol fixtures. Production fails closed.
        if (sessionId == null && !required) { return true; }
        if (sessionId == null) { throw conflict("WORKER_NOT_REGISTERED", "Register the worker before claiming work"); }
        String session = uuid(sessionId);
        lockCapacity();
        Instant now = now();
        Instance worker = instance(workerId);
        if (worker == null) { throw conflict("WORKER_NOT_REGISTERED", "Worker is not registered"); }
        if (!worker.session().equals(session) || !online(worker, now)) { throw expired(); }
        if (!worker.types().contains(type)) { throw conflict("WORKER_CAPABILITY_MISMATCH", "Worker cannot execute this stage"); }
        if (worker.target() != null && !worker.target().equals(jobId)) { throw conflict("WORKER_TARGET_MISMATCH", "Worker is scoped to another analysis job"); }
        if (!additionalSlot) { return true; }
        List<Occupancy> occupancy = occupancy(now);
        if (busy(workerId, occupancy) >= worker.slots()) { return false; }
        String resource = resource(type);
        return resource == null || resourceBusy(resource, occupancy) < limits.get(resource);
    }

    public Snapshot snapshot(String requestedJob) {
        String job = requestedJob == null ? null : uuid(requestedJob);
        return transaction.execute(tx -> {
            lockCapacity();
            Instant now = now();
            List<Instance> all = jdbc.query("SELECT * FROM worker_instance ORDER BY worker_id", ROW);
            List<Occupancy> occupancy = occupancy(now);
            List<WorkerView> workers = all.stream().filter(w -> applies(w, job)).map(w -> new WorkerView(w.id(),
                online(w, now) ? "ONLINE" : "OFFLINE", w.types(), w.slots(), busy(w.id(), occupancy), w.seen(), w.target())).toList();
            int online = 0, total = 0, busy = 0, available = 0;
            for (WorkerView w : workers) {
                busy += w.busySlots();
                if (w.status().equals("ONLINE")) { online++; total += w.slots(); available += Math.max(0, w.slots() - w.busySlots()); }
            }
            List<StageView> stages = new ArrayList<>();
            for (String type : TYPES) {
                int stageOnline = 0, stageTotal = 0, stageBusy = 0, stageAvailable = 0;
                for (WorkerView w : workers) {
                    if (!w.stageTypes().contains(type)) { continue; }
                    stageBusy += w.busySlots();
                    if (w.status().equals("ONLINE")) {
                        stageOnline++; stageTotal += w.slots(); stageAvailable += Math.max(0, w.slots() - w.busySlots());
                    }
                }
                String resource = resource(type);
                int resourceRemaining = resource == null ? Integer.MAX_VALUE : Math.max(0, limits.get(resource) - resourceBusy(resource, occupancy));
                String reason = stageOnline == 0 ? "NO_EXECUTOR" : stageAvailable == 0 ? "BUSY" : resourceRemaining == 0 ? "RESOURCE_BUSY" : "AVAILABLE";
                stages.add(new StageView(type, stageOnline, stageTotal, stageBusy, Math.min(stageAvailable, resourceRemaining), reason));
            }
            List<ResourceView> resources = List.of("FFMPEG", "ASR", "TEXT").stream().map(name -> new ResourceView(name, limits.get(name), resourceBusy(name, occupancy))).toList();
            return new Snapshot(now, timeout, online, total, busy, available, workers, stages, resources);
        });
    }

    private boolean online(Instance worker, Instant now) { return worker.accepting() && worker.seen().plusSeconds(timeout).isAfter(now); }
    private static boolean applies(Instance worker, String job) { return job == null || worker.target() == null || job.equals(worker.target()); }
    private static int busy(String id, List<Occupancy> occupancy) { return (int) occupancy.stream().filter(o -> id.equals(o.worker())).count(); }
    private static int resourceBusy(String name, List<Occupancy> occupancy) { return (int) occupancy.stream().filter(o -> name.equals(resource(o.type()))).count(); }
    private static String resource(String type) { return switch (type) { case "MEDIA", "EXPORT_CLIP" -> "FFMPEG"; case "ASR" -> "ASR"; case "SEGMENT" -> "TEXT"; default -> null; }; }
    private List<Occupancy> occupancy(Instant now) {
        return jdbc.query("SELECT worker_id,stage_type FROM job_stage WHERE status='RUNNING' AND lease_until>? AND hard_deadline_at>?", (row, index) -> new Occupancy(row.getString("worker_id"), row.getString("stage_type")), Timestamp.from(now), Timestamp.from(now));
    }
    private Instance instance(String id) { return jdbc.query("SELECT * FROM worker_instance WHERE worker_id=? FOR UPDATE", ROW, id).stream().findFirst().orElse(null); }
    private void lockCapacity() { jdbc.queryForObject("SELECT id FROM executor_capacity_lock WHERE id=1 FOR UPDATE", Integer.class); }
    private Instant now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant(); }
    private Receipt receipt(String outcome, String id, String session, Instant seen) { return new Receipt(outcome, id, session, timeout, seen); }
    static String uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) { throw new IllegalArgumentException(); } return value; }
        catch (IllegalArgumentException invalid) { throw bad("INVALID_WORKER", "Worker session and target identifiers must be UUIDs"); }
    }
    private static String identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9_-]{1,100}")) { throw bad("INVALID_WORKER", "Worker identity is invalid"); } return value; }
    private static VideoApiException expired() { return conflict("WORKER_SESSION_EXPIRED", "Worker session is expired or has been replaced"); }
    private static VideoApiException bad(String code, String message) { return new VideoApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static VideoApiException conflict(String code, String message) { return new VideoApiException(HttpStatus.CONFLICT, code, message); }
}
