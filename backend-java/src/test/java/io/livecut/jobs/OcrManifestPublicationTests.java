package io.livecut.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Manifest publication only: tiny synthetic marker files, no OCR, model, database or Spring context. */
class OcrManifestPublicationTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;
    private record Fixture(ArtifactVerifier.ManifestIdentity identity, Map<String,Object> manifest, List<Map<String,Object>> files) {}
    private record Ref(String key, String hash) {}

    @Test void acceptsTheActual120FrameTopologyWithAndWithoutTraceAndPreservesOriginalBytes() throws Exception {
        for (boolean trace : new boolean[]{false, true}) {
            Fixture fixture = ocr(120, trace); Ref reference = seal(fixture); byte[] original = Files.readAllBytes(root.resolve(reference.key()));
            var verified = verify(fixture, reference); assertEquals(trace ? 126 : 125, verified.artifacts().size());
            assertArrayEquals(original, Files.readAllBytes(root.resolve(reference.key())));
            Collections.reverse(fixture.files()); reference = seal(fixture); assertEquals(trace ? 126 : 125, verify(fixture, reference).artifacts().size());
        }
    }
    @Test void crossesThe64MemberBoundaryOnlyForTheCompleteOcrCohort() throws Exception {
        for (int frames : new int[]{58, 59, 60}) {
            Fixture fixture = ocr(frames, true); assertEquals(frames + 6, verify(fixture, seal(fixture)).artifacts().size());
        }
        Fixture legacy = generic("SEGMENT", List.of("analysis_result"), 64); Ref before = seal(legacy);
        assertEquals(64, verify(legacy, before).artifacts().size()); assertEquals(before.hash(), hash(Files.readAllBytes(root.resolve(before.key()))));
        Fixture excessive = generic("SEGMENT", List.of("analysis_result"), 65); invalid(excessive);
    }
    @Test void retains64ForOtherStagesAndForClipAndAgentSpecialKinds() throws Exception {
        for (String type : List.of("MEDIA", "ASR", "TIMELINE", "CAPTION_PROOFREAD", "AGENT_VISUAL", "AGENT_VISUAL_FOCUS", "AGENT_EVIDENCE")) {
            List<String> required = switch (type) { case "MEDIA" -> List.of("media_info", "audio"); case "ASR" -> List.of("transcript"); case "TIMELINE" -> List.of("analysis_result"); case "CAPTION_PROOFREAD" -> List.of("caption_proofread"); default -> List.of("agent_answer"); };
            Fixture small = generic(type, required, 64); assertEquals(64, verify(small, seal(small)).artifacts().size()); invalid(generic(type, required, 65));
        }
        for (String kind : List.of("clip_plan", "agent_answer")) {
            Fixture small = generic("SEGMENT", List.of(kind), 64); Ref reference = seal(small);
            assertEquals(64, special(small, reference, kind).artifacts().size());
            Fixture large = generic("SEGMENT", List.of(kind), 65); Ref oversized = seal(large); code("INVALID_MANIFEST", () -> special(large, oversized, kind));
        }
    }
    @Test void refusesOversizedGapsDuplicateFramesOutOfRangeAndUnrelatedKinds() throws Exception {
        Fixture gap = ocr(120, true); gap.files().removeIf(file -> file.get("kind").equals("ocr_frame_000060")); invalid(gap);
        Fixture duplicate = ocr(120, true); duplicate.files().set(duplicate.files().size() - 1, duplicate.files().get(5)); invalid(duplicate);
        Fixture outOfRange = ocr(121, false); assertEquals(126, outOfRange.files().size()); invalid(outOfRange);
        Fixture tooMany = ocr(121, true); assertEquals(127, tooMany.files().size()); invalid(tooMany);
        Fixture extra = ocr(119, false); extra.files().add(file(extra.identity(), "unrelated", "extra.bin")); invalid(extra);
        Fixture zero = ocr(120, true); zero.files().get(5).put("kind", "ocr_frame_000000"); invalid(zero);
    }
    @Test void requiresAllFixedFilesAndExactAttemptRelativeLocationsForTheNewCohort() throws Exception {
        for (String kind : List.of("analysis_result", "ocr_cache_provenance", "ocr_observations", "ocr_frames", "ocr_state")) {
            Fixture missing = ocr(120, true); missing.files().removeIf(file -> file.get("kind").equals(kind)); invalid(missing);
            Fixture renamed = ocr(120, true); renamed.files().stream().filter(file -> file.get("kind").equals(kind)).findFirst().orElseThrow().put("key", renamed.identity().attemptPrefix() + "other.json"); invalid(renamed);
        }
        Fixture framePath = ocr(120, true); framePath.files().get(5).put("key", framePath.identity().attemptPrefix() + "ocr/frames/../ocr_000001.jpg"); invalid(framePath);
        Fixture tracePath = ocr(120, true); tracePath.files().getLast().put("key", tracePath.identity().attemptPrefix() + "elsewhere/trace.json"); invalid(tracePath);
        Fixture extraField = ocr(120, true); extraField.files().get(0).put("unrecognized", true); invalid(extraField);
    }
    @Test void stillVerifiesEveryFileHashIncludingTheLastOcrFrameAndMetadata() throws Exception {
        for (String kind : List.of("ocr_frame_000120", "ocr_state")) {
            Fixture fixture = ocr(120, true); Ref reference = seal(fixture); assertEquals(126, verify(fixture, reference).artifacts().size());
            String key = (String) fixture.files().stream().filter(file -> file.get("kind").equals(kind)).findFirst().orElseThrow().get("key"); Files.writeString(root.resolve(key), "changed");
            code("ARTIFACT_HASH_MISMATCH", () -> verify(fixture, reference));
        }
        Fixture wrongSource = ocr(120, true); wrongSource.manifest().put("input_sha256", "b".repeat(64)); Ref reference = seal(wrongSource); code("ARTIFACT_IDENTITY_MISMATCH", () -> verify(wrongSource, reference));
    }
    @Test void stillRejectsMissingFilesInAnOtherwiseCompleteLargeCohort() throws Exception {
        Fixture fixture = ocr(120, true); Ref reference = seal(fixture); String key = (String) fixture.files().get(5).get("key");
        Files.delete(root.resolve(key)); code("ARTIFACT_NOT_FOUND", () -> verify(fixture, reference));
    }
    @Test void stillRejectsLinkedFilesInAnOtherwiseCompleteLargeCohort() throws Exception {
        Fixture fixture = ocr(120, true); Ref reference = seal(fixture); String key = (String) fixture.files().get(5).get("key");
        Files.delete(root.resolve(key));
        Path external = root.resolve("external.bin"); Files.writeString(external, "external synthetic marker");
        try {
            Files.createSymbolicLink(root.resolve(key), external);
        } catch (IOException | UnsupportedOperationException failure) {
            Assumptions.abort("Symbolic link creation unavailable: " + failure.getClass().getSimpleName());
        }
        code("UNSAFE_ARTIFACT_REFERENCE", () -> verify(fixture, reference));
    }
    @Test void retainsTheExistingManifestByteLimit() throws Exception {
        Fixture fixture = ocr(120, true); fixture.manifest().put("oversized_metadata", "x".repeat(1024 * 1024)); Ref reference = seal(fixture);
        code("ARTIFACT_TOO_LARGE", () -> verify(fixture, reference));
    }
    @Test void matchesAllTwentySharedCrossLanguageFixturesUsingTheirActualMarkerBytes() throws Exception {
        var fixture = JSON.readTree(Files.readAllBytes(Path.of("../fixtures/ocr-segment-artifact-capacity.synthetic.json")));
        assertEquals(20, fixture.path("cases").size());
        for (var item : fixture.path("cases")) {
            String label = item.path("case_id").asText(); assertTrue(label.matches("[A-Za-z0-9_-]+"));
            var manifest = item.path("manifest");
            var identity = new ArtifactVerifier.ManifestIdentity(manifest.path("job_id").asText(), manifest.path("video_id").asText(), manifest.path("analysis_generation").intValue(), manifest.path("stage_id").asText(), manifest.path("stage_type").asText(), manifest.path("attempt").intValue(), manifest.path("run_token").asText(), fixture.path("source_sha256").asText());
            Path storage = root.resolve(label); Files.createDirectories(storage.resolve(identity.attemptPrefix()));
            for (var artifact : manifest.path("artifacts")) {
                String key = artifact.path("key").asText();
                if (!key.startsWith(identity.attemptPrefix()) || key.contains("\\") || List.of(key.split("/", -1)).stream().anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) continue;
                Path file = storage.resolve(key); assertTrue(file.normalize().startsWith(storage)); Files.createDirectories(file.getParent());
                Files.write(file, ("synthetic-artifact:" + artifact.path("kind").asText()).getBytes(StandardCharsets.UTF_8));
            }
            byte[] bytes = JSON.writeValueAsBytes(manifest); String key = identity.attemptPrefix() + "manifest.json"; Files.write(storage.resolve(key), bytes);
            var verifier = new ArtifactVerifier(storage);
            if (item.path("expected_valid").booleanValue()) assertEquals(manifest.path("artifacts").size(), verifier.verifyManifest(key, hash(bytes), identity).artifacts().size(), label);
            else assertEquals("INVALID_MANIFEST", assertThrows(JobException.class, () -> verifier.verifyManifest(key, hash(bytes), identity), label).getCode(), label);
        }
    }

    private Fixture ocr(int frames, boolean trace) throws Exception {
        Fixture fixture = fixture("SEGMENT"); var identity = fixture.identity();
        for (var item : Map.of("analysis_result", "analysis-result.json", "ocr_cache_provenance", "ocr/cache-provenance.json", "ocr_observations", "ocr/observations.json", "ocr_frames", "ocr/frames.json", "ocr_state", "ocr/ocr-state.json").entrySet()) fixture.files().add(file(identity, item.getKey(), item.getValue()));
        for (int frame = 1; frame <= frames; frame++) { String digits = String.format(Locale.ROOT, "%06d", frame); fixture.files().add(file(identity, "ocr_frame_" + digits, "ocr/frames/ocr_" + digits + ".jpg")); }
        if (trace) fixture.files().add(file(identity, "text_execution_trace", "text-execution-trace.json")); return fixture;
    }
    private Fixture generic(String stage, List<String> required, int count) throws Exception {
        Fixture fixture = fixture(stage); for (String kind : required) fixture.files().add(file(fixture.identity(), kind, "legacy/" + kind + ".bin"));
        for (int number = fixture.files().size(); number < count; number++) fixture.files().add(file(fixture.identity(), "legacy_" + number, "legacy/" + number + ".bin")); return fixture;
    }
    private Fixture fixture(String stage) {
        var identity = new ArtifactVerifier.ManifestIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), stage, 1, UUID.randomUUID().toString(), "a".repeat(64));
        var manifest = new LinkedHashMap<String,Object>(); manifest.put("schema_version", 1); manifest.put("job_id", identity.jobId()); manifest.put("video_id", identity.videoId()); manifest.put("analysis_generation", 1); manifest.put("stage_id", identity.stageId()); manifest.put("stage_type", stage); manifest.put("attempt", 1); manifest.put("run_token", identity.runToken()); manifest.put("input_sha256", identity.sourceHash()); manifest.put("data_kind", "model_result"); manifest.put("review_status", "DRAFT");
        var files = new ArrayList<Map<String,Object>>(); manifest.put("artifacts", files); return new Fixture(identity, manifest, files);
    }
    private Map<String,Object> file(ArtifactVerifier.ManifestIdentity identity, String kind, String suffix) throws Exception {
        String key = identity.attemptPrefix() + suffix; Path path = root.resolve(key); Files.createDirectories(path.getParent()); byte[] bytes = ("synthetic marker: " + kind).getBytes(StandardCharsets.UTF_8); Files.write(path, bytes);
        return new LinkedHashMap<>(Map.of("kind", kind, "key", key, "sha256", hash(bytes)));
    }
    private Ref seal(Fixture fixture) throws Exception {
        String key = fixture.identity().attemptPrefix() + "manifest.json"; byte[] bytes = JSON.writeValueAsBytes(fixture.manifest()); Files.write(root.resolve(key), bytes); return new Ref(key, hash(bytes));
    }
    private ArtifactVerifier.VerifiedManifest verify(Fixture fixture, Ref reference) { return new ArtifactVerifier(root).verifyManifest(reference.key(), reference.hash(), fixture.identity()); }
    private ArtifactVerifier.VerifiedManifest special(Fixture fixture, Ref reference, String kind) { var verifier = new ArtifactVerifier(root); return kind.equals("clip_plan") ? verifier.verifyClipPlanManifest(reference.key(), reference.hash(), fixture.identity()) : verifier.verifyAgentAnswerManifest(reference.key(), reference.hash(), fixture.identity()); }
    private void invalid(Fixture fixture) throws Exception { Ref reference = seal(fixture); code("INVALID_MANIFEST", () -> verify(fixture, reference)); }
    private static void code(String expected, Executable action) { assertEquals(expected, assertThrows(JobException.class, action).getCode()); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
