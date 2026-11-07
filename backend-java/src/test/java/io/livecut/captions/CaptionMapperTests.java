package io.livecut.captions;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.livecut.captions.CaptionMapper.SourceUtterance;
import io.livecut.jobs.ArtifactVerifier.SourcePart;

class CaptionMapperTests {
    private static SourceUtterance u(String id, long start, long end, String text) {
        return new SourceUtterance(id, start, end, text);
    }
    private static SourcePart p(long start, long end) { return new SourcePart(start, end); }

    @Test void removedSentencesStayAbsentWhileRetainedSilenceAndPartOffsetsRemain() {
        var source = List.of(u("u10", 11_000, 12_000, "第一段"), u("u2", 20_000, 21_000, "已删除内容"),
                u("u3", 31_000, 32_000, "第二段"));
        var result = CaptionMapper.map(40_000, List.of(p(10_000, 15_000), p(30_000, 34_000)), source);
        assertEquals(9_000, result.durationMs());
        assertEquals(CaptionMapper.TIMING_BASIS, result.timingBasis());
        assertEquals(List.of("u10", "u3"), result.cues().stream().map(CaptionMapper.MappingCue::utteranceId).toList());
        assertEquals(List.of(1_000L, 6_000L), result.cues().stream().map(CaptionMapper.MappingCue::startMs).toList());
        assertEquals(List.of(2_000L, 7_000L), result.cues().stream().map(CaptionMapper.MappingCue::endMs).toList());
        assertEquals(List.of(0, 1), result.cues().stream().map(CaptionMapper.MappingCue::partIndex).toList());
        assertTrue(result.cues().stream().noneMatch(CaptionMapper.MappingCue::boundaryPartial));
    }

    @Test void touchingEndpointsDoNotInventZeroLengthCues() {
        var result = CaptionMapper.map(3_000, List.of(p(1_000, 2_000)), List.of(
                u("before", 0, 1_000, "在片段前"), u("inside", 1_000, 2_000, "完整保留"),
                u("after", 2_000, 3_000, "在片段后")));
        assertEquals(1, result.cues().size());
        var cue = result.cues().getFirst();
        assertEquals("inside", cue.utteranceId());assertEquals(0, cue.startMs());assertEquals(1_000, cue.endMs());
        assertFalse(cue.boundaryPartial());
    }

    @Test void leftRightAndBothBoundaryCutsKeepOriginalTextOnlyAsUnresolvedEvidence() {
        for (SourcePart part : List.of(p(1_500, 2_000), p(1_000, 1_500), p(1_200, 1_800))) {
            var cue = CaptionMapper.map(3_000, List.of(part), List.of(u("sentence", 1_000, 2_000, "解释与关注引导在同一句"))).cues().getFirst();
            assertTrue(cue.boundaryPartial());assertEquals("解释与关注引导在同一句", cue.sourceText());
            assertEquals(1_000, cue.sourceStartMs());assertEquals(2_000, cue.sourceEndMs());
            assertEquals(part.startMs(), cue.retainedStartMs());assertEquals(part.endMs(), cue.retainedEndMs());
            assertEquals(0, cue.startMs());assertEquals(part.endMs() - part.startMs(), cue.endMs());
        }
    }

    @Test void oneSentenceAcrossRemovedSourceAndAdjacentPartsRequiresSeparateReview() {
        for (var parts : List.of(List.of(p(0, 1_000), p(4_000, 5_000)), List.of(p(0, 2_500), p(2_500, 5_000)))) {
            var result = CaptionMapper.map(5_000, parts, List.of(u("long", 0, 5_000, "没有词时间，不能猜删掉了哪些字")));
            assertEquals(2, result.cues().size());assertEquals("c000001", result.cues().get(0).id());assertEquals("c000002", result.cues().get(1).id());
            assertTrue(result.cues().stream().allMatch(CaptionMapper.MappingCue::boundaryPartial));
            assertEquals(result.cues().get(0).endMs(), result.cues().get(1).startMs());
            assertEquals(result.durationMs(), result.cues().get(1).endMs());
        }
    }

    @Test void emptyAndNoOverlapSourcesAreValidWithoutInventingSpeechOrSilenceClaims() {
        var empty = CaptionMapper.map(10_000, List.of(p(1_000, 2_000)), List.of());
        assertEquals(1_000, empty.durationMs());assertTrue(empty.cues().isEmpty());
        assertTrue(CaptionMapper.map(10_000, List.of(p(1_000, 2_000)), List.of(u("u1", 3_000, 4_000, "别处的原句"))).cues().isEmpty());
    }

