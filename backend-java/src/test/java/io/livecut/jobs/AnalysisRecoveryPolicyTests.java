package io.livecut.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.livecut.jobs.JobModels.StageRow;

/** Policies distinguish exhausted logical text rounds from recoverable cached work. */
class AnalysisRecoveryPolicyTests {
    private static final String JOB = "11111111-1111-4111-8111-111111111111";
    private static final String TOKEN = "test-recovery-token-123456";
    private static final Map<String, Object> TOPICS = Map.of("analysis_mode", "topics", "asr_model", "source-asr-v1");

    @Test void exhaustedTextRoundsNeverAdvertiseAStageRetry() {
        for (String error : List.of("TEXT_RETRIES_EXHAUSTED", "TEXT_RETRY_DEADLINE_EXCEEDED")) {
            assertEquals("TEXT_RECOVERY_EXHAUSTED", AnalysisRecoveryPolicy.retryBlockReason("FAILED", List.of(stage("SEGMENT", "0", "FAILED", 1, error)), 3));
            assertEquals("TEXT_RECOVERY_EXHAUSTED", AnalysisRecoveryPolicy.retryBlockReason("FAILED", List.of(stage("SEGMENT", "0", "FAILED", 3, error)), 3));
        }
    }

    @Test void budgetAndUnknownOutcomeFailuresKeepTheirCachedRecoveryPath() {
        for (String error : List.of("MODEL_BUDGET_EXHAUSTED", "BUDGET_EXHAUSTED", "UNKNOWN_OUTCOME", "MODEL_RESPONSE_PERSISTENCE_FAILED")) {
            assertNull(AnalysisRecoveryPolicy.retryBlockReason("FAILED", List.of(stage("SEGMENT", "0", "FAILED", 1, error)), 3));
        }
        assertEquals("ATTEMPTS_EXHAUSTED", AnalysisRecoveryPolicy.retryBlockReason("FAILED", List.of(stage("SEGMENT", "0", "FAILED", 3, "UNKNOWN_OUTCOME")), 3));
        assertEquals("JOB_NOT_RETRYABLE", AnalysisRecoveryPolicy.retryBlockReason("CANCELLED", List.of(stage("SEGMENT", "0", "FAILED", 1, "UNKNOWN_OUTCOME")), 3));
    }

    @Test void preparationReuseRequiresTerminalTopicsAndExactlyOnePublishedShardPerKind() {
        var media = stage("MEDIA", "0", "SUCCEEDED", 1, null);
        var asr = stage("ASR", "0", "SUCCEEDED", 1, null);
        for (String status : List.of("FAILED", "CANCELLED", "REVIEW_READY")) assertNull(AnalysisRecoveryPolicy.resumeBlockReason(status, TOPICS, List.of(media, asr)));
        assertEquals("JOB_NOT_RESUMABLE", AnalysisRecoveryPolicy.resumeBlockReason("RUNNING", TOPICS, List.of(media, asr)));
        assertEquals("RESUME_REQUIRES_TOPICS", AnalysisRecoveryPolicy.resumeBlockReason("FAILED", Map.of("asr_model", "x"), List.of(media, asr)));
        assertEquals("PREPARATION_NOT_READY", AnalysisRecoveryPolicy.resumeBlockReason("FAILED", TOPICS, List.of(media)));
        assertEquals("PREPARATION_NOT_READY", AnalysisRecoveryPolicy.resumeBlockReason("FAILED", TOPICS, List.of(media, asr, stage("ASR", "1", "SUCCEEDED", 1, null))));
        assertEquals("PREPARATION_NOT_READY", AnalysisRecoveryPolicy.resumeBlockReason("FAILED", TOPICS, List.of(media, stage("ASR", "1", "SUCCEEDED", 1, null))));
        assertEquals("PREPARATION_NOT_READY", AnalysisRecoveryPolicy.resumeBlockReason("FAILED", TOPICS, List.of(media, stage("ASR", "0", "FAILED", 1, null))));
        assertEquals("PREPARATION_SOURCE_INVALID", AnalysisRecoveryPolicy.resumeBlockReason("FAILED", Map.of("analysis_mode", "topics", "asr_model", "../bad"), List.of(media, asr)));
        assertEquals("PREPARATION_SOURCE_INVALID", AnalysisRecoveryPolicy.resumeBlockReason("FAILED", TOPICS, List.of(media, stage("ASR", "0", "SUCCEEDED", 0, null))));
    }

    private static StageRow stage(String type, String shard, String status, int attempt, String error) {
        String id = "MEDIA".equals(type) ? "22222222-2222-4222-8222-222222222222" : "33333333-3333-4333-8333-333333333333";
        return new StageRow(id, JOB, type, shard, status, attempt, TOKEN, null, null, null, null, "a".repeat(64), null, null,
                "jobs/" + JOB + "/stages/" + id + "/attempts/" + attempt + "/" + TOKEN + "/manifest.json", "b".repeat(64), error, "ANALYSIS", JOB);
    }
}
