package io.livecut.video;

import org.springframework.http.HttpStatus;

public class VideoApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Long resourceLength;

    public VideoApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public VideoApiException(HttpStatus status, String code, String message, Long resourceLength) {
        super(message);
        this.status = status;
        this.code = code;
        this.resourceLength = resourceLength;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Long getResourceLength() { return resourceLength; }
}
