package io.livecut.exports;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import io.livecut.captions.CaptionDeliveryAsyncConfig;
import io.livecut.jobs.JobException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class ExportVideoDeliveryController {
    private final ExportVideoDeliveryService delivery;
    public ExportVideoDeliveryController(ExportVideoDeliveryService delivery){this.delivery=delivery;}
    @GetMapping("/api/exports/{exportId}/video-package")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String exportId,@RequestParam(required=false) String clips,HttpServletRequest request){
        if("HEAD".equals(request.getMethod()))throw new JobException(HttpStatus.METHOD_NOT_ALLOWED,"VIDEO_PACKAGE_GET_REQUIRED","Download video archives with GET");
        if(clips==null||clips.isEmpty()||clips.length()>3699)throw new JobException(HttpStatus.BAD_REQUEST,"INVALID_VIDEO_PACKAGE","Select 1..100 unique clip IDs");
        var prepared=delivery.bundle(exportId,Arrays.asList(clips.split(",",-1)));
        try{
            request.setAttribute(CaptionDeliveryAsyncConfig.PREPARED,prepared);
            StreamingResponseBody body=output->{try(prepared;InputStream input=Files.newInputStream(prepared.path())){input.transferTo(output);}};
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+prepared.filename()+"\"")
                .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore())
                .contentLength(prepared.sizeBytes()).eTag("\""+prepared.hash()+"\"").body(body);
        }catch(RuntimeException failed){prepared.close();throw failed;}
    }
}
