package io.livecut.clips;

import static io.livecut.clips.ClipPlanValidator.*;
import java.util.*;
import io.livecut.agent.AgentAnswerValidator;
import io.livecut.agent.AgentVisualArtifacts;
import io.livecut.agent.AgentFocus;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Immutable, verified VIDEO answer evidence; browser times never enter this projection. */
public final class AnswerClipSource {
    private AnswerClipSource() {}
    public static final String STAGE="ANCHOR_CLIP",PROMPT="anchor-clips-v1";
    public static final int MAX_TEXT=12000;
    private static final Set<String> FIELDS=Set.of("schema_version","turn_id","session_id","answer_hash","job_id","video_id","source_hash","transcript_hash","duration_ms","question","answer","utterance_ids","citations","context_utterance_ids");
    public record Frozen(JsonNode source,JsonNode answer) {}
    public static JsonNode selector(JsonNode value){
        if(value==null||value.isNull())return null;
        try{fields(value,Set.of("turn_id","utterance_ids"));ClipRequestService.uuid(text(value,"turn_id",36));ids(value,"utterance_ids");return value.deepCopy();}
        catch(RuntimeException invalid){throw bad("A source answer accepts only a turn UUID and 1..24 unique citation IDs");}
    }
    public static Frozen resolve(JdbcTemplate jdbc,ArtifactVerifier artifacts,Map<String,Object> job,
            String sourceHash,String transcriptHash,long duration,JsonNode utterances,JsonNode selection){
        String parent=String.valueOf(job.get("id"));
        var rows=jdbc.queryForList("SELECT t.*,s.kind,s.video_id,s.source_hash,s.transcript_hash,s.source_json,s.duration_ms FROM agent_turn t JOIN agent_session s ON s.id=t.session_id AND s.job_id=t.job_id WHERE t.id=? AND t.job_id=? FOR UPDATE",selection.path("turn_id").asText(),parent);
        if(rows.size()!=1)throw unavailable();var owner=rows.getFirst();
        if(!"VIDEO".equals(owner.get("kind"))||!"SUCCEEDED".equals(owner.get("status"))
            ||!Objects.equals(owner.get("video_id"),job.get("video_id"))||!sourceHash.equals(owner.get("source_hash"))
            ||!transcriptHash.equals(owner.get("transcript_hash"))||duration!=((Number)owner.get("duration_ms")).longValue())throw unavailable();
        try{
            if(!JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances").equals(utterances))throw invalid();
            JsonNode answer=JSON.readTree(String.valueOf(owner.get("result_json")));
            if(!ClipRequestService.sha(ClipRequestService.canonicalJson(answer)).equals(owner.get("result_hash")))throw invalid();
            AgentAnswerValidator.validate(answer,owner);if(!answer.path("answer_scope").asText().equals("VIDEO"))throw unavailable();
            var stages=jdbc.queryForList("SELECT * FROM job_stage WHERE owner_type='AGENT_TURN' AND owner_id=? FOR UPDATE",owner.get("id"));
            String expectedStage=AgentFocus.stage(owner);
            if(stages.size()!=1||!"SUCCEEDED".equals(stages.getFirst().get("status"))||!expectedStage.equals(stages.getFirst().get("stage_type")))throw unavailable();
            var stage=stages.getFirst();var identity=new ArtifactVerifier.ManifestIdentity(parent,String.valueOf(owner.get("video_id")),((Number)job.get("generation")).intValue(),String.valueOf(stage.get("id")),expectedStage,((Number)stage.get("attempt")).intValue(),String.valueOf(stage.get("run_token")),sourceHash);
            var manifest=artifacts.verifyAgentAnswerManifest(String.valueOf(stage.get("manifest_key")),String.valueOf(stage.get("manifest_hash")),identity);
            var artifact=manifest.artifacts().stream().filter(item->item.kind().equals("agent_answer")).findFirst().orElseThrow();
            if(!answer.equals(artifacts.readJsonArtifact(artifact,identity)))throw invalid();
            if(AgentFocus.visualStage(expectedStage))AgentVisualArtifacts.verify(jdbc,artifacts,job,owner,answer,manifest,identity);
            return new Frozen(project(answer,utterances,duration,selection.path("utterance_ids")),answer);
        }catch(JobException error){if(Set.of("INVALID_ANSWER_SOURCE","ANSWER_SOURCE_UNAVAILABLE").contains(error.getCode()))throw error;throw invalid();}
        catch(RuntimeException error){throw invalid();}
    }
    static JsonNode project(JsonNode answer,JsonNode utterances,long duration,JsonNode selected){
        List<String> ids=ids(JSON.valueToTree(Map.of("utterance_ids",selected)),"utterance_ids");
        Map<String,Integer> order=new HashMap<>();Map<String,JsonNode> originals=new HashMap<>(),cited=new HashMap<>();
        for(JsonNode row:utterances){String id=row.path("utterance_id").asText();order.put(id,order.size());originals.put(id,row);}
        answer.path("citations").forEach(row->cited.put(row.path("utterance_id").asText(),row));
        int previous=-1,characters=0;List<JsonNode> citations=new ArrayList<>();Set<Integer> neighbors=new TreeSet<>();Set<String> context=new HashSet<>();
        for(String id:ids){Integer index=order.get(id);if(index==null||index<=previous||!cited.containsKey(id))throw bad("Select only source-ordered citations from this VIDEO answer");previous=index;
            JsonNode original=originals.get(id),citation=cited.get(id);if(!citation.path("text").equals(original.path("text"))||!citation.path("start_ms").equals(original.path("start_ms"))||!citation.path("end_ms").equals(original.path("end_ms")))throw invalid();
            characters+=original.path("text").asText().length();if(characters>MAX_TEXT)throw bad("Selected citation text exceeds 12000 UTF-16 units");citations.add(citation);context.add(id);
            if(index>0)neighbors.add(index-1);if(index+1<utterances.size())neighbors.add(index+1);
        }
        for(int index:neighbors){JsonNode original=utterances.get(index);String id=original.path("utterance_id").asText();int size=original.path("text").asText().length();if(!context.contains(id)&&characters+size<=MAX_TEXT){characters+=size;context.add(id);}}
        Map<String,Object> source=new TreeMap<>();source.put("schema_version","1.0");
        for(String key:List.of("turn_id","session_id","job_id","video_id","source_hash","transcript_hash","question","answer"))source.put(key,answer.path(key));
        source.put("answer_hash",ClipRequestService.sha(ClipRequestService.canonicalJson(answer)));source.put("duration_ms",duration);source.put("utterance_ids",ids);source.put("citations",citations);source.put("context_utterance_ids",context.stream().sorted(Comparator.comparingInt(order::get)).toList());return JSON.valueToTree(source);
    }
    public static Frozen stored(Map<String,Object> owner){
        Object raw=owner.get("source_answer_json"),hash=owner.get("source_answer_hash");JsonNode frozen=JSON.readTree(String.valueOf(owner.get("source_json")));
        if(raw==null){if(hash!=null||frozen.has("answer_result"))throw invalid();return null;}
        try{
            JsonNode source=JSON.readTree(String.valueOf(raw)),answer=frozen.path("answer_result");fields(source,FIELDS);
            if(!ClipRequestService.sha(ClipRequestService.canonicalJson(source)).equals(hash)||!source.path("schema_version").asText().equals("1.0"))throw invalid();
            ClipRequestService.uuid(text(source,"turn_id",36));ClipRequestService.uuid(text(source,"session_id",36));
            for(String key:List.of("job_id","video_id","source_hash","transcript_hash"))if(!source.path(key).asText().equals(String.valueOf(owner.get(key))))throw invalid();
            long duration=((Number)owner.get("duration_ms")).longValue();if(number(source,"duration_ms")!=duration)throw invalid();
            Map<String,Object> frame=new HashMap<>(owner);frame.put("id",source.path("turn_id").asText());frame.put("session_id",source.path("session_id").asText());frame.put("question",source.path("question").asText());frame.put("text_model_id",answer.path("provenance").path("text_model_id").asText());
            frame.put("visual_mode",Set.of("1.1","1.2").contains(answer.path("schema_version").asText())?"frames":"none");
            if(answer.path("schema_version").asText().equals("1.2")){
                JsonNode focus=answer.path("focus_timestamp_ms");if(!focus.isIntegralNumber()||!focus.canConvertToLong())throw invalid();frame.put("focus_timestamp_ms",focus.longValue());
            }else frame.remove("focus_timestamp_ms");
            AgentAnswerValidator.validate(answer,frame);if(!answer.path("answer_scope").asText().equals("VIDEO")||!ClipRequestService.canonicalJson(project(answer,frozen.path("utterances"),duration,source.path("utterance_ids"))).equals(ClipRequestService.canonicalJson(source)))throw invalid();
            return new Frozen(source,answer);
        }catch(RuntimeException error){throw invalid();}
    }
    public static void validatePlan(JsonNode plan,Map<String,Object> owner,boolean initial){
        Frozen frozen=stored(owner);if(frozen==null)return;JsonNode source=frozen.source();
        if(plan.path("goals").size()!=1||plan.path("outputs").size()!=1||!plan.path("goals").get(0).path("goal_id").asText().equals("answer-source"))throw planInvalid("An anchored request has one fixed goal and output");
        String instruction=String.valueOf(owner.get("instruction"));int goalEnd=Math.min(300,instruction.length());if(goalEnd>0&&goalEnd<instruction.length()&&Character.isHighSurrogate(instruction.charAt(goalEnd-1))&&Character.isLowSurrogate(instruction.charAt(goalEnd)))goalEnd--;
        if(!plan.path("goals").get(0).path("description").asText().equals(instruction.substring(0,goalEnd))||!plan.path("constraints").equals(JSON.valueToTree(Map.of("keep_explanation_complete",true,"exclude",List.of()))))throw planInvalid("Anchored goal and constraints must match the frozen single instruction");
        JsonNode output=plan.path("outputs").get(0);Set<String> anchors=new HashSet<>(ids(source,"utterance_ids")),context=new HashSet<>();source.path("context_utterance_ids").forEach(id->context.add(id.asText()));
        JsonNode utterances=JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances");Map<String,Integer> order=new HashMap<>();for(JsonNode row:utterances)order.put(row.path("utterance_id").asText(),order.size());
        Set<String> included=new HashSet<>();for(JsonNode part:output.path("parts")){int start=order.get(part.path("start_utterance_id").asText()),end=order.get(part.path("end_utterance_id").asText());for(int i=start;i<=end;i++)included.add(utterances.get(i).path("utterance_id").asText());}
        if(!output.path("parts").isEmpty()&&!included.containsAll(anchors))throw planInvalid("Every anchored candidate must retain all selected source sentences");
        if(initial&&!context.containsAll(included))throw planInvalid("Automatic anchored parts must stay inside the frozen observed context");
        for(JsonNode exclusion:output.path("excluded")){String id=exclusion.path("utterance_id").asText();if(anchors.contains(id)||initial&&!context.contains(id))throw planInvalid("Anchors cannot be excluded or replaced by unobserved source");}
        if(!plan.path("provenance").path("index_hash").asText().equals(indexHash(owner,source)))throw planInvalid("Anchored context index does not match the frozen source");
    }
    public static String indexHash(Map<String,Object> owner,JsonNode source){
        JsonNode utterances=JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances");Map<String,Integer> order=new HashMap<>();Map<String,JsonNode> byId=new HashMap<>();
        for(JsonNode row:utterances){String id=row.path("utterance_id").asText();order.put(id,order.size());byId.put(id,row);}
        List<JsonNode> selected=new ArrayList<>();List<Map<String,String>> ranges=new ArrayList<>();int previous=-2;String first=null,last=null;
        for(JsonNode id:source.path("context_utterance_ids")){String key=id.asText();int index=order.get(key);selected.add(byId.get(key));if(index!=previous+1){if(first!=null)ranges.add(Map.of("start_utterance_id",first,"end_utterance_id",last));first=key;}last=key;previous=index;}
        if(first!=null)ranges.add(Map.of("start_utterance_id",first,"end_utterance_id",last));
        return ClipRequestService.sha(ClipRequestService.canonicalJson(JSON.valueToTree(Map.of("version","answer-anchor-context-v1","source_hash",owner.get("source_hash"),"transcript_hash",owner.get("transcript_hash"),"answer_hash",source.path("answer_hash").asText(),"retrieval_mode","anchor_context","pinned_utterance_ids",source.path("utterance_ids"),"utterances",selected,"context_ranges",ranges))));
    }
    private static List<String> ids(JsonNode value,String key){JsonNode list=value.path(key);if(!list.isArray()||list.isEmpty()||list.size()>24)throw bad("Select 1..24 original citations");Set<String> unique=new HashSet<>();List<String> result=new ArrayList<>();for(JsonNode id:list){if(!id.isString()||!id.asText().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||!unique.add(id.asText()))throw bad("Invalid or repeated citation ID");result.add(id.asText());}return result;}
    private static JobException bad(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_ANSWER_SOURCE",message);}
    private static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"ANSWER_SOURCE_INVALID","Frozen answer evidence failed provenance validation");}
    private static JobException unavailable(){return new JobException(HttpStatus.CONFLICT,"ANSWER_SOURCE_UNAVAILABLE","Choose citations from a successful VIDEO answer on this exact source");}
    private static JobException planInvalid(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_CLIP_PLAN",message);}
}
