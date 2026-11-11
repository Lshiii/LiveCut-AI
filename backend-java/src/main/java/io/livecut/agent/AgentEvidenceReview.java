package io.livecut.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Pure projection of an already verified structured result, never a source or semantic validator. */
public final class AgentEvidenceReview {
    private AgentEvidenceReview() {}
    public static final String LIMITATION_SCREEN="画面文字查询的状态见独立记录；其他工具轨迹的空返回不能证明全片不存在。OCR 文字不等于讲者原句，来源核验不证明语义正确。";
    public static final String LIMITATION="本摘要仅由已有工具轨迹和引用派生，不新增检索，不判断回答正确性。工具轨迹未记录可区分的执行结果，空返回不能解释为查无匹配。";
    public static final String LIMITATION_WITH_POLICY="本摘要仅由已有工具轨迹和引用派生，不新增检索，不判断回答正确性。程序预查的状态见独立记录；其他工具轨迹的空返回不能解释为查无匹配。";

    public enum SpeechStatus { SPEECH_CITED, SPEECH_NOT_CITED, NO_UTTERANCES_RETURNED, TOPIC_ANCHORS_ONLY, NOT_REQUESTED }

    public record Review(
            @JsonProperty("speech_status") SpeechStatus speechStatus,
            @JsonProperty("search_transcript_calls") int searchTranscriptCalls,
            @JsonProperty("read_context_calls") int readContextCalls,
            @JsonProperty("list_topics_calls") int listTopicsCalls,
            @JsonProperty("returned_utterance_count") int returnedUtteranceCount,
            @JsonProperty("search_context_utterance_count") int searchContextUtteranceCount,
            @JsonProperty("topic_anchor_utterance_count") int topicAnchorUtteranceCount,
            @JsonProperty("citation_count") int citationCount,
            @JsonProperty("speech_block_count") int speechBlockCount,
            @JsonProperty("speech_utterance_count") int speechUtteranceCount,
            @JsonProperty("inference_utterance_count") int inferenceUtteranceCount,
            @JsonProperty("user_focus_tool_calls") int userFocusToolCalls,
            @JsonProperty("model_visual_tool_calls") int modelVisualToolCalls,
            @JsonProperty("user_focus_frame_count") int userFocusFrameCount,
            @JsonProperty("model_frame_count") int modelFrameCount,
            @JsonProperty("observed_frame_count") int observedFrameCount,
            @JsonProperty("visual_citation_count") int visualCitationCount,
            @JsonProperty("sampled_change_block_count") int sampledChangeBlockCount,
            @JsonProperty("insufficient_block_count") int insufficientBlockCount) {
        public String statusText(){
            return switch(speechStatus){
                case NOT_REQUESTED->"本轮未请求原句检索或上下文。";
                case TOPIC_ANCHORS_ONLY->"原句相关轨迹仅来自话题工具，未请求原句检索或上下文；返回话题锚点 "+topicAnchorUtteranceCount+" 条。";
                case NO_UTTERANCES_RETURNED->"已有原句检索或上下文工具轨迹，但这些轨迹未记录可用原句；不能据此判断无匹配或全片不存在。";
                case SPEECH_NOT_CITED->"本轮已取得原句，但没有讲者原句分项；这不表示相关问题已回答。";
                case SPEECH_CITED->"本轮含讲者原句分项；可核对原句来源，但不证明文字准确回应问题或构成语义支持。";
            };
        }

        /** All text is program-owned; model strings are deliberately absent. */
        public List<String> detailLines(){
            return List.of(
                "原句工具轨迹：检索 "+searchTranscriptCalls+" 次，上下文 "+readContextCalls+" 次，话题 "+listTopicsCalls+" 次。",
                "返回原句（去重）：共 "+returnedUtteranceCount+" 条；检索/上下文 "+searchContextUtteranceCount+" 条，话题锚点 "+topicAnchorUtteranceCount+" 条。两类原句可重叠，不能相加。",
                "回答原句引用（去重）：顶层 "+citationCount+" 条；原句依据分项 "+speechBlockCount+" 项、引用 "+speechUtteranceCount+" 条；推断分项引用 "+inferenceUtteranceCount+" 条。推断引用不等于讲者原句分项。",
                "画面工具轨迹：用户定位 "+userFocusToolCalls+" 次、返回 "+userFocusFrameCount+" 张；模型选图 "+modelVisualToolCalls+" 次、返回 "+modelFrameCount+" 张。各来源帧可重叠，不能相加。",
                "实际画面（去重）：观察 "+observedFrameCount+" 张，引用 "+visualCitationCount+" 张。",
                "分项：前后采样 "+sampledChangeBlockCount+" 项，证据不足 "+insufficientBlockCount+" 项。"
            );
        }
    }

