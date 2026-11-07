package io.livecut.captions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Set archive timeouts during the original Servlet dispatch, before it exits. */
@Configuration
public class CaptionDeliveryAsyncConfig implements WebMvcConfigurer {
    static final String PREPARED = CaptionDeliveryAsyncConfig.class.getName() + ".prepared";
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest request,
                    HttpServletResponse response, Object handler) {
                if (request.getAttribute(PREPARED) instanceof CaptionBundleWriter.Prepared
                        && request.isAsyncStarted()) request.getAsyncContext().setTimeout(600_000);
            }
            @Override public void afterCompletion(HttpServletRequest request,
                    HttpServletResponse response, Object handler, Exception exception) {
                if (request.getAttribute(PREPARED) instanceof CaptionBundleWriter.Prepared prepared) prepared.close();
            }
        }).addPathPatterns("/api/exports/*/package", "/api/exports/*/clips/*/captions/package");
    }
}
