package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

import java.time.Instant;

public class RateLimitException extends ODataException {

    private final Instant retryAfter;

    public RateLimitException(HttpResponse response) {
        super(429, "Rate limit exceeded: " + response.getText(), ODataError.fromResponse(response));
        this.retryAfter = parseRetryAfter(response);
    }

    public RateLimitException(String message, Instant retryAfter) {
        super(429, message);
        this.retryAfter = retryAfter;
    }

    public RateLimitException(String message, Instant retryAfter, ODataError error) {
        super(429, message, error);
        this.retryAfter = retryAfter;
    }

    public Instant getRetryAfter() {
        return retryAfter;
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
