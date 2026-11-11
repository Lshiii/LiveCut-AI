package io.livecut.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import io.livecut.jobs.InternalWorkerAuthFilter;
import io.livecut.jobs.JobService;
import io.livecut.uploads.UploadService;
import io.livecut.video.ApiErrorHandler;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Real Boot static handlers and production error/auth behavior, with no socket, database or business beans. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkbenchStaticResourcesTests {
    private static final String KEY = "workbench-static-test-only";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ObjectMapper json;
    private final boolean packaged = Boolean.getBoolean("workbench.packaged");
    @Configuration(proxyBeanMethods = false)
    @Import({WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, ApiErrorHandler.class, ExistingApi.class})
    static class WebOnly {
        @Bean InternalWorkerAuthFilter workerAuthentication(ObjectMapper json) { return new InternalWorkerAuthFilter(KEY, json); }
    }
    @RestController static class ExistingApi {
        @GetMapping("/api/workbench-test-probe") java.util.Map<String, Boolean> existing() { return java.util.Map.of("existing_api", true); }
    }
    @BeforeAll void startMockContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(WebOnly.class); context.refresh();
        json = context.getBean(ObjectMapper.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(InternalWorkerAuthFilter.class)).build();
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        assertTrue(context.getBeansOfType(JobService.class).isEmpty());
        assertTrue(context.getBeansOfType(UploadService.class).isEmpty());
    }
    @AfterAll void closeMockContext() { if (context != null) context.close(); }

    @Test void rootAndJobQueryUseOnlyThePackagedWelcomePage() throws Exception {
        for (String path : List.of("/", "/?job=00000000-0000-0000-0000-000000000001")) {
            MvcResult result = mvc.perform(get(path)).andReturn();
            if (packaged) { assertEquals(200, result.getResponse().getStatus()); assertEquals("index.html", result.getResponse().getForwardedUrl()); }
            else error(result, 404, "RESOURCE_NOT_FOUND");
        }
    }
    @Test void realClasspathFilesMatchTheCompleteValidatedDistribution() throws Exception {
        if (!packaged) { assertNull(getClass().getResource("/static/index.html")); error(mvc.perform(get("/index.html")).andReturn(), 404, "RESOURCE_NOT_FOUND"); return; }
        Path distribution = Path.of(System.getProperty("workbench.dist"));
        int count = 0; boolean worker = false, javascript = false, stylesheet = false;
        try (var files = Files.walk(distribution)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = distribution.relativize(file).toString().replace('\\', '/');
                MvcResult response = mvc.perform(get("/" + relative)).andReturn(); assertEquals(200, response.getResponse().getStatus(), relative);
                byte[] expected = Files.readAllBytes(file); assertArrayEquals(expected, response.getResponse().getContentAsByteArray(), relative);
                try (var resource = getClass().getResourceAsStream("/static/" + relative)) { assertNotNull(resource, relative); assertArrayEquals(expected, resource.readAllBytes(), relative); }
                String type = response.getResponse().getContentType(); assertNotNull(type, relative);
                if (relative.endsWith(".js")) { assertTrue(type.contains("javascript"), type); javascript = true; }
                if (relative.endsWith(".css")) { assertTrue(type.startsWith("text/css"), type); stylesheet = true; }
                if (relative.equals("index.html")) assertTrue(type.startsWith("text/html"), type);
                if (relative.equals("favicon.svg")) assertTrue(type.startsWith("image/svg+xml"), type);
                worker |= relative.matches("assets/(.*/)?fileHash\\.worker-[A-Za-z0-9_-]{8,}\\.js"); count++;
            }
        }
        assertTrue(count >= 5 && worker && javascript && stylesheet);
    }
    @Test void unknownApisCapabilitiesAssetsAndPathRoutesNeverBecomeIndexHtml() throws Exception {
        for (String path : List.of("/api/__not_a_route__", "/api/agent-capabilities/__not_a_capability__", "/assets/__missing__.js", "/not-a-spa-route")) {
            error(mvc.perform(get(path).accept(MediaType.APPLICATION_JSON)).andReturn(), 404, "RESOURCE_NOT_FOUND");
        }
        var existing = mvc.perform(get("/api/workbench-test-probe")).andReturn(); assertEquals(200, existing.getResponse().getStatus()); assertTrue(json.readTree(existing.getResponse().getContentAsString()).path("existing_api").booleanValue());
    }
    @Test void missingResourcesStayJson404EvenWhenABrowserRequestsHtml() throws Exception {
        for (String path : List.of("/api/__not_a_route__", "/api/agent-capabilities/__not_a_capability__", "/assets/__missing__.js")) {
            error(mvc.perform(get(path).accept(MediaType.TEXT_HTML)).andReturn(), 404, "RESOURCE_NOT_FOUND");
        }
    }
    @Test void internalUnknownPathsKeepRealAuthenticationThen404IncludingEncodedVariants() throws Exception {
        for (String path : List.of("/internal/__not_a_route__", "/%69nternal/__not_a_route__", "/internal;version=1/__not_a_route__")) {
            error(mvc.perform(get(URI.create(path))).andReturn(), 401, "WORKER_UNAUTHORIZED");
            error(mvc.perform(get(URI.create(path)).header("X-Worker-Key", KEY).accept(MediaType.APPLICATION_JSON)).andReturn(), 404, "RESOURCE_NOT_FOUND");
        }
    }
    private void error(MvcResult result, int status, String code) throws Exception {
        var response = result.getResponse(); assertEquals(status, response.getStatus(), result.getRequest().getRequestURI());
        assertNotNull(response.getContentType()); assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
        var body = json.readTree(response.getContentAsString()); assertEquals(code, body.path("error_code").asText()); assertTrue(body.path("request_id").isString()); assertNull(response.getForwardedUrl());
    }
}
