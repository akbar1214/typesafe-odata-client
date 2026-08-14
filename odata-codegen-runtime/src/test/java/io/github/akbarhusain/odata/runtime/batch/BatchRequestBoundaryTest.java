package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M6: the response Content-Type boundary parameter may be quoted and
 * case-varying (BOUNDARY= vs boundary=) — both must be honored.
 */
class BatchRequestBoundaryTest {

    static class StubTransport implements HttpTransport {
        private final HttpResponse response;

        StubTransport(HttpResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private BatchResponse executeWithResponse(String contentTypeHeader, String bodyBoundary) {
        String body = "--" + bodyBoundary + "\r\n"
                + "Content-Type: application/http\r\n"
                + "\r\n"
                + "HTTP/1.1 200 OK\r\n"
                + "\r\n"
                + "{\"a\":1}\r\n"
                + "--" + bodyBoundary + "--\r\n";
        HttpResponse response = new HttpResponse(200,
                Map.of("Content-Type", List.of(contentTypeHeader)),
                body.getBytes(StandardCharsets.UTF_8));
        Context ctx = Context.builder().baseUrl("https://example.com")
                .transport(new StubTransport(response)).build();
        return ctx.batch().add(BatchOperation.get("People")).execute();
    }

    @Test
    void quotedBoundaryParameterIsHonored() {
        BatchResponse batch = executeWithResponse(
                "multipart/mixed; boundary=\"quoted_b\"", "quoted_b");
        assertEquals(1, batch.size(), "quoted boundary must be extracted and used");
    }

    @Test
    void caseInsensitiveBoundaryParameterIsHonored() {
        BatchResponse batch = executeWithResponse(
                "multipart/mixed; BOUNDARY=upper_b", "upper_b");
        assertEquals(1, batch.size(), "BOUNDARY= (upper case) must be recognized");
    }
}
