package io.livecut.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.ObjectMapper;

/** Real local files and synthetic data only; these tests never call a model or a database. */
class ArtifactVerifierTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MIB = 1024 * 1024;

    @TempDir
    Path root;

    @Test
    void verifiesRequiredArtifactsForEverySupportedStage() throws Exception {
        for (String stageType : List.of("MEDIA", "ASR", "SEGMENT", "TIMELINE")) {
            var identity = identity(stageType);
            assertEquals("jobs/11111111-1111-4111-8111-111111111111/stages/33333333-3333-4333-8333-333333333333/"
                    + "attempts/3/44444444-4444-4444-8444-444444444444/", identity.attemptPrefix());
            var manifest = validManifest(identity);
            var reference = writeManifest(identity, manifest);

            assertDoesNotThrow(() -> {
                verifier().verifyManifest(reference.key(), reference.hash(), identity);
            }, stageType);
        }
    }

    @Test
    void allowsAdditionalUniqueKindsAndNestedArtifactPaths() throws Exception {
        var identity = identity("ASR");
        var manifest = validManifest(identity);
        Ref evidence = write(identity.attemptPrefix() + "evidence/raw-response.bin", new byte[] {0, 1, 2, -1});
        artifacts(manifest).add(artifact("raw_response", evidence));
        Ref reference = writeManifest(identity, manifest);

        assertDoesNotThrow(() -> {
            verifier().verifyManifest(reference.key(), reference.hash(), identity);
        });
    }

    @Test
    void rejectsChangedManifestBytes() throws Exception {
        var identity = identity("ASR");
        Ref reference = writeManifest(identity, validManifest(identity));
        Files.writeString(root.resolve(reference.key()), "\n", StandardOpenOption.APPEND);

        assertCode(HttpStatus.CONFLICT, "ARTIFACT_HASH_MISMATCH",
                () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
    }

    @Test
    void rejectsChangedArtifactBytesEvenWhenManifestHashMatches() throws Exception {
        var identity = identity("ASR");
        var manifest = validManifest(identity);
        Ref reference = writeManifest(identity, manifest);
        String transcriptKey = (String) artifacts(manifest).getFirst().get("key");
        Files.writeString(root.resolve(transcriptKey), "corrupted transcript", StandardCharsets.UTF_8);

        assertCode(HttpStatus.CONFLICT, "ARTIFACT_HASH_MISMATCH",
                () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
    }

    @Test
    void rejectsMissingManifestAndMissingReferencedArtifact() throws Exception {
        var identity = identity("ASR");
        assertCode(HttpStatus.CONFLICT, "ARTIFACT_NOT_FOUND",
                () -> verifier().verifyManifest(identity.attemptPrefix() + "manifest.json", hash(new byte[0]), identity));

        var manifest = validManifest(identity);
        Ref reference = writeManifest(identity, manifest);
        Files.delete(root.resolve((String) artifacts(manifest).getFirst().get("key")));

        assertCode(HttpStatus.CONFLICT, "ARTIFACT_NOT_FOUND",
                () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
    }

    @Test
    void rejectsMalformedJsonAndUnsupportedSchema() throws Exception {
        var identity = identity("ASR");
        for (String malformed : List.of("not-json", "[]", "null", "{\"schema_version\":", "{} {}",
                "{\"schema_version\":1,\"schema_version\":1}")) {
            Ref reference = write(identity.attemptPrefix() + "manifest.json", malformed.getBytes(StandardCharsets.UTF_8));
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST",
                    () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
        }
        for (Object schemaVersion : List.of(2, "1", 1.0)) {
            var manifest = validManifest(identity);
            manifest.put("schema_version", schemaVersion);
            Ref reference = writeManifest(identity, manifest);
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST",
                    () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
        }
    }

    @Test
    void bindsEveryIdentityFieldWithoutCoercingJsonTypes() throws Exception {
        var identity = identity("ASR");
        Map<String, Object> mismatches = new LinkedHashMap<>();
        mismatches.put("job_id", "another-job");
        mismatches.put("video_id", "another-video");
        mismatches.put("analysis_generation", identity.generation() + 1);
        mismatches.put("stage_id", "another-stage");
        mismatches.put("stage_type", "MEDIA");
        mismatches.put("attempt", identity.attempt() + 1);
        mismatches.put("run_token", "another-token");
        mismatches.put("input_sha256", "0".repeat(64));
        for (var mismatch : mismatches.entrySet()) {
            var manifest = validManifest(identity);
            manifest.put(mismatch.getKey(), mismatch.getValue());
            Ref reference = writeManifest(identity, manifest);
            assertCode(HttpStatus.CONFLICT, "ARTIFACT_IDENTITY_MISMATCH",
                    () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
        }
        for (var wrongType : Map.<String, Object>of("analysis_generation", "2", "attempt", 3.0, "job_id", 111).entrySet()) {
            var manifest = validManifest(identity);
            manifest.put(wrongType.getKey(), wrongType.getValue());
            Ref reference = writeManifest(identity, manifest);
            assertCode(HttpStatus.CONFLICT, "ARTIFACT_IDENTITY_MISMATCH",
                    () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
        }
        var missingField = validManifest(identity);
        missingField.remove("run_token");
        Ref reference = writeManifest(identity, missingField);
        assertCode(HttpStatus.CONFLICT, "ARTIFACT_IDENTITY_MISMATCH",
                () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
    }

    @Test
    void onlyAcceptsDraftModelResults() throws Exception {
        var identity = identity("ASR");
        for (var change : Map.of("data_kind", "fixture", "review_status", "CONFIRMED").entrySet()) {
            var manifest = validManifest(identity);
            manifest.put(change.getKey(), change.getValue());
            Ref reference = writeManifest(identity, manifest);
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST",
                    () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
        }
    }

    @Test
    void requiresNonemptyArtifactArrayAndEveryStageSpecificKind() throws Exception {
        for (String stageType : List.of("MEDIA", "ASR", "SEGMENT", "TIMELINE")) {
            var identity = identity(stageType);
            for (String required : requiredKinds(stageType)) {
                var manifest = validManifest(identity);
                artifacts(manifest).removeIf(item -> item.get("kind").equals(required));
                Ref reference = writeManifest(identity, manifest);
                assertCode(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST",
                        () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
            }
        }
        var identity = identity("ASR");
        for (Object invalidArtifacts : List.of(List.of(), "transcript", Map.of("kind", "transcript"))) {
            var manifest = validManifest(identity);
            manifest.put("artifacts", invalidArtifacts);
            Ref reference = writeManifest(identity, manifest);
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST",
                    () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
        }
    }

    @Test
    void rejectsDuplicateKindsAndInvalidKindNames() throws Exception {
        var identity = identity("ASR");
        var duplicate = validManifest(identity);
        artifacts(duplicate).add(new LinkedHashMap<>(artifacts(duplicate).getFirst()));
        Ref duplicateRef = writeManifest(identity, duplicate);
        assertCode(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST",
                () -> verifier().verifyManifest(duplicateRef.key(), duplicateRef.hash(), identity));

        for (String invalidKind : List.of("", "Transcript", "raw-response", "a".repeat(65))) {
            var manifest = validManifest(identity);
            Ref extra = write(identity.attemptPrefix() + "extra.bin", new byte[] {1});
            artifacts(manifest).add(artifact(invalidKind, extra));
            Ref reference = writeManifest(identity, manifest);
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST",
                    () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
        }
    }

    @Test
    void rejectsManifestAndArtifactReferencesFromAnotherAttempt() throws Exception {
        var identity = identity("ASR");
        String otherPrefix = identity.attemptPrefix().replace("/attempts/3/", "/attempts/2/");
        Ref otherManifest = write(otherPrefix + "manifest.json", JSON.writeValueAsBytes(validManifest(identity)));
        assertCode(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_REFERENCE",
                () -> verifier().verifyManifest(otherManifest.key(), otherManifest.hash(), identity));

        var manifest = validManifest(identity);
        Ref otherArtifact = write(otherPrefix + "transcript.json", "another attempt".getBytes(StandardCharsets.UTF_8));
        manifest.put("artifacts", List.of(artifact("transcript", otherArtifact)));
        Ref reference = writeManifest(identity, manifest);
        assertCode(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_REFERENCE",
                () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
    }

    @Test
    void rejectsTraversalAbsoluteBackslashAndAmbiguousPathReferences() {
        var identity = identity("ASR");
        String prefix = identity.attemptPrefix();
        for (String key : List.of("../checkpoint.json", "/tmp/checkpoint.json", "C:/checkpoint.json",
                prefix + "../checkpoint.json", prefix + "nested/../../checkpoint.json",
                prefix + "nested\\checkpoint.json", prefix + "/checkpoint.json", prefix + "./checkpoint.json")) {
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_REFERENCE",
                    () -> verifier().verifyCheckpoint(key, hash(new byte[0]), identity));
        }
    }

    @Test
    void rejectsNonSha256HashReferences() throws Exception {
        var identity = identity("ASR");
        Ref reference = write(identity.attemptPrefix() + "checkpoint.bin", new byte[] {0, 1});
        for (String invalidHash : List.of("", "g".repeat(64), "a".repeat(63), "a".repeat(65))) {
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_REFERENCE",
                    () -> verifier().verifyCheckpoint(reference.key(), invalidHash, identity));
        }
    }

    @Test
    void checksCheckpointAndProviderBytesWithoutAssumingJson() throws Exception {
        var identity = identity("ASR");
        byte[] raw = new byte[] {0, -1, 0, 17, 127};
        Ref checkpoint = write(identity.attemptPrefix() + "checkpoints/provider-task.bin", raw);
        Ref response = write(identity.attemptPrefix() + "provider-responses/request.json", raw);

        assertDoesNotThrow(() -> {
            verifier().verifyCheckpoint(checkpoint.key(), checkpoint.hash(), identity);
            verifier().verifyProviderResponse(response.key(), response.hash(), identity.attemptPrefix());
        });
    }

    @Test
    void checkpointsAndProviderResponsesRequireExistingMatchingBytes() throws Exception {
        var identity = identity("ASR");
        Ref checkpoint = write(identity.attemptPrefix() + "checkpoint.bin", new byte[] {1, 2});
        Ref response = write(identity.attemptPrefix() + "provider-responses/request.json", new byte[] {3, 4});
        assertCode(HttpStatus.CONFLICT, "ARTIFACT_HASH_MISMATCH",
                () -> verifier().verifyCheckpoint(checkpoint.key(), "0".repeat(64), identity));
        assertCode(HttpStatus.CONFLICT, "ARTIFACT_HASH_MISMATCH",
                () -> verifier().verifyProviderResponse(response.key(), "0".repeat(64), identity.attemptPrefix()));
        Files.delete(root.resolve(checkpoint.key()));
        Files.delete(root.resolve(response.key()));
        assertCode(HttpStatus.CONFLICT, "ARTIFACT_NOT_FOUND",
                () -> verifier().verifyCheckpoint(checkpoint.key(), checkpoint.hash(), identity));
        assertCode(HttpStatus.CONFLICT, "ARTIFACT_NOT_FOUND",
                () -> verifier().verifyProviderResponse(response.key(), response.hash(), identity.attemptPrefix()));
    }

    @Test
    void providerResponsesMustUseCurrentAttemptsProviderDirectory() throws Exception {
        var identity = identity("ASR");
        Ref misplaced = write(identity.attemptPrefix() + "request.json", new byte[] {1});
        assertCode(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_REFERENCE",
                () -> verifier().verifyProviderResponse(misplaced.key(), misplaced.hash(), identity.attemptPrefix()));
        String otherPrefix = identity.attemptPrefix().replace("/attempts/3/", "/attempts/2/");
        Ref stale = write(otherPrefix + "provider-responses/request.json", new byte[] {1});
        assertCode(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_REFERENCE",
                () -> verifier().verifyProviderResponse(stale.key(), stale.hash(), identity.attemptPrefix()));
    }

    @Test
    void rejectsOversizedManifestCheckpointAndProviderResponseUsingActualFileSizes() throws Exception {
        var identity = identity("ASR");
        Ref manifest = writeSparse(identity.attemptPrefix() + "manifest.json", MIB + 1);
        Ref checkpoint = writeSparse(identity.attemptPrefix() + "checkpoint.bin", 4 * MIB + 1);
        Ref response = writeSparse(identity.attemptPrefix() + "provider-responses/request.json", 16 * MIB + 1);

        assertCode(HttpStatus.BAD_REQUEST, "ARTIFACT_TOO_LARGE",
                () -> verifier().verifyManifest(manifest.key(), manifest.hash(), identity));
        assertCode(HttpStatus.BAD_REQUEST, "ARTIFACT_TOO_LARGE",
                () -> verifier().verifyCheckpoint(checkpoint.key(), checkpoint.hash(), identity));
        assertCode(HttpStatus.BAD_REQUEST, "ARTIFACT_TOO_LARGE",
                () -> verifier().verifyProviderResponse(response.key(), response.hash(), identity.attemptPrefix()));
    }

    @Test
    void rejectsSymbolicLinksEscapingStorageWithoutExposingLocalPaths(@TempDir Path externalRoot) throws Exception {
        var identity = identity("ASR");
        byte[] bytes = new byte[] {1, 2, 3};
        Path external = Files.write(externalRoot.resolve("private-checkpoint.bin"), bytes);
        String key = identity.attemptPrefix() + "checkpoint.bin";
        Path link = root.resolve(key);
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic link creation unavailable: " + exception.getClass().getSimpleName());
        }

        JobException error = assertCode(HttpStatus.BAD_REQUEST, "UNSAFE_ARTIFACT_REFERENCE",
                () -> verifier().verifyCheckpoint(key, hash(bytes), identity));
        assertFalse(error.getMessage().contains(externalRoot.toString()));
        assertArrayEquals(bytes, Files.readAllBytes(external));
    }

    @Test
    void rejectsSymbolicLinksIntoAnotherAttemptEvenWhenTargetIsInsideSharedRoot() throws Exception {
        var identity = identity("ASR");
        byte[] bytes = new byte[] {4, 5, 6};
        String otherPrefix = identity.attemptPrefix().replace("/attempts/3/", "/attempts/2/");
        Ref target = write(otherPrefix + "transcript.bin", bytes);
        String linkKey = identity.attemptPrefix() + "borrowed-transcript.bin";
        Path link = root.resolve(linkKey);
        Files.createDirectories(link.getParent());
        try { Files.createSymbolicLink(link, root.resolve(target.key())); }
        catch (IOException | UnsupportedOperationException failure) {
            Assumptions.abort("Symbolic link creation unavailable: " + failure.getClass().getSimpleName());
        }
        var manifest = validManifest(identity);
        manifest.put("artifacts", List.of(artifact("transcript", new Ref(linkKey, target.hash()))));
        Ref reference = writeManifest(identity, manifest);

        assertCode(HttpStatus.BAD_REQUEST, "UNSAFE_ARTIFACT_REFERENCE",
                () -> verifier().verifyManifest(reference.key(), reference.hash(), identity));
    }

    @Test
    void readsAnalysisJsonOnlyAfterRecheckingManifestAndArtifactBytes() throws Exception {
        var identity = identity("TIMELINE");
        var manifest = validManifest(identity);
        Ref result = write(identity.attemptPrefix() + "analysis-result.json",
                JSON.writeValueAsBytes(Map.of("job_id", identity.jobId(), "data_kind", "model_result", "products", List.of())));
        manifest.put("artifacts", List.of(artifact("analysis_result", result)));
        Ref reference = writeManifest(identity, manifest);

        var tree = verifier().readAnalysisResult(reference.key(), reference.hash(), identity);
        assertEquals(identity.jobId(), tree.path("job_id").asText());
        assertTrue(tree.path("products").isArray());
        Files.writeString(root.resolve(result.key()), "{}", StandardCharsets.UTF_8);
        assertCode(HttpStatus.CONFLICT, "ARTIFACT_HASH_MISMATCH",
                () -> verifier().readAnalysisResult(reference.key(), reference.hash(), identity));
    }

    @Test
    void analysisReaderRejectsNonObjectJsonAndOversizedResults() throws Exception {
        var identity = identity("SEGMENT");
        for (String invalid : List.of("[]", "null", "not-json", "{} {}")) {
            var manifest = validManifest(identity);
            Ref result = write(identity.attemptPrefix() + "analysis-result.json", invalid.getBytes(StandardCharsets.UTF_8));
            manifest.put("artifacts", List.of(artifact("analysis_result", result)));
            Ref reference = writeManifest(identity, manifest);
            assertCode(HttpStatus.BAD_REQUEST, "INVALID_ANALYSIS_RESULT",
                    () -> verifier().readAnalysisResult(reference.key(), reference.hash(), identity));
        }
        var manifest = validManifest(identity);
        Ref oversized = writeSparse(identity.attemptPrefix() + "analysis-result.json", 8 * MIB + 1);
        manifest.put("artifacts", List.of(artifact("analysis_result", oversized)));
        Ref reference = writeManifest(identity, manifest);
        assertCode(HttpStatus.BAD_REQUEST, "ARTIFACT_TOO_LARGE",
                () -> verifier().readAnalysisResult(reference.key(), reference.hash(), identity));
    }

    private ArtifactVerifier verifier() {
        return new ArtifactVerifier(root);
    }

    private static ArtifactVerifier.ManifestIdentity identity(String stageType) {
        return new ArtifactVerifier.ManifestIdentity(
                "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", 2,
                "33333333-3333-4333-8333-333333333333", stageType, 3,
                "44444444-4444-4444-8444-444444444444", hash("synthetic source video".getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> validManifest(ArtifactVerifier.ManifestIdentity identity) throws Exception {
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schema_version", 1);
        manifest.put("job_id", identity.jobId());
        manifest.put("video_id", identity.videoId());
        manifest.put("analysis_generation", identity.generation());
        manifest.put("stage_id", identity.stageId());
        manifest.put("stage_type", identity.stageType());
        manifest.put("attempt", identity.attempt());
        manifest.put("run_token", identity.runToken());
        manifest.put("input_sha256", identity.sourceHash());
        manifest.put("data_kind", "model_result");
        manifest.put("review_status", "DRAFT");
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (String kind : requiredKinds(identity.stageType())) {
            Ref file = write(identity.attemptPrefix() + kind + ".bin", ("synthetic " + kind).getBytes(StandardCharsets.UTF_8));
            artifacts.add(artifact(kind, file));
        }
        manifest.put("artifacts", artifacts);
        return manifest;
    }

    private static List<String> requiredKinds(String stageType) {
        return switch (stageType) {
            case "MEDIA" -> List.of("media_info", "audio");
            case "ASR" -> List.of("transcript");
            case "SEGMENT", "TIMELINE" -> List.of("analysis_result");
            default -> throw new IllegalArgumentException("Unexpected synthetic stage");
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> artifacts(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("artifacts");
    }

    private static Map<String, Object> artifact(String kind, Ref reference) {
        return new LinkedHashMap<>(Map.of("kind", kind, "key", reference.key(), "sha256", reference.hash()));
    }

    private Ref writeManifest(ArtifactVerifier.ManifestIdentity identity, Map<String, Object> manifest) throws Exception {
        return write(identity.attemptPrefix() + "manifest.json", JSON.writeValueAsBytes(manifest));
    }

    private Ref write(String key, byte[] bytes) throws Exception {
        Path path = root.resolve(key);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return new Ref(key, hash(bytes));
    }

    private Ref writeSparse(String key, long size) throws Exception {
        Path path = root.resolve(key);
        Files.createDirectories(path.getParent());
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return new Ref(key, HexFormat.of().formatHex(digest.digest()));
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private JobException assertCode(HttpStatus status, String code, Executable action) {
        JobException error = assertThrows(JobException.class, action);
        assertEquals(status, error.getStatus());
        assertEquals(code, error.getCode());
        assertNotNull(error.getMessage());
        assertFalse(error.getMessage().contains(root.toString()), "Error must not expose a local storage path");
        assertFalse(error.getMessage().contains(root.toString().replace('\\', '/')));
        return error;
    }

    private record Ref(String key, String hash) {}
}
