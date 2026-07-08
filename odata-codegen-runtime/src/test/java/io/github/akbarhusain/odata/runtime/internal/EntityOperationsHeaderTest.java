package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class EntityOperationsHeaderTest {

    static final class CapturingTransport implements HttpTransport {
        HttpRequest captured;

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.captured = request;
            return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0]));
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            this.captured = request;
            return CompletableFuture.completedFuture(InputStream.nullInputStream());
        }
    }

    @Test
    void collidingHeaderKeysAreMergedNotThrown() {
        AuthProvider auth = () -> Map.of("X-Trace", "auth-value");
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder()
                .baseUrl("http://example.com")
                .transport(transport)
                .authProvider(auth)
                .build();

        EntityOperations.executeAsync(ctx, HttpMethod.GET,
                ctx.basePath().addSegment("People"), null,
                Map.of("X-Trace", "extra-value"));

        List<String> values = transport.captured.headers().get("X-Trace");
        assertNotNull(values, "X-Trace header should be present");
        assertTrue(values.contains("auth-value"), "auth header value should be retained");
        assertTrue(values.contains("extra-value"), "extra header value should be retained");
    }
}
