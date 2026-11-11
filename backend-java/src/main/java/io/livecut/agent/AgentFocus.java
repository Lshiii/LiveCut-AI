package io.livecut.agent;

import java.util.Map;
import java.util.Set;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;

/** A persisted user locator is input identity, never an observed image. */
public final class AgentFocus {
    private AgentFocus() {}
    public static final String STAGE="AGENT_VISUAL_FOCUS";
    public static Long value(Map<String,Object> owner){
        Object raw=owner.get("focus_timestamp_ms");if(raw==null)return null;
        if(!(raw instanceof Long||raw instanceof Integer)||!"frames".equals(owner.get("visual_mode")))throw invalid();
        long value=((Number)raw).longValue();Object duration=owner.get("duration_ms");
        if(value<0||value>AgentVisualEvidence.SAFE_INTEGER||duration instanceof Number&&value>=((Number)duration).longValue())throw invalid();
        return value;
    }
    public static boolean visualStage(String stage){return Set.of("AGENT_VISUAL",STAGE,AgentEvidence.STAGE).contains(stage);}
    public static String stage(Map<String,Object> owner){Long focus=value(owner);return AgentEvidence.structured(owner)?AgentEvidence.STAGE:focus!=null?STAGE:"frames".equals(owner.get("visual_mode"))?"AGENT_VISUAL":"SEGMENT";}
    private static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"AGENT_SUBMISSION_INVALID","Stored focus no longer matches its immutable visual input");}
}
