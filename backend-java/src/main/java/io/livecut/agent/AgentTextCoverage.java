package io.livecut.agent;

import java.util.Map;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;

/** Frozen text execution policy; old submissions keep their original input and prompt. */
public final class AgentTextCoverage {
    private AgentTextCoverage() {}
    public static final String POLICY="coverage_v1", PROMPT="video-agent-coverage-v1";
    public static boolean enabled(Map<String,Object> owner){
        Object policy=owner.get("qa_policy");
        if(policy==null||"none".equals(policy))return false;
        if(!POLICY.equals(policy)||!none(owner.get("visual_mode"))||!none(owner.get("evidence_mode"))
                ||!none(owner.get("evidence_policy"))||owner.get("focus_timestamp_ms")!=null
                ||owner.get("replay_source_turn_id")!=null||owner.get("screen_source_json")!=null
                ||owner.get("kind")!=null&&!"VIDEO".equals(owner.get("kind")))
            throw new JobException(HttpStatus.CONFLICT,"AGENT_SUBMISSION_INVALID","Text coverage policy no longer matches its frozen text-only input");
        return true;
    }
    private static boolean none(Object value){return value==null||"none".equals(value);}
    public static String publicPolicy(Map<String,Object> owner){return enabled(owner)?POLICY:null;}
}
