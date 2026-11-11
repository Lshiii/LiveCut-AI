package io.livecut.agent;

import static org.junit.jupiter.api.Assertions.*;
import static io.livecut.agent.AgentAnswerValidator.JSON;
import io.livecut.agent.AgentNoteModels.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Pure synthetic snapshots; publishing/source/JPEG decode proofs belong to the service tests. */
class AgentNoteRendererTests {
    private static final String TURN="11111111-1111-4111-8111-111111111111",SESSION="22222222-2222-4222-8222-222222222222",JOB="33333333-3333-4333-8333-333333333333",VIDEO="44444444-4444-4444-8444-444444444444";
    private static final byte[] JPEG={(byte)255,(byte)216,1,2,3,(byte)255,(byte)217};
    private static ObjectNode answer(String version){
        var result=JSON.createObjectNode().put("schema_version",version).put("turn_id",TURN).put("session_id",SESSION).put("job_id",JOB).put("video_id",VIDEO)
            .put("source_hash","a".repeat(64)).put("transcript_hash","b".repeat(64)).put("question","为什么分两条路径？").put("answer_scope","VIDEO")
            .put("answer","先看原句。\n\n1. **有效**：保留 `旧工具结果`。\n2. **过期**：缩小下一次 Prefill 输入。");
        result.putObject("provenance").put("text_model_id","synthetic-model").put("prompt_version",version.equals("1.0")?"video-agent-v1":version.equals("1.1")?"video-agent-visual-v1":"video-agent-visual-focus-v1").put("retrieval_mode","none");
        result.putArray("citations");result.putArray("tool_calls");result.putNull("suggested_clip_instruction");
        if(!version.equals("1.0")){result.putArray("visual_evidence");result.putArray("visual_citations");}
        if(version.equals("1.2"))result.put("focus_timestamp_ms",0);
        return result;
    }
    private static Snapshot snapshot(ObjectNode answer,Map<String,byte[]> images){return snapshot(answer,images,"lecture.mp4");}
    private static Snapshot snapshot(ObjectNode answer,Map<String,byte[]> images,String filename){return new Snapshot(TURN,SESSION,JOB,VIDEO,"a".repeat(64),"b".repeat(64),ClipRequestService.sha(ClipRequestService.canonicalJson(answer)),filename,8_000_000,answer,images);}
    private static void citation(ObjectNode answer,String id,long start,long end,String text){answer.withArray("citations").addObject().put("utterance_id",id).put("start_ms",start).put("end_ms",end).put("text",text);}
    private static void frame(ObjectNode answer,String id,long at,boolean cited){answer.withArray("visual_evidence").addObject().put("frame_id",id).put("requested_ms",at).put("timestamp_ms",at).put("source_pts",at).put("source_time_base","1/1000").put("sha256",sha(JPEG)).put("bytes",JPEG.length).put("width",320).put("height",180);if(cited)answer.withArray("visual_citations").addObject().put("frame_id",id).put("explanation","这是实际引用的流程图。");}
    private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception error){throw new AssertionError(error);}}
    private static String code(Runnable action){return assertThrows(JobException.class,action::run).getCode();}

    @Test void textNoteRetainsQuestionParagraphsListsEmphasisAndFullGlobalAsrText(){
        var result=answer("1.0");citation(result,"u1",3_600_001,3_601_999,"完整原句 😀\n第二行\n");var document=AgentNoteRenderer.render(snapshot(result,Map.of()));
        assertTrue(document.markdown().contains("为什么分两条路径？"));assertTrue(document.markdown().contains("1. **有效**"));assertTrue(document.markdown().contains("2. **过期**"));assertTrue(document.markdown().contains("`旧工具结果`"));
        assertTrue(document.markdown().contains("01:00:00.001 → 01:00:01.999"));assertTrue(document.markdown().contains("3600001–3601999 ms"));assertTrue(document.markdown().contains("完整原句 😀  \n第二行  \n"));
        assertEquals(document.markdown(),document.packageMarkdown());assertFalse(document.markdown().contains("## 证据核查摘要"));var source=JSON.readTree(document.sourceJson());assertEquals("完整原句 😀\n第二行\n",source.path("citations").get(0).path("text").asText());assertTrue(source.path("visual_evidence").isEmpty());
    }
    @Test void bothVisualVersionsPackageOnlyReferencedFramesUsingFixedRelativePaths(){
        for(String version:List.of("1.1","1.2")){
            var result=answer(version);frame(result,"f0001",0,false);frame(result,"f0003",162533,true);var document=AgentNoteRenderer.render(snapshot(result,Map.of("f0003",JPEG)));
            assertTrue(document.markdown().contains("本轮没有原句引用"));assertTrue(document.markdown().contains("图片文件包含在 ZIP 中"));assertFalse(document.markdown().contains("!["));assertFalse(document.markdown().contains("## 证据核查摘要"));
            assertTrue(document.packageMarkdown().startsWith(document.markdown()));assertTrue(document.packageMarkdown().contains("![f0003 · 00:02:42.533](images/f0003.jpg)"));assertFalse(document.packageMarkdown().contains("images/f0001.jpg"));
            var source=JSON.readTree(document.sourceJson());assertEquals(1,source.path("visual_evidence").size());assertEquals("f0003",source.path("visual_evidence").get(0).path("frame_id").asText());assertEquals(version.equals("1.2"),source.has("focus_timestamp_ms"));
            if(version.equals("1.2"))assertTrue(document.markdown().contains("用户定位提示：00:00:00.000（0 ms）"));
        }
    }
    @Test void generalAndInsufficientLabelsSurviveWithoutBecomingVideoFacts(){
        for(String scope:List.of("GENERAL","INSUFFICIENT")){var result=answer("1.0").put("answer_scope",scope);var document=AgentNoteRenderer.render(snapshot(result,Map.of()));assertTrue(document.markdown().contains(scope));assertTrue(document.markdown().contains(scope.equals("GENERAL")?"不能当作视频中已经说过的话":"证据不足"));assertEquals(scope,JSON.readTree(document.sourceJson()).path("answer_scope").asText());}
    }
    @Test void sourceProjectionExcludesPrivateOrUncitedDataAndHasNoClock(){
        var result=answer("1.1");frame(result,"f0001",0,false);frame(result,"f0032",1200,true);result.put("provider_response","private-response").put("run_token","private-token").put("storage_key","private-storage");((ObjectNode)result.path("provenance")).put("api_key","private-key");
        var snapshot=snapshot(result,Map.of("f0032",JPEG));var one=AgentNoteRenderer.render(snapshot);var two=AgentNoteRenderer.render(snapshot);assertEquals(one.markdown(),two.markdown());assertArrayEquals(one.sourceJson(),two.sourceJson());
        String json=new String(one.sourceJson(),StandardCharsets.UTF_8);for(String secret:List.of("private-response","private-token","private-storage","private-key","tool_calls","\"answer\"","created_at","updated_at"))assertFalse(json.contains(secret),secret);
        var source=JSON.readTree(json);assertEquals(Set.of("schema_version","turn_id","session_id","job_id","video_id","source_hash","transcript_hash","result_hash","source_filename","duration_ms","question","answer_scope","provenance","citations","visual_evidence","visual_citations"),source.propertyNames());assertEquals(Set.of("text_model_id","prompt_version","retrieval_mode"),source.path("provenance").propertyNames());
    }
    @Test void maliciousMarkdownHtmlLinksAndImageSyntaxRemainLiteralExceptProgramImages(){
        var result=answer("1.1").put("answer","<script>alert(1)</script>\n![tracking](https://evil.invalid/pixel)\n[open](javascript:alert(1))\n<https://evil.invalid>\nwww.evil.invalid\nuser@evil.invalid\n```html\n**安全 `代码`**");frame(result,"f0001",0,true);
        result.put("question","# fake heading\n![question](../../secret)");var document=AgentNoteRenderer.render(snapshot(result,Map.of("f0001",JPEG),"../../![name](https://evil.invalid/x).mp4"));
        assertFalse(document.markdown().contains("<script>"));assertTrue(document.markdown().contains("&lt;script&gt;"));assertTrue(document.markdown().contains("`![tracking](https://evil.invalid/pixel)`"));assertTrue(document.markdown().contains("`www.evil.invalid`"));assertTrue(document.markdown().contains("`user@evil.invalid`"));assertTrue(document.markdown().contains("\\[open\\]\\(javascript\\:alert\\(1\\)\\)"));
        assertTrue(document.markdown().contains("**安全 `代码`**"));assertTrue(document.markdown().contains("\\# fake heading"));assertEquals(1,document.packageMarkdown().lines().filter(line->line.startsWith("![")).count());assertTrue(document.packageMarkdown().contains("](images/f0001.jpg)"));assertFalse(document.packageMarkdown().contains("](../../"));
    }
    @Test void unknownAndEscapedGrammarDoesNotBecomeActiveFormattingOrRenumberItems(){
        var result=answer("1.0").put("answer","### 小节\n03. 原来是03\n8) 原来是8\n  - 嵌套语法原样\n\n\\**不是强调**\n***三重星号***\n*斜体*\n> 引文\n---\n3.14 数值");var text=AgentNoteRenderer.render(snapshot(result,Map.of())).markdown();
        assertTrue(text.contains("##### 小节"));assertTrue(text.contains("- 03\\. 原来是03"));assertTrue(text.contains("- 8\\) 原来是8"));assertTrue(text.contains("\\- 嵌套语法原样"));assertTrue(text.contains("\\*斜体\\*"));assertTrue(text.contains("&gt; 引文"));assertTrue(text.contains("3\\.14 数值"));assertFalse(text.contains("\n---\n"));
    }
    @Test void frameIdsExtraMissingOrChangedBytesFailClosed(){
        var result=answer("1.1");frame(result,"f0001",0,true);
        assertEquals("AGENT_NOTE_SOURCE_INVALID",code(()->AgentNoteRenderer.render(snapshot(result,Map.of()))));assertEquals("AGENT_NOTE_SOURCE_INVALID",code(()->AgentNoteRenderer.render(snapshot(result,Map.of("f0001",JPEG,"f0002",JPEG)))));
        assertEquals("AGENT_NOTE_SOURCE_INVALID",code(()->AgentNoteRenderer.render(snapshot(result,Map.of("f0001",new byte[]{1,2,3,4})))));
        ((ObjectNode)result.path("visual_citations").get(0)).put("frame_id","../bad");assertEquals("AGENT_NOTE_SOURCE_INVALID",code(()->AgentNoteRenderer.render(snapshot(result,Map.of("../bad",JPEG)))));
    }
    @Test void mismatchedResultIdentityHashAndMalformedUnicodeAreRejected(){
        var result=answer("1.0");var valid=snapshot(result,Map.of());assertEquals("AGENT_NOTE_SOURCE_INVALID",code(()->AgentNoteRenderer.render(new Snapshot(TURN,SESSION,JOB,VIDEO,"a".repeat(64),"b".repeat(64),"0".repeat(64),"x.mp4",8000000,result,Map.of()))));
        result.put("turn_id",SESSION);assertEquals("AGENT_NOTE_SOURCE_INVALID",code(()->AgentNoteRenderer.render(snapshot(result,Map.of()))));var malformed=answer("1.0").put("answer","bad\ud800");var broken=new Snapshot(TURN,SESSION,JOB,VIDEO,"a".repeat(64),"b".repeat(64),valid.resultHash(),"x",8000000,malformed,Map.of());assertEquals("AGENT_NOTE_SOURCE_INVALID",code(()->AgentNoteRenderer.render(broken)));assertEquals("AGENT_NOTE_SOURCE_INVALID",code(()->AgentNoteRenderer.render(new Snapshot(null,SESSION,JOB,VIDEO,"a".repeat(64),"b".repeat(64),valid.resultHash(),"x",8000000,valid.result(),Map.of()))));
    }
    @Test void wholeDocumentsRejectUtf8OverflowInsteadOfTruncatingSourceText(){
        var result=answer("1.0");for(int i=0;i<24;i++)citation(result,"u"+i,i*1000,i*1000+999,"长".repeat(40000));var snapshot=snapshot(result,Map.of());assertEquals("AGENT_NOTE_TOO_LARGE",code(()->AgentNoteRenderer.render(snapshot)));
        var escaping=answer("1.0");for(int i=0;i<24;i++)citation(escaping,"u"+i,i*1000,i*1000+999,"&".repeat(20000));var escaped=snapshot(escaping,Map.of());assertEquals("AGENT_NOTE_TOO_LARGE",code(()->AgentNoteRenderer.render(escaped)));
    }
}
