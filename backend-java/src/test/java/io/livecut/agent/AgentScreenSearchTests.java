package io.livecut.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Shared public protocol vectors; these are synthetic records, not published JPEG/file proofs. */
class AgentScreenSearchTests {
    private static JsonNode fixture()throws Exception{return AgentAnswerValidator.JSON.readTree(Files.readAllBytes(Path.of("../fixtures/agent-screen-search.synthetic.json")));}
    @Test void sharedLiteralQueriesAndIndependentCanonicalHash()throws Exception{
        JsonNode all=fixture();assertEquals(20,all.path("query_cases").size());assertEquals(all.path("screen_source").path("index_hash").asText(),AgentScreenSearch.indexHash(all.path("rows"),all.path("source_hash").asText(),all.path("screen_source")));
        for(JsonNode item:all.path("query_cases")){
            if(!item.path("expected_valid").booleanValue()){assertThrows(RuntimeException.class,()->AgentScreenSearch.lookup(all.path("rows"),item.path("arguments")),item.path("case_id").asText());continue;}
            var result=AgentScreenSearch.lookup(all.path("rows"),item.path("arguments"));assertEquals(item.path("expected_total_match_count").intValue(),result.totalMatchCount(),item.path("case_id").asText());
            assertEquals(AgentAnswerValidator.JSON.convertValue(item.path("expected_frame_ids"),List.class),result.hits().stream().map(hit->hit.path("ocr_frame_id").asText()).toList(),item.path("case_id").asText());
        }
    }
    @Test void allTwentyFiveSharedCompleteAnswersWithFullInMemorySource()throws Exception{
        JsonNode all=fixture();assertEquals(30,all.path("cases").size());for(JsonNode item:all.path("cases")){
            Runnable validate=()->{Map<String,Object> owner=owner(all,item);AgentAnswerValidator.validate(item.path("result"),owner);AgentScreenSearchAnswerValidator.verifySource(item.path("result"),new AgentScreenSource.Prepared(AgentAnswerValidator.JSON.readTree((String)owner.get("screen_source_json")),item.path("rows"),"synthetic-no-db"));};
            if(item.path("expected_valid").booleanValue())assertDoesNotThrow(validate::run,item.path("case_id").asText());else assertThrows(RuntimeException.class,validate::run,item.path("case_id").asText());
        }
    }
    @Test void policyIsDistinctAndKeepsLegacyProtocolSelection(){
        var owner=new HashMap<String,Object>(Map.of("visual_mode","frames","evidence_mode","structured","evidence_policy",AgentScreenSearch.POLICY));assertEquals(4,AgentSpeechLookup.requiredProtocol(owner));assertFalse(AgentSpeechLookup.enabled(owner));
        owner.put("evidence_policy",AgentSpeechLookup.POLICY);assertEquals(2,AgentSpeechLookup.requiredProtocol(owner));owner.put("evidence_policy","none");assertEquals(1,AgentSpeechLookup.requiredProtocol(owner));
    }
    @Test void indexDigestDistinguishesRawControlsFromLiteralBackslashSequences()throws Exception{JsonNode all=fixture();var rows=all.path("rows").deepCopy();((tools.jackson.databind.node.ObjectNode)rows.get(0)).put("observed_text","\u001B");String raw=AgentScreenSearch.indexHash(rows,all.path("source_hash").asText(),all.path("screen_source"));((tools.jackson.databind.node.ObjectNode)rows.get(0)).put("observed_text","\\u001B");assertNotEquals(raw,AgentScreenSearch.indexHash(rows,all.path("source_hash").asText(),all.path("screen_source")));}
    static Map<String,Object> owner(JsonNode all,JsonNode item){
        JsonNode answer=item.path("result");var owner=new HashMap<String,Object>();for(String field:List.of("session_id","job_id","video_id","source_hash","transcript_hash","question"))owner.put(field,answer.path(field).asText());
        owner.put("id",answer.path("turn_id").asText());owner.put("duration_ms",item.path("duration_ms").longValue());owner.put("text_model_id",answer.path("provenance").path("text_model_id").asText());owner.put("visual_mode","frames");owner.put("evidence_mode","structured");owner.put("evidence_policy",AgentScreenSearch.POLICY);if(answer.has("focus_timestamp_ms"))owner.put("focus_timestamp_ms",answer.path("focus_timestamp_ms").longValue());
        owner.put("source_json",AgentAnswerValidator.JSON.writeValueAsString(Map.of("utterances",all.path("utterances"),"topics",List.of())));
        var context=AgentAnswerValidator.JSON.createObjectNode().put("schema_version",1);context.putObject("segment");context.putObject("analysis");context.putObject("ocr_config");context.set("screen_source",item.path("screen_source"));owner.put("screen_source_json",AgentAnswerValidator.JSON.writeValueAsString(context));return owner;
    }
}
