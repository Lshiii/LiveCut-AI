package io.livecut.exports;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OpenTimelineIO hard cuts of one verified, confirmed output. Times use the source plan's millisecond clock. */
final class EditTimeline {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern UNSAFE_NAME = Pattern.compile("[\\\\/<>:\"|?*%\\p{C}]");
    private static final Pattern DEVICE_NAME = Pattern.compile("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])\\..*");

    private EditTimeline() {}

    record ExportOrigin(String exportId, String clipId, String snapshotHash) {}

    static ObjectNode document(long revision, String planHash, String manualReviewHash, ExportOrigin origin,
                               String originalFilename, JsonNode plan, JsonNode output, JsonNode utterances) {
        String title = output.path("title").asText();
        String hash = plan.path("source_hash").asText();
        String filename = portableFilename(originalFilename, hash);
        ObjectNode timeline = schema("Timeline.1").put("name", title);
        timeline.set("global_start_time", time(0));
        ObjectNode proof = timeline.putObject("metadata").putObject("livecut");
        proof.put("source_sha256", hash).put("source_filename", filename)
                .put("approved_revision", revision).put("plan_hash", planHash);
        if (origin != null) {
            proof.put("export_id", origin.exportId()).put("clip_id", origin.clipId()).put("snapshot_hash", origin.snapshotHash());
        }
        if (manualReviewHash != null) proof.put("manual_review_hash", manualReviewHash);
        proof.set("clip_request_id", plan.get("request_id"));
        proof.set("video_id", plan.get("video_id"));
        proof.set("transcript_hash", plan.get("transcript_hash"));
        proof.set("output_id", output.get("output_id"));
        proof.set("goal_id", output.get("goal_id"));
        proof.set("evidence_utterance_ids", output.get("evidence_utterance_ids"));

        ObjectNode reference = schema("ExternalReference.1").put("name", filename)
                .put("target_url", "./" + UriUtils.encodePathSegment(filename, StandardCharsets.UTF_8));
        reference.set("available_range", range(0, plan.path("duration_ms").longValue()));
        reference.putNull("available_image_bounds");
        reference.putObject("metadata").putObject("livecut").put("source_sha256", hash);

        ObjectNode tracks = item("Stack.1", "tracks");
        timeline.set("tracks", tracks);
        ArrayNode children = tracks.putArray("children");
        ArrayNode video = track(children, "V1", "Video");
        ArrayNode audio = track(children, "A1", "Audio");
        Map<String,Integer> order = new HashMap<>();
        for (int n = 0; n < utterances.size(); n++) order.put(utterances.get(n).path("utterance_id").asText(), n);
        Set<String> evidence = new HashSet<>();
        output.path("evidence_utterance_ids").forEach(id -> evidence.add(id.asText()));
        int index = 0;
        for (JsonNode part : output.path("parts")) {
            ObjectNode clip = item("Clip.1", title + " · " + ++index);
            clip.set("media_reference", reference);
            clip.set("source_range", range(part.path("start_ms").longValue(), part.path("end_ms").longValue()));
            ObjectNode boundaries = clip.withObjectProperty("metadata").putObject("livecut");
            boundaries.set("start_utterance_id", part.get("start_utterance_id"));
            boundaries.set("end_utterance_id", part.get("end_utterance_id"));
            // The audio follows the same cuts. Evidence markers are shown once on the picture track.
            audio.add(clip.deepCopy());
            ArrayNode markers = clip.withArrayProperty("markers");
            int first = order.get(part.path("start_utterance_id").asText());
            int last = order.get(part.path("end_utterance_id").asText());
            for (int n = first; n <= last; n++) {
                JsonNode sentence = utterances.get(n);
                String id = sentence.path("utterance_id").asText();
                if (evidence.contains(id)) {
                    ObjectNode marker = schema("Marker.2").put("name", sentence.path("text").asText()).put("color", "GREEN");
                    // OTIO clip markers share the source_range time frame, even when the cut moves in the edit.
                    marker.set("marked_range", range(sentence.path("start_ms").longValue(), sentence.path("end_ms").longValue()));
                    marker.putObject("metadata").putObject("livecut").put("utterance_id", id);
                    markers.add(marker);
                }
            }
            video.add(clip);
        }
        return timeline;
    }

    private static ArrayNode track(ArrayNode children, String name, String kind) {
        ObjectNode track = item("Track.1", name).put("kind", kind);
        children.add(track);
        return track.putArray("children");
    }

    private static ObjectNode item(String kind, String name) {
        ObjectNode item = schema(kind).put("name", name).put("enabled", true).putNull("source_range");
        item.putObject("metadata");
        item.putArray("markers");
        item.putArray("effects");
        return item;
    }

    private static ObjectNode range(long startMs, long endMs) {
        ObjectNode range = schema("TimeRange.1");
        range.set("start_time", time(startMs));
        range.set("duration", time(endMs - startMs));
        return range;
    }

    private static ObjectNode time(long ms) {
        return schema("RationalTime.1").put("value", ms).put("rate", 1000);
    }

    private static ObjectNode schema(String type) {
        return JSON.createObjectNode().put("OTIO_SCHEMA", type);
    }

    static String portableFilename(String name, String hash) {
        if (name == null || name.length() < 5 || name.length() > 255 || name.startsWith(".")
                || !name.equals(name.strip()) || !name.toLowerCase(Locale.ROOT).endsWith(".mp4")
                || UNSAFE_NAME.matcher(name).find() || DEVICE_NAME.matcher(name).matches()) {
            return "source-" + hash + ".mp4";
        }
        return name;
    }
}
