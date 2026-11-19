package io.livecut.clips;

import static io.livecut.clips.ClipPlanValidator.*;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Bind the published alternatives to real transcript boundaries and the initial cut. */
final class ComponentCandidates {
    private ComponentCandidates() {}

    private record Span(int first, int last) {}
    private record Group(Span original, List<Span> options) {}

    static void validate(JsonNode plan, JsonNode transcript, Map<String, Integer> order) {
        JsonNode output = plan.path("outputs").get(0);
        Set<String> excluded = new HashSet<>();
        for (JsonNode item : output.path("excluded")) excluded.add(item.path("utterance_id").asText());
        var groups = new ArrayList<Group>();
        var primaryParts = new ArrayList<JsonNode>();
        var primaryEvidence = new TreeSet<String>(Comparator.comparingInt(order::get));
        var ids = new HashSet<String>();

        for (JsonNode group : array(plan, "component_candidates", 1, 6)) {
            fields(group, Set.of("component_id", "title", "choices"));
            String componentId = id(group, "component_id");
            if (componentId.length() > 64 || !ids.add(componentId)) throw bad("Invalid montage component ID");
            text(group, "title", 120);
            var options = new ArrayList<Span>();
            var seen = new HashSet<JsonNode>();
            for (JsonNode choice : array(group, "choices", 1, 4)) {
                fields(choice, Set.of("reason", "parts", "evidence_utterance_ids"));
                text(choice, "reason", 1000);
                JsonNode parts = array(choice, "parts", 1, 12);
                if (!seen.add(parts)) throw bad("Repeated choice in montage component");
                boolean original = options.isEmpty();
                var ranges = new ArrayList<Span>();
                int previous = -1;
                for (JsonNode part : parts) {
                    fields(part, Set.of("start_utterance_id", "end_utterance_id", "start_ms", "end_ms"));
                    int first = order.getOrDefault(id(part, "start_utterance_id"), -1);
                    int last = order.getOrDefault(id(part, "end_utterance_id"), -1);
                    if (first <= previous || last < first
                            || number(part, "start_ms") != number(transcript.get(first), "start_ms")
                            || number(part, "end_ms") != number(transcript.get(last), "end_ms"))
                        throw bad("Choice must use ordered frozen transcript boundaries");
                    for (int i = first; i <= last; i++)
                        if (excluded.contains(transcript.get(i).path("utterance_id").asText()))
                            throw bad("Choice includes an excluded utterance");
                    ranges.add(new Span(first, last));
                    if (original) primaryParts.add(part);
                    previous = last;
                }
                int priorEvidence = -1;
                for (JsonNode evidence : array(choice, "evidence_utterance_ids", 1, 200)) {
                    int position = order.getOrDefault(evidence.asText(), -1);
                    if (!evidence.isString() || position <= priorEvidence
                            || ranges.stream().noneMatch(span -> span.first <= position && position <= span.last))
                        throw bad("Choice evidence must be ordered and inside its retained parts");
                    if (original) primaryEvidence.add(evidence.asText());
                    priorEvidence = position;
                }
                options.add(new Span(ranges.getFirst().first, ranges.getLast().last));
            }
            groups.add(new Group(options.getFirst(), options));
        }

        for (int i = 0; i < groups.size(); i++) {
            int previousEnd = i == 0 ? -1 : groups.get(i - 1).original.last;
            int nextStart = i + 1 == groups.size() ? transcript.size() : groups.get(i + 1).original.first;
            for (Span option : groups.get(i).options)
                if (option.first <= previousEnd || option.last >= nextStart)
                    throw bad("Choice crosses another component's primary interval");
        }
        if (!JSON.valueToTree(primaryParts).equals(output.path("parts"))
                || !JSON.valueToTree(primaryEvidence).equals(output.path("evidence_utterance_ids")))
            throw bad("Primary component choices must reproduce the complete published cut");
    }
}
