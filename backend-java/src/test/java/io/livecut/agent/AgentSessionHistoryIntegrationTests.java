package io.livecut.agent;

import static io.livecut.agent.AgentAnswerValidator.JSON;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import io.livecut.LivecutBackendApplication;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Real isolated MySQL; every source, conversation and result in this suite is synthetic. */
@EnabledIfEnvironmentVariable(named="LIVECUT_DB_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentSessionHistoryIntegrationTests {
    @TempDir static Path storage;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private AgentSessionHistoryService history;
    private URI base;
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<String> jobs=new ArrayList<>(),videos=new ArrayList<>();
    private record Fixture(String job,String video) {}
    @BeforeAll void start(){
        assertTrue(System.getenv("LIVECUT_DB_URL").matches("jdbc:mysql://[^/]+/livecut_test(?:_topics)?(?:\\?.*)?"));
        app=new SpringApplicationBuilder(LivecutBackendApplication.class).run("--server.port=0","--server.address=127.0.0.1","--livecut.storage.root="+storage,
            "--livecut.worker.api-key=history-synthetic-only","--livecut.jobs.scan-enabled=false","--livecut.uploads.scan-enabled=false","--livecut.events.scan-enabled=false",
            "--spring.main.banner-mode=off","--logging.level.root=WARN");
        jdbc=app.getBean(JdbcTemplate.class);history=app.getBean(AgentSessionHistoryService.class);
        base=URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port"));
    }
    @AfterEach void cleanup(){for(String id:jobs)jdbc.update("DELETE FROM analysis_job WHERE id=?",id);for(String id:videos)jdbc.update("DELETE FROM video_asset WHERE id=?",id);jobs.clear();videos.clear();}
    @AfterAll void close(){if(app!=null)app.close();}
    @Test void emptyHistoryAndHttpContractAreSmallAndExact()throws Exception{
        var f=fixture();var empty=read(f.job,"",200);assertEquals(Set.of("schema_version","job_id","items","limit","next_cursor","active_count"),empty.propertyNames());
        assertEquals("1.0",empty.path("schema_version").asText());assertEquals(20,empty.path("limit").asInt());assertTrue(empty.path("items").isEmpty());assertTrue(empty.path("next_cursor").isNull());assertEquals(0,empty.path("active_count").asInt());
        String id=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.123456");
        var page=read(f.job,"?limit=1",200);var item=page.path("items").get(0);
        assertEquals(Set.of("id","job_id","video_id","created_at","source","first_question","turn_count","active_turn_count"),item.propertyNames());
        assertEquals(Set.of("video_sha256","transcript_hash","duration_ms"),item.path("source").propertyNames());assertEquals(id,item.path("id").asText());assertEquals("2026-11-09T12:00:00.123456Z",item.path("created_at").asText());assertTrue(item.path("first_question").isNull());
        assertEquals(0,item.path("turn_count").asInt());assertEquals(0,item.path("active_turn_count").asInt());
    }
    @Test void keysetUsesFullMicrosecondsAndAsciiHighBitUuidTiesWithoutRepeats(){
        var f=fixture();String low="00000000-0000-4000-8000-000000000001",high="ffffffff-ffff-4fff-8fff-ffffffffffff",middle="80000000-0000-4000-8000-000000000001";
        session(f,low,"2026-11-09T12:00:00.123456");session(f,high,"2026-11-09T12:00:00.123456");session(f,middle,"2026-11-09T12:00:00.123457");
        List<String> found=new ArrayList<>();String cursor=null;
        do{var page=history.list(f.job,"1",cursor);found.add(page.items().getFirst().id());cursor=page.nextCursor();}while(cursor!=null);
        assertEquals(List.of(middle,high,low),found);assertEquals(3,new HashSet<>(found).size());
    }
    @Test void ceilingAndImmutableTupleIgnoreNewerInsertAndStatusChangesWhileActiveCountIncludesOffPageWork(){
        var f=fixture();String old=session(f,UUID.randomUUID().toString(),"2026-11-09T11:00:00.000000"),newer=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000");
        String active=turn(f,old,1,"RUNNING","完整首问"),terminal=turn(f,newer,1,"FAILED","较新对话");
        var first=history.list(f.job,"1",null);assertEquals(newer,first.items().getFirst().id());assertEquals(1,first.activeCount());assertEquals(0,first.items().getFirst().activeTurnCount());
        String newest=session(f,UUID.randomUUID().toString(),"2026-11-09T13:00:00.000000");turn(f,newest,1,"QUEUED","ceiling之外的活动轮");
        jdbc.update("UPDATE agent_turn SET state_version=state_version+1,status='CANCELLED' WHERE id=?",terminal);
        var next=history.list(f.job,"1",first.nextCursor());assertEquals(old,next.items().getFirst().id());assertEquals(2,next.activeCount());assertEquals(1,next.items().getFirst().activeTurnCount());assertNull(next.nextCursor());
        jdbc.update("UPDATE agent_turn SET status='SUCCEEDED' WHERE id=?",active);assertEquals(newest,history.list(f.job,"1",null).items().getFirst().id());
    }
    @Test void onlyVideoSessionsParticipateInPageBoundariesAndGlobalCounts()throws Exception{
        var f=fixture();String video=session(f,UUID.randomUUID().toString(),"2026-11-09T11:00:00.000000"),caption=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000");
        turn(f,video,1,"CANCELLED","视频首问");turn(f,caption,1,"RUNNING","字幕内容不能进入视频历史");caption(caption);
        var page=history.list(f.job,"1",null);assertEquals(List.of(video),page.items().stream().map(AgentSessionHistoryModels.Item::id).toList());assertEquals(0,page.activeCount());assertNull(page.nextCursor());
        var time=LocalDateTime.parse("2026-11-09T12:00:00");String cursor=AgentSessionHistoryService.encode(new AgentSessionHistoryService.Cursor(f.job,new AgentSessionHistoryService.Boundary(time,caption),new AgentSessionHistoryService.Boundary(time,caption)));
        assertError(f.job,"?cursor="+cursor,400,"INVALID_AGENT_HISTORY_CURSOR");
    }
    @Test void cursorRejectsForeignMissingOrTamperedRealBoundariesAndBadHttpParameters()throws Exception{
        var f=fixture();var foreign=fixture();session(f,UUID.randomUUID().toString(),"2026-11-09T11:00:00.000000");session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000");
        String value=history.list(f.job,"1",null).nextCursor();assertError(foreign.job,"?cursor="+value,400,"INVALID_AGENT_HISTORY_CURSOR");
        var cursor=AgentSessionHistoryService.decode(value,f.job);String bad=AgentSessionHistoryService.encode(new AgentSessionHistoryService.Cursor(f.job,new AgentSessionHistoryService.Boundary(cursor.ceiling().createdAt().plusNanos(1000),cursor.ceiling().id()),cursor.after()));
        assertError(f.job,"?cursor="+bad,400,"INVALID_AGENT_HISTORY_CURSOR");
        for(String limit:List.of("","0","31","01","1.0","-1","1,2"))assertError(f.job,"?limit="+limit,400,"INVALID_AGENT_HISTORY_LIMIT");
        for(String c:List.of("","%25","x".repeat(1025)))assertError(f.job,"?cursor="+c,400,"INVALID_AGENT_HISTORY_CURSOR");
        assertError(UUID.randomUUID().toString(),"",404,"JOB_NOT_FOUND");caption(cursor.after().id());assertError(f.job,"?cursor="+value,400,"INVALID_AGENT_HISTORY_CURSOR");
    }
    @Test void fullQuestionsAndAllTerminalTurnCountsArePreservedWithoutPrivateDocuments(){
        var f=fixture();String id=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000"),question="  完整问题"+"问".repeat(1990);
        turn(f,id,1,"FAILED",question);turn(f,id,2,"CANCELLED","第二轮");turn(f,id,3,"SUCCEEDED","第三轮");turn(f,id,4,"RUNNING","第四轮");
        var item=history.list(f.job,null,null).items().getFirst();assertEquals(question,item.firstQuestion());assertEquals(4,item.turnCount());assertEquals(1,item.activeTurnCount());
        String json=JSON.writeValueAsString(item);for(String field:List.of("source_json","history_json","result","stage","question_id","idempotency_key","request_hash","caption_context","transcript_key","analysis_key","budget"))assertFalse(json.contains('"'+field+'"'));
    }
    @Test void largeFrozenJsonDoesNotChangeResponseOrQueryCountAndNoStageOrArtifactsAreRead()throws Exception{
        var f=fixture();String id=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000");String turn=turn(f,id,1,"FAILED","摘要问题");
        var probe=new SqlProbe(app.getBean(DataSource.class));var service=probe.service();String before=JSON.writeValueAsString(service.list(f.job,null,null));assertEquals(4,probe.sql.size());assertProjectionSql(probe.sql);
        String huge=JSON.writeValueAsString(Map.of("synthetic_padding","大".repeat(400_000)));jdbc.update("UPDATE agent_session SET source_json=? WHERE id=?",huge,id);jdbc.update("UPDATE agent_turn SET history_json=?,result_json=?,caption_context_json=? WHERE id=?",huge,huge,huge,turn);
        probe.sql.clear();assertEquals(before,JSON.writeValueAsString(service.list(f.job,null,null)));assertEquals(4,probe.sql.size());assertProjectionSql(probe.sql);assertTrue(before.getBytes(StandardCharsets.UTF_8).length<2000);
        for(int i=0;i<29;i++)session(f,UUID.randomUUID().toString(),"2026-11-09T11:00:00.000000");probe.sql.clear();assertEquals(30,service.list(f.job,"30",null).items().size());assertEquals(4,probe.sql.size());assertProjectionSql(probe.sql);
        assertTrue(probe.readOnlySeen);assertTrue(probe.repeatableReadSeen);
    }
    @Test void metadataDoesNotWaitForAnalysisSessionOrTurnWriteLocks()throws Exception{
        var f=fixture();String id=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000"),turn=turn(f,id,1,"RUNNING","锁隔离");
        var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));tx.execute(status->{
            jdbc.queryForObject("SELECT id FROM analysis_job WHERE id=? FOR UPDATE",String.class,f.job);jdbc.queryForObject("SELECT id FROM agent_session WHERE id=? FOR UPDATE",String.class,id);jdbc.queryForObject("SELECT id FROM agent_turn WHERE id=? FOR UPDATE",String.class,turn);
            var future=CompletableFuture.supplyAsync(()->history.list(f.job,"20",null));assertDoesNotThrow(()->assertEquals(id,future.get(5,TimeUnit.SECONDS).items().getFirst().id()));return null;
        });
    }
    @Test void pageAndGlobalCountsUseOneConsistentReadOnlySnapshotDuringConcurrentStatusCommit()throws Exception{
        var f=fixture();String id=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000"),turn=turn(f,id,1,"RUNNING","一致快照");
        var probe=new SqlProbe(app.getBean(DataSource.class));var pageStarted=new CountDownLatch(1);var resume=new CountDownLatch(1);
        probe.hook=sql->{if(sql.startsWith(AgentSessionHistoryService.PAGE_SELECT)){pageStarted.countDown();try{if(!resume.await(5,TimeUnit.SECONDS))throw new AssertionError("test page was not released");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}};
        var service=probe.service();var future=CompletableFuture.supplyAsync(()->service.list(f.job,null,null));
        try{assertTrue(pageStarted.await(5,TimeUnit.SECONDS));jdbc.update("UPDATE agent_turn SET status='CANCELLED' WHERE id=?",turn);}finally{resume.countDown();}
        var page=future.get(5,TimeUnit.SECONDS);assertEquals(1,page.activeCount());assertEquals(1,page.items().getFirst().activeTurnCount());assertEquals(0,history.list(f.job,null,null).activeCount());
    }
    @Test void metadataGetDoesNotMutateBusinessRowsBudgetsOrLegacyFullSnapshots()throws Exception{
        var f=fixture();String id=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000");turn(f,id,1,"QUEUED","旧完整接口保持");
        var agents=app.getBean(AgentService.class);var full=JSON.valueToTree(agents.list(f.job));var detail=JSON.valueToTree(agents.getSession(id));String before=state(f.job);
        read(f.job,"?limit=1",200);read(f.job,"",200);assertEquals(before,state(f.job));assertEquals(full,JSON.valueToTree(agents.list(f.job)));assertEquals(detail,JSON.valueToTree(agents.getSession(id)));
        assertTrue(full.path("items").get(0).has("turns"));assertTrue(detail.path("turns").get(0).has("stage"));assertTrue(detail.path("turns").get(0).has("budget"));
    }
    @Test void invalidStoredMetadataIsRejectedWithoutRepairingRows(){
        var f=fixture();String id=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000");turn(f,id,2,"FAILED","缺首轮");String before=state(f.job);assertEquals("AGENT_HISTORY_INVALID",failure(()->history.list(f.job,null,null)));assertEquals(before,state(f.job));
        turn(f,id,1,"FAILED","首轮恢复");jdbc.update("UPDATE agent_session SET transcript_hash=? WHERE id=?","g".repeat(64),id);before=state(f.job);assertEquals("AGENT_HISTORY_INVALID",failure(()->history.list(f.job,null,null)));assertEquals(before,state(f.job));
        jdbc.update("UPDATE agent_session SET transcript_hash=? WHERE id=?","d".repeat(64),id);jdbc.update("UPDATE agent_turn SET question=? WHERE session_id=? AND sequence_number=1","x".repeat(2001),id);before=state(f.job);assertEquals("AGENT_HISTORY_INVALID",failure(()->history.list(f.job,null,null)));assertEquals(before,state(f.job));
        jdbc.update("UPDATE agent_turn SET question='合法首问' WHERE session_id=? AND sequence_number=1",id);for(int sequence=3;sequence<=101;sequence++)turn(f,id,sequence,"FAILED","合成轮数反例");before=state(f.job);assertEquals("AGENT_HISTORY_INVALID",failure(()->history.list(f.job,null,null)));assertEquals(before,state(f.job));
    }
    @Test void existingIndexesAndProjectionPlanAreInspectableWithoutAddingIndexes(){
        var f=fixture();String id=session(f,UUID.randomUUID().toString(),"2026-11-09T12:00:00.000000");turn(f,id,1,"RUNNING","explain合成例");
        assertFalse(jdbc.queryForList("SHOW INDEX FROM agent_session WHERE Key_name='idx_agent_session_job'").isEmpty());assertFalse(jdbc.queryForList("SHOW INDEX FROM agent_turn WHERE Key_name='uk_agent_turn_sequence'").isEmpty());assertFalse(jdbc.queryForList("SHOW INDEX FROM agent_turn WHERE Key_name='idx_agent_turn_owner'").isEmpty());
        var page=jdbc.queryForList("EXPLAIN "+AgentSessionHistoryService.PAGE_SELECT+" ORDER BY s.created_at DESC,s.id DESC LIMIT ?",f.job,21);var active=jdbc.queryForList("EXPLAIN "+AgentSessionHistoryService.ACTIVE_SELECT,f.job);
        assertTrue(page.stream().anyMatch(row->"s".equals(row.get("table"))&&String.valueOf(row.get("possible_keys")).contains("idx_agent_session_job")));assertFalse(active.isEmpty());
        System.out.println("Synthetic agent history EXPLAIN page="+page+" active="+active);
    }
    private Fixture fixture(){
        String video=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();videos.add(video);jobs.add(job);
        jdbc.update("INSERT INTO video_asset(id,content_hash,source_key,original_filename,size_bytes,media_validation_status) VALUES (?,?,?,?,1,'VALID')",video,"a".repeat(64),"history-synthetic/"+video,"history-synthetic.mp4");
        jdbc.update("INSERT INTO analysis_job(id,video_id,generation,idempotency_key,request_hash,config_hash,config_json,status,max_text_calls,max_asr_submissions) VALUES (?,?,1,?,?,?,'{}','REVIEW_READY',0,0)",job,video,"history-job-"+job,"b".repeat(64),"c".repeat(64));return new Fixture(job,video);
    }
    private String session(Fixture f,String id,String time){
        jdbc.update("INSERT INTO agent_session(id,job_id,video_id,idempotency_key,request_hash,source_hash,transcript_hash,transcript_key,analysis_hash,analysis_key,source_json,duration_ms,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,'{}',300000,?)",id,f.job,f.video,"history-session-"+id,"b".repeat(64),"a".repeat(64),"d".repeat(64),"synthetic/transcript","e".repeat(64),"synthetic/analysis",LocalDateTime.parse(time));return id;
    }
    private String turn(Fixture f,String session,int sequence,String status,String question){
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO agent_turn(id,session_id,job_id,sequence_number,question,idempotency_key,request_hash,text_model_id,text_fallback_models_json,max_text_calls,history_json,status) VALUES (?,?,?,?,?,?,?,'synthetic','[]',4,'[]',?)",id,session,f.job,sequence,question,"history-turn-"+id,"f".repeat(64),status);
        jdbc.update("INSERT INTO job_stage(id,job_id,owner_type,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,'AGENT_TURN',?,'SEGMENT',?,'READY',?)",UUID.randomUUID().toString(),f.job,id,"history-"+id,"f".repeat(64));return id;
    }
    private void caption(String id){jdbc.update("UPDATE agent_session SET kind='CAPTION',caption_export_id=?,caption_clip_id=? WHERE id=?",UUID.randomUUID().toString(),UUID.randomUUID().toString(),id);}
    private JsonNode read(String job,String query,int expected)throws Exception{
        var response=http.send(HttpRequest.newBuilder(base.resolve("/api/jobs/"+job+"/agent-session-history"+query)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(expected,response.statusCode(),()->new String(response.body(),StandardCharsets.UTF_8));return JSON.readTree(response.body());
    }
    private void assertError(String job,String query,int status,String code)throws Exception{assertEquals(code,read(job,query,status).path("error_code").asText());}
    private String state(String job){StringBuilder text=new StringBuilder();for(String table:List.of("analysis_job","agent_session","agent_turn","job_stage","model_call")){String field=table.equals("analysis_job")?"id":"job_id",order=table.equals("model_call")?"request_id":"id";text.append(JSON.writeValueAsString(jdbc.queryForList("SELECT * FROM "+table+" WHERE "+field+"=? ORDER BY "+order,job)));}return ClipRequestService.sha(text.toString());}
    private static String failure(Runnable action){return assertThrows(JobException.class,action::run).getCode();}
    private static void assertProjectionSql(List<String> sql){for(String value:sql){String upper=value.toUpperCase(Locale.ROOT);assertTrue(upper.startsWith("SELECT "));for(String forbidden:List.of("SELECT *","FOR UPDATE","SOURCE_JSON","HISTORY_JSON","RESULT_JSON","CAPTION_CONTEXT_JSON","JOB_STAGE","MODEL_CALL"))assertFalse(upper.contains(forbidden),value);}}
    /** SQL interception uses a dedicated transaction-bound proxy; it does not enable DB-wide logging. */
    private static final class SqlProbe {
        final List<String> sql=new CopyOnWriteArrayList<>();final DataSource dataSource;volatile Consumer<String> hook=value->{};volatile boolean readOnlySeen,repeatableReadSeen;
        SqlProbe(DataSource source){dataSource=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            Object result=invoke(source,method,args);if(result instanceof Connection connection&&method.getName().equals("getConnection"))return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("prepareStatement")&&a!=null&&a[0] instanceof String query){sql.add(query);hook.accept(query);}
                if(m.getName().equals("setReadOnly")&&Boolean.TRUE.equals(a[0]))readOnlySeen=true;
                if(m.getName().equals("setTransactionIsolation")&&Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(a[0]))repeatableReadSeen=true;
                Object value=invoke(connection,m,a);
                if(m.getName().equals("getTransactionIsolation")&&Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(value))repeatableReadSeen=true;
                return value;
            });return result;});}
        AgentSessionHistoryService service(){return new AgentSessionHistoryService(new JdbcTemplate(dataSource),new JdbcTransactionManager(dataSource));}
        private static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}}
    }
}
