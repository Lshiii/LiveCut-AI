package io.livecut.captions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.livecut.jobs.ArtifactVerifier.SourcePart;

/** Projects sentence anchors onto the frozen edit timeline; it does not infer word timings. */
public final class CaptionMapper {
    private CaptionMapper() {}

    public static final String TIMING_BASIS = "SOURCE_EDL_SENTENCE_MS";
    public static final int MAX_CUES = 10_000;
    public static final int MAX_TEXT_CHARACTERS_PER_CUE = 2_000;
    public static final int MAX_TOTAL_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SOURCE_UTTERANCES = 100_000;
    private static final int MAX_SOURCE_TEXT_CHARACTERS = 100_000;

    public record SourceUtterance(String id, long startMs, long endMs, String text) {}
    public record MappingCue(String id, int partIndex, String utteranceId,
                             long sourceStartMs, long sourceEndMs,
                             long retainedStartMs, long retainedEndMs,
                             long startMs, long endMs, String sourceText, boolean boundaryPartial) {}
    public record MappingResult(long durationMs, List<MappingCue> cues, String timingBasis) {
        public MappingResult {
            if (durationMs <= 0 || cues == null || !TIMING_BASIS.equals(timingBasis)) {
                throw invalid("Invalid caption mapping result");
            }
            cues = List.copyOf(cues);
        }
    }

    /** Parts and source sentences are chronological half-open intervals; touching endpoints are legal. */
    public static MappingResult map(long sourceDurationMs, List<SourcePart> parts,
                                    List<SourceUtterance> utterances) {
        if (sourceDurationMs <= 0 || parts == null || parts.isEmpty() || parts.size() > 12
                || utterances == null || utterances.size() > MAX_SOURCE_UTTERANCES) {
            throw invalid("Invalid caption source or edit intervals");
        }
        long previousEnd = 0;
        for (SourcePart part : parts) {
            if (part == null || part.startMs() < previousEnd || part.endMs() <= part.startMs()
                    || part.endMs() > sourceDurationMs) {
                throw invalid("Edit intervals must be ordered, disjoint and inside the source");
            }
            previousEnd = part.endMs();
        }
        Set<String> ids = new HashSet<>();
        previousEnd = 0;
        for (SourceUtterance utterance : utterances) {
            if (utterance == null || !validId(utterance.id()) || !ids.add(utterance.id())
                    || utterance.startMs() < previousEnd || utterance.endMs() <= utterance.startMs()
                    || utterance.endMs() > sourceDurationMs) {
                throw invalid("Source sentences must have unique IDs and ordered disjoint source times");
            }
            textBytes(utterance.text(), MAX_SOURCE_TEXT_CHARACTERS);
            previousEnd = utterance.endMs();
        }

        List<MappingCue> cues = new ArrayList<>();
        int sourceIndex = 0;
        long offset = 0, textBytes = 0;
        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
            SourcePart part = parts.get(partIndex);
            while (sourceIndex < utterances.size() && utterances.get(sourceIndex).endMs() <= part.startMs()) {
                sourceIndex++;
            }
            for (int index = sourceIndex; index < utterances.size(); index++) {
                SourceUtterance utterance = utterances.get(index);
                if (utterance.startMs() >= part.endMs()) break;
                long retainedStart = Math.max(part.startMs(), utterance.startMs());
                long retainedEnd = Math.min(part.endMs(), utterance.endMs());
                if (retainedStart >= retainedEnd) continue;
                if (cues.size() == MAX_CUES) throw invalid("Caption cue limit exceeded");
                textBytes += textBytes(utterance.text(), MAX_TEXT_CHARACTERS_PER_CUE);
                if (textBytes > MAX_TOTAL_TEXT_BYTES) throw invalid("Caption text byte limit exceeded");
                boolean partial = retainedStart != utterance.startMs() || retainedEnd != utterance.endMs();
                cues.add(new MappingCue(String.format(Locale.ROOT, "c%06d", cues.size() + 1),
                        partIndex, utterance.id(), utterance.startMs(), utterance.endMs(),
                        retainedStart, retainedEnd, offset + (retainedStart - part.startMs()),
                        offset + (retainedEnd - part.startMs()), utterance.text(), partial));
            }
            // Disjoint intervals inside sourceDurationMs also bound the cumulative duration and additions.
            offset = Math.addExact(offset, part.endMs() - part.startMs());
        }
        return new MappingResult(offset, cues, TIMING_BASIS);
    }

    static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    }

    /** Bound text before escaping and reject characters that cannot survive UTF-8 cue delivery. */
    static int textBytes(String value, int maximumCharacters) {
        if (value == null || value.isBlank() || value.length() > maximumCharacters) {
            throw invalid("Caption text is missing or exceeds the character limit");
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw invalid("Caption text contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(ch)
                    || (ch < 0x20 && ch != '\t' && ch != '\r' && ch != '\n')) {
                throw invalid("Caption text contains an unsupported control character");
            }
        }
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
