package io.livecut.jobs;

import java.util.List;
import java.util.Map;
import java.util.Set;
import io.livecut.jobs.JobModels.StageRow;

/** Advisory metadata checks are cheap; creation must also reverify the pinned files. */
final class AnalysisRecoveryPolicy {
    private AnalysisRecoveryPolicy() {}

    static String retryBlockReason(String status, List<StageRow> stages, int maxAttempts) {
        List<StageRow> failed = stages.stream().filter(stage -> "FAILED".equals(stage.status())).toList();
        if (!"FAILED".equals(status) || failed.isEmpty()) return "JOB_NOT_RETRYABLE";
        if (failed.stream().anyMatch(stage -> Set.of("TEXT_RETRIES_EXHAUSTED", "TEXT_RETRY_DEADLINE_EXCEEDED").contains(stage.errorCode() == null ? "" : stage.errorCode()))) {
            return "TEXT_RECOVERY_EXHAUSTED";
        }
        return failed.stream().anyMatch(stage -> stage.attempt() >= maxAttempts) ? "ATTEMPTS_EXHAUSTED" : null;
    }

    static String resumeBlockReason(String status, Map<String, Object> config, List<StageRow> stages) {
        if (!"topics".equals(config.get("analysis_mode"))) return "RESUME_REQUIRES_TOPICS";
        if (!Set.of("FAILED", "CANCELLED", "REVIEW_READY").contains(status)) return "JOB_NOT_RESUMABLE";
        Object asr = config.get("asr_model");
        if (!(asr instanceof String model) || !model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) return "PREPARATION_SOURCE_INVALID";
        for (String type : List.of("MEDIA", "ASR")) {
            List<StageRow> matches = stages.stream().filter(stage -> type.equals(stage.stageType())).toList();
            if (matches.size() != 1 || !"0".equals(matches.getFirst().shardKey()) || !"SUCCEEDED".equals(matches.getFirst().status())) return "PREPARATION_NOT_READY";
            StageRow stage = matches.getFirst();
            if (!"ANALYSIS".equals(stage.ownerType()) || !stage.jobId().equals(stage.ownerId())
                    || stage.attempt() < 1 || stage.runToken() == null || !stage.runToken().matches("[A-Za-z0-9_-]{16,64}")
                    || stage.manifestHash() == null || !stage.manifestHash().matches("[0-9a-f]{64}")
                    || !("jobs/" + stage.jobId() + "/stages/" + stage.id() + "/attempts/" + stage.attempt() + "/" + stage.runToken() + "/manifest.json").equals(stage.manifestKey())) return "PREPARATION_SOURCE_INVALID";
        }
        return null;
    }
}
