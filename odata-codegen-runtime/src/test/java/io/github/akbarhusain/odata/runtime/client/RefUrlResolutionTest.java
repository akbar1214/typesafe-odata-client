package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
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
 * $ref add/remove must send ABSOLUTE entity URIs: services reject relative
 * {@code @odata.id} payloads (TripPin: 500 "relative URI value ... odata.context
 * annotation is missing") and resolve the {@code $id} query parameter as a URI too.
 * The runtime resolves entity paths against the service root, like batch does.
 */
class RefUrlResolutionTest {

    static class CapturingTransport implements HttpTransport {
        HttpRequest lastRequest;

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            return CompletableFuture.completedFuture(new HttpResponse(204, Map.of(), new byte[0]));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private Context context(CapturingTransport transport) {
        return Context.builder().baseUrl("https://example.com/service/").transport(transport).build();
    }

    @Test
    void addRefResolvesRelativeEntityPathToAbsoluteODataId() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = context(transport);
        ContextPath nav = ctx.basePath().addSegment("People").addKey("UserName", "scott")
                .addSegment("Friends");

        EntityOperations.addRef(ctx, nav, "People('keith')");

        String body = new String(transport.lastRequest.body(), StandardCharsets.UTF_8);
        assertEquals("{\"@odata.id\":\"https://example.com/service/People('keith')\"}", body,
                "relative entity paths must resolve against the service root (no double slash)");
        assertEquals("https://example.com/service/People('scott')/Friends/$ref",
                transport.lastRequest.url());
    }

    @Test
    void addRefKeepsAbsoluteUrlAsIs() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = context(transport);
        ContextPath nav = ctx.basePath().addSegment("People").addKey("UserName", "scott")
                .addSegment("Friends");

        EntityOperations.addRef(ctx, nav, "https://other.example.org/People('keith')");

        String body = new String(transport.lastRequest.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("https://other.example.org/People('keith')"),
                "already-absolute URIs pass through unchanged: " + body);
    }

    @Test
    void removeRefResolvesEntityPathInDollarId() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = context(transport);
        ContextPath nav = ctx.basePath().addSegment("People").addKey("UserName", "scott")
                .addSegment("Friends");

        EntityOperations.removeRef(ctx, nav, "People('keith')");

        // encodeQueryParam restores OData-safe characters (:, /, ', (, )) verbatim
        assertEquals("https://example.com/service/People('scott')/Friends/$ref"
                        + "?$id=https://example.com/service/People('keith')",
                transport.lastRequest.url(),
                "entity-path $id values must be absolute");
    }

    @Test
    void removeRefKeepsAbsoluteAndBareKeyValues() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = context(transport);
        ContextPath nav = ctx.basePath().addSegment("People").addKey("UserName", "scott")
                .addSegment("Friends");

        EntityOperations.removeRef(ctx, nav, "https://other.example.org/People('keith')");
        assertTrue(transport.lastRequest.url().contains("$id=https://other.example.org/People"),
                "absolute $id unchanged");

        EntityOperations.removeRef(ctx, nav, "keith");
        assertTrue(transport.lastRequest.url().endsWith("$id=keith"),
                "bare key values pass through for services that accept them");
    }
}
