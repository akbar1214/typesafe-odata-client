package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Thrown on HTTP 429 with the server's retry directive when one was sent.
 *
 * <p>The runtime never retries automatically — honoring {@code Retry-After} is an
 * {@code HttpInterceptor} away. The contract this class carries is everything such
 * an interceptor needs: {@link #getRetryAfter()} is the instant to wait for, and
 * {@link #hasServerRetryAfter()} tells a fabricated client-side default apart
 * from a real server directive (don't treat them identically — see
 * {@code RetryAfterPatternTest} for the proven pattern: catch, sleep until
 * {@code getRetryAfter()}, resubmit).</p>
 */
public class RateLimitException extends ODataException {

    private static final System.Logger LOG = System.getLogger(RateLimitException.class.getName());

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final Instant retryAfter;
    private final boolean serverSpecified;

    public RateLimitException(HttpResponse response) {
        super(429, "Rate limit exceeded: " + response.getText(), ODataError.fromResponse(response));
        Instant parsed = parseServerRetryAfter(response);
        this.retryAfter = parsed != null ? parsed : Instant.now().plusSeconds(60);
        this.serverSpecified = parsed != null;
        if (parsed == null) {
            LOG.log(System.Logger.Level.DEBUG,
                    "No parseable Retry-After header; getRetryAfter() returns a fabricated now+60s default");
        }
    }

    public RateLimitException(String message, Instant retryAfter) {
        super(429, message);
        this.retryAfter = retryAfter;
        this.serverSpecified = retryAfter != null;
    }

    public RateLimitException(String message, Instant retryAfter, ODataError error) {
        super(429, message, error);
        this.retryAfter = retryAfter;
        this.serverSpecified = retryAfter != null;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }

    /**
     * Whether the server actually sent a {@code Retry-After} header. When false,
     * {@link #getRetryAfter()} returns a client-side default of now + 60s — callers
     * honoring it unconditionally may back off longer than the server would require.
     */
    public boolean hasServerRetryAfter() {
        return serverSpecified;
    }

    /** Returns the server-specified retry instant, or null when absent/unparseable. */
    private static Instant parseServerRetryAfter(HttpResponse response) {
        var retryAfterHeader = response.headers().get("Retry-After");
        if (retryAfterHeader == null || retryAfterHeader.isEmpty()) {
            return null;
        }
        String value = retryAfterHeader.get(0);
        // RFC 9110 allows delay-seconds or HTTP-date (IMF-fixdate)
        try {
            return ZonedDateTime.parse(value, HTTP_DATE).toInstant();
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return Instant.now().plusSeconds(Long.parseLong(value));
        } catch (NumberFormatException ignore) {
            return null; // unparseable header value — treat as absent
        }
    }
}
