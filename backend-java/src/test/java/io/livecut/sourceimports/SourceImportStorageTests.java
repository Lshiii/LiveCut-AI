package io.livecut.sourceimports;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import io.livecut.jobs.JobException;
import io.livecut.video.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SourceImportStorageTests {
    @TempDir Path root;
    private SourceImportStorage storage;private String id,token,video;
    @BeforeEach void setup(){var properties=new VideoConfiguration.StorageProperties(root,1024);storage=new SourceImportStorage(properties,new VideoStorage(root,1024));id=UUID.randomUUID().toString();token=UUID.randomUUID().toString();video=UUID.randomUUID().toString();}
    @Test void copiesHashVerifiedFixedSourceAndPublishesOneImmutableTarget()throws Exception{
        byte[] bytes=mp4();source(bytes);var prepared=storage.prepare(id,1,token,video,hash(bytes),bytes.length,1024);
        assertFalse(Files.exists(root.resolve("videos/"+video+".mp4")));storage.publish(prepared);storage.discard(prepared);
        assertArrayEquals(bytes,Files.readAllBytes(root.resolve("videos/"+video+".mp4")));
        var replay=storage.prepare(id,1,token,video,hash(bytes),bytes.length,1024);assertTrue(replay.existing());storage.publish(replay);storage.discard(replay);
        assertArrayEquals(bytes,Files.readAllBytes(root.resolve("videos/"+video+".mp4")));
    }
    @Test void mismatchedHashLeavesNoJavaTemporary()throws Exception{
        source(mp4());code("SOURCE_IMPORT_CONTENT_INVALID",()->storage.prepare(id,1,token,video,"0".repeat(64),48,1024));
        try(var files=Files.list(root.resolve("videos"))){assertEquals(0,files.count());}
    }
    @Test void wrongSizeAndUnsupportedHeaderCannotPublish()throws Exception{
        source(mp4());code("SOURCE_IMPORT_CONTENT_INVALID",()->storage.prepare(id,1,token,video,hash(mp4()),47,1024));
        byte[] fake=new byte[48];source(fake);code("SOURCE_IMPORT_CONTENT_INVALID",()->storage.prepare(id,1,token,video,hash(fake),48,1024));
        assertFalse(Files.exists(root.resolve("videos/"+video+".mp4")));
    }
    @Test void changedPreparedBytesAreRejectedBeforeFinalPublication()throws Exception{
        byte[] bytes=mp4();source(bytes);var prepared=storage.prepare(id,1,token,video,hash(bytes),48,1024);
        Files.write(prepared.candidate(),new byte[49]);code("SOURCE_IMPORT_CONTENT_CHANGED",()->storage.publish(prepared));storage.discard(prepared);
        assertFalse(Files.exists(root.resolve("videos/"+video+".mp4")));
    }
    @Test void publishedBytesMustStillMatchBeforeACompletionReplay()throws Exception{
        byte[] bytes=mp4();source(bytes);var prepared=storage.prepare(id,1,token,video,hash(bytes),48,1024);storage.publish(prepared);
        Files.write(root.resolve("videos/"+video+".mp4"),new byte[48]);
        code("SOURCE_IMPORT_CONTENT_INVALID",()->storage.prepare(id,1,token,video,hash(bytes),48,1024));
    }
    @Test void symlinkInAnySourceParentIsRejected()throws Exception{
        Path imports=root.resolve("imports");Path external=Files.createDirectory(root.resolve("elsewhere"));
        try{Files.createSymbolicLink(imports,external);}catch(Exception unsupported){Assumptions.assumeTrue(false,"Symbolic links unavailable");return;}
        Path source=external.resolve(id+"/attempts/1/"+token+"/source.mp4");Files.createDirectories(source.getParent());Files.write(source,mp4());
        code("SOURCE_IMPORT_UNSAFE_PATH",()->storage.prepare(id,1,token,video,hash(mp4()),48,1024));
    }
    private void source(byte[] bytes)throws Exception{Path path=root.resolve("imports/"+id+"/attempts/1/"+token+"/source.mp4");Files.createDirectories(path.getParent());Files.write(path,bytes);}
    private static void code(String expected,org.junit.jupiter.api.function.Executable action){assertEquals(expected,assertThrows(JobException.class,action).getCode());}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static byte[] mp4(){return ByteBuffer.allocate(48).putInt(32).put("ftyp".getBytes(StandardCharsets.US_ASCII)).put("isom".getBytes(StandardCharsets.US_ASCII)).putInt(512).put("isomiso2avc1mp41".getBytes(StandardCharsets.US_ASCII)).putInt(16).put("free".getBytes(StandardCharsets.US_ASCII)).putLong(123456789).array();}
}
