package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.JSON;
import static org.junit.jupiter.api.Assertions.*;
import io.livecut.clips.ClipRequestService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared synthetic cases describe recorded observations, never successful searches or answer quality. */
class AgentEvidenceReviewTests {
    private static JsonNode fixtures()throws Exception{return JSON.readTree(Files.readAllBytes(Path.of("../fixtures/agent-evidence-review.synthetic.json"))).path("cases");}
    private static Map<String,Object> owner(JsonNode test){
        JsonNode result=test.path("result");var owner=new HashMap<String,Object>();
        for(String field:List.of("session_id","job_id","video_id","source_hash","transcript_hash","question"))owner.put(field,result.path(field).asText());
        owner.put("id",result.path("turn_id").asText());owner.put("text_model_id",result.path("provenance").path("text_model_id").asText());
        owner.put("visual_mode","frames");owner.put("evidence_mode","structured");owner.put("duration_ms",test.path("duration_ms").longValue());
        owner.put("source_json",JSON.writeValueAsString(Map.of("utterances",test.path("utterances"))));
        if(result.has("focus_timestamp_ms"))owner.put("focus_timestamp_ms",result.path("focus_timestamp_ms").longValue());return owner;
    }

    @TestFactory Stream<DynamicTest> sharedCasesRemainLegalAndMatchTheExactCrossLanguageProjection()throws Exception{
        var tests=new java.util.ArrayList<DynamicTest>();
        for(JsonNode test:fixtures())tests.add(DynamicTest.dynamicTest(test.path("id").asText(),()->{
            JsonNode result=test.path("result");String before=JSON.writeValueAsString(result);
            assertSame(result,AgentAnswerValidator.validate(result,owner(test)));
            var review=AgentEvidenceReview.fromVerifiedResult(result);
            assertNotNull(review);assertEquals(test.path("expected_review"),JSON.valueToTree(review));
            assertEquals(before,JSON.writeValueAsString(result));assertFalse(review.statusText().isBlank());
            assertEquals(6,review.detailLines().size());assertTrue(AgentEvidenceReview.LIMITATION.contains("空返回不能解释为查无匹配"));
        }));
        assertEquals(15,tests.size());return tests.stream();
    }

    @Test void oldVersionsHaveNoReviewAndTheHelperDoesNotClaimToValidateUntrustedInputs(){
        for(String version:List.of("1.0","1.1","1.2"))assertNull(AgentEvidenceReview.fromVerifiedResult(JSON.createObjectNode().put("schema_version",version)));
        assertThrows(IllegalArgumentException.class,()->AgentEvidenceReview.fromVerifiedResult(null));
        assertThrows(IllegalArgumentException.class,()->AgentEvidenceReview.fromVerifiedResult(JSON.createObjectNode().put("schema_version","1.3")));
    }

    @Test void notesAddAnIndependentReviewWithoutMutatingAnswerHashOrSourceProjection()throws Exception{
        byte[] jpeg={(byte)255,(byte)216,1,2,3,(byte)255,(byte)217};String imageHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
        for(JsonNode test:fixtures()){
            ObjectNode result=(ObjectNode)test.path("result").deepCopy();Map<String,byte[]> images=new HashMap<>();
            for(JsonNode raw:result.path("visual_evidence")){var frame=(ObjectNode)raw;frame.put("sha256",imageHash).put("bytes",jpeg.length);}
            for(JsonNode citation:result.path("visual_citations"))images.put(citation.path("frame_id").asText(),jpeg);
            String original=JSON.writeValueAsString(result),hash=ClipRequestService.sha(ClipRequestService.canonicalJson(result));
            var snapshot=new AgentNoteModels.Snapshot(result.path("turn_id").asText(),result.path("session_id").asText(),result.path("job_id").asText(),result.path("video_id").asText(),
                result.path("source_hash").asText(),result.path("transcript_hash").asText(),hash,"synthetic.mp4",test.path("duration_ms").longValue(),result,images);
            var document=AgentNoteRenderer.render(snapshot);var review=AgentEvidenceReview.fromVerifiedResult(result);
            assertTrue(document.markdown().contains("## 证据核查摘要\n\n"+review.statusText()));
            for(String line:review.detailLines())assertTrue(document.markdown().contains("- "+line+"\n"));
            assertTrue(document.markdown().contains(AgentEvidenceReview.LIMITATION));assertTrue(document.packageMarkdown().startsWith(document.markdown()));
            JsonNode source=JSON.readTree(document.sourceJson());assertEquals(hash,source.path("result_hash").asText());
            assertEquals(result.path("answer_blocks"),source.path("answer_blocks"));assertFalse(source.has("evidence_review"));assertFalse(source.has("speech_status"));assertFalse(source.has("tool_calls"));
            assertEquals(original,JSON.writeValueAsString(result));assertEquals(hash,ClipRequestService.sha(ClipRequestService.canonicalJson(snapshot.result())));
        }
    }
}
