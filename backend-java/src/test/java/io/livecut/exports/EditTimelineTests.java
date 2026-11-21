package io.livecut.exports;

import java.util.List;
import io.livecut.video.SourceMediaFilename;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class EditTimelineTests {
    @Test void unsafeOrNonPortableSourceNameGetsOneRelinkableMp4Basename() {
        String hash = "a".repeat(64);
        for (String name : List.of("../../source.mp4", "C:\\tmp\\source.mp4", "https://host/movie.mp4",
                "AUX.mp4", "a%2Fb.mp4", "clip\r\n.mp4", "bad\u202Ename.mp4", "ff.mp3")) {
            assertEquals("source-" + hash + ".mp4", SourceMediaFilename.portable(name, hash));
        }
        assertEquals("课程 #1.mp4", SourceMediaFilename.portable("课程 #1.mp4", hash));
    }

    @Test void externalMediaUrlEncodesOneSegmentAndMarkersUseOriginalTime() {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode plan = json.readTree("""
                {"source_hash":"source", "duration_ms":8000, "transcript_hash":"asr"}
                """);
        JsonNode output = json.readTree("""
                {"output_id":"cut","goal_id":"goal","title":"回答","evidence_utterance_ids":["u1"],
                 "parts":[{"start_utterance_id":"u1","end_utterance_id":"u1","start_ms":4397,"end_ms":5091}]}
                """);
        JsonNode speech = json.readTree("""
                [{"utterance_id":"u1","text":"原句","start_ms":4397,"end_ms":5091}]
                """);
        JsonNode timeline = EditTimeline.document(1, "plan", null, null, "课程 #1.mp4", plan, output, speech);
        JsonNode part = timeline.at("/tracks/children/0/children/0");
        assertEquals("./%E8%AF%BE%E7%A8%8B%20%231.mp4", part.at("/media_reference/target_url").asText());
        assertEquals(4397, part.at("/markers/0/marked_range/start_time/value").intValue());
        assertEquals(694, part.at("/source_range/duration/value").intValue());
    }
}
