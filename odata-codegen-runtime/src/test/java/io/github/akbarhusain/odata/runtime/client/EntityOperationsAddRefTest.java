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

class EntityOperationsAddRefTest {

    static class FakeTransport implements HttpTransport {
        HttpRequest lastRequest;

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            return CompletableFuture.completedFuture(new HttpResponse(204, Map.of(), new byte[0]));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            return CompletableFuture.completedFuture(new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    private Context context(FakeTransport transport) {
        return Context.builder().baseUrl("http://svc").transport(transport).build();
    }

    @Test
    void addRefEscapesSpecialCharactersInTargetEntityUrl() {
        FakeTransport transport = new FakeTransport();
        Context ctx = context(transport);
        ContextPath path = new ContextPath("http://svc").addSegment("People").addSegment("Friends");

        // An OData string key may legitimately contain double quotes and backslashes.
        EntityOperations.addRef(ctx, path, "People('O\"Brien')");

        String body = new String(transport.lastRequest.body(), StandardCharsets.UTF_8);
        // The produced JSON must be valid: the quote inside the value must be escaped.
        assertTrue(body.contains("\\\""), "Value-internal double quote should be escaped: " + body);
        assertEquals("{\"@odata.id\":\"People('O\\\"Brien')\"}", body,
                "JSON body should be properly escaped");
    }

    @Test
    void addRefLeavesPlainUrlUnchanged() {
        FakeTransport transport = new FakeTransport();
        Context ctx = context(transport);
        ContextPath path = new ContextPath("http://svc").addSegment("People").addSegment("Friends");

        EntityOperations.addRef(ctx, path, "People('keithcombs')");

        String body = new String(transport.lastRequest.body(), StandardCharsets.UTF_8);
        assertEquals("{\"@odata.id\":\"People('keithcombs')\"}", body,
                "Plain URL should produce unchanged JSON body");
    }
}