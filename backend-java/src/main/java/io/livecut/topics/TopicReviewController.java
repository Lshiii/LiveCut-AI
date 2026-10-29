package io.livecut.topics;

import io.livecut.downloads.JsonAttachment;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/jobs/{id}/topic-review")
public class TopicReviewController {
    private final TopicReviewService service;
    public TopicReviewController(TopicReviewService service){this.service=service;}
    @GetMapping public TopicModels.Snapshot get(@PathVariable String id,@RequestParam(required=false) String revision){return service.get(id,revision==null?null:number(revision));}
    @PutMapping public TopicModels.Snapshot save(@PathVariable String id,@RequestBody JsonNode request){return service.save(id,request);}
    @PostMapping("/confirm") public TopicModels.Snapshot confirm(@PathVariable String id,@RequestBody JsonNode request){return service.confirm(id,request);}
    @GetMapping("/download") public ResponseEntity<byte[]> download(@PathVariable String id,@RequestParam String revision){long value=number(revision);return JsonAttachment.response("topic-review-"+id+"-r"+value+".json",service.download(id,value));}
    private static long number(String value){try{if(value==null||!value.matches("0|[1-9][0-9]*"))throw new NumberFormatException();return Long.parseLong(value);}catch(NumberFormatException error){throw new JobException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","revision must be a nonnegative integer");}}
}
