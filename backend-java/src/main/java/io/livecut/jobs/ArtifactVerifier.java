package io.livecut.jobs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.livecut.captions.CaptionRenderSpec;
import io.livecut.clips.ClipRequestService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies immutable private references before they become durable business state. */
@Component
public class ArtifactVerifier {
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    static final int MAX_CHECKPOINT_BYTES = 4 * 1024 * 1024;
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    static final int MAX_ANALYSIS_BYTES = 8 * 1024 * 1024;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String ATTEMPT_PREFIX = "jobs/" + UUID_PATTERN + "/stages/" + UUID_PATTERN
            + "/attempts/[1-9][0-9]*/[A-Za-z0-9_-]{16,64}/";
    private static final Map<String, Set<String>> REQUIRED_KINDS = Map.of(
            "MEDIA", Set.of("media_info", "audio"), "ASR", Set.of("transcript"),
            "SEGMENT", Set.of("analysis_result"), "TIMELINE", Set.of("analysis_result"),
            "EXPORT_CLIP", Set.of("clip", "media_info"), "CAPTION_PROOFREAD", Set.of("caption_proofread"),"ANCHOR_CLIP",Set.of("clip_plan"),"AGENT_VISUAL",Set.of("agent_answer"),"AGENT_VISUAL_FOCUS",Set.of("agent_answer"),"AGENT_EVIDENCE",Set.of("agent_answer"));
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path configuredRoot;

    public ArtifactVerifier(@Value("${livecut.storage.root:../storage}") Path root) {
        if (root == null) { throw new IllegalArgumentException("A shared storage root is required"); }
        this.configuredRoot = root.toAbsolutePath().normalize();
    }

    public record ManifestIdentity(String jobId, String videoId, int generation, String stageId,
                                   String stageType, int attempt, String runToken, String sourceHash) {
        public ManifestIdentity {
            if (!validUuid(jobId) || !validUuid(videoId) || !validUuid(stageId)
                    || generation < 1 || attempt < 1 || !REQUIRED_KINDS.containsKey(stageType == null ? "" : stageType)
                    || runToken == null || !runToken.matches("[A-Za-z0-9_-]{16,64}")
                    || sourceHash == null || !sourceHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid expected artifact identity");
            }
        }

        public String attemptPrefix() {
            return "jobs/" + jobId + "/stages/" + stageId + "/attempts/" + attempt + "/" + runToken + "/";
        }
    }

    public record VerifiedArtifact(String kind, String key, String sha256, long sizeBytes) {}
    public record VerifiedManifest(List<VerifiedArtifact> artifacts) {
        public VerifiedManifest { artifacts = List.copyOf(artifacts); }
    }

    public record ExportIdentity(String exportId, String clipId, long approvedRevision, String snapshotHash,
                                 String segmentId, long startMs, long endMs, String encodingProfile,
                                 String stageInputHash, List<SourcePart> parts, String clipRequestId,
                                 JsonNode captionRender,String captionRenderHash) {
        public ExportIdentity(String exportId,String clipId,long approvedRevision,String snapshotHash,String segmentId,
                long startMs,long endMs,String encodingProfile,String stageInputHash,List<SourcePart> parts,String clipRequestId){
            this(exportId,clipId,approvedRevision,snapshotHash,segmentId,startMs,endMs,encodingProfile,stageInputHash,parts,clipRequestId,null,null);
        }
        public ExportIdentity(String exportId,String clipId,long approvedRevision,String snapshotHash,String segmentId,
                long startMs,long endMs,String encodingProfile,String stageInputHash) {
            this(exportId,clipId,approvedRevision,snapshotHash,segmentId,startMs,endMs,encodingProfile,stageInputHash,List.of(),null,null,null);
        }
    }
    public record SourcePart(@com.fasterxml.jackson.annotation.JsonProperty("start_ms") long startMs,
                             @com.fasterxml.jackson.annotation.JsonProperty("end_ms") long endMs) {}

