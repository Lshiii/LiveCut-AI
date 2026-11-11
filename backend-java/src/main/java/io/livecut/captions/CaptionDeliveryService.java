package io.livecut.captions;

import io.livecut.exports.ExportService;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** Confirmed captions and verified clean MP4s share one delivery representation. */
@Service
public final class CaptionDeliveryService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CaptionService captions;
    private final ExportService exports;
    private final CaptionBundleWriter bundles;
    public record Selection(String clipId, long captionRevision) {}
    public record Attachment(String filename, String contentType, byte[] bytes, String hash) {}

    public CaptionDeliveryService(CaptionService captions, ExportService exports, CaptionBundleWriter bundles) {
        this.captions = captions; this.exports = exports;
        this.bundles = bundles;
    }

    public Attachment file(String exportId, String clipId, long revision, String format) {
        if (!Set.of("srt", "vtt", "txt", "json").contains(format))
            throw new JobException(HttpStatus.BAD_REQUEST, "INVALID_CAPTION_FORMAT", "Use srt, vtt, txt or json");
        CaptionModels.Snapshot snapshot = captions.readConfirmed(exportId, clipId, revision);
        return attachment(snapshot, format);
    }

    public CaptionBundleWriter.Prepared bundle(String exportId, List<Selection> selections) {
        if (selections == null || selections.isEmpty() || selections.size() > 100
                || selections.stream().anyMatch(Objects::isNull)
                || selections.stream().map(Selection::clipId).distinct().count() != selections.size())
            throw invalidSelection();
        var batch = exports.get(exportId);
        var clips = new HashMap<String, io.livecut.exports.ExportModels.Clip>();
        batch.clips().forEach(clip -> clips.put(clip.id(), clip));
        List<CaptionBundleWriter.Plan> plans = new ArrayList<>();
        for (Selection selection : selections) {
            if (selection.captionRevision() < 0) throw invalidSelection();
            var clip = clips.get(selection.clipId());
            if (clip == null) throw new JobException(HttpStatus.NOT_FOUND, "EXPORT_CLIP_NOT_FOUND", "A selected clip does not belong to this export");
            if (!"SUCCEEDED".equals(clip.status()) || clip.sizeBytes() == null || clip.sizeBytes() < 1)
                throw new JobException(HttpStatus.CONFLICT, "EXPORT_CONTENT_NOT_READY", "Every explicitly selected clip must be complete");
            plans.add(new CaptionBundleWriter.Plan(selection.clipId(), selection.captionRevision(), clip.sizeBytes()));
        }
        // Admission and media-size reservations precede hashing or rendering.
        // The writer loads and releases one clip's sidecars at a time.
        return bundles.prepare(exportId, List.copyOf(plans), plan -> {
            var confirmed = captions.readConfirmedForDelivery(exportId, plan.clipId(), plan.captionRevision());
            CaptionModels.Snapshot snapshot = confirmed.snapshot();
            var media = confirmed.content();
            if (!snapshot.source().clipSha256().equals(media.hash()))
                throw new JobException(HttpStatus.CONFLICT, "CAPTION_SOURCE_INVALID", "Caption source differs from the completed video");
            List<CaptionBundleWriter.Sidecar> sidecars = new ArrayList<>();
            for (String format : List.of("srt", "vtt", "txt", "json")) {
                Attachment file = attachment(snapshot, format);
                sidecars.add(new CaptionBundleWriter.Sidecar(format, file.contentType(), file.bytes()));
            }
            return new CaptionBundleWriter.Item(snapshot.clipId(), snapshot.revision(), snapshot.clipTitle(),
                    snapshot.contentHash(), media, List.copyOf(sidecars), source(snapshot.source()));
        });
    }

    private Attachment attachment(CaptionModels.Snapshot snapshot, String format) {
        List<CaptionSerializer.DeliveryCue> cues = snapshot.cues().stream()
                .map(cue -> new CaptionSerializer.DeliveryCue(cue.id(), cue.startMs(), cue.endMs(), cue.text())).toList();
        byte[] bytes = switch (format) {
            case "srt" -> CaptionSerializer.renderSrt(snapshot.source().durationMs(), cues);
            case "vtt" -> CaptionSerializer.renderVtt(snapshot.source().durationMs(), cues);
            case "txt" -> CaptionSerializer.renderTxt(snapshot.source().durationMs(), cues);
            case "json" -> JSON.writeValueAsBytes(deliveryDocument(snapshot));
            default -> throw new IllegalArgumentException("Unsupported caption format");
        };
        String type = switch (format) {
            case "srt" -> "application/x-subrip; charset=UTF-8";
            case "vtt" -> "text/vtt; charset=UTF-8";
            case "txt" -> "text/plain; charset=UTF-8";
            default -> "application/json";
        };
        return new Attachment("clip-" + snapshot.clipId() + "-r" + snapshot.revision() + "." + format, type, bytes, CaptionBundleWriter.hash(bytes));
    }

    /** Delivery JSON contains approved words, not discarded source text or private ASR paths. */
    private Map<String, Object> deliveryDocument(CaptionModels.Snapshot snapshot) {
        Map<String, Object> value = new TreeMap<>();
        value.put("schema_version", "1.0"); value.put("data_kind", "confirmed_clip_captions");
        value.put("export_id", snapshot.exportId()); value.put("clip_id", snapshot.clipId());
        value.put("clip_title", snapshot.clipTitle()); value.put("revision", snapshot.revision());
        value.put("status", "CONFIRMED"); value.put("review_content_hash", snapshot.contentHash());
        value.put("source", source(snapshot.source()));
        value.put("cues", snapshot.cues().stream().map(cue -> {
            Map<String, Object> item = new TreeMap<>();
            item.put("id", cue.id()); item.put("start_ms", cue.startMs()); item.put("end_ms", cue.endMs());
            item.put("text", cue.text()); item.put("source_utterance_id", cue.sourceUtteranceId()); item.put("part_index", cue.partIndex());
            return item;
        }).toList());
        return value;
    }

    private Map<String, Object> source(CaptionModels.Source source) {
        Map<String, Object> value = new TreeMap<>();
        value.put("snapshot_hash", source.snapshotHash()); value.put("clip_sha256", source.clipSha256());
        value.put("transcript_hash", source.transcriptHash()); value.put("duration_ms", source.durationMs());
        value.put("media_duration_ms", source.mediaDurationMs()); value.put("timing_basis", source.timingBasis());
        value.put("parts", source.parts()); return value;
    }

    static JobException invalidSelection() {
        return new JobException(HttpStatus.BAD_REQUEST, "INVALID_CAPTION_PACKAGE", "Select 1..100 unique completed clips with explicit confirmed caption revisions");
    }

}
