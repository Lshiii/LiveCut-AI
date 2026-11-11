package io.livecut.agent;

import java.util.Map;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;

/** Explicit persisted mode; legacy turns never acquire new protocol fields. */
public final class AgentEvidence {
    private AgentEvidence() {}
    public static final String STAGE="AGENT_EVIDENCE",PROMPT="video-agent-evidence-v1";
    public static boolean structured(Map<String,Object> owner){
        Object raw=owner.get("evidence_mode");
        if(raw==null||"none".equals(raw))return false;
        if(!"structured".equals(raw)||!"frames".equals(owner.get("visual_mode")))
            throw new JobException(HttpStatus.CONFLICT,"AGENT_SUBMISSION_INVALID","Stored evidence mode no longer matches its immutable visual input");
        return true;
    }
    public static String publicMode(Map<String,Object> owner){return structured(owner)?"structured":null;}
}
