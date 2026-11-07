package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.*;
import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** Exact source and coverage validation; a model suggestion is never a confirmed semantic fact. */
public final class CaptionProofreadValidator {
    private CaptionProofreadValidator() {}
    public static final String STAGE="CAPTION_PROOFREAD";
    public static final String PROMPT="caption-proofread-v1";
    public static final String DEFAULT_INSTRUCTION="校对本批字幕中的明显转写错字和术语，保留原意、数字与事实；不确定时提示回听，不要改写或补充内容。";
    public static final int MAX_CUES=20,MAX_BATCH_CHARACTERS=6000,MAX_CONTEXT_CHARACTERS=12000;
    private static final Set<String> RESULT=Set.of("schema_version","session_id","turn_id","job_id","video_id","source_hash","transcript_hash","instruction",
        "caption_snapshot_hash","export_id","clip_id","caption_revision","caption_content_hash","coverage","suggestions","tool_calls","provenance");
    public static boolean validText(String value,int maximum,boolean allowBlank){
        if(value==null||value.length()>maximum||!allowBlank&&value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xfeff))return false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);
            if(c<0x20&&c!='\t'&&c!='\r'&&c!='\n')return false;
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))return false;}
            else if(Character.isLowSurrogate(c))return false;}
        return true;
    }
    public static JsonNode frozen(Map<String,Object> owner){
        JsonNode frozen=JSON.readTree(String.valueOf(owner.get("source_json"))),caption=frozen.path("caption");
        String hash=frozen.path("caption_snapshot_hash").asText();
        if(!frozen.isObject()||!caption.isObject()||!hash.matches("[a-f0-9]{64}")
            ||!hash.equals(sha(caption))||!caption.path("cues").isArray()||caption.path("cues").isEmpty()
            ||caption.path("cues").size()>10000||!frozen.path("utterances").isArray()
            ||!caption.path("export_id").asText().equals(owner.get("caption_export_id"))||!caption.path("clip_id").asText().equals(owner.get("caption_clip_id"))
            ||!caption.path("source").path("transcript_hash").asText().equals(owner.get("transcript_hash"))
            ||!caption.path("content_hash").asText().equals(sha(JSON.valueToTree(Map.of("cues",caption.path("cues"))))))throw corrupt();
        return frozen;
    }
    /** Whole cues only; UTF-16 agrees with the existing Java caption limits and the browser. */
    public static JsonNode context(JsonNode frozen,int offset){
        JsonNode caption=frozen.path("caption"),all=caption.path("cues");
        if(offset<0||offset>=all.size())throw new JobException(HttpStatus.BAD_REQUEST,"INVALID_CAPTION_PROOFREAD_BATCH","Choose a saved subtitle batch within this document");
        List<JsonNode> cues=new ArrayList<>();int characters=0;
        for(int index=offset;index<all.size()&&cues.size()<MAX_CUES;index++){
            JsonNode cue=all.get(index);int size=text(cue,"text",2000).length()+text(cue,"source_text",2000).length();
            if(characters+size>MAX_BATCH_CHARACTERS)break;cues.add(cue);characters+=size;
        }
        if(cues.isEmpty())throw corrupt();
        JsonNode utterances=frozen.path("utterances");Map<String,Integer> order=new HashMap<>();
        for(int i=0;i<utterances.size();i++)order.put(text(utterances.get(i),"utterance_id",128),i);
        Set<String> initial=new HashSet<>();int contextSize=0;
        for(JsonNode cue:cues){String id=text(cue,"source_utterance_id",128);Integer index=order.get(id);
            if(index==null||!cue.path("source_text").equals(utterances.get(index).path("text")))throw corrupt();
            if(initial.add(id))contextSize+=text(utterances.get(index),"text",100000).length();}
        if(contextSize>MAX_CONTEXT_CHARACTERS)throw corrupt();
        Set<Integer> neighbors=new TreeSet<>();
        for(String id:initial){int index=order.get(id);if(index>0)neighbors.add(index-1);if(index+1<utterances.size())neighbors.add(index+1);}
        for(int index:neighbors){JsonNode row=utterances.get(index);String id=row.path("utterance_id").asText();int size=row.path("text").asText().length();
            if(!initial.contains(id)&&contextSize+size<=MAX_CONTEXT_CHARACTERS){initial.add(id);contextSize+=size;}}
        List<String> contextIds=initial.stream().sorted(Comparator.comparingInt(order::get)).toList();
        Map<String,Object> result=new TreeMap<>();result.put("schema_version","1.0");result.put("caption_snapshot_hash",frozen.path("caption_snapshot_hash").asText());
        for(String key:List.of("export_id","clip_id","revision","content_hash","source"))result.put(key,caption.path(key));
        result.put("offset",offset);result.put("count",cues.size());result.put("total_cues",all.size());result.put("next_offset",offset+cues.size()==all.size()?null:offset+cues.size());
        result.put("cues",cues);result.put("context_utterance_ids",contextIds);return JSON.valueToTree(result);
    }
    public static JsonNode storedContext(Map<String,Object> owner){
        return storedContext(owner,frozen(owner));
    }
    static JsonNode storedContext(Map<String,Object> owner,JsonNode frozen){
        JsonNode stored=JSON.readTree(String.valueOf(owner.get("caption_context_json")));
        long offset=number(stored,"offset");if(offset>Integer.MAX_VALUE)throw corrupt();JsonNode expected=context(frozen,(int)offset);
        if(!expected.equals(stored))throw corrupt();return stored;
    }
    public static CaptionProofreadModels.Batch batch(JsonNode context){
        List<String> ids=new ArrayList<>();context.path("cues").forEach(cue->ids.add(cue.path("id").asText()));
        return new CaptionProofreadModels.Batch(context.path("offset").intValue(),context.path("count").intValue(),context.path("total_cues").intValue(),context.path("next_offset").isNull()?null:context.path("next_offset").intValue(),ids);
    }
    public static JsonNode validate(JsonNode result,Map<String,Object> owner){
        return validate(result,owner,frozen(owner));
    }
    static JsonNode validate(JsonNode result,Map<String,Object> owner,JsonNode frozen){
        fields(result,RESULT);equal(result,"schema_version","1.0");
        for(String key:List.of("session_id","job_id","video_id","source_hash","transcript_hash"))equal(result,key,String.valueOf(owner.get(key)));
        equal(result,"turn_id",String.valueOf(owner.get("id")));equal(result,"instruction",String.valueOf(owner.get("question")));
        if(!validText(result.path("instruction").asText(),1000,false))throw invalid("Invalid proofreading instruction");
        JsonNode context=storedContext(owner,frozen),caption=frozen.path("caption");
        for(String key:List.of("export_id","clip_id"))equal(result,key,caption.path(key).asText());
        equal(result,"caption_snapshot_hash",frozen.path("caption_snapshot_hash").asText());equal(result,"caption_content_hash",caption.path("content_hash").asText());
        if(number(result,"caption_revision")!=number(caption,"revision")||!result.path("coverage").equals(JSON.valueToTree(batch(context))))throw invalid("Caption coverage or revision changed");
        Map<String,JsonNode> utterances=new HashMap<>();Map<String,Integer> sourceOrder=new HashMap<>();
        for(JsonNode row:frozen.path("utterances")){String id=text(row,"utterance_id",128);sourceOrder.put(id,sourceOrder.size());utterances.put(id,row);}
        Set<String> observed=new HashSet<>();context.path("context_utterance_ids").forEach(id->observed.add(id.asText()));
        int step=1,toolCharacters=0;boolean searched=false;
        int maxTools=Math.min(4,((Number)owner.get("max_text_calls")).intValue()-1);
        for(JsonNode call:array(result,"tool_calls",0,maxTools)){
            fields(call,Set.of("step","tool_name","arguments","result_utterance_ids"));if(number(call,"step")!=step++)throw invalid("Tool steps must be contiguous");
            String name=text(call,"tool_name",40);JsonNode args=call.path("arguments");
            if(name.equals("search_transcript")){searched=true;fields(args,Set.of("query","top_k"));text(args,"query",300);range(args,"top_k",1,8);}
            else if(name.equals("read_context")){fields(args,Set.of("utterance_id","before","after"));if(!utterances.containsKey(text(args,"utterance_id",128)))throw invalid("Unknown context source");range(args,"before",0,3);range(args,"after",0,3);}
            else throw invalid("Unsupported caption proofread tool");
            Set<String> seen=new HashSet<>();int prior=-1,characters=0;
            for(JsonNode id:array(call,"result_utterance_ids",0,200)){if(!id.isString()||!sourceOrder.containsKey(id.stringValue())||!seen.add(id.stringValue())||sourceOrder.get(id.stringValue())<=prior)throw invalid("Invalid observed evidence");prior=sourceOrder.get(id.stringValue());observed.add(id.stringValue());characters+=utterances.get(id.stringValue()).path("text").asText().length();}
            toolCharacters+=characters;if(characters>12000||toolCharacters>36000)throw invalid("Tool evidence exceeds the bounded observed context");
        }
        Map<String,JsonNode> cues=new HashMap<>();Map<String,Integer> cueOrder=new HashMap<>();
        for(JsonNode cue:context.path("cues")){String id=text(cue,"id",128);cueOrder.put(id,cueOrder.size());cues.put(id,cue);}
        Set<String> changed=new HashSet<>();int previous=-1;
        for(JsonNode item:array(result,"suggestions",0,cues.size())){
            fields(item,Set.of("cue_id","before","after","decision","reason","evidence_utterance_ids"));String id=text(item,"cue_id",128);JsonNode original=cues.get(id);
            if(original==null||!changed.add(id)||cueOrder.get(id)<=previous||!item.path("before").equals(original.path("text")))throw invalid("Suggestion does not match this saved batch");previous=cueOrder.get(id);
            String reason=text(item,"reason",500);if(!validText(reason,500,false))throw invalid("Invalid suggestion reason");String decision=text(item,"decision",20);
            if(decision.equals("CORRECTION")){String after=text(item,"after",2000);if(!validText(after,2000,false)||after.equals(original.path("text").asText()))throw invalid("Invalid or unchanged correction text");}
            else if(!decision.equals("UNCERTAIN")||!item.path("after").isNull())throw invalid("Uncertain suggestions must not propose replacement text");
            Set<String> evidence=new HashSet<>();int prior=-1;
            for(JsonNode citation:array(item,"evidence_utterance_ids",1,24)){if(!citation.isString()||!observed.contains(citation.stringValue())||!evidence.add(citation.stringValue())||sourceOrder.get(citation.stringValue())<=prior)throw invalid("Suggestion evidence was not observed");prior=sourceOrder.get(citation.stringValue());}
        }
        JsonNode provenance=result.path("provenance");fields(provenance,Set.of("text_model_id","prompt_version","retrieval_mode"));equal(provenance,"text_model_id",String.valueOf(owner.get("text_model_id")));equal(provenance,"prompt_version",PROMPT);
        String mode=text(provenance,"retrieval_mode",40);if(!Set.of("none","bm25","bm25_dense_rrf").contains(mode)||searched==mode.equals("none"))throw invalid("Retrieval provenance does not match the actual tools");
        return result;
    }
    private static List<JsonNode> array(JsonNode node,String key,int min,int max){JsonNode value=node.path(key);if(!value.isArray()||value.size()<min||value.size()>max)throw invalid("Invalid "+key);List<JsonNode> result=new ArrayList<>();value.forEach(result::add);return result;}
    private static void equal(JsonNode node,String key,String expected){if(!node.path(key).isString()||!node.path(key).stringValue().equals(expected))throw invalid("Source identity mismatch: "+key);}
    private static void range(JsonNode node,String key,long min,long max){long value=number(node,key);if(value<min||value>max)throw invalid("Invalid "+key);}
    private static String sha(JsonNode value){return ClipRequestService.sha(ClipRequestService.canonicalJson(value));}
    private static JobException invalid(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_CAPTION_PROOFREAD_RESULT",message);}
    private static JobException corrupt(){return new JobException(HttpStatus.CONFLICT,"CAPTION_PROOFREAD_SOURCE_INVALID","Frozen caption provenance or batch failed validation");}
}
