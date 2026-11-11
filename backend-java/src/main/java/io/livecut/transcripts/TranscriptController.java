package io.livecut.transcripts;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs/{jobId}/transcript")
public class TranscriptController {
    private final TranscriptService transcripts;
    public TranscriptController(TranscriptService transcripts){this.transcripts=transcripts;}
    @GetMapping public ResponseEntity<TranscriptModels.Page> page(@PathVariable String jobId,
            @RequestParam MultiValueMap<String,String> query){return response(transcripts.page(jobId,query));}
    @GetMapping("/context") public ResponseEntity<TranscriptModels.Context> context(@PathVariable String jobId,
            @RequestParam MultiValueMap<String,String> query){return response(transcripts.context(jobId,query));}
    private static <T> ResponseEntity<T> response(T body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(body);
    }
}