    public VerifiedManifest verifyManifest(String key, String expectedHash, ManifestIdentity expected) {
        return verifyManifest(key, expectedHash, expected, null);
    }

    public VerifiedManifest verifyClipPlanManifest(String key,String hash,ManifestIdentity expected) {
        if(expected==null||!Set.of("SEGMENT","ANCHOR_CLIP").contains(expected.stageType()))throw invalidReference();
        return verifyManifest(key,hash,expected,null,"clip_plan");
    }

    public VerifiedManifest verifyAgentAnswerManifest(String key,String hash,ManifestIdentity expected) {
        if(expected==null||!Set.of("SEGMENT","AGENT_VISUAL","AGENT_VISUAL_FOCUS","AGENT_EVIDENCE").contains(expected.stageType()))throw invalidReference();
        return verifyManifest(key,hash,expected,null,"agent_answer");
    }
    public VerifiedManifest verifyCaptionProofreadManifest(String key,String hash,ManifestIdentity expected) {
        if(expected==null||!"CAPTION_PROOFREAD".equals(expected.stageType()))throw invalidReference();
        return verifyManifest(key,hash,expected,null,"caption_proofread");
    }

    public JsonNode readJsonArtifact(VerifiedArtifact artifact,ManifestIdentity expected) {
        try{return JSON.readTree(verifiedBytes(artifact.key(),artifact.sha256(),expected.attemptPrefix(),MAX_ANALYSIS_BYTES));}
        catch(JobException error){throw error;}catch(RuntimeException error){throw invalidAnalysis();}
    }

