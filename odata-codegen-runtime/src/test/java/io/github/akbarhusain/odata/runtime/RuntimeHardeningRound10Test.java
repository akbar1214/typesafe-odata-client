package io.github.akbarhusain.odata.runtime;

import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.exception.RateLimitException;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two contracts the sync-over-async wrappers and the 429 mapper must hold regardless of
 * the value a hostile or buggy peer supplies.
 */
class RuntimeHardeningRound10Test {

    private static HttpTransport failing(CompletableFuture<HttpResponse> future) {
        return new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return future;
            }

            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                return future.thenApply(r -> {
                    throw new UnsupportedOperationException();
                });
            }
        };
    }

    private static Context contextFailingWith(CompletableFuture<HttpResponse> future) {
        return Context.builder()
                .baseUrl("https://example.com")
                .transport(failing(future))
                .build();
    }

    // ------------------------------------------------------------------
    // Interrupt flag restoration
    // ------------------------------------------------------------------

    @Test
    void batchExecuteRestoresTheInterruptFlagForTheTransportsOwnExceptionShape() {
        // JdkHttpTransport catches InterruptedException on its worker thread and rewraps
        // it as ODataException("HTTP request interrupted", e). So the CompletionException
        // cause reaching a sync wrapper in PRODUCTION is an ODataException, not an
        // InterruptedException -- a handler that only tests for the latter never fires,
        // and the calling thread continues as if it were never cancelled.
        Context ctx = contextFailingWith(CompletableFuture.failedFuture(
                new ODataException("HTTP request interrupted", new InterruptedException("cancelled"))));

        try {
            assertThrows(ODataException.class,
                    () -> ctx.batch().add(BatchOperation.get("People")).execute());
            assertTrue(Thread.currentThread().isInterrupted(),
                    "decision 160: the sync wrapper must restore the flag on the CALLING thread");
        } finally {
            Thread.interrupted(); // clear so the assertion above cannot leak into later tests
        }
    }

    @Test
    void entityOperationsExecuteSyncRestoresTheInterruptFlagForTheSameShape() {
        // The sibling that already had both branches -- BatchRequest is the call site that
        // missed the second one, so this test pins the parity rather than the defect.
        Context ctx = contextFailingWith(CompletableFuture.failedFuture(
                new ODataException("HTTP request interrupted", new InterruptedException("cancelled"))));

        try {
            assertThrows(ODataException.class, () -> EntityOperations.executeSync(
                    ctx, io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                    ctx.basePath().addSegment("People"), null, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void batchExecuteStillRestoresTheFlagForARawInterruptedException() {
        Context ctx = contextFailingWith(CompletableFuture.failedFuture(
                new InterruptedException("cancelled")));
        try {
            assertThrows(ODataException.class,
                    () -> ctx.batch().add(BatchOperation.get("People")).execute());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void batchExecuteDoesNotSetTheFlagForAnOrdinaryFailure() {
        // Guard against over-correcting into "always interrupt on any exception".
        Context ctx = contextFailingWith(CompletableFuture.failedFuture(
                new ODataException("HTTP 503", null)));
        assertThrows(ODataException.class,
                () -> ctx.batch().add(BatchOperation.get("People")).execute());
        assertFalse(Thread.currentThread().isInterrupted(),
                "an ordinary transport failure is not a cancellation");
    }

    // ------------------------------------------------------------------
    // 429 Retry-After parsing
    // ------------------------------------------------------------------

    private static HttpResponse tooManyRequests(String retryAfter) {
        return new HttpResponse(429,
                Map.of("Retry-After", List.of(retryAfter),
                        "Content-Type", List.of("application/json")),
                ("{\"error\":{\"code\":\"throttled\",\"message\":\"slow down\"}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** {@code fromResponse} RETURNS the mapped exception; it does not throw it. */
    private static RateLimitException rateLimitFor(HttpResponse response) {
        ODataException mapped = ODataException.fromResponse(response);
        assertTrue(mapped instanceof RateLimitException,
                "429 must map to RateLimitException, got " + mapped.getClass().getName());
        return (RateLimitException) mapped;
    }

    @Test
    void aDelayNearLongMaxDoesNotEscapeAsAnUntypedArithmeticException() {
        // Instant.plusSeconds adds with Math.addExact, so this overflows. Catching only
        // NumberFormatException let ArithmeticException out of the 429 constructor and
        // past ODataException.fromResponse: a caller catching RateLimitException never
        // fired and the parsed OData error was lost.
        RateLimitException error = rateLimitFor(tooManyRequests("9223372036854775807"));
        assertFalse(error.hasServerRetryAfter(),
                "an unusable directive degrades to the client default, not an exception");
        assertNotNull(error.getRetryAfter());
        assertNotNull(error.getError(), "the parsed OData error must survive");
        assertEquals("throttled", error.getError().getCode());
    }

    @Test
    void aNegativeDelayNearLongMinDoesNotEscapeAsADateTimeException() {
        RateLimitException error = rateLimitFor(tooManyRequests("-9223372036854775808"));
        assertFalse(error.hasServerRetryAfter());
        assertNotNull(error.getError());
    }

    @Test
    void anOrdinaryDelayIsStillHonoured() {
        // The regression witness: the fix must not turn every parse into the fallback.
        RateLimitException error = rateLimitFor(tooManyRequests("120"));
        assertTrue(error.hasServerRetryAfter(), "a plain delay-seconds value is server-specified");
        assertTrue(error.getRetryAfter().isAfter(java.time.Instant.now()));
    }

    @Test
    void anHttpDateIsStillHonoured() {
        RateLimitException error = rateLimitFor(tooManyRequests("Wed, 21 Oct 2099 07:28:00 GMT"));
        assertTrue(error.hasServerRetryAfter());
        assertEquals(2099, error.getRetryAfter().atZone(java.time.ZoneOffset.UTC).getYear());
    }

    @Test
    void aNonNumericValueDegradesToTheClientDefault() {
        RateLimitException error = rateLimitFor(tooManyRequests("soon"));
        assertFalse(error.hasServerRetryAfter());
    }

    @Test
    void aMissingHeaderDegradesToTheClientDefault() {
        HttpResponse response = new HttpResponse(429, Map.of(),
                "{\"error\":{\"code\":\"throttled\"}}".getBytes(StandardCharsets.UTF_8));
        RateLimitException error = rateLimitFor(response);
        assertFalse(error.hasServerRetryAfter());
        assertNull(response.headers().get("Retry-After"));
    }
}
