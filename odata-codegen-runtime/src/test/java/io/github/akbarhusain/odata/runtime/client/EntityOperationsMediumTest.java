package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M14: startsWith("http") case-sensitive
 */
class EntityOperationsMediumTest {

    static class BodyCapture implements HttpTransport {
        String bodyStr;
        String url;
        @Override public CompletableFuture<HttpResponse> submit(HttpRequest req) {
            url = req.url();
            if (req.body() != null) bodyStr = new String(req.body());
            return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0]));
        }
        @Override public CompletableFuture<InputStream> stream(HttpRequest req) { return CompletableFuture.completedFuture(InputStream.nullInputStream()); }
    }

    static class UrlCapture implements HttpTransport {
        String url;
        @Override public CompletableFuture<HttpResponse> submit(HttpRequest req) {
            url = req.url();
            return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0]));
        }
        @Override public CompletableFuture<InputStream> stream(HttpRequest req) { return CompletableFuture.completedFuture(InputStream.nullInputStream()); }
    }

    @Test
    void m14_httpUpperCaseShouldBeTreatedAsAbsolute() {
        BodyCapture bc = new BodyCapture();
        Context ctx = Context.builder().baseUrl("https://example.com/service").transport(bc).build();
        var path = ctx.basePath().addSegment("People('1')").addSegment("Friends");
        EntityOperations.addRef(ctx, path, "HTTP://other.com/People('2')");
        assertNotNull(bc.bodyStr, "should have captured body");
        assertTrue(bc.bodyStr.contains("HTTP://other.com/People('2')"),
                "M14: HTTP uppercase should be treated as absolute, body: " + bc.bodyStr);
        assertFalse(bc.bodyStr.contains("https://example.com/service/HTTP"),
                "M14: should not prepend baseUrl for HTTP uppercase, body: " + bc.bodyStr);
    }

    @Test
    void m14_httpUpperCaseInRemoveRef() {
        UrlCapture uc = new UrlCapture();
        Context ctx = Context.builder().baseUrl("https://example.com/service").transport(uc).build();
        var path = ctx.basePath().addSegment("People('1')").addSegment("Friends");
        EntityOperations.removeRef(ctx, path, "HTTP://other.com/People('2')");
        String url = uc.url;
        assertNotNull(url, "removeRef should have been called");
        assertTrue(url.contains("HTTP://other.com") || url.contains("http://other.com"),
                "M14 removeRef HTTP uppercase should be absolute, got: " + url);
        assertFalse(url.contains("https://example.com/service/HTTP"),
                "M14: should not prepend baseUrl for HTTP uppercase, got: " + url);
    }

    @Test
    void m14_lowercaseHttpStillAbsolute() {
        BodyCapture bc = new BodyCapture();
        Context ctx = Context.builder().baseUrl("https://example.com/service").transport(bc).build();
        var path = ctx.basePath().addSegment("People('1')").addSegment("Friends");
        EntityOperations.addRef(ctx, path, "http://other.com/People('2')");
        assertTrue(bc.bodyStr.contains("http://other.com"), "lowercase should be absolute, body: " + bc.bodyStr);
    }

    @Test
    void m14_httpsUpperCase() {
        BodyCapture bc = new BodyCapture();
        Context ctx = Context.builder().baseUrl("https://example.com/service").transport(bc).build();
        var path = ctx.basePath().addSegment("People('1')").addSegment("Friends");
        EntityOperations.addRef(ctx, path, "HTTPS://other.com/People('2')");
        assertTrue(bc.bodyStr.contains("HTTPS://other.com"), "HTTPS uppercase should be absolute, body: " + bc.bodyStr);
    }
}
