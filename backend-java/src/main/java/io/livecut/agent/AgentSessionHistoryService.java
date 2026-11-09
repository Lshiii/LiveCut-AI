package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.JSON;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Bounded VIDEO history projection. No source documents, result JSON, stages or owner locks. */
@Service
public class AgentSessionHistoryService {
    private static final long SAFE_INTEGER=9007199254740991L;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> CURSOR_FIELDS=Set.of("v","job_id","ceiling_created_at","ceiling_id","after_created_at","after_id");
    static final String PAGE_SELECT="SELECT s.id,s.job_id,s.video_id,s.created_at,s.source_hash,s.transcript_hash,s.duration_ms,f.question AS first_question FROM agent_session s LEFT JOIN agent_turn f ON f.session_id=s.id AND f.job_id=s.job_id AND f.sequence_number=1 WHERE s.job_id=? AND s.kind='VIDEO'";
    static final String ACTIVE_SELECT="SELECT COUNT(*) FROM agent_turn t JOIN agent_session s ON s.id=t.session_id AND s.job_id=t.job_id WHERE t.job_id=? AND s.kind='VIDEO' AND t.status IN ('QUEUED','RUNNING')";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    record Boundary(LocalDateTime createdAt,String id) implements Comparable<Boundary> {
        @Override public int compareTo(Boundary other){int time=createdAt.compareTo(other.createdAt);return time==0?id.compareTo(other.id):time;}
    }
    record Cursor(String jobId,Boundary ceiling,Boundary after) {}
    private record Row(String id,String jobId,String videoId,Boundary boundary,AgentModels.Source source,String firstQuestion) {}
    private record Counts(int total,int active) {}

