package io.livecut.agent;

import java.util.Map;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;

/** Immutable opt-in; legacy profiles never acquire a policy, prompt or fingerprint suffix. */
public final class AgentSpeechLookup {
    private AgentSpeechLookup() {}
    public static final String POLICY="speech_lookup_v1",PROMPT="video-agent-evidence-speech-v1";
    public static boolean enabled(Map<String,Object> owner){
        Object value=owner.get("evidence_policy");if(value==null||"none".equals(value))return false;
        if(!POLICY.equals(value)||!AgentEvidence.structured(owner))throw new JobException(HttpStatus.CONFLICT,"AGENT_SUBMISSION_INVALID","Stored speech lookup policy no longer matches its frozen mode");
        return true;
    }
    public static String publicPolicy(Map<String,Object> owner){return enabled(owner)?POLICY:null;}
    public static int requiredProtocol(Map<String,Object> owner){return AgentResponseReplayData.isReplay(owner)?3:enabled(owner)?2:1;}
    public record Query(String text,boolean truncated) {}
    public static Query query(String question){
        int start=0,end=question.length();while(start<end&&trimSpace(question.charAt(start)))start++;while(end>start&&trimSpace(question.charAt(end-1)))end--;
        boolean truncated=end-start>300;int prefix=Math.min(end,start+300);
        if(prefix<end&&prefix>start&&Character.isHighSurrogate(question.charAt(prefix-1))&&Character.isLowSurrogate(question.charAt(prefix)))prefix--;
        while(prefix>start&&trimSpace(question.charAt(prefix-1)))prefix--;
        return new Query(question.substring(start,prefix),truncated);
    }
    public static boolean validQuestion(String value){
        if(value==null||value.isEmpty()||value.length()>2000||query(value).text().isEmpty())return false;
        for(int index=0;index<value.length();index++){
            char c=value.charAt(index);if(c<32&&c!='\t'&&c!='\r'&&c!='\n'||c>=127&&c<=159)return false;
            if(Character.isHighSurrogate(c)){if(++index>=value.length()||!Character.isLowSurrogate(value.charAt(index)))return false;}else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    private static boolean trimSpace(char value){return value>=0x0009&&value<=0x000D||value==0x0020||value==0x00A0||value==0x1680||value>=0x2000&&value<=0x200A||value==0x2028||value==0x2029||value==0x202F||value==0x205F||value==0x3000||value==0xFEFF;}
}
