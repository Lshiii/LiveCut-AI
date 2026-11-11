package io.livecut.agent;

import java.math.BigInteger;
import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** The opt-in profile and bounded literal index. OCR records are locators, never ASR. */
public final class AgentScreenSearch {
    private AgentScreenSearch() {}
    public static final String POLICY="screen_search_v1",PROMPT="video-agent-evidence-screen-v1",INDEX="screen-text-literal-v1";
    public static final Set<String> PUBLIC_FIELDS=Set.of("segment_stage_id","segment_manifest_hash","frames_hash","observations_hash","index_version","index_hash","duration_ms","sampling_policy","interval_ms","frame_count","text_frame_count","coverage_truncated");
    public static boolean enabled(Map<String,Object> owner){
        if(!POLICY.equals(owner.get("evidence_policy")))return false;
        if(!AgentEvidence.structured(owner)||AgentResponseReplayData.isReplay(owner))throw invalid();return true;
    }
    public static JsonNode context(Map<String,Object> owner){
        if(!enabled(owner)||owner.get("screen_source_json")==null)throw invalid();
        try{JsonNode context=AgentAnswerValidator.JSON.readTree(String.valueOf(owner.get("screen_source_json")));
            AgentAnswerValidator.fields(context,Set.of("schema_version","segment","analysis","ocr_config","screen_source"));
            if(AgentAnswerValidator.number(context,"schema_version")!=1)throw invalid();validatePublicSource(context.path("screen_source"));return context;
        }catch(JobException e){throw e;}catch(RuntimeException e){throw invalid();}
    }
    public static JsonNode publicSource(Map<String,Object> owner){return enabled(owner)?context(owner).path("screen_source").deepCopy():null;}
    public static void validatePublicSource(JsonNode source){
        AgentAnswerValidator.fields(source,PUBLIC_FIELDS);
        if(!source.path("segment_stage_id").isString()||!source.path("segment_stage_id").asText().matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw bad("Invalid screen stage identity");
        for(String key:List.of("segment_manifest_hash","frames_hash","observations_hash","index_hash"))if(!source.path(key).isString()||!source.path(key).asText().matches("[a-f0-9]{64}"))throw bad("Invalid screen source digest");
        if(!INDEX.equals(source.path("index_version").asText())||!Set.of("prefix","uniform").contains(source.path("sampling_policy").asText())||!source.path("coverage_truncated").isBoolean())throw bad("Invalid screen source profile");
        long count=safe(source,"frame_count"),text=safe(source,"text_frame_count"),duration=safe(source,"duration_ms"),interval=safe(source,"interval_ms");
        if(duration==0||interval==0||count<1||count>120||text>count)throw bad("Invalid sampled source bounds");long plan=1+(duration-1)/interval;
        if(count>plan||source.path("coverage_truncated").booleanValue()!=(source.path("sampling_policy").asText().equals("prefix")&&plan>count))throw bad("Invalid sampling coverage");
    }
    public static List<String> tokens(String query){
        if(query==null||query.isEmpty()||query.length()>120||!legalText(query))throw bad("Screen query must be valid nonempty text through 120 UTF-16 units");
        List<String> raw=new ArrayList<>();int start=0;
        for(int index=0;index<=query.length();index++)if(index==query.length()||space(query.charAt(index))){if(index>start)raw.add(fold(query.substring(start,index)));start=index+1;}
        if(raw.isEmpty()||raw.size()>8)throw bad("Screen queries accept at most eight words");return new ArrayList<>(new LinkedHashSet<>(raw));
    }
    public static boolean legalText(String text){for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c<32&&!space(c)||c>=127&&c<=159)return false;if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    public static boolean unicode(String text){for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    public static boolean hasText(String text){for(int i=0;i<text.length();i++)if(!space(text.charAt(i)))return true;return false;}
    public static List<JsonNode> matches(JsonNode rows,String query){
        var tokens=tokens(query);List<JsonNode> matches=new ArrayList<>();Set<String> positions=new HashSet<>();
        for(JsonNode row:rows){String text=fold(row.path("observed_text").asText());if(tokens.stream().allMatch(text::contains)&&positions.add(position(row)))matches.add(row);}
        return matches;
    }
    public record Lookup(int totalMatchCount,List<JsonNode> hits) {}
    public static Lookup lookup(JsonNode rows,JsonNode arguments){AgentAnswerValidator.fields(arguments,Set.of("query","top_k"));if(!arguments.path("query").isString()||!arguments.path("top_k").isIntegralNumber())throw bad("Invalid screen arguments");long k=safe(arguments,"top_k");if(k<1||k>3)throw bad("Invalid screen top_k");var matched=matches(rows,arguments.path("query").asText());return new Lookup(matched.size(),matched.subList(0,Math.min((int)k,matched.size())));}
    public static String indexHash(JsonNode rows,String sourceHash,JsonNode source){var payload=AgentAnswerValidator.JSON.createObjectNode().put("index_version",INDEX).put("source_hash",sourceHash).put("duration_ms",safe(source,"duration_ms")).put("segment_manifest_hash",source.path("segment_manifest_hash").asText()).put("frames_hash",source.path("frames_hash").asText()).put("observations_hash",source.path("observations_hash").asText());var hashed=payload.putArray("rows");for(JsonNode row:rows){var item=(tools.jackson.databind.node.ObjectNode)row.deepCopy();item.remove("observed_text");item.put("observed_text_sha256",ClipRequestService.sha(row.path("observed_text").asText()));hashed.add(item);}return hash(payload);}
    public static String position(JsonNode frame){String[] ratio=frame.path("source_time_base").asText().split("/");if(ratio.length!=2)throw bad("Invalid source PTS");BigInteger n=BigInteger.valueOf(safe(frame,"source_pts")).multiply(new BigInteger(ratio[0])),d=new BigInteger(ratio[1]);if(d.signum()<=0)throw bad("Invalid source PTS");BigInteger gcd=n.gcd(d);return n.divide(gcd)+"/"+d.divide(gcd);}
    public static long safe(JsonNode value,String key){long result=AgentAnswerValidator.number(value,key);if(result>AgentVisualEvidence.SAFE_INTEGER)throw bad("Unsafe screen integer");return result;}
    public static String canonical(JsonNode value){return ClipRequestService.canonicalJson(value);}
    public static String hash(JsonNode value){return ClipRequestService.sha(canonical(value));}
    public static boolean same(JsonNode a,JsonNode b){return canonical(a).equals(canonical(b));}
    private static String fold(String text){StringBuilder out=new StringBuilder(text.length());for(int i=0;i<text.length();i++){char c=text.charAt(i);out.append(c>='A'&&c<='Z'?(char)(c+32):c);}return out.toString();}
    private static boolean space(char c){return c>=9&&c<=13||c==32||c==0xA0||c==0x1680||c>=0x2000&&c<=0x200A||c==0x2028||c==0x2029||c==0x202F||c==0x205F||c==0x3000||c==0xFEFF;}
    public static JobException bad(String text){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_AGENT_ANSWER",text);}
    public static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"AGENT_SUBMISSION_INVALID","Stored screen policy no longer matches its frozen input");}
}
