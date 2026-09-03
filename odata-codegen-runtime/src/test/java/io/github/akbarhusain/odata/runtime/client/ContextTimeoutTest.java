package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch B4): connect/read timeouts are hardcoded (30s/60s, 30s/120s
 * for batch) with no Context-level override — slow aggregations and large
 * $expands hit a wall users cannot move without a custom transport.
 */
class ContextTimeoutTest {

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
            return CompletableFuture.completedFuture(
                    new ByteArrayInputStream(new byte[0]));
        }
    }

    private ContextPath people(Context ctx) {
        return ctx.basePath().addSegment("People");
    }

    @Test
    void defaultTimeoutsAre30And60() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport).build();

        EntityOperations.executeAsync(ctx, HttpMethod.GET, people(ctx), null, null).join();

        assertEquals(Duration.ofSeconds(30), transport.lastRequest.connectTimeout());
        assertEquals(Duration.ofSeconds(60), transport.lastRequest.readTimeout());
    }

    @Test
    void customTimeoutsFlowToRequests() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport)
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(90))
                .build();

        EntityOperations.executeAsync(ctx, HttpMethod.GET, people(ctx), null, null).join();

        assertEquals(Duration.ofSeconds(5), transport.lastRequest.connectTimeout(),
                "Context connectTimeout must reach the request");
        assertEquals(Duration.ofSeconds(90), transport.lastRequest.readTimeout(),
                "Context readTimeout must reach the request");
    }

    @Test
    void streamMediaHonorsContextTimeouts() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport)
                .connectTimeout(Duration.ofSeconds(7))
                .readTimeout(Duration.ofSeconds(77))
                .build();

        EntityOperations.streamMediaAsync(ctx, people(ctx)).join();

        assertEquals(Duration.ofSeconds(7), transport.lastRequest.connectTimeout());
        assertEquals(Duration.ofSeconds(77), transport.lastRequest.readTimeout());
    }

    @Test
    void batchHonorsContextTimeouts() {
        CapturingTransport transport = new CapturingTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                this.lastRequest = request;
                return CompletableFuture.completedFuture(new HttpResponse(200,
                        Map.of("Content-Type", List.of("multipart/mixed; boundary=resp_b")),
                        "--resp_b\r\nContent-Type: application/http\r\n\r\nHTTP/1.1 200 OK\r\n\r\n\r\n--resp_b--\r\n"
                                .getBytes(StandardCharsets.UTF_8)));
            }
        };
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport)
                .connectTimeout(Duration.ofSeconds(11))
                .readTimeout(Duration.ofSeconds(111))
                .build();

        ctx.batch().add(io.github.akbarhusain.odata.runtime.batch.BatchOperation.get("People")).execute();

        assertEquals(Duration.ofSeconds(11), transport.lastRequest.connectTimeout());
        assertEquals(Duration.ofSeconds(111), transport.lastRequest.readTimeout());
    }

    @Test
    void blankTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Context.builder().baseUrl("https://example.com")
                        .connectTimeout(Duration.ofSeconds(-1)).build());
        assertThrows(IllegalArgumentException.class,
                () -> Context.builder().baseUrl("https://example.com")
                        .readTimeout(null).build());
    }
}
