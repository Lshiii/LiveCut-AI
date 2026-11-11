package io.livecut.agent;

import static org.junit.jupiter.api.Assertions.*;
import static io.livecut.agent.AgentAnswerValidator.JSON;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared synthetic wire fixtures and adversarial source/modality/temporal cases; no semantic-quality claim. */
class AgentEvidenceAnswerValidatorTests {
    private static JsonNode fixture(boolean focus)throws Exception{return JSON.readTree(Files.readAllBytes(Path.of("../fixtures/agent-evidence"+(focus?"-focus":"")+".synthetic.json")));}
    private static ObjectNode answer()throws Exception{return (ObjectNode)fixture(false).path("result").deepCopy();}
    private static Map<String,Object> owner(JsonNode answer)throws Exception{
        var owner=new HashMap<String,Object>();for(String field:List.of("session_id","job_id","video_id","source_hash","transcript_hash","question"))owner.put(field,answer.path(field).asText());
        owner.put("id",answer.path("turn_id").asText());owner.put("text_model_id",answer.path("provenance").path("text_model_id").asText());owner.put("visual_mode","frames");owner.put("evidence_mode","structured");owner.put("duration_ms",4000L);
        owner.put("source_json",JSON.writeValueAsString(Map.of("utterances",fixture(false).path("utterances"))));if(answer.has("focus_timestamp_ms"))owner.put("focus_timestamp_ms",answer.path("focus_timestamp_ms").longValue());return owner;
    }
    private static void valid(ObjectNode answer)throws Exception{assertSame(answer,AgentAnswerValidator.validate(answer,owner(answer)));}
    private static void invalid(ObjectNode answer)throws Exception{var owner=owner(answer);assertEquals("INVALID_AGENT_ANSWER",assertThrows(JobException.class,()->AgentAnswerValidator.validate(answer,owner)).getCode());}
    private static ObjectNode block(ObjectNode answer,int index){return (ObjectNode)answer.path("answer_blocks").get(index);}
    private static void replaceText(ObjectNode answer,int index,String text){String before=block(answer,index).path("text").asText();block(answer,index).put("text",text);answer.put("answer",answer.path("answer").asText().replace(before,text));}
    @Test void bothSharedSixKindFixturesPassWithoutChangingTheirBytesOrFields()throws Exception{for(boolean focus:List.of(false,true)){var a=(ObjectNode)fixture(focus).path("result").deepCopy();String before=JSON.writeValueAsString(a);valid(a);assertEquals(before,JSON.writeValueAsString(a));}}
    @Test void blockAssemblyUsesAsciiCanonicalIdsUnderArabicDefaultLocaleAndRestoresLocale()throws Exception{
        Locale original=Locale.getDefault(),format=Locale.getDefault(Locale.Category.FORMAT),display=Locale.getDefault(Locale.Category.DISPLAY);
        try{
            Locale.setDefault(Locale.forLanguageTag("ar"));
            for(boolean focus:List.of(false,true)){
                var a=(ObjectNode)fixture(focus).path("result").deepCopy();String before=JSON.writeValueAsString(a);valid(a);assertEquals(before,JSON.writeValueAsString(a));
                var legacy=a.deepCopy();legacy.remove(List.of("answer_blocks","evidence_mode"));legacy.put("schema_version",focus?"1.2":"1.1");((ObjectNode)legacy.path("provenance")).put("prompt_version",focus?"video-agent-visual-focus-v1":"video-agent-visual-v1");var oldOwner=owner(legacy);oldOwner.put("evidence_mode","none");assertDoesNotThrow(()->AgentAnswerValidator.validate(legacy,oldOwner));
                block(a,0).put("block_id","b\u0660\u0661");invalid(a);
                a=(ObjectNode)fixture(focus).path("result").deepCopy();((ObjectNode)a.path("visual_evidence").get(0)).put("frame_id","f\u0660\u0660\u0660\u0661");invalid(a);
                ((ObjectNode)legacy.path("visual_evidence").get(0)).put("frame_id","f\u0660\u0660\u0660\u0661");assertThrows(JobException.class,()->AgentAnswerValidator.validate(legacy,oldOwner));
            }
        }finally{Locale.setDefault(original);Locale.setDefault(Locale.Category.FORMAT,format);Locale.setDefault(Locale.Category.DISPLAY,display);}
        assertEquals(original,Locale.getDefault());assertEquals(format,Locale.getDefault(Locale.Category.FORMAT));assertEquals(display,Locale.getDefault(Locale.Category.DISPLAY));
    }
    @Test void structuredTopLevelNeverProjectsAwayMissingExtraOrWrongProtocolFields()throws Exception{
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->a.put("extra","ignored"),a->a.remove("answer_blocks"),a->a.remove("evidence_mode"),a->a.put("evidence_mode","none"),a->a.put("schema_version","1.2"),a->a.put("schema_version",1.3),a->((ObjectNode)a.path("provenance")).put("prompt_version","video-agent-visual-v1"))){var a=answer();change.accept(a);invalid(a);}
        var a=answer();var old=owner(a);old.put("evidence_mode","none");assertThrows(JobException.class,()->AgentAnswerValidator.validate(a,old));
    }
    @Test void blockIdsCountsKindsAndExactFieldSetsAreBounded()throws Exception{
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->a.putArray("answer_blocks"),a->block(a,0).put("block_id","b02"),a->block(a,0).put("kind","VISUAL"),a->block(a,0).put("extra",true),a->block(a,0).remove("text"),a->{while(a.path("answer_blocks").size()<9)a.withArray("answer_blocks").add(a.path("answer_blocks").get(0).deepCopy());})){var a=answer();change.accept(a);invalid(a);}
    }
    @Test void modalityCannotUpgradeAnEmptyOrCrossModalBlockIntoObservedEvidence()throws Exception{
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->block(a,0).putArray("utterance_ids"),a->block(a,0).putArray("frame_ids").add("f0001"),a->block(a,1).putArray("frame_ids"),a->block(a,1).putArray("utterance_ids").add("u1"),a->{block(a,3).putArray("utterance_ids");block(a,3).putArray("frame_ids");},a->block(a,4).putArray("frame_ids").add("f0001"),a->block(a,5).putArray("utterance_ids").add("u1"))){var a=answer();change.accept(a);invalid(a);}
    }
    @Test void blockIdsMustBeObservedUniqueSourceOrderedAndWithinPerBlockBudgets()throws Exception{
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->block(a,0).putArray("utterance_ids").add("not-observed"),a->block(a,0).withArray("utterance_ids").add("u1"),a->block(a,1).putArray("frame_ids").add("f0032"),a->block(a,1).putArray("frame_ids").add("f0001").add("f0001"),a->block(a,1).putArray("frame_ids").add("f0002").add("f0001"),a->{for(int i=0;i<9;i++)block(a,0).withArray("utterance_ids").add("u"+(i+2));},a->{for(int i=0;i<5;i++)block(a,1).withArray("frame_ids").add("f"+i);})){var a=answer();change.accept(a);invalid(a);}
        var a=answer();((ObjectNode)a.path("tool_calls").get(0)).putArray("result_utterance_ids");invalid(a);
    }
    @Test void scopeCannotTreatOnlyInferenceAsVideoOrMixGeneralAndInsufficient()throws Exception{
        for(String scope:List.of("GENERAL","INSUFFICIENT","UNKNOWN")){var a=answer().put("answer_scope",scope);invalid(a);}
        var a=answer();var inference=block(a,3).deepCopy().put("block_id","b01");a.putArray("answer_blocks").add(inference);a.put("answer","【推断 b01】\n"+inference.path("text").asText()+"\n（属于推断，不能当作讲者原话或已观察事实。）");invalid(a);
    }
    @Test void bodyAndBothCitationUnionsAreRecomputedExactly()throws Exception{
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->a.put("answer",a.path("answer").asText()+"\n"),a->a.put("answer","另附一段没有分项依据的结论"),a->a.putArray("citations"),a->((ObjectNode)a.path("citations").get(0)).put("text","改写原句"),a->a.putArray("visual_citations"),a->((ObjectNode)a.path("visual_citations").get(0)).put("explanation","模型自行写的引用解释"),a->a.withArray("visual_citations").add(a.path("visual_citations").get(0).deepCopy()))){var a=answer();change.accept(a);invalid(a);}
    }
    @Test void changeNeedsExactlyTwoDifferentFramesFromOneSuccessfulClipBatch()throws Exception{
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(a->block(a,2).putArray("frame_ids").add("f0001"),a->block(a,2).putArray("frame_ids").add("f0001").add("f0001"),a->block(a,2).putArray("frame_ids").add("f0002").add("f0001"),a->{var tool=(ObjectNode)a.path("tool_calls").get(1);tool.put("tool_name","inspect_frames");tool.putObject("arguments").putArray("timestamps_ms").add(1000).add(1500);})) {var a=answer();change.accept(a);invalid(a);}
    }
    @Test void crossBatchFramesCannotImpersonateOneSampledChange()throws Exception{
        var a=answer();var tools=a.withArray("tool_calls");var first=(ObjectNode)tools.get(1);first.put("tool_name","inspect_frames");first.putObject("arguments").putArray("timestamps_ms").add(1000);first.putArray("result_frame_ids").add("f0001");var last=first.deepCopy().put("step",3);last.putObject("arguments").putArray("timestamps_ms").add(1500);last.putArray("result_frame_ids").add("f0002");tools.add(last);invalid(a);
    }
    @Test void changeOrderUsesExactRationalPtsEvenWhenDisplayMillisecondsAreEqual()throws Exception{
        var a=answer();for(int i=0;i<2;i++)((ObjectNode)a.path("visual_evidence").get(i)).put("timestamp_ms",1500).put("source_pts",15001+i).put("source_time_base","1/10000");valid(a);
        ((ObjectNode)a.path("visual_evidence").get(0)).put("source_pts",15002);((ObjectNode)a.path("visual_evidence").get(1)).put("source_pts",15001);invalid(a);
    }
    @Test void changeMayUseFrameIdsOppositeToTheirTimeOrderWhenOneBatchActuallyReusesThem()throws Exception{
        var a=answer();((ObjectNode)a.path("visual_evidence").get(0)).put("requested_ms",1500).put("timestamp_ms",1500).put("source_pts",1500);((ObjectNode)a.path("visual_evidence").get(1)).put("requested_ms",1000).put("timestamp_ms",1000).put("source_pts",1000);
        var clip=((ObjectNode)a.path("tool_calls").get(1)).deepCopy().put("step",3);clip.putArray("result_frame_ids").add("f0002").add("f0001");var prime=(ObjectNode)a.path("tool_calls").get(1);prime.put("tool_name","inspect_frames");prime.putObject("arguments").putArray("timestamps_ms").add(1500);prime.putArray("result_frame_ids").add("f0001");a.withArray("tool_calls").add(clip);block(a,2).putArray("frame_ids").add("f0002").add("f0001");valid(a);
    }
    @Test void textKeepsWhitespaceAndAllowsTabCrLfButRejectsControlsAndMalformedUnicode()throws Exception{
        var a=answer();replaceText(a,0," \t原文\r\n保留首尾空白 ");valid(a);
        for(String whitespace:List.of("\u00a0","\u2007","\u202f","\ufeff","\t\r\n")){a=answer();replaceText(a,0,whitespace);invalid(a);}
        a=answer();replaceText(a,0,"\u00a0\ufeff原文\u2007\u202f");valid(a);
        for(String value:List.of("\u0000","\u000b","\u007f","\u0085","\ud800","\udc00","   ","😀".repeat(501))){a=answer();replaceText(a,0,"测试"+value);if(value.equals("   "))replaceText(a,0,value);invalid(a);}
        a=answer();replaceText(a,0,"😀".repeat(500));valid(a);
    }
    @Test void generalAndInsufficientCanAnswerWithoutInventingSourceAndTotalUtf16BudgetApplies()throws Exception{
        for(String scope:List.of("GENERAL","INSUFFICIENT")){var a=answer().put("answer_scope",scope);String label=scope.equals("GENERAL")?"通用解释":"证据不足";a.putArray("answer_blocks").addObject().put("block_id","b01").put("kind",scope).put("text","明确范围").putArray("utterance_ids");block(a,0).putArray("frame_ids");a.putArray("citations");a.putArray("visual_citations");a.put("answer","【"+label+" b01】\n明确范围");valid(a);}
        var a=answer().put("answer_scope","GENERAL");var blocks=a.putArray("answer_blocks");List<String> body=new ArrayList<>();for(int i=1;i<=8;i++){String id="b%02d".formatted(i);var b=blocks.addObject().put("block_id",id).put("kind","GENERAL").put("text","字".repeat(1000));b.putArray("utterance_ids");b.putArray("frame_ids");body.add("【通用解释 "+id+"】\n"+"字".repeat(1000));}a.putArray("citations");a.putArray("visual_citations");a.put("answer",String.join("\n\n",body));invalid(a);
    }
    @Test void focusedProjectionStillRequiresTheOriginalUserObservationAndExactFocus()throws Exception{
        var a=(ObjectNode)fixture(true).path("result").deepCopy();var owner=owner(a);((ObjectNode)a.path("tool_calls").get(0)).put("origin","MODEL");assertEquals("INVALID_AGENT_ANSWER",assertThrows(JobException.class,()->AgentAnswerValidator.validate(a,owner)).getCode());
        var changed=(ObjectNode)fixture(true).path("result").deepCopy();changed.put("focus_timestamp_ms",1);owner.put("focus_timestamp_ms",0L);assertThrows(JobException.class,()->AgentAnswerValidator.validate(changed,owner));
    }
    @Test void structuredNotesKeepAllSixLabelsAndRejectResignedIndependentBodyTampering()throws Exception{
        byte[] jpeg={(byte)255,(byte)216,1,2,3,(byte)255,(byte)217};String imageHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(jpeg));
        for(boolean focus:List.of(false,true)){
            var a=(ObjectNode)fixture(focus).path("result").deepCopy();Map<String,byte[]> images=new TreeMap<>();for(JsonNode raw:a.path("visual_evidence")){var frame=(ObjectNode)raw;frame.put("sha256",imageHash).put("bytes",jpeg.length);images.put(frame.path("frame_id").asText(),jpeg);}
            var snapshot=note(a,images);var document=AgentNoteRenderer.render(snapshot);for(String label:List.of("原句依据","画面观察","前后采样","推断","通用解释","证据不足"))assertTrue(document.markdown().contains("【"+label+" "));
            assertTrue(document.markdown().contains("仅比较已取样画面，不证明采样间隙或因果。"));assertTrue(document.markdown().contains("属于推断，不能当作讲者原话或已观察事实。"));var source=JSON.readTree(document.sourceJson());assertEquals(a.path("answer_blocks"),source.path("answer_blocks"));assertEquals("structured",source.path("evidence_mode").asText());assertEquals(focus,source.has("focus_timestamp_ms"));assertTrue(document.packageMarkdown().contains("images/f0001.jpg"));
            a.put("answer",a.path("answer").asText()+"\n无依据结论");var tampered=note(a,images);assertEquals("AGENT_NOTE_SOURCE_INVALID",assertThrows(JobException.class,()->AgentNoteRenderer.render(tampered)).getCode());
        }
    }
    private static AgentNoteModels.Snapshot note(ObjectNode result,Map<String,byte[]> images){return new AgentNoteModels.Snapshot(result.path("turn_id").asText(),result.path("session_id").asText(),result.path("job_id").asText(),result.path("video_id").asText(),result.path("source_hash").asText(),result.path("transcript_hash").asText(),io.livecut.clips.ClipRequestService.sha(io.livecut.clips.ClipRequestService.canonicalJson(result)),"synthetic.mp4",4000,result,images);}
}