    /** Bounded binary reader; the caller still validates business metadata and JPEG type. */
    public byte[] readVisualFrame(VerifiedArtifact artifact,ManifestIdentity expected){
        if(expected==null||!Set.of("AGENT_VISUAL","AGENT_VISUAL_FOCUS","AGENT_EVIDENCE").contains(expected.stageType())||!"agent_visual_frame".equals(artifact.kind()))throw invalidReference();
        return verifiedBytes(artifact.key(),artifact.sha256(),expected.attemptPrefix()+"visual-frames/",1024*1024);
    }
    /** Bounded read-only recovery admission; caller binds prefix to an original succeeded ledger response. */
    public byte[] readReplayFile(String key,String prefix,int maximum){
        if(maximum<1||maximum>MAX_RESPONSE_BYTES)throw invalidReference();
        Path path=confinedFile(key,prefix);try(java.io.InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            if(Files.size(path)>maximum)throw tooLarge();byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum)throw tooLarge();if(!path.equals(confinedFile(key,prefix)))throw unsafeReference();return bytes;
        }catch(NoSuchFileException failure){throw notFound();}catch(IOException failure){throw unreadable();}
    }

    public VerifiedManifest verifyExportManifest(String key, String expectedHash, ManifestIdentity expected,
                                                  ExportIdentity export) {
        if (expected == null || !"EXPORT_CLIP".equals(expected.stageType()) || export == null) { throw invalidReference(); }
        VerifiedManifest verified = verifyManifest(key, expectedHash, expected, export);
        VerifiedArtifact clip = verified.artifacts().stream().filter(item -> item.kind().equals("clip")).findFirst().orElseThrow(ArtifactVerifier::invalidManifest);
        if (clip.sizeBytes() <= 0) { throw invalidManifest(); }
        VerifiedArtifact info = verified.artifacts().stream().filter(item -> item.kind().equals("media_info")).findFirst().orElseThrow(ArtifactVerifier::invalidManifest);
        JsonNode metadata;
        try { metadata = JSON.readTree(verifiedBytes(info.key(), info.sha256(), expected.attemptPrefix(), MAX_MANIFEST_BYTES)); }
        catch (JobException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalidManifest(); }
        // This is only a safety/type check of worker-reported metadata. Python
        // performs real ffprobe/decode validation; Java makes no quality claim.
        if (metadata == null || !metadata.isObject() || metadata.has("path")
                || !metadata.path("duration_ms").isIntegralNumber() || !metadata.path("duration_ms").canConvertToLong()
                || metadata.path("duration_ms").longValue() <= 0 || !text(metadata, "video_codec", "h264")) { throw invalidManifest(); }
        JsonNode audio = metadata.path("audio_codec");
        if (!audio.isNull() && !audio.isMissingNode() && !(audio.isString() && "aac".equals(audio.stringValue()))) { throw invalidManifest(); }
        JsonNode fps = metadata.path("frame_rate");
        double frameRate = fps.isNull() || fps.isMissingNode() ? 0 : fps.isNumber() ? fps.doubleValue() : Double.NaN;
        if (!Double.isFinite(frameRate) || frameRate < 0 || (!fps.isNull() && !fps.isMissingNode() && frameRate == 0)) { throw invalidManifest(); }
        double tolerance = frameRate > 0 ? Math.max(100, Math.ceil(2000 / frameRate)) : 100;
        long expectedDuration=export.parts().isEmpty()?export.endMs()-export.startMs():export.parts().stream().mapToLong(p->p.endMs()-p.startMs()).sum();
        if (Math.abs(metadata.path("duration_ms").longValue() - expectedDuration) > tolerance*Math.max(1,export.parts().size())) { throw invalidManifest(); }
        if(export.captionRender()!=null){
            JsonNode render=export.captionRender();try{CaptionRenderSpec.validate(render);}catch(RuntimeException invalid){throw invalidManifest();}
            if(!longInteger(metadata,"width",render.path("output").path("width").longValue())||!longInteger(metadata,"height",render.path("output").path("height").longValue()))throw invalidManifest();
            if(render.path("input_media").path("has_audio").booleanValue()){if(!text(metadata,"audio_codec","aac"))throw invalidManifest();}
            else if(!audio.isNull()&&!audio.isMissingNode())throw invalidManifest();
            var reference=verified.artifacts().stream().filter(a->a.kind().equals("caption_render_info")).findFirst().orElseThrow(ArtifactVerifier::invalidManifest);
            JsonNode renderInfo;try{renderInfo=JSON.readTree(verifiedBytes(reference.key(),reference.sha256(),expected.attemptPrefix(),MAX_MANIFEST_BYTES));CaptionRenderSpec.fields(renderInfo,Set.of("schema_version","caption_render_hash","caption_render"));}
            catch(JobException error){throw error;}catch(RuntimeException error){throw invalidManifest();}
            if(!integer(renderInfo,"schema_version",1)||!text(renderInfo,"caption_render_hash",export.captionRenderHash())||!ClipRequestService.canonicalJson(renderInfo.path("caption_render")).equals(ClipRequestService.canonicalJson(render)))throw invalidManifest();
        }
        return verified;
    }

    private VerifiedManifest verifyManifest(String key, String expectedHash, ManifestIdentity expected,
                                             ExportIdentity export) {
        return verifyManifest(key,expectedHash,expected,export,null);
    }

    private VerifiedManifest verifyManifest(String key,String expectedHash,ManifestIdentity expected,
                                             ExportIdentity export,String specialKind) {
        if (expected == null || !expected.attemptPrefix().concat("manifest.json").equals(key)) { throw invalidReference(); }
        byte[] bytes = verifiedBytes(key, expectedHash, expected.attemptPrefix(), MAX_MANIFEST_BYTES);
        JsonNode manifest;
        try { manifest = JSON.readTree(bytes); }
        catch (RuntimeException failure) { throw invalidManifest(); }
        if (manifest == null || !manifest.isObject() || !integer(manifest, "schema_version", 1)) { throw invalidManifest(); }
        if (!text(manifest, "job_id", expected.jobId())
                || !text(manifest, "video_id", expected.videoId())
                || !integer(manifest, "analysis_generation", expected.generation())
                || !text(manifest, "stage_id", expected.stageId())
                || !text(manifest, "stage_type", expected.stageType())
                || !integer(manifest, "attempt", expected.attempt())
                || !text(manifest, "run_token", expected.runToken())
                || !text(manifest, "input_sha256", expected.sourceHash())) {
            throw error(HttpStatus.CONFLICT, "ARTIFACT_IDENTITY_MISMATCH", "Artifact identity does not match this execution");
        }
        if ("EXPORT_CLIP".equals(expected.stageType())) {
            if (export == null || !text(manifest, "data_kind", export.captionRender()==null?"reviewed_export":"captioned_export") || !text(manifest, "review_status", "CONFIRMED")) { throw invalidManifest(); }
            if(export.captionRender()!=null){
                if(!CaptionRenderSpec.PROFILE.equals(export.encodingProfile())||!text(manifest,"render_mode","CAPTIONED")||!text(manifest,"caption_render_hash",export.captionRenderHash())
                    ||!export.captionRenderHash().equals(ClipRequestService.sha(ClipRequestService.canonicalJson(export.captionRender()))))throw invalidManifest();
            }else if(manifest.has("render_mode")||manifest.has("caption_render_hash"))throw invalidManifest();
            if (!text(manifest, "export_id", export.exportId()) || !text(manifest, "clip_id", export.clipId())
                    || !longInteger(manifest, "approved_revision", export.approvedRevision())
                    || !text(manifest, "snapshot_hash", export.snapshotHash()) || !text(manifest, "segment_id", export.segmentId())
                    || !longInteger(manifest, "start_ms", export.startMs()) || !longInteger(manifest, "end_ms", export.endMs())
                    || !text(manifest, "encoding_profile", export.encodingProfile())
                    || !text(manifest, "stage_input_hash", export.stageInputHash())) {
                throw error(HttpStatus.CONFLICT, "ARTIFACT_IDENTITY_MISMATCH", "Artifact identity does not match this frozen export");
            }
            if(!export.parts().isEmpty()){
                JsonNode parts=manifest.path("parts");
                if(export.clipRequestId()==null||!text(manifest,"clip_request_id",export.clipRequestId())||!parts.isArray()||parts.size()!=export.parts().size())throw invalidManifest();
                long duration=0,previous=-1;
                if(parts.size()>12)throw invalidManifest();
                for(int i=0;i<parts.size();i++){
                    SourcePart part=export.parts().get(i);
                    if(part.startMs()<0||part.endMs()<=part.startMs()||part.startMs()<previous
                            ||!longInteger(parts.get(i),"start_ms",part.startMs())||!longInteger(parts.get(i),"end_ms",part.endMs())
                            ||parts.get(i).propertyNames().size()!=2)throw invalidManifest();
                    previous=part.endMs();duration=Math.addExact(duration,part.endMs()-part.startMs());
                }
                if(!longInteger(manifest,"duration_ms",duration))throw invalidManifest();
            } else if(manifest.has("parts")||manifest.has("clip_request_id"))throw invalidManifest();
        } else if (!text(manifest, "data_kind", "model_result") || !text(manifest, "review_status", "DRAFT")) { throw invalidManifest(); }
        JsonNode artifacts = manifest.path("artifacts");
        if (!artifacts.isArray() || artifacts.isEmpty() || artifacts.size() > 64) { throw invalidManifest(); }
        Set<String> kinds = new HashSet<>();
        Set<String> keys = new HashSet<>();
        List<VerifiedArtifact> verified = new ArrayList<>();
        for (JsonNode artifact : artifacts) {
            if (!artifact.isObject() || !artifact.path("kind").isString()
                    || !artifact.path("key").isString() || !artifact.path("sha256").isString()) { throw invalidManifest(); }
            String kind = artifact.path("kind").stringValue();
            String artifactKey = artifact.path("key").stringValue();
            String artifactHash = artifact.path("sha256").stringValue();
            boolean repeatedVisual=Set.of("AGENT_VISUAL","AGENT_VISUAL_FOCUS","AGENT_EVIDENCE").contains(expected.stageType())&&"agent_answer".equals(specialKind)&&"agent_visual_frame".equals(kind);
            if (!kind.matches("[a-z][a-z0-9_]{0,63}") || !kinds.add(kind)&&!repeatedVisual || !keys.add(artifactKey)
                    || artifactKey.equals(key)) { throw invalidManifest(); }
            long size = verifyFile(artifactKey, artifactHash, expected.attemptPrefix(), repeatedVisual?1024*1024:Long.MAX_VALUE);
            verified.add(new VerifiedArtifact(kind, artifactKey, artifactHash, size));
        }
        if (!kinds.containsAll(specialKind==null?REQUIRED_KINDS.get(expected.stageType()):Set.of(specialKind))) { throw invalidManifest(); }
        if(export!=null&&export.captionRender()!=null&&!kinds.contains("caption_render_info"))throw invalidManifest();
        return new VerifiedManifest(verified);
    }

    /** Checkpoints are opaque provider/worker state, not a model-quality assertion. */
    public void verifyCheckpoint(String key, String expectedHash, ManifestIdentity expected) {
        if (expected == null) { throw invalidReference(); }
        verifyFile(key, expectedHash, expected.attemptPrefix(), MAX_CHECKPOINT_BYTES);
    }

    public Path verifiedArtifactPath(String key, String expectedHash, ManifestIdentity expected) {
        if (expected == null) { throw invalidReference(); }
        verifyFile(key, expectedHash, expected.attemptPrefix(), Long.MAX_VALUE);
        return confinedFile(key, expected.attemptPrefix());
    }

    /** A response may settle its original dispatched attempt after that lease is revoked. */
    public void verifyProviderResponse(String key, String expectedHash, String attemptPrefix) {
        if (attemptPrefix == null || !attemptPrefix.matches(ATTEMPT_PREFIX)) { throw invalidReference(); }
        verifyFile(key, expectedHash, attemptPrefix + "provider-responses/", MAX_RESPONSE_BYTES);
    }

    /** Internal reader only: callers must select a public DTO and bind result business fields. */
    public JsonNode readAnalysisResult(String manifestKey, String manifestHash, ManifestIdentity expected) {
        if (expected == null || !Set.of("SEGMENT", "TIMELINE").contains(expected.stageType())) { throw invalidReference(); }
        VerifiedManifest manifest = verifyManifest(manifestKey, manifestHash, expected);
        VerifiedArtifact result = manifest.artifacts().stream().filter(artifact -> artifact.kind().equals("analysis_result"))
                .findFirst().orElseThrow(ArtifactVerifier::invalidManifest);
        byte[] bytes = verifiedBytes(result.key(), result.sha256(), expected.attemptPrefix(), MAX_ANALYSIS_BYTES);
        JsonNode tree;
        try { tree = JSON.readTree(bytes); }
        catch (RuntimeException failure) { throw invalidAnalysis(); }
        if (tree == null || !tree.isObject()) { throw invalidAnalysis(); }
        return tree;
    }

    private byte[] verifiedBytes(String key, String expectedHash, String prefix, int maximum) {
        requireHash(expectedHash);
        Path path = confinedFile(key, prefix);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(path) > maximum) { throw tooLarge(); }
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) { throw tooLarge(); }
            if (!expectedHash.equals(HexFormat.of().formatHex(digest().digest(bytes)))) { throw hashMismatch(); }
            if (!path.equals(confinedFile(key, prefix))) { throw unsafeReference(); }
            return bytes;
        } catch (NoSuchFileException failure) { throw notFound(); }
        catch (IOException failure) { throw unreadable(); }
    }

    private long verifyFile(String key, String expectedHash, String prefix, long maximum) {
        requireHash(expectedHash);
        Path path = confinedFile(key, prefix);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(path) > maximum) { throw tooLarge(); }
            MessageDigest digest = digest();
            byte[] buffer = new byte[64 * 1024];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) { continue; }
                if (size > maximum - count) { throw tooLarge(); }
                size += count;
                digest.update(buffer, 0, count);
            }
            if (!expectedHash.equals(HexFormat.of().formatHex(digest.digest()))) { throw hashMismatch(); }
            if (!path.equals(confinedFile(key, prefix))) { throw unsafeReference(); }
            return size;
        } catch (NoSuchFileException failure) { throw notFound(); }
        catch (IOException failure) { throw unreadable(); }
    }

    private Path confinedFile(String key, String prefix) {
        if (key == null || key.length() > 512 || !key.matches("[A-Za-z0-9_./-]+")
                || prefix == null || !key.startsWith(prefix) || key.length() == prefix.length()) { throw invalidReference(); }
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) { throw invalidReference(); }
        }
        try {
            Path root = configuredRoot.toRealPath();
            Path lexical = root.resolve(key).normalize();
            Path allowed = root.resolve(prefix).normalize();
            if (!lexical.startsWith(allowed) || !lexical.startsWith(root)) { throw invalidReference(); }
            Path cursor = root;
            for (Path component : root.relativize(lexical)) {
                cursor = cursor.resolve(component);
                if (Files.isSymbolicLink(cursor)) { throw unsafeReference(); }
            }
            Path real = lexical.toRealPath();
            // The real target must also remain in the literal attempt directory.
            // Root-only containment would allow a link into another attempt.
            if (!real.startsWith(root) || !real.startsWith(allowed)) { throw unsafeReference(); }
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) { throw invalidReference(); }
            return real;
        } catch (NoSuchFileException failure) { throw notFound(); }
        catch (InvalidPathException failure) { throw invalidReference(); }
        catch (IOException failure) { throw unreadable(); }
    }

    private static boolean validUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException failure) { return false; }
    }
    private static boolean text(JsonNode node, String field, String expected) {
        JsonNode value = node.path(field);
        return value.isString() && expected.equals(value.stringValue());
    }
    private static boolean integer(JsonNode node, String field, int expected) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == expected;
    }
    private static boolean longInteger(JsonNode node, String field, long expected) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == expected;
    }
    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) { throw invalidReference(); }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
    }
    private static JobException invalidReference() { return error(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_REFERENCE", "Artifact must be a safe relative reference inside its execution directory"); }
    private static JobException unsafeReference() { return error(HttpStatus.BAD_REQUEST, "UNSAFE_ARTIFACT_REFERENCE", "Artifact reference crosses a storage boundary or symbolic link"); }
    private static JobException invalidManifest() { return error(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST", "Manifest structure, required artifacts or draft provenance is invalid"); }
    private static JobException invalidAnalysis() { return error(HttpStatus.BAD_REQUEST, "INVALID_ANALYSIS_RESULT", "Analysis result must be a valid JSON object"); }
    private static JobException tooLarge() { return error(HttpStatus.BAD_REQUEST, "ARTIFACT_TOO_LARGE", "Artifact exceeds the permitted size"); }
    private static JobException hashMismatch() { return error(HttpStatus.CONFLICT, "ARTIFACT_HASH_MISMATCH", "Stored artifact does not match its SHA-256 reference"); }
    private static JobException notFound() { return error(HttpStatus.CONFLICT, "ARTIFACT_NOT_FOUND", "Referenced artifact is not present in shared storage"); }
    private static JobException unreadable() { return error(HttpStatus.CONFLICT, "ARTIFACT_UNREADABLE", "Referenced artifact could not be read from shared storage"); }
    private static JobException error(HttpStatus status, String code, String message) { return new JobException(status, code, message); }
}
