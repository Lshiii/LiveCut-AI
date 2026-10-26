package io.livecut.jobs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A bounded public summary, never exception messages, provider text or artifact references. */
public final class StageDiagnostics {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> INPUT_FIELDS = Set.of("reason_code", "window_index", "window_count",
            "missing_core_id_count", "extra_context_id_count", "unknown_key_count", "non_core_key_count",
            "value_error_count", "format_repair");
    private static final Set<String> REPAIRS = Set.of("NOT_ATTEMPTED", "FAILED_VALIDATION", "INTERRUPTED");
    private static final Set<String> MODEL_REASONS = Set.of("ASSIGNMENT_KEYS_MISMATCH",
            "CLASSIFICATION_VALUES_INVALID", "CLASSIFICATION_SCHEMA_INVALID");
    private StageDiagnostics() {}

    private record Policy(String category, String reason, String suggestion) {}

    /** Called only after the current execution has been fenced by its database lease. */
    public static Map<String, Object> create(JsonNode input, String code, int attempt) {
        if (code == null || !code.matches("[A-Z0-9_]{1,64}") || attempt < 0) throw invalid();
        Map<String, Object> context = context(input, code);
        Policy policy = policy(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1);
        result.put("attempt", attempt);
        result.put("error_code", code);
        result.put("category", policy.category());
        result.put("reason_code", context.getOrDefault("reason_code", policy.reason()));
        result.put("suggestion_code", "WINDOW_LIMIT_EXCEEDED".equals(result.get("reason_code"))
                ? "CHECK_WINDOW_CONFIGURATION" : policy.suggestion());
        for (var entry : context.entrySet()) if (!"reason_code".equals(entry.getKey())) result.put(entry.getKey(), entry.getValue());
        if (JSON.writeValueAsBytes(result).length > 4096) throw invalid();
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> context(JsonNode input, String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input == null || input.isNull()) return result;
        if (!input.isObject() || input.size() > INPUT_FIELDS.size() || !INPUT_FIELDS.containsAll(input.propertyNames())) throw invalid();
        JsonNode reason = input.get("reason_code");
        if (reason != null) {
            if (!reason.isString() || !allowedReason(code, reason.stringValue())) throw invalid();
            result.put("reason_code", reason.stringValue());
        }
        JsonNode index = input.get("window_index"), total = input.get("window_count");
        if ((index == null) != (total == null)) throw invalid();
        if (index != null) {
            int window = number(index, 0, 9999), count = number(total, 1, 10000);
            if (window >= count) throw invalid();
            result.put("window_index", window); result.put("window_count", count);
        }
        for (String field : new String[] {"missing_core_id_count", "extra_context_id_count", "unknown_key_count", "non_core_key_count", "value_error_count"}) {
            JsonNode value = input.get(field);
            if (value != null) result.put(field, number(value, 0, 1000000));
        }
        if (result.containsKey("non_core_key_count") && result.containsKey("extra_context_id_count") && result.containsKey("unknown_key_count")
                && (Integer) result.get("non_core_key_count") != (Integer) result.get("extra_context_id_count") + (Integer) result.get("unknown_key_count")) throw invalid();
        JsonNode repair = input.get("format_repair");
        if (repair != null) {
            if (!repair.isString() || !REPAIRS.contains(repair.stringValue())
                    || ("FAILED_VALIDATION".equals(repair.stringValue()) && !"INVALID_MODEL_OUTPUT".equals(code))) throw invalid();
            result.put("format_repair", repair.stringValue());
        }
        if (JSON.writeValueAsBytes(input).length > 4096) throw invalid();
        return result;
    }

