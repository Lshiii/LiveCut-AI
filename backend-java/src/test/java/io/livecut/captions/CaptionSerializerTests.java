package io.livecut.captions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.livecut.captions.CaptionSerializer.DeliveryCue;

class CaptionSerializerTests {
    private static DeliveryCue c(String id, long start, long end, String text) { return new DeliveryCue(id, start, end, text); }
    private static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    @Test void goldenFilesKeepOutputGapsAndIntegerMillisecondsWithoutSourceTimeLeakage() {
        var cues = List.of(c("c000001", 1_000, 2_000, "第一段"), c("c000002", 6_000, 7_001, "第二段 😀"));
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\n第一段\n\n2\n00:00:06,000 --> 00:00:07,001\n第二段 😀\n\n", utf8(CaptionSerializer.renderSrt(9_000, cues)));
        assertEquals("WEBVTT\n\nc000001\n00:00:01.000 --> 00:00:02.000\n第一段\n\nc000002\n00:00:06.000 --> 00:00:07.001\n第二段 😀\n\n", utf8(CaptionSerializer.renderVtt(9_000, cues)));
        assertEquals("第一段\n\n第二段 😀\n", utf8(CaptionSerializer.renderTxt(9_000, cues)));
        assertArrayEquals(CaptionSerializer.renderSrt(9_000, cues), CaptionSerializer.renderSrt(9_000, cues));
        assertFalse(utf8(CaptionSerializer.renderVtt(9_000, cues)).contains("\r"));
    }

    @Test void emptyDeliveryIsDeterministicAndDoesNotCreateFakeCaptionText() {
        assertArrayEquals(new byte[0], CaptionSerializer.renderSrt(1, List.of()));
        assertArrayEquals(new byte[0], CaptionSerializer.renderTxt(1, List.of()));
        assertEquals("WEBVTT\n\n", utf8(CaptionSerializer.renderVtt(1, List.of())));
    }

    @Test void arbitraryHourCountsAndOneMillisecondCuesNeverUseFloatingPointOrWrapAtTwentyFourHours() {
        var cues = List.of(c("a", 3_723_004, 3_723_005, "一毫秒"), c("b", 360_000_000, 360_000_001, "一百小时"));
        String srt = utf8(CaptionSerializer.renderSrt(360_000_001, cues));
        assertTrue(srt.contains("01:02:03,004 --> 01:02:03,005"));
        assertTrue(srt.contains("100:00:00,000 --> 100:00:00,001"));
        assertTrue(utf8(CaptionSerializer.renderVtt(Long.MAX_VALUE, List.of(c("last", Long.MAX_VALUE - 1, Long.MAX_VALUE, "末尾")))).contains("2562047788015:12:55.806 --> 2562047788015:12:55.807"));
    }

    @Test void literalTextCannotInjectMarkupOrAdditionalCueBlocks() {
        String text = "  <b>&字幕</b>\r\n\r\n00:00:01 --> 意外箭头\r下一行  ";
        var cues = List.of(c("id", 0, 1_000, text));
        String escaped = "&lt;b&gt;&amp;字幕&lt;/b&gt;\n00:00:01 --&gt; 意外箭头\n下一行";
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\n" + escaped + "\n\n", utf8(CaptionSerializer.renderSrt(1_000, cues)));
        assertEquals("WEBVTT\n\nid\n00:00:00.000 --> 00:00:01.000\n" + escaped + "\n\n", utf8(CaptionSerializer.renderVtt(1_000, cues)));
        assertEquals("<b>&字幕</b>\n00:00:01 --> 意外箭头\n下一行\n", utf8(CaptionSerializer.renderTxt(1_000, cues)));
        assertEquals(text, cues.getFirst().text());
    }

    @Test void adjacentOutputCuesAreAllowedButReorderingOverlapAndInvalidRangesAreRejected() {
        assertDoesNotThrow(() -> CaptionSerializer.renderSrt(2, List.of(c("a", 0, 1, "甲"), c("b", 1, 2, "乙"))));
        for (var cues : List.of(List.of(c("a", -1, 1, "甲")), List.of(c("a", 1, 1, "甲")),
                List.of(c("a", 0, 4, "甲")), List.of(c("a", 0, 2, "甲"), c("b", 1, 3, "乙")),
                List.of(c("a", 2, 3, "甲"), c("b", 0, 1, "乙")), List.of(c("a", 0, 1, "甲"), c("a", 1, 2, "乙")))) {
            assertAllFormatsReject(3, cues);
        }
        assertAllFormatsReject(0, List.of());assertAllFormatsReject(-1, List.of());
        assertAllFormatsReject(1, null);assertAllFormatsReject(1, Arrays.asList((DeliveryCue)null));
    }

    @Test void badTextAndCueIdsCannotCreateMalformedFilesOrSilentlyLoseUnicode() {
        for (String text : Arrays.asList(null, "", " \r\n\t", "\0", "x\u0001", "\uD800", "\uDC00", "x".repeat(2_001))) {
            assertAllFormatsReject(1, List.of(c("a", 0, 1, text)));
        }
        for (String id : Arrays.asList(null, "", "../id", "id\nWEBVTT", "i".repeat(129))) {
            assertAllFormatsReject(1, List.of(c(id, 0, 1, "甲")));
        }
        assertDoesNotThrow(() -> CaptionSerializer.renderTxt(1, List.of(c("a", 0, 1, "😀".repeat(1_000)))));
    }

    @Test void allFormatsEnforceCueCharacterAndRawUtf8BudgetsAtTheExactBoundary() {
        var cues = new ArrayList<DeliveryCue>();
        for (int i = 0; i < 699; i++) cues.add(c("c" + i, i, i + 1, "甲".repeat(1_000)));
        cues.add(c("tail", 699, 700, "a".repeat(152)));
        assertDoesNotThrow(() -> CaptionSerializer.renderSrt(700, cues));
        assertDoesNotThrow(() -> CaptionSerializer.renderVtt(700, cues));
        assertDoesNotThrow(() -> CaptionSerializer.renderTxt(700, cues));
        cues.set(699, c("tail", 699, 700, "a".repeat(153)));assertAllFormatsReject(700, cues);
        cues.clear();for (int i = 0; i < 10_000; i++) cues.add(c("c" + i, i, i + 1, "a"));
        assertDoesNotThrow(() -> CaptionSerializer.renderSrt(10_000, cues));
        cues.add(c("last", 10_000, 10_001, "a"));assertAllFormatsReject(10_001, cues);
        assertDoesNotThrow(() -> CaptionSerializer.renderTxt(1, List.of(c("a", 0, 1, "a".repeat(2_000)))));
    }

    private static void assertAllFormatsReject(long duration, List<DeliveryCue> cues) {
        assertThrows(IllegalArgumentException.class, () -> CaptionSerializer.renderSrt(duration, cues));
        assertThrows(IllegalArgumentException.class, () -> CaptionSerializer.renderVtt(duration, cues));
        assertThrows(IllegalArgumentException.class, () -> CaptionSerializer.renderTxt(duration, cues));
    }
}
