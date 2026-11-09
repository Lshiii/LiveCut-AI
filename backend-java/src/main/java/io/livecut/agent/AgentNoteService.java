package io.livecut.agent;

import io.livecut.jobs.JobException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Prepare a complete bounded note from detached verified bytes before HTTP delivery. */
@Service
public class AgentNoteService {
    static final int MAX_TEXT_BYTES=2*1024*1024,MAX_ZIP_BYTES=24*1024*1024;
    private final AgentService agents;
    private final Semaphore slots=new Semaphore(2);
    public AgentNoteService(AgentService agents){this.agents=agents;}

    public AgentNoteModels.Preview preview(String id){
        return prepare(()->{
            var snapshot=agents.noteSnapshot(id);var document=AgentNoteRenderer.render(snapshot);check(document);
            var result=snapshot.result();Map<String,byte[]> images=snapshot.images();checkImages(images);
            return new AgentNoteModels.Preview(1,snapshot.turnId(),snapshot.sessionId(),snapshot.jobId(),snapshot.videoId(),snapshot.sourceHash(),
                snapshot.transcriptHash(),snapshot.resultHash(),snapshot.sourceFilename(),result.path("answer_scope").asText(),
                result.path("citations").size(),images.size(),document.markdown(),filename(snapshot));
        });
    }
    public AgentNoteModels.Download download(String id){
        return prepare(()->{
            var snapshot=agents.noteSnapshot(id);var document=AgentNoteRenderer.render(snapshot);
            Map<String,byte[]> images=snapshot.images();
            return new AgentNoteModels.Download(snapshot.turnId(),snapshot.resultHash(),filename(snapshot),archive(document,images,MAX_ZIP_BYTES));
        });
    }
    private <T>T prepare(Supplier<T> work){
        if(!slots.tryAcquire())throw new JobException(HttpStatus.TOO_MANY_REQUESTS,"AGENT_NOTE_BUSY","Two study notes are already being prepared; retry shortly");
        try{return work.get();}finally{slots.release();}
    }
    private static String filename(AgentNoteModels.Snapshot snapshot){return "video-note-"+snapshot.turnId()+".zip";}
    private static void check(AgentNoteModels.Document document){
        if(document==null||document.markdown()==null||document.packageMarkdown()==null)throw invalid();
        byte[] source=document.sourceJson();if(source==null)throw invalid();
        if(document.markdown().getBytes(StandardCharsets.UTF_8).length>MAX_TEXT_BYTES
                ||document.packageMarkdown().getBytes(StandardCharsets.UTF_8).length>MAX_TEXT_BYTES
                ||source.length>MAX_TEXT_BYTES)throw tooLarge();
    }
    private static void checkImages(Map<String,byte[]> images){
        if(images==null)throw invalid();if(images.size()>32)throw tooLarge();long bytes=0;
        for(var item:images.entrySet()){
            if(item.getKey()==null||!item.getKey().matches("f00(?:0[1-9]|[12][0-9]|3[0-2])")||item.getValue()==null||item.getValue().length<4)throw invalid();
            if(item.getValue().length>1024*1024||(bytes+=item.getValue().length)>16*1024*1024)throw tooLarge();
        }
    }
    /** Package limit remains independently enforced, including headers and the ZIP directory. */
    static byte[] archive(AgentNoteModels.Document document,Map<String,byte[]> images,int maximum){
        check(document);checkImages(images);
        var output=new BoundedOutput(maximum);
        try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            zip.setLevel(Deflater.NO_COMPRESSION);
            entry(zip,"note.md",document.packageMarkdown().getBytes(StandardCharsets.UTF_8));
            entry(zip,"source.json",document.sourceJson());
            for(var item:images.entrySet())entry(zip,"images/"+item.getKey()+".jpg",item.getValue());
        }catch(IOException failure){throw new JobException(HttpStatus.INTERNAL_SERVER_ERROR,"AGENT_NOTE_BUILD_FAILED","The study note archive could not be prepared");}
        return output.bytes();
    }
    private static void entry(ZipOutputStream zip,String name,byte[] bytes)throws IOException{
        var entry=new ZipEntry(name);entry.setTimeLocal(LocalDateTime.of(1980,1,1,0,0));zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();
    }
    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();private final int maximum;
        BoundedOutput(int maximum){this.maximum=maximum;}
        @Override public void write(int value){if(output.size()>=maximum)throw tooLarge();output.write(value);}
        @Override public void write(byte[] bytes,int offset,int length){if(length>maximum-output.size())throw tooLarge();output.write(bytes,offset,length);}
        byte[] bytes(){return output.toByteArray();}
    }
    private static JobException tooLarge(){return new JobException(HttpStatus.PAYLOAD_TOO_LARGE,"AGENT_NOTE_TOO_LARGE","The complete study note exceeds its size limit; no content was truncated");}
    private static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"AGENT_NOTE_SOURCE_INVALID","The study note material failed its source checks");}
}