    /** Old versions have no derived review. Callers must first verify result identity and provenance. */
    public static Review fromVerifiedResult(JsonNode result){
        if(result==null||!result.isObject())throw new IllegalArgumentException("A verified result is required");
        if(!Set.of("1.3","1.4","1.5").contains(result.path("schema_version").asText()))return null;
        if(!"structured".equals(result.path("evidence_mode").asText()))throw new IllegalArgumentException("A verified structured result is required");
        int searches=0,contexts=0,topics=0,focusCalls=0,modelCalls=0,speechBlocks=0,changes=0,insufficient=0;
        Set<String> returned=new HashSet<>(),searchContext=new HashSet<>(),topicAnchors=new HashSet<>(),focusFrames=new HashSet<>(),modelFrames=new HashSet<>(),speech=new HashSet<>(),inference=new HashSet<>();
        for(JsonNode call:array(result,"tool_calls")){
            Set<String> utterances=ids(call,"result_utterance_ids");returned.addAll(utterances);
            switch(call.path("tool_name").asText()){
                case "search_transcript"->{searches++;searchContext.addAll(utterances);}
                case "read_context"->{contexts++;searchContext.addAll(utterances);}
                case "list_topics"->{topics++;topicAnchors.addAll(utterances);}
                case "inspect_frames","inspect_clip","search_screen_text"->{
                    Set<String> frames=ids(call,"result_frame_ids");
                    if("USER_FOCUS".equals(call.path("origin").asText())){focusCalls++;focusFrames.addAll(frames);}
                    else {modelCalls++;modelFrames.addAll(frames);}
                }
                default->throw new IllegalArgumentException("Unverified tool name");
            }
        }
        for(JsonNode block:array(result,"answer_blocks")){
            switch(block.path("kind").asText()){
                case "SPEECH"->{speechBlocks++;speech.addAll(ids(block,"utterance_ids"));}
                case "INFERENCE"->inference.addAll(ids(block,"utterance_ids"));
                case "SAMPLED_CHANGE"->changes++;
                case "INSUFFICIENT"->insufficient++;
                case "FRAME","GENERAL"->{ }
                default->throw new IllegalArgumentException("Unverified block kind");
            }
        }
        SpeechStatus status=speechBlocks>0?SpeechStatus.SPEECH_CITED:!searchContext.isEmpty()?SpeechStatus.SPEECH_NOT_CITED:
            searches+contexts>0?SpeechStatus.NO_UTTERANCES_RETURNED:topics>0?SpeechStatus.TOPIC_ANCHORS_ONLY:SpeechStatus.NOT_REQUESTED;
        return new Review(status,searches,contexts,topics,returned.size(),searchContext.size(),topicAnchors.size(),
            objectIds(result,"citations","utterance_id").size(),speechBlocks,speech.size(),inference.size(),focusCalls,modelCalls,
            focusFrames.size(),modelFrames.size(),objectIds(result,"visual_evidence","frame_id").size(),objectIds(result,"visual_citations","frame_id").size(),changes,insufficient);
    }

    private static JsonNode array(JsonNode node,String name){JsonNode value=node.path(name);if(!value.isArray())throw new IllegalArgumentException("A verified array is required: "+name);return value;}
    private static Set<String> ids(JsonNode node,String name){Set<String> result=new HashSet<>();for(JsonNode id:array(node,name)){if(!id.isString())throw new IllegalArgumentException("A verified ID is required");result.add(id.stringValue());}return result;}
    private static Set<String> objectIds(JsonNode node,String name,String field){Set<String> result=new HashSet<>();for(JsonNode item:array(node,name)){JsonNode id=item.path(field);if(!id.isString())throw new IllegalArgumentException("A verified ID is required");result.add(id.stringValue());}return result;}
}
