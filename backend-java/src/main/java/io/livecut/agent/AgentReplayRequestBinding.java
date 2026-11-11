package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.JSON;
import io.livecut.clips.ClipRequestService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Reconstructs the original primary/slot-zero HTTP identity; cache hashes alone cannot attest paid input. */
final class AgentReplayRequestBinding {
    private AgentReplayRequestBinding() {}
    private static final String SPEECH=resource("evidence-speech-v1.system.txt","ee96cdd05ac516088544f8ecb0f6e3a996bee76d012fb7f7ee3981a91dff00a0");
    private static final String FOCUS=resource("evidence-focus-v1.system.txt","2542630dfe534b301e066e585729036b85bc35388939a74bff75ce039bbfd9ac");
    record InputProof(String configHash,String payloadSha256,String inputHash) {}
    static void verify(Map<String,Object> owner,JsonNode source,JsonNode prepared,JsonNode frames,byte[] jpeg,JsonNode plan,Map<String,Object> call,String configHash,String endpoint,int maxAttempts){
        if(call==null)throw AgentResponseReplayVerifier.unavailable("CALLS_NOT_REPLAYABLE");InputProof proof=reconstruct(owner,source,prepared,frames,jpeg,plan,endpoint,maxAttempts);
        if(!proof.configHash().equals(configHash)||!proof.payloadSha256().equals(plan.path("payload_sha256").asText()))throw AgentResponseReplayVerifier.unavailable("CONFIG_MISMATCH");
        if(!proof.inputHash().equals(call.get("input_hash")))throw AgentResponseReplayVerifier.unavailable("CALLS_NOT_REPLAYABLE");
    }
    static InputProof reconstruct(Map<String,Object> owner,JsonNode source,JsonNode prepared,JsonNode frames,byte[] jpeg,JsonNode plan,String endpoint,int maxAttempts){
        if(plan==null)throw AgentResponseReplayVerifier.unavailable("CALLS_NOT_REPLAYABLE");Long focus=AgentFocus.value(owner);int maximum=((Number)owner.get("max_text_calls")).intValue();
        int expectedSteps=Math.min(4-(focus==null?0:1)-1,Math.max(0,maximum-maxAttempts));
        if(plan.path("remaining_calls").intValue()!=maximum||plan.path("remaining_tool_steps").intValue()!=expectedSteps)throw AgentResponseReplayVerifier.unavailable("CONFIG_MISMATCH");
        String system=SPEECH+(focus==null?"":FOCUS+"\n本轮明确附加位置："+focus+"毫秒。");JsonNode history=JSON.readTree(String.valueOf(owner.get("history_json")));
        var topics=JSON.createArrayNode();for(JsonNode topic:source.path("topics")){var safe=topics.addObject();for(String field:List.of("segment_id","title","summary","start_utterance_id","end_utterance_id")){String text=topic.path(field).asText();int count=text.codePointCount(0,text.length());safe.put(field,text.substring(0,text.offsetByCodePoints(0,Math.min(800,count))));}}
        var config=new TreeMap<String,Object>();config.put("prompt",AgentSpeechLookup.PROMPT);config.put("source_hash",owner.get("source_hash"));config.put("transcript_hash",owner.get("transcript_hash"));config.put("duration_ms",owner.get("duration_ms"));config.put("question",owner.get("question"));config.put("model",owner.get("text_model_id"));config.put("endpoint",endpoint);config.put("session",owner.get("session_id"));config.put("turn",owner.get("id"));config.put("job",owner.get("job_id"));config.put("video",owner.get("video_id"));config.put("utterances",source.path("utterances"));config.put("topics",topics);config.put("history",history);config.put("max_steps",4);config.put("embedding_model",null);config.put("dense",false);config.put("max_request_attempts",maxAttempts);config.put("max_calls",maximum);config.put("central_budget",true);config.put("system_sha256",ClipRequestService.sha(system));config.put("evidence_mode","structured");config.put("evidence_policy",AgentSpeechLookup.POLICY);config.put("worker_protocol",2);if(focus!=null)config.put("focus_timestamp_ms",focus);
        String configHash=ClipRequestService.sha(AgentResponseReplayVerifier.pythonJson(JSON.valueToTree(config)));
        var observations=JSON.createArrayNode();if(focus!=null){if(frames.size()!=1||jpeg==null)throw AgentResponseReplayVerifier.unavailable("CACHE_UNAVAILABLE");var observation=observations.addObject().put("tool_name","inspect_frames").put("origin","USER_FOCUS");observation.putObject("arguments").putArray("timestamps_ms").add(focus);var result=observation.putObject("result").put("sampling","keyframes").put("audio_included",false);result.putArray("utterances");result.set("frames",frames.deepCopy());}
        var policy=observations.addObject().put("tool_name",prepared.path("receipt").path("strategy").asText().equals("FOCUS_CONTEXT")?"read_context":"search_transcript").put("origin","EVIDENCE_POLICY");policy.set("arguments",prepared.path("arguments").deepCopy());policy.set("result",prepared.path("observation").deepCopy());
        var payload=new TreeMap<String,Object>();payload.put("video_question",owner.get("question"));payload.put("conversation",history);payload.put("observations",observations);payload.put("video_info",Map.of("duration_ms",owner.get("duration_ms"),"topic_count",topics.size(),"utterance_count",source.path("utterances").size()));payload.put("remaining_tool_steps",plan.path("remaining_tool_steps"));payload.put("remaining_model_calls",plan.path("remaining_calls"));payload.put("sampling_notice","Pictures are bounded samples, not continuous audio/video understanding.");payload.put("evidence_policy",AgentSpeechLookup.POLICY);payload.put("speech_lookup",prepared.path("receipt"));if(focus!=null)payload.put("focus_timestamp_ms",focus);
        var content=JSON.createArrayNode();content.addObject().put("type","text").put("text",AgentResponseReplayVerifier.pythonJson(JSON.valueToTree(payload)));
        if(focus!=null){JsonNode frame=frames.get(0);content.addObject().put("type","text").put("text","FRAME "+frame.path("frame_id").asText()+" actual_timestamp_ms="+frame.path("timestamp_ms").asText()+" requested_ms="+frame.path("requested_ms").asText());content.addObject().put("type","image_url").putObject("image_url").put("url","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(jpeg));}
        var messages=JSON.createArrayNode();messages.addObject().put("role","system").put("content",system);messages.addObject().put("role","user").set("content",content);
        String payloadHash=ClipRequestService.sha(AgentResponseReplayVerifier.pythonJson(messages));
        URI uri=URI.create(endpoint);var body=JSON.createObjectNode().put("model",String.valueOf(owner.get("text_model_id"))).put("stream",false).put("temperature",0);body.set("messages",messages);body.putObject("response_format").put("type","json_object");String host=uri.getHost().toLowerCase(Locale.ROOT);if(host.equals("api.deepseek.com"))body.putObject("thinking").put("type","disabled");
        var address=new TreeMap<String,Object>();address.put("scheme","https");address.put("host",host);address.put("port",uri.getPort()==-1||uri.getPort()==443?null:uri.getPort());address.put("path",uri.getPath());
        String paid=ClipRequestService.sha(ClipRequestService.canonicalJson(JSON.valueToTree(Map.of("endpoint",address,"body",body))));return new InputProof(configHash,payloadHash,paid);
    }
    private static String resource(String name,String expected){try(var input=AgentReplayRequestBinding.class.getResourceAsStream("/agent-replay/"+name)){if(input==null)throw new IllegalStateException("Frozen replay prompt is absent");byte[] bytes=input.readNBytes(65537);if(bytes.length>65536)throw new IllegalStateException("Frozen replay prompt exceeds its bound");String text=new String(bytes,StandardCharsets.UTF_8);if(!ClipRequestService.sha(text).equals(expected))throw new IllegalStateException("Frozen replay prompt changed");return text;}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
}
