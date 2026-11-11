package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.JSON;
import static org.junit.jupiter.api.Assertions.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic complete sources, never a BM25 relevance or real-provider evaluation. */
class AgentSpeechLookupAnswerValidatorTests {
    private static JsonNode cases()throws Exception{return JSON.readTree(Files.readAllBytes(Path.of("../fixtures/agent-speech-lookup.synthetic.json"))).path("cases");}
    private static JsonNode fixture(String name)throws Exception{for(JsonNode test:cases())if(test.path("id").asText().equals(name))return test;throw new AssertionError(name);}
    private static Map<String,Object> owner(JsonNode test,JsonNode result){
        Map<String,Object> owner=new HashMap<>();for(String name:List.of("session_id","job_id","video_id","source_hash","transcript_hash","question"))owner.put(name,result.path(name).asText());
        owner.put("id",result.path("turn_id").asText());owner.put("text_model_id",result.path("provenance").path("text_model_id").asText());owner.put("visual_mode","frames");owner.put("evidence_mode","structured");owner.put("evidence_policy",AgentSpeechLookup.POLICY);owner.put("duration_ms",test.path("duration_ms").longValue());owner.put("source_json",JSON.writeValueAsString(Map.of("utterances",test.path("utterances"))));
        if(result.has("focus_timestamp_ms"))owner.put("focus_timestamp_ms",result.path("focus_timestamp_ms").longValue());return owner;
    }
    private static void invalid(JsonNode test,Consumer<ObjectNode> change){ObjectNode result=(ObjectNode)test.path("result").deepCopy();change.accept(result);var owner=owner(test,result);assertEquals("INVALID_AGENT_ANSWER",assertThrows(JobException.class,()->AgentAnswerValidator.validate(result,owner)).getCode());}

