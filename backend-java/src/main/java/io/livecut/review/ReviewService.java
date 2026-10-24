package io.livecut.review;

import static io.livecut.review.ReviewModels.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Human snapshots never rewrite the machine result or an older revision. */
@Service
public class ReviewService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PRODUCT_FIELDS = Set.of("id", "name");
    private static final Set<String> SEGMENT_FIELDS = Set.of("id", "start_ms", "end_ms", "product_id", "enabled");
    private final JdbcTemplate jdbc;
    private final ArtifactVerifier artifacts;
    private final TransactionTemplate transaction;

    public ReviewService(JdbcTemplate jdbc, ArtifactVerifier artifacts, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Snapshot get(String id, Long requestedRevision) {
        String jobId = uuid(id);
        if (requestedRevision != null && requestedRevision < 0) { throw invalid("revision must be nonnegative"); }
        return transaction.execute(status -> {
            Document document = document(jobId);
            long revision = requestedRevision == null ? document.latest() : requestedRevision;
            return snapshot(document, revision);
        });
    }

    public Snapshot save(String id, JsonNode request) {
        String jobId = uuid(id);
        fields(request, Set.of("base_revision", "products", "segments"));
        long baseRevision = nonnegative(request, "base_revision");
        return transaction.execute(status -> {
            Document document = document(jobId);
            requireCurrent(document, baseRevision);
            Content edited = edited(request, document.source());
            long revision;
            try { revision = Math.addExact(baseRevision, 1L); }
            catch (ArithmeticException exhausted) { throw conflict("REVIEW_REVISION_LIMIT", "Review revision limit reached"); }
            jdbc.update("INSERT INTO review_revision (job_id,revision,snapshot_json) VALUES (?,?,?)",
                    jobId, revision, JSON.writeValueAsString(edited));
            jdbc.update("UPDATE review_document SET latest_revision=? WHERE job_id=?", revision, jobId);
            return snapshot(new Document(document.job(), document.sourceHash(), document.source(), revision), revision);
        });
    }

    public Snapshot confirm(String id, JsonNode request) {
        String jobId = uuid(id);
        fields(request, Set.of("revision"));
        long revision = nonnegative(request, "revision");
        return transaction.execute(status -> {
            Document document = document(jobId);
            requireCurrent(document, revision);
            Content content = content(jobId, revision);
            if (content.segments().stream().anyMatch(segment -> segment.enabled() && segment.productId() == null)) {
                throw conflict("REVIEW_UNASSIGNED_SEGMENT", "Assign a product to each enabled segment before confirming");
            }
            // The parent job lock serializes confirmations and edits. The unique
            // key gives repeat confirmation of the same current version no effect.
            jdbc.update("INSERT INTO review_confirmation (job_id,revision) VALUES (?,?) ON DUPLICATE KEY UPDATE revision=revision",
                    jobId, revision);
            return snapshot(document, revision);
        });
    }

    private record Job(String id, String videoId, int generation, String sourceHash) {}
    private record Source(long durationMs, List<Product> products, List<Segment> segments,
                          List<Utterance> utterances, List<Prediction> predictions) {}
    private record Document(Job job, String sourceHash, Source source, long latest) {}
    public record Content(List<Product> products, List<Segment> segments) {}

    /** Always locks job first, matching the scheduler and model-call lock order. */
    private Document document(String jobId) {
        List<Map<String, Object>> jobs = jdbc.queryForList(
                "SELECT id,video_id,generation,status FROM analysis_job WHERE id=? FOR UPDATE", jobId);
        if (jobs.isEmpty()) { throw missing("JOB_NOT_FOUND", "Analysis job was not found"); }
        Map<String, Object> row = jobs.getFirst();
        if (!"REVIEW_READY".equals(row.get("status"))) { throw conflict("REVIEW_NOT_READY", "Analysis must reach REVIEW_READY before review"); }
        String videoId = (String) row.get("video_id");
        String sourceHashForVideo = jdbc.queryForObject("SELECT content_hash FROM video_asset WHERE id=?", String.class, videoId);
        Job job = new Job(jobId, videoId, ((Number) row.get("generation")).intValue(), sourceHashForVideo);
        List<Map<String, Object>> documents = jdbc.queryForList("SELECT source_hash,source_json,latest_revision FROM review_document WHERE job_id=? FOR UPDATE", jobId);
        if (!documents.isEmpty()) {
            Map<String, Object> stored = documents.getFirst();
            return new Document(job, (String) stored.get("source_hash"), source(JSON.readTree(String.valueOf(stored.get("source_json"))), job),
                    ((Number) stored.get("latest_revision")).longValue());
        }
        List<Map<String, Object>> stages = jdbc.queryForList("SELECT id,attempt,run_token,manifest_key,manifest_hash FROM job_stage WHERE job_id=? AND stage_type='TIMELINE' AND status='SUCCEEDED' ORDER BY shard_key FOR UPDATE", jobId);
        if (stages.size() != 1) { throw conflict("REVIEW_SOURCE_UNAVAILABLE", "A single completed timeline is required for review"); }
        Map<String, Object> stage = stages.getFirst();
        ArtifactVerifier.ManifestIdentity identity = new ArtifactVerifier.ManifestIdentity(jobId, job.videoId(), job.generation(),
                (String) stage.get("id"), "TIMELINE", ((Number) stage.get("attempt")).intValue(), (String) stage.get("run_token"), job.sourceHash());
        String manifestKey = (String) stage.get("manifest_key");
        String manifestHash = (String) stage.get("manifest_hash");
        var manifest = artifacts.verifyManifest(manifestKey, manifestHash, identity);
        String sourceHash = manifest.artifacts().stream().filter(item -> "analysis_result".equals(item.kind()))
                .findFirst().orElseThrow(() -> conflict("REVIEW_SOURCE_UNAVAILABLE", "Timeline has no analysis result")).sha256();
        JsonNode raw = artifacts.readAnalysisResult(manifestKey, manifestHash, identity);
        Source source = source(raw, job);
        jdbc.update("INSERT INTO review_document (job_id,source_hash,source_json,duration_ms,latest_revision) VALUES (?,?,?,?,0)",
                jobId, sourceHash, JSON.writeValueAsString(raw), source.durationMs());
        jdbc.update("INSERT INTO review_revision (job_id,revision,snapshot_json) VALUES (?,0,?)", jobId,
                JSON.writeValueAsString(new Content(source.products(), source.segments())));
        return new Document(job, sourceHash, source, 0);
    }

    private Snapshot snapshot(Document document, long revision) {
        Content content = content(document.job().id(), revision);
        List<Long> confirmed = jdbc.queryForList("SELECT revision FROM review_confirmation WHERE job_id=? ORDER BY revision", Long.class, document.job().id());
        return new Snapshot(document.job().id(), document.job().videoId(), document.job().generation(), "model_result",
                document.source().durationMs(), revision, confirmed.contains(revision) ? "CONFIRMED" : "DRAFT", document.sourceHash(),
                List.copyOf(confirmed), content.products(), content.segments(), document.source().utterances(), document.source().predictions());
    }

    private Content content(String jobId, long revision) {
        List<String> rows = jdbc.queryForList("SELECT snapshot_json FROM review_revision WHERE job_id=? AND revision=?", String.class, jobId, revision);
        if (rows.isEmpty()) { throw missing("REVIEW_REVISION_NOT_FOUND", "Review revision was not found"); }
        return JSON.readValue(rows.getFirst(), Content.class);
    }

    private static void requireCurrent(Document document, long revision) {
        if (document.latest() != revision) { throw conflict("REVIEW_REVISION_CONFLICT", "Review changed; reload the current revision before saving or confirming"); }
    }

    private static Content edited(JsonNode request, Source source) {
        JsonNode productsNode = array(request, "products", 1000);
        Map<String, Product> originals = new HashMap<>();
        source.products().forEach(product -> originals.put(product.id(), product));
        List<Product> products = new ArrayList<>();
        Set<String> productIds = new HashSet<>();
        for (JsonNode product : productsNode) {
            fields(product, PRODUCT_FIELDS);
            String id = identifier(product, "id");
            if (!productIds.add(id)) { throw invalid("Product ids must be unique"); }
            String name = name(product, "name");
            Product original = originals.get(id);
            products.add(new Product(id, name, original != null && original.name().equals(name) ? "model" : "manual"));
        }
        Map<String, Segment> originalsById = new HashMap<>();
        source.segments().forEach(segment -> originalsById.put(segment.id(), segment));
        JsonNode segmentsNode = array(request, "segments", 10000);
        List<Segment> segments = new ArrayList<>();
        Set<String> segmentIds = new HashSet<>();
        for (JsonNode segment : segmentsNode) {
            fields(segment, SEGMENT_FIELDS);
            String id = identifier(segment, "id");
            if (!segmentIds.add(id)) { throw invalid("Segment ids must be unique"); }
            long start = nonnegative(segment, "start_ms");
            long end = nonnegative(segment, "end_ms");
            bounds(start, end, source.durationMs());
            String productId = nullableIdentifier(segment, "product_id");
            if (productId != null && !productIds.contains(productId)) { throw invalid("Segment product_id must reference a product in this revision"); }
            JsonNode enabled = segment.path("enabled");
            if (!enabled.isBoolean()) { throw invalid("enabled must be a boolean"); }
            Segment original = originalsById.get(id);
            segments.add(new Segment(id, start, end, productId, enabled.booleanValue(), original == null ? "manual" : "model",
                    original != null && original.startMs() == start && original.endMs() == end ? "model" : "manual",
                    original != null && Objects.equals(original.productId(), productId) ? "model" : "manual", original == null ? null : id));
        }
        noOverlap(segments);
        return new Content(List.copyOf(products), List.copyOf(segments));
    }

    private static Source source(JsonNode raw, Job job) {
        try {
            if (!raw.isObject() || !"2.0".equals(text(raw, "schema_version", 16))
                    || !"model_result".equals(text(raw, "data_kind", 32))
                    || !"DRAFT".equals(text(raw, "review_status", 32))
                    || !job.id().equals(text(raw, "job_id", 128)) || !job.videoId().equals(text(raw, "video_id", 128))
                    || nonnegative(raw, "analysis_generation") != job.generation()) {
                throw invalid("Model result identity must match this job, video and generation");
            }
            long duration = nonnegative(raw, "duration_ms");
            if (duration == 0) { throw invalid("Model result must have positive duration_ms"); }
            List<Product> products = new ArrayList<>();
            Set<String> productIds = new HashSet<>();
            for (JsonNode product : array(raw, "products", 1000)) {
                String id = identifier(product, "product_id");
                if (!productIds.add(id)) { throw invalid("Original product ids must be unique"); }
                products.add(new Product(id, name(product, "name"), "model"));
            }
            List<Utterance> utterances = new ArrayList<>();
            Set<String> utteranceIds = new HashSet<>();
            for (JsonNode utterance : array(raw, "utterances", 100000)) {
                String id = identifier(utterance, "utterance_id");
                if (!utteranceIds.add(id)) { throw invalid("Original utterance ids must be unique"); }
                long start = nonnegative(utterance, "start_ms");
                long end = nonnegative(utterance, "end_ms");
                bounds(start, end, duration);
                utterances.add(new Utterance(id, start, end, text(utterance, "text", 20000), identifier(utterance, "source_chunk_id")));
            }
            List<Segment> segments = new ArrayList<>();
            List<Prediction> predictions = new ArrayList<>();
            Set<String> segmentIds = new HashSet<>();
            for (JsonNode segment : array(raw, "segments", 10000)) {
                String id = identifier(segment, "segment_id");
                if (!segmentIds.add(id)) { throw invalid("Original segment ids must be unique"); }
                long start = nonnegative(segment, "start_ms");
                long end = nonnegative(segment, "end_ms");
                bounds(start, end, duration);
                String kind = text(segment, "content_kind", 32);
                if (!Set.of("product_talk", "non_product", "mixed", "uncertain").contains(kind)) { throw invalid("Unknown original content kind"); }
                String productId = nullableIdentifier(segment.path("identity"), "product_id");
                if (productId != null && !productIds.contains(productId)) { throw invalid("Unknown original product reference"); }
                segments.add(new Segment(id, start, end, productId, "product_talk".equals(kind), "model", "model", "model", id));
                List<String> evidence = strings(segment, "audio_evidence_utterance_ids", 100000, 128);
                if (!utteranceIds.containsAll(evidence)) { throw invalid("Unknown original utterance reference"); }
                predictions.add(new Prediction(id, kind, text(segment.path("identity"), "status", 32),
                        text(segment, "visual_status", 32), strings(segment, "review_reasons", 64, 128), evidence));
            }
            noOverlap(segments);
            return new Source(duration, List.copyOf(products), List.copyOf(segments), List.copyOf(utterances), List.copyOf(predictions));
        } catch (JobException invalidSource) {
            throw conflict("INVALID_REVIEW_SOURCE", "Stored model result has invalid identity, time bounds or references");
        }
    }

    private static void noOverlap(List<Segment> segments) {
        long previousEnd = -1;
        for (Segment segment : segments.stream().filter(Segment::enabled).sorted(Comparator.comparingLong(Segment::startMs).thenComparingLong(Segment::endMs)).toList()) {
            if (segment.startMs() < previousEnd) { throw invalid("Enabled segments must not overlap"); }
            previousEnd = segment.endMs();
        }
    }

    private static void bounds(long start, long end, long duration) {
        if (start >= end || end > duration) { throw invalid("Segment and utterance times must satisfy 0 <= start_ms < end_ms <= duration_ms"); }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) { throw invalid("Provide exactly the required editable fields"); }
        for (String field : node.propertyNames()) { if (!expected.contains(field)) { throw invalid("Unknown editable field"); } }
    }

    private static JsonNode array(JsonNode node, String field, int maximum) {
        JsonNode result = node.path(field);
        if (!result.isArray() || result.size() > maximum) { throw invalid(field + " must be an array within the supported size limit"); }
        return result;
    }

    private static long nonnegative(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) { throw invalid(field + " must be a nonnegative integer"); }
        return value.longValue();
    }

    private static String identifier(JsonNode node, String field) {
        String value = text(node, field, 128);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) { throw invalid(field + " must be a stable identifier"); }
        return value;
    }

    private static String nullableIdentifier(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) { throw invalid(field + " is required, using null for unassigned"); }
        return value.isNull() ? null : identifier(node, field);
    }

    private static String name(JsonNode node, String field) {
        String name = text(node, field, 160).strip();
        if (name.isEmpty() || name.codePoints().anyMatch(Character::isISOControl)) { throw invalid("Product name must contain printable nonblank text"); }
        return name;
    }

    private static String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.stringValue().length() > maximum) { throw invalid(field + " must be a string within the supported size limit"); }
        return value.stringValue();
    }

    private static List<String> strings(JsonNode node, String field, int countLimit, int lengthLimit) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(node, field, countLimit)) {
            if (!value.isString() || value.stringValue().length() > lengthLimit) { throw invalid("Invalid evidence or review reason"); }
            result.add(value.stringValue());
        }
        return List.copyOf(result);
    }

    private static String uuid(String id) {
        try {
            String normalized = UUID.fromString(id).toString();
            if (!normalized.equalsIgnoreCase(id)) { throw new IllegalArgumentException(); }
            return normalized;
        } catch (IllegalArgumentException | NullPointerException failure) { throw invalid("job id must be a UUID"); }
    }

    private static JobException invalid(String message) { return new JobException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW", message); }
    private static JobException conflict(String code, String message) { return new JobException(HttpStatus.CONFLICT, code, message); }
    private static JobException missing(String code, String message) { return new JobException(HttpStatus.NOT_FOUND, code, message); }
}
