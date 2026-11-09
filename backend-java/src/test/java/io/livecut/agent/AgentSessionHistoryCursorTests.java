package io.livecut.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class AgentSessionHistoryCursorTests {
    private static final String JOB="12345678-1234-4234-8234-123456789abc";
    private static final LocalDateTime TIME=LocalDateTime.of(2026,11,9,13,59,58,123456000);
    private static AgentSessionHistoryService.Cursor cursor(){return new AgentSessionHistoryService.Cursor(JOB,
        new AgentSessionHistoryService.Boundary(TIME,"ffffffff-ffff-4fff-8fff-ffffffffffff"),
        new AgentSessionHistoryService.Boundary(TIME,"00000000-0000-4000-8000-000000000001"));}
    private static ObjectNode node(){return (ObjectNode)AgentAnswerValidator.JSON.readTree(Base64.getUrlDecoder().decode(AgentSessionHistoryService.encode(cursor())));}
    private static String encoded(ObjectNode node){return Base64.getUrlEncoder().withoutPadding().encodeToString(ClipRequestService.canonicalJson(node).getBytes(StandardCharsets.UTF_8));}
    private static void invalid(String value){assertEquals("INVALID_AGENT_HISTORY_CURSOR",assertThrows(JobException.class,()->AgentSessionHistoryService.decode(value,JOB)).getCode());}
    @Test void cursorRoundTripsDatabaseMicrosecondsAndUsesUnsignedAsciiUuidOrder(){
        var expected=cursor();String value=AgentSessionHistoryService.encode(expected);assertEquals(expected,AgentSessionHistoryService.decode(value,JOB));
        assertFalse(value.contains("="));assertTrue(expected.ceiling().compareTo(expected.after())>0);
    }
    @Test void cursorRejectsForeignJobWrongVersionAndNonExactShape(){
        assertEquals("INVALID_AGENT_HISTORY_CURSOR",assertThrows(JobException.class,()->AgentSessionHistoryService.decode(AgentSessionHistoryService.encode(cursor()),UUID.randomUUID().toString())).getCode());
        ObjectNode n=node();n.put("v",2);invalid(encoded(n));n=node();n.put("v",1.0);invalid(Base64.getUrlEncoder().withoutPadding().encodeToString(AgentAnswerValidator.JSON.writeValueAsBytes(n)));
        n=node();n.put("extra",true);invalid(encoded(n));n=node();n.remove("after_id");invalid(encoded(n));
    }
    @Test void cursorRejectsNonCanonicalEncodingUuidAndExcessiveInput(){
        String valid=AgentSessionHistoryService.encode(cursor());
        for(String value:List.of("",valid+"=",valid+"\n","%","x".repeat(1025),Base64.getUrlEncoder().withoutPadding().encodeToString((" "+ClipRequestService.canonicalJson(node())).getBytes(StandardCharsets.UTF_8))))invalid(value);
        ObjectNode n=node();n.put("ceiling_id","FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF");invalid(encoded(n));n=node();n.put("after_id","1-1-1-1-1");invalid(encoded(n));n=node();n.putNull("job_id");invalid(encoded(n));
    }
    @Test void cursorRejectsNonMicrosecondOrImpossibleTimeAndReversedTuple(){
        for(String value:List.of("2026-02-30T13:59:58.123456","2026-11-09T13:59:58.123","2026-11-09T13:59:58.123456Z","0999-11-09T13:59:58.123456","2026-11-09T24:00:00.123456")){
            ObjectNode n=node();n.put("after_created_at",value);invalid(encoded(n));
        }
        ObjectNode n=node();n.put("after_created_at","2026-11-09T13:59:58.123457");invalid(encoded(n));
    }
    @Test void limitsUseStrictBoundedDecimalIntegers(){
        assertEquals(20,AgentSessionHistoryService.limit(null));assertEquals(1,AgentSessionHistoryService.limit("1"));assertEquals(30,AgentSessionHistoryService.limit("30"));
        for(String value:List.of("","0","31","-1","+1","01"," 2","1.0","1e1","2,3","1000000000000000"))
            assertEquals("INVALID_AGENT_HISTORY_LIMIT",assertThrows(JobException.class,()->AgentSessionHistoryService.limit(value)).getCode());
    }
}
