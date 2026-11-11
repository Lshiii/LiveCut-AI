package io.livecut.exports;

import io.livecut.downloads.DeliveryBundleWriter;
import io.livecut.jobs.ArtifactVerifier.SourcePart;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExportVideoDeliveryControllerTests {
    @TempDir Path root;
    private static final String ID="11111111-1111-4111-8111-111111111111";
    private static final long OVERHEAD=16L*1024*1024;

    @Test void headFailsBeforeSelectionOrServiceAndHasSpecificCode(){
        var request=new MockHttpServletRequest("HEAD","/api/exports/invalid/video-package");
        JobException error=assertThrows(JobException.class,()->new ExportVideoDeliveryController(null).download("invalid",null,request));
        assertEquals("VIDEO_PACKAGE_GET_REQUIRED",error.getCode());assertEquals(405,error.getStatus().value());
    }

    @Test void networkDisconnectClosesPreparedArchiveAndRecoversSharedCapacity()throws Exception{
        byte[] bytes={1,2,3};Path media=root.resolve("media.mp4");Files.write(media,bytes);
        var clip=new ExportVideoModels.Clip(ID,"part",null,0,1000,1000,List.of(new SourcePart(0,1000)),bytes.length,DeliveryBundleWriter.hash(bytes),null);
        var plan=new ExportVideoModels.Plan(ID,ID,ID,"a".repeat(64),"b".repeat(64),0,"h264-aac-v1",null,List.of(clip));
        var content=new ExportModels.Content(media,bytes.length,clip.contentHash(),"clip.mp4");
        try(var writer=new DeliveryBundleWriter(root,100_000,1,OVERHEAD*2,30,60)){
            var ready=writer.prepareVideos(plan,ignored->content);
            var service=new ExportVideoDeliveryService(null,writer){@Override public DeliveryBundleWriter.Prepared bundle(String id,List<String> selected){return ready;}};
            var response=new ExportVideoDeliveryController(service).download(ID,ID,new MockHttpServletRequest("GET","/api/exports/"+ID+"/video-package"));
            assertNotNull(response.getBody());assertTrue(Files.exists(ready.path()));
            assertThrows(IOException.class,()->response.getBody().writeTo(new OutputStream(){@Override public void write(int value)throws IOException{throw new IOException("synthetic client disconnected");}}));
            assertFalse(Files.exists(ready.path()));
            try(var next=writer.prepareVideos(plan,ignored->content)){assertTrue(Files.exists(next.path()));}
        }
    }
}
