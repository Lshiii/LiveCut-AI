package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded admission over paid responses, never over a directory scan or model-generated file path. */
public final class AgentResponseReplayVerifier {
    private final ArtifactVerifier artifacts;
    private static final int MAX_FILE=16*1024*1024,MAX_TOTAL=64*1024*1024;
    private static final JsonMapper STRICT=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> CALL_FIELDS=Set.of("request_id","operation_key","input_hash","model_id","kind","status","origin_attempt","dispatch_attempt","response_key","response_hash","window_index","repair_attempt");
    public record Prepared(JsonNode descriptor,JsonNode answer){public String hash(){return ClipRequestService.sha(ClipRequestService.canonicalJson(descriptor));}}
    public AgentResponseReplayVerifier(ArtifactVerifier artifacts){this.artifacts=artifacts;}
    private final class Reader {
        final List<Map<String,Object>> files=new ArrayList<>();final Set<String> keys=new HashSet<>();int total;
        byte[] bytes(String kind,String key,String prefix,int maximum){
            if(files.size()>=24||!keys.add(key))throw unavailable("CACHE_UNAVAILABLE");byte[] data=artifacts.readReplayFile(key,prefix,maximum);total+=data.length;if(total>MAX_TOTAL)throw unavailable("CACHE_UNAVAILABLE");
            files.add(Map.of("kind",kind,"key",key,"sha256",digest(data),"bytes",data.length));return data;
        }
        JsonNode json(String kind,String key,String prefix,int maximum){try{return STRICT.readTree(bytes(kind,key,prefix,maximum));}catch(JobException failure){throw failure;}catch(RuntimeException malformed){throw unavailable("CACHE_UNAVAILABLE");}}
    }
    public Prepared prepare(Map<String,Object> owner,Map<String,Object> stage,List<Map<String,Object>> ledger){
        try{return checked(owner,stage,ledger);}catch(JobException controlled){if(controlled.getCode().equals("AGENT_RESPONSE_REPLAY_UNAVAILABLE"))throw controlled;throw unavailable("CACHE_UNAVAILABLE");}catch(RuntimeException malformed){throw unavailable("CACHE_UNAVAILABLE");}
    }
    private Prepared checked(Map<String,Object> owner,Map<String,Object> stage,List<Map<String,Object>> ledger){
        JsonNode frozenSource=JSON.readTree(String.valueOf(owner.get("source_json")));String sourcePrefix="jobs/"+owner.get("job_id")+"/";
        for(String kind:List.of("transcript","analysis")){byte[] bytes=artifacts.readReplayFile(String.valueOf(owner.get(kind+"_key")),sourcePrefix,8*1024*1024);if(!digest(bytes).equals(owner.get(kind+"_hash")))throw unavailable("SOURCE_UNAVAILABLE");JsonNode document=STRICT.readTree(bytes);if(!ClipRequestService.canonicalJson(kind.equals("transcript")?document:document.path("utterances")).equals(ClipRequestService.canonicalJson(frozenSource.path("utterances")))||kind.equals("analysis")&&!ClipRequestService.canonicalJson(document.path("segments")).equals(ClipRequestService.canonicalJson(frozenSource.path("topics"))))throw unavailable("SOURCE_UNAVAILABLE");}
        if(ledger.isEmpty()||ledger.size()>2)throw unavailable("FIRST_ANSWER_UNSUPPORTED");String job=String.valueOf(owner.get("job_id")),stageId=String.valueOf(stage.get("id"));
        String stagePrefix="jobs/"+job+"/stages/"+stageId+"/",firstKey=String.valueOf(ledger.getFirst().get("response_key"));
        var tokenMatch=Pattern.compile(Pattern.quote(stagePrefix)+"attempts/1/([a-f0-9-]{36})/provider-responses/[a-f0-9]{64}\\.json").matcher(firstKey);
        if(!tokenMatch.matches())throw unavailable("CALLS_NOT_REPLAYABLE");String token=UUID.fromString(tokenMatch.group(1)).toString(),prefix=stagePrefix+"attempts/1/"+token+"/",directory=prefix+"agent-evidence-speech/";
        Reader read=new Reader();JsonNode state=read.json("state",directory+"state.json",prefix,4*1024*1024),plans=read.json("request_plans",directory+"request-plans.json",prefix,1024*1024),speech=read.json("speech_observation",directory+"speech-observation.json",prefix,1024*1024);
        String configHash=hash(state,"config_hash");if(!configHash.equals(plans.path("config_hash").asText())||!configHash.equals(speech.path("config_hash").asText())||!speech.path("status").asText().equals("READY"))throw unavailable("CONFIG_MISMATCH");
        JsonNode cached=array(state,"calls",ledger.size(),ledger.size()),requests=array(plans,"requests",ledger.size(),ledger.size());
        JsonNode trace=read.json("trace",prefix+"text-execution-trace.json",prefix,1024*1024);if(!job.equals(trace.path("job_id").asText())||!stageId.equals(trace.path("stage_id").asText()))throw unavailable("CACHE_UNAVAILABLE");
        var starts=new ArrayList<JsonNode>();var ended=new HashSet<String>();for(JsonNode event:array(trace,"calls",2,8)){
            if(!String.valueOf(owner.get("text_model_id")).equals(event.path("model_id").asText())||number(event,"attempt_number")!=1||number(event,"slot")!=0)throw unavailable("CALLS_NOT_REPLAYABLE");
            String logical=hash(event,"logical_hash");if(event.path("state").asText().equals("REQUESTING"))starts.add(event);else if(!event.path("state").asText().equals("SUCCEEDED")||!ended.add(logical))throw unavailable("CALLS_NOT_REPLAYABLE");
        }
        if(starts.size()!=ledger.size()||ended.size()!=ledger.size())throw unavailable("CALLS_NOT_REPLAYABLE");
        String endpoint=null;int maxAttempts=0;Set<String> logicals=new HashSet<>();
        for(JsonNode start:starts){String logical=hash(start,"logical_hash");if(!logicals.add(logical)||!ended.contains(logical))throw unavailable("CALLS_NOT_REPLAYABLE");
            JsonNode policy=read.json("retry_policy",stagePrefix+"text-retry-policies/"+logical+".json",stagePrefix,65536).path("policy");
            JsonNode profiles=array(policy,"profiles",1,1),profile=profiles.get(0);String url=text(profile,"endpoint",2048);URI uri=URI.create(url);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().endsWith("/chat/completions")||!profile.path("api_style").asText().equals("openai")||!profile.path("model").asText().equals(owner.get("text_model_id")))throw unavailable("CONFIG_MISMATCH");
            int attempts=(int)number(policy,"max_attempts");if(attempts<1||attempts>3||endpoint!=null&&(!endpoint.equals(url)||maxAttempts!=attempts))throw unavailable("CONFIG_MISMATCH");endpoint=url;maxAttempts=attempts;
        }
        JsonNode prepared=speech.path("prepared");if(!prepared.isObject()||!digest(pythonJson(prepared).getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(speech.path("prepared_sha256").asText()))throw unavailable("CACHE_UNAVAILABLE");
        JsonNode supplied=array(prepared.path("observation"),"utterances",0,4),suppliedIds=array(prepared.path("receipt"),"returned_utterance_ids",0,4);if(supplied.size()!=suppliedIds.size())throw unavailable("CACHE_UNAVAILABLE");
        for(int index=0;index<supplied.size();index++){JsonNode row=supplied.get(index);String id=suppliedIds.get(index).asText();JsonNode original=null;for(JsonNode sourceRow:frozenSource.path("utterances"))if(id.equals(sourceRow.path("utterance_id").asText()))original=sourceRow;if(original==null||!ClipRequestService.canonicalJson(original).equals(ClipRequestService.canonicalJson(row)))throw unavailable("SOURCE_UNAVAILABLE");}
        var frames=JSON.createArrayNode();var tools=JSON.createArrayNode();Long focus=AgentFocus.value(owner);byte[] firstJpeg=null;
        if(focus!=null){JsonNode focused=read.json("focus_observation",directory+"focus-observation.json",prefix,1024*1024);if(!configHash.equals(focused.path("config_hash").asText())||!focused.path("status").asText().equals("READY")||number(focused,"focus_timestamp_ms")!=focus)throw unavailable("CONFIG_MISMATCH");
            JsonNode frame=focused.path("frame");frames.add(frame.deepCopy());byte[] jpeg=read.bytes("frame",prefix+"visual-frames/"+text(frame,"frame_id",5)+".jpg",prefix,1024*1024);firstJpeg=jpeg;if(!digest(jpeg).equals(frame.path("sha256").asText())||jpeg.length!=number(frame,"bytes"))throw unavailable("CACHE_UNAVAILABLE");
            read.json("frame_cache",prefix+"visual-frame-cache.json",prefix,1024*1024);var tool=tools.addObject().put("step",1).put("tool_name","inspect_frames").put("origin","USER_FOCUS");tool.putObject("arguments").putArray("timestamps_ms").add(focus);tool.putArray("result_utterance_ids");tool.putArray("result_frame_ids").add(frame.path("frame_id").asText());
        }
        JsonNode lookup=prepared.path("receipt");var policyTool=tools.addObject().put("step",focus==null?1:2).put("origin","EVIDENCE_POLICY").put("tool_name",lookup.path("strategy").asText().equals("FOCUS_CONTEXT")?"read_context":"search_transcript");policyTool.set("arguments",prepared.path("arguments").deepCopy());policyTool.set("result_utterance_ids",lookup.path("returned_utterance_ids").deepCopy());policyTool.putArray("result_frame_ids");
        Map<String,JsonNode> wrappers=new HashMap<>();Map<String,Map<String,Object>> byResponse=new HashMap<>();
        for(var call:ledger){
            if(!"SUCCEEDED".equals(call.get("status"))||!"TEXT".equals(call.get("kind"))||!String.valueOf(owner.get("id")).equals(call.get("owner_id"))||!"AGENT_TURN".equals(call.get("owner_type"))||!stageId.equals(call.get("stage_id"))||!job.equals(call.get("job_id"))||!owner.get("text_model_id").equals(call.get("model_id"))||((Number)call.get("origin_attempt")).intValue()!=1||!(call.get("dispatch_attempt") instanceof Number dispatch)||dispatch.intValue()!=1||!ClipRequestService.sha(token).equals(call.get("origin_token_hash"))||!ClipRequestService.sha(token).equals(call.get("dispatch_token_hash")))throw unavailable("CALLS_NOT_REPLAYABLE");
            if(!("text-"+call.get("input_hash")).equals(call.get("operation_key"))||!ClipRequestService.sha(stageId+":"+call.get("operation_key")).equals(call.get("request_id")))throw unavailable("CALLS_NOT_REPLAYABLE");
            String key=String.valueOf(call.get("response_key"));if(!key.startsWith(prefix+"provider-responses/"))throw unavailable("CALLS_NOT_REPLAYABLE");byte[] bytes=read.bytes("provider_response",key,prefix,MAX_FILE);if(!digest(bytes).equals(call.get("response_hash")))throw unavailable("CACHE_UNAVAILABLE");JsonNode wrapper=STRICT.readTree(bytes);if(number(wrapper,"http_status")!=200||!wrapper.path("body_is_json").isBoolean()||!wrapper.path("body_is_json").booleanValue()||!wrapper.path("body").isObject())throw unavailable("CALLS_NOT_REPLAYABLE");String body=ClipRequestService.canonicalJson(wrapper.path("body"));if(wrappers.put(body,wrapper)!=null)throw unavailable("CALLS_NOT_REPLAYABLE");byResponse.put(body,call);
        }
        var frozenCalls=new ArrayList<Map<String,Object>>();JsonNode firstDecision=null,firstPlan=null;Map<String,Object> firstPaid=null;Set<Integer> attempts=new HashSet<>();
        for(JsonNode call:cached){int repair=(int)number(call,"attempt");if(number(call,"window_index")!=0||repair<0||repair>1||!attempts.add(repair)||!call.path("status").asText().equals("received")||call.path("provider_failed").asBoolean(true)||number(call,"http_status")!=200)throw unavailable("CALLS_NOT_REPLAYABLE");
            String name="window-0000-attempt-"+repair+".json";if(!name.equals(call.path("response_file").asText()))throw unavailable("CACHE_UNAVAILABLE");byte[] bytes=read.bytes("window",directory+name,prefix,8*1024*1024);if(!digest(bytes).equals(call.path("response_sha256").asText()))throw unavailable("CACHE_UNAVAILABLE");JsonNode window=STRICT.readTree(bytes);String body=ClipRequestService.canonicalJson(window);Map<String,Object> paid=byResponse.remove(body);if(paid==null)throw unavailable("CALLS_NOT_REPLAYABLE");
            boolean found=false;for(JsonNode plan:requests)if(number(plan,"window_index")==0&&number(plan,"attempt")==repair){hash(plan,"payload_sha256");found=true;if(repair==0)firstPlan=plan;}if(!found)throw unavailable("CACHE_UNAVAILABLE");
            var frozen=new TreeMap<String,Object>();for(String field:CALL_FIELDS)if(!Set.of("window_index","repair_attempt").contains(field))frozen.put(field,paid.get(field));frozen.put("window_index",0);frozen.put("repair_attempt",repair);frozenCalls.add(frozen);
            if(repair==0){JsonNode content=window.path("choices").path(0).path("message").path("content");if(!content.isString())throw unavailable("FIRST_ANSWER_UNSUPPORTED");firstDecision=STRICT.readTree(content.stringValue());firstPaid=paid;}
        }
        if(firstDecision==null||!byResponse.isEmpty())throw unavailable("FIRST_ANSWER_UNSUPPORTED");
        AgentReplayRequestBinding.verify(owner,frozenSource,prepared,frames,firstJpeg,firstPlan,firstPaid,configHash,endpoint,maxAttempts);
        Set<String> decisionFields=Set.of("type","answer_scope","answer_blocks","suggested_clip_instruction");var actual=new HashSet<>(firstDecision.propertyNames());if(!firstDecision.isObject()||!firstDecision.path("type").asText().equals("answer")||!decisionFields.containsAll(actual)||!actual.containsAll(Set.of("type","answer_scope","answer_blocks")))throw unavailable("FIRST_ANSWER_UNSUPPORTED");
        var original=JSON.createObjectNode().put("schema_version","1.4").put("evidence_mode","structured").put("evidence_policy",AgentSpeechLookup.POLICY).put("turn_id",String.valueOf(owner.get("id")));
        for(String field:List.of("session_id","job_id","video_id","source_hash","transcript_hash","question"))original.put(field,String.valueOf(owner.get(field)));if(focus!=null)original.put("focus_timestamp_ms",focus);
        original.set("speech_lookup",lookup.deepCopy());original.set("tool_calls",tools);original.set("visual_evidence",frames);original.put("answer_scope",firstDecision.path("answer_scope").asText());original.set("answer_blocks",firstDecision.path("answer_blocks").deepCopy());original.set("suggested_clip_instruction",firstDecision.has("suggested_clip_instruction")?firstDecision.path("suggested_clip_instruction").deepCopy():JSON.getNodeFactory().nullNode());
        original.putObject("provenance").put("text_model_id",String.valueOf(owner.get("text_model_id"))).put("prompt_version",AgentSpeechLookup.PROMPT).put("retrieval_mode",policyTool.path("tool_name").asText().equals("search_transcript")?"bm25":"none");
        assemble(original,JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances"));try{AgentAnswerValidator.validate(original,owner);}catch(RuntimeException unsupported){throw unavailable("FIRST_ANSWER_UNSUPPORTED");}
        read.files.sort(Comparator.comparing(file->String.valueOf(file.get("key"))));frozenCalls.sort(Comparator.comparingInt(call->((Number)call.get("repair_attempt")).intValue()));
        var descriptor=new TreeMap<String,Object>();descriptor.put("schema_version",1);descriptor.put("decoder_profile",AgentResponseReplayData.DECODER);descriptor.put("source_turn_id",owner.get("id"));descriptor.put("source_session_id",owner.get("session_id"));descriptor.put("source_stage_id",stageId);descriptor.put("source_attempt",1);descriptor.put("source_run_token",token);descriptor.put("source_request_hash",owner.get("request_hash"));descriptor.put("source_input_hash",stage.get("input_hash"));descriptor.put("source_config_hash",configHash);descriptor.put("original_max_text_calls",owner.get("max_text_calls"));descriptor.put("original_max_request_attempts",maxAttempts);descriptor.put("endpoint",endpoint);descriptor.put("files",read.files);descriptor.put("calls",frozenCalls);
        return new Prepared(JSON.valueToTree(descriptor),original);
    }
    static JsonNode proof(Map<String,Object> child,JsonNode descriptor){
        JsonNode first=descriptor.path("calls").get(0),window=null;String suffix="/agent-evidence-speech/window-0000-attempt-0.json";for(JsonNode file:descriptor.path("files"))if(file.path("kind").asText().equals("window")&&file.path("key").asText().endsWith(suffix))window=file;
        if(window==null)throw unavailable("CACHE_UNAVAILABLE");return JSON.valueToTree(Map.ofEntries(Map.entry("schema_version",1),Map.entry("data_kind","cached_agent_answer_replay"),Map.entry("turn_id",child.get("id")),Map.entry("source_turn_id",descriptor.path("source_turn_id").asText()),Map.entry("source_stage_id",descriptor.path("source_stage_id").asText()),Map.entry("source_attempt",1),Map.entry("snapshot_hash",child.get("replay_snapshot_hash")),Map.entry("source_request_id",first.path("request_id").asText()),Map.entry("source_response_hash",first.path("response_hash").asText()),Map.entry("source_window_sha256",window.path("sha256").asText()),Map.entry("decoder_profile",AgentResponseReplayData.DECODER),Map.entry("rebound_fields",List.of("turn_id")),Map.entry("provider_requests",0)));
    }
    private static void assemble(ObjectNode answer,JsonNode source){
        Map<String,String> labels=Map.of("SPEECH","原句依据","FRAME","画面观察","SAMPLED_CHANGE","前后采样","INFERENCE","推断","GENERAL","通用解释","INSUFFICIENT","证据不足");var sections=new ArrayList<String>();Set<String> originals=new HashSet<>();var visual=new TreeMap<String,List<String>>();
        for(JsonNode block:answer.path("answer_blocks")){String kind=block.path("kind").asText(),id=block.path("block_id").asText();String body="【"+labels.get(kind)+" "+id+"】\n"+block.path("text").asText();if(kind.equals("SAMPLED_CHANGE"))body+="\n（仅比较已取样画面，不证明采样间隙或因果。）";if(kind.equals("INFERENCE"))body+="\n（属于推断，不能当作讲者原话或已观察事实。）";sections.add(body);block.path("utterance_ids").forEach(ref->originals.add(ref.asText()));block.path("frame_ids").forEach(ref->visual.computeIfAbsent(ref.asText(),ignored->new ArrayList<>()).add(id));}
        answer.put("answer",String.join("\n\n",sections));var citations=answer.putArray("citations");for(JsonNode row:source)if(originals.contains(row.path("utterance_id").asText())){var citation=citations.addObject();for(String field:List.of("utterance_id","start_ms","end_ms","text"))citation.set(field,row.path(field).deepCopy());}var frames=answer.putArray("visual_citations");visual.forEach((id,blocks)->frames.addObject().put("frame_id",id).put("explanation","关联分项："+String.join("、",blocks)));
    }
    private static String digest(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    static String pythonJson(JsonNode value){String compact=ClipRequestService.canonicalJson(value);StringBuilder result=new StringBuilder();boolean string=false,escaped=false;for(int index=0;index<compact.length();index++){char c=compact.charAt(index);result.append(c);if(string){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')string=false;}else if(c=='"')string=true;else if(c==':'||c==',')result.append(' ');}return result.toString();}
    private static String hash(JsonNode node,String field){String value=text(node,field,64);if(!value.matches("[a-f0-9]{64}"))throw unavailable("CACHE_UNAVAILABLE");return value;}
    private static JsonNode array(JsonNode node,String field,int minimum,int maximum){JsonNode value=node.path(field);if(!value.isArray()||value.size()<minimum||value.size()>maximum)throw unavailable("CACHE_UNAVAILABLE");return value;}
    static JobException unavailable(String reason){return new JobException(HttpStatus.CONFLICT,"AGENT_RESPONSE_REPLAY_UNAVAILABLE",reason);}
}
