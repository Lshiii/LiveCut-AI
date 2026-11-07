package io.livecut.clips;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ClipRequestHistoryCursorTests {
    private static final String JOB="12345678-1234-4234-8234-123456789abc";
    private static final LocalDateTime TIME=LocalDateTime.of(2026,11,7,13,59,58,123456000);
    private static ClipRequestHistoryService.Cursor cursor(){return new ClipRequestHistoryService.Cursor(JOB,new ClipRequestHistoryService.Boundary(TIME,"ffffffff-ffff-4fff-8fff-ffffffffffff"),new ClipRequestHistoryService.Boundary(TIME,"00000000-0000-4000-8000-000000000001"));}
    private static String encoded(ObjectNode node){return Base64.getUrlEncoder().withoutPadding().encodeToString(ClipRequestService.canonicalJson(node).getBytes(StandardCharsets.UTF_8));}
    private static ObjectNode node(){return (ObjectNode)ClipPlanValidator.JSON.readTree(Base64.getUrlDecoder().decode(ClipRequestHistoryService.encode(cursor())));}
    private static void invalid(String value){assertEquals("INVALID_CLIP_HISTORY_CURSOR",assertThrows(JobException.class,()->ClipRequestHistoryService.decode(value,JOB)).getCode());}

    @Test void opaqueCursorPreservesMicrosecondsAndUsesAsciiUuidOrdering(){
        var expected=cursor();String value=ClipRequestHistoryService.encode(expected);
        assertEquals(expected,ClipRequestHistoryService.decode(value,JOB));assertFalse(value.contains("="));
        assertTrue(expected.ceiling().compareTo(expected.after())>0,"UUID.compareTo uses signed longs and would invert this ASCII tie");
    }
    @Test void cursorRejectsOtherJobExtraFieldsAndUnsupportedVersion(){
        assertEquals("INVALID_CLIP_HISTORY_CURSOR",assertThrows(JobException.class,()->ClipRequestHistoryService.decode(ClipRequestHistoryService.encode(cursor()),UUID.randomUUID().toString())).getCode());
        ObjectNode n=node();n.put("extra",true);invalid(encoded(n));n=node();n.put("v",2);invalid(encoded(n));
        n=node();n.put("v",1.0);invalid(Base64.getUrlEncoder().withoutPadding().encodeToString(ClipPlanValidator.JSON.writeValueAsBytes(n)));n=node();n.remove("after_id");invalid(encoded(n));
    }
    @Test void cursorRejectsNonCanonicalBase64JsonAndUuid(){
        String valid=ClipRequestHistoryService.encode(cursor());
        for(String value:List.of("",valid+"=",valid+"\n","x".repeat(1025),"%",Base64.getUrlEncoder().withoutPadding().encodeToString((" "+ClipRequestService.canonicalJson(node())).getBytes(StandardCharsets.UTF_8))))invalid(value);
        ObjectNode n=node();n.put("ceiling_id","FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF");invalid(encoded(n));
        n=node();n.put("after_id","1-1-1-1-1");invalid(encoded(n));n=node();n.putNull("after_id");invalid(encoded(n));
    }
    @Test void cursorRejectsInvalidOrNonMicrosecondDatesAndReverseBoundaries(){
        for(String value:List.of("2026-02-30T13:59:58.123456","2026-11-07T13:59:58.123","2026-11-07T13:59:58.123456Z","0999-11-07T13:59:58.123456","2026-11-07T24:00:00.123456")){
            ObjectNode n=node();n.put("after_created_at",value);invalid(encoded(n));
        }
        ObjectNode n=node();n.put("after_created_at","2026-11-07T13:59:58.123457");invalid(encoded(n));
    }
    @Test void limitsAreStrictAndBounded(){
        assertEquals(20,ClipRequestHistoryService.limit(null));assertEquals(1,ClipRequestHistoryService.limit("1"));assertEquals(30,ClipRequestHistoryService.limit("30"));
        for(String value:List.of("","0","31","100000000000000000","-1","+1","01"," 2","1.0","1e1","2,3"))assertEquals("INVALID_CLIP_HISTORY_LIMIT",assertThrows(JobException.class,()->ClipRequestHistoryService.limit(value)).getCode());
    }
}