    public AgentSessionHistoryService(JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public AgentSessionHistoryModels.Page list(String jobId,String requestedLimit,String value){
        String job=ClipRequestService.uuid(jobId);int limit=limit(requestedLimit);Cursor cursor=value==null?null:decode(value,job);
        return tx.execute(status->{
            if(jdbc.queryForList("SELECT id FROM analysis_job WHERE id=?",String.class,job).isEmpty())
                throw new JobException(HttpStatus.NOT_FOUND,"JOB_NOT_FOUND","Requested analysis job was not found");
            if(cursor!=null)verifyBoundaries(cursor);
            Long active=jdbc.queryForObject(ACTIVE_SELECT,Long.class,job);
            if(active==null||active<0||active>SAFE_INTEGER)throw invalidHistory();
            List<Object> parameters=new ArrayList<>();parameters.add(job);String sql=PAGE_SELECT;
            if(cursor!=null){
                sql+=" AND (s.created_at<? OR (s.created_at=? AND s.id<=?)) AND (s.created_at<? OR (s.created_at=? AND s.id<?))";
                parameters.addAll(List.of(cursor.ceiling.createdAt,cursor.ceiling.createdAt,cursor.ceiling.id,cursor.after.createdAt,cursor.after.createdAt,cursor.after.id));
            }
            parameters.add(limit+1);
            List<Row> rows=jdbc.query(sql+" ORDER BY s.created_at DESC,s.id DESC LIMIT ?",this::row,parameters.toArray());
            boolean more=rows.size()>limit;List<Row> page=more?rows.subList(0,limit):rows;
            Map<String,Counts> counts=counts(job,page);
            var items=page.stream().map(row->{
                Counts count=counts.getOrDefault(row.id,new Counts(0,0));String question=row.firstQuestion;
                if(count.total==0?question!=null:question==null||question.isBlank()||question.length()>2000)throw invalidHistory();
                return new AgentSessionHistoryModels.Item(row.id,row.jobId,row.videoId,row.boundary.createdAt.toInstant(ZoneOffset.UTC),row.source,question,count.total,count.active);
            }).toList();
            String next=more?encode(new Cursor(job,cursor==null?rows.getFirst().boundary:cursor.ceiling,page.getLast().boundary)):null;
            return new AgentSessionHistoryModels.Page("1.0",job,items,limit,next,active);
        });
    }
    private Map<String,Counts> counts(String job,List<Row> page){
        if(page.isEmpty())return Map.of();
        List<Object> parameters=new ArrayList<>();parameters.add(job);page.forEach(row->parameters.add(row.id));
        String placeholders=String.join(",",Collections.nCopies(page.size(),"?"));
        String sql="SELECT session_id,COUNT(*) AS turn_count,SUM(status IN ('QUEUED','RUNNING')) AS active_turn_count FROM agent_turn WHERE job_id=? AND session_id IN ("+placeholders+") GROUP BY session_id";
        Map<String,Counts> result=new HashMap<>();
        jdbc.query(sql,rs->{long total=rs.getLong("turn_count"),active=rs.getLong("active_turn_count");
            if(total<0||total>100||active<0||active>total)throw invalidHistory();
            result.put(rs.getString("session_id"),new Counts((int)total,(int)active));
        },parameters.toArray());
        return result;
    }
    private Row row(ResultSet rs,int number)throws SQLException{
        String id=rs.getString("id"),job=rs.getString("job_id"),video=rs.getString("video_id"),hash=rs.getString("source_hash"),transcript=rs.getString("transcript_hash");
        long duration=rs.getLong("duration_ms");LocalDateTime created=rs.getObject("created_at",LocalDateTime.class);
        if(!canonicalUuid(id)||!canonicalUuid(job)||!canonicalUuid(video)||hash==null||!hash.matches("[a-f0-9]{64}")||transcript==null||!transcript.matches("[a-f0-9]{64}")||duration<=0||duration>SAFE_INTEGER||created==null||created.getYear()<1000||created.getYear()>9999)throw invalidHistory();
        return new Row(id,job,video,new Boundary(created,id),new AgentModels.Source(hash,transcript,duration),rs.getString("first_question"));
    }
    private void verifyBoundaries(Cursor cursor){
        var rows=jdbc.query("SELECT id,created_at FROM agent_session WHERE job_id=? AND kind='VIDEO' AND id IN (?,?)",(rs,n)->new Boundary(rs.getObject("created_at",LocalDateTime.class),rs.getString("id")),cursor.jobId,cursor.ceiling.id,cursor.after.id);
        if(!rows.contains(cursor.ceiling)||!rows.contains(cursor.after))throw invalidCursor();
    }
    static int limit(String value){
        if(value==null)return 20;if(!value.matches("[1-9][0-9]?"))throw invalidLimit();int result=Integer.parseInt(value);if(result>30)throw invalidLimit();return result;
    }
    static String encode(Cursor cursor){
        JsonNode node=JSON.valueToTree(Map.of("v",1,"job_id",cursor.jobId,"ceiling_created_at",TIME.format(cursor.ceiling.createdAt),"ceiling_id",cursor.ceiling.id,"after_created_at",TIME.format(cursor.after.createdAt),"after_id",cursor.after.id));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ClipRequestService.canonicalJson(node).getBytes(StandardCharsets.UTF_8));
    }
    static Cursor decode(String value,String job){
        try{
            if(value.length()>1024||!value.matches("[A-Za-z0-9_-]+"))throw invalidCursor();
            byte[] bytes=Base64.getUrlDecoder().decode(value);JsonNode node=JSON.readTree(bytes);
            if(!node.isObject()||!node.propertyNames().equals(CURSOR_FIELDS)||!node.path("v").isIntegralNumber()||node.path("v").longValue()!=1)throw invalidCursor();
            if(!Arrays.equals(bytes,ClipRequestService.canonicalJson(node).getBytes(StandardCharsets.UTF_8)))throw invalidCursor();
            String boundJob=uuid(node.path("job_id"));if(!job.equals(boundJob))throw invalidCursor();
            Cursor cursor=new Cursor(boundJob,boundary(node,"ceiling"),boundary(node,"after"));
            if(cursor.after.compareTo(cursor.ceiling)>0||!encode(cursor).equals(value))throw invalidCursor();return cursor;
        }catch(Exception failure){throw invalidCursor();}
    }
    private static Boundary boundary(JsonNode node,String prefix){
        JsonNode text=node.path(prefix+"_created_at");
        if(!text.isString()||!text.stringValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}"))throw invalidCursor();
        LocalDateTime created=LocalDateTime.parse(text.stringValue(),TIME);if(created.getYear()<1000)throw invalidCursor();return new Boundary(created,uuid(node.path(prefix+"_id")));
    }
    private static String uuid(JsonNode node){if(!node.isString()||!canonicalUuid(node.stringValue()))throw invalidCursor();return node.stringValue();}
    private static boolean canonicalUuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException invalid){return false;}}
    private static JobException invalidCursor(){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_AGENT_HISTORY_CURSOR","History cursor is invalid or its boundaries are unavailable; return to the latest page");}
    private static JobException invalidLimit(){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_AGENT_HISTORY_LIMIT","History limit must be an integer from 1 to 30");}
    private static JobException invalidHistory(){return new JobException(HttpStatus.CONFLICT,"AGENT_HISTORY_INVALID","Stored conversation history metadata failed validation");}
}
