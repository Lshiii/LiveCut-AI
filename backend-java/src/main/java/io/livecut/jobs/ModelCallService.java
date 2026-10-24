package io.livecut.jobs;

import static io.livecut.jobs.ModelCallModels.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.livecut.video.VideoApiException;
import tools.jackson.databind.ObjectMapper;

/** Durable request-count reservations, not a currency estimate or provider bill. */
@Service
public class ModelCallService {
    private static final Set<String> STATES = Set.of("DISPATCHING", "SUBMITTED", "SUCCEEDED", "FAILED", "UNKNOWN_OUTCOME");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "RESERVED", Set.of("DISPATCHING", "FAILED"),
            "DISPATCHING", Set.of("SUBMITTED", "SUCCEEDED", "FAILED", "UNKNOWN_OUTCOME"),
            "SUBMITTED", Set.of("SUCCEEDED", "FAILED", "UNKNOWN_OUTCOME"),
            "UNKNOWN_OUTCOME", Set.of("SUBMITTED", "SUCCEEDED", "FAILED"),
            "SUCCEEDED", Set.of(), "FAILED", Set.of());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JobService jobs;
    private final ObjectMapper json;
    private final ArtifactVerifier artifactVerifier;

    private record CallRow(String requestId, String jobId, String stageId, String operationKey,
                           String inputHash, String modelId, String kind, String status,
                           int originAttempt, String originTokenHash, Integer dispatchAttempt,
                           String dispatchTokenHash, String providerTaskId, String usageJson,
                           String responseKey, String responseHash) {}

    private static final RowMapper<CallRow> ROW = (row, index) -> new CallRow(
            row.getString("request_id"), row.getString("job_id"), row.getString("stage_id"),
            row.getString("operation_key"), row.getString("input_hash"), row.getString("model_id"),
            row.getString("kind"), row.getString("status"), row.getInt("origin_attempt"),
            row.getString("origin_token_hash"), row.getObject("dispatch_attempt", Integer.class),
            row.getString("dispatch_token_hash"), row.getString("provider_task_id"), row.getString("usage_json"),
            row.getString("response_key"), row.getString("response_hash"));

    public ModelCallService(JdbcTemplate jdbc, PlatformTransactionManager manager, JobService jobs, ObjectMapper json,
                            ArtifactVerifier artifactVerifier) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.jobs = jobs;
        this.json = json;
        this.artifactVerifier = artifactVerifier;
    }

    public CallResponse reserve(ReserveRequest request) {
        validateReserve(request);
        return transactions.execute(transaction -> {
            // The same parent lock serializes every reservation and stage write.
            var active = jobs.lockActiveStage(request.stageId(), new JobModels.Credential(request.attempt(), request.runToken()));
            if (!"ANALYSIS".equals(active.stage().ownerType())) {
                throw error(HttpStatus.CONFLICT, "MODEL_KIND_CONFLICT", "Export stages do not permit provider requests");
            }
            String expectedStage = "TEXT".equals(request.kind()) ? "SEGMENT" : "ASR";
            if (!expectedStage.equals(active.stage().stageType())) {
                throw error(HttpStatus.CONFLICT, "MODEL_KIND_CONFLICT", "Model operation is not allowed in this stage");
            }
            String configJson = jdbc.queryForObject("SELECT config_json FROM analysis_job WHERE id = ?", String.class, active.jobId());
            String modelField = "TEXT".equals(request.kind()) ? "text_model" : "asr_model";
            if (!request.modelId().equals(json.readTree(configJson).path(modelField).asText())) {
                throw error(HttpStatus.CONFLICT, "MODEL_CONFIG_CONFLICT", "Model must match the immutable job configuration");
            }
            List<CallRow> byId = find("request_id = ?", request.requestId());
            if (!byId.isEmpty()) {
                checkSameInput(byId.getFirst(), request);
                return new CallResponse("EXISTING", view(byId.getFirst()));
            }
            List<CallRow> byOperation = find("stage_id = ? AND operation_key = ?", request.stageId(), request.operationKey());
            if (!byOperation.isEmpty()) {
                checkSameInput(byOperation.getFirst(), request);
                return new CallResponse("EXISTING", view(byOperation.getFirst()));
            }
            String used = "TEXT".equals(request.kind()) ? "text_calls_reserved" : "asr_calls_reserved";
            String limit = "TEXT".equals(request.kind()) ? "max_text_calls" : "max_asr_submissions";
            int changed = jdbc.update("UPDATE analysis_job SET " + used + " = " + used
                    + " + 1, state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND " + used + " < " + limit, active.jobId());
            if (changed != 1) {
                throw error(HttpStatus.CONFLICT, "MODEL_BUDGET_EXHAUSTED", "This job has no remaining model request reservations");
            }
            jdbc.update("INSERT INTO model_call (request_id, job_id, stage_id, operation_key, input_hash, model_id, kind, status, origin_attempt, origin_token_hash, usage_json) VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, '{}')",
                    request.requestId(), active.jobId(), request.stageId(), request.operationKey(), request.inputHash(),
                    request.modelId(), request.kind(), request.attempt(), tokenHash(request.runToken()));
            return new CallResponse("RESERVED", view(find("request_id = ?", request.requestId()).getFirst()));
        });
    }

    public CallResponse update(String requestId, UpdateRequest request) {
        validateUpdate(requestId, request);
        return transactions.execute(transaction -> {
            List<CallRow> initial = jdbc.query("SELECT * FROM model_call WHERE request_id = ?", ROW, requestId);
            if (initial.isEmpty()) { throw error(HttpStatus.NOT_FOUND, "MODEL_CALL_NOT_FOUND", "Model request was not found"); }
            CallRow hint = initial.getFirst();
            if (!hint.stageId().equals(request.stageId())) {
                throw error(HttpStatus.CONFLICT, "MODEL_CALL_INPUT_CONFLICT", "Model request belongs to a different stage");
            }
            String ownerType = jdbc.queryForObject("SELECT owner_type FROM job_stage WHERE id=?", String.class, hint.stageId());
            if (!"ANALYSIS".equals(ownerType)) { throw error(HttpStatus.CONFLICT, "MODEL_KIND_CONFLICT", "Export stages do not permit provider requests"); }
            // Accounting callbacks may arrive after a stage has been reclaimed.
            // Lock in the same job -> stage -> call order without granting stage ownership.
            jdbc.queryForObject("SELECT id FROM analysis_job WHERE id = ? FOR UPDATE", String.class, hint.jobId());
            Map<String, Object> stage = jdbc.queryForMap("SELECT status, attempt, run_token FROM job_stage WHERE id = ? FOR UPDATE", hint.stageId());
            CallRow current = find("request_id = ?", requestId).getFirst();
            boolean dispatchedOwner = current.dispatchAttempt() != null
                    && current.dispatchAttempt().equals(request.attempt())
                    && constantEquals(current.dispatchTokenHash(), tokenHash(request.runToken()));
            boolean currentOwner = "RUNNING".equals(stage.get("status"))
                    && ((Number) stage.get("attempt")).intValue() == request.attempt()
                    && constantEquals((String) stage.get("run_token"), request.runToken());
            if (!dispatchedOwner && !currentOwner) {
                throw error(HttpStatus.CONFLICT, "STALE_ATTEMPT", "Model accounting credentials are stale");
            }
            if ("DISPATCHING".equals(request.status())) {
                // An expired attempt may settle its own bill, never initiate a new call.
                jobs.lockActiveStage(request.stageId(), new JobModels.Credential(request.attempt(), request.runToken()));
                if ("DISPATCHING".equals(current.status())) {
                    // Only RESERVED -> DISPATCHING grants network permission.
                    // Metadata must never turn a duplicate acknowledgement into a new grant.
                    return new CallResponse("ALREADY_APPLIED", view(current));
                }
            }
            if (!current.status().equals(request.status())
                    && !TRANSITIONS.get(current.status()).contains(request.status())) {
                throw error(HttpStatus.CONFLICT, "MODEL_CALL_STATE_CONFLICT", "Model request cannot move to that state");
            }
            String providerId = mergeProviderId(current.providerTaskId(), request.providerTaskId());
            String responseKey = current.responseKey();
            String responseHash = current.responseHash();
            if (request.responseKey() != null) {
                String prefix = "jobs/" + current.jobId() + "/stages/" + current.stageId()
                        + "/attempts/" + request.attempt() + "/" + request.runToken() + "/provider-responses/";
                if (!request.responseKey().equals(prefix + requestId + ".json")) {
                    throw error(HttpStatus.BAD_REQUEST, "INVALID_RESPONSE_REFERENCE", "Provider response must belong to its current attempt directory");
                }
                if (responseKey != null && (!responseKey.equals(request.responseKey()) || !responseHash.equals(request.responseHash()))) {
                    throw error(HttpStatus.CONFLICT, "MODEL_RESPONSE_CONFLICT", "Recorded provider response is immutable");
                }
                artifactVerifier.verifyProviderResponse(request.responseKey(), request.responseHash(),
                        prefix.substring(0, prefix.length() - "provider-responses/".length()));
                responseKey = request.responseKey();
                responseHash = request.responseHash();
            }
            if ("SUBMITTED".equals(request.status()) && (!"ASR".equals(current.kind()) || providerId == null)) {
                throw error(HttpStatus.BAD_REQUEST, "INVALID_MODEL_CALL", "ASR submission requires its provider task ID");
            }
            if ("ASR".equals(current.kind()) && "SUCCEEDED".equals(request.status()) && providerId == null) {
                throw error(HttpStatus.BAD_REQUEST, "INVALID_MODEL_CALL", "Completed ASR requires its provider task ID");
            }
            Map<String, Long> before = readUsage(current.usageJson());
            Map<String, Long> usage = new TreeMap<>(before);
            for (var entry : validateUsage(request.usage()).entrySet()) {
                Long old = usage.putIfAbsent(entry.getKey(), entry.getValue());
                if (old != null && !old.equals(entry.getValue())) {
                    throw error(HttpStatus.CONFLICT, "MODEL_USAGE_CONFLICT", "Previously recorded usage cannot be overwritten");
                }
            }
            boolean noChange = current.status().equals(request.status())
                    && java.util.Objects.equals(providerId, current.providerTaskId()) && before.equals(usage)
                    && java.util.Objects.equals(responseKey, current.responseKey()) && java.util.Objects.equals(responseHash, current.responseHash());
            if (noChange) { return new CallResponse("ALREADY_APPLIED", view(current)); }
            Integer dispatchAttempt = current.dispatchAttempt();
            String dispatchToken = current.dispatchTokenHash();
            if ("DISPATCHING".equals(request.status()) && dispatchAttempt == null) {
                dispatchAttempt = request.attempt();
                dispatchToken = tokenHash(request.runToken());
            }
            jdbc.update("UPDATE model_call SET status = ?, provider_task_id = ?, usage_json = ?, dispatch_attempt = ?, dispatch_token_hash = ?, response_key = ?, response_hash = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE request_id = ?",
                    request.status(), providerId, json.writeValueAsString(usage), dispatchAttempt, dispatchToken, responseKey, responseHash, requestId);
            return new CallResponse("UPDATED", view(find("request_id = ?", requestId).getFirst()));
        });
    }

    private List<CallRow> find(String condition, Object... values) {
        return jdbc.query("SELECT * FROM model_call WHERE " + condition + " FOR UPDATE", ROW, values);
    }

    private void checkSameInput(CallRow row, ReserveRequest request) {
        if (!row.stageId().equals(request.stageId()) || !row.operationKey().equals(request.operationKey())
                || !row.inputHash().equals(request.inputHash()) || !row.modelId().equals(request.modelId()) || !row.kind().equals(request.kind())) {
            throw error(HttpStatus.CONFLICT, "MODEL_CALL_INPUT_CONFLICT", "Model request identity is bound to different input");
        }
    }

    private CallView view(CallRow row) {
        return new CallView(row.requestId(), row.stageId(), row.operationKey(), row.inputHash(), row.modelId(),
                row.kind(), row.status(), row.providerTaskId(), Map.copyOf(readUsage(row.usageJson())), row.responseKey(), row.responseHash());
    }

    private Map<String, Long> readUsage(String value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(value, Map.class);
        return validateUsage(parsed);
    }

    private static void validateReserve(ReserveRequest request) {
        if (request == null) { throw invalid(); }
        credential(request.stageId(), request.attempt(), request.runToken());
        if (!matches(request.requestId(), "[A-Za-z0-9_-]{1,128}")
                || !matches(request.operationKey(), "[A-Za-z0-9][A-Za-z0-9:._/-]{0,199}")
                || !matches(request.inputHash(), "[0-9a-f]{64}")
                || !matches(request.modelId(), "[A-Za-z0-9._:/-]{1,128}")
                || !Set.of("TEXT", "ASR").contains(request.kind() == null ? "" : request.kind())) { throw invalid(); }
    }

    private static void validateUpdate(String requestId, UpdateRequest request) {
        if (request == null || !matches(requestId, "[A-Za-z0-9_-]{1,128}")) { throw invalid(); }
        credential(request.stageId(), request.attempt(), request.runToken());
        if (!STATES.contains(request.status() == null ? "" : request.status())
                || (request.providerTaskId() != null && !matches(request.providerTaskId(), "[A-Za-z0-9_-]{1,256}"))) { throw invalid(); }
        if ((request.responseKey() == null) != (request.responseHash() == null)
                || (request.responseHash() != null && !matches(request.responseHash(), "[0-9a-f]{64}"))) { throw invalid(); }
        if ("DISPATCHING".equals(request.status()) && (request.providerTaskId() != null
                || request.responseKey() != null || (request.usage() != null && !request.usage().isEmpty()))) { throw invalid(); }
        validateUsage(request.usage());
    }

    private static void credential(String stageId, Integer attempt, String runToken) {
        try {
            if (!UUID.fromString(stageId).toString().equals(stageId) || attempt == null || attempt < 1
                    || !matches(runToken, "[A-Za-z0-9_-]{16,64}")) { throw invalid(); }
        } catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
    }

    private static Map<String, Long> validateUsage(Map<String, Object> input) {
        Map<String, Long> result = new TreeMap<>();
        if (input == null) { return result; }
        if (input.size() > 32) { throw invalid(); }
        for (var entry : input.entrySet()) {
            Object value = entry.getValue();
            if (!matches(entry.getKey(), "[a-z][a-z0-9_]{0,63}")
                    || !(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger)) { throw invalid(); }
            BigInteger number = new BigInteger(value.toString());
            if (number.signum() < 0 || number.bitLength() > 63) { throw invalid(); }
            result.put(entry.getKey(), number.longValueExact());
        }
        return result;
    }

    private static String mergeProviderId(String old, String supplied) {
        if (old != null && supplied != null && !old.equals(supplied)) {
            throw error(HttpStatus.CONFLICT, "MODEL_TASK_CONFLICT", "Provider task ID cannot be replaced");
        }
        return old == null ? supplied : old;
    }

    private static String tokenHash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean matches(String value, String regex) { return value != null && value.matches(regex); }
    private static VideoApiException invalid() { return error(HttpStatus.BAD_REQUEST, "INVALID_MODEL_CALL", "Model call fields or usage are invalid"); }
    private static VideoApiException error(HttpStatus status, String code, String message) { return new VideoApiException(status, code, message); }
}
