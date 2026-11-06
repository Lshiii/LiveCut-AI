package io.livecut.clips;

import java.util.*;
import org.springframework.http.HttpStatus;
import io.livecut.jobs.JobException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Source identity and interval safety; this does not establish semantic correctness. */
public final class ClipPlanValidator {
    private ClipPlanValidator() {}
    public static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> PLAN=Set.of("schema_version","request_id","job_id","video_id","source_hash","transcript_hash","duration_ms","instruction","goals","constraints","outputs","provenance");
    public static JsonNode validate(JsonNode plan,Map<String,Object> request,boolean initial) {
        fields(plan,PLAN);
        equal(plan,"schema_version","1.0");
        for(String field:List.of("job_id","video_id","source_hash","transcript_hash","instruction"))equal(plan,field,String.valueOf(request.get(field)));
        equal(plan,"request_id",String.valueOf(request.get("id")));
        long duration=((Number)request.get("duration_ms")).longValue();
        if(number(plan,"duration_ms")!=duration)throw bad("Duration does not match the frozen source");
        JsonNode utterances=JSON.readTree(String.valueOf(request.get("source_json"))).path("utterances");
        Map<String,Integer> order=new HashMap<>();Map<String,JsonNode> byId=new HashMap<>();
        for(JsonNode u:utterances){String id=text(u,"utterance_id",128);order.put(id,order.size());byId.put(id,u);}
        Set<String> goals=new HashSet<>();
        for(JsonNode g:array(plan,"goals",1,6)){fields(g,Set.of("goal_id","description"));String goal=id(g,"goal_id");if(goal.length()>64||!goals.add(goal))throw bad("Duplicate or invalid goal");text(g,"description",300);}
        JsonNode constraints=plan.path("constraints");fields(constraints,Set.of("keep_explanation_complete","exclude"));
        if(!constraints.path("keep_explanation_complete").isBoolean())throw bad("Invalid completeness constraint");
        Set<String> exclusionRules=new HashSet<>();for(JsonNode e:array(constraints,"exclude",0,8))if(!e.isString()||e.stringValue().isBlank()||e.stringValue().length()>120||!exclusionRules.add(e.stringValue()))throw bad("Invalid exclusion");
        JsonNode provenance=plan.path("provenance");fields(provenance,Set.of("text_model_id","prompt_version","retrieval_mode","index_hash"));
        equal(provenance,"text_model_id",String.valueOf(request.get("text_model_id")));
        equal(provenance,"prompt_version","intent-clips-v1");String mode=text(provenance,"retrieval_mode",64);
        if(!Set.of("bm25","bm25_dense_rrf").contains(mode))throw bad("Invalid retrieval mode");hash(text(provenance,"index_hash",64));
        Set<String> outputIds=new HashSet<>(),seenGoals=new HashSet<>();
        for(JsonNode output:array(plan,"outputs",1,6)){
            fields(output,Set.of("output_id","goal_id","title","status","reason","enabled","parts","excluded","evidence_utterance_ids"));
            if(!outputIds.add(id(output,"output_id")))throw bad("Duplicate output");
            String goal=id(output,"goal_id");if(!goals.contains(goal)||!seenGoals.add(goal))throw bad("Outputs must cover each goal once");
            text(output,"title",120);String state=text(output,"status",20);text(output,"reason",1000);
            if(!Set.of("FOUND","NOT_FOUND","NEEDS_REVIEW").contains(state)||!output.path("enabled").isBoolean())throw bad("Invalid output status");
            if((initial&&output.path("enabled").booleanValue())||(!state.equals("FOUND")&&output.path("enabled").booleanValue()))throw bad("Unverified output cannot be selected");
            JsonNode parts=array(output,"parts",0,12);Set<String> included=new HashSet<>();int previous=-1;
            for(JsonNode part:parts){
                fields(part,Set.of("start_utterance_id","end_utterance_id","start_ms","end_ms"));
                String first=id(part,"start_utterance_id"),last=id(part,"end_utterance_id");
                if(!order.containsKey(first)||!order.containsKey(last))throw bad("Unknown source utterance");
                int start=order.get(first),end=order.get(last);
                if(start>end||start<=previous)throw bad("Output parts must be chronological and disjoint");previous=end;
                if(number(part,"start_ms")!=number(byId.get(first),"start_ms")||number(part,"end_ms")!=number(byId.get(last),"end_ms"))throw bad("Part time must equal source ASR boundaries");
                for(int i=start;i<=end;i++)included.add(utterances.get(i).path("utterance_id").asText());
            }
            Set<String> exclusions=new HashSet<>();
            for(JsonNode exclusion:array(output,"excluded",0,100)){
                fields(exclusion,Set.of("utterance_id","reason"));String uid=id(exclusion,"utterance_id");text(exclusion,"reason",300);
                if(!byId.containsKey(uid)||included.contains(uid)||!exclusions.add(uid))throw bad("Excluded source must exist outside retained parts");
            }
            Set<String> evidence=new HashSet<>();
            int previousEvidence=-1;for(JsonNode e:array(output,"evidence_utterance_ids",0,200)){if(!e.isString()||!included.contains(e.stringValue())||!evidence.add(e.stringValue())||order.get(e.stringValue())<=previousEvidence)throw bad("Evidence must be ordered unique retained source utterances");previousEvidence=order.get(e.stringValue());}
            if(state.equals("FOUND")&&(parts.isEmpty()||evidence.isEmpty()))throw bad("Found output requires source parts and evidence");
            if(state.equals("NOT_FOUND")&&(!parts.isEmpty()||!evidence.isEmpty()))throw bad("Missing goal cannot invent source parts");
        }
        if(!seenGoals.equals(goals))throw bad("Each goal requires an explicit result");
        return plan;
    }
    /** Ordinary review edits retain the identities and classifications published by the worker. */
    public static JsonNode validateReview(JsonNode plan,Map<String,Object> request,JsonNode automatic){
        validate(automatic,request,true);
        validate(plan,request,false);
        for(String field:PLAN)if(!field.equals("outputs")&&!plan.path(field).equals(automatic.path(field)))throw bad("Review metadata must match the automatic plan");
        Map<String,JsonNode> originals=new HashMap<>();
        for(JsonNode output:automatic.path("outputs"))originals.put(output.path("output_id").stringValue(),output);
        if(plan.path("outputs").size()!=originals.size())throw bad("Review must retain every automatic output");
        for(JsonNode output:plan.path("outputs")){
            JsonNode original=originals.get(output.path("output_id").stringValue());
            if(original==null||!output.path("goal_id").equals(original.path("goal_id"))||!output.path("status").equals(original.path("status")))throw bad("Output identity and status must match the automatic plan");
        }
        return plan;
    }
    public static void fields(JsonNode value,Set<String> expected){if(value==null||!value.isObject()||!value.propertyNames().equals(expected))throw bad("Unexpected or missing fields");}
    public static JsonNode array(JsonNode value,String field,int minimum,int maximum){JsonNode a=value.path(field);if(!a.isArray()||a.size()<minimum||a.size()>maximum)throw bad("Invalid "+field);return a;}
    public static long number(JsonNode value,String field){JsonNode n=value.path(field);if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw bad("Invalid "+field);return n.longValue();}
    public static String text(JsonNode value,String field,int maximum){String s=nullableText(value,field,maximum);if(s.isBlank())throw bad("Empty "+field);return s;}
    private static String nullableText(JsonNode value,String field,int maximum){JsonNode n=value.path(field);if(!n.isString()||n.stringValue().length()>maximum)throw bad("Invalid "+field);return n.stringValue();}
    public static String id(JsonNode value,String field){String s=text(value,field,128);if(!s.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw bad("Invalid identifier");return s;}
    public static void equal(JsonNode value,String field,String expected){if(!value.path(field).isString()||!expected.equals(value.path(field).stringValue()))throw bad("Source identity mismatch: "+field);}
    public static String hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw bad("Invalid hash");return value;}
    private static JobException bad(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_CLIP_PLAN",message);}
}
