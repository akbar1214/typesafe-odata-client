package com.modernodata.runtime.exception;

import com.modernodata.runtime.http.HttpResponse;

import java.time.Instant;

public class RateLimitException extends ODataException {

    private final Instant retryAfter;
    private final ODataError error;

    public RateLimitException(HttpResponse response) {
        super(429, "Rate limit exceeded: " + response.getText());
        this.retryAfter = parseRetryAfter(response);
        this.error = ODataError.fromResponse(response);
    }

    public RateLimitException(String message, Instant retryAfter) {
        super(429, message);
        this.retryAfter = retryAfter;
        this.error = null;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }

    public ODataError getError() {
        return error;
    }

    private static Instant parseRetryAfter(HttpResponse response) {
        var retryAfterHeader = response.headers().get("Retry-After");
        if (retryAfterHeader != null && !retryAfterHeader.isEmpty()) {
            String value = retryAfterHeader.get(0);
            try {
                return Instant.parse(value);
            } catch (Exception e) {
                try {
                    long seconds = Long.parseLong(value);
                    return Instant.now().plusSeconds(seconds);
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            }
        }
        return Instant.now().plusSeconds(60);
    }
}
