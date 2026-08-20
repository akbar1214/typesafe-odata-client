package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H6: Batch double-slash — toRelativeUrl leading '/' + BatchRequest.resolveOperationUrl prepends another '/'.
 * Expected: https://svc/People('x'), actual (bug): https://svc//People('x')
 */
class BatchRequestDoubleSlashTest {

    static class CapturingTransport implements HttpTransport {
        HttpRequest lastRequest;
        String lastBodyAsString;

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            if (request.body() != null) {
                this.lastBodyAsString = new String(request.body(), StandardCharsets.UTF_8);
            }
            return CompletableFuture.completedFuture(
                    new HttpResponse(200,
                            Map.of("Content-Type", List.of("multipart/mixed; boundary=resp_b")),
                            ("--resp_b\r\nContent-Type: application/http\r\n\r\nHTTP/1.1 200 OK\r\n\r\n\r\n--resp_b--\r\n")
                                    .getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void batchWithLeadingSlashUrlDoesNotDoubleSlash() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://services.odata.org/V4/TripPinService").transport(transport).build();

        ctx.batch().add(BatchOperation.get("/People('scott')")).execute();

        assertNotNull(transport.lastRequest);
        assertNotNull(transport.lastBodyAsString, "batch body must be captured");
        // Body must contain absolute URL with single slash after service root, not double
        assertTrue(transport.lastBodyAsString.contains("https://services.odata.org/V4/TripPinService/People('scott')"),
                "H6: batch body should contain single-slash URL, got: " + transport.lastBodyAsString);
        assertFalse(transport.lastBodyAsString.contains("TripPinService//People"),
                "H6: double slash after service root: " + transport.lastBodyAsString);
    }

    @Test
    void batchWithContextPathRelativeUrlDoesNotDoubleSlash() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://services.odata.org/V4/TripPinService").transport(transport).build();

        ContextPath path = ctx.basePath().addSegment("People").addKey("UserName", "scott");
        String relative = path.toRelativeUrl(); // returns "People('scott')" (no leading slash)
        assertFalse(relative.startsWith("/"), "toRelativeUrl has no leading slash: " + relative);
        // Simulate a user who manually prefixes "/" (common mistake) — must still be single slash
        String withSlash = "/" + relative;

        ctx.batch().add(BatchOperation.get(withSlash)).execute();

        assertTrue(transport.lastBodyAsString.contains("https://services.odata.org/V4/TripPinService/People('scott')"),
                "H6: ContextPath-derived relative URL with manual '/' must not double-slash, body: " + transport.lastBodyAsString);
        assertFalse(transport.lastBodyAsString.contains("//People"),
                "H6: double slash: " + transport.lastBodyAsString);
    }

    @Test
    void batchWithoutLeadingSlashIsAlsoSingleSlash() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://services.odata.org/V4/TripPinService/").transport(transport).build();

        ctx.batch().add(BatchOperation.get("People('scott')")).execute();

        assertTrue(transport.lastBodyAsString.contains("https://services.odata.org/V4/TripPinService/People('scott')"),
                "trailing-slash baseUrl + no leading slash should still be single: " + transport.lastBodyAsString);
        assertFalse(transport.lastBodyAsString.contains("//People"),
                "H6: double slash: " + transport.lastBodyAsString);
    }
}
