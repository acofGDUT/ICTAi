package edu.whut.cs.bi.rag.service;

public class ResponsesApiException extends RuntimeException {
    private final Integer statusCode;

    public ResponsesApiException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ResponsesApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return statusCode == null || statusCode == 408 || statusCode == 409
                || statusCode == 429 || statusCode >= 500;
    }

    public boolean mayBeMissingPreviousResponse() {
        if (statusCode == null || (statusCode != 400 && statusCode != 404)) {
            return false;
        }
        String text = getMessage().toLowerCase();
        return text.contains("previous_response_id")
                || text.contains("previous response")
                || text.contains("not found")
                || text.contains("expired");
    }
}
