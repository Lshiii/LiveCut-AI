package io.livecut.sourceimports;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import io.livecut.jobs.InternalWorkerAuthFilter;
import io.livecut.video.ApiErrorHandler;
import org.junit.jupiter.api.*;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Production HTTP route/filter boundary without a DB, server socket or model provider. */
class SourceImportMvcTests {
    private AnnotationConfigWebApplicationContext context;private MockMvc mvc;private ObjectMapper json;private SourceImportService service;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Import({WebMvcAutoConfiguration.class,JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,ApiErrorHandler.class,SourceImportController.class,InternalSourceImportController.class})
    static class WebOnly {
        @Bean SourceImportService service(){return mock(SourceImportService.class);}
        @Bean InternalWorkerAuthFilter auth(ObjectMapper json){return new InternalWorkerAuthFilter("test-only-key",json);}
    }
    @BeforeEach void start(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(WebOnly.class);context.refresh();json=context.getBean(ObjectMapper.class);service=context.getBean(SourceImportService.class);mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(InternalWorkerAuthFilter.class)).build();}
    @AfterEach void stop(){if(context!=null)context.close();}
    @Test void missingOrWrongKeyNeverReachesTheClaimService()throws Exception{
        for(String route:java.util.List.of("/internal/source-imports/claim","/%69nternal/source-imports/claim","/internal;v=1/source-imports/claim")){
            var response=mvc.perform(post(java.net.URI.create(route)).contentType("application/json").content("{\"worker_id\":\"w\",\"claim_request_id\":\"c\"}")).andReturn().getResponse();
            assertEquals(401,response.getStatus(),route);assertEquals("WORKER_UNAUTHORIZED",json.readTree(response.getContentAsByteArray()).path("error_code").asText());
        }
        var wrong=mvc.perform(post("/internal/source-imports/claim").header("X-Worker-Key","wrong").contentType("application/json").content("{}")).andReturn().getResponse();assertEquals(401,wrong.getStatus());verifyNoInteractions(service);
    }
    @Test void authenticatedRequestUsesTheNewClaimRoute()throws Exception{
        when(service.claim(any())).thenReturn(new SourceImportModels.ClaimResponse(null));
        var response=mvc.perform(post("/internal/source-imports/claim").header("X-Worker-Key","test-only-key").contentType("application/json").content("{\"worker_id\":\"w\",\"claim_request_id\":\"c\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertTrue(json.readTree(response.getContentAsByteArray()).path("claim").isNull());verify(service).claim(argThat(input->input.path("worker_id").asText().equals("w")));
    }
    @Test void missingServerConfigurationFailsClosed()throws Exception{
        var closed=MockMvcBuilders.webAppContextSetup(context).addFilters(new InternalWorkerAuthFilter("",json)).build();
        var response=closed.perform(post("/internal/source-imports/claim").contentType("application/json").content("{}")).andReturn().getResponse();
        assertEquals(503,response.getStatus());assertEquals("WORKER_AUTH_NOT_CONFIGURED",json.readTree(response.getContentAsByteArray()).path("error_code").asText());verifyNoInteractions(service);
    }
    @Test void retryPassesTheFrozenExpectedAttemptBody()throws Exception{
        String id=java.util.UUID.randomUUID().toString();
        mvc.perform(post("/api/source-imports/"+id+"/retry").contentType("application/json").content("{\"expected_attempt\":2}")).andReturn();
        verify(service).retry(eq(id),argThat(input->input.path("expected_attempt").asInt()==2));
    }
}
