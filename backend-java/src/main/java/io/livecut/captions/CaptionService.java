package io.livecut.captions;

import static io.livecut.captions.CaptionModels.*;
import java.util.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.exports.ExportModels;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Caption edits create immutable revisions without changing the confirmed MP4, its review or its ASR. */
@Service
public class CaptionService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> EDIT=Set.of("id","start_ms","end_ms","text","reviewed");
    private static final Set<String> CUE=Set.of("id","part_index","source_utterance_id","source_start_ms","source_end_ms","retained_start_ms","retained_end_ms","start_ms","end_ms","text","source_text","boundary_partial","reviewed","requires_review");
    private final JdbcTemplate jdbc;
    private final CaptionSourceResolver sources;
    private final TransactionTemplate tx;
    private record Revision(long number,String hash,List<Cue> cues) {}
    /** Internal delivery pairing, never returned by the public caption controller. */
    public record VerifiedDelivery(Snapshot snapshot,ExportModels.Content content,String contentKey,JsonNode mediaInfo) {}
    public CaptionService(JdbcTemplate jdbc,CaptionSourceResolver sources,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.sources=sources;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Snapshot initialize(String exportId,String clipId){return tx.execute(status->{
        var source=sources.resolve(exportId,clipId);
        var existing=jdbc.queryForList("SELECT * FROM caption_document WHERE clip_id=? FOR UPDATE",source.clipId());
        if(existing.isEmpty()){
            String basis=canonical(source.basis()),content=canonical(JSON.valueToTree(new Content(source.originals())));
            jdbc.update("INSERT INTO caption_document (clip_id,export_id,job_id,basis_hash,basis_json) VALUES (?,?,?,?,?)",source.clipId(),source.exportId(),source.jobId(),source.basisHash(),basis);
            jdbc.update("INSERT INTO caption_revision (clip_id,revision,content_hash,content_json) VALUES (?,0,?,?)",source.clipId(),sha(content),content);
        }
        var document=lockDocument(source);return snapshot(source,document,((Number)document.get("latest_revision")).longValue());
    });}
    public Snapshot get(String exportId,String clipId,Long revision){
        if(revision!=null&&revision<0)throw invalid("Revision must be nonnegative");
        return tx.execute(status->{var source=sources.resolve(exportId,clipId);var document=lockDocument(source);
            return snapshot(source,document,revision==null?((Number)document.get("latest_revision")).longValue():revision);});
    }
    public Snapshot save(String exportId,String clipId,JsonNode input){
        fields(input,Set.of("base_revision","cues"));long base=number(input,"base_revision");
        return tx.execute(status->{
            var source=sources.resolve(exportId,clipId);var document=lockDocument(source);current(document,base);
            revision(source,0); // r0 is the immutable authority, never the previously edited revision.
            List<Cue> cues=edits(input.path("cues"),source);
            long next;try{next=Math.addExact(base,1);}catch(ArithmeticException exhausted){throw invalid("Revision limit reached");}
            String content=canonical(JSON.valueToTree(new Content(cues))),hash=sha(content);
            jdbc.update("INSERT INTO caption_revision (clip_id,revision,content_hash,content_json) VALUES (?,?,?,?)",source.clipId(),next,hash,content);
            jdbc.update("UPDATE caption_document SET latest_revision=? WHERE clip_id=?",next,source.clipId());
            return snapshot(source,document,next);
        });
    }
    public Snapshot confirm(String exportId,String clipId,JsonNode input){
        fields(input,Set.of("revision","acknowledge_review"));long number=number(input,"revision");
        if(!input.path("acknowledge_review").isBoolean()||!input.path("acknowledge_review").booleanValue())throw invalid("Explicit review acknowledgement is required");
        return tx.execute(status->{
            var source=sources.resolve(exportId,clipId);var document=lockDocument(source);current(document,number);
            revision(source,0);var revision=revision(source,number);
            if(revision.cues().stream().anyMatch(Cue::requiresReview))throw conflict("CAPTION_REVIEW_REQUIRED","Review, correct or remove every partial source sentence before confirming");
            var old=jdbc.queryForList("SELECT content_hash FROM caption_confirmation WHERE clip_id=? AND revision=?",String.class,source.clipId(),number);
            if(!old.isEmpty()&&!old.getFirst().equals(revision.hash()))throw corrupt();
            jdbc.update("INSERT INTO caption_confirmation (clip_id,revision,content_hash) VALUES (?,?,?) ON DUPLICATE KEY UPDATE content_hash=content_hash",source.clipId(),number,revision.hash());
            return snapshot(source,document,number);
        });
    }
    /** Delivery callers must name an immutable confirmed revision; latest is not silently substituted. */
    public Snapshot readConfirmed(String exportId,String clipId,long number){
        return readConfirmedForDelivery(exportId,clipId,number).snapshot();
    }
    public VerifiedDelivery readConfirmedForDelivery(String exportId,String clipId,long number){
        if(number<0)throw invalid("Revision must be nonnegative");
        return tx.execute(status->{var source=sources.resolve(exportId,clipId);var document=lockDocument(source);var result=snapshot(source,document,number);
            if(!result.status().equals("CONFIRMED"))throw conflict("CAPTION_CONFIRMATION_REQUIRED","Confirm this caption revision before downloading");
            return new VerifiedDelivery(result,source.verifiedContent(),source.verifiedContentKey(),source.verifiedMediaInfo());});
    }
    private Map<String,Object> lockDocument(CaptionSourceResolver.Resolved source){
        var rows=jdbc.queryForList("SELECT * FROM caption_document WHERE clip_id=? FOR UPDATE",source.clipId());
        if(rows.isEmpty())throw missing("CAPTIONS_NOT_INITIALIZED");var document=rows.getFirst();
        try{
            JsonNode basis=JSON.readTree(String.valueOf(document.get("basis_json")));
            if(!source.exportId().equals(document.get("export_id"))||!source.jobId().equals(document.get("job_id"))
                ||!source.basisHash().equals(document.get("basis_hash"))||!sha(canonical(basis)).equals(document.get("basis_hash"))
                ||!canonical(basis).equals(canonical(source.basis())))throw CaptionSourceResolver.invalid();
        }catch(JobException controlled){throw controlled;}catch(RuntimeException malformed){throw CaptionSourceResolver.invalid();}
        return document;
    }
    private Snapshot snapshot(CaptionSourceResolver.Resolved source,Map<String,Object> document,long number){
        revision(source,0);Revision revision=revision(source,number);
        var confirmations=jdbc.queryForList("SELECT revision,content_hash FROM caption_confirmation WHERE clip_id=? ORDER BY revision",source.clipId());
        List<Long> confirmed=new ArrayList<>();boolean selected=false;
        for(var row:confirmations){long value=((Number)row.get("revision")).longValue();confirmed.add(value);
            if(value==number){if(!revision.hash().equals(row.get("content_hash")))throw corrupt();selected=true;}}
        if(selected&&revision.cues().stream().anyMatch(Cue::requiresReview))throw corrupt();
        return new Snapshot(source.exportId(),source.clipId(),source.title(),source.contentUrl(),number,selected?"CONFIRMED":"DRAFT",confirmed,revision.hash(),source.source(),revision.cues());
    }
    private Revision revision(CaptionSourceResolver.Resolved source,long number){
        var rows=jdbc.queryForList("SELECT content_hash,content_json FROM caption_revision WHERE clip_id=? AND revision=?",source.clipId(),number);
        if(rows.isEmpty())throw missing("CAPTION_REVISION_NOT_FOUND");
        try{
            var row=rows.getFirst();JsonNode content=JSON.readTree(String.valueOf(row.get("content_json")));
            if(!sha(canonical(content)).equals(row.get("content_hash")))throw corrupt();
            fields(content,Set.of("cues"));var array=content.path("cues");if(!array.isArray()||array.size()>CaptionMapper.MAX_CUES)throw corrupt();
            Map<String,Cue> originals=new HashMap<>();source.originals().forEach(c->originals.put(c.id(),c));
            List<Cue> result=new ArrayList<>();
            for(JsonNode value:array){
                fields(value,CUE);String id=text(value,"id",128);Cue original=originals.get(id);if(original==null)throw corrupt();
                Cue cue=CaptionModels.edited(original,number(value,"start_ms"),number(value,"end_ms"),text(value,"text",CaptionMapper.MAX_TEXT_CHARACTERS_PER_CUE),bool(value,"reviewed"));
                if(number(value,"part_index")!=original.partIndex()||!text(value,"source_utterance_id",128).equals(original.sourceUtteranceId())
                    ||number(value,"source_start_ms")!=original.sourceStartMs()||number(value,"source_end_ms")!=original.sourceEndMs()
                    ||number(value,"retained_start_ms")!=original.retainedStartMs()||number(value,"retained_end_ms")!=original.retainedEndMs()
                    ||!text(value,"source_text",CaptionMapper.MAX_TEXT_CHARACTERS_PER_CUE).equals(original.sourceText())
                    ||bool(value,"boundary_partial")!=original.boundaryPartial()||bool(value,"requires_review")!=cue.requiresReview())throw corrupt();
                result.add(cue);
            }
            validate(result,source.source().durationMs());
            if(number==0&&(!result.equals(source.originals())||!row.get("content_hash").equals(source.basis().path("original_content_hash").asText())))throw corrupt();
            return new Revision(number,String.valueOf(row.get("content_hash")),List.copyOf(result));
        }catch(JobException invalid){if(invalid.getCode().equals("CAPTION_CONTENT_INVALID"))throw invalid;throw corrupt();}
        catch(RuntimeException invalid){throw corrupt();}
    }
    private List<Cue> edits(JsonNode values,CaptionSourceResolver.Resolved source){
        if(!values.isArray()||values.size()>CaptionMapper.MAX_CUES)throw invalid("Too many caption cues");
        Map<String,Cue> originals=new HashMap<>();source.originals().forEach(c->originals.put(c.id(),c));List<Cue> cues=new ArrayList<>();
        for(JsonNode value:values){fields(value,EDIT);Cue original=originals.get(text(value,"id",128));if(original==null)throw invalid("New source cue identities cannot be invented");
            cues.add(CaptionModels.edited(original,number(value,"start_ms"),number(value,"end_ms"),text(value,"text",CaptionMapper.MAX_TEXT_CHARACTERS_PER_CUE),bool(value,"reviewed")));}
        validate(cues,source.source().durationMs());return List.copyOf(cues);
    }
    private static void validate(List<Cue> cues,long duration){
        try{
            Set<String> ids=new HashSet<>();long previous=0,totalBytes=0;
            for(Cue cue:cues){if(!CaptionMapper.validId(cue.id())||!ids.add(cue.id())||cue.startMs()<previous||cue.endMs()<=cue.startMs()||cue.endMs()>duration)throw invalid("Cue times must be ordered, disjoint and within the clip");
                previous=cue.endMs();totalBytes+=CaptionMapper.textBytes(cue.text(),CaptionMapper.MAX_TEXT_CHARACTERS_PER_CUE);
                if(totalBytes>CaptionMapper.MAX_TOTAL_TEXT_BYTES)throw invalid("Caption text byte limit exceeded");}
        }catch(IllegalArgumentException bad){throw invalid("Caption text is invalid");}
    }
    private static void current(Map<String,Object> document,long expected){if(((Number)document.get("latest_revision")).longValue()!=expected)throw conflict("CAPTION_REVISION_CONFLICT","Caption edits changed; the current draft remains in the browser");}
    private static void fields(JsonNode node,Set<String> names){if(node==null||!node.isObject()||!Set.copyOf(node.propertyNames()).equals(names))throw invalid("Unexpected or missing fields");}
    private static long number(JsonNode node,String field){JsonNode value=node.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0)throw invalid("Invalid "+field);return value.longValue();}
    private static boolean bool(JsonNode node,String field){JsonNode value=node.path(field);if(!value.isBoolean())throw invalid("Invalid "+field);return value.booleanValue();}
    private static String text(JsonNode node,String field,int maximum){JsonNode value=node.path(field);if(!value.isString()||value.stringValue().isBlank()||value.stringValue().length()>maximum)throw invalid("Invalid "+field);return value.stringValue();}
    private static String canonical(JsonNode node){return ClipRequestService.canonicalJson(node);}
    private static String sha(String content){return ClipRequestService.sha(content);}
    private static JobException invalid(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_CAPTION_REVIEW",message);}
    private static JobException corrupt(){return conflict("CAPTION_CONTENT_INVALID","Stored caption content failed source or hash validation");}
    private static JobException conflict(String code,String message){return new JobException(HttpStatus.CONFLICT,code,message);}
    private static JobException missing(String code){return new JobException(HttpStatus.NOT_FOUND,code,"Requested caption document or revision does not exist");}
}