    private static int number(JsonNode value, int minimum, int maximum) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        int result = value.intValue();
        if (result < minimum || result > maximum) throw invalid();
        return result;
    }

    private static boolean allowedReason(String code, String reason) {
        return switch (code) {
            case "INVALID_MODEL_OUTPUT" -> MODEL_REASONS.contains(reason);
            case "INVALID_DISCOVERY_OUTPUT" -> "DISCOVERY_SCHEMA_INVALID".equals(reason);
            case "BUDGET_EXHAUSTED" -> Set.of("REQUEST_BUDGET_EXHAUSTED", "WINDOW_LIMIT_EXCEEDED").contains(reason);
            case "MODEL_BUDGET_EXHAUSTED" -> "REQUEST_BUDGET_EXHAUSTED".equals(reason);
            default -> false;
        };
    }

    public static String json(Map<String, Object> value) { return value == null ? null : JSON.writeValueAsString(value); }

    /** No read-time backfill. Invalid persisted summaries are omitted, never echoed or rewritten. */
    public static Map<String, Object> read(String stored, String code, int attempt) {
        if (stored == null) return null;
        try {
            if (stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) return null;
            JsonNode value = JSON.readTree(stored);
            if (!value.isObject()) return null;
            var context = JSON.createObjectNode();
            for (String field : INPUT_FIELDS) {
                JsonNode item = value.get(field);
                if (item != null && (!"reason_code".equals(field) || (item.isString() && allowedReason(code, item.stringValue())))) context.set(field, item);
            }
            Map<String, Object> expected = create(context, code, attempt);
            return JSON.valueToTree(expected).equals(value) ? expected : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static Policy policy(String code) {
        return switch (code) {
            case "INVALID_MODEL_OUTPUT" -> new Policy("MODEL_OUTPUT", "MODEL_OUTPUT_INVALID", "CHECK_FORMAT_THEN_RETRY");
            case "INVALID_DISCOVERY_OUTPUT" -> new Policy("MODEL_OUTPUT", "DISCOVERY_SCHEMA_INVALID", "CHECK_FORMAT_THEN_RETRY");
            case "BUDGET_EXHAUSTED", "MODEL_BUDGET_EXHAUSTED" -> new Policy("BUDGET", "REQUEST_BUDGET_EXHAUSTED", "NEW_JOB_WITH_BUDGET");
            case "UNKNOWN_OUTCOME", "CALL_STATE_UNCERTAIN", "MODEL_CALL_REQUIRES_RECOVERY", "INVALID_MODEL_RECOVERY_STATE",
                    "MODEL_RESPONSE_PERSISTENCE_FAILED", "MODEL_RESPONSE_ALREADY_EXISTS", "MODEL_DISPATCH_NOT_GRANTED" ->
                    new Policy("PROVIDER", "PROVIDER_OUTCOME_UNKNOWN", "RECONCILE_MODEL_CALL");
            case "AUTHENTICATION_FAILED", "PERMISSION_DENIED", "MISSING_API_KEY", "INVALID_PROVIDER_REQUEST", "MODEL_CONFIG_CONFLICT", "INVALID_CONFIGURATION" ->
                    new Policy("PROVIDER", "PROVIDER_CONFIGURATION_INVALID", "CHECK_PROVIDER_CONFIGURATION");
            case "POLL_TIMEOUT", "QUERY_TIMEOUT" -> new Policy("PROVIDER", "PROVIDER_QUERY_INTERRUPTED", "RESUME_PROVIDER_TASK");
            case "RATE_LIMITED", "PROVIDER_UNAVAILABLE", "PROVIDER_ERROR", "NETWORK_ERROR", "TASK_FAILED", "SUBTASK_FAILED", "INVALID_RESPONSE", "INVALID_RESULT" ->
                    new Policy("PROVIDER", "PROVIDER_REQUEST_FAILED", "CHECK_PROVIDER_AND_CALL_STATE");
            case "OCR_CACHE_BUSY" -> new Policy("OCR_CACHE", code, "WAIT_FOR_CACHE_OWNER");
            case "OCR_CACHE_CAPACITY_EXCEEDED" -> new Policy("OCR_CACHE", code, "CHECK_CACHE_CAPACITY");
            case "OCR_CACHE_UNSAFE_PATH" -> new Policy("OCR_CACHE", code, "CHECK_STORAGE_SAFETY");
            case "OCR_CACHE_CORRUPT", "OCR_CACHE_OUTPUT_CONFLICT" -> new Policy("OCR_CACHE", code, "INSPECT_CACHE_INTEGRITY");
            case "OCR_CACHE_INPUT_CHANGED" -> new Policy("OCR_CACHE", code, "VERIFY_SOURCE_AND_RUNTIME");
            case "OCR_CACHE_IDENTITY_REQUIRED", "OCR_CACHE_RUNTIME_NOT_READY", "OCR_CACHE_UNSUPPORTED_RUNTIME" ->
                    new Policy("OCR_CACHE", code, "CONFIGURE_OCR_RUNTIME");
            case "OCR_CACHE_STORAGE_ERROR" -> new Policy("OCR_CACHE", code, "CHECK_WORKER_STORAGE");
            case "LEASE_EXPIRED", "LEASE_LOST", "HARD_DEADLINE_EXCEEDED", "PROCESS_TIMEOUT", "FRAME_TIMEOUT" ->
                    new Policy("EXECUTION", "EXECUTION_TIMEOUT", "CHECK_WORKER_THEN_RETRY");
            case "PARENT_STAGE_FAILED" -> new Policy("DEPENDENCY", "UPSTREAM_STAGE_FAILED", "RESOLVE_UPSTREAM_FAILURE");
            case "ATTEMPTS_EXHAUSTED" -> new Policy("EXECUTION", "ATTEMPTS_EXHAUSTED", "CREATE_NEW_JOB");
            case "INVALID_MEDIA", "UNSUPPORTED_MEDIA", "UNSUPPORTED_TIMELINE", "INVALID_AUDIO", "INVALID_SOURCE", "INVALID_INPUT" ->
                    new Policy("INPUT", "INPUT_INVALID", "CHECK_SOURCE_AND_CONFIGURATION");
            case "ARTIFACT_HASH_MISMATCH", "ARTIFACT_INPUT_MISMATCH", "SOURCE_HASH_MISMATCH", "MISSING_ARTIFACT", "INVALID_CHECKPOINT",
                    "MODEL_RESPONSE_HASH_MISMATCH", "INVALID_MODEL_RESPONSE_RECORDING", "INVALID_RESULT_MANIFEST" ->
                    new Policy("INTEGRITY", "ARTIFACT_INVALID", "CHECK_ARTIFACT_INTEGRITY");
            case "STORAGE_ERROR", "OUTPUT_WRITE_FAILED", "MODEL_RESPONSE_STORAGE_UNAVAILABLE", "TOOL_NOT_FOUND" ->
                    new Policy("STORAGE", "STORAGE_UNAVAILABLE", "CHECK_WORKER_STORAGE");
            default -> new Policy("EXECUTION", "UNCLASSIFIED_FAILURE", "CHECK_EXECUTION_ENVIRONMENT");
        };
    }

    private static JobException invalid() { return new JobException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSTIC", "Diagnostic must contain only bounded, supported structured fields"); }
}
