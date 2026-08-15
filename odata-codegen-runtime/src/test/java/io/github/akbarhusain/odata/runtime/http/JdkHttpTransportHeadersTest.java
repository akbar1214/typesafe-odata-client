package io.github.akbarhusain.odata.runtime.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1: the transport advertises OData-MaxVersion 4.01 (the client emits 4.01 payload
 * forms such as the {@code @odata.id} control URL). L6: request building is shared
 * between submit() and stream(), and the Accept default is skipped case-insensitively —
 * a caller-set lowercase {@code accept} must not get a second {@code Accept} appended.
 */
class JdkHttpTransportHeadersTest {

    @Test
    void maxVersionHeaderAndCaseInsensitiveAccept() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        final Map<String, List<String>>[] captured = new Map[1];
        server.createContext("/People", exchange -> {
            captured[0] = exchange.getRequestHeaders();
            byte[] body = "{\"value\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            JdkHttpTransport transport = new JdkHttpTransport();

            // lowercase accept must be honored — no duplicate Accept header
            HttpRequest request = HttpRequest.builder()
                    .method(HttpMethod.GET)
                    .url("http://localhost:" + server.getAddress().getPort() + "/People")
                    .header("accept", "application/json;odata.metadata=minimal")
                    .build();
            CompletableFuture<HttpResponse> future = transport.submit(request);
            future.join();

            assertNotNull(captured[0]);
            List<String> accept = captured[0].get("Accept");
            assertEquals(1, accept.size(), "exactly one Accept header expected: " + accept);
            assertEquals("application/json;odata.metadata=minimal", accept.get(0));
            assertEquals(List.of("4.01"), captured[0].get("OData-MaxVersion"),
                    "client emits 4.01 payload forms, so it must advertise 4.01");
            assertEquals(List.of("4.0"), captured[0].get("OData-Version"));
        } finally {
            server.stop(0);
        }
    }
}
