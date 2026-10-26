package io.livecut.exports;

import static io.livecut.exports.ExportModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import io.livecut.jobs.JobModels.StageRow;
import io.livecut.jobs.StageDiagnostics;
import io.livecut.review.ReviewModels;
import io.livecut.review.ReviewService;
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
    private final ArtifactVerifier verifier;
    private final TransactionTemplate transaction;
    private final int maxAttempts;

    public ExportService(JdbcTemplate jdbc, ReviewService reviews, ArtifactVerifier verifier,
                         PlatformTransactionManager manager,
                         @Value("${livecut.jobs.max-attempts:3}") int maxAttempts) {
        this.jdbc = jdbc; this.reviews = reviews; this.verifier = verifier; this.maxAttempts = maxAttempts;
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
            // ReviewService acquires the same analysis lock before review rows.
            // It is deliberately called before any export/stage lock is held.
            ReviewModels.Snapshot review = reviews.get(jobId, request.approvedRevision());
            if (!"CONFIRMED".equals(review.status())) { throw conflict("EXPORT_REQUIRES_CONFIRMATION", "Only an explicitly confirmed revision may be exported"); }
            Map<String, ReviewModels.Product> products = new HashMap<>();
            review.products().forEach(product -> products.put(product.id(), product));
            Map<String, ReviewModels.Segment> segments = new HashMap<>();
            review.segments().forEach(segment -> segments.put(segment.id(), segment));
            List<Map<String, Object>> frozenClips = new ArrayList<>();
            for (String segmentId : request.segmentIds().stream().sorted().toList()) {
                ReviewModels.Segment segment = segments.get(segmentId);
                if (segment == null || !segment.enabled() || segment.productId() == null || !products.containsKey(segment.productId())) {
                    throw bad("INVALID_EXPORT_SEGMENT", "Every selected segment must be enabled and assigned in the confirmed revision");
                }
                frozenClips.add(new TreeMap<>(Map.of("segment_id", segment.id(), "product_id", segment.productId(),
                        "product_name", products.get(segment.productId()).name(), "start_ms", segment.startMs(), "end_ms", segment.endMs())));
            }
            String sourceHash = jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, job.get("video_id"));
            Map<String, Object> frozen = new TreeMap<>();
            frozen.put("job_id", jobId); frozen.put("video_id", job.get("video_id")); frozen.put("generation", job.get("generation"));
            frozen.put("source_hash", sourceHash); frozen.put("approved_revision", request.approvedRevision());
            frozen.put("encoding_profile", PROFILE); frozen.put("clips", frozenClips);
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
                jdbc.update("INSERT INTO export_clip (id,export_id,stage_id,segment_id,product_id,product_name,start_ms,end_ms) VALUES (?,?,?,?,?,?,?,?)",
                        clipId, exportId, stageId, frozenClip.get("segment_id"), frozenClip.get("product_id"), frozenClip.get("product_name"), frozenClip.get("start_ms"), frozenClip.get("end_ms"));
            }
            return snapshot(lockOwner(exportId, jobId));
        });
    }

    public Page list(String analysisId, int limit, int offset) {
        String jobId = uuid(analysisId, "INVALID_JOB_ID");
        if (limit < 1 || limit > 100 || offset < 0) { throw bad("INVALID_PAGINATION", "limit must be 1..100 and offset nonnegative"); }
        return transaction.execute(status -> {
            lockAnalysis(jobId);
            List<String> ids = jdbc.queryForList("SELECT id FROM export_job WHERE job_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", String.class, jobId, limit, offset);
            return new Page(ids.stream().map(id -> snapshot(lockOwner(id, jobId))).toList(), limit, offset);
        });
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
                        clip.startMs(), clip.endMs(), clip.status(), clip.attempt(), clip.errorCode(), file));
            }
            return new Manifest("1.0", "export_manifest", id, batch.jobId(), batch.videoId(), generation,
                    batch.approvedRevision(), String.valueOf(owner.get("source_hash")), batch.snapshotHash(),
                    batch.profile(), batch.status(), batch.stateVersion(), batch.createdAt(), batch.updatedAt(), List.copyOf(clips));
        });
    }

    public Snapshot retry(String value, String clipValue) {
        String id = uuid(value, "INVALID_EXPORT_ID");
        String clipId = uuid(clipValue, "INVALID_CLIP_ID");
        return transaction.execute(status -> {
            Map<String, Object> owner = lockPublicOwner(id);
            Map<String, Object> clip = clip(id, clipId, true);
            if (!"FAILED".equals(clip.get("status"))) { throw conflict("EXPORT_CLIP_NOT_RETRYABLE", "Only this export's failed clip may be retried"); }
            if (((Number) clip.get("attempt")).intValue() >= maxAttempts) { throw conflict("ATTEMPTS_EXHAUSTED", "Clip execution attempts are exhausted"); }
            jdbc.update("UPDATE job_stage SET status='READY',run_token=NULL,worker_id=NULL,lease_until=NULL,hard_deadline_at=NULL,next_run_at=CURRENT_TIMESTAMP(6),error_code=NULL,diagnostic_json=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
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

    /** Caller holds analysis then export owner; never reacquire another parent. */
    private Content contentLocked(Map<String, Object> owner, String clipId) {
        Map<String, Object> clip = clip(String.valueOf(owner.get("id")), clipId, true);
        if (!"SUCCEEDED".equals(clip.get("status"))) { throw conflict("EXPORT_CONTENT_NOT_READY", "This clip has not completed successfully"); }
        Map<String, Object> analysis = jdbc.queryForMap("SELECT * FROM analysis_job WHERE id=?", owner.get("job_id"));
        StageRow stage = stageRow(String.valueOf(clip.get("stage_id")));
        ArtifactVerifier.ManifestIdentity identity = manifestIdentity(stage, analysis);
        var manifest = verifier.verifyExportManifest(stage.manifestKey(), stage.manifestHash(), identity, exportIdentity(stage));
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
        List<Map<String, Object>> stages = jdbc.queryForList("SELECT status,attempt FROM job_stage WHERE owner_type='EXPORT' AND owner_id=? ORDER BY id FOR UPDATE", id);
        boolean pending = stages.stream().anyMatch(row -> Set.of("READY", "RUNNING", "RETRY_WAIT").contains(row.get("status")));
        boolean succeeded = stages.stream().anyMatch(row -> "SUCCEEDED".equals(row.get("status")));
        boolean failed = stages.stream().anyMatch(row -> "FAILED".equals(row.get("status")));
        boolean attempted = stages.stream().anyMatch(row -> ((Number) row.get("attempt")).intValue() > 0);
        String result = pending ? (attempted ? "RUNNING" : "QUEUED") : failed ? (succeeded ? "PARTIAL_FAILED" : "FAILED") : "SUCCEEDED";
        jdbc.update("UPDATE export_job SET status=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", result, id);
    }

    public Map<String, Object> claimConfig(StageRow stage) {
        Map<String, Object> owner = jdbc.queryForMap("SELECT * FROM export_job WHERE id=?", stage.ownerId());
        Map<String, Object> row = clip(stage.ownerId(), stage.shardKey(), false);
        Map<String, Object> export = new TreeMap<>();
        export.put("export_id", stage.ownerId()); export.put("clip_id", row.get("id"));
        export.put("approved_revision", ((Number) owner.get("approved_revision")).longValue());
        export.put("snapshot_hash", owner.get("snapshot_hash")); export.put("segment_id", row.get("segment_id"));
        export.put("start_ms", ((Number) row.get("start_ms")).longValue()); export.put("end_ms", ((Number) row.get("end_ms")).longValue());
        export.put("encoding_profile", owner.get("encoding_profile"));
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
    }

    private ArtifactVerifier.ExportIdentity exportIdentity(StageRow stage) {
        @SuppressWarnings("unchecked")
        Map<String, Object> export = (Map<String, Object>) claimConfig(stage).get("export");
        return new ArtifactVerifier.ExportIdentity(stage.ownerId(), String.valueOf(export.get("clip_id")),
                ((Number) export.get("approved_revision")).longValue(), String.valueOf(export.get("snapshot_hash")),
                String.valueOf(export.get("segment_id")), ((Number) export.get("start_ms")).longValue(),
                ((Number) export.get("end_ms")).longValue(), PROFILE, stage.inputHash());
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
        String id = String.valueOf(owner.get("id"));
        List<Clip> clips = jdbc.query("SELECT c.*,s.status,s.attempt,s.error_code,s.diagnostic_json FROM export_clip c JOIN job_stage s ON s.id=c.stage_id WHERE c.export_id=? ORDER BY c.start_ms,c.id",
                (row, index) -> {
                    boolean ready = "SUCCEEDED".equals(row.getString("status"));
                    String url = ready ? "/api/exports/" + id + "/clips/" + row.getString("id") + "/content" : null;
                    Number size = (Number) row.getObject("size_bytes");
                    return new Clip(row.getString("id"), row.getString("segment_id"), row.getString("product_id"), row.getString("product_name"),
                            row.getLong("start_ms"), row.getLong("end_ms"), row.getString("stage_id"), row.getString("status"), row.getInt("attempt"),
                            row.getString("error_code"), size == null ? null : size.longValue(), url, url == null ? null : url + "?download=true",
                            StageDiagnostics.read(row.getString("diagnostic_json"), row.getString("error_code"), row.getInt("attempt")));
                }, id);
        return new Snapshot(id, String.valueOf(owner.get("job_id")), String.valueOf(owner.get("video_id")), ((Number) owner.get("approved_revision")).longValue(),
                String.valueOf(owner.get("snapshot_hash")), String.valueOf(owner.get("encoding_profile")), String.valueOf(owner.get("status")),
                ((Number) owner.get("state_version")).longValue(), instant(owner.get("created_at")), instant(owner.get("updated_at")), clips);
    }

    private Map<String, Object> clip(String exportId, String clipId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT c.*,s.status,s.attempt FROM export_clip c JOIN job_stage s ON s.id=c.stage_id AND s.owner_type='EXPORT' AND s.owner_id=c.export_id AND s.shard_key=c.id WHERE c.export_id=? AND c.id=?" + (lock ? " FOR UPDATE" : ""), exportId, clipId);
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
    private static Instant instant(Object value) { return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC); }
    private static String uuid(String value, String code) { try { String id = UUID.fromString(value).toString(); if (!id.equalsIgnoreCase(value)) { throw new IllegalArgumentException(); } return id; } catch (IllegalArgumentException | NullPointerException failure) { throw bad(code, "Identifier must be a UUID"); } }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); } }
    private static JobException bad(String code, String message) { return new JobException(HttpStatus.BAD_REQUEST, code, message); }
    private static JobException missing(String code, String message) { return new JobException(HttpStatus.NOT_FOUND, code, message); }
    private static JobException conflict(String code, String message) { return new JobException(HttpStatus.CONFLICT, code, message); }
}
