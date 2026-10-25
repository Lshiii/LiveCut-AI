package io.livecut.uploads;

import static org.junit.jupiter.api.Assertions.*;

import io.livecut.video.VideoConfiguration;
import io.livecut.video.VideoStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises native file-lock ownership across storage instances and JVMs, without a database. */
class UploadStorageLockTests {
    @TempDir Path root;

    @Test
    void failedLocalContendersAndRepeatedCloseCannotReleaseAnotherOwnersNativeLock() throws Exception {
        var properties = new VideoConfiguration.StorageProperties(root, 1024);
        var videos = new VideoStorage(root, properties.maxVideoBytes());
        var storageA = new UploadStorage(properties, videos);
        var storageB = new UploadStorage(properties, videos);
        var storageC = new UploadStorage(properties, videos);
        String id = UUID.randomUUID().toString();
        Path guard = root.resolve("uploads/sessions/" + id + "/guard.lock");
        Path probe = writeProbe();

        var firstOwner = storageA.trySessionLock(id);
        assertNotNull(firstOwner);
        try (firstOwner) {
            assertLocalContenderBusy(storageB, id);
            // Some Unix file-lock implementations release a process's lock
            // when ANY descriptor to the same inode is closed. A separate JVM
            // detects that loss even if this JVM still reports an overlap.
            assertEquals("BUSY", probe(probe, guard));
        }

        try (var nextOwner = storageC.trySessionLock(id)) {
            assertNotNull(nextOwner, "Closing the first owner must permit a new owner");
            firstOwner.close();
            // A stale close must not remove the new owner's local registry
            // entry either: retry locally before checking the native lock.
            assertLocalContenderBusy(storageA, id);
            assertEquals("BUSY", probe(probe, guard));
        }
        assertEquals("ACQUIRED", probe(probe, guard), "The final owner must release the native lock");
    }

    private static void assertLocalContenderBusy(UploadStorage storage, String id) {
        try (var unexpected = storage.trySessionLock(id)) {
            assertNull(unexpected, "A different storage instance in this JVM must not acquire an owned guard");
        }
    }

    private Path writeProbe() throws Exception {
        Path source = root.resolve("LockProbe.java");
        Files.writeString(source, """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;

                class LockProbe {
                    public static void main(String[] args) throws Exception {
                        try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                             var lock = channel.tryLock()) {
                            System.out.println(lock == null ? "BUSY" : "ACQUIRED");
                        }
                    }
                }
                """);
        return source;
    }

    private String probe(Path source, Path guard) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path output = root.resolve("lock-probe-" + UUID.randomUUID() + ".log");
        Process process = new ProcessBuilder(java.toString(), "--source", "21", source.toString(), guard.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Independent JVM lock probe timed out");
            String diagnostic = Files.readString(output);
            assertEquals(0, process.exitValue(), diagnostic);
            List<String> results = diagnostic.lines().filter(line -> line.equals("BUSY") || line.equals("ACQUIRED")).toList();
            assertEquals(1, results.size(), diagnostic);
            return results.getFirst();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Independent lock probe could not be terminated");
            }
        }
    }
}
