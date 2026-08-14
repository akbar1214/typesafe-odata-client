package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * H4: PATCH commonly returns 204 No Content (OData v4 11.4.3 allows it) and GET can
 * return 204 for gone entities. These paths must return null like POST/PUT do,
 * instead of failing to deserialize an empty payload.
 */
class EntityOperationsEmptyBodyTest {

    static class EmptyBodyTransport implements HttpTransport {
        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            return CompletableFuture.completedFuture(new HttpResponse(204, Map.of(), new byte[0]));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private Context context() {
        return Context.builder().baseUrl("https://example.com")
                .transport(new EmptyBodyTransport()).build();
    }

    @Test
    void executePatchEntityReturnsNullOnNoContent() {
        Context ctx = context();
        ContextPath path = ctx.basePath().addSegment("People").addKey("UserName", "x");
        assertNull(EntityOperations.executePatchEntity(ctx, path, Map.of("Age", 30), Map.class),
                "PATCH with 204/empty body must return null, not throw");
    }

    @Test
    void executePatchEntityWithETagReturnsNullOnNoContent() {
        Context ctx = context();
        ContextPath path = ctx.basePath().addSegment("People").addKey("UserName", "x");
        assertNull(EntityOperations.executePatchEntityWithETag(ctx, path, Map.of("Age", 30), Map.class, "etag-1"),
                "PATCH with If-Match and 204/empty body must return null, not throw");
    }

    @Test
    void executeAndGetEntityReturnsNullOnNoContent() {
        Context ctx = context();
        ContextPath path = ctx.basePath().addSegment("People").addKey("UserName", "x");
        assertNull(EntityOperations.executeAndGetEntity(ctx, path, Map.class),
                "GET returning 204/empty body must return null, not throw");
    }
}
