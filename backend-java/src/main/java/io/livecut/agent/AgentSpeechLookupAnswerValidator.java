package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.*;
import java.util.*;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Checks the bounded program receipt and source identity; BM25 ranking and semantic relevance are not proven. */
public final class AgentSpeechLookupAnswerValidator {
    private AgentSpeechLookupAnswerValidator() {}
    private static final Set<String> TOP=Set.of("schema_version","session_id","turn_id","job_id","video_id","source_hash","transcript_hash","question","answer","answer_scope","citations","tool_calls","provenance","suggested_clip_instruction","visual_evidence","visual_citations","evidence_mode","answer_blocks","evidence_policy","speech_lookup");
    private static final Set<String> RECEIPT=Set.of("tool_step","strategy","status","query_truncated","anchor_distance_ms","candidate_utterance_count","returned_utterance_ids");
    private record Nearest(int index,long distance) {}

    public static JsonNode validate(JsonNode answer,Map<String,Object> owner){
        if(!AgentSpeechLookup.enabled(owner))throw bad("Speech lookup requires its frozen policy");
        JsonNode source=JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances");
        ObjectNode projection=projection(answer,source);var legacyOwner=new HashMap<>(owner);legacyOwner.put("evidence_policy","none");
        AgentEvidenceAnswerValidator.validate(projection,legacyOwner);return answer;
    }
    /** The note service has already checked the complete owner source. This only rechecks public receipt facts. */
    public static ObjectNode noteProjection(JsonNode verifiedAnswer){return projection(verifiedAnswer,null);}

