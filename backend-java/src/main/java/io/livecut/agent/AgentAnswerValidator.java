package io.livecut.agent;

import java.util.*;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies provenance and exact source anchors; it cannot establish semantic truth. */
public final class AgentAnswerValidator {
    private AgentAnswerValidator() {}
    public static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> ANSWER=Set.of("schema_version","session_id","turn_id","job_id","video_id",
        "source_hash","transcript_hash","question","answer","answer_scope","citations","tool_calls","provenance","suggested_clip_instruction");
    public static JsonNode validate(JsonNode answer,Map<String,Object> owner){
        boolean visual="frames".equals(owner.get("visual_mode"));
        Long focus=AgentFocus.value(owner);boolean focused=focus!=null;
        Set<String> expected=new HashSet<>(ANSWER);if(visual)expected.addAll(Set.of("visual_evidence","visual_citations"));
        if(focused)expected.add("focus_timestamp_ms");
        fields(answer,expected);equal(answer,"schema_version",focused?"1.2":visual?"1.1":"1.0");
        if(focused&&number(answer,"focus_timestamp_ms")!=focus)throw bad("Focus must match the immutable user locator");
        for(String name:List.of("session_id","job_id","video_id","source_hash","transcript_hash","question"))equal(answer,name,String.valueOf(owner.get(name)));
        equal(answer,"turn_id",String.valueOf(owner.get("id")));text(answer,"answer",6000);
        String scope=text(answer,"answer_scope",20);if(!Set.of("VIDEO","GENERAL","INSUFFICIENT").contains(scope))throw bad("Unknown answer scope");
        JsonNode source=JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances");
        Map<String,JsonNode> utterances=new HashMap<>();Map<String,Integer> order=new HashMap<>();
        for(JsonNode row:source){String id=text(row,"utterance_id",128);order.put(id,order.size());utterances.put(id,row);}
        Set<String> observed=new HashSet<>();int expectedStep=1;boolean searched=false;
        for(JsonNode call:array(answer,"tool_calls",focused?1:0,4)){
            fields(call,focused?Set.of("step","tool_name","arguments","result_utterance_ids","result_frame_ids","origin"):visual?Set.of("step","tool_name","arguments","result_utterance_ids","result_frame_ids"):Set.of("step","tool_name","arguments","result_utterance_ids"));
            if(number(call,"step")!=expectedStep++)throw bad("Tool steps must be contiguous");
            String tool=text(call,"tool_name",40);JsonNode args=call.path("arguments");
            if(focused){boolean first=expectedStep==2;equal(call,"origin",first?"USER_FOCUS":"MODEL");if(first){
                if(!tool.equals("inspect_frames")||!args.path("timestamps_ms").isArray()||args.path("timestamps_ms").size()!=1
                        ||!args.path("timestamps_ms").get(0).isIntegralNumber()||!args.path("timestamps_ms").get(0).canConvertToLong()||args.path("timestamps_ms").get(0).longValue()!=focus
                        ||!call.path("result_frame_ids").isArray()||call.path("result_frame_ids").size()!=1||!call.path("result_frame_ids").get(0).asText().equals("f0001"))throw bad("The first user focus observation must contain its actual frame");
            }}
            switch(tool){
                case "search_transcript" -> {
                    searched=true;
                    fields(args,Set.of("query","top_k"));text(args,"query",300);range(args,"top_k",1,8);
                }
                case "read_context" -> {
                    fields(args,Set.of("utterance_id","before","after"));
                    if(!utterances.containsKey(text(args,"utterance_id",128)))throw bad("Unknown context source");
                    range(args,"before",0,3);range(args,"after",0,3);
                }
                case "list_topics" -> {
                    fields(args,Set.of("offset","limit"));range(args,"offset",0,20000);range(args,"limit",1,12);
                }
                case "inspect_frames", "inspect_clip" -> {if(!visual)throw bad("Unknown tool");}
                default -> throw bad("Unknown tool");
            }
            int previous=-1;Set<String> seen=new HashSet<>();
            for(JsonNode id:array(call,"result_utterance_ids",0,200)){
                if(!id.isString()||!order.containsKey(id.stringValue())||!seen.add(id.stringValue())||order.get(id.stringValue())<=previous)throw bad("Tool observations must be unique source-ordered utterances");
                previous=order.get(id.stringValue());observed.add(id.stringValue());
            }
        }
        Set<String> cited=new HashSet<>();int previous=-1;
        for(JsonNode citation:array(answer,"citations",scope.equals("VIDEO")&&!visual?1:0,24)){
            fields(citation,Set.of("utterance_id","start_ms","end_ms","text"));
            String id=text(citation,"utterance_id",128);JsonNode original=utterances.get(id);
            if(original==null||!observed.contains(id)||!cited.add(id)||order.get(id)<=previous)throw bad("Citations must come from this turn's observed source");
            previous=order.get(id);
            if(number(citation,"start_ms")!=number(original,"start_ms")||number(citation,"end_ms")!=number(original,"end_ms")
                    ||!text(citation,"text",100000).equals(original.path("text").asText()))throw bad("Citation must exactly match original ASR");
        }
        JsonNode provenance=answer.path("provenance");fields(provenance,Set.of("text_model_id","prompt_version","retrieval_mode"));
        equal(provenance,"text_model_id",String.valueOf(owner.get("text_model_id")));equal(provenance,"prompt_version",focused?"video-agent-visual-focus-v1":visual?"video-agent-visual-v1":"video-agent-v1");
        String mode=text(provenance,"retrieval_mode",40);
        if(!Set.of("none","bm25","bm25_dense_rrf").contains(mode)||searched==mode.equals("none"))throw bad("Retrieval mode must describe the actual tools");
        JsonNode suggested=answer.path("suggested_clip_instruction");
        if(!suggested.isNull()&&(!suggested.isString()||suggested.stringValue().isBlank()||suggested.stringValue().length()>2000))throw bad("Invalid clipping suggestion");
        if(visual)AgentVisualEvidence.validate(answer,((Number)owner.get("duration_ms")).longValue());
        return answer;
    }
    public static void fields(JsonNode node,Set<String> expected){if(node==null||!node.isObject()||!node.propertyNames().equals(expected))throw bad("Unexpected or missing fields");}
    public static String text(JsonNode node,String field,int maximum){JsonNode value=node.path(field);if(!value.isString()||value.stringValue().isBlank()||value.stringValue().length()>maximum)throw bad("Invalid "+field);return value.stringValue();}
    public static long number(JsonNode node,String field){JsonNode value=node.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw bad("Invalid "+field);return value.longValue();}
    private static void range(JsonNode node,String field,long minimum,long maximum){long value=number(node,field);if(value<minimum||value>maximum)throw bad("Invalid "+field);}
    private static void equal(JsonNode node,String field,String expected){if(!node.path(field).isString()||!node.path(field).stringValue().equals(expected))throw bad("Source identity mismatch");}
    private static JsonNode array(JsonNode node,String field,int minimum,int maximum){JsonNode value=node.path(field);if(!value.isArray()||value.size()<minimum||value.size()>maximum)throw bad("Invalid "+field);return value;}
    private static JobException bad(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_AGENT_ANSWER",message);}
}
