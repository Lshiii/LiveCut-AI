package io.livecut.video;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorHandler {
    public record ApiError(@JsonProperty("error_code") String errorCode, String message, boolean retryable,
                           @JsonProperty("request_id") String requestId) {}

    @ExceptionHandler(VideoApiException.class)
    ResponseEntity<ApiError> video(VideoApiException failure) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.getStatus());
        if (failure.getResourceLength() != null) {
            response.header(HttpHeaders.CONTENT_RANGE, "bytes */" + failure.getResourceLength());
        }
        return error(response, failure.getCode(), failure.getMessage(), false);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge() {
        return error(ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE), "VIDEO_TOO_LARGE", "Video exceeds the upload size limit", false);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<ApiError> multipart() {
        return error(ResponseEntity.badRequest(), "INVALID_MULTIPART", "Provide one MP4 file in multipart field file", false);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedType() {
        return error(ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE), "UNSUPPORTED_CONTENT_TYPE", "Use multipart/form-data for video upload", false);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> database() {
        return error(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE), "DATABASE_UNAVAILABLE", "Video registration is temporarily unavailable", true);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return error(ResponseEntity.status(HttpStatus.NOT_FOUND), "RESOURCE_NOT_FOUND", "Resource was not found", false);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected() {
        return error(ResponseEntity.internalServerError(), "INTERNAL_ERROR", "The request could not be completed", false);
    }

    private ResponseEntity<ApiError> error(ResponseEntity.BodyBuilder response, String code, String message, boolean retryable) {
        String requestId = UUID.randomUUID().toString();
        return response.header("X-Request-ID", requestId).body(new ApiError(code, message, retryable, requestId));
    }
}
