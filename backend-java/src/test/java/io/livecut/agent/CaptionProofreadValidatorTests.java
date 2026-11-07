package io.livecut.agent;

import static org.junit.jupiter.api.Assertions.*;
import static io.livecut.agent.AgentAnswerValidator.JSON;
import java.util.*;
import java.util.function.Consumer;
import io.livecut.captions.CaptionModels;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.ArtifactVerifier.SourcePart;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class CaptionProofreadValidatorTests {
    static final String EXPORT="11111111-1111-4111-8111-111111111111",CLIP="22222222-2222-4222-8222-222222222222";
    static Map<String,Object> owner(int count,String text){
        List<CaptionModels.Cue> cues=new ArrayList<>();var utterances=JSON.createArrayNode();
        for(int i=0;i<count;i++){String source=text+i;long start=i*1000L;cues.add(new CaptionModels.Cue("c"+i,0,"u"+i,start,start+1000,start,start+1000,start,start+1000,source,source,false,false,false));
            utterances.addObject().put("utterance_id","u"+i).put("start_ms",start).put("end_ms",start+1000).put("text",source).put("source_chunk_id","s0");}
        var source=new CaptionModels.Source("a".repeat(64),"b".repeat(64),"c".repeat(64),count*1000L,count*1000L,"SOURCE_EDL_SENTENCE_MS",List.of(new SourcePart(0,count*1000L)));
        String hash=sha(JSON.valueToTree(new CaptionModels.Content(cues)));
        var snapshot=new CaptionModels.Snapshot(EXPORT,CLIP,"合成校对成片","/api/exports/"+EXPORT+"/clips/"+CLIP+"/content",0,"DRAFT",List.of(),hash,source,cues);
        JsonNode caption=JSON.valueToTree(snapshot),frozen=JSON.valueToTree(Map.of("caption",caption,"caption_snapshot_hash",sha(caption),"utterances",utterances,"topics",List.of()));
        Map<String,Object> owner=new HashMap<>();owner.put("id","44444444-4444-4444-8444-444444444444");owner.put("session_id","33333333-3333-4333-8333-333333333333");
        owner.put("job_id","55555555-5555-4555-8555-555555555555");owner.put("video_id","66666666-6666-4666-8666-666666666666");owner.put("source_hash","d".repeat(64));owner.put("transcript_hash","c".repeat(64));owner.put("caption_export_id",EXPORT);owner.put("caption_clip_id",CLIP);owner.put("question",CaptionProofreadValidator.DEFAULT_INSTRUCTION);owner.put("text_model_id","qwen-plus");owner.put("source_json",frozen.toString());
        owner.put("max_text_calls",4);owner.put("caption_context_json",CaptionProofreadValidator.context(frozen,0).toString());return owner;
    }
    static ObjectNode result(Map<String,Object> owner){
        JsonNode frozen=CaptionProofreadValidator.frozen(owner),caption=frozen.path("caption"),context=CaptionProofreadValidator.storedContext(owner);
        var result=JSON.createObjectNode().put("schema_version","1.0");for(String key:List.of("session_id","job_id","video_id","source_hash","transcript_hash"))result.put(key,String.valueOf(owner.get(key)));
        result.put("turn_id",String.valueOf(owner.get("id"))).put("instruction",String.valueOf(owner.get("question"))).put("caption_snapshot_hash",frozen.path("caption_snapshot_hash").asText())
            .put("export_id",caption.path("export_id").asText()).put("clip_id",caption.path("clip_id").asText()).put("caption_revision",caption.path("revision").longValue()).put("caption_content_hash",caption.path("content_hash").asText());
        result.set("coverage",JSON.valueToTree(CaptionProofreadValidator.batch(context)));
        var suggestion=result.putArray("suggestions").addObject().put("cue_id",context.path("cues").get(0).path("id").asText()).put("before",context.path("cues").get(0).path("text").asText()).put("after","合成纠错建议").put("decision","CORRECTION").put("reason","这是测试建议，不代表已核实语义");suggestion.putArray("evidence_utterance_ids").add(context.path("cues").get(0).path("source_utterance_id").asText());
        result.putArray("tool_calls");result.putObject("provenance").put("text_model_id","qwen-plus").put("prompt_version","caption-proofread-v1").put("retrieval_mode","none");return result;
    }
    static String sha(JsonNode value){return ClipRequestService.sha(ClipRequestService.canonicalJson(value));}
    private static void rejects(Map<String,Object> owner,Consumer<ObjectNode> change){var result=result(owner);change.accept(result);assertThrows(JobException.class,()->CaptionProofreadValidator.validate(result,owner));}

    @Test void completeBatchesHaveExactCoverageAndCanReachTheFinalCue(){
        var owner=owner(45,"合成字幕");var frozen=CaptionProofreadValidator.frozen(owner);
        var first=CaptionProofreadValidator.batch(CaptionProofreadValidator.context(frozen,0));assertEquals(20,first.count());assertEquals(20,first.nextOffset());
        var last=CaptionProofreadValidator.context(frozen,40);assertEquals(5,last.path("count").intValue());assertTrue(last.path("next_offset").isNull());assertEquals("c44",last.path("cues").get(4).path("id").asText());
        assertThrows(JobException.class,()->CaptionProofreadValidator.context(frozen,45));
    }
    @Test void utf16BoundKeepsWholeCuesRatherThanSplittingEmojiOrSourceText(){
        var owner=owner(3,"😀".repeat(900));var context=CaptionProofreadValidator.storedContext(owner);
        assertEquals(1,context.path("count").intValue());assertEquals("😀".repeat(900)+"0",context.path("cues").get(0).path("text").asText());assertEquals(1,context.path("next_offset").intValue());
    }
    @Test void aVeryLongNeighborIsSkippedWholeAndCannotBecomeInitialEvidence(){
        var owner=owner(1,"目标原句");var frozen=(ObjectNode)JSON.readTree(String.valueOf(owner.get("source_json")));
        ((tools.jackson.databind.node.ArrayNode)frozen.path("utterances")).addObject().put("utterance_id","neighbor").put("start_ms",1000).put("end_ms",2000).put("text","长".repeat(20000));
        owner.put("source_json",frozen.toString());owner.put("caption_context_json",CaptionProofreadValidator.context(frozen,0).toString());
        assertEquals(List.of("u0"),JSON.convertValue(CaptionProofreadValidator.storedContext(owner).path("context_utterance_ids"),List.class));
        rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).putArray("evidence_utterance_ids").add("neighbor"));
    }
    @Test void validCorrectionAndUncertainItemsRemainOnlySuggestions(){
        var owner=owner(3,"原句");var value=result(owner);var uncertain=((tools.jackson.databind.node.ArrayNode)value.path("suggestions")).addObject().put("cue_id","c1").put("before","原句1").putNull("after").put("decision","UNCERTAIN").put("reason","请回听确认专名");uncertain.putArray("evidence_utterance_ids").add("u1");
        assertEquals(value,CaptionProofreadValidator.validate(value,owner));assertEquals("原句0",CaptionProofreadValidator.frozen(owner).path("caption").path("cues").get(0).path("text").asText());
    }
    @Test void foreignSourceRevisionInstructionOrCoverageCannotBePublished(){
        var owner=owner(4,"字幕");for(String key:List.of("session_id","turn_id","job_id","video_id","source_hash","transcript_hash","caption_snapshot_hash","export_id","clip_id","caption_content_hash","instruction"))rejects(owner,r->r.put(key,"changed"));
        rejects(owner,r->r.put("caption_revision",1));rejects(owner,r->((ObjectNode)r.path("coverage")).put("count",3));rejects(owner,r->((ObjectNode)r.path("coverage")).putArray("cue_ids").add("c0"));
    }
    @Test void inventedOrDuplicatedTargetsBeforeTextAndUnsupportedFieldsAreRejected(){
        var owner=owner(4,"字幕");rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).put("cue_id","outside"));
        rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).put("before","模型重写原文"));
        rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).put("start_ms",123));
        rejects(owner,r->((tools.jackson.databind.node.ArrayNode)r.path("suggestions")).add(r.path("suggestions").get(0)));
        rejects(owner,r->r.put("auto_confirm",true));
    }
    @Test void correctionTextCannotBeEmptyUnchangedTooLongOrAttachedToUncertainty(){
        var owner=owner(2,"字幕");for(String after:List.of(""," ","\u00a0","\ufeff","字幕0","字".repeat(2001),"坏\u0000文本","坏\u001f文本","\ud800","\udc00"))rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).put("after",after));
        rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).put("decision","UNCERTAIN"));
        rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).put("reason"," "));
        var allowed=result(owner);((ObjectNode)allowed.path("suggestions").get(0)).put("after","正常😀\t换行\r\n文本");assertDoesNotThrow(()->CaptionProofreadValidator.validate(allowed,owner));
    }
    @Test void evidenceMustActuallyHaveBeenProvidedAndRetrievalModeMustDescribeTools(){
        var owner=owner(40,"字幕");rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).putArray("evidence_utterance_ids"));
        rejects(owner,r->((ObjectNode)r.path("suggestions").get(0)).putArray("evidence_utterance_ids").add("u39"));
        rejects(owner,r->((ObjectNode)r.path("provenance")).put("retrieval_mode","bm25"));
        rejects(owner,r->{var call=((tools.jackson.databind.node.ArrayNode)r.path("tool_calls")).addObject().put("step",1).put("tool_name","list_topics");call.putObject("arguments").put("offset",0).put("limit",1);call.putArray("result_utterance_ids");});
    }
    @Test void sourceOrStoredBatchTamperingDoesNotChangeTheBoundBeforeText(){
        var owner=owner(3,"字幕");var context=(ObjectNode)JSON.readTree(String.valueOf(owner.get("caption_context_json")));((ObjectNode)context.path("cues").get(0)).put("text","篡改");owner.put("caption_context_json",context.toString());assertThrows(JobException.class,()->CaptionProofreadValidator.storedContext(owner));
        var second=owner(3,"字幕");var frozen=(ObjectNode)JSON.readTree(String.valueOf(second.get("source_json")));((ObjectNode)frozen.path("caption").path("cues").get(0)).put("source_text","篡改来源");second.put("source_json",frozen.toString());assertThrows(JobException.class,()->CaptionProofreadValidator.frozen(second));
    }
    @Test void independentSuggestionsRemainAvailableWhenOnlyASelectedSubsetFitsTheWholeDocument(){
        var owner=owner(1051,"a".repeat(1990));var frozen=(ObjectNode)JSON.readTree(String.valueOf(owner.get("source_json")));var caption=(ObjectNode)frozen.path("caption");
        var cues=caption.path("cues");((ObjectNode)cues.get(0)).put("text","aa");((ObjectNode)cues.get(1)).put("text","bb");
        int bytes=0;for(JsonNode cue:cues)bytes+=cue.path("text").asText().length();int remaining=2*1024*1024-2-bytes;
        for(int index=2;index<cues.size()&&remaining>0;index++){var cue=(ObjectNode)cues.get(index);String before=cue.path("text").asText();int extra=Math.min(2000-before.length(),remaining);cue.put("text",before+"x".repeat(extra));remaining-=extra;}
        assertEquals(0,remaining);caption.put("content_hash",sha(JSON.valueToTree(Map.of("cues",cues))));frozen.put("caption_snapshot_hash",sha(caption));owner.put("source_json",frozen.toString());owner.put("caption_context_json",CaptionProofreadValidator.context(frozen,0).toString());
        var value=result(owner);((ObjectNode)value.path("suggestions").get(0)).put("after","aaa");
        var second=((tools.jackson.databind.node.ArrayNode)value.path("suggestions")).addObject().put("cue_id","c1").put("before","bb").put("after","bbbb").put("decision","CORRECTION").put("reason","独立候选，用户只采纳其中一条");second.putArray("evidence_utterance_ids").add("u1");
        assertEquals(2,CaptionProofreadValidator.storedContext(owner).path("count").intValue());
        assertDoesNotThrow(()->CaptionProofreadValidator.validate(value,owner));
        // Base has two bytes remaining; selecting +1 is legal while selecting both (+3) is not.
        int baseBytes=0;for(JsonNode cue:cues)baseBytes+=cue.path("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(2*1024*1024-2,baseBytes);assertTrue(baseBytes+1<=2*1024*1024);assertTrue(baseBytes+3>2*1024*1024);
    }
}
