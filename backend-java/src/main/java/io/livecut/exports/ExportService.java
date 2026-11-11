package io.livecut.exports;

import static io.livecut.exports.ExportModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import io.livecut.jobs.JobModels.StageRow;
import io.livecut.jobs.StageDiagnostics;
import io.livecut.captions.CaptionRenderSpec;
import io.livecut.review.ReviewModels;
import io.livecut.review.ReviewService;
import io.livecut.topics.TopicReviewService;
import io.livecut.clips.ClipRequestService;
import io.livecut.clips.ClipPlanValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Frozen confirmed revisions; computation uses the ordinary job_stage lease protocol. */
@Service
public class ExportService {
    public static final String PROFILE = "h264-aac-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final ReviewService reviews;
    private final TopicReviewService topics;
    private final ArtifactVerifier verifier;
    private final ClipRequestService clipRequests;
    private final TransactionTemplate transaction;
    private final int maxAttempts;

    public ExportService(JdbcTemplate jdbc, ReviewService reviews, TopicReviewService topics, ArtifactVerifier verifier, ClipRequestService clipRequests,
                         PlatformTransactionManager manager,
                         @Value("${livecut.jobs.max-attempts:3}") int maxAttempts) {
        this.jdbc = jdbc; this.reviews = reviews; this.topics = topics; this.verifier = verifier; this.clipRequests=clipRequests; this.maxAttempts = maxAttempts;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Snapshot create(CreateRequest request) {
        if (request == null || request.approvedRevision() == null || request.approvedRevision() < 0
                || request.segmentIds() == null || request.segmentIds().isEmpty() || request.segmentIds().size() > 100
                || request.segmentIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
                || new HashSet<>(request.segmentIds()).size() != request.segmentIds().size()) { throw bad("INVALID_EXPORT", "Select 1..100 unique segment ids and a nonnegative confirmed revision"); }
        if (!PROFILE.equals(request.profile())) { throw bad("INVALID_EXPORT_PROFILE", "The supported encoding profile is h264-aac-v1"); }
        String jobId = uuid(request.jobId(), "INVALID_JOB_ID");
        return transaction.execute(status -> {
            Map<String, Object> job = lockAnalysis(jobId);
            boolean topicMode="topics".equals(JSON.readTree(String.valueOf(job.get("config_json"))).path("analysis_mode").asText());
            List<Map<String, Object>> frozenClips = new ArrayList<>();
            if (topicMode) {
                var review=topics.get(jobId,request.approvedRevision());
                if(!"CONFIRMED".equals(review.status()))throw conflict("EXPORT_REQUIRES_CONFIRMATION","Only confirmed topic selections may be exported");
                var selected=new HashMap<String,io.livecut.topics.TopicModels.Segment>();review.segments().forEach(s->selected.put(s.id(),s));
                for(String segmentId:request.segmentIds().stream().sorted().toList()){
                    var segment=selected.get(segmentId);
                    if(segment==null||!segment.enabled())throw bad("INVALID_EXPORT_SEGMENT","Select enabled topics from the confirmed revision");
                    frozenClips.add(new TreeMap<>(Map.of("segment_id",segment.id(),"title",segment.title(),"start_ms",segment.startMs(),"end_ms",segment.endMs())));
                }
            } else {
            // ReviewService acquires the same analysis lock before review rows.
            // It is deliberately called before any export/stage lock is held.
            ReviewModels.Snapshot review = reviews.get(jobId, request.approvedRevision());
            if (!"CONFIRMED".equals(review.status())) { throw conflict("EXPORT_REQUIRES_CONFIRMATION", "Only an explicitly confirmed revision may be exported"); }
            Map<String, ReviewModels.Product> products = new HashMap<>();
            review.products().forEach(product -> products.put(product.id(), product));
            Map<String, ReviewModels.Segment> segments = new HashMap<>();
            review.segments().forEach(segment -> segments.put(segment.id(), segment));
            for (String segmentId : request.segmentIds().stream().sorted().toList()) {
                ReviewModels.Segment segment = segments.get(segmentId);
                if (segment == null || !segment.enabled() || segment.productId() == null || !products.containsKey(segment.productId())) {
                    throw bad("INVALID_EXPORT_SEGMENT", "Every selected segment must be enabled and assigned in the confirmed revision");
                }
                frozenClips.add(new TreeMap<>(Map.of("segment_id", segment.id(), "product_id", segment.productId(),
                        "product_name", products.get(segment.productId()).name(), "start_ms", segment.startMs(), "end_ms", segment.endMs())));
            }
            }
            String sourceHash = jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, job.get("video_id"));
            Map<String, Object> frozen = new TreeMap<>();
            frozen.put("job_id", jobId); frozen.put("video_id", job.get("video_id")); frozen.put("generation", job.get("generation"));
            frozen.put("source_hash", sourceHash); frozen.put("approved_revision", request.approvedRevision());
            frozen.put("encoding_profile", PROFILE); frozen.put("clips", frozenClips);
            if(topicMode)frozen.put("analysis_mode","topics");
            String frozenJson = JSON.writeValueAsString(frozen);
            String fingerprint = sha256(frozenJson);
            List<String> existing = jdbc.queryForList("SELECT id FROM export_job WHERE fingerprint=?", String.class, fingerprint);
            if (!existing.isEmpty()) { return snapshot(lockOwner(existing.getFirst(), jobId)); }
            String exportId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO export_job (id,job_id,video_id,approved_revision,source_hash,snapshot_hash,fingerprint,encoding_profile,frozen_json) VALUES (?,?,?,?,?,?,?,?,?)",
                    exportId, jobId, job.get("video_id"), request.approvedRevision(), sourceHash, fingerprint, fingerprint, PROFILE, frozenJson);
            for (Map<String, Object> frozenClip : frozenClips) {
                String clipId = UUID.randomUUID().toString();
                String stageId = UUID.randomUUID().toString();
                String inputHash = sha256(fingerprint + "\n" + JSON.writeValueAsString(new TreeMap<>(frozenClip)) + "\n" + clipId);
                jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,'EXPORT',?,'EXPORT_CLIP',?,'READY',?)",
                        stageId, jobId, exportId, clipId, inputHash);
                jdbc.update("INSERT INTO export_clip (id,export_id,stage_id,segment_id,product_id,product_name,topic_title,start_ms,end_ms) VALUES (?,?,?,?,?,?,?,?,?)",
                        clipId, exportId, stageId, frozenClip.get("segment_id"), frozenClip.get("product_id"), frozenClip.get("product_name"), frozenClip.get("title"), frozenClip.get("start_ms"), frozenClip.get("end_ms"));
            }
            return snapshot(lockOwner(exportId, jobId));
        });
    }

    public Page list(String analysisId, int limit, int offset) {
        String jobId = uuid(analysisId, "INVALID_JOB_ID");
        if (limit < 1 || limit > 100 || offset < 0) { throw bad("INVALID_PAGINATION", "limit must be 1..100 and offset nonnegative"); }
        return transaction.execute(status -> {
            lockAnalysis(jobId);
            List<String> ids = jdbc.queryForList("SELECT id FROM export_job WHERE job_id=? AND clip_request_id IS NULL AND render_mode='CLEAN' ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", String.class, jobId, limit, offset);
            return new Page(ids.stream().map(id -> snapshot(lockOwner(id, jobId))).toList(), limit, offset);
        });
    }

    public Snapshot createClipRequest(String requestId,JsonNode input){
        ClipPlanValidator.fields(input,input.has("manual_review_hash")?Set.of("revision","plan_hash","profile","manual_review_hash"):Set.of("revision","plan_hash","profile"));
        long revision=ClipPlanValidator.number(input,"revision");String planHash=ClipPlanValidator.hash(ClipPlanValidator.text(input,"plan_hash",64));
        String profile=ClipPlanValidator.text(input,"profile",40);
        if(!Set.of(PROFILE,"mp4-h264-aac-v1").contains(profile))throw bad("INVALID_EXPORT_PROFILE","Unsupported encoding profile");
        String id=ClipRequestService.uuid(requestId);
        return transaction.execute(status->{
            var request=clipRequests.lockPublicOwner(id);String jobId=String.valueOf(request.get("job_id"));
            var analysis=jdbc.queryForMap("SELECT * FROM analysis_job WHERE id=?",jobId);
            if(!"REVIEW_READY".equals(analysis.get("status")))throw conflict("EXPORT_ANALYSIS_NOT_READY","Source analysis is not ready");
            var selection=clipRequests.deliverySelection(request,revision,planHash,ClipRequestService.manualHash(input),true);
            List<Map<String,Object>> frozenClips=deliveryClips(selection);
            if(frozenClips.isEmpty())throw conflict("CLIP_PLAN_EMPTY_SELECTION","Select at least one output");
            Map<String,Object> frozen=deliveryFrozen(request,analysis,revision,planHash,selection,frozenClips);
            String frozenJson=ClipRequestService.canonicalJson(JSON.valueToTree(frozen)),fingerprint=sha256(frozenJson);
            List<String> existing=jdbc.queryForList("SELECT id FROM export_job WHERE fingerprint=?",String.class,fingerprint);
            if(!existing.isEmpty())return snapshot(lockOwner(existing.getFirst(),jobId));
            String exportId=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO export_job (id,job_id,video_id,approved_revision,source_hash,snapshot_hash,fingerprint,encoding_profile,frozen_json,clip_request_id) VALUES (?,?,?,?,?,?,?,?,?,?)",exportId,jobId,request.get("video_id"),revision,request.get("source_hash"),fingerprint,fingerprint,PROFILE,frozenJson,id);
            for(Map<String,Object> clip:frozenClips){String clipId=UUID.randomUUID().toString(),stageId=UUID.randomUUID().toString();String inputHash=sha256(fingerprint+"\n"+ClipRequestService.canonicalJson(JSON.valueToTree(clip))+"\n"+clipId);
                jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,'EXPORT',?,'EXPORT_CLIP',?,'READY',?)",stageId,jobId,exportId,clipId,inputHash);
                jdbc.update("INSERT INTO export_clip (id,export_id,stage_id,segment_id,product_id,product_name,topic_title,start_ms,end_ms,source_parts_json) VALUES (?,?,?,?,NULL,NULL,?,?,?,?)",clipId,exportId,stageId,clip.get("segment_id"),clip.get("title"),clip.get("start_ms"),clip.get("end_ms"),ClipRequestService.canonicalJson(JSON.valueToTree(clip.get("parts"))));
            }
            return snapshot(lockOwner(exportId,jobId));
        });
    }

    public Page listClipRequest(String requestId){String id=ClipRequestService.uuid(requestId);return transaction.execute(status->{var request=clipRequests.lockPublicOwner(id);String job=String.valueOf(request.get("job_id"));return new Page(jdbc.queryForList("SELECT id FROM export_job WHERE clip_request_id=? AND render_mode='CLEAN' ORDER BY created_at DESC,id DESC LIMIT 100",String.class,id).stream().map(value->snapshot(lockOwner(value,job))).toList(),100,0);});}

    /** Caption orchestration already owns analysis/source locks; no caption dependency enters this service. */
    public record CreatedRender(String exportId,String clipId) {}
    public CreatedRender createCaptionRender(String jobId,String videoId,long approvedRevision,String sourceHash,String title,JsonNode render,String keyHash){
        CaptionRenderSpec.validate(render);Map<String,Object> job=lockAnalysis(jobId);
        if(!"REVIEW_READY".equals(job.get("status"))||!videoId.equals(job.get("video_id")))throw conflict("CAPTION_RENDER_SOURCE_UNAVAILABLE","Source analysis is not ready");
        String exportId=UUID.randomUUID().toString(),clipId=UUID.randomUUID().toString(),stageId=UUID.randomUUID().toString();
        long duration=render.path("input_media").path("duration_ms").longValue();
        Map<String,Object> clip=new TreeMap<>(Map.of("segment_id",render.path("source_clip_id").asText(),"title",title,"start_ms",0,"end_ms",duration));
        Map<String,Object> frozen=new TreeMap<>();frozen.put("job_id",jobId);frozen.put("video_id",videoId);frozen.put("generation",job.get("generation"));frozen.put("source_hash",sourceHash);
        frozen.put("approved_revision",approvedRevision);frozen.put("encoding_profile",CaptionRenderSpec.PROFILE);frozen.put("clips",List.of(clip));frozen.put("render_mode","CAPTIONED");
        frozen.put("caption_render",render);String renderHash=sha256(ClipRequestService.canonicalJson(render));frozen.put("caption_render_hash",renderHash);
        String frozenJson=ClipRequestService.canonicalJson(JSON.valueToTree(frozen)),snapshotHash=sha256(frozenJson),fingerprint=sha256(snapshotHash+"\n"+keyHash);
        jdbc.update("INSERT INTO export_job (id,job_id,video_id,approved_revision,source_hash,snapshot_hash,fingerprint,encoding_profile,frozen_json,render_mode) VALUES (?,?,?,?,?,?,?,?,?,'CAPTIONED')",exportId,jobId,videoId,approvedRevision,sourceHash,snapshotHash,fingerprint,CaptionRenderSpec.PROFILE,frozenJson);
        String inputHash=sha256(snapshotHash+"\n"+ClipRequestService.canonicalJson(JSON.valueToTree(clip))+"\n"+clipId);
        jdbc.update("INSERT INTO job_stage (id,job_id,owner_type,owner_id,stage_type,shard_key,status,input_hash) VALUES (?,?,'EXPORT',?,'EXPORT_CLIP',?,'READY',?)",stageId,jobId,exportId,clipId,inputHash);
        jdbc.update("INSERT INTO export_clip (id,export_id,stage_id,segment_id,product_id,product_name,topic_title,start_ms,end_ms) VALUES (?,?,?,?,NULL,NULL,?,0,?)",clipId,exportId,stageId,render.path("source_clip_id").asText(),title,duration);
        return new CreatedRender(exportId,clipId);
    }

    public Snapshot get(String value) {
        String id = uuid(value, "INVALID_EXPORT_ID");
        return transaction.execute(status -> snapshot(lockPublicOwner(id)));
    }

    public Manifest manifest(String value) {
        String id = uuid(value, "INVALID_EXPORT_ID");
        return transaction.execute(status -> {
            Map<String, Object> owner = lockPublicOwner(id);
            Snapshot batch = snapshot(owner);
            int generation;
            try {
                JsonNode frozen = JSON.readTree(String.valueOf(owner.get("frozen_json")));
                JsonNode number = frozen.path("generation");
                if (!frozen.isObject() || !number.isIntegralNumber() || !number.canConvertToInt()
                        || number.intValue() < 1) { throw new IllegalArgumentException(); }
                generation = number.intValue();
            } catch (RuntimeException invalid) {
                throw conflict("EXPORT_SNAPSHOT_INVALID", "The frozen export generation is invalid");
            }
            List<ManifestClip> clips = new ArrayList<>();
            for (Clip clip : batch.clips()) {
                ManifestFile file = null;
                if ("SUCCEEDED".equals(clip.status())) {
                    // Same locked byte verification as media GET/HEAD; a
                    // registered success is not proof that bytes still exist.
                    Content content = contentLocked(owner, clip.id());
                    file = new ManifestFile(content.filename(), content.sizeBytes(), content.hash(), clip.downloadUrl());
                }
                clips.add(new ManifestClip(clip.id(), clip.segmentId(), clip.productId(), clip.productName(),
                        clip.startMs(), clip.endMs(), clip.status(), clip.attempt(), clip.errorCode(), file, clip.title(),clip.parts()));
            }
            return new Manifest("1.0", "export_manifest", id, batch.jobId(), batch.videoId(), generation,
                    batch.approvedRevision(), String.valueOf(owner.get("source_hash")), batch.snapshotHash(),
                    batch.profile(), batch.status(), batch.stateVersion(), batch.createdAt(), batch.updatedAt(), List.copyOf(clips),batch.clipRequestId());
        });
    }

    public Snapshot retry(String value, String clipValue) {
        String id = uuid(value, "INVALID_EXPORT_ID");
        String clipId = uuid(clipValue, "INVALID_CLIP_ID");
        return transaction.execute(status -> {
            Map<String, Object> owner = lockPublicOwner(id);
            Map<String, Object> clip = clip(id, clipId, true);
            if (!"FAILED".equals(clip.get("status"))) { throw conflict("EXPORT_CLIP_NOT_RETRYABLE", "Only this export's failed clip may be retried"); }
            if("CAPTIONED".equals(owner.get("render_mode"))&&CaptionRenderSpec.requiresNewRender(clip.get("error_code"))){throw conflict("CAPTION_RENDER_NOT_RETRYABLE","Correct and confirm captions, then create a new render");}
            if (((Number) clip.get("attempt")).intValue() >= maxAttempts) { throw conflict("ATTEMPTS_EXHAUSTED", "Clip execution attempts are exhausted"); }
            jdbc.update("UPDATE job_stage SET status='READY',run_token=NULL,worker_id=NULL,worker_session_id=NULL,lease_until=NULL,hard_deadline_at=NULL,next_run_at=CURRENT_TIMESTAMP(6),error_code=NULL,diagnostic_json=NULL,render_progress_json=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    clip.get("stage_id"));
            refreshLocked(id);
            return snapshot(lockOwner(id, String.valueOf(owner.get("job_id"))));
        });
    }

    public Content content(String value, String clipValue) {
        String id = uuid(value, "INVALID_EXPORT_ID");
        String clipId = uuid(clipValue, "INVALID_CLIP_ID");
        return transaction.execute(status -> contentLocked(lockPublicOwner(id), clipId));
    }

    /** Admission metadata only, with existing manual-proof source checks; no captions or sibling media. */
    public ExportVideoModels.Plan videoPackagePlan(String value,List<String> selection){
        String id=uuid(value,"INVALID_EXPORT_ID");
        if(selection==null||selection.isEmpty()||selection.size()>100||new HashSet<>(selection).size()!=selection.size()
            ||selection.stream().anyMatch(item->item==null||!item.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))throw bad("INVALID_VIDEO_PACKAGE","Select 1..100 unique clip IDs");
        return transaction.execute(status->{
            var owner=lockPublicOwner(id);requireCleanVideoPackage(owner);
            List<Object> parameters=new ArrayList<>();parameters.add(owner.get("job_id"));parameters.add(id);parameters.addAll(selection);
            String placeholders=String.join(",",Collections.nCopies(selection.size(),"?"));
            var rows=jdbc.queryForList("SELECT c.*,s.status,s.attempt,s.run_token,s.input_hash,s.manifest_key,s.manifest_hash FROM export_clip c JOIN job_stage s ON s.id=c.stage_id AND s.owner_type='EXPORT' AND s.owner_id=c.export_id AND s.shard_key=c.id AND s.job_id=? AND s.stage_type='EXPORT_CLIP' WHERE c.export_id=? AND c.id IN ("+placeholders+") ORDER BY c.start_ms,c.id",parameters.toArray());
            if(rows.size()!=selection.size())throw missing("EXPORT_CLIP_NOT_FOUND","A selected clip does not belong to this export");
            verifyVideoPackageFrozen(owner,Set.copyOf(selection));
            var clips=rows.stream().map(this::videoPackageClip).toList();
            return new ExportVideoModels.Plan(id,String.valueOf(owner.get("job_id")),String.valueOf(owner.get("video_id")),String.valueOf(owner.get("source_hash")),String.valueOf(owner.get("snapshot_hash")),
                ((Number)owner.get("approved_revision")).longValue(),String.valueOf(owner.get("encoding_profile")),(String)owner.get("clip_request_id"),clips);
        });
    }

    /** One short owner transaction per admitted file; archive copying occurs after it returns. */
    public Content videoPackageContent(ExportVideoModels.Plan plan,ExportVideoModels.Clip admitted){
        return transaction.execute(status->{
            var owner=lockPublicOwner(plan.exportId());requireCleanVideoPackage(owner);
            if(!Objects.equals(plan.jobId(),owner.get("job_id"))||!Objects.equals(plan.videoId(),owner.get("video_id"))
                ||!Objects.equals(plan.sourceVideoHash(),owner.get("source_hash"))||!Objects.equals(plan.snapshotHash(),owner.get("snapshot_hash"))
                ||plan.approvedRevision()!=((Number)owner.get("approved_revision")).longValue()||!Objects.equals(plan.profile(),owner.get("encoding_profile"))
                ||!Objects.equals(plan.clipRequestId(),owner.get("clip_request_id")))throw conflict("EXPORT_SNAPSHOT_INVALID","The admitted export source changed");
            if(!admitted.equals(videoPackageClip(clip(plan.exportId(),admitted.clipId(),true))))throw conflict("EXPORT_SNAPSHOT_INVALID","A selected clip changed after package admission");
            Content content=contentLocked(owner,admitted.clipId(),true);
            if(content.sizeBytes()!=admitted.sizeBytes()||!content.hash().equals(admitted.contentHash()))throw conflict("EXPORT_CONTENT_INTEGRITY_ERROR","Selected video bytes changed after package admission");
            return content;
        });
    }
    private static void requireCleanVideoPackage(Map<String,Object> owner){
        if(!"CLEAN".equals(owner.get("render_mode")))throw conflict("EXPORT_SNAPSHOT_INVALID","Video-only packages require original CLEAN exports");
        for(String field:List.of("source_hash","snapshot_hash"))if(!(owner.get(field) instanceof String hash)||!hash.matches("[a-f0-9]{64}"))throw conflict("EXPORT_SNAPSHOT_INVALID","Invalid export source summary");
        Object revision=owner.get("approved_revision");if(!(revision instanceof Number)||((Number)revision).longValue()<0||((Number)revision).longValue()>9007199254740991L
            ||!(owner.get("encoding_profile") instanceof String profile)||profile.isBlank()||profile.length()>128)throw conflict("EXPORT_SNAPSHOT_INVALID","Invalid export revision or encoding profile");
    }
    private ExportVideoModels.Clip videoPackageClip(Map<String,Object> row){
        if(!"SUCCEEDED".equals(row.get("status"))||!(row.get("size_bytes") instanceof Number size)||size.longValue()<1)throw conflict("EXPORT_CONTENT_NOT_READY","Every selected clip must have completed successfully");
        if(!(row.get("content_hash") instanceof String hash)||!hash.matches("[a-f0-9]{64}"))throw conflict("EXPORT_CONTENT_INTEGRITY_ERROR","A selected clip has an invalid content reference");
        long start=((Number)row.get("start_ms")).longValue(),end=((Number)row.get("end_ms")).longValue(),duration=0;
        List<ArtifactVerifier.SourcePart> parts=row.get("source_parts_json")==null?List.of(new ArtifactVerifier.SourcePart(start,end)):parseParts(String.valueOf(row.get("source_parts_json")));
        if(start<0||end<=start||end>9007199254740991L||parts.getFirst().startMs()!=start||parts.getLast().endMs()!=end)throw conflict("EXPORT_SNAPSHOT_INVALID","Selected source intervals are invalid");
        for(var part:parts){duration=Math.addExact(duration,part.endMs()-part.startMs());}if(duration<1||duration>9007199254740991L)throw conflict("EXPORT_SNAPSHOT_INVALID","Selected logical duration is invalid");
        String title=(String)(row.get("topic_title")!=null?row.get("topic_title"):row.get("product_name"));
        String segment=String.valueOf(row.get("segment_id"));if(segment.isBlank()||segment.length()>128||title!=null&&(title.isBlank()||title.length()>2000))throw conflict("EXPORT_SNAPSHOT_INVALID","Selected clip metadata is invalid");
        var execution=new ExportVideoModels.Execution(String.valueOf(row.get("stage_id")),((Number)row.get("attempt")).intValue(),
                (String)row.get("run_token"),(String)row.get("input_hash"),(String)row.get("manifest_key"),(String)row.get("manifest_hash"));
        if(execution.attempt()<1||execution.runToken()==null||!execution.runToken().matches("[A-Za-z0-9_-]{16,64}")
            ||execution.inputHash()==null||!execution.inputHash().matches("[a-f0-9]{64}")
            ||execution.manifestKey()==null||execution.manifestKey().isBlank()||execution.manifestHash()==null||!execution.manifestHash().matches("[a-f0-9]{64}"))
            throw conflict("EXPORT_SNAPSHOT_INVALID","Selected successful execution identity is invalid");
        return new ExportVideoModels.Clip(String.valueOf(row.get("id")),segment,title,start,end,duration,parts,size.longValue(),hash,execution);
    }

    /** Recheck frozen provenance and only the selected persisted clip identities, never sibling files. */
    private void verifyVideoPackageFrozen(Map<String,Object> owner,Set<String> selected){
        try{
            var analysis=jdbc.queryForMap("SELECT video_id,generation FROM analysis_job WHERE id=?",owner.get("job_id"));
            String sourceHash=jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?",String.class,owner.get("video_id"));
            JsonNode frozen=JSON.readTree(String.valueOf(owner.get("frozen_json")));
            String digest=sha256(ClipRequestService.canonicalJson(frozen));
            if(!frozen.isObject()||!digest.equals(owner.get("snapshot_hash"))||!digest.equals(owner.get("fingerprint"))
                ||!Objects.equals(owner.get("video_id"),analysis.get("video_id"))||!Objects.equals(sourceHash,owner.get("source_hash"))
                ||!Objects.equals(owner.get("job_id"),frozen.path("job_id").asText())||!Objects.equals(owner.get("video_id"),frozen.path("video_id").asText())
                ||!Objects.equals(sourceHash,frozen.path("source_hash").asText())||!frozen.path("generation").isIntegralNumber()
                ||!frozen.path("generation").canConvertToInt()||frozen.path("generation").intValue()!=((Number)analysis.get("generation")).intValue()
                ||!frozen.path("approved_revision").isIntegralNumber()||!frozen.path("approved_revision").canConvertToLong()
                ||frozen.path("approved_revision").longValue()!=((Number)owner.get("approved_revision")).longValue()
                ||!PROFILE.equals(owner.get("encoding_profile"))||!PROFILE.equals(frozen.path("encoding_profile").asText())
                ||frozen.has("render_mode")&&!"CLEAN".equals(frozen.path("render_mode").asText()))throw new IllegalArgumentException();
            if(owner.get("clip_request_id")!=null){verifyClipRequestFrozen(owner,selected);return;}
            JsonNode frozenClips=frozen.path("clips");if(!frozenClips.isArray()||frozenClips.isEmpty()||frozenClips.size()>100)throw new IllegalArgumentException();
            for(String id:selected){
                var row=clip(String.valueOf(owner.get("id")),id,false);JsonNode matching=null;
                for(JsonNode candidate:frozenClips)if(Objects.equals(row.get("segment_id"),candidate.path("segment_id").asText())){
                    if(matching!=null)throw new IllegalArgumentException();matching=candidate;
                }
                if(matching==null||row.get("source_parts_json")!=null
                    ||!Objects.equals(row.get("product_id"),matching.path("product_id").isMissingNode()?null:matching.path("product_id").asText())
                    ||!Objects.equals(row.get("product_name"),matching.path("product_name").isMissingNode()?null:matching.path("product_name").asText())
                    ||!Objects.equals(row.get("topic_title"),matching.path("title").isMissingNode()?null:matching.path("title").asText())
                    ||!matching.path("start_ms").isIntegralNumber()||!matching.path("end_ms").isIntegralNumber()
                    ||matching.path("start_ms").longValue()!=((Number)row.get("start_ms")).longValue()
                    ||matching.path("end_ms").longValue()!=((Number)row.get("end_ms")).longValue())throw new IllegalArgumentException();
                StageRow stage=stageRow(String.valueOf(row.get("stage_id")));
                if(!Objects.equals(owner.get("job_id"),stage.jobId())||!"EXPORT_CLIP".equals(stage.stageType())
                    ||!sha256(digest+"\n"+ClipRequestService.canonicalJson(matching)+"\n"+id).equals(stage.inputHash()))throw new IllegalArgumentException();
            }
        }catch(JobException invalid){throw invalid;}
        catch(RuntimeException invalid){throw conflict("EXPORT_SNAPSHOT_INVALID","Selected export failed frozen source verification");}
    }

    /** Caller holds analysis then export owner; never reacquire another parent. */
    private Content contentLocked(Map<String, Object> owner, String clipId) {
        return contentLocked(owner,clipId,false);
    }
    private Content contentLocked(Map<String, Object> owner, String clipId,boolean videoPackage) {
        Map<String, Object> clip = clip(String.valueOf(owner.get("id")), clipId, true);
        if (!"SUCCEEDED".equals(clip.get("status"))) { throw conflict("EXPORT_CONTENT_NOT_READY", "This clip has not completed successfully"); }
        Map<String, Object> analysis = jdbc.queryForMap("SELECT * FROM analysis_job WHERE id=?", owner.get("job_id"));
        StageRow stage = stageRow(String.valueOf(clip.get("stage_id")));
        ArtifactVerifier.ManifestIdentity identity = manifestIdentity(stage, analysis);
        if(videoPackage)verifyVideoPackageFrozen(owner,Set.of(clipId));
        var manifest = verifier.verifyExportManifest(stage.manifestKey(), stage.manifestHash(), identity, exportIdentity(stage,videoPackage));
        var file = manifest.artifacts().stream().filter(item -> "clip".equals(item.kind())).findFirst().orElseThrow();
        if (!file.sha256().equals(clip.get("content_hash")) || file.sizeBytes() != ((Number) clip.get("size_bytes")).longValue()) {
            throw conflict("EXPORT_CONTENT_INTEGRITY_ERROR", "Export bytes no longer match their registered reference");
        }
        return new Content(verifier.verifiedArtifactPath(file.key(), file.sha256(), identity), file.sizeBytes(), file.sha256(), clipId + ".mp4");
    }

    /** Caller already holds analysis_job; acquire this parent before its stages. */
    public Map<String, Object> lockOwner(String id, String analysisId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM export_job WHERE id=? AND job_id=? FOR UPDATE", id, analysisId);
        if (rows.isEmpty()) { throw missing("EXPORT_NOT_FOUND", "Export was not found"); }
        return rows.getFirst();
    }

    public boolean active(Map<String, Object> owner) { return owner != null && Set.of("QUEUED", "RUNNING").contains(owner.get("status")); }

    /** All siblings are serialized by the export parent, never by the analysis status. */
    public void refreshLocked(String id) {
        if("CANCELLED".equals(jdbc.queryForObject("SELECT status FROM export_job WHERE id=?",String.class,id)))return;
        List<Map<String, Object>> stages = jdbc.queryForList("SELECT status,attempt FROM job_stage WHERE owner_type='EXPORT' AND owner_id=? ORDER BY id FOR UPDATE", id);
        boolean pending = stages.stream().anyMatch(row -> Set.of("READY", "RUNNING", "RETRY_WAIT").contains(row.get("status")));
        boolean succeeded = stages.stream().anyMatch(row -> "SUCCEEDED".equals(row.get("status")));
        boolean failed = stages.stream().anyMatch(row -> "FAILED".equals(row.get("status")));
        boolean attempted = stages.stream().anyMatch(row -> ((Number) row.get("attempt")).intValue() > 0);
        String result = pending ? (attempted ? "RUNNING" : "QUEUED") : failed ? (succeeded ? "PARTIAL_FAILED" : "FAILED") : "SUCCEEDED";
        jdbc.update("UPDATE export_job SET status=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", result, id);
    }

    public Map<String, Object> claimConfig(StageRow stage) {
        return claimConfig(stage,false);
    }
    private Map<String,Object> claimConfig(StageRow stage,boolean selectedOnly) {
        Map<String, Object> owner = jdbc.queryForMap("SELECT * FROM export_job WHERE id=?", stage.ownerId());
        if(owner.get("clip_request_id")!=null)verifyClipRequestFrozen(owner,selectedOnly?Set.of(stage.shardKey()):null);
        Map<String, Object> row = clip(stage.ownerId(), stage.shardKey(), false);
        Map<String, Object> export = new TreeMap<>();
        export.put("export_id", stage.ownerId()); export.put("clip_id", row.get("id"));
        export.put("approved_revision", ((Number) owner.get("approved_revision")).longValue());
        export.put("snapshot_hash", owner.get("snapshot_hash")); export.put("segment_id", row.get("segment_id"));
        export.put("start_ms", ((Number) row.get("start_ms")).longValue()); export.put("end_ms", ((Number) row.get("end_ms")).longValue());
        export.put("encoding_profile", owner.get("encoding_profile"));
        if("CAPTIONED".equals(owner.get("render_mode"))){JsonNode frozen=captionRenderFrozen(owner);export.put("render_mode","CAPTIONED");export.put("caption_render_hash",frozen.path("caption_render_hash").asText());export.put("caption_render",frozen.path("caption_render"));}
        if(owner.get("clip_request_id")!=null){
            List<ArtifactVerifier.SourcePart> parts=sourceParts(row);export.put("parts",parts);export.put("duration_ms",parts.stream().mapToLong(p->p.endMs()-p.startMs()).sum());export.put("clip_request_id",owner.get("clip_request_id"));
        }
        return Map.of("export", export);
    }

    public ArtifactVerifier.ManifestIdentity manifestIdentity(StageRow stage, Map<String, Object> analysis) {
        String sourceHash = jdbc.queryForObject("SELECT source_hash FROM export_job WHERE id=?", String.class, stage.ownerId());
        return new ArtifactVerifier.ManifestIdentity(stage.jobId(), String.valueOf(analysis.get("video_id")),
                ((Number) analysis.get("generation")).intValue(), stage.id(), "EXPORT_CLIP", stage.attempt(), stage.runToken(), sourceHash);
    }

    public ArtifactVerifier.VerifiedManifest verifyResult(StageRow stage, Map<String, Object> analysis, String key, String hash) {
        return verifier.verifyExportManifest(key, hash, manifestIdentity(stage, analysis), exportIdentity(stage));
    }

    public void recordSuccess(StageRow stage, ArtifactVerifier.VerifiedManifest manifest) {
        var file = manifest.artifacts().stream().filter(item -> "clip".equals(item.kind())).findFirst().orElseThrow();
        jdbc.update("UPDATE export_clip SET size_bytes=?,content_hash=? WHERE export_id=? AND stage_id=?", file.sizeBytes(), file.sha256(), stage.ownerId(), stage.id());
        if(manifest.artifacts().stream().anyMatch(a->a.kind().equals("caption_render_info"))){var info=manifest.artifacts().stream().filter(a->a.kind().equals("media_info")).findFirst().orElseThrow();
            var owner=jdbc.queryForMap("SELECT * FROM export_job WHERE id=?",stage.ownerId());var analysis=jdbc.queryForMap("SELECT * FROM analysis_job WHERE id=?",stage.jobId());
            JsonNode media=verifier.readJsonArtifact(info,manifestIdentity(stage,analysis));
            jdbc.update("UPDATE caption_render SET media_json=? WHERE id=?",ClipRequestService.canonicalJson(JSON.valueToTree(Map.of("duration_ms",media.path("duration_ms").longValue(),"width",media.path("width").intValue(),"height",media.path("height").intValue()))),owner.get("id"));}
    }

    private ArtifactVerifier.ExportIdentity exportIdentity(StageRow stage) {
        return exportIdentity(stage,false);
    }
    private ArtifactVerifier.ExportIdentity exportIdentity(StageRow stage,boolean selectedOnly) {
        @SuppressWarnings("unchecked")
        Map<String, Object> export = (Map<String, Object>) claimConfig(stage,selectedOnly).get("export");
        return new ArtifactVerifier.ExportIdentity(stage.ownerId(), String.valueOf(export.get("clip_id")),
                ((Number) export.get("approved_revision")).longValue(), String.valueOf(export.get("snapshot_hash")),
                String.valueOf(export.get("segment_id")), ((Number) export.get("start_ms")).longValue(),
                ((Number) export.get("end_ms")).longValue(), String.valueOf(export.get("encoding_profile")), stage.inputHash(),
                export.get("parts")==null?List.of():castParts(export.get("parts")),(String)export.get("clip_request_id"),
                (JsonNode)export.get("caption_render"),(String)export.get("caption_render_hash"));
    }

    public JsonNode captionRenderFrozen(Map<String,Object> owner){
        try{JsonNode frozen=JSON.readTree(String.valueOf(owner.get("frozen_json")));JsonNode render=frozen.path("caption_render");CaptionRenderSpec.validate(render);
            if(!"CAPTIONED".equals(owner.get("render_mode"))||!CaptionRenderSpec.PROFILE.equals(owner.get("encoding_profile"))||!"CAPTIONED".equals(frozen.path("render_mode").asText())
                ||!String.valueOf(owner.get("snapshot_hash")).equals(sha256(ClipRequestService.canonicalJson(frozen)))
                ||!frozen.path("caption_render_hash").asText().equals(sha256(ClipRequestService.canonicalJson(render))))throw new IllegalArgumentException();
            var sources=jdbc.queryForList("SELECT * FROM export_job WHERE id=?",render.path("source_export_id").asText());
            if(sources.size()!=1)throw new IllegalArgumentException();var source=sources.getFirst();
            if(source.get("clip_request_id")!=null){
                verifyClipRequestFrozen(source);
                var clips=jdbc.queryForList("SELECT * FROM export_clip WHERE export_id=? AND id=?",source.get("id"),render.path("source_clip_id").asText());
                if(clips.size()!=1||!Objects.equals(owner.get("job_id"),source.get("job_id"))||!Objects.equals(owner.get("video_id"),source.get("video_id"))
                    ||!Objects.equals(owner.get("source_hash"),source.get("source_hash"))||!render.path("source_snapshot_hash").asText().equals(source.get("snapshot_hash"))
                    ||!render.path("input_media").path("sha256").asText().equals(clips.getFirst().get("content_hash")))throw new IllegalArgumentException();
            }
            return frozen;
        }catch(RuntimeException invalid){throw conflict("EXPORT_SNAPSHOT_INVALID","Frozen caption render failed integrity validation");}
    }

    private static List<Map<String,Object>> deliveryClips(ClipRequestService.DeliverySelection selection){
        List<Map<String,Object>> clips=new ArrayList<>();
        for(JsonNode output:selection.outputs()){
            List<Map<String,Object>> parts=new ArrayList<>();long duration=0;
            for(JsonNode part:output.path("parts")){
                long start=part.path("start_ms").longValue(),end=part.path("end_ms").longValue();
                duration=Math.addExact(duration,end-start);parts.add(new TreeMap<>(Map.of("start_ms",start,"end_ms",end)));
            }
            if(parts.isEmpty())throw bad("INVALID_EXPORT_SEGMENT","No source parts were retained");
            Map<String,Object> clip=new TreeMap<>();clip.put("segment_id",output.path("output_id").asText());clip.put("goal_id",output.path("goal_id").asText());
            clip.put("title",output.path("title").asText());clip.put("start_ms",parts.getFirst().get("start_ms"));clip.put("end_ms",parts.getLast().get("end_ms"));clip.put("parts",parts);clip.put("duration_ms",duration);clips.add(clip);
        }
        return clips;
    }

    /** Ordinary snapshots deliberately retain their historical field set and fingerprint. */
    private static Map<String,Object> deliveryFrozen(Map<String,Object> request,Map<String,Object> analysis,long revision,String planHash,
            ClipRequestService.DeliverySelection selection,List<Map<String,Object>> clips){
        Map<String,Object> frozen=new TreeMap<>();frozen.put("job_id",request.get("job_id"));frozen.put("video_id",request.get("video_id"));frozen.put("generation",analysis.get("generation"));
        frozen.put("source_hash",request.get("source_hash"));frozen.put("approved_revision",revision);frozen.put("encoding_profile",PROFILE);frozen.put("clips",clips);frozen.put("clip_request_id",request.get("id"));frozen.put("plan_hash",planHash);frozen.put("analysis_mode","intent_clip");
        if(selection.review()!=null){frozen.put("manual_review",selection.review());frozen.put("manual_review_hash",selection.review().path("review_hash").asText());}
        return frozen;
    }

    /** Shared by public reads, claims, completion manifests and caption sources. No worker fields are added. */
    public JsonNode verifyClipRequestFrozen(Map<String,Object> owner){
        return verifyClipRequestFrozen(owner,null);
    }
    private JsonNode verifyClipRequestFrozen(Map<String,Object> owner,Set<String> selected){
        try{
            JsonNode frozen=JSON.readTree(String.valueOf(owner.get("frozen_json")));
            String digest=sha256(ClipRequestService.canonicalJson(frozen));
            if(!digest.equals(owner.get("snapshot_hash"))||!digest.equals(owner.get("fingerprint")))throw new IllegalArgumentException();
            var requests=jdbc.queryForList("SELECT * FROM clip_request WHERE id=? AND job_id=?",owner.get("clip_request_id"),owner.get("job_id"));
            if(requests.size()!=1)throw new IllegalArgumentException();var request=requests.getFirst();
            var analysis=jdbc.queryForMap("SELECT * FROM analysis_job WHERE id=?",owner.get("job_id"));
            long revision=((Number)owner.get("approved_revision")).longValue();String planHash=ClipPlanValidator.hash(ClipPlanValidator.text(frozen,"plan_hash",64));
            var selection=clipRequests.deliverySelection(request,revision,planHash,ClipRequestService.manualHash(frozen),true);
            var clips=deliveryClips(selection);JsonNode expected=JSON.valueToTree(deliveryFrozen(request,analysis,revision,planHash,selection,clips));
            if(clips.isEmpty()||!ClipRequestService.canonicalJson(expected).equals(ClipRequestService.canonicalJson(frozen))||!Objects.equals(owner.get("video_id"),request.get("video_id"))
                ||!Objects.equals(owner.get("source_hash"),request.get("source_hash"))||!PROFILE.equals(owner.get("encoding_profile"))||!"CLEAN".equals(owner.get("render_mode")))throw new IllegalArgumentException();
            List<Object> parameters=new ArrayList<>();parameters.add(owner.get("id"));if(selected!=null)parameters.addAll(selected);
            var persisted=jdbc.queryForList("SELECT c.*,s.job_id AS stage_job,s.owner_type AS stage_owner_type,s.owner_id AS stage_owner,s.shard_key,s.stage_type,s.input_hash FROM export_clip c LEFT JOIN job_stage s ON s.id=c.stage_id WHERE c.export_id=?"+(selected==null?"":" AND c.id IN ("+String.join(",",Collections.nCopies(selected.size(),"?"))+")"),parameters.toArray());
            if(persisted.size()!=(selected==null?clips.size():selected.size()))throw new IllegalArgumentException();Map<String,Map<String,Object>> byId=new HashMap<>();for(var clip:clips)byId.put(String.valueOf(clip.get("segment_id")),clip);
            for(var row:persisted){var clip=byId.remove(String.valueOf(row.get("segment_id")));if(clip==null)throw new IllegalArgumentException();
                String expectedParts=ClipRequestService.canonicalJson(JSON.valueToTree(clip.get("parts")));
                String actualParts=ClipRequestService.canonicalJson(JSON.readTree(String.valueOf(row.get("source_parts_json"))));
                if(!expectedParts.equals(actualParts)
                ||!Objects.equals(clip.get("title"),row.get("topic_title"))||((Number)clip.get("start_ms")).longValue()!=((Number)row.get("start_ms")).longValue()
                ||((Number)clip.get("end_ms")).longValue()!=((Number)row.get("end_ms")).longValue()
                ||!Objects.equals(owner.get("job_id"),row.get("stage_job"))||!"EXPORT".equals(row.get("stage_owner_type"))||!Objects.equals(owner.get("id"),row.get("stage_owner"))
                ||!Objects.equals(row.get("id"),row.get("shard_key"))||!"EXPORT_CLIP".equals(row.get("stage_type"))
                ||!sha256(digest+"\n"+ClipRequestService.canonicalJson(JSON.valueToTree(clip))+"\n"+row.get("id")).equals(row.get("input_hash")))throw new IllegalArgumentException();}
            return frozen;
        }catch(JobException invalid){throw invalid;}catch(RuntimeException invalid){throw conflict("EXPORT_SNAPSHOT_INVALID","Frozen intent export failed plan and manual-review verification");}
    }

    private Map<String, Object> lockPublicOwner(String id) {
        List<String> jobs = jdbc.queryForList("SELECT job_id FROM export_job WHERE id=?", String.class, id);
        if (jobs.isEmpty()) { throw missing("EXPORT_NOT_FOUND", "Export was not found"); }
        lockAnalysis(jobs.getFirst());
        return lockOwner(id, jobs.getFirst());
    }

    private Map<String, Object> lockAnalysis(String jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM analysis_job WHERE id=? FOR UPDATE", jobId);
        if (rows.isEmpty()) { throw missing("JOB_NOT_FOUND", "Analysis job was not found"); }
        return rows.getFirst();
    }

    private Snapshot snapshot(Map<String, Object> owner) {
        if(owner.get("clip_request_id")!=null)verifyClipRequestFrozen(owner);
        else if("CAPTIONED".equals(owner.get("render_mode")))captionRenderFrozen(owner);
        String id = String.valueOf(owner.get("id"));
        List<Clip> clips = jdbc.query("SELECT c.*,s.status,s.attempt,s.error_code,s.diagnostic_json FROM export_clip c JOIN job_stage s ON s.id=c.stage_id WHERE c.export_id=? ORDER BY c.start_ms,c.id",
                (row, index) -> {
                    boolean ready = "SUCCEEDED".equals(row.getString("status"));
                    String url = ready ? "/api/exports/" + id + "/clips/" + row.getString("id") + "/content" : null;
                    Number size = (Number) row.getObject("size_bytes");
                    return new Clip(row.getString("id"), row.getString("segment_id"), row.getString("product_id"), row.getString("product_name"),
                            row.getLong("start_ms"), row.getLong("end_ms"), row.getString("stage_id"), row.getString("status"), row.getInt("attempt"),
                            row.getString("error_code"), size == null ? null : size.longValue(), url, url == null ? null : url + "?download=true",
                            StageDiagnostics.read(row.getString("diagnostic_json"), row.getString("error_code"), row.getInt("attempt")), row.getString("topic_title"),
                            row.getString("source_parts_json")==null?null:parseParts(row.getString("source_parts_json")));
                }, id);
        return new Snapshot(id, String.valueOf(owner.get("job_id")), String.valueOf(owner.get("video_id")), ((Number) owner.get("approved_revision")).longValue(),
                String.valueOf(owner.get("snapshot_hash")), String.valueOf(owner.get("encoding_profile")), String.valueOf(owner.get("status")),
                ((Number) owner.get("state_version")).longValue(), instant(owner.get("created_at")), instant(owner.get("updated_at")), clips,(String)owner.get("clip_request_id"));
    }

    private Map<String, Object> clip(String exportId, String clipId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT c.*,s.status,s.attempt,s.error_code,s.run_token,s.input_hash,s.manifest_key,s.manifest_hash FROM export_clip c JOIN job_stage s ON s.id=c.stage_id AND s.owner_type='EXPORT' AND s.owner_id=c.export_id AND s.shard_key=c.id WHERE c.export_id=? AND c.id=?" + (lock ? " FOR UPDATE" : ""), exportId, clipId);
        if (rows.isEmpty()) { throw missing("EXPORT_CLIP_NOT_FOUND", "Export clip was not found"); }
        return rows.getFirst();
    }

    private StageRow stageRow(String id) {
        return jdbc.queryForObject("SELECT * FROM job_stage WHERE id=?", (row, index) -> new StageRow(row.getString("id"), row.getString("job_id"), row.getString("stage_type"),
                row.getString("shard_key"), row.getString("status"), row.getInt("attempt"), row.getString("run_token"), row.getString("worker_id"),
                timestampInstant(row.getTimestamp("lease_until")), timestampInstant(row.getTimestamp("hard_deadline_at")), timestampInstant(row.getTimestamp("next_run_at")),
                row.getString("input_hash"), row.getString("checkpoint_key"), row.getString("checkpoint_hash"), row.getString("manifest_key"), row.getString("manifest_hash"), row.getString("error_code"),
                row.getString("owner_type"), row.getString("owner_id"),
                StageDiagnostics.read(row.getString("diagnostic_json"), row.getString("error_code"), row.getInt("attempt"))), id);
    }

    private static Instant timestampInstant(Timestamp value) { return value == null ? null : value.toInstant(); }
    @SuppressWarnings("unchecked") private static List<ArtifactVerifier.SourcePart> castParts(Object value){return (List<ArtifactVerifier.SourcePart>)value;}
    private static List<ArtifactVerifier.SourcePart> sourceParts(Map<String,Object> row){return parseParts(String.valueOf(row.get("source_parts_json")));}
    private static List<ArtifactVerifier.SourcePart> parseParts(String raw){
        JsonNode parts=JSON.readTree(raw);if(!parts.isArray()||parts.isEmpty()||parts.size()>12)throw conflict("EXPORT_SNAPSHOT_INVALID","Invalid source parts");List<ArtifactVerifier.SourcePart> result=new ArrayList<>();long previous=-1;
        for(JsonNode part:parts){long start=ClipPlanValidator.number(part,"start_ms"),end=ClipPlanValidator.number(part,"end_ms");if(end<=start||start<previous)throw conflict("EXPORT_SNAPSHOT_INVALID","Invalid source part order");previous=end;result.add(new ArtifactVerifier.SourcePart(start,end));}return List.copyOf(result);
    }
    private static Instant instant(Object value) { return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC); }
    private static String uuid(String value, String code) { try { String id = UUID.fromString(value).toString(); if (!id.equalsIgnoreCase(value)) { throw new IllegalArgumentException(); } return id; } catch (IllegalArgumentException | NullPointerException failure) { throw bad(code, "Identifier must be a UUID"); } }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); } }
    private static JobException bad(String code, String message) { return new JobException(HttpStatus.BAD_REQUEST, code, message); }
    private static JobException missing(String code, String message) { return new JobException(HttpStatus.NOT_FOUND, code, message); }
    private static JobException conflict(String code, String message) { return new JobException(HttpStatus.CONFLICT, code, message); }
}
