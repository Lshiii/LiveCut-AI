package io.livecut.clips;

import io.livecut.video.VideoApiException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static io.livecut.clips.ClipPlanValidator.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductFrameControllerTests {
    @Test void restrictsRequestsToTheFrozenSourceAndMapsIdsToOriginalTimes() {
        var snapshot = mock(ClipRequestModels.Snapshot.class);
        when(snapshot.status()).thenReturn("PLAN_READY");
        when(snapshot.planHash()).thenReturn("p");
        when(snapshot.source()).thenReturn(new ClipRequestModels.Source("v", "t", 12000));
        when(snapshot.utterances()).thenReturn(JSON.readTree("""
            [{"utterance_id":"u1","start_ms":200,"end_ms":2500},{"utterance_id":"u2","start_ms":3000,"end_ms":5500}]
            """));
        var input = new ProductFrameController.Candidate("choice1", List.of(new ProductFrameController.Part("u1","u2")));
        var query = new ProductFrameController.Query("v","t","p","展示鱼柳",List.of(input));
        assertEquals(List.of(List.of(200L,5500L)), ProductFrameController.bind(snapshot,query).getFirst().parts());
        var wrongHash = new ProductFrameController.Query("v","t","older","展示鱼柳",List.of(input));
        assertEquals("PRODUCT_FRAME_SOURCE_CHANGED",assertThrows(VideoApiException.class, ()->ProductFrameController.bind(snapshot,wrongHash)).getCode());
        for(var bad : List.of(new ProductFrameController.Part("u2","u1"),new ProductFrameController.Part("u1","unknown"))) {
            var incorrect = new ProductFrameController.Query("v","t","p","鱼柳",List.of(new ProductFrameController.Candidate("c", List.of(bad))));
            assertThrows(VideoApiException.class,()->ProductFrameController.bind(snapshot,incorrect));
        }
    }
}
