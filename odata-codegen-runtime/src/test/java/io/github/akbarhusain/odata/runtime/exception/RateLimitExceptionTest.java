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
}
