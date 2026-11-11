package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.*;
import java.math.BigInteger;
import java.util.*;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Modality, temporal provenance and deterministic assembly, never semantic entailment. */
public final class AgentEvidenceAnswerValidator {
    private AgentEvidenceAnswerValidator() {}
    private static final Set<String> TOP=Set.of("schema_version","session_id","turn_id","job_id","video_id","source_hash","transcript_hash","question","answer","answer_scope","citations","tool_calls","provenance","suggested_clip_instruction","visual_evidence","visual_citations","evidence_mode","answer_blocks");
    private static final Map<String,String> LABELS=Map.of("SPEECH","原句依据","FRAME","画面观察","SAMPLED_CHANGE","前后采样","INFERENCE","推断","GENERAL","通用解释","INSUFFICIENT","证据不足");
    public static JsonNode validate(JsonNode answer,Map<String,Object> owner){
        if(!AgentEvidence.structured(owner))throw bad("Structured evidence requires its frozen mode");
        var expected=new HashSet<>(TOP);Long focus=AgentFocus.value(owner);if(focus!=null)expected.add("focus_timestamp_ms");fields(answer,expected);
        if(!answer.path("schema_version").isString()||!"1.3".equals(answer.path("schema_version").asText())
                ||!answer.path("evidence_mode").isString()||!"structured".equals(answer.path("evidence_mode").asText())
                ||!answer.path("provenance").path("prompt_version").isString()||!AgentEvidence.PROMPT.equals(answer.path("provenance").path("prompt_version").asText()))throw bad("Structured result protocol changed");
        // The original object is checked first. Projection only reuses existing source/focus/PTS/tool rules.
        ObjectNode projection=(ObjectNode)answer.deepCopy();projection.remove(List.of("evidence_mode","answer_blocks"));projection.put("schema_version",focus==null?"1.1":"1.2");
        ((ObjectNode)projection.path("provenance")).put("prompt_version",focus==null?"video-agent-visual-v1":"video-agent-visual-focus-v1");
        var legacyOwner=new HashMap<>(owner);legacyOwner.put("evidence_mode","none");AgentAnswerValidator.validate(projection,legacyOwner);
        verifyAssembly(answer,JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances"));return answer;
    }

    /** Notes already own a verified snapshot; recompute all new public content before rendering it. */
    public static void verifyAssembly(JsonNode answer,JsonNode source){
        if(!answer.path("schema_version").isString()||!"1.3".equals(answer.path("schema_version").asText())||!answer.path("evidence_mode").isString()||!"structured".equals(answer.path("evidence_mode").asText()))throw bad("Invalid structured note mode");
        if(!source.isArray())throw bad("Invalid structured source");
        Map<String,JsonNode> originals=new LinkedHashMap<>();Map<String,Integer> order=new HashMap<>();
        for(JsonNode row:source){String id=text(row,"utterance_id",128);if(originals.put(id,row)!=null)throw bad("Repeated original utterance");order.put(id,order.size());}
        Set<String> observed=new HashSet<>();for(JsonNode call:array(answer,"tool_calls",0,4))for(JsonNode id:array(call,"result_utterance_ids",0,200))observed.add(id.asText());
        Map<String,JsonNode> frames=new TreeMap<>();for(JsonNode frame:array(answer,"visual_evidence",0,32))if(frames.put(text(frame,"frame_id",5),frame)!=null)throw bad("Repeated frame");
        Set<String> utteranceUnion=new HashSet<>();Map<String,List<String>> frameUnion=new TreeMap<>();List<String> sections=new ArrayList<>();boolean grounded=false;
        String scope=text(answer,"answer_scope",20);
        for(JsonNode block:array(answer,"answer_blocks",1,8)){
            fields(block,Set.of("block_id","kind","text","utterance_ids","frame_ids"));String id=text(block,"block_id",3),kind=text(block,"kind",20),body=blockText(block,"text",1000);
            if(!id.equals(String.format(Locale.ROOT,"b%02d",sections.size()+1))||!LABELS.containsKey(kind))throw bad("Block IDs and kinds must follow the protocol");
            List<String> utterances=references(block,"utterance_ids",8),visual=references(block,"frame_ids",4);int previous=-1;
            for(String uid:utterances){if(!originals.containsKey(uid)||!observed.contains(uid)||order.get(uid)<=previous)throw bad("Block original utterances must be observed and source ordered");previous=order.get(uid);utteranceUnion.add(uid);}
            String previousFrame="";for(String frame:visual){if(!frames.containsKey(frame)||(!kind.equals("SAMPLED_CHANGE")&&frame.compareTo(previousFrame)<=0))throw bad("Block frames must be observed and ordered");previousFrame=frame;frameUnion.computeIfAbsent(frame,ignored->new ArrayList<>()).add(id);}
            switch(kind){
                case "SPEECH"->{if(utterances.isEmpty()||!visual.isEmpty())throw bad("Speech requires original utterances only");grounded=true;}
                case "FRAME"->{if(!utterances.isEmpty()||visual.isEmpty())throw bad("Frame observation requires actual frames only");grounded=true;}
                case "SAMPLED_CHANGE"->{if(!utterances.isEmpty()||visual.size()!=2||compare(frames.get(visual.get(0)),frames.get(visual.get(1)))>=0||!sameClip(answer,visual))throw bad("Change requires two time-ordered frames from one successful sampled clip");grounded=true;}
                case "INFERENCE"->{if(utterances.isEmpty()&&visual.isEmpty())throw bad("Inference requires declared support");}
                case "GENERAL","INSUFFICIENT"->{if(!utterances.isEmpty()||!visual.isEmpty())throw bad("General explanations and evidence gaps cannot acquire source authority");}
                default->throw bad("Unknown evidence block kind");
            }
            if(scope.equals("GENERAL")&&!kind.equals("GENERAL")||scope.equals("INSUFFICIENT")&&!kind.equals("INSUFFICIENT"))throw bad("Answer scope and block kinds disagree");
            String section="【"+LABELS.get(kind)+" "+id+"】\n"+body;
            if(kind.equals("SAMPLED_CHANGE"))section+="\n（仅比较已取样画面，不证明采样间隙或因果。）";
            if(kind.equals("INFERENCE"))section+="\n（属于推断，不能当作讲者原话或已观察事实。）";
            sections.add(section);
        }
        if(!Set.of("VIDEO","GENERAL","INSUFFICIENT").contains(scope)||scope.equals("VIDEO")&&!grounded)throw bad("VIDEO requires an observed evidence block");
        String assembled=String.join("\n\n",sections);blockText(answer,"answer",6000);if(assembled.length()>6000||!assembled.equals(answer.path("answer").asText()))throw bad("Answer must equal program-assembled blocks");
        var expectedCitations=JSON.createArrayNode();for(var item:originals.entrySet())if(utteranceUnion.contains(item.getKey())){
            JsonNode row=item.getValue();expectedCitations.addObject().put("utterance_id",item.getKey()).put("start_ms",number(row,"start_ms")).put("end_ms",number(row,"end_ms")).put("text",text(row,"text",100000));
        }
        var expectedVisual=JSON.createArrayNode();frameUnion.forEach((frame,blocks)->expectedVisual.addObject().put("frame_id",frame).put("explanation","关联分项："+String.join("、",blocks)));
        if(!io.livecut.clips.ClipRequestService.canonicalJson(expectedCitations).equals(io.livecut.clips.ClipRequestService.canonicalJson(answer.path("citations")))
                ||!expectedVisual.equals(answer.path("visual_citations")))throw bad("Citations must equal the exact program-derived block union");
    }
    private static List<String> references(JsonNode block,String name,int maximum){List<String> result=new ArrayList<>();Set<String> seen=new HashSet<>();for(JsonNode ref:array(block,name,0,maximum)){if(!ref.isString()||!seen.add(ref.stringValue()))throw bad("Invalid repeated block reference");result.add(ref.stringValue());}return result;}
    private static boolean sameClip(JsonNode answer,List<String> ids){for(JsonNode call:answer.path("tool_calls"))if("inspect_clip".equals(call.path("tool_name").asText())){Set<String> result=new HashSet<>();call.path("result_frame_ids").forEach(id->result.add(id.asText()));if(result.containsAll(ids))return true;}return false;}
    private static int compare(JsonNode first,JsonNode last){String[] a=first.path("source_time_base").asText().split("/"),b=last.path("source_time_base").asText().split("/");return BigInteger.valueOf(number(first,"source_pts")).multiply(new BigInteger(a[0])).multiply(new BigInteger(b[1])).compareTo(BigInteger.valueOf(number(last,"source_pts")).multiply(new BigInteger(b[0])).multiply(new BigInteger(a[1])));}
    private static JsonNode array(JsonNode node,String field,int minimum,int maximum){JsonNode value=node.path(field);if(!value.isArray()||value.size()<minimum||value.size()>maximum)throw bad("Invalid "+field);return value;}
    private static String blockText(JsonNode node,String field,int maximum){String value=text(node,field,maximum);if(value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xFEFF))throw bad("Evidence text needs non-whitespace content");for(int index=0;index<value.length();index++){char c=value.charAt(index);if(c<32&&c!='\t'&&c!='\r'&&c!='\n'||c>=127&&c<=159)throw bad("Unsupported control character in evidence content");if(Character.isHighSurrogate(c)){if(++index>=value.length()||!Character.isLowSurrogate(value.charAt(index)))throw bad("Malformed evidence Unicode");}else if(Character.isLowSurrogate(c))throw bad("Malformed evidence Unicode");}return value;}
    private static JobException bad(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_AGENT_ANSWER",message);}
}
