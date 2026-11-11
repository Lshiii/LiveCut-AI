package io.livecut.exports;

import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import io.livecut.jobs.JobException;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Manual business authority stays in Java; the encoder still receives the existing base-eight export shape. */
@RestController
@RequestMapping("/api/manual-clip-plans/{id}")
public class ManualClipExportController {
    private final ExportService exports;
    public ManualClipExportController(ExportService exports){this.exports=exports;}
    @PostMapping("/exports")
    public ExportModels.Snapshot create(@PathVariable String id,@RequestBody JsonNode input,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return exports.createManualClip(id,input,key);}
    @GetMapping("/exports") public ExportModels.ManualPage list(@PathVariable String id,@RequestParam MultiValueMap<String,String> query){
        fields(query,Set.of("limit","offset"));
        return exports.listManualClip(id,(int)number(query.getFirst("limit"),20,1,50),number(query.getFirst("offset"),0,0,9007199254740991L));
    }
    @GetMapping("/export-submissions/{key}") public ExportModels.Snapshot submission(@PathVariable String id,@PathVariable String key,
            @RequestParam MultiValueMap<String,String> query){fields(query,Set.of());return exports.manualClipExportSubmission(id,key);}
    private static void fields(MultiValueMap<String,String> query,Set<String> allowed){
        if(!allowed.containsAll(query.keySet())||query.values().stream().anyMatch(values->values==null||values.size()!=1||values.getFirst()==null))throw invalid();
    }
    private static long number(String value,long fallback,long minimum,long maximum){
        if(value==null)return fallback;
        try{if(!value.matches("0|[1-9][0-9]*")||value.length()>16)throw invalid();long parsed=Long.parseLong(value);if(parsed<minimum||parsed>maximum)throw invalid();return parsed;}
        catch(NumberFormatException bad){throw invalid();}
    }
    private static JobException invalid(){return new JobException(HttpStatus.BAD_REQUEST,"MANUAL_CLIP_INVALID","Manual export parameters are invalid");}
}
