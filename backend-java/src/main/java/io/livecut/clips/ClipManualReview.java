package io.livecut.clips;

import static io.livecut.clips.ClipPlanValidator.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.*;
import io.livecut.agent.CaptionProofreadValidator;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Immutable human attestations. These never alter an AI classification or manufacture word timing. */
@Component
public final class ClipManualReview {
    private static final DateTimeFormatter CREATED=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final JdbcTemplate jdbc;private final ArtifactVerifier artifacts;
    public ClipManualReview(JdbcTemplate jdbc,ArtifactVerifier artifacts){this.jdbc=jdbc;this.artifacts=artifacts;}
    public static JsonNode input(JsonNode input,String key){
        try{
            if(!UUID.fromString(key).toString().equals(key))throw new IllegalArgumentException();
            fields(input,Set.of("base_revision","plan_hash","outputs"));number(input,"base_revision");hash(text(input,"plan_hash",64));
            Set<String> seen=new HashSet<>();for(JsonNode item:array(input,"outputs",0,6)){
                fields(item,Set.of("output_id","note","accept_sentence_boundaries"));if(!seen.add(id(item,"output_id"))||!item.path("accept_sentence_boundaries").isBoolean()||!item.path("accept_sentence_boundaries").booleanValue()
                    ||!item.path("note").isString()||!CaptionProofreadValidator.validText(item.path("note").stringValue(),1000,false))throw new IllegalArgumentException();
            }
            return input.deepCopy();
        }catch(RuntimeException bad){throw new JobException(HttpStatus.BAD_REQUEST,"INVALID_CLIP_MANUAL_REVIEW","Provide a canonical UUID key and a bounded explicit human review batch");}
    }
    public static String digest(JsonNode value){return ClipRequestService.sha(ClipRequestService.canonicalJson(value));}
    public static String fingerprint(String request,JsonNode input){return digest(JSON.valueToTree(Map.of("request_id",request,"input",input)));}
    public static String sourceHash(Map<String,Object> owner){return digest(JSON.readTree(String.valueOf(owner.get("source_json"))));}
    public static String now(){return CREATED.format(Instant.now().truncatedTo(ChronoUnit.MICROS));}

