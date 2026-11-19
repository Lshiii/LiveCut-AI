package io.livecut.clips;

import io.livecut.video.VideoApiException;
import io.livecut.video.VideoService;
import io.livecut.video.VideoStorage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.*;

@RestController
public class ProductFrameController {
    private final ClipRequestService clips;
    private final VideoService videos;
    private final VideoStorage storage;
    private final ProductFrameRunner runner;

    public ProductFrameController(ClipRequestService clips, VideoService videos, VideoStorage storage, ProductFrameRunner runner) {
        this.clips = clips;
        this.videos = videos;
        this.storage = storage;
        this.runner = runner;
    }

    public record Part(String start_utterance_id, String end_utterance_id) {}
    public record Candidate(String id, List<Part> parts) {}
    public record Query(String source_hash, String transcript_hash, String plan_hash, String label, List<Candidate> candidates) {}
    public record IntervalGroup(String id, List<List<Long>> parts) {}

    @PostMapping("/api/clip-requests/{id}/product-frames")
    public Map<String, Object> rank(@PathVariable String id, @RequestBody Query query) {
        var snapshot = clips.get(id);
        var intervals = bind(snapshot, query);
        var source = videos.get(snapshot.videoId());
        if (!source.contentHash().equals(snapshot.source().videoSha256())) throw stale();
        var path = storage.resolve(source.sourceKey());
        var result = runner.run(Map.of("source", path.toString(), "source_hash", source.contentHash(),
            "duration_ms", snapshot.source().durationMs(), "cache_root", path.getParent().getParent().resolve("product-frame-cache").toString(),
            "label", query.label().strip(), "candidates", intervals));
        return Map.of("request_id", snapshot.id(), "video_id", snapshot.videoId(), "source_hash", source.contentHash(),
            "transcript_hash", snapshot.source().transcriptHash(), "plan_hash", snapshot.planHash(), "label", query.label().strip(),
            "model_id", result.get("model_id"), "rows", result.get("rows"));
    }

    static List<IntervalGroup> bind(ClipRequestModels.Snapshot snapshot, Query query) {
        if (query == null || !"PLAN_READY".equals(snapshot.status()) || snapshot.planHash() == null
            || !snapshot.planHash().equals(query.plan_hash()) || !snapshot.source().videoSha256().equals(query.source_hash())
            || !snapshot.source().transcriptHash().equals(query.transcript_hash())) throw stale();
        if (query.label() == null || query.label().isBlank() || query.label().length() > 80
            || query.candidates() == null || query.candidates().isEmpty() || query.candidates().size() > 12) throw invalid();
        Map<String,JsonNode> utterances = new HashMap<>();
        for (JsonNode u : snapshot.utterances()) utterances.put(u.get("utterance_id").asString(), u);
        var seen = new HashSet<String>();
        var groups = new ArrayList<IntervalGroup>();
        for (var candidate : query.candidates()) {
            if (candidate == null || candidate.id() == null || !candidate.id().matches("[a-zA-Z0-9_.:-]{1,128}")
                || !seen.add(candidate.id()) || candidate.parts() == null || candidate.parts().isEmpty() || candidate.parts().size() > 12) throw invalid();
            var intervals = new ArrayList<List<Long>>();
            long previous = -1;
            for (var part : candidate.parts()) {
                if (part == null) throw invalid();
                var first = utterances.get(part.start_utterance_id());
                var last = utterances.get(part.end_utterance_id());
                if (first == null || last == null) throw invalid();
                long start = first.get("start_ms").asLong(), end = last.get("end_ms").asLong();
                if (start > last.get("start_ms").asLong() || start < previous || start < 0 || start >= end
                    || end > snapshot.source().durationMs()) throw invalid();
                intervals.add(List.of(start, end));
                previous = end;
            }
            groups.add(new IntervalGroup(candidate.id(), intervals));
        }
        return groups;
    }

    private static VideoApiException invalid() {
        return new VideoApiException(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_FRAME_CANDIDATES", "Choose valid transcript ranges and a product description");
    }
    private static VideoApiException stale() {
        return new VideoApiException(HttpStatus.CONFLICT, "PRODUCT_FRAME_SOURCE_CHANGED", "Refresh the selected source and revision");
    }
}
