package io.livecut.manualclips;

import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Set;

@RestController
public class ManualClipPlanController {
    private final ManualClipPlanService plans;
    public ManualClipPlanController(ManualClipPlanService plans){this.plans=plans;}
    @PostMapping("/api/jobs/{jobId}/manual-clip-plans")
    public JsonNode create(@PathVariable String jobId,@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestParam MultiValueMap<String,String> query){fields(query,Set.of());return plans.create(jobId,input,key);}
    @PostMapping("/api/manual-clip-plans/{id}/revisions")
    public JsonNode revise(@PathVariable String id,@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestParam MultiValueMap<String,String> query){fields(query,Set.of());return plans.revise(id,input,key);}
    @GetMapping("/api/jobs/{jobId}/manual-clip-plan-submissions/{key}")
    public JsonNode createSubmission(@PathVariable String jobId,@PathVariable String key,@RequestParam MultiValueMap<String,String> query){fields(query,Set.of());return plans.createSubmission(jobId,key);}
    @GetMapping("/api/manual-clip-plans/{id}/revision-submissions/{key}")
    public JsonNode revisionSubmission(@PathVariable String id,@PathVariable String key,@RequestParam MultiValueMap<String,String> query){fields(query,Set.of());return plans.revisionSubmission(id,key);}
    @GetMapping("/api/jobs/{jobId}/manual-clip-plans")
    public ManualClipModels.Page list(@PathVariable String jobId,@RequestParam MultiValueMap<String,String> query){fields(query,Set.of("limit","offset"));return plans.list(jobId,(int)number(query.getFirst("limit"),20,1,50),number(query.getFirst("offset"),0,0,9007199254740991L));}
    @GetMapping("/api/manual-clip-plans/{id}")
    public JsonNode get(@PathVariable String id,@RequestParam MultiValueMap<String,String> query){fields(query,Set.of("revision"));return plans.get(id,query.containsKey("revision")?number(query.getFirst("revision"),0,1,9007199254740991L):null);}
    @PostMapping("/api/manual-clip-plans/{id}/confirm")
    public JsonNode confirm(@PathVariable String id,@RequestBody JsonNode input,@RequestParam MultiValueMap<String,String> query){fields(query,Set.of());return plans.confirm(id,input);}
    private static void fields(MultiValueMap<String,String> query,Set<String> allowed){if(!allowed.containsAll(query.keySet())||query.values().stream().anyMatch(values->values.size()!=1||values.getFirst()==null||values.getFirst().isEmpty()))throw invalid();}
    private static long number(String value,long fallback,long minimum,long maximum){if(value==null)return fallback;try{if(!value.matches("0|[1-9][0-9]*")||value.length()>16)throw invalid();long result=Long.parseLong(value);if(result<minimum||result>maximum)throw invalid();return result;}catch(NumberFormatException failure){throw invalid();}}
    private static JobException invalid(){return new JobException(HttpStatus.BAD_REQUEST,"MANUAL_CLIP_INVALID","Manual clip query is invalid");}
}
