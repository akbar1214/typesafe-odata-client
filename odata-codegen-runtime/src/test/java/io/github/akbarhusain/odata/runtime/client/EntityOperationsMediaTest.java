package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class EntityOperationsMediaTest {

    static class FakeTransport implements HttpTransport {
        HttpRequest lastRequest;
        CompletableFuture<InputStream> streamResult = CompletableFuture.completedFuture(
                new ByteArrayInputStream("media-bytes".getBytes(StandardCharsets.UTF_8)));

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            return CompletableFuture.completedFuture(new HttpResponse(204, Map.of(), new byte[0]));
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            this.lastRequest = request;
            return streamResult;
        }
    }

    private Context context(FakeTransport transport) {
        return Context.builder().baseUrl("http://svc").transport(transport).build();
    }

    private static String header(HttpRequest request, String name) {
        List<String> values = request.headers().get(name);
        return values == null ? null : values.get(0);
    }

    @Test
    void streamMediaIssuesGetToValueSegment() throws Exception {
        FakeTransport transport = new FakeTransport();
        Context ctx = context(transport);
        ContextPath path = new ContextPath("http://svc").addSegment("Advertisements").addKey("ID", "1");

        InputStream is = EntityOperations.streamMedia(ctx, path.addSegment("$value"));

        assertEquals("media-bytes", new String(is.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(HttpMethod.GET, transport.lastRequest.method());
        assertTrue(transport.lastRequest.url().endsWith("/$value"),
                "media URL should end with /$value: " + transport.lastRequest.url());
        assertEquals("*/*", header(transport.lastRequest, "Accept"),
                "media stream should request raw bytes, not JSON");
    }

    @Test
    void putMediaIssuesPutWithContentTypeAndIfMatch() {
        FakeTransport transport = new FakeTransport();
        Context ctx = context(transport);
        ContextPath path = new ContextPath("http://svc").addSegment("Advertisements").addKey("ID", "1");
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

        EntityOperations.putMedia(ctx, path.addSegment("$value"), body, "image/png", "etag-9");

        assertEquals(HttpMethod.PUT, transport.lastRequest.method());
        assertTrue(transport.lastRequest.url().endsWith("/$value"));
        assertArrayEquals(body, transport.lastRequest.body());
        assertEquals("image/png", header(transport.lastRequest, "Content-Type"));
        assertEquals("etag-9", header(transport.lastRequest, "If-Match"));
    }

    @Test
    void putMediaDefaultsContentTypeAndOmitsIfMatchWhenAbsent() {
        FakeTransport transport = new FakeTransport();
        Context ctx = context(transport);
        ContextPath path = new ContextPath("http://svc").addSegment("Photos");

        EntityOperations.putMedia(ctx, path.addSegment("$value"), new byte[0], null, null);

        assertEquals("application/octet-stream", header(transport.lastRequest, "Content-Type"));
        assertNull(header(transport.lastRequest, "If-Match"));
    }
}
