package io.livecut.captions;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Bounded content-addressed inputs; creation never modifies source media or captions. */
@Component
public class CaptionRenderAssets {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Path root,font;
    public CaptionRenderAssets(@Value("${livecut.storage.root:../storage}") Path root,
        @Value("${livecut.captions.render-font-path:data/fonts/noto-sans-cjk-sc-2.004/NotoSansCJKsc-Regular.otf}") Path font){this.root=root.toAbsolutePath().normalize();this.font=font.toAbsolutePath().normalize();}
    public synchronized Map<String,Object> font(){
        try{
            if(!Files.isRegularFile(font)||Files.size(font)!=CaptionRenderSpec.FONT_SIZE)throw unavailable();
            byte[] bytes;try(var input=Files.newInputStream(font)){bytes=input.readNBytes((int)CaptionRenderSpec.FONT_SIZE+1);}if(bytes.length!=CaptionRenderSpec.FONT_SIZE||!hash(bytes).equals(CaptionRenderSpec.FONT_HASH))throw unavailable();
            String key="caption-render/fonts/"+CaptionRenderSpec.FONT_HASH+".otf";publish(key,bytes,CaptionRenderSpec.FONT_HASH);
            return Map.of("profile_id",CaptionRenderSpec.FONT_PROFILE,"family",CaptionRenderSpec.FONT_FAMILY,"key",key,"sha256",CaptionRenderSpec.FONT_HASH,"size_bytes",CaptionRenderSpec.FONT_SIZE);
        }catch(IOException error){throw unavailable();}
    }
    public synchronized Map<String,Object> cues(CaptionModels.Snapshot snapshot){
        if(snapshot.cues().isEmpty())throw new JobException(HttpStatus.BAD_REQUEST,"CAPTION_RENDER_EMPTY","Select a nonempty confirmed caption revision");
        List<Map<String,Object>> cues=new ArrayList<>();long previous=0,total=0;
        try{for(var cue:snapshot.cues()){
            if(cues.size()>=CaptionMapper.MAX_CUES||cue.startMs()<previous||cue.endMs()<=cue.startMs()||cue.endMs()>snapshot.source().durationMs())throw new IllegalArgumentException();
            if(cue.endMs()/10<=cue.startMs()/10)throw new JobException(HttpStatus.BAD_REQUEST,"CAPTION_RENDER_TIMING_UNSUPPORTED","A caption becomes empty at the supported subtitle timing precision");
            previous=cue.endMs();total+=CaptionMapper.textBytes(cue.text(),CaptionMapper.MAX_TEXT_CHARACTERS_PER_CUE);
            if(cue.text().codePoints().anyMatch(code->code>=127&&code<=159))throw new IllegalArgumentException();
            if(total>CaptionMapper.MAX_TOTAL_TEXT_BYTES)throw new IllegalArgumentException();
            cues.add(new TreeMap<>(Map.of("id",cue.id(),"start_ms",cue.startMs(),"end_ms",cue.endMs(),"text",cue.text())));
        }}catch(IllegalArgumentException error){throw new JobException(HttpStatus.BAD_REQUEST,"CAPTION_RENDER_INVALID_INPUT","Caption text or timing is invalid");}
        byte[] bytes=ClipRequestService.canonicalJson(JSON.valueToTree(Map.of("schema_version",1,"duration_ms",snapshot.source().durationMs(),"cues",cues))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>8*1024*1024)throw new JobException(HttpStatus.BAD_REQUEST,"CAPTION_RENDER_INVALID_INPUT","Caption input exceeds the file size limit");
        String sha=hash(bytes),key="caption-render/cues/"+sha+".json";
        try{publish(key,bytes,sha);}catch(IOException error){throw new JobException(HttpStatus.CONFLICT,"CAPTION_RENDER_SOURCE_UNAVAILABLE","Caption render inputs could not be stored");}
        return Map.of("revision",snapshot.revision(),"content_hash",snapshot.contentHash(),"cues_key",key,"cues_sha256",sha,"duration_ms",snapshot.source().durationMs());
    }
    private void publish(String key,byte[] bytes,String expected)throws IOException{
        Files.createDirectories(root);Path base=root.toRealPath(),target=base.resolve(key).normalize();if(!target.startsWith(base))throw new IOException();
        Files.createDirectories(target.getParent());if(!target.getParent().toRealPath().startsWith(base))throw new IOException();
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){verify(target,expected,bytes.length);return;}
        Path temporary=Files.createTempFile(target.getParent(),".render-",".tmp");
        try{
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);}
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(FileAlreadyExistsException exists){verify(target,expected,bytes.length);}
            catch(AtomicMoveNotSupportedException unsupported){try{Files.move(temporary,target);}catch(FileAlreadyExistsException exists){verify(target,expected,bytes.length);}}
            verify(target,expected,bytes.length);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static void verify(Path path,String expected,long size)throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=size)throw new IOException();
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(Math.toIntExact(size)+1);if(bytes.length!=size||!hash(bytes).equals(expected))throw new IOException();}
    }
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static JobException unavailable(){return new JobException(HttpStatus.SERVICE_UNAVAILABLE,"CAPTION_RENDER_FONT_UNAVAILABLE","The configured fixed subtitle font is unavailable or failed integrity validation");}
}
