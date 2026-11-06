package io.livecut.captions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic UTF-8 delivery only; the domain service must authorize reviewed cues first. */
public final class CaptionSerializer {
    private CaptionSerializer() {}

    public record DeliveryCue(String id, long startMs, long endMs, String text) {}

    public static byte[] renderSrt(long durationMs, List<DeliveryCue> cues) {
        validate(durationMs, cues);
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < cues.size(); index++) {
            DeliveryCue cue = cues.get(index);
            output.append(index + 1).append('\n').append(timestamp(cue.startMs(), ','))
                    .append(" --> ").append(timestamp(cue.endMs(), ',')).append('\n')
                    .append(escape(payload(cue.text()))).append("\n\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] renderVtt(long durationMs, List<DeliveryCue> cues) {
        validate(durationMs, cues);
        StringBuilder output = new StringBuilder("WEBVTT\n\n");
        for (DeliveryCue cue : cues) {
            output.append(cue.id()).append('\n').append(timestamp(cue.startMs(), '.'))
                    .append(" --> ").append(timestamp(cue.endMs(), '.')).append('\n')
                    .append(escape(payload(cue.text()))).append("\n\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] renderTxt(long durationMs, List<DeliveryCue> cues) {
        validate(durationMs, cues);
        StringBuilder output = new StringBuilder();
        for (DeliveryCue cue : cues) {
            if (!output.isEmpty()) output.append('\n');
            output.append(payload(cue.text())).append('\n');
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void validate(long durationMs, List<DeliveryCue> cues) {
        if (durationMs <= 0 || cues == null || cues.size() > CaptionMapper.MAX_CUES) {
            throw CaptionMapper.invalid("Invalid caption duration or cue count");
        }
        Set<String> ids = new HashSet<>();
        long previousEnd = 0, totalTextBytes = 0;
        for (DeliveryCue cue : cues) {
            if (cue == null || !CaptionMapper.validId(cue.id()) || !ids.add(cue.id())
                    || cue.startMs() < previousEnd || cue.endMs() <= cue.startMs() || cue.endMs() > durationMs) {
                throw CaptionMapper.invalid("Delivery cues must have unique IDs and ordered disjoint output times");
            }
            totalTextBytes += CaptionMapper.textBytes(cue.text(), CaptionMapper.MAX_TEXT_CHARACTERS_PER_CUE);
            if (totalTextBytes > CaptionMapper.MAX_TOTAL_TEXT_BYTES) {
                throw CaptionMapper.invalid("Caption text byte limit exceeded");
            }
            previousEnd = cue.endMs();
        }
    }

    private static String timestamp(long milliseconds, char separator) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", milliseconds / 3_600_000,
                milliseconds / 60_000 % 60, milliseconds / 1_000 % 60, separator, milliseconds % 1_000);
    }

    private static String payload(String text) {
        // Blank lines delimit cues in SRT/VTT. Keep words and meaningful line breaks, not extra blocks.
        List<String> lines = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (!line.isBlank()) lines.add(line.strip());
        }
        return String.join("\n", lines);
    }

    private static String escape(String text) {
        // Treat ASR/user text as literal text, including tags and the timing delimiter's greater-than sign.
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
