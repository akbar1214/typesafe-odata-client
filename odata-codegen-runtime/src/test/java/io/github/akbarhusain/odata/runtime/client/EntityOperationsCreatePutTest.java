package io.github.akbarhusain.odata.runtime.client;

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

class EntityOperationsCreatePutTest {

    static class CapturingTransport implements HttpTransport {
        HttpRequest lastRequest;
        private final int status;
        private final byte[] body;

        CapturingTransport(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            return CompletableFuture.completedFuture(new HttpResponse(status,
                    Map.of("Content-Type", List.of("application/json")), body));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private Context context(CapturingTransport transport) {
        return Context.builder().baseUrl("https://example.com").transport(transport).build();
    }

    private static String header(HttpRequest request, String name) {
        List<String> values = request.headers().get(name);
        return values == null ? null : values.get(0);
    }

    @Test
    void executePutEntitySendsPutWithSerializedBodyAndDeserializesResponse() {
        CapturingTransport transport = new CapturingTransport(200,
                "{\"UserName\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);
        ContextPath path = ctx.basePath().addSegment("People").addKey("UserName", "x");

        Map<String, Object> result = EntityOperations.executePutEntity(ctx, path, Map.of("UserName", "x"), Map.class);

        assertEquals(HttpMethod.PUT, transport.lastRequest.method());
        assertEquals("https://example.com/People('x')", transport.lastRequest.url());
        assertEquals("application/json", header(transport.lastRequest, "Content-Type"));
        assertTrue(new String(transport.lastRequest.body(), StandardCharsets.UTF_8).contains("\"UserName\":\"x\""),
                "PUT body should contain the serialized entity");
        assertEquals("x", result.get("UserName"));
    }

    @Test
    void executePutEntityWithETagSendsIfMatchHeader() {
        CapturingTransport transport = new CapturingTransport(200,
                "{\"UserName\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);
        ContextPath path = ctx.basePath().addSegment("People").addKey("UserName", "x");

        EntityOperations.executePutEntityWithETag(ctx, path, Map.of("UserName", "x"), Map.class, "etag-1");

        assertEquals(HttpMethod.PUT, transport.lastRequest.method());
        assertEquals("etag-1", header(transport.lastRequest, "If-Match"));
    }

    @Test
    void executePutEntityWithETagOmitsIfMatchWhenAbsent() {
        CapturingTransport transport = new CapturingTransport(200,
                "{\"UserName\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);
        ContextPath path = ctx.basePath().addSegment("People").addKey("UserName", "x");

        EntityOperations.executePutEntityWithETag(ctx, path, Map.of("UserName", "x"), Map.class, null);

        assertNull(header(transport.lastRequest, "If-Match"));
    }

    @Test
    void executePostEntitySendsPostAndDeserializesResponse() {
        CapturingTransport transport = new CapturingTransport(201,
                "{\"UserName\":\"newuser\"}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);
        ContextPath path = ctx.basePath().addSegment("People");

        Map<String, Object> result = EntityOperations.executePostEntity(ctx, path, Map.of("UserName", "newuser"), Map.class);

        assertEquals(HttpMethod.POST, transport.lastRequest.method());
        assertEquals("https://example.com/People", transport.lastRequest.url());
        assertEquals("application/json", header(transport.lastRequest, "Content-Type"));
        assertEquals("newuser", result.get("UserName"));
    }

    @Test
    void executePostEntityReturnsNullOnEmptyResponseBody() {
        // Some services return 204/empty body for POST. create() must not blow up
        // trying to deserialize an empty payload.
        CapturingTransport transport = new CapturingTransport(204, new byte[0]);
        Context ctx = context(transport);
        ContextPath path = ctx.basePath().addSegment("People");

        Map<String, Object> result = EntityOperations.executePostEntity(ctx, path, Map.of("UserName", "x"), Map.class);

        assertNull(result);
    }
}
