package io.livecut.agent;

import static org.junit.jupiter.api.Assertions.*;
import static io.livecut.agent.AgentAnswerValidator.JSON;
import java.util.*;
import java.util.function.Consumer;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Deliberately synthetic metadata: checks provenance, not recognition accuracy. */
class AgentVisualEvidenceTests {
    private ObjectNode answer(){
        var answer=JSON.createObjectNode().put("answer_scope","VIDEO");answer.putArray("citations");
        frame(answer.putArray("visual_evidence").addObject(),"f0001",1000,1000,1000,"1/1000");
        answer.putArray("visual_citations").addObject().put("frame_id","f0001").put("explanation","合成架构图");
        var call=answer.putArray("tool_calls").addObject().put("step",1).put("tool_name","inspect_frames");call.putObject("arguments").putArray("timestamps_ms").add(1000);call.putArray("result_utterance_ids");call.putArray("result_frame_ids").add("f0001");return answer;
    }
    private static void frame(ObjectNode frame,String id,long requested,long actual,long pts,String base){frame.put("frame_id",id).put("requested_ms",requested).put("timestamp_ms",actual).put("source_pts",pts).put("source_time_base",base).put("sha256","a".repeat(64)).put("bytes",100).put("width",320).put("height",180);}
    private static void valid(ObjectNode answer){assertDoesNotThrow(()->AgentVisualEvidence.validate(answer,100000));}
    private static void invalid(ObjectNode answer){assertEquals("INVALID_AGENT_ANSWER",assertThrows(JobException.class,()->AgentVisualEvidence.validate(answer,100000)).getCode());}
    @Test void frameOnlyVideoAndMixedCitationAreValid(){var a=answer();valid(a);a.putArray("citations").addObject().put("utterance_id","u1");valid(a);}
    @Test void unsafeWrongRoundedAndOutOfRangeTimesAreRejected(){
        for(Consumer<ObjectNode> mutate:List.<Consumer<ObjectNode>>of(f->f.put("source_pts",9007199254740992L),f->f.put("source_time_base","0/1000"),f->f.put("timestamp_ms",999),f->f.put("requested_ms",100001),f->f.put("source_pts",999),f->f.put("requested_ms",0))){var a=answer();mutate.accept((ObjectNode)a.path("visual_evidence").get(0));invalid(a);}
        var rounded=answer();frame((ObjectNode)rounded.path("visual_evidence").get(0),"f0001",1000,1001,2001,"1/2000");valid(rounded);
    }
    @Test void sameActualRationalFrameCannotMasqueradeAsAnotherObservation(){var a=answer();frame(a.withArray("visual_evidence").addObject(),"f0002",1001,1001,2001,"1/2000");frame((ObjectNode)a.path("visual_evidence").get(0),"f0001",1000,1001,2001,"1/2000");((ObjectNode)a.path("visual_evidence").get(1)).put("source_pts",6003).put("source_time_base","1/6000");((ObjectNode)a.path("tool_calls").get(0).path("arguments")).putArray("timestamps_ms").add(1000).add(1001);((ObjectNode)a.path("tool_calls").get(0)).putArray("result_frame_ids").add("f0001").add("f0002");invalid(a);}
    @Test void evidenceAndTraceMustBeExactlyOneSetAndOriginalObservationOrder(){var a=answer();((ObjectNode)a.path("tool_calls").get(0)).putArray("result_frame_ids");invalid(a);a=answer();a.putArray("visual_evidence");invalid(a);a=answer();((ObjectNode)a.path("visual_evidence").get(0)).put("frame_id","f0002");invalid(a);}
    @Test void onlyRealRequestedFramesAndSeparateASRAreAccepted(){var a=answer();((ObjectNode)a.path("tool_calls").get(0).path("arguments")).putArray("timestamps_ms").add(2000);invalid(a);a=answer();((ObjectNode)a.path("tool_calls").get(0)).putArray("result_utterance_ids").add("u1");invalid(a);}
    @Test void failedRepeatedAndOverBudgetRequestsCanRemainEmptyObservations(){var a=answer();for(int step=2;step<=4;step++){var call=a.withArray("tool_calls").addObject().put("step",step).put("tool_name","inspect_clip");call.putObject("arguments").put("start_ms",step*13000).put("end_ms",step*13000+12000);call.putArray("result_frame_ids");call.putArray("result_utterance_ids");}valid(a);var repeat=a.withArray("tool_calls");repeat.removeAll();repeat.add(answer().path("tool_calls").get(0));var call=repeat.addObject().put("step",2).put("tool_name","inspect_frames");call.putObject("arguments").putArray("timestamps_ms").add(1000);call.putArray("result_frame_ids");call.putArray("result_utterance_ids");valid(a);call.withArray("result_frame_ids").add("f0001");invalid(a);}
    @Test void clipIsHalfOpenAndSamplingGridIsExact(){var a=answer();var c=(ObjectNode)a.path("tool_calls").get(0);c.put("tool_name","inspect_clip");c.putObject("arguments").put("start_ms",1000).put("end_ms",1500);valid(a);((ObjectNode)a.path("visual_evidence").get(0)).put("timestamp_ms",1500).put("source_pts",1500);invalid(a);a=answer();c=(ObjectNode)a.path("tool_calls").get(0);c.put("tool_name","inspect_clip");c.putObject("arguments").put("start_ms",999).put("end_ms",1500);invalid(a);}
    @Test void citationsCannotReferToUnobservedOrDuplicateFrames(){var a=answer();a.withArray("visual_citations").add(a.path("visual_citations").get(0));invalid(a);a=answer();((ObjectNode)a.path("visual_citations").get(0)).put("frame_id","f0002");invalid(a);a=answer();a.putArray("visual_citations");invalid(a);a.put("answer_scope","INSUFFICIENT");valid(a);}
    @Test void frameCountBytesDimensionsAndExtraFieldsFailClosed(){for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(f->f.put("bytes",1048577),f->f.put("width",1281),f->f.put("height",0),f->f.put("path","secret"),f->f.put("sha256","bad"))){var a=answer();change.accept((ObjectNode)a.path("visual_evidence").get(0));invalid(a);}}
    @Test void successfulToolBatchesAreAtomicButDifferentClipIntervalsMayReuseTheirOneFrame(){var a=answer();((ObjectNode)a.path("tool_calls").get(0).path("arguments")).putArray("timestamps_ms").add(1000).add(2000);invalid(a);a=answer();var first=(ObjectNode)a.path("tool_calls").get(0);first.put("tool_name","inspect_clip");first.putObject("arguments").put("start_ms",1000).put("end_ms",1100);var second=a.withArray("tool_calls").addObject().put("step",2).put("tool_name","inspect_clip");second.putObject("arguments").put("start_ms",1000).put("end_ms",1200);second.putArray("result_utterance_ids");second.putArray("result_frame_ids").add("f0001");valid(a);}
    @Test void roundedMillisecondsCannotHideAPtsBeforeTheRequestedPosition(){var a=answer();frame((ObjectNode)a.path("visual_evidence").get(0),"f0001",1000,1000,9999,"1/10000");invalid(a);frame((ObjectNode)a.path("visual_evidence").get(0),"f0001",1000,1000,10000,"1/10000");valid(a);}
    @Test void roundedMillisecondsCannotHideAPtsBeyondTheAllowedForwardWindow(){var a=answer();frame((ObjectNode)a.path("visual_evidence").get(0),"f0001",1000,2000,20001,"1/10000");invalid(a);frame((ObjectNode)a.path("visual_evidence").get(0),"f0001",1000,2000,20000,"1/10000");valid(a);var call=(ObjectNode)a.path("tool_calls").get(0);call.put("tool_name","inspect_clip");call.putObject("arguments").put("start_ms",1000).put("end_ms",2000);invalid(a);}
    @Test void aRealFrameBeforeClipEndRemainsValidWhenItsDisplayTimeRoundsToEnd(){var a=answer();frame((ObjectNode)a.path("visual_evidence").get(0),"f0001",1000,1500,14999,"1/10000");var call=(ObjectNode)a.path("tool_calls").get(0);call.put("tool_name","inspect_clip");call.putObject("arguments").put("start_ms",1000).put("end_ms",1500);valid(a);((ObjectNode)a.path("visual_evidence").get(0)).put("source_pts",15000);invalid(a);}
}
