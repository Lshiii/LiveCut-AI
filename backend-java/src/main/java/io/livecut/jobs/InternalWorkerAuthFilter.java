package io.livecut.jobs;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import io.livecut.video.ApiErrorHandler.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Protects all internal worker endpoints, including model-call reservations. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalWorkerAuthFilter extends OncePerRequestFilter {
    private final byte[] expectedDigest;
    private final ObjectMapper json;

    public InternalWorkerAuthFilter(@Value("${livecut.worker.api-key:}") String key, ObjectMapper json) {
        this.expectedDigest = key != null && key.matches("[!-~]+") ? digest(key) : null;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Match the servlet's decoded route, not the raw URI: encoded letters
        // and matrix parameters must not bypass protection for the same MVC
        // handler (e.g. /%69nternal or /internal;version=1).
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            String raw = request.getRequestURI().substring(request.getContextPath().length());
            try { path = URI.create(raw).getPath(); }
            catch (IllegalArgumentException malformed) { return false; }
        }
        if (path == null) { return false; }
        path = path.replaceAll(";[^/]*", "");
        return !path.equals("/internal") && !path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (expectedDigest == null) {
            reject(response, 503, "WORKER_AUTH_NOT_CONFIGURED", "Internal worker authentication is not configured");
            return;
        }
        String supplied = request.getHeader("X-Worker-Key");
        // Hashes have a fixed 32-byte length before the constant-time compare.
        boolean valid = MessageDigest.isEqual(expectedDigest, digest(supplied == null ? "" : supplied));
        if (!valid) {
            reject(response, 401, "WORKER_UNAUTHORIZED", "Worker authentication failed");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        String requestId = UUID.randomUUID().toString();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Request-ID", requestId);
        json.writeValue(response.getOutputStream(), new ApiError(code, message, false, requestId));
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("JDK SHA-256 provider is required"); }
    }
}
