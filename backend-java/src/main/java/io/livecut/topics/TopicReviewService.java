package io.livecut.topics;

import static io.livecut.topics.TopicModels.*;
import java.util.*;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import io.livecut.review.ReviewModels.Utterance;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Topic revisions share immutable revision storage, never product identities. */
@Service
public class TopicReviewService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> EDIT_FIELDS = Set.of("id","start_ms","end_ms","title","summary","keywords","enabled");
    private static final Set<String> SOURCE_FIELDS = Set.of("schema_version", "analysis_mode", "data_kind", "job_id", "video_id",
            "analysis_generation", "duration_ms", "transcript_version", "pipeline_version", "review_status", "provenance", "utterances", "frames", "segments");
    private static final Set<String> UTTERANCE_FIELDS = Set.of("utterance_id", "start_ms", "end_ms", "text", "source_chunk_id");
    private static final Set<String> TOPIC_FIELDS = Set.of("segment_id", "title", "summary", "keywords", "start_ms", "end_ms",
            "start_utterance_id", "end_utterance_id", "audio_evidence_utterance_ids");
    private final JdbcTemplate jdbc;
    private final ArtifactVerifier artifacts;
    private final TransactionTemplate tx;
    private record Source(long duration, List<Segment> segments, List<Utterance> utterances) {}
    private record Document(String job, String video, int generation, String videoHash,
                            String sourceHash, Source source, long latest) {}

    public TopicReviewService(JdbcTemplate jdbc, ArtifactVerifier artifacts, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.artifacts=artifacts;tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Snapshot get(String id, Long revision) {
        String job=uuid(id);
        if(revision!=null&&revision<0)throw bad("Invalid revision");
        return tx.execute(status->{var document=document(job);return snapshot(document,revision==null?document.latest():revision);});
    }

    public Snapshot save(String id, JsonNode request) {
        String job=uuid(id);fields(request,Set.of("base_revision","segments"));
        long base=number(request,"base_revision");
        return tx.execute(status->{
            var document=document(job);current(document,base);
            List<Segment> edited=edited(array(request,"segments",10000),document.source());
            long revision;
            try{revision=Math.addExact(base,1);}catch(ArithmeticException error){throw bad("Revision exhausted");}
            jdbc.update("INSERT INTO review_revision (job_id,revision,snapshot_json) VALUES (?,?,?)",job,revision,JSON.writeValueAsString(new Content(edited)));
            jdbc.update("UPDATE review_document SET latest_revision=? WHERE job_id=?",revision,job);
            return snapshot(new Document(job,document.video(),document.generation(),document.videoHash(),document.sourceHash(),document.source(),revision),revision);
        });
    }

    public Snapshot confirm(String id, JsonNode request) {
        String job=uuid(id);fields(request,Set.of("revision"));long revision=number(request,"revision");
        return tx.execute(status->{
            var document=document(job);current(document,revision);var content=content(job,revision);
            if(content.segments().stream().noneMatch(Segment::enabled))throw conflict("REVIEW_EMPTY_SELECTION","Select at least one topic before confirming");
            noOverlap(content.segments());
            jdbc.update("INSERT INTO review_confirmation (job_id,revision) VALUES (?,?) ON DUPLICATE KEY UPDATE revision=revision",job,revision);
            return snapshot(document,revision);
        });
    }

    public Confirmed download(String id,long revision) {
        String job=uuid(id);if(revision<0)throw bad("Invalid revision");
        return tx.execute(status->{
            var document=document(job);var content=content(job,revision);
            var confirmations=jdbc.queryForList("SELECT DATE_FORMAT(confirmed_at,'%Y-%m-%dT%H:%i:%s.%fZ') FROM review_confirmation WHERE job_id=? AND revision=?",String.class,job,revision);
            if(confirmations.isEmpty())throw conflict("REVIEW_DOWNLOAD_REQUIRES_CONFIRMATION","Confirm this revision first");
            return new Confirmed("1.0","topic_review_revision","topics",job,document.video(),document.generation(),revision,"CONFIRMED",confirmations.getFirst(),document.source().duration(),document.videoHash(),document.sourceHash(),content.segments());
        });
    }

    private Document document(String job) {
        var rows=jdbc.queryForList("SELECT id,video_id,generation,status,config_json FROM analysis_job WHERE id=? FOR UPDATE",job);
        if(rows.isEmpty())throw missing("JOB_NOT_FOUND");var row=rows.getFirst();
        if(!"topics".equals(JSON.readTree(String.valueOf(row.get("config_json"))).path("analysis_mode").asText()))throw conflict("REVIEW_MODE_UNSUPPORTED","This task uses the legacy product review");
        if(!"REVIEW_READY".equals(row.get("status")))throw conflict("REVIEW_NOT_READY","Analysis must finish before topic review");
        String video=String.valueOf(row.get("video_id"));int generation=((Number)row.get("generation")).intValue();
        String videoHash=jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?",String.class,video);
        var stored=jdbc.queryForList("SELECT source_hash,source_json,latest_revision FROM review_document WHERE job_id=? FOR UPDATE",job);
        if(!stored.isEmpty()){
            var value=stored.getFirst();var source=source(JSON.readTree(String.valueOf(value.get("source_json"))),job,video,generation);
            return new Document(job,video,generation,videoHash,String.valueOf(value.get("source_hash")),source,((Number)value.get("latest_revision")).longValue());
        }
        var stages=jdbc.queryForList("SELECT id,attempt,run_token,manifest_key,manifest_hash FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type='TIMELINE' AND status='SUCCEEDED' FOR UPDATE",job);
        if(stages.size()!=1)throw conflict("REVIEW_SOURCE_UNAVAILABLE","A completed topic timeline is required");
        var stage=stages.getFirst();
        var identity=new ArtifactVerifier.ManifestIdentity(job,video,generation,String.valueOf(stage.get("id")),"TIMELINE",((Number)stage.get("attempt")).intValue(),String.valueOf(stage.get("run_token")),videoHash);
        String key=String.valueOf(stage.get("manifest_key")),hash=String.valueOf(stage.get("manifest_hash"));
        var manifest=artifacts.verifyManifest(key,hash,identity);var raw=artifacts.readAnalysisResult(key,hash,identity);
        var source=source(raw,job,video,generation);
        String sourceHash=manifest.artifacts().stream().filter(file->"analysis_result".equals(file.kind())).findFirst().orElseThrow().sha256();
        jdbc.update("INSERT INTO review_document (job_id,source_hash,source_json,duration_ms,latest_revision) VALUES (?,?,?,?,0)",job,sourceHash,JSON.writeValueAsString(raw),source.duration());
        jdbc.update("INSERT INTO review_revision (job_id,revision,snapshot_json) VALUES (?,0,?)",job,JSON.writeValueAsString(new Content(source.segments())));
        return new Document(job,video,generation,videoHash,sourceHash,source,0);
    }

    private Snapshot snapshot(Document document,long revision) {
        var confirmed=jdbc.queryForList("SELECT revision FROM review_confirmation WHERE job_id=? ORDER BY revision",Long.class,document.job());
        return new Snapshot(document.job(),document.video(),document.generation(),"topics","model_result",document.source().duration(),revision,confirmed.contains(revision)?"CONFIRMED":"DRAFT",document.sourceHash(),List.copyOf(confirmed),content(document.job(),revision).segments(),document.source().utterances());
    }
    private Content content(String job,long revision) {
        var rows=jdbc.queryForList("SELECT snapshot_json FROM review_revision WHERE job_id=? AND revision=?",String.class,job,revision);
        if(rows.isEmpty())throw missing("REVIEW_REVISION_NOT_FOUND");return JSON.readValue(rows.getFirst(),Content.class);
    }
    private static void current(Document document,long revision) {
        if(document.latest()!=revision)throw conflict("REVIEW_REVISION_CONFLICT","Review changed; reload before saving");
    }

    private static List<Segment> edited(JsonNode values,Source source) {
        Map<String,Segment> originals=new HashMap<>();source.segments().forEach(s->originals.put(s.id(),s));
        List<Segment> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(JsonNode row:values){
            fields(row,EDIT_FIELDS);String id=identifier(row,"id");if(!ids.add(id))throw bad("Duplicate topic ID");
            long start=number(row,"start_ms"),end=number(row,"end_ms");interval(start,end,source.duration());
            String title=text(row,"title",120),summary=text(row,"summary",1000);List<String> keywords=keywords(row);
            if(!row.path("enabled").isBoolean())throw bad("enabled must be Boolean");
            Segment old=originals.get(id);
            boolean sameContent=old!=null&&old.title().equals(title)&&old.summary().equals(summary)&&old.keywords().equals(keywords);
            result.add(new Segment(id,start,end,title,summary,keywords,row.path("enabled").booleanValue(),old==null?"manual":"model",old!=null&&old.startMs()==start&&old.endMs()==end?"model":"manual",sameContent?"model":"manual",old==null?null:id));
        }
        noOverlap(result);return List.copyOf(result);
    }

    private static Source source(JsonNode raw,String job,String video,int generation) {
        try {
            return parseSource(raw, job, video, generation);
        } catch (JobException invalid) {
            if (invalid.getStatus().is4xxClientError()) {
                throw conflict("REVIEW_SOURCE_INVALID", "The published topic result is invalid");
            }
            throw invalid;
        }
    }

    private static Source parseSource(JsonNode raw,String job,String video,int generation) {
        fields(raw, SOURCE_FIELDS);
        if(!raw.path("schema_version").isString()||!"1.0".equals(raw.path("schema_version").asText())||!"topics".equals(raw.path("analysis_mode").asText())
                ||!"model_result".equals(raw.path("data_kind").asText())||!"DRAFT".equals(raw.path("review_status").asText())
                ||!job.equals(raw.path("job_id").asText())||!video.equals(raw.path("video_id").asText())
                ||number(raw,"analysis_generation")!=generation)throw conflict("REVIEW_SOURCE_INVALID","Topic result identity is invalid");
        if (!"topics-text-v1".equals(raw.path("pipeline_version").asText())) throw bad("Invalid topic pipeline");
        text(raw, "transcript_version", 128);
        JsonNode provenance = raw.path("provenance");
        fields(provenance, Set.of("asr_model_id", "text_model_id", "prompt_version", "config_hash"));
        for (String field : List.of("asr_model_id", "text_model_id", "prompt_version")) text(provenance, field, 128);
        if (!provenance.path("config_hash").isString() || !provenance.path("config_hash").stringValue().matches("[a-f0-9]{64}")) throw bad("Invalid topic configuration hash");
        long duration=number(raw,"duration_ms");if(duration<=0)throw bad("Invalid topic duration");
        Set<String> frameIds = new HashSet<>();
        for (JsonNode frame : array(raw, "frames", 100000)) {
            fields(frame, Set.of("frame_id", "timestamp_ms", "object_key"));
            if (!frameIds.add(identifier(frame, "frame_id")) || number(frame, "timestamp_ms") >= duration) throw bad("Invalid topic frame");
            text(frame, "object_key", 1024);
        }
        List<Utterance> utterances=new ArrayList<>();Map<String,Utterance> byId=new HashMap<>();long previous=-1;
        for(JsonNode row:array(raw,"utterances",100000)){
            fields(row, UTTERANCE_FIELDS);
            String id=identifier(row,"utterance_id");long start=number(row,"start_ms"),end=number(row,"end_ms");interval(start,end,duration);
            if(start<previous||byId.containsKey(id))throw bad("Invalid source utterance order");previous=end;
            var utterance=new Utterance(id,start,end,text(row,"text",Integer.MAX_VALUE),identifier(row,"source_chunk_id"));byId.put(id,utterance);utterances.add(utterance);
        }
        List<String> coverage=new ArrayList<>();Set<String> ids=new HashSet<>();List<Segment> segments=new ArrayList<>();
        for(JsonNode row:array(raw,"segments",10000)){
            fields(row, TOPIC_FIELDS);
            String id=identifier(row,"segment_id");if(!ids.add(id))throw bad("Duplicate topic ID");
            List<String> evidence=new ArrayList<>();for(JsonNode value:array(row,"audio_evidence_utterance_ids",100000)){
                if(!value.isString()||!byId.containsKey(value.stringValue()))throw bad("Unknown topic evidence");evidence.add(value.stringValue());
            }
            if(evidence.isEmpty()||!evidence.getFirst().equals(identifier(row,"start_utterance_id"))||!evidence.getLast().equals(identifier(row,"end_utterance_id")))throw bad("Invalid topic evidence boundaries");
            long start=number(row,"start_ms"),end=number(row,"end_ms");interval(start,end,duration);
            if(start!=byId.get(evidence.getFirst()).startMs()||end!=byId.get(evidence.getLast()).endMs())throw bad("Topic times must match source ASR");
            coverage.addAll(evidence);segments.add(new Segment(id,start,end,text(row,"title",120),text(row,"summary",1000),keywords(row),false,"model","model","model",id));
        }
        if(!coverage.equals(utterances.stream().map(Utterance::utteranceId).toList()))throw bad("Topics must cover every source utterance once");
        return new Source(duration,List.copyOf(segments),List.copyOf(utterances));
    }
    private static void noOverlap(List<Segment> segments){
        long end=-1;for(var segment:segments.stream().filter(Segment::enabled).sorted(Comparator.comparingLong(Segment::startMs)).toList()){
            if(segment.startMs()<end)throw bad("Selected topic intervals must not overlap");end=segment.endMs();
        }
    }
    private static void interval(long start,long end,long duration){if(!(0<=start&&start<end&&end<=duration))throw bad("Topic interval outside source");}
    private static List<String> keywords(JsonNode row){
        List<String> result=new ArrayList<>();for(JsonNode value:array(row,"keywords",8)){
            if(!value.isString()||value.stringValue().isBlank()||length(value.stringValue())>40)throw bad("Invalid topic keyword");
            String word=value.stringValue().strip();if(result.contains(word))throw bad("Duplicate topic keyword");result.add(word);
        }return List.copyOf(result);
    }
    private static JsonNode array(JsonNode node,String field,int maximum){var value=node.path(field);if(!value.isArray()||value.size()>maximum)throw bad("Invalid "+field+" array");return value;}
    private static String text(JsonNode node,String field,int maximum){var value=node.path(field);if(!value.isString()||value.stringValue().isBlank()||length(value.stringValue())>maximum)throw bad("Invalid "+field);return value.stringValue().strip();}
    private static int length(String value) { return value.codePointCount(0, value.length()); }
    private static String identifier(JsonNode node,String field){String value=text(node,field,128);if(!value.equals(node.path(field).stringValue())||!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw bad("Invalid source identifier");return value;}
    private static long number(JsonNode node,String field){var value=node.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw bad("Invalid "+field);return value.longValue();}
    private static void fields(JsonNode node,Set<String> fields){if(!node.isObject()||!Set.copyOf(node.propertyNames()).equals(fields))throw bad("Unexpected topic review fields");}
    private static String uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return id;}catch(RuntimeException error){throw bad("Invalid job ID");}}
    private static JobException bad(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_TOPIC_REVIEW",message);}
    private static JobException missing(String code){return new JobException(HttpStatus.NOT_FOUND,code,"Topic review was not found");}
    private static JobException conflict(String code,String message){return new JobException(HttpStatus.CONFLICT,code,message);}
}
