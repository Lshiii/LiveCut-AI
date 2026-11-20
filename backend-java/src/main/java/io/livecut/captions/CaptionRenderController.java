package io.livecut.captions;

import io.livecut.video.VideoApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class CaptionRenderController {
    private final CaptionRenderService renders;
    private final CaptionFramePreview preview;
    public CaptionRenderController(CaptionRenderService renders, CaptionFramePreview preview){this.renders=renders;this.preview=preview;}
    @PostMapping("/api/exports/{export}/clips/{clip}/caption-renders/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String export, @PathVariable String clip,
        @RequestParam("at_ms") long atMs, @RequestBody JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
            .header("X-Content-Type-Options", "nosniff").body(preview.render(export, clip, body, atMs));
    }
    @PostMapping("/api/exports/{export}/clips/{clip}/caption-renders")
    public CaptionRenderModels.Snapshot create(@PathVariable String export,@PathVariable String clip,
        @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode body){return renders.create(export,clip,key,body);}
    @GetMapping("/api/exports/{export}/clips/{clip}/caption-renders")
    public CaptionRenderModels.Page list(@PathVariable String export,@PathVariable String clip){return renders.list(export,clip);}
    @GetMapping("/api/caption-renders/{id}") public CaptionRenderModels.Snapshot get(@PathVariable String id){return renders.get(id);}
    @PostMapping("/api/caption-renders/{id}/cancel") public CaptionRenderModels.Snapshot cancel(@PathVariable String id){return renders.cancel(id);}
    @PostMapping("/api/caption-renders/{id}/retry") public CaptionRenderModels.Snapshot retry(@PathVariable String id){return renders.retry(id);}
    @RequestMapping(value="/api/caption-renders/{id}/content",method={RequestMethod.GET,RequestMethod.HEAD})
    public ResponseEntity<Resource> content(@PathVariable String id,@RequestParam(defaultValue="false") boolean download,HttpServletRequest request){
        var file=renders.content(id);ResponseEntity.BodyBuilder response=ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
            .header(HttpHeaders.ACCEPT_RANGES,"bytes").header(HttpHeaders.CONTENT_DISPOSITION,(download?"attachment":"inline")+"; filename=\""+file.filename()+"\"")
            .header("X-Content-Type-Options","nosniff").eTag("\""+file.hash()+"\"");
        if(request.getMethod().equals("HEAD"))return response.contentLength(file.sizeBytes()).build();Resource resource=new FileSystemResource(file.path());
        if(request.getHeader(HttpHeaders.RANGE)!=null)try{HttpRange.toResourceRegions(HttpRange.parseRanges(request.getHeader(HttpHeaders.RANGE)),resource);}
            catch(IllegalArgumentException bad){throw new VideoApiException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,"RANGE_NOT_SATISFIABLE","Requested byte range is outside the clip",file.sizeBytes());}
        return response.body(resource);
    }
}
