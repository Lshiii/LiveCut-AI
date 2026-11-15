package io.livecut.sourceimports;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import io.livecut.jobs.JobException;
import io.livecut.video.VideoConfiguration;
import io.livecut.video.VideoStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Copy and hash outside business locks; only the final atomic move holds a DB row lock. */
@Component
public class SourceImportStorage {
    private final Path root;
    private final VideoStorage videos;
    public record Stamp(long size, FileTime modified, Object fileKey) {}
    public record Prepared(Path candidate, Path target, boolean existing, Stamp stamp) {}
    public SourceImportStorage(VideoConfiguration.StorageProperties properties,VideoStorage videos) {
        this.videos=videos;
        try { root=properties.root().toRealPath(); }
        catch(IOException failure) { throw unavailable(); }
    }
    public Prepared prepare(String id,int attempt,String token,String videoId,String hash,long size,long maximum) {
        Path target=videos.resolve("videos/"+videoId+".mp4");
        Path temporary=null;boolean keep=false;
        try {
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                checkFile(target,hash,size,maximum,null);
                return new Prepared(target,target,true,stamp(target));
            }
            Path source=checked(root.resolve("imports/"+id+"/attempts/"+attempt+"/"+token+"/source.mp4"));
            temporary=Files.createTempFile(target.getParent(),".import-",".tmp");
            checkFile(source,hash,size,maximum,temporary);
            var prepared=new Prepared(temporary,target,false,stamp(temporary));keep=true;return prepared;
        } catch(IOException failure) { throw unavailable(); }
        finally {
            // Ownership transfers to Prepared only on success.
            if(temporary!=null && !keep)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}
        }
    }
    public void publish(Prepared prepared) {
        try {
            Path candidate=checked(prepared.candidate());
            if(!stamp(candidate).equals(prepared.stamp())) throw conflict("SOURCE_IMPORT_CONTENT_CHANGED");
            Path target=videos.resolve("videos/"+prepared.target().getFileName());
            if(!target.equals(prepared.target())) throw unavailable();
            if(prepared.existing()) {
                if(!candidate.equals(target)) throw unavailable();
                return;
            }
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw conflict("SOURCE_IMPORT_CONTENT_CHANGED");
            Files.move(candidate,target,StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException failure) { throw unavailable(); }
    }
    public void discard(Prepared prepared) {
        if(prepared==null || prepared.existing()) return;
        try {
            Path candidate=prepared.candidate();
            if(candidate.getParent().equals(prepared.target().getParent()) && candidate.getFileName().toString().startsWith(".import-")) Files.deleteIfExists(candidate);
        } catch(IOException ignored) { /* An unregistered private temporary may be reconciled later. */ }
    }
    private void checkFile(Path path,String expected,long expectedSize,long maximum,Path copy) throws IOException {
        path=checked(path);
        if(expectedSize<1 || expectedSize>maximum || Files.size(path)!=expectedSize) throw conflict("SOURCE_IMPORT_CONTENT_INVALID");
        MessageDigest digest=sha256();byte[] prefix=new byte[64];int prefixLength=0;long total=0;
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);
            FileChannel channel=copy==null?null:FileChannel.open(copy,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            var output=channel==null?java.io.OutputStream.nullOutputStream():Channels.newOutputStream(channel);
            byte[] buffer=new byte[64*1024];int count;
            while((count=input.read(buffer))!=-1) {
                total+=count;
                if(total>expectedSize || total>maximum) throw conflict("SOURCE_IMPORT_CONTENT_INVALID");
                int length=Math.min(count,prefix.length-prefixLength);
                if(length>0){System.arraycopy(buffer,0,prefix,prefixLength,length);prefixLength+=length;}
                digest.update(buffer,0,count);output.write(buffer,0,count);
            }
            if(total!=expectedSize || !HexFormat.of().formatHex(digest.digest()).equals(expected)) throw conflict("SOURCE_IMPORT_CONTENT_INVALID");
            try { VideoStorage.validateHeader(prefix,prefixLength,total); }
            catch(RuntimeException badHeader) { throw conflict("SOURCE_IMPORT_CONTENT_INVALID"); }
            if(channel!=null) channel.force(true);
        }
    }
    private Path checked(Path path) throws IOException {
        Path normalized=path.toAbsolutePath().normalize();
        if(!normalized.startsWith(root) || normalized.equals(root)) throw unavailable();
        Path current=root;
        for(Path part:root.relativize(normalized)) {
            current=current.resolve(part);
            if(Files.isSymbolicLink(current)) throw conflict("SOURCE_IMPORT_UNSAFE_PATH");
            if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS)) throw conflict("SOURCE_IMPORT_CONTENT_MISSING");
            if(!current.toRealPath().startsWith(root)) throw conflict("SOURCE_IMPORT_UNSAFE_PATH");
        }
        if(!Files.isRegularFile(normalized,LinkOption.NOFOLLOW_LINKS)) throw conflict("SOURCE_IMPORT_CONTENT_INVALID");
        return normalized;
    }
    private static Stamp stamp(Path file) throws IOException {
        var value=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        return new Stamp(value.size(),value.lastModifiedTime(),value.fileKey());
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
    private static JobException unavailable(){return new JobException(HttpStatus.SERVICE_UNAVAILABLE,"SOURCE_IMPORT_STORAGE_UNAVAILABLE","Import storage is temporarily unavailable");}
    private static JobException conflict(String code){return new JobException(HttpStatus.CONFLICT,code,"Imported media failed source validation");}
}
