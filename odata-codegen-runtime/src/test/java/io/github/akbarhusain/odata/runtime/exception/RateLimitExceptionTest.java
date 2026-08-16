package io.github.akbarhusain.odata.runtime.exception;

import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitExceptionTest {

    @Test
    void retryAfterFromLowercaseHeader() {
        Instant now = Instant.now();
        String retryAfter = now.plusSeconds(120).toString();
        HttpResponse response = new HttpResponse(429,
                Map.of("retry-after", List.of(retryAfter)),
                "Rate limited".getBytes());

        RateLimitException ex = (RateLimitException) ODataException.fromResponse(response);
        assertEquals(now.plusSeconds(120), ex.getRetryAfter());
    }

    @Test
    void retryAfterFromUppercaseHeader() {
        Instant now = Instant.now();
        String retryAfter = now.plusSeconds(90).toString();
        HttpResponse response = new HttpResponse(429,
                Map.of("Retry-After", List.of(retryAfter)),
                "Rate limited".getBytes());

        RateLimitException ex = (RateLimitException) ODataException.fromResponse(response);
        assertEquals(now.plusSeconds(90), ex.getRetryAfter());
    }

    @Test
    void retryAfterFromDeltaSeconds() {
        HttpResponse response = new HttpResponse(429,
                Map.of("retry-after", List.of("30")),
                "Rate limited".getBytes());

        RateLimitException ex = (RateLimitException) ODataException.fromResponse(response);
        Instant expected = Instant.now().plusSeconds(30);
        Instant actual = ex.getRetryAfter();
        assertNotNull(actual);
        assertTrue(actual.isAfter(expected.minusSeconds(5)) && actual.isBefore(expected.plusSeconds(5)),
                "Expected retryAfter within 5s of " + expected + " but got " + actual);
    }

    @Test
    void retryAfterFallsBackToDefaultWhenMissing() {
        HttpResponse response = new HttpResponse(429, Map.of(), "Rate limited".getBytes());

        RateLimitException ex = (RateLimitException) ODataException.fromResponse(response);
        Instant expected = Instant.now().plusSeconds(60);
        Instant actual = ex.getRetryAfter();
        assertNotNull(actual);
        assertTrue(actual.isAfter(expected.minusSeconds(5)) && actual.isBefore(expected.plusSeconds(5)),
                "Expected fallback within 5s of " + expected + " but got " + actual);
    }

    @Test
    void m15HttpDateRetryAfterIsParsed() {
        HttpResponse response = new HttpResponse(429,
                Map.of("Retry-After", java.util.List.of("Wed, 21 Oct 2015 07:28:00 GMT")),
                new byte[0]);
        RateLimitException ex = new RateLimitException(response);

        assertEquals(java.time.Instant.parse("2015-10-21T07:28:00Z"), ex.getRetryAfter(),
                "RFC 9110 HTTP-date Retry-After values must be parsed");
        assertTrue(ex.hasServerRetryAfter());
    }

    @Test
    void m15AbsentRetryAfterReportsDefaultNotServerSpecified() {
        HttpResponse response = new HttpResponse(429, Map.of(), new byte[0]);
        RateLimitException ex = new RateLimitException(response);

        assertNotNull(ex.getRetryAfter(), "the 60s default remains for backward compatibility");
        assertFalse(ex.hasServerRetryAfter(),
                "callers must be able to distinguish a server-specified from a fabricated retry-after");
    }
}
