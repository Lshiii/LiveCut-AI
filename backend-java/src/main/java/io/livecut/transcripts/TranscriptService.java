package io.livecut.transcripts;

import static io.livecut.transcripts.TranscriptModels.*;
import io.livecut.jobs.ArtifactVerifier;
import io.livecut.jobs.JobException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only original-ASR access: short consistent metadata reads surround file IO outside transactions. */
@Service
public class TranscriptService {
    private static final long SAFE_INTEGER=9007199254740991L;
    private static final int PAGE_TEXT_BUDGET=200000;
    private static final Pattern WORD_BREAK=Pattern.compile("[\\s\\p{Z}\\uFEFF]+",Pattern.UNICODE_CHARACTER_CLASS);
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final JdbcTemplate jdbc;
    private final ArtifactVerifier artifacts;
    private final TransactionTemplate metadataRead, outsideTransaction;
    private record Metadata(Map<String,Object> job,Map<String,Object> video,List<Map<String,Object>> stages) {}
    private record Verified(Source source,List<Utterance> utterances) {}
    private record PageQuery(String q,List<String> terms,long offset,int limit,String expected) {}

    public TranscriptService(JdbcTemplate jdbc,ArtifactVerifier artifacts,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.artifacts=artifacts;
        metadataRead=new TransactionTemplate(manager);metadataRead.setReadOnly(true);
        metadataRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        metadataRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outsideTransaction=new TransactionTemplate(manager);
        outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public Page page(String value,MultiValueMap<String,String> parameters){
        PageQuery query=pageQuery(parameters);String jobId=uuid(value);
        return read(jobId,query.expected(),verified->{
            List<Utterance> matches=verified.utterances().stream().filter(row->matches(row,query.terms())).toList();
            List<Utterance> items=new ArrayList<>();int characters=0;
            for(int index=(int)Math.min(query.offset(),matches.size());index<matches.size()&&items.size()<query.limit();index++){
                Utterance row=matches.get(index);
                if(!items.isEmpty()&&characters+row.text().length()>PAGE_TEXT_BUDGET)break;
                items.add(row);characters+=row.text().length();
            }
            long after=query.offset()+items.size();
            return new Page("1.0","transcript_page",verified.source(),query.q(),query.offset(),query.limit(),
                    verified.utterances().size(),matches.size(),after<matches.size()?after:null,items);
        });
    }

    public Context context(String value,MultiValueMap<String,String> parameters){
        fields(parameters,Set.of("utterance_id","source_fingerprint"));
        String id=parameter(parameters,"utterance_id",null),expected=fingerprintParameter(parameters,true);
        if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw queryInvalid();
        String jobId=uuid(value);
        return read(jobId,expected,verified->{
            List<Utterance> rows=verified.utterances();
            for(int index=0;index<rows.size();index++)if(rows.get(index).utteranceId().equals(id))
                return new Context("1.0","transcript_context",verified.source(),rows.get(index),
                        index==0?null:rows.get(index-1),index+1==rows.size()?null:rows.get(index+1));
            throw new JobException(HttpStatus.NOT_FOUND,"TRANSCRIPT_UTTERANCE_NOT_FOUND","This original utterance is not in the selected source");
        });
    }

    private <T> T read(String jobId,String expected,Function<Verified,T> projection){
        return outsideTransaction.execute(ignored->{
            Metadata before=metadata(jobId);
            Verified verified;
            try{verified=verify(before);}
            catch(RuntimeException invalid){throw sourceInvalid();}
            if(expected!=null&&!expected.equals(verified.source().sourceFingerprint()))throw sourceChanged();
            T result=projection.apply(verified);
            Metadata after;
            try{after=metadata(jobId);}catch(JobException changed){throw sourceChanged();}
            if(!before.equals(after))throw sourceChanged();
            return result;
        });
    }

    private Metadata metadata(String jobId){
        return metadataRead.execute(ignored->{
            var jobs=jdbc.queryForList("SELECT id,video_id,generation,status,config_hash,config_json,resumed_from_job_id,preparation_source_json FROM analysis_job WHERE id=?",jobId);
            if(jobs.isEmpty())throw new JobException(HttpStatus.NOT_FOUND,"JOB_NOT_FOUND","Analysis job was not found");
            Map<String,Object> job=jobs.getFirst();JsonNode config;
            try{config=JSON.readTree(String.valueOf(job.get("config_json")));}catch(RuntimeException invalid){throw sourceInvalid();}
            if(config==null||!config.isObject())throw sourceInvalid();
            if(!"REVIEW_READY".equals(job.get("status"))||!"topics".equals(config.path("analysis_mode").asText()))
                throw new JobException(HttpStatus.CONFLICT,"TRANSCRIPT_NOT_READY","A completed topics analysis is required");
            var videos=jdbc.queryForList("SELECT id,content_hash,source_key,size_bytes,media_validation_status FROM video_asset WHERE id=?",job.get("video_id"));
            var stages=jdbc.queryForList("SELECT id,job_id,owner_type,owner_id,stage_type,shard_key,status,attempt,run_token,input_hash,manifest_key,manifest_hash FROM job_stage WHERE job_id=? AND owner_type='ANALYSIS' AND stage_type IN ('ASR','TIMELINE') ORDER BY stage_type,id",jobId);
            if(videos.size()!=1||stages.size()!=2||stages.stream().anyMatch(stage->!"SUCCEEDED".equals(stage.get("status")))
                    ||stages.stream().map(stage->stage.get("stage_type")).distinct().count()!=2)throw sourceInvalid();
            return new Metadata(freeze(job),freeze(videos.getFirst()),stages.stream().map(TranscriptService::freeze).toList());
        });
    }

    private Verified verify(Metadata metadata){
        var job=metadata.job();var video=metadata.video();String jobId=String.valueOf(job.get("id")),videoId=String.valueOf(video.get("id"));
        uuid(jobId);uuid(videoId);String videoHash=hashField(video.get("content_hash"));
        int generation=((Number)job.get("generation")).intValue();if(generation<1||!videoId.equals(job.get("video_id")))throw sourceInvalid();
        Map<String,Object> asr=stage(metadata,"ASR"),timeline=stage(metadata,"TIMELINE");
        var asrIdentity=identity(asr,jobId,videoId,generation,videoHash);
        var asrManifest=artifacts.verifyManifest((String)asr.get("manifest_key"),(String)asr.get("manifest_hash"),asrIdentity);
        var transcript=asrManifest.artifacts().stream().filter(file->"transcript".equals(file.kind())).findFirst().orElseThrow();
        JsonNode utterances=artifacts.readJsonArtifact(transcript,asrIdentity);
        var timelineIdentity=identity(timeline,jobId,videoId,generation,videoHash);
        var timelineManifest=artifacts.verifyManifest((String)timeline.get("manifest_key"),(String)timeline.get("manifest_hash"),timelineIdentity);
        var analysis=timelineManifest.artifacts().stream().filter(file->"analysis_result".equals(file.kind())).findFirst().orElseThrow();
        JsonNode result=artifacts.readJsonArtifact(analysis,timelineIdentity);
        if(!result.isObject()||!"1.0".equals(result.path("schema_version").asText())||!"topics".equals(result.path("analysis_mode").asText())
                ||!"model_result".equals(result.path("data_kind").asText())||!"DRAFT".equals(result.path("review_status").asText())
                ||!jobId.equals(result.path("job_id").asText())||!videoId.equals(result.path("video_id").asText())
                ||number(result,"analysis_generation")!=generation||!utterances.isArray()||utterances.isEmpty()||utterances.size()>100000
                ||!utterances.equals(result.path("utterances")))throw sourceInvalid();
        long duration=number(result,"duration_ms");if(duration<1)throw sourceInvalid();
        List<Utterance> rows=new ArrayList<>();Set<String> ids=new HashSet<>();long previous=0;
        for(JsonNode row:utterances){
            if(!row.isObject()||!row.propertyNames().equals(Set.of("utterance_id","start_ms","end_ms","text","source_chunk_id")))throw sourceInvalid();
            String id=identifier(row,"utterance_id"),text=text(row,"text",100000);identifier(row,"source_chunk_id");
            long start=number(row,"start_ms"),end=number(row,"end_ms");
            if(!ids.add(id)||start<previous||end<=start||end>duration)throw sourceInvalid();
            previous=end;rows.add(new Utterance(id,start,end,text));
        }
        String transcriptHash=hashField(transcript.sha256()),analysisHash=hashField(analysis.sha256());
        String fingerprint=sha("transcript-source-v1\n"+jobId+"\n"+videoId+"\n"+generation+"\n"+videoHash+"\n"
                +transcriptHash+"\n"+analysisHash+"\n"+duration+"\n");
        return new Verified(new Source(jobId,videoId,generation,videoHash,transcriptHash,analysisHash,duration,fingerprint),List.copyOf(rows));
    }

    private static ArtifactVerifier.ManifestIdentity identity(Map<String,Object> stage,String job,String video,int generation,String hash){
        if(!job.equals(stage.get("job_id"))||!"ANALYSIS".equals(stage.get("owner_type"))
                ||stage.get("owner_id")!=null&&!job.equals(stage.get("owner_id")))throw sourceInvalid();
        return new ArtifactVerifier.ManifestIdentity(job,video,generation,(String)stage.get("id"),(String)stage.get("stage_type"),
                ((Number)stage.get("attempt")).intValue(),(String)stage.get("run_token"),hash);
    }
    private static Map<String,Object> stage(Metadata source,String type){return source.stages().stream().filter(row->type.equals(row.get("stage_type"))).findFirst().orElseThrow();}
    private static Map<String,Object> freeze(Map<String,Object> row){return Collections.unmodifiableMap(new LinkedHashMap<>(row));}
    private static long number(JsonNode object,String field){JsonNode value=object.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0||value.longValue()>SAFE_INTEGER)throw sourceInvalid();return value.longValue();}
    private static String text(JsonNode object,String field,int maximum){JsonNode value=object.path(field);if(!value.isString()||value.stringValue().isBlank()||value.stringValue().length()>maximum)throw sourceInvalid();return value.stringValue();}
    private static String identifier(JsonNode object,String field){String value=text(object,field,128);if(!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))throw sourceInvalid();return value;}
    private static String hashField(Object value){if(!(value instanceof String hash)||!hash.matches("[a-f0-9]{64}"))throw sourceInvalid();return hash;}
    private static String uuid(String value){try{String canonical=UUID.fromString(value).toString();if(!canonical.equals(value))throw queryInvalid();return canonical;}catch(IllegalArgumentException | NullPointerException invalid){throw queryInvalid();}}
    private static PageQuery pageQuery(MultiValueMap<String,String> parameters){
        fields(parameters,Set.of("q","offset","limit","source_fingerprint"));
        String q=parameter(parameters,"q","");if(q.length()>200)throw queryInvalid();
        List<String> terms=WORD_BREAK.splitAsStream(normalize(q)).filter(term->!term.isEmpty()).toList();if(terms.size()>8)throw queryInvalid();
        return new PageQuery(q,terms,integer(parameter(parameters,"offset","0"),0,SAFE_INTEGER),
                (int)integer(parameter(parameters,"limit","20"),1,50),fingerprintParameter(parameters,false));
    }
    private static void fields(MultiValueMap<String,String> parameters,Set<String> allowed){
        if(parameters==null||!allowed.containsAll(parameters.keySet())||parameters.values().stream().anyMatch(values->values==null||values.size()!=1||values.getFirst()==null))throw queryInvalid();
    }
    private static String parameter(MultiValueMap<String,String> parameters,String key,String fallback){return parameters.containsKey(key)?parameters.getFirst(key):fallback;}
    private static String fingerprintParameter(MultiValueMap<String,String> parameters,boolean required){String value=parameter(parameters,"source_fingerprint",null);if(value==null?required:!value.matches("[a-f0-9]{64}"))throw queryInvalid();return value;}
    private static long integer(String value,long minimum,long maximum){try{if(!value.matches("0|[1-9][0-9]*")||value.length()>16)throw queryInvalid();long parsed=Long.parseLong(value);if(parsed<minimum||parsed>maximum)throw queryInvalid();return parsed;}catch(NumberFormatException invalid){throw queryInvalid();}}
    private static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private static boolean matches(Utterance row,List<String> terms){if(terms.isEmpty())return true;String haystack=normalize(row.text()+" "+time(row.startMs())+" "+time(row.endMs()));return terms.stream().allMatch(haystack::contains);}
    private static String time(long ms){long seconds=ms/1000,hours=seconds/3600,minutes=seconds/60%60;return hours==0?String.format(Locale.ROOT,"%02d:%02d",minutes,seconds%60):String.format(Locale.ROOT,"%02d:%02d:%02d",hours,minutes,seconds%60);}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static JobException queryInvalid(){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_TRANSCRIPT_QUERY","Transcript query parameters are invalid");}
    private static JobException sourceInvalid(){return new JobException(HttpStatus.CONFLICT,"TRANSCRIPT_SOURCE_INVALID","Published transcript source failed integrity validation");}
    private static JobException sourceChanged(){return new JobException(HttpStatus.CONFLICT,"TRANSCRIPT_SOURCE_CHANGED","Published transcript source changed; reload the first page");}
}
