package io.livecut.jobs;

import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Public text recovery metadata deliberately excludes credentials, URLs and provider bodies. */
public final class TextExecutionPolicy {
    private TextExecutionPolicy() {}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> REQUIRED=Set.of("state","attempt_number","max_attempts","provider","model_id","timeout_seconds","elapsed_seconds","retry_in_seconds");
    private static final Set<String> STATES=Set.of("REQUESTING","RETRY_WAIT","SWITCHING_PROVIDER","SUCCEEDED","EXHAUSTED");
    public static List<String> fallbackModels(Object value) {
        if(!(value instanceof List<?> models)||models.size()>2)throw invalid("At most two fallback model identifiers are allowed");
        List<String> result=new ArrayList<>();
        for(Object model:models)if(!(model instanceof String id)||!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||result.contains(id))throw invalid("Fallback models must be distinct identifiers");else result.add(id);
        return List.copyOf(result);
    }
    public static List<String> configuredFallbacks(String value){return value==null||value.isBlank()?List.of():fallbackModels(Arrays.stream(value.split(",",-1)).map(String::strip).toList());}
    public static List<String> storedFallbacks(Object value){return value==null?List.of():fallbackModels(JSON.readValue(String.valueOf(value),List.class));}
    public static Map<String,Object> progress(JsonNode value){
        if(value==null||!value.isObject()||!value.propertyNames().containsAll(REQUIRED)||value.propertyNames().stream().anyMatch(k->!REQUIRED.contains(k)&&!k.equals("error_code")))throw invalid("Invalid text progress fields");
        if(!value.path("state").isString()||!STATES.contains(value.path("state").stringValue()))throw invalid("Invalid text progress state");
        Map<String,Object> result=new TreeMap<>();result.put("state",value.path("state").stringValue());
        result.put("provider",identifier(value,"provider",64));result.put("model_id",identifier(value,"model_id",128));
        long maximum=integer(value,"max_attempts",1,3),attempt=integer(value,"attempt_number",1,maximum);
        result.put("max_attempts",maximum);result.put("attempt_number",attempt);
        result.put("timeout_seconds",integer(value,"timeout_seconds",1,300));
        result.put("elapsed_seconds",integer(value,"elapsed_seconds",0,1800));
        result.put("retry_in_seconds",integer(value,"retry_in_seconds",0,60));
        if(value.has("error_code")){JsonNode error=value.path("error_code");if(!error.isString()||!error.stringValue().matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid("Invalid recovery error code");result.put("error_code",error.stringValue());}
        return Map.copyOf(result);
    }
    public static Map<String,Object> readProgress(Object value){if(value==null)return null;return progress(JSON.readTree(String.valueOf(value)));}
    public static String serialize(Object value){return JSON.writeValueAsString(value);}
    private static String identifier(JsonNode value,String field,int maximum){JsonNode node=value.path(field);if(!node.isString()||!node.stringValue().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,"+(maximum-1)+"}"))throw invalid("Invalid text progress identifier");return node.stringValue();}
    private static long integer(JsonNode value,String field,long minimum,long maximum){JsonNode node=value.path(field);if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<minimum||node.longValue()>maximum)throw invalid("Invalid text progress value");return node.longValue();}
    private static JobException invalid(String message){return new JobException(HttpStatus.BAD_REQUEST,"INVALID_TEXT_POLICY",message);}
}
