package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** Structural, time and observation provenance checks; no claim of visual semantic truth. */
public final class AgentVisualEvidence {
    private AgentVisualEvidence() {}
    public static final long SAFE_INTEGER=9007199254740991L;
    public static void validate(JsonNode answer,long duration){
        if(duration<=0||duration>SAFE_INTEGER)throw bad("Invalid visual source duration");
        Map<String,JsonNode> frames=new LinkedHashMap<>();Set<Long> requested=new HashSet<>();Set<String> actualPositions=new HashSet<>();long total=0;
        for(JsonNode frame:array(answer,"visual_evidence",32)){
            fields(frame,Set.of("frame_id","requested_ms","timestamp_ms","source_pts","source_time_base","sha256","bytes","width","height"));
            String id=text(frame,"frame_id",5),expected=String.format(Locale.ROOT,"f%04d",frames.size()+1);
            if(!id.equals(expected))throw bad("Frame IDs must be contiguous observation order");
            long request=safe(frame,"requested_ms"),actual=safe(frame,"timestamp_ms"),pts=safe(frame,"source_pts");
            if(request>=duration||actual>=duration||actual<request||actual-request>1000||!requested.add(request))throw bad("Invalid or duplicate frame time");
            String base=text(frame,"source_time_base",21);
            if(!base.matches("[1-9][0-9]{0,9}/[1-9][0-9]{0,9}"))throw bad("Invalid source time base");
            String[] ratio=base.split("/");
            BigInteger numerator=BigInteger.valueOf(pts).multiply(new BigInteger(ratio[0])),denominator=new BigInteger(ratio[1]),gcd=numerator.gcd(denominator);
            if(!actualPositions.add(numerator.divide(gcd)+"/"+denominator.divide(gcd)))throw bad("Duplicate actual source frame");
            BigInteger exactMillis=numerator.multiply(BigInteger.valueOf(1000));
            if(exactMillis.compareTo(BigInteger.valueOf(request).multiply(denominator))<0
                    ||exactMillis.compareTo(BigInteger.valueOf(request+1000).multiply(denominator))>0
                    ||exactMillis.compareTo(BigInteger.valueOf(duration).multiply(denominator))>=0)throw bad("Actual source PTS is outside the requested media range");
            BigDecimal millis=BigDecimal.valueOf(pts).multiply(new BigDecimal(ratio[0])).multiply(BigDecimal.valueOf(1000))
                .divide(new BigDecimal(ratio[1]),0,RoundingMode.HALF_UP);
            if(millis.compareTo(BigDecimal.valueOf(actual))!=0)throw bad("Frame time must come from source PTS");
            if(!text(frame,"sha256",64).matches("[a-f0-9]{64}"))throw bad("Invalid frame hash");
            long bytes=safe(frame,"bytes");if(bytes<4||bytes>1048576)throw bad("Invalid frame byte size");total+=bytes;
            for(String dimension:List.of("width","height")){long size=safe(frame,dimension);if(size<1||size>1280)throw bad("Invalid frame dimensions");}
            frames.put(id,frame);
        }
        if(total>16777216)throw bad("Visual frame byte budget exceeded");
        Set<String> produced=new HashSet<>();Set<String> signatures=new HashSet<>();
        for(JsonNode call:array(answer,"tool_calls",4)){
            String tool=text(call,"tool_name",40);JsonNode args=call.path("arguments"),ids=array(call,"result_frame_ids",24);
            if(!Set.of("inspect_frames","inspect_clip").contains(tool)){if(!ids.isEmpty())throw bad("Text tools cannot produce frames");continue;}
            if(!array(call,"result_utterance_ids",0).isEmpty())throw bad("Visual tools cannot produce ASR evidence");
            Set<Long> positions=new LinkedHashSet<>();long end=-1;
            if(tool.equals("inspect_frames")){
                fields(args,Set.of("timestamps_ms"));JsonNode list=array(args,"timestamps_ms",4);if(list.isEmpty())throw bad("No requested frame time");
                long previous=-1;for(JsonNode item:list){if(!item.isIntegralNumber()||!item.canConvertToLong())throw bad("Invalid frame request time");long value=item.longValue();
                    if(value<0||value>SAFE_INTEGER||value>=duration||value<=previous)throw bad("Frame request times must be unique and increasing");positions.add(value);previous=value;}
            }else{
                fields(args,Set.of("start_ms","end_ms"));long start=safe(args,"start_ms");end=safe(args,"end_ms");
                if(end<=start||end>duration||end-start>12000)throw bad("Invalid sampled clip interval");
                for(long t=start;t<end;t+=500)positions.add(t);
            }
            // Entirely repeated observations must not create another executed tool step.
            String signature=tool+":"+io.livecut.clips.ClipRequestService.canonicalJson(args);boolean fresh=signatures.add(signature);if(!fresh&&!ids.isEmpty())throw bad("Repeated visual tool execution");
            if(!ids.isEmpty()&&ids.size()!=positions.size())throw bad("A visual tool observation must include its entire requested batch");
            Set<String> seen=new HashSet<>();long previousRequest=-1;
            for(JsonNode ref:ids){if(!ref.isString()||!seen.add(ref.stringValue()))throw bad("Invalid repeated frame reference");JsonNode frame=frames.get(ref.stringValue());
                if(frame==null||!positions.contains(frame.path("requested_ms").longValue()))throw bad("Frame not requested by this tool");
                long request=frame.path("requested_ms").longValue();if(request<=previousRequest)throw bad("Tool frames must follow requested time order");previousRequest=request;
                if(end>=0&&compareActualMillis(frame,end)>=0)throw bad("Frame outside sampled clip interval");
                if(!produced.contains(ref.stringValue())&&!ref.stringValue().equals(String.format(Locale.ROOT,"f%04d",produced.size()+1)))throw bad("Frame IDs must follow first observation order");produced.add(ref.stringValue());}
        }
        if(!produced.equals(frames.keySet()))throw bad("Every visual frame must have a tool observation");
        String previous="";for(JsonNode citation:array(answer,"visual_citations",12)){
            fields(citation,Set.of("frame_id","explanation"));String id=text(citation,"frame_id",5);text(citation,"explanation",1000);
            if(!frames.containsKey(id)||id.compareTo(previous)<=0)throw bad("Visual citations must uniquely reference observed frames in ID order");previous=id;}
        if("VIDEO".equals(answer.path("answer_scope").asText())&&answer.path("citations").isEmpty()&&answer.path("visual_citations").isEmpty())throw bad("VIDEO answers require source evidence");
    }
    private static long safe(JsonNode node,String field){long value=number(node,field);if(value>SAFE_INTEGER)throw bad("Unsafe integer in "+field);return value;}
    private static int compareActualMillis(JsonNode frame,long millis){String[] base=frame.path("source_time_base").asText().split("/");return BigInteger.valueOf(frame.path("source_pts").longValue()).multiply(new BigInteger(base[0])).multiply(BigInteger.valueOf(1000)).compareTo(BigInteger.valueOf(millis).multiply(new BigInteger(base[1])));}
    private static JsonNode array(JsonNode node,String field,int maximum){JsonNode value=node.path(field);if(!value.isArray()||value.size()>maximum)throw bad("Invalid "+field);return value;}
    private static JobException bad(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_AGENT_ANSWER",message);}
}
