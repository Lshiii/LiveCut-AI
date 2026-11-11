package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Strict 1.5 receipts; private source verification remains a separate file-proof boundary. */
public final class AgentScreenSearchAnswerValidator {
    private AgentScreenSearchAnswerValidator() {}
    private static final Set<String> TOP=Set.of("schema_version","session_id","turn_id","job_id","video_id","source_hash","transcript_hash","question","answer","answer_scope","citations","tool_calls","provenance","suggested_clip_instruction","visual_evidence","visual_citations","evidence_mode","answer_blocks","evidence_policy","screen_source","screen_searches");
    private static final Set<String> CALL=Set.of("step","tool_name","arguments","result_utterance_ids","result_frame_ids","origin");
    private static final Set<String> RECEIPT=Set.of("tool_step","status","error_code","total_match_count","hits");
    private static final Set<String> HIT=Set.of("ocr_frame_id","requested_ms","ocr_timestamp_ms","source_pts","source_time_base","observed_text","frame_id");
    public static JsonNode validate(JsonNode answer,Map<String,Object> owner){
        if(!AgentScreenSearch.enabled(owner)||!AgentScreenSearch.same(AgentScreenSearch.publicSource(owner),answer.path("screen_source")))throw bad("Screen source differs from its frozen owner");
        ObjectNode projection=projection(answer);var legacy=new HashMap<>(owner);legacy.put("evidence_policy","none");legacy.remove("screen_source_json");AgentEvidenceAnswerValidator.validate(projection,legacy);return answer;
    }
    public static ObjectNode noteProjection(JsonNode answer){return projection(answer);}
    public static JsonNode validateFrozenAnswer(JsonNode answer,Map<String,Object> owner){var legacy=new HashMap<>(owner);legacy.put("evidence_policy","none");legacy.remove("screen_source_json");AgentEvidenceAnswerValidator.validate(projection(answer),legacy);return answer;}
    public static void verifySource(JsonNode answer,AgentScreenSource.Prepared source){
        if(!AgentScreenSearch.same(source.publicSource(),answer.path("screen_source")))throw AgentScreenSource.invalid();
        projection(answer);
        for(JsonNode receipt:answer.path("screen_searches")){
            JsonNode call=answer.path("tool_calls").get((int)number(receipt,"tool_step")-1);List<JsonNode> matches=AgentScreenSearch.matches(source.rows(),call.path("arguments").path("query").asText());
            long count=number(receipt,"total_match_count");String status=receipt.path("status").asText(),error=receipt.path("error_code").asText();
            if(count!=matches.size())throw AgentScreenSource.invalid();
            if(status.equals("OBSERVED"))for(int index=0;index<receipt.path("hits").size();index++){
                JsonNode hit=receipt.path("hits").get(index),row=matches.get(index);
                for(String field:List.of("ocr_frame_id","requested_ms","ocr_timestamp_ms","source_pts","source_time_base","observed_text"))if(!AgentScreenSearch.same(hit.path(field),row.path(field)))throw AgentScreenSource.invalid();
            }
        }
    }
    private static ObjectNode projection(JsonNode answer){
        boolean focus=answer!=null&&answer.has("focus_timestamp_ms");var top=new HashSet<>(TOP);if(focus)top.add("focus_timestamp_ms");fields(answer,top);
        equal(answer,"schema_version","1.5");equal(answer,"evidence_mode","structured");equal(answer,"evidence_policy",AgentScreenSearch.POLICY);equal(answer.path("provenance"),"prompt_version",AgentScreenSearch.PROMPT);
        if(!AgentSpeechLookup.validQuestion(text(answer,"question",2000)))throw bad("Invalid screen policy question");
        JsonNode source=answer.path("screen_source");AgentScreenSearch.validatePublicSource(source);long duration=number(source,"duration_ms");
        Map<String,JsonNode> frames=new HashMap<>();for(JsonNode frame:array(answer,"visual_evidence",32))if(frames.put(text(frame,"frame_id",5),frame)!=null)throw bad("Repeated frame");
        JsonNode calls=array(answer,"tool_calls",4),receipts=array(answer,"screen_searches",4);Map<Long,JsonNode> receiptSteps=new HashMap<>();
        long lastReceipt=0;for(JsonNode receipt:receipts){fields(receipt,RECEIPT);long step=number(receipt,"tool_step");if(step<=lastReceipt||step>calls.size()||receiptSteps.put(step,receipt)!=null)throw bad("Invalid screen receipt step");lastReceipt=step;}
        int searches=0;Set<String> signatures=new HashSet<>(),produced=new HashSet<>();var projectedCalls=JSON.createArrayNode();
        for(int index=0;index<calls.size();index++){
            JsonNode raw=calls.get(index);fields(raw,CALL);if(number(raw,"step")!=index+1)throw bad("Tool steps must be contiguous");equal(raw,"origin",focus&&index==0?"USER_FOCUS":"MODEL");
            String name=text(raw,"tool_name",40);JsonNode args=raw.path("arguments");String signature=name+":"+AgentScreenSearch.canonical(args);boolean duplicate=!signatures.add(signature);
            JsonNode ids=array(raw,"result_frame_ids",24);boolean reused=true;for(JsonNode id:ids)if(!produced.contains(id.asText()))reused=false;
            if(duplicate&&(!ids.isEmpty()||!array(raw,"result_utterance_ids",200).isEmpty()))throw bad("Repeated tools cannot claim additional observations");
            ObjectNode projected=(ObjectNode)raw.deepCopy();boolean omit=false;
            if(name.equals("search_screen_text")){
                searches++;if(focus&&index==0)throw bad("User focus cannot be screen search");fields(args,Set.of("query","top_k"));AgentScreenSearch.tokens(text(args,"query",120));long k=number(args,"top_k");if(k<1||k>3||!array(raw,"result_utterance_ids",0).isEmpty())throw bad("Invalid screen tool arguments");
                JsonNode receipt=receiptSteps.get((long)index+1);if(receipt==null)throw bad("Missing screen receipt");String status=text(receipt,"status",24);long count=number(receipt,"total_match_count");if(count>number(source,"text_frame_count"))throw bad("Invalid match count");
                JsonNode hits=array(receipt,"hits",3);JsonNode error=receipt.path("error_code");
                switch(status){
                    case "OBSERVED"->{if(duplicate||count<1||hits.size()!=Math.min(k,count)||!error.isNull())throw bad("Invalid observed receipt");}
                    case "EMPTY"->{if(duplicate||number(source,"text_frame_count")!=0||count!=0||!hits.isEmpty()||!error.isNull())throw bad("Invalid empty index receipt");}
                    case "NO_MATCH"->{if(duplicate||number(source,"text_frame_count")==0||count!=0||!hits.isEmpty()||!error.isNull())throw bad("Invalid no-match receipt");}
                    case "TOOL_ERROR"->{if(!hits.isEmpty()||!error.isString()||!Set.of("CONTEXT_LIMIT","FRAME_UNAVAILABLE","FRAME_POSITION_MISMATCH","DUPLICATE_TOOL_CALL").contains(error.asText())||error.asText().equals("DUPLICATE_TOOL_CALL")!=duplicate||count==0&&!Set.of("CONTEXT_LIMIT","DUPLICATE_TOOL_CALL").contains(error.asText()))throw bad("Invalid screen tool error");}
                    default->throw bad("Unknown screen receipt status");
                }
                if(ids.size()!=hits.size())throw bad("Screen hits must map to actual frames");var positions=JSON.createArrayNode();Set<String> actuals=new HashSet<>(),ocrIds=new HashSet<>();long previous=-1;
                for(int h=0;h<hits.size();h++){
                    JsonNode hit=hits.get(h);fields(hit,HIT);String ocr=text(hit,"ocr_frame_id",10),fid=text(hit,"frame_id",5),body=hit.path("observed_text").asText();long requested=AgentScreenSearch.safe(hit,"requested_ms"),old=AgentScreenSearch.safe(hit,"ocr_timestamp_ms");
                    if(!ocr.matches("ocr_[0-9]{6}")||Integer.parseInt(ocr.substring(4))<1||Integer.parseInt(ocr.substring(4))>number(source,"frame_count")||!ocrIds.add(ocr)||requested<=previous||requested>=duration||old>=duration||old+1<requested||!hit.path("observed_text").isString()||body.isEmpty()||body.codePointCount(0,body.length())>2000||!AgentScreenSearch.unicode(body)||!ids.get(h).isString()||!ids.get(h).asText().equals(fid)||!hit.path("source_time_base").asText().matches("[1-9][0-9]{0,9}/[1-9][0-9]{0,9}"))throw bad("Invalid screen locator");
                    JsonNode frame=frames.get(fid);if(frame==null||number(frame,"requested_ms")!=requested||!AgentScreenSearch.position(frame).equals(AgentScreenSearch.position(hit))||!actuals.add(AgentScreenSearch.position(hit))||AgentScreenSearch.matches(JSON.createArrayNode().add(hit),args.path("query").asText()).isEmpty())throw bad("Screen locator and actual source PTS differ");
                    String[] ratio=hit.path("source_time_base").asText().split("/");var d=new java.math.BigInteger(ratio[1]);if(java.math.BigInteger.valueOf(old).multiply(d).subtract(java.math.BigInteger.valueOf(number(hit,"source_pts")).multiply(new java.math.BigInteger(ratio[0])).multiply(java.math.BigInteger.valueOf(1000))).abs().compareTo(d)>0)throw bad("Invalid OCR display time");
                    positions.add(requested);previous=requested;
                }
                omit=ids.isEmpty()||reused;projected.put("tool_name","inspect_frames");projected.putObject("arguments").set("timestamps_ms",positions);
            }else{
                if(!Set.of("search_transcript","read_context","list_topics","inspect_frames","inspect_clip").contains(name)||receiptSteps.containsKey((long)index+1))throw bad("Unexpected tool or receipt");
                if(Set.of("inspect_frames","inspect_clip").contains(name)&&!array(raw,"result_utterance_ids",0).isEmpty())throw bad("Native visual tools cannot produce ASR");
                if(name.equals("inspect_frames")){fields(args,Set.of("timestamps_ms"));JsonNode times=array(args,"timestamps_ms",4);if(times.isEmpty())throw bad("Empty native frame request");long prior=-1;for(JsonNode t:times){if(!t.isIntegralNumber()||!t.canConvertToLong()||t.longValue()<=prior||t.longValue()>=duration)throw bad("Invalid native frame time");prior=t.longValue();}}
                if(name.equals("inspect_clip")){fields(args,Set.of("start_ms","end_ms"));long start=AgentScreenSearch.safe(args,"start_ms"),end=AgentScreenSearch.safe(args,"end_ms");if(end<=start||end>duration||end-start>12000)throw bad("Invalid native sampled clip");}
                if(Set.of("inspect_frames","inspect_clip").contains(name)&&!ids.isEmpty()){
                    List<Long> positions=new ArrayList<>();if(name.equals("inspect_frames"))for(JsonNode t:args.path("timestamps_ms"))positions.add(t.longValue());else for(long t=number(args,"start_ms");t<number(args,"end_ms");t+=500)positions.add(t);
                    if(ids.size()!=positions.size())throw bad("Partial native visual batch");Set<String> seen=new HashSet<>();for(int i=0;i<ids.size();i++){if(!ids.get(i).isString()||!seen.add(ids.get(i).asText())||!frames.containsKey(ids.get(i).asText())||number(frames.get(ids.get(i).asText()),"requested_ms")!=positions.get(i))throw bad("Invalid native visual frame mapping");}
                }
                if(Set.of("inspect_frames","inspect_clip").contains(name)&&duplicate&&!ids.isEmpty())throw bad("Repeated native visual tool");
                if(Set.of("inspect_frames","inspect_clip").contains(name)&&ids.isEmpty()||name.equals("inspect_frames")&&reused)omit=true;
            }
            if(!omit){projected.put("step",projectedCalls.size()+1);if(!focus)projected.remove("origin");projectedCalls.add(projected);}for(JsonNode id:ids)produced.add(id.asText());
        }
        if(searches!=receipts.size())throw bad("Screen receipts must exactly match screen tool steps");
        ObjectNode projection=(ObjectNode)answer.deepCopy();projection.remove(List.of("evidence_policy","screen_source","screen_searches"));projection.put("schema_version","1.3");((ObjectNode)projection.path("provenance")).put("prompt_version",AgentEvidence.PROMPT);projection.set("tool_calls",projectedCalls);return projection;
    }
    private static JsonNode array(JsonNode n,String key,int max){JsonNode value=n.path(key);if(!value.isArray()||value.size()>max)throw bad("Invalid "+key);return value;}
    private static void equal(JsonNode n,String key,String value){if(!n.path(key).isString()||!n.path(key).asText().equals(value))throw bad("Screen identity changed");}
    private static io.livecut.jobs.JobException bad(String text){return AgentScreenSearch.bad(text);}
}