    @TestFactory Stream<DynamicTest> sharedSourcesPassFullReceiptLegacyProjectionAndReadOnlyNotes()throws Exception{
        List<DynamicTest> tests=new ArrayList<>();for(JsonNode test:cases())tests.add(DynamicTest.dynamicTest(test.path("id").asText(),()->{
            JsonNode result=test.path("result");String before=JSON.writeValueAsString(result);assertSame(result,AgentAnswerValidator.validate(result,owner(test,result)));
            assertEquals(test.path("expected_strategy"),result.path("speech_lookup").path("strategy"));assertEquals(test.path("expected_status"),result.path("speech_lookup").path("status"));
            JsonNode projection=AgentSpeechLookupAnswerValidator.noteProjection(result);assertEquals("1.3",projection.path("schema_version").asText());assertFalse(projection.has("speech_lookup"));assertEquals(before,JSON.writeValueAsString(result));
            var review=AgentEvidenceReview.fromVerifiedResult(result);assertNotNull(review);assertEquals(1,review.searchTranscriptCalls()+review.readContextCalls()-modelTextCalls(result));
            byte[] jpeg={(byte)255,(byte)216,1,2,3,(byte)255,(byte)217};String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));ObjectNode note=(ObjectNode)result.deepCopy();Map<String,byte[]> images=new HashMap<>();for(JsonNode frame:note.path("visual_evidence"))((ObjectNode)frame).put("sha256",sha).put("bytes",jpeg.length);for(JsonNode ref:note.path("visual_citations"))images.put(ref.path("frame_id").asText(),jpeg);
            String hash=ClipRequestService.sha(ClipRequestService.canonicalJson(note));var snapshot=new AgentNoteModels.Snapshot(note.path("turn_id").asText(),note.path("session_id").asText(),note.path("job_id").asText(),note.path("video_id").asText(),note.path("source_hash").asText(),note.path("transcript_hash").asText(),hash,"synthetic.mp4",test.path("duration_ms").longValue(),note,images);
            var document=AgentNoteRenderer.render(snapshot);assertTrue(document.markdown().contains("## 程序原句预查"));assertTrue(document.markdown().contains("EVIDENCE_POLICY"));assertTrue(document.markdown().contains(AgentEvidenceReview.LIMITATION_WITH_POLICY));assertTrue(document.packageMarkdown().startsWith(document.markdown()));
            JsonNode publicSource=JSON.readTree(document.sourceJson());assertEquals(AgentSpeechLookup.POLICY,publicSource.path("evidence_policy").asText());assertEquals(note.path("speech_lookup"),publicSource.path("speech_lookup"));assertEquals(hash,publicSource.path("result_hash").asText());assertFalse(publicSource.has("tool_calls"));
            ((ObjectNode)note.path("speech_lookup")).put("tool_step",4);String changed=ClipRequestService.sha(ClipRequestService.canonicalJson(note));var broken=new AgentNoteModels.Snapshot(snapshot.turnId(),snapshot.sessionId(),snapshot.jobId(),snapshot.videoId(),snapshot.sourceHash(),snapshot.transcriptHash(),changed,"synthetic.mp4",snapshot.durationMs(),note,images);assertEquals("AGENT_NOTE_SOURCE_INVALID",assertThrows(JobException.class,()->AgentNoteRenderer.render(broken)).getCode());
        }));assertEquals(15,tests.size());return tests.stream();
    }
    private static int modelTextCalls(JsonNode result){int count=0;for(JsonNode call:result.path("tool_calls"))if(call.path("origin").asText().equals("MODEL")&&Set.of("read_context","search_transcript").contains(call.path("tool_name").asText()))count++;return count;}

    @Test void focusedReceiptCannotChangeItsIdentitySourceStrategyOrPacking()throws Exception{
        JsonNode test=fixture("focus_zero_is_valid");
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(
            a->a.remove("speech_lookup"),a->a.put("evidence_policy","none"),a->a.put("schema_version","1.3"),a->((ObjectNode)a.path("provenance")).put("prompt_version",AgentEvidence.PROMPT),
            a->((ObjectNode)a.path("tool_calls").get(1)).put("origin","MODEL"),a->((ObjectNode)a.path("speech_lookup")).put("tool_step",1),a->((ObjectNode)a.path("speech_lookup")).put("anchor_distance_ms",1),
            a->((ObjectNode)a.path("tool_calls").get(1).path("arguments")).put("utterance_id","u2"),a->((ObjectNode)a.path("tool_calls").get(1).path("arguments")).put("before",0),
            a->((ObjectNode)a.path("speech_lookup")).put("candidate_utterance_count",0),a->((ObjectNode)a.path("speech_lookup")).put("status","EMPTY_RETURN"),a->((ObjectNode)a.path("speech_lookup")).put("query_truncated",true),
            a->((ObjectNode)a.path("speech_lookup")).putArray("returned_utterance_ids"),a->((ObjectNode)a.path("speech_lookup")).put("extra",true),a->((ObjectNode)a.path("provenance")).put("retrieval_mode","bm25_dense_rrf")))invalid(test,change);
    }

    @Test void searchReceiptCannotReplaceItsBoundedQuestionQueryOrAdvertiseUnobservedOriginals()throws Exception{
        JsonNode test=fixture("no_focus_local_search");for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(
            a->((ObjectNode)a.path("tool_calls").get(0).path("arguments")).put("query","another question"),a->((ObjectNode)a.path("tool_calls").get(0).path("arguments")).put("top_k",5),
            a->((ObjectNode)a.path("speech_lookup")).put("query_truncated",true),a->((ObjectNode)a.path("speech_lookup")).put("anchor_distance_ms",0),a->((ObjectNode)a.path("speech_lookup")).put("candidate_utterance_count",5),
            a->((ObjectNode)a.path("speech_lookup")).putArray("returned_utterance_ids").add("unknown"),a->((ObjectNode)a.path("tool_calls").get(0)).put("origin","MODEL"),a->a.withArray("tool_calls").add(a.path("tool_calls").get(0).deepCopy())))invalid(test,change);
        ObjectNode result=(ObjectNode)test.path("result").deepCopy();var owner=owner(test,result);assertEquals("INVALID_AGENT_ANSWER",assertThrows(JobException.class,()->AgentAnswerValidator.validate(null,owner)).getCode());
    }

    @Test void newQuestionValidationUsesExactJsTrimAndUtf16WithoutWeakeningLegacyText(){
        assertFalse(AgentSpeechLookup.validQuestion("\uFEFF\u00A0"));assertFalse(AgentSpeechLookup.validQuestion("x\u001cy"));assertFalse(AgentSpeechLookup.validQuestion("x\u0085y"));assertFalse(AgentSpeechLookup.validQuestion("x\uD800"));assertFalse(AgentSpeechLookup.validQuestion("\uDC00x"));
        assertTrue(AgentSpeechLookup.validQuestion("\uFEFF\u00A0 x\t\r\ny \uFEFF"));assertEquals("x\t\r\ny",AgentSpeechLookup.query("\uFEFF\u00A0 x\t\r\ny \uFEFF").text());
        assertEquals("a".repeat(299),AgentSpeechLookup.query("a".repeat(299)+"😀z").text());assertTrue(AgentSpeechLookup.query("a".repeat(299)+"😀z").truncated());
        assertEquals(300,AgentSpeechLookup.query("😀".repeat(150)).text().length());assertFalse(AgentSpeechLookup.query("😀".repeat(150)).truncated());assertEquals("x\u001cy",AgentSpeechLookup.query("x\u001cy").text());
        for(int c:List.of(9,10,11,12,13,32,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288,65279))assertEquals("x",AgentSpeechLookup.query((char)c+"x"+(char)c).text());
    }
}
