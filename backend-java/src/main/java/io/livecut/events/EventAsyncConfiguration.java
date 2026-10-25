package io.livecut.events;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class EventAsyncConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Object value = request.getAttribute(TaskEventsController.CONNECTION_ATTRIBUTE);
                if (value instanceof TaskEventsService.Connection connection) connection.start();
            }
        }).addPathPatterns("/api/jobs/*/events", "/api/exports/*/events");
    }
}
