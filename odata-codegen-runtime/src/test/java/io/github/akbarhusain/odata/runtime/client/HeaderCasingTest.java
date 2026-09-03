package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch B7): header merging is case-sensitive — auth
 * "authorization" plus an extra "Authorization" are sent as TWO headers (JDK
 * joins them "v1, v2" and auth breaks). BatchResult.getHeader has the same
 * class of bug: case-sensitive lookup on an unnormalized map.
 */
class HeaderCasingTest {

    static class CapturingTransport implements HttpTransport {
        HttpRequest lastRequest;

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            return CompletableFuture.completedFuture(new HttpResponse(200,
                    Map.of("Content-Type", List.of("application/json")), new byte[0]));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            this.lastRequest = request;
            throw new UnsupportedOperationException("fake cannot stream");
        }
    }

    @Test
    void extraHeadersMergeCaseInsensitively() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport)
                .authProvider(() -> Map.of("authorization", "Bearer token"))
                .build();
        ContextPath path = ctx.basePath().addSegment("People");

        EntityOperations.executeAsync(ctx, HttpMethod.GET, path, null,
                Map.of("Authorization", "extra")).join();

        long authKeys = transport.lastRequest.headers().keySet().stream()
                .filter(k -> k.equalsIgnoreCase("authorization"))
                .count();
        assertEquals(1, authKeys, "one Authorization header expected, got: "
                + transport.lastRequest.headers());
        List<String> values = transport.lastRequest.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("authorization"))
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertEquals(List.of("Bearer token", "extra"), values);
    }

    @Test
    void streamMediaAcceptWinsCaseInsensitively() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport)
                .authProvider(() -> Map.of("accept", "application/json"))
                .build();

        // The fake throws synchronously, but only after the request was assembled
        // and captured — that is the assertion target.
        assertThrows(UnsupportedOperationException.class,
                () -> EntityOperations.streamMediaAsync(ctx, ctx.basePath().addSegment("Ads")));

        long acceptKeys = transport.lastRequest.headers().keySet().stream()
                .filter(k -> k.equalsIgnoreCase("accept"))
                .count();
        assertEquals(1, acceptKeys, "one Accept header expected, got: "
                + transport.lastRequest.headers());
    }

    @Test
    void batchResultHeaderLookupIsCaseInsensitive() {
        BatchResult<Map> result = new BatchResult<>(200,
                Map.of("retry-after", List.of("5")),
                "{}".getBytes(StandardCharsets.UTF_8), Map.class, null);

        assertEquals("5", result.getHeader("Retry-After"));
        assertEquals("5", result.getHeader("RETRY-AFTER"));
        assertEquals("5", result.getHeader("retry-after"));
    }
}
