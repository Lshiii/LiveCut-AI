package io.livecut.events;

import java.util.UUID;

import io.livecut.jobs.JobException;
import io.livecut.video.ApiErrorHandler.ApiError;
import io.livecut.video.VideoApiException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Explicit JSON content type preserves pre-stream errors even with Accept: text/event-stream. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TaskEventsController.class)
public class EventApiErrorHandler {
    @ExceptionHandler(JobException.class)
    ResponseEntity<ApiError> job(JobException failure, HttpServletResponse response) {
        return response.isCommitted() ? null : error(failure.getStatus(), failure.getCode(), failure.getMessage(), false);
    }
    @ExceptionHandler(VideoApiException.class)
    ResponseEntity<ApiError> video(VideoApiException failure, HttpServletResponse response) {
        return response.isCommitted() ? null : error(failure.getStatus(), failure.getCode(), failure.getMessage(), failure.getStatus().value() == 429 || failure.getStatus().is5xxServerError());
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> database(HttpServletResponse response) {
        return response.isCommitted() ? null : error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Persistent business state is temporarily unavailable", true);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(HttpServletResponse response) {
        return response.isCommitted() ? null : error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed", false);
    }
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, boolean retryable) {
        String id = UUID.randomUUID().toString();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).header("X-Request-ID", id)
                .body(new ApiError(code, message, retryable, id));
    }
}