    private static ObjectNode projection(JsonNode answer,JsonNode source){
        boolean focused=answer!=null&&answer.has("focus_timestamp_ms");var top=new HashSet<>(TOP);if(focused)top.add("focus_timestamp_ms");fields(answer,top);
        equal(answer,"schema_version","1.4");equal(answer,"evidence_mode","structured");equal(answer,"evidence_policy",AgentSpeechLookup.POLICY);equal(answer.path("provenance"),"prompt_version",AgentSpeechLookup.PROMPT);
        if(!AgentSpeechLookup.validQuestion(text(answer,"question",2000)))throw bad("Invalid policy question");
        Long focus=focused?safeNumber(answer,"focus_timestamp_ms"):null;JsonNode receipt=answer.path("speech_lookup");fields(receipt,RECEIPT);
        int policyIndex=focused?1:0,step=focused?2:1;if(safeNumber(receipt,"tool_step")!=step)throw bad("Policy step changed");
        JsonNode calls=array(answer,"tool_calls",focused?2:1,4);JsonNode policy=calls.get(policyIndex);
        for(int index=0;index<calls.size();index++){
            JsonNode call=calls.get(index);fields(call,Set.of("step","tool_name","arguments","result_utterance_ids","result_frame_ids","origin"));
            equal(call,"origin",index==policyIndex?"EVIDENCE_POLICY":focused&&index==0?"USER_FOCUS":"MODEL");
        }
        if(safeNumber(policy,"step")!=step)throw bad("Policy tool step changed");
        List<String> returned=ids(receipt,"returned_utterance_ids",4);
        if(!returned.equals(ids(policy,"result_utterance_ids",4))||!array(policy,"result_frame_ids",0,0).isEmpty())throw bad("Policy receipt must match actual observed utterances");
        long candidate=safeNumber(receipt,"candidate_utterance_count");String strategy=text(receipt,"strategy",32),status=text(receipt,"status",32);
        if(!receipt.path("query_truncated").isBoolean())throw bad("Query truncation must be boolean");
        boolean truncated=receipt.path("query_truncated").booleanValue();JsonNode rawDistance=receipt.path("anchor_distance_ms");
        Long distance=rawDistance.isNull()?null:safeNumber(receipt,"anchor_distance_ms");if(!focused&&distance!=null)throw bad("No focus can acquire an anchor distance");
        if(candidate<returned.size()||candidate>4)throw bad("Invalid policy candidate count");
        String expectedStatus=candidate>returned.size()?"CONTEXT_LIMIT":returned.isEmpty()?"EMPTY_RETURN":"RETURNED";if(!expectedStatus.equals(status))throw bad("Policy status must describe the bounded observation");
        Nearest nearest=null;Map<String,Integer> order=new HashMap<>();
        if(source!=null){
            if(!source.isArray())throw bad("Invalid policy source");long totalText=0;for(int index=0;index<source.size();index++){JsonNode row=source.get(index);if(order.put(text(row,"utterance_id",128),index)!=null)throw bad("Repeated source ID");}
            int previous=-1;for(String id:returned){Integer index=order.get(id);if(index==null||index<=previous)throw bad("Policy originals must be source ordered");previous=index;totalText+=text(source.get(index),"text",100000).length();}
            if(totalText>10000)throw bad("Policy original text exceeds its input budget");
            if(focus!=null)nearest=nearest(source,focus);
            if(!Objects.equals(distance,nearest==null?null:nearest.distance()))throw bad("Policy anchor distance must match the frozen ASR");
            String expectedStrategy=nearest!=null&&nearest.distance()<=15000?"FOCUS_CONTEXT":"QUESTION_SEARCH";if(!strategy.equals(expectedStrategy))throw bad("Policy strategy must follow its frozen focus rule");
        }
        JsonNode args=policy.path("arguments");
        switch(strategy){
            case "FOCUS_CONTEXT"->{
                if(!focused||distance==null||distance>15000||truncated||candidate<1||candidate>3||returned.isEmpty()||returned.size()>3)throw bad("Invalid focused context receipt");
                equal(policy,"tool_name","read_context");fields(args,Set.of("utterance_id","before","after"));if(safeNumber(args,"before")!=1||safeNumber(args,"after")!=1)throw bad("Policy context must be one sentence on each side");text(args,"utterance_id",128);
                if(source!=null){
                    equal(args,"utterance_id",source.get(nearest.index()).path("utterance_id").asText());
                    List<Integer> candidates=new ArrayList<>();candidates.add(nearest.index());if(nearest.index()>0)candidates.add(nearest.index()-1);if(nearest.index()+1<source.size())candidates.add(nearest.index()+1);
                    List<Integer> admitted=new ArrayList<>();int units=0;for(int index:candidates){int size=text(source.get(index),"text",100000).length();if(size<=10000-units){admitted.add(index);units+=size;}}
                    admitted.sort(Integer::compareTo);List<String> expected=admitted.stream().map(index->source.get(index).path("utterance_id").asText()).toList();
                    if(candidate!=candidates.size()||!returned.equals(expected))throw bad("Policy context candidates and complete-text packing changed");
                }
            }
            case "QUESTION_SEARCH"->{
                if(distance!=null&&distance<=15000)throw bad("Nearby focus requires context first");equal(policy,"tool_name","search_transcript");fields(args,Set.of("query","top_k"));
                AgentSpeechLookup.Query query=AgentSpeechLookup.query(answer.path("question").asText());equal(args,"query",query.text());if(safeNumber(args,"top_k")!=4||truncated!=query.truncated())throw bad("Policy search must use its bounded question prefix");
            }
            default->throw bad("Unknown policy strategy");
        }
        if(!Set.of("none","bm25").contains(text(answer.path("provenance"),"retrieval_mode",40)))throw bad("Policy searches must remain local BM25");
        ObjectNode projection=(ObjectNode)answer.deepCopy();projection.remove(List.of("evidence_policy","speech_lookup"));projection.put("schema_version","1.3");((ObjectNode)projection.path("provenance")).put("prompt_version",AgentEvidence.PROMPT);
        for(JsonNode raw:projection.path("tool_calls")){ObjectNode call=(ObjectNode)raw;if(!focused)call.remove("origin");else if("EVIDENCE_POLICY".equals(call.path("origin").asText()))call.put("origin","MODEL");}
        return projection;
    }
    private static Nearest nearest(JsonNode source,long focus){Nearest best=null;for(int index=0;index<source.size();index++){JsonNode row=source.get(index);long start=safeNumber(row,"start_ms"),end=safeNumber(row,"end_ms");if(end<=start)throw bad("Invalid source chronology");long distance=focus<start?start-focus:focus<end?0:focus-end+1;if(best==null||distance<best.distance())best=new Nearest(index,distance);}return best;}
    private static List<String> ids(JsonNode node,String field,int maximum){List<String> result=new ArrayList<>();Set<String> unique=new HashSet<>();for(JsonNode id:array(node,field,0,maximum)){if(!id.isString()||!id.stringValue().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||!unique.add(id.stringValue()))throw bad("Invalid policy original IDs");result.add(id.stringValue());}return result;}
    private static long safeNumber(JsonNode node,String field){long value=number(node,field);if(value>AgentVisualEvidence.SAFE_INTEGER)throw bad("Unsafe policy integer");return value;}
    private static JsonNode array(JsonNode node,String field,int minimum,int maximum){JsonNode value=node.path(field);if(!value.isArray()||value.size()<minimum||value.size()>maximum)throw bad("Invalid "+field);return value;}
    private static void equal(JsonNode node,String field,String value){if(!node.path(field).isString()||!value.equals(node.path(field).stringValue()))throw bad("Policy identity mismatch: "+field);}
    private static JobException bad(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_AGENT_ANSWER",message);}
}
