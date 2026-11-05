package io.livecut.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TextExecutionPolicyTests {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static Map<String,Object> progress(){return Map.of("state","RETRY_WAIT","attempt_number",2,"max_attempts",3,"provider","backup","model_id","deepseek-flash","timeout_seconds",90,"elapsed_seconds",91,"retry_in_seconds",2);}
    @Test void rejectsSecretsAndUnknownFieldsInsteadOfPublishingThem(){
        for(String field:List.of("api_key","base_url","raw_response","run_token")){
            var value=new HashMap<>(progress());value.put(field,"do-not-publish");
            assertThrows(JobException.class,()->TextExecutionPolicy.progress(JSON.valueToTree(value)));
        }
        var unsafe=new HashMap<>(progress());unsafe.put("provider","https://provider.test/?secret=value");assertThrows(JobException.class,()->TextExecutionPolicy.progress(JSON.valueToTree(unsafe)));
    }
    @Test void recoveryCountersAreBoundedAndRoundTripIsStable(){
        var value=TextExecutionPolicy.progress(JSON.valueToTree(progress()));assertEquals(value,TextExecutionPolicy.readProgress(TextExecutionPolicy.serialize(value)));
        for(var change:List.of(Map.of("attempt_number",4),Map.of("max_attempts",4),Map.of("timeout_seconds",301),Map.of("retry_in_seconds",61),Map.of("state","UNKNOWN"))){
            var invalid=new HashMap<>(progress());invalid.putAll(change);assertThrows(JobException.class,()->TextExecutionPolicy.progress(JSON.valueToTree(invalid)));
        }
    }
    @Test void fallbackModelsCannotContainDuplicatesUrlsOrMoreThanTwoEntries(){
        assertEquals(List.of("deepseek-flash","qwen-plus"),TextExecutionPolicy.configuredFallbacks("deepseek-flash, qwen-plus"));
        assertEquals(List.of(),TextExecutionPolicy.storedFallbacks(null));
        for(List<String> invalid:List.of(List.of("same","same"),List.of("https://model.test"),List.of("a","b","c")))assertThrows(JobException.class,()->TextExecutionPolicy.fallbackModels(invalid));
    }
}
