package io.livecut.clips;

import io.livecut.exports.ExportModels;
import io.livecut.exports.ExportService;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class ClipRequestController {
    private final ClipRequestService requests;
    private final ExportService exports;
    private final ClipRequestHistoryService history;
    public ClipRequestController(ClipRequestService requests,ExportService exports,ClipRequestHistoryService history){this.requests=requests;this.exports=exports;this.history=history;}
    @PostMapping("/api/jobs/{jobId}/clip-requests")
    public ClipRequestModels.Snapshot create(@PathVariable String jobId,@RequestBody ClipRequestModels.CreateRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key){return requests.create(jobId,request,key);}
    @GetMapping("/api/jobs/{jobId}/clip-requests")
    public ClipRequestModels.Page list(@PathVariable String jobId){return requests.list(jobId);}
    @GetMapping("/api/jobs/{jobId}/clip-request-history")
    public ClipRequestModels.HistoryPage history(@PathVariable String jobId,@RequestParam(required=false) String limit,@RequestParam(required=false) String cursor){return history.list(jobId,limit,cursor);}
    @GetMapping("/api/clip-requests/{id}")
    public ClipRequestModels.Snapshot get(@PathVariable String id){return requests.get(id);}
    @PostMapping("/api/clip-requests/{id}/manual-reviews")
    public JsonNode manualReview(@PathVariable String id,@RequestBody JsonNode request,@RequestHeader(value="Idempotency-Key",required=false) String key){return requests.createManualReview(id,request,key);}
    @GetMapping("/api/clip-requests/{id}/manual-reviews/{reviewId}")
    public JsonNode manualReview(@PathVariable String id,@PathVariable String reviewId){return requests.getManualReview(id,reviewId);}
    @PostMapping("/api/clip-requests/{id}/cancel")
    public ClipRequestModels.Snapshot cancel(@PathVariable String id){return requests.cancel(id);}
    @PostMapping("/api/clip-requests/{id}/resume")
    public ClipRequestModels.Snapshot resume(@PathVariable String id,@RequestBody(required=false) ClipRequestModels.ResumeRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key){return requests.resume(id,request,key);}
    @PutMapping("/api/clip-requests/{id}/plan")
    public ClipRequestModels.Snapshot save(@PathVariable String id,@RequestBody JsonNode request){return requests.save(id,request);}
    @PostMapping("/api/clip-requests/{id}/confirm")
    public ClipRequestModels.Snapshot confirm(@PathVariable String id,@RequestBody JsonNode request){return requests.confirm(id,request);}
    @PostMapping("/api/clip-requests/{id}/exports")
    public ExportModels.Snapshot export(@PathVariable String id,@RequestBody JsonNode request){return exports.createClipRequest(id,request);}
    @GetMapping("/api/clip-requests/{id}/exports")
    public ExportModels.Page exports(@PathVariable String id){return exports.listClipRequest(id);}
}
