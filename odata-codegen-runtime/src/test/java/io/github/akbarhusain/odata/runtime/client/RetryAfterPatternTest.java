package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.exception.RateLimitException;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract pin for the documented retry pattern (Batch C11): nothing in the
 * runtime retries automatically, but {@link RateLimitException} carries
 * everything an {@code HttpInterceptor} needs — {@code getRetryAfter()} with
 * {@code hasServerRetryAfter()} distinguishing server directive from the
 * fabricated default. Proves the pattern end to end: 429-with-Retry-After once,
 * then success.
 */
class RetryAfterPatternTest {

    static class FlakyTransport implements HttpTransport {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            if (calls.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(new HttpResponse(429,
                        Map.of("Retry-After", List.of("0"),
                                "Content-Type", List.of("application/json")),
                        "{\"error\":{\"code\":\"429\",\"message\":\"throttled\"}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            return CompletableFuture.completedFuture(new HttpResponse(200,
                    Map.of("Content-Type", List.of("application/json")),
                    "{\"value\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void retryAfterContractSupportsInterceptorRetries() {
        FlakyTransport transport = new FlakyTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport).build();
        ContextPath path = ctx.basePath().addSegment("People");

        // First attempt surfaces the typed exception with the server's directive.
        RateLimitException limited = assertThrows(RateLimitException.class,
                () -> EntityOperations.executeAndGetCollection(ctx, path, Map.class));
        assertTrue(limited.hasServerRetryAfter(),
                "server-sent Retry-After must be distinguished from the default");
        assertFalse(limited.getRetryAfter().isAfter(Instant.now().plusSeconds(5)),
                "Retry-After: 0 must be imminent, was " + limited.getRetryAfter());

        // The documented pattern: wait until getRetryAfter(), then resubmit.
        Duration wait = Duration.between(Instant.now(), limited.getRetryAfter());
        assertTrue(wait.isNegative() || wait.getSeconds() < 5);

        var page = EntityOperations.executeAndGetCollection(ctx, path, Map.class);
        assertNotNull(page);
        assertEquals(2, transport.calls.get(), "exactly one retry after the 429");
    }
}
