package io.livecut.agent;

import io.livecut.clips.ClipRequestModels;
import io.livecut.jobs.JobException;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Frozen input and constraints for a one-shot, read-only edit suggestion. */
final class PaperCutSuggestion {
    static final int PROTOCOL = 8;
    static final int MAX_CALLS = 2;
    static final String KIND = "PAPER_CUT";
    static final String ARTIFACT = "paper_cut_suggestion";
    private static final Set<String> REQUIRED = Set.of("base_revision","plan_hash","output_id","instruction","rows");

    private PaperCutSuggestion() {}
    static boolean isPaper(Map<String,Object> owner) { return KIND.equals(owner.get("kind")); }
    static JobException invalid(String message) { return new JobException(HttpStatus.BAD_REQUEST,"INVALID_PAPER_CUT",message); }
    static JobException changed() { return new JobException(HttpStatus.CONFLICT,"PAPER_CUT_SOURCE_CHANGED","Save the current plan or reopen the candidate before requesting suggestions"); }
    static JsonNode stored(Map<String,Object> owner) {
        if (!isPaper(owner)) throw new JobException(HttpStatus.NOT_FOUND,"PAPER_CUT_NOT_FOUND","Suggestion not found");
        return AgentAnswerValidator.JSON.readTree(String.valueOf(owner.get("source_json")));
    }
    static void validateInput(JsonNode input) {
        if (input==null || !input.isObject() || !input.propertyNames().containsAll(REQUIRED)
                || input.propertyNames().stream().anyMatch(n -> !REQUIRED.contains(n) && !n.equals("target_ms"))
                || !AgentSpeechLookup.validQuestion(input.path("instruction").asString(""))
                || !input.path("instruction").isString() || input.path("instruction").stringValue().length()>1000
                || !input.path("plan_hash").asString("").matches("[a-f0-9]{64}")
                || !input.path("output_id").isString() || input.path("output_id").stringValue().length()>128
                || !input.path("base_revision").isIntegralNumber() || !input.path("base_revision").canConvertToLong() || input.path("base_revision").longValue()<0
                || !input.path("rows").isArray() || input.path("rows").isEmpty() || input.path("rows").size()>128
                || input.has("target_ms") && !validTarget(input.path("target_ms"))) throw invalid("Invalid draft, instruction or time limit");
        for (var row:input.path("rows"))
            if (!row.isObject() || !row.propertyNames().equals(Set.of("utterance_id","part_index","evidence","anchor"))
                    || !row.path("utterance_id").isString() || row.path("utterance_id").stringValue().length()>128
                    || !row.path("part_index").isIntegralNumber() || !row.path("part_index").canConvertToInt()
                    || !row.path("evidence").isBoolean() || !row.path("anchor").isBoolean()) throw invalid("Invalid draft sentence");
    }
    private static boolean validTarget(JsonNode n) {
        return n.isIntegralNumber() && n.canConvertToLong() && n.longValue()>0 && n.longValue()<=9_007_199_254_740_991L;
    }

    static JsonNode freeze(ClipRequestModels.Snapshot clip, JsonNode input) {
        if (!"PLAN_READY".equals(clip.status()) || clip.revision()!=input.path("base_revision").longValue()
                || !clip.planHash().equals(input.path("plan_hash").stringValue()) || clip.manualReview()!=null) throw changed();
        var output=clip.plan().path("outputs").valueStream().filter(o -> o.path("output_id").asText().equals(input.path("output_id").asText()))
                .findFirst().orElseThrow(PaperCutSuggestion::changed);
        if (!"FOUND".equals(output.path("status").asText())) throw changed();
        var transcript=clip.utterances();
        Map<String,Integer> positions=new HashMap<>(), allowed=new HashMap<>();
        for(int i=0;i<transcript.size();i++) positions.put(transcript.get(i).path("utterance_id").asText(),i);
        int part=0;
        for (var span:output.path("parts")) {
            Integer start=positions.get(span.path("start_utterance_id").asText()), end=positions.get(span.path("end_utterance_id").asText());
            if(start==null || end==null || end<start) throw changed();
            for(int i=start;i<=end;i++) allowed.put(transcript.get(i).path("utterance_id").asText(),part);
            part++;
        }
        var evidence=new HashSet<String>(); output.path("evidence_utterance_ids").forEach(id->evidence.add(id.asText()));
        var anchors=new HashSet<String>();
        if(clip.sourceAnswer()!=null) clip.sourceAnswer().path("utterance_ids").forEach(id->anchors.add(id.asText()));
        int previous=-1, previousPart=-1, run=-1, chars=0; boolean hasEvidence=false;
        ObjectNode context=(ObjectNode)input.deepCopy();
        var rows=context.putArray("rows");
        for(var row:input.path("rows")) {
            String id=row.path("utterance_id").asText(); Integer index=positions.get(id), group=allowed.get(id);
            if(index==null || group==null || index<=previous) throw changed();
            if(group!=previousPart || index!=previous+1) run++;
            if(row.path("part_index").intValue()!=run || run>=12 || row.path("evidence").booleanValue()!=evidence.contains(id)
                    || row.path("anchor").booleanValue()!=anchors.remove(id)) throw changed();
            var source=transcript.get(index);
            var item=(ObjectNode)row.deepCopy();
            for(String field:List.of("start_ms","end_ms","text")) item.set(field,source.path(field));
            rows.add(item);
            hasEvidence|=evidence.contains(id); chars+=source.path("text").asText().length();
            previous=index; previousPart=group;
        }
        if(!anchors.isEmpty() || !hasEvidence) throw changed();
        if(chars>24000) throw invalid("The selected draft exceeds 24000 characters");
        context.put("clip_request_id",clip.id());
        return context;
    }
    static JsonNode validateResult(JsonNode result, Map<String,Object> owner) {
        var context=stored(owner);
        if(!result.isObject() || !result.propertyNames().equals(Set.of("turn_id","proposal"))
                || !String.valueOf(owner.get("id")).equals(result.path("turn_id").asText())) throw invalid("Invalid suggestion provenance");
        var proposal=result.path("proposal"); var ids=proposal.path("remove_ids"); var target=proposal.path("target_ms");
        if(!proposal.isObject() || !proposal.propertyNames().equals(Set.of("remove_ids","reason","target_ms"))
                || !ids.isArray() || ids.size()>128 || !proposal.path("reason").isString()
                || proposal.path("reason").stringValue().isBlank() || proposal.path("reason").stringValue().length()>1000
                || !target.isNull()&&!validTarget(target) || context.has("target_ms")&&!context.get("target_ms").equals(target))
            throw invalid("Invalid edit proposal or time limit");
        var removed=new HashSet<String>(); for (var id:ids) if(!id.isString() || !removed.add(id.asText())) throw invalid("Invalid sentence selection");
        int runs=0,previous=-1; long start=0,end=0,total=0; boolean evidence=false;
        for(var row:context.path("rows")) {
            int part=row.path("part_index").intValue();
            if(removed.remove(row.path("utterance_id").asText())) {
                if(row.path("anchor").asBoolean()) throw invalid("The proposal removes a required anchor");
                previous=-1; continue;
            }
            if(previous!=part) {runs++;total+=end-start;start=row.path("start_ms").longValue();}
            end=row.path("end_ms").longValue(); previous=part; evidence|=row.path("evidence").asBoolean();
        }
        total+=end-start;
        if(!removed.isEmpty() || !evidence || runs>12 || !ids.isEmpty()&&!target.isNull()&&total>target.longValue())
            throw invalid("Proposal violates the draft constraints");
        return result;
    }
}
