package io.livecut.clips;

import static io.livecut.clips.ClipRequestModels.*;
import static io.livecut.clips.ClipPlanValidator.JSON;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Bounded read-only history. Full source/artifact validation remains in the selected-detail API. */
@Service
public class ClipRequestHistoryService {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> CURSOR_FIELDS=Set.of("v","job_id","ceiling_created_at","ceiling_id","after_created_at","after_id");
    // Intentionally omit every JSON/document column; the join reads only the latest revision's hash.
    private static final String SELECT="SELECT r.id,r.job_id,r.video_id,r.instruction,r.status,r.state_version,r.created_at,r.updated_at,r.max_text_calls,r.text_calls_reserved,r.source_hash,r.transcript_hash,r.duration_ms,r.latest_revision,p.plan_hash,CASE WHEN r.source_answer_hash IS NULL THEN 'INSTRUCTION' ELSE 'ANSWER_CITATIONS' END AS source_mode FROM clip_request r LEFT JOIN clip_plan_revision p ON p.request_id=r.id AND p.revision=r.latest_revision WHERE r.job_id=?";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    record Boundary(LocalDateTime createdAt,String id) implements Comparable<Boundary> {
        @Override public int compareTo(Boundary other){int time=createdAt.compareTo(other.createdAt);return time==0?id.compareTo(other.id):time;}
    }
    record Cursor(String jobId,Boundary ceiling,Boundary after) {}
    private record Row(HistoryItem item,Boundary boundary) {}

    public ClipRequestHistoryService(JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public HistoryPage list(String jobId,String requestedLimit,String value){
        String job=ClipRequestService.uuid(jobId);int limit=limit(requestedLimit);
        Cursor cursor=value==null?null:decode(value,job);
        return tx.execute(status->{
            // This existence check is independent of the cursor and never locks business rows.
            if(jdbc.queryForList("SELECT id FROM analysis_job WHERE id=?",String.class,job).isEmpty())
                throw new JobException(HttpStatus.NOT_FOUND,"JOB_NOT_FOUND","Requested analysis job was not found");
            if(cursor!=null)verifyBoundaries(cursor);
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM clip_request WHERE job_id=? AND status IN ('QUEUED','RUNNING')",Long.class,job);
            List<Object> parameters=new ArrayList<>();parameters.add(job);String sql=SELECT;
            if(cursor!=null){
                // Expanded inequalities preserve the existing (job_id,created_at,id) index range.
                sql+=" AND (r.created_at<? OR (r.created_at=? AND r.id<=?)) AND (r.created_at<? OR (r.created_at=? AND r.id<?))";
                parameters.addAll(List.of(cursor.ceiling.createdAt,cursor.ceiling.createdAt,cursor.ceiling.id,cursor.after.createdAt,cursor.after.createdAt,cursor.after.id));
            }
            parameters.add(limit+1);
            List<Row> rows=jdbc.query(sql+" ORDER BY r.created_at DESC,r.id DESC LIMIT ?",this::row,parameters.toArray());
            boolean more=rows.size()>limit;List<Row> page=more?rows.subList(0,limit):rows;
            String next=null;
            if(more){Boundary ceiling=cursor==null?rows.getFirst().boundary:cursor.ceiling;next=encode(new Cursor(job,ceiling,page.getLast().boundary));}
            return new HistoryPage("1.0",job,page.stream().map(Row::item).toList(),limit,next,count==null?0:count);
        });
    }

    private Row row(ResultSet rs,int number)throws SQLException{
        LocalDateTime created=rs.getObject("created_at",LocalDateTime.class),updated=rs.getObject("updated_at",LocalDateTime.class);
        HistoryItem item=new HistoryItem(rs.getString("id"),rs.getString("job_id"),rs.getString("video_id"),rs.getString("instruction"),rs.getString("status"),rs.getLong("state_version"),created.toInstant(ZoneOffset.UTC),updated.toInstant(ZoneOffset.UTC),new Budget(rs.getInt("max_text_calls"),rs.getInt("text_calls_reserved")),new Source(rs.getString("source_hash"),rs.getString("transcript_hash"),rs.getLong("duration_ms")),rs.getLong("latest_revision"),rs.getString("plan_hash"),rs.getString("source_mode"));
        return new Row(item,new Boundary(created,item.id()));
    }

    private void verifyBoundaries(Cursor cursor){
        var rows=jdbc.query("SELECT id,created_at FROM clip_request WHERE job_id=? AND id IN (?,?)",(rs,n)->new Boundary(rs.getObject("created_at",LocalDateTime.class),rs.getString("id")),cursor.jobId,cursor.ceiling.id,cursor.after.id);
        if(!rows.contains(cursor.ceiling)||!rows.contains(cursor.after))throw invalidCursor();
    }

    static int limit(String value){
        if(value==null)return 20;
        if(!value.matches("[1-9][0-9]?"))throw invalidLimit();
        int result=Integer.parseInt(value);if(result>30)throw invalidLimit();return result;
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
            String boundJob=canonicalUuid(node.path("job_id"));if(!job.equals(boundJob))throw invalidCursor();
            Boundary ceiling=boundary(node,"ceiling"),after=boundary(node,"after");
            Cursor cursor=new Cursor(boundJob,ceiling,after);
            if(after.compareTo(ceiling)>0||!encode(cursor).equals(value))throw invalidCursor();return cursor;
        }catch(Exception failure){throw invalidCursor();}
    }

    private static Boundary boundary(JsonNode node,String prefix){
        JsonNode text=node.path(prefix+"_created_at");
        if(!text.isString()||!text.stringValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}"))throw invalidCursor();
        LocalDateTime created=LocalDateTime.parse(text.stringValue(),TIME);if(created.getYear()<1000)throw invalidCursor();
        return new Boundary(created,canonicalUuid(node.path(prefix+"_id")));
    }
    private static String canonicalUuid(JsonNode node){if(!node.isString())throw invalidCursor();String id=node.stringValue();if(!UUID.fromString(id).toString().equals(id))throw invalidCursor();return id;}
    private static JobException invalidCursor(){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_CLIP_HISTORY_CURSOR","History cursor is invalid or its boundaries are unavailable; return to the latest page");}
    private static JobException invalidLimit(){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_CLIP_HISTORY_LIMIT","History limit must be an integer from 1 to 30");}
}