    @Test void adjacentSourceSentencesAreLegalButOverlappingOrUnorderedSourcesAreRejected() {
        var adjacent = List.of(u("u10", 0, 1, "甲"), u("u2", 1, 2, "乙"));
        assertEquals(2, CaptionMapper.map(2, List.of(p(0, 2)), adjacent).cues().size());
        for (var invalid : List.of(List.of(u("a", 0, 2, "甲"), u("b", 1, 3, "乙")),
                List.of(u("a", 2, 3, "甲"), u("b", 0, 1, "乙")),
                List.of(u("a", 0, 1, "甲"), u("a", 1, 2, "乙")))) {
            assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(3, List.of(p(0, 3)), invalid));
        }
    }

    @Test void malformedOrOverlappingEditsAndSourceRangesAreRejected() {
        for (var parts : List.of(List.<SourcePart>of(), List.of(p(-1, 1)), List.of(p(1, 1)), List.of(p(1, 4)),
                List.of(p(0, 2), p(1, 3)), List.of(p(2, 3), p(0, 1)))) {
            assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(3, parts, List.of()));
        }
        for (var utterance : List.of(u("../id", 0, 1, "甲"), u("a", -1, 1, "甲"), u("a", 0, 0, "甲"), u("a", 0, 4, "甲"), u("a", 0, 1, " "))) {
            assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(3, List.of(p(0, 3)), List.of(utterance)));
        }
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(0, List.of(p(0, 1)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(3, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(3, List.of(p(0, 3)), null));
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(3, Arrays.asList((SourcePart)null), List.of()));
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(3, List.of(p(0, 3)), Arrays.asList((SourceUtterance)null)));
    }

    @Test void extremeSourcePositionsCannotOverflowAnOtherwiseSmallOutputOffset() {
        long end = Long.MAX_VALUE;
        var result = CaptionMapper.map(end, List.of(p(0, 2), p(end - 2, end)), List.of(
                u("first", 0, 2, "开头"), u("last", end - 2, end, "结尾")));
        assertEquals(4, result.durationMs());assertEquals(2, result.cues().get(1).startMs());assertEquals(4, result.cues().get(1).endMs());
        var whole = CaptionMapper.map(end, List.of(p(0, end)), List.of(u("last", end - 1, end, "末尾")));
        assertEquals(end - 1, whole.cues().getFirst().startMs());assertEquals(end, whole.cues().getFirst().endMs());
    }

    @Test void twelvePartsRemainIndependentAndTheThirteenthIsRejected() {
        var parts = new ArrayList<SourcePart>();
        var source = new ArrayList<SourceUtterance>();
        for (int i = 0; i < 12; i++) {parts.add(p(i * 2, i * 2 + 1));source.add(u("u" + i, i * 2, i * 2 + 1, "甲"));}
        var result = CaptionMapper.map(26, parts, source);
        assertEquals(12, result.durationMs());assertEquals(12, result.cues().size());assertEquals(11, result.cues().getLast().partIndex());
        parts.add(p(24, 25));
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(26, parts, source));
    }

    @Test void idsAndTextAreStableAndOutputDoesNotShareTheCallersMutableLists() {
        String id = "u".repeat(128), text = " 原文 😀\r\n第二行 ";
        var source = new ArrayList<>(List.of(u(id, 0, 1, text)));
        var parts = new ArrayList<>(List.of(p(0, 1)));
        var result = CaptionMapper.map(1, parts, source);
        assertEquals(result, CaptionMapper.map(1, parts, source));assertEquals("c000001", result.cues().getFirst().id());
        assertEquals(id, result.cues().getFirst().utteranceId());assertEquals(text, result.cues().getFirst().sourceText());
        source.clear();parts.clear();assertEquals(1, result.cues().size());
        assertThrows(UnsupportedOperationException.class, () -> result.cues().clear());
    }

    @Test void cueAndCharacterCapsAreExplicitRatherThanSilentTruncation() {
        var source = new ArrayList<SourceUtterance>();
        for (int i = 0; i < CaptionMapper.MAX_CUES; i++) source.add(u("u" + i, i, i + 1, "甲"));
        assertEquals(CaptionMapper.MAX_CUES, CaptionMapper.map(source.size(), List.of(p(0, source.size())), source).cues().size());
        source.add(u("extra", source.size(), source.size() + 1, "乙"));
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(source.size(), List.of(p(0, source.size())), source));
        assertEquals(2_000, CaptionMapper.map(1, List.of(p(0, 1)), List.of(u("u", 0, 1, "甲".repeat(2_000)))).cues().getFirst().sourceText().length());
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(1, List.of(p(0, 1)), List.of(u("u", 0, 1, "甲".repeat(2_001)))));
        assertTrue(CaptionMapper.map(2, List.of(p(1, 2)), List.of(u("unused", 0, 1, "甲".repeat(2_001)))).cues().isEmpty());
    }

    @Test void cumulativeUtf8ByteLimitCountsNonAsciiAndRepeatedPartialEvidence() {
        var source = new ArrayList<SourceUtterance>();
        for (int i = 0; i < 699; i++) source.add(u("u" + i, i, i + 1, "甲".repeat(1_000)));
        source.add(u("tail", 699, 700, "a".repeat(152)));
        assertEquals(700, CaptionMapper.map(700, List.of(p(0, 700)), source).cues().size());
        source.set(699, u("tail", 699, 700, "a".repeat(153)));
        assertThrows(IllegalArgumentException.class, () -> CaptionMapper.map(700, List.of(p(0, 700)), source));
    }
}
