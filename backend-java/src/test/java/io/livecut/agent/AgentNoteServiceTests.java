package io.livecut.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import io.livecut.jobs.JobException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentNoteServiceTests {
    @Test void twoSourceFreezesHoldPermitsAndBothExceptionsReturnTheirCapacity()throws Exception{
        var agents=mock(AgentService.class);var notes=new AgentNoteService(agents);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(agents.noteSnapshot(anyString())).thenAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));throw new JobException(HttpStatus.CONFLICT,"AGENT_NOTE_SOURCE_INVALID","Synthetic source failure");});
        var first=CompletableFuture.supplyAsync(()->failure(()->notes.preview("one")));var second=CompletableFuture.supplyAsync(()->failure(()->notes.download("two")));
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));var busy=assertThrows(JobException.class,()->notes.preview("three"));assertEquals("AGENT_NOTE_BUSY",busy.getCode());assertEquals(HttpStatus.TOO_MANY_REQUESTS,busy.getStatus());verify(agents,times(2)).noteSnapshot(anyString());}
        finally{release.countDown();}
        assertEquals("AGENT_NOTE_SOURCE_INVALID",first.get(10,TimeUnit.SECONDS));assertEquals("AGENT_NOTE_SOURCE_INVALID",second.get(10,TimeUnit.SECONDS));
        for(int i=0;i<6;i++)assertEquals("AGENT_NOTE_SOURCE_INVALID",failure(()->notes.preview("after-release")));
        doReturn(null).when(agents).noteSnapshot(anyString());
        for(int i=0;i<6;i++)assertEquals("AGENT_NOTE_SOURCE_INVALID",failure(()->notes.download("renderer-failure-also-releases")));
    }
    @Test void zipBoundIncludesDirectoryAndFixedNamesInsteadOfReturningPartialBytes(){
        var document=new AgentNoteModels.Document("Plain safe note","Plain safe note", "{}".getBytes(StandardCharsets.UTF_8));
        var error=assertThrows(JobException.class,()->AgentNoteService.archive(document,Map.of(),32));assertEquals("AGENT_NOTE_TOO_LARGE",error.getCode());assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,error.getStatus());
        assertTrue(AgentNoteService.archive(document,Map.of(),AgentNoteService.MAX_ZIP_BYTES).length>32);
        var pathError=assertThrows(JobException.class,()->AgentNoteService.archive(document,Map.of("../escape",new byte[]{1,2,3,4}),1024));assertEquals("AGENT_NOTE_SOURCE_INVALID",pathError.getCode());
    }
    @Test void perDocumentAndPerImageCapsRejectWithoutTruncating(){
        var large=new AgentNoteModels.Document("x".repeat(AgentNoteService.MAX_TEXT_BYTES+1),"x",new byte[]{1});
        assertEquals("AGENT_NOTE_TOO_LARGE",failure(()->AgentNoteService.archive(large,Map.of(),AgentNoteService.MAX_ZIP_BYTES)));
        var document=new AgentNoteModels.Document("x","x",new byte[]{1});
        assertEquals("AGENT_NOTE_TOO_LARGE",failure(()->AgentNoteService.archive(document,Map.of("f0001",new byte[1024*1024+1]),AgentNoteService.MAX_ZIP_BYTES)));
        assertEquals("AGENT_NOTE_SOURCE_INVALID",failure(()->AgentNoteService.archive(document,Map.of("f0001",new byte[]{1}),AgentNoteService.MAX_ZIP_BYTES)));
    }
    private static String failure(Runnable action){return assertThrows(JobException.class,action::run).getCode();}
}