    /** Verify the actual hashed ASR, not only hash labels on the request row. */
    public void verifySource(Map<String,Object> owner){
        try{
            var job=jdbc.queryForMap("SELECT id,video_id,generation FROM analysis_job WHERE id=?",owner.get("job_id"));
            if(!Objects.equals(job.get("video_id"),owner.get("video_id"))||!Objects.equals(owner.get("source_hash"),jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?",String.class,owner.get("video_id"))))throw invalid();
            var rows=jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type='ASR' AND status='SUCCEEDED'",owner.get("job_id"));if(rows.size()!=1)throw invalid();var stage=rows.getFirst();
            var identity=new ArtifactVerifier.ManifestIdentity(String.valueOf(owner.get("job_id")),String.valueOf(owner.get("video_id")),((Number)job.get("generation")).intValue(),String.valueOf(stage.get("id")),"ASR",((Number)stage.get("attempt")).intValue(),String.valueOf(stage.get("run_token")),String.valueOf(owner.get("source_hash")));
            var manifest=artifacts.verifyManifest(String.valueOf(stage.get("manifest_key")),String.valueOf(stage.get("manifest_hash")),identity);
            var transcript=manifest.artifacts().stream().filter(a->a.kind().equals("transcript")).findFirst().orElseThrow();
            if(!transcript.sha256().equals(owner.get("transcript_hash"))||!transcript.key().equals(owner.get("transcript_key"))||!artifacts.readJsonArtifact(transcript,identity).equals(JSON.readTree(String.valueOf(owner.get("source_json"))).path("utterances")))throw invalid();
        }catch(JobException failure){throw invalid();}catch(IllegalArgumentException|NoSuchElementException failure){throw invalid();}
    }

    public JsonNode build(Map<String,Object> owner,JsonNode plan,JsonNode automatic,JsonNode input,String id,long revision,String created){
        List<JsonNode> decisions=new ArrayList<>();Map<String,Integer> order=new HashMap<>();Map<String,JsonNode> outputs=new HashMap<>();
        for(JsonNode output:plan.path("outputs")){String uid=output.path("output_id").asText();order.put(uid,order.size());outputs.put(uid,output);}
        int previous=-1;
        for(JsonNode selected:input.path("outputs")){
            String uid=selected.path("output_id").asText();JsonNode output=outputs.get(uid);
            if(output==null||order.get(uid)<=previous||!output.path("status").asText().equals("NEEDS_REVIEW")||output.path("enabled").asBoolean()||output.path("parts").isEmpty()||output.path("evidence_utterance_ids").isEmpty())throw notAllowed();previous=order.get(uid);
            decisions.add(JSON.valueToTree(Map.of("output_id",uid,"goal_id",output.path("goal_id").asText(),"output_hash",digest(output),"note",selected.path("note").asText(),"accept_sentence_boundaries",true)));
        }
        Map<String,Object> value=new TreeMap<>();value.put("schema_version","1.0");value.put("id",id);value.put("request_id",owner.get("id"));
        for(String field:List.of("job_id","video_id","source_hash","transcript_hash","duration_ms"))value.put(field,owner.get(field));
        value.put("automatic_plan_hash",digest(automatic));value.put("base_revision",number(input,"base_revision"));value.put("base_plan_hash",input.path("plan_hash").asText());value.put("revision",revision);value.put("plan_hash",digest(plan));value.put("outputs",decisions);value.put("created_at",created);
        ObjectNode review=(ObjectNode)JSON.valueToTree(value);review.put("review_hash",digest(review));return JSON.readTree(ClipRequestService.canonicalJson(review));
    }

    public JsonNode stored(Map<String,Object> owner,Map<String,Object> revision,JsonNode plan,JsonNode automatic){
        var rows=jdbc.queryForList("SELECT * FROM clip_plan_manual_review WHERE request_id=? AND revision=?",owner.get("id"),revision.get("revision"));
        if(revision.get("manual_review_id")==null&&revision.get("manual_review_hash")==null){if(!rows.isEmpty())throw invalid();return null;}
        if(rows.size()!=1)throw invalid();var row=rows.getFirst();
        try{
            validateReview(plan,owner,automatic);
            if(!Objects.equals(revision.get("manual_review_id"),row.get("id"))||!Objects.equals(revision.get("manual_review_hash"),row.get("review_hash"))||!Objects.equals(revision.get("plan_hash"),row.get("plan_hash"))||!sourceHash(owner).equals(row.get("source_snapshot_hash")))throw invalid();
            JsonNode review=JSON.readTree(String.valueOf(row.get("review_json")));String key=review.path("id").asText();
            ObjectNode request=JSON.createObjectNode().put("base_revision",number(review,"base_revision")).put("plan_hash",text(review,"base_plan_hash",64));var decisions=request.putArray("outputs");
            for(JsonNode selected:array(review,"outputs",0,6))decisions.addObject().put("output_id",text(selected,"output_id",128)).put("note",text(selected,"note",1000)).put("accept_sentence_boundaries",selected.path("accept_sentence_boundaries").asBoolean());
            input(request,key);long base=number(review,"base_revision"),next=number(review,"revision");
            var bases=jdbc.queryForList("SELECT plan_hash,plan_json FROM clip_plan_revision WHERE request_id=? AND revision=?",owner.get("id"),base);
            if(bases.size()!=1||next!=Math.addExact(base,1)||next!=((Number)row.get("revision")).longValue()||base!=((Number)row.get("base_revision")).longValue()||!key.equals(row.get("id"))||!review.path("request_id").asText().equals(owner.get("id"))||!digest(plan).equals(row.get("plan_hash"))||!review.path("base_plan_hash").asText().equals(row.get("base_plan_hash")))throw invalid();
            var original=bases.getFirst();if(!digest(JSON.readTree(String.valueOf(original.get("plan_json")))).equals(original.get("plan_hash"))||!Objects.equals(original.get("plan_hash"),row.get("plan_hash"))||!fingerprint(String.valueOf(owner.get("id")),request).equals(row.get("request_hash")))throw invalid();
            String created=text(review,"created_at",40);Instant time=Instant.parse(created);if(!CREATED.format(time).equals(created)||!time.equals(instant(row.get("created_at"))))throw invalid();
            JsonNode expected=build(owner,plan,automatic,request,key,next,created);
            if(!ClipRequestService.canonicalJson(expected).equals(ClipRequestService.canonicalJson(review))||!review.path("review_hash").asText().equals(row.get("review_hash")))throw invalid();
            verifySource(owner);return review;
        }catch(RuntimeException failure){throw invalid();}
    }
    private static Instant instant(Object value){return value instanceof Timestamp t?t.toInstant():((java.time.LocalDateTime)value).toInstant(ZoneOffset.UTC);}
    public static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"CLIP_MANUAL_REVIEW_INVALID","Immutable manual review provenance failed verification");}
    private static JobException notAllowed(){return new JobException(HttpStatus.CONFLICT,"CLIP_MANUAL_REVIEW_NOT_ALLOWED","Select source-ordered NEEDS_REVIEW outputs with nonempty parts and evidence");}
}
