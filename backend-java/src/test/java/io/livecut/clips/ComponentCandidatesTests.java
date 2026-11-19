package io.livecut.clips;

import static io.livecut.clips.ClipPlanValidator.JSON;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ComponentCandidatesTests {
    private Map<String,Object> owner;

    private ObjectNode montage() {
        owner = AnswerClipSourceTests.owner("u4");
        ObjectNode plan = AnswerClipSourceTests.plan(owner, "FOUND");
        owner.remove("source_answer_json");
        owner.remove("source_answer_hash");
        owner.put("source_json", JSON.writeValueAsString(Map.of("utterances", AnswerClipSourceTests.utterances())));
        owner.put("output_policy", ClipOutputPolicy.SINGLE_MONTAGE);
        ObjectNode provenance = (ObjectNode)plan.path("provenance");
        provenance.put("prompt_version", ClipOutputPolicy.MONTAGE_PROMPT).put("retrieval_mode", "bm25");
        ObjectNode out = (ObjectNode)plan.path("outputs").get(0);
        var parts = out.putArray("parts");
        var evidence = out.putArray("evidence_utterance_ids");
        ArrayNode groups = plan.putArray("component_candidates");
        for (int index : new int[]{1,5}) {
            ArrayNode options = groups.addObject().put("title", "商品" + index)
                    .put("component_id", "p"+index).putArray("choices");
            for (int offset : new int[]{0,1}) {
                int i = index + offset;
                ObjectNode choice = options.addObject().put("reason","完整卖点");
                AnswerClipSourceTests.parts(choice,"u"+i,"u"+i);
                if (offset == 0) {
                    parts.add(choice.path("parts").get(0));
                    evidence.add("u"+i);
                }
            }
        }
        return plan;
    }

    @Test void acceptsAlternateWithFrozenGroupCatalogAndImmutableBase() {
        ObjectNode auto = montage();
        assertDoesNotThrow(() -> ClipPlanValidator.validate(auto,owner,true));
        var edited = auto.deepCopy();
        ObjectNode output = (ObjectNode)edited.path("outputs").get(0);
        var other = auto.path("component_candidates").get(1).path("choices").get(1);
        ((ArrayNode)output.path("parts")).set(1, other.path("parts").get(0));
        output.putArray("evidence_utterance_ids").add("u1").add("u6");
        assertDoesNotThrow(() -> ClipPlanValidator.validateReview(edited,owner,auto));
        ((ObjectNode)edited.path("component_candidates").get(0)).put("title","另一件商品");
        assertEquals("INVALID_CLIP_PLAN",AnswerClipSourceTests.failure(() -> ClipPlanValidator.validateReview(edited,owner,auto)));
        edited.remove("component_candidates");
        assertEquals("INVALID_CLIP_PLAN",AnswerClipSourceTests.failure(() -> ClipPlanValidator.validateReview(edited,owner,auto)));
        auto.remove("component_candidates");
        assertDoesNotThrow(() -> ClipPlanValidator.validateReview(edited,owner,auto));
    }

    private void rejects(Consumer<ObjectNode> change) {
        var plan = montage();
        change.accept(plan);
        assertEquals("INVALID_CLIP_PLAN",AnswerClipSourceTests.failure(() -> ClipPlanValidator.validate(plan,owner,true)));
    }

    private ObjectNode choice(ObjectNode plan,int group,int option) {
        return (ObjectNode)plan.path("component_candidates").get(group).path("choices").get(option);
    }

    @Test void rejectsSwappedOwnershipDuplicateChoicesAndFalseTimingOrEvidence() {
        rejects(p -> {
            var groups = (ArrayNode)p.path("component_candidates");
            groups.add(groups.remove(0));
        });
        rejects(p -> AnswerClipSourceTests.parts(choice(p,0,1),"u5","u5"));
        rejects(p -> AnswerClipSourceTests.parts(choice(p,0,1),"u1","u1"));
        rejects(p -> AnswerClipSourceTests.parts(choice(p,0,0),"u3","u3"));
        rejects(p -> ((ObjectNode)choice(p,0,1).path("parts").get(0)).put("end_ms",7900));
        rejects(p -> choice(p,0,1).putArray("evidence_utterance_ids").add("u7"));
        rejects(p -> ((ArrayNode)p.path("outputs").get(0).path("excluded"))
                .addObject().put("utterance_id","u2").put("reason","夹杂闲聊"));
    }
}
