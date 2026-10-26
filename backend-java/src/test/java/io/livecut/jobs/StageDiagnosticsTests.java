package io.livecut.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.livecut.exports.ExportModels;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StageDiagnosticsTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void boundsAcceptIntegersAndRejectCoercionUnpairedWindowsAndInvalidCounts() {
        var valid = StageDiagnostics.create(node("{\"window_index\":9999,\"window_count\":10000,\"missing_core_id_count\":1000000}"), "INVALID_MODEL_OUTPUT", 1);
        assertEquals(9999, valid.get("window_index"));
        for (String input : List.of("[]", "true", "{\"window_index\":0}", "{\"window_count\":1}",
                "{\"window_index\":1,\"window_count\":1}", "{\"window_index\":0,\"window_count\":10001}",
                "{\"window_index\":-1,\"window_count\":2}", "{\"window_index\":0.0,\"window_count\":2}",
                "{\"window_index\":false,\"window_count\":2}", "{\"missing_core_id_count\":null}",
                "{\"missing_core_id_count\":\"1\"}", "{\"unknown_key_count\":1000001}",
                "{\"value_error_count\":2147483648}", "{\"extra_context_id_count\":-1}")) invalid(node(input), "INVALID_MODEL_OUTPUT");
    }

    @Test void reasonAndRepairEnumsAreBoundToTheActualFailureAndCountsRemainConsistent() {
        var value = StageDiagnostics.create(node("{\"reason_code\":\"ASSIGNMENT_KEYS_MISMATCH\",\"non_core_key_count\":5,\"extra_context_id_count\":3,\"unknown_key_count\":2,\"format_repair\":\"FAILED_VALIDATION\"}"), "INVALID_MODEL_OUTPUT", 2);
        assertEquals("MODEL_OUTPUT", value.get("category"));
        assertEquals("CHECK_FORMAT_THEN_RETRY", value.get("suggestion_code"));
        assertEquals(2, value.get("attempt"));
        invalid(node("{\"reason_code\":\"ASSIGNMENT_KEYS_MISMATCH\"}"), "MODEL_BUDGET_EXHAUSTED");
        invalid(node("{\"format_repair\":\"FAILED_VALIDATION\"}"), "BUDGET_EXHAUSTED");
        invalid(node("{\"format_repair\":\"RETRY_FOREVER\"}"), "INVALID_MODEL_OUTPUT");
        invalid(node("{\"non_core_key_count\":1,\"extra_context_id_count\":1,\"unknown_key_count\":1}"), "INVALID_MODEL_OUTPUT");
    }

    @Test void sensitiveOrServerOwnedFieldsCanNeverBecomePublicDiagnostics() {
        for (String key : List.of("message", "text", "raw_model_text", "missing_core_ids", "extra_context_ids", "url",
                "path", "run_token", "response_key", "schema_version", "attempt", "error_code", "category", "suggestion_code")) {
            invalid(JSON.createObjectNode().put(key, "private-value"), "INVALID_MODEL_OUTPUT");
        }
        invalid(JSON.createObjectNode().put("reason_code", "private-value".repeat(500)), "INVALID_MODEL_OUTPUT");
    }

    @Test void zeroBudgetUnknownOutcomeAndOcrFailuresDoNotSuggestBlindPaidRetry() {
        assertSuggestion("BUDGET_EXHAUSTED", "NEW_JOB_WITH_BUDGET");
        assertSuggestion("MODEL_BUDGET_EXHAUSTED", "NEW_JOB_WITH_BUDGET");
        assertSuggestion("UNKNOWN_OUTCOME", "RECONCILE_MODEL_CALL");
        assertSuggestion("MODEL_CALL_REQUIRES_RECOVERY", "RECONCILE_MODEL_CALL");
        assertSuggestion("CALL_STATE_UNCERTAIN", "RECONCILE_MODEL_CALL");
        assertSuggestion("MODEL_RESPONSE_ALREADY_EXISTS", "RECONCILE_MODEL_CALL");
        assertSuggestion("MODEL_DISPATCH_NOT_GRANTED", "RECONCILE_MODEL_CALL");
        assertSuggestion("OCR_CACHE_BUSY", "WAIT_FOR_CACHE_OWNER");
        assertSuggestion("OCR_CACHE_CAPACITY_EXCEEDED", "CHECK_CACHE_CAPACITY");
        assertSuggestion("OCR_CACHE_UNSAFE_PATH", "CHECK_STORAGE_SAFETY");
        assertSuggestion("OCR_CACHE_CORRUPT", "INSPECT_CACHE_INTEGRITY");
        assertSuggestion("OCR_CACHE_OUTPUT_CONFLICT", "INSPECT_CACHE_INTEGRITY");
        assertSuggestion("OCR_CACHE_RUNTIME_NOT_READY", "CONFIGURE_OCR_RUNTIME");
        assertSuggestion("OCR_CACHE_STORAGE_ERROR", "CHECK_WORKER_STORAGE");
        var windows = StageDiagnostics.create(node("{\"reason_code\":\"WINDOW_LIMIT_EXCEEDED\"}"), "BUDGET_EXHAUSTED", 1);
        assertEquals("CHECK_WINDOW_CONFIGURATION", windows.get("suggestion_code"));
    }

    @Test void codeValidationMatchesTheExistingFailProtocolIncludingLegacyLeadingDigitsAndUnderscores() {
        for (String code : List.of("0_LEGACY", "_LEGACY", "_", "A".repeat(64))) {
            assertEquals(code, StageDiagnostics.create(null, code, 0).get("error_code"));
        }
        for (String code : List.of("", "lowercase", "A-B", "A".repeat(65))) invalid(null, code);
    }

    @Test void persistedSummariesAreBoundedValidatedAndBoundToTheirAttemptRatherThanBlindlyEchoed() {
        var value = StageDiagnostics.create(node("{\"window_index\":8,\"window_count\":10,\"extra_context_id_count\":3}"), "INVALID_MODEL_OUTPUT", 2);
        String stored = StageDiagnostics.json(value);
        assertEquals(value, StageDiagnostics.read(stored, "INVALID_MODEL_OUTPUT", 2));
        assertThrows(UnsupportedOperationException.class, () -> value.put("message", "unsafe"));
        assertNull(StageDiagnostics.read(null, "INVALID_MODEL_OUTPUT", 2));
        assertNull(StageDiagnostics.read(stored, "INVALID_MODEL_OUTPUT", 3));
        assertNull(StageDiagnostics.read(stored, "UNKNOWN_OUTCOME", 2));
        for (String corrupt : List.of("[]", "{}", "{", stored + "{}", stored.replace("\"attempt\":2", "\"attempt\":2,\"attempt\":2"),
                stored.replace("MODEL_OUTPUT", "UNTRUSTED"), stored.substring(0, stored.length() - 1) + ",\"message\":\"secret\"}", " ".repeat(4097) + stored)) {
            assertNull(StageDiagnostics.read(corrupt, "INVALID_MODEL_OUTPUT", 2), corrupt);
        }
    }

    @Test void addingNullableDiagnosticsPreservesLegacyRecordBytesAndConstructors() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        var legacy = new LegacyStage("stage", "SEGMENT", "0", "FAILED", 1, null, null, now, "hash", null, null, null, "INVALID_MODEL_OUTPUT");
        var current = new JobModels.StageSnapshot("stage", "SEGMENT", "0", "FAILED", 1, null, null, now, "hash", null, null, null, "INVALID_MODEL_OUTPUT");
        assertArrayEquals(JSON.writeValueAsBytes(legacy), JSON.writeValueAsBytes(current));
        var legacyClip = new LegacyClip("clip", "segment", "product", "商品", 1, 2, "stage", "FAILED", 1, "BAD", null, null, null);
        var clip = new ExportModels.Clip("clip", "segment", "product", "商品", 1, 2, "stage", "FAILED", 1, "BAD", null, null, null);
        assertArrayEquals(JSON.writeValueAsBytes(legacyClip), JSON.writeValueAsBytes(clip));
        var fail = new JobModels.FailRequest(1, "private-token", "BAD", false);
        assertFalse(JSON.writeValueAsString(fail).contains("diagnostic"));
    }

    private static JsonNode node(String json) { return JSON.readTree(json); }
    private static void invalid(JsonNode input, String code) {
        assertEquals("INVALID_DIAGNOSTIC", assertThrows(JobException.class, () -> StageDiagnostics.create(input, code, 1)).getCode());
    }
    private static void assertSuggestion(String code, String suggestion) {
        assertEquals(suggestion, StageDiagnostics.create(null, code, 1).get("suggestion_code"));
    }
    private record LegacyStage(String id, @JsonProperty("stage_type") String stageType,
                               @JsonProperty("shard_key") String shardKey, String status, int attempt,
                               @JsonProperty("lease_until") Instant leaseUntil, @JsonProperty("hard_deadline_at") Instant hardDeadlineAt,
                               @JsonProperty("next_run_at") Instant nextRunAt, @JsonProperty("input_hash") String inputHash,
                               @JsonProperty("checkpoint_key") String checkpointKey, @JsonProperty("manifest_key") String manifestKey,
                               @JsonProperty("manifest_hash") String manifestHash, @JsonProperty("error_code") String errorCode) {}
    private record LegacyClip(String id, @JsonProperty("segment_id") String segmentId, @JsonProperty("product_id") String productId,
                              @JsonProperty("product_name") String productName, @JsonProperty("start_ms") long startMs,
                              @JsonProperty("end_ms") long endMs, @JsonProperty("stage_id") String stageId, String status, int attempt,
                              @JsonProperty("error_code") String errorCode, @JsonProperty("size_bytes") Long sizeBytes,
                              @JsonProperty("content_url") String contentUrl, @JsonProperty("download_url") String downloadUrl) {}
}
