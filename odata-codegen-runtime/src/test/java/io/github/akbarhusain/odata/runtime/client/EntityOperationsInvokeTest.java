package io.github.akbarhusain.odata.runtime.client;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.exception.NotFoundException;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
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

class EntityOperationsInvokeTest {

    static class CapturingTransport implements HttpTransport {
        HttpRequest lastRequest;
        private final int status;
        private final byte[] body;
        private final boolean failFuture;

        CapturingTransport(int status, byte[] body) {
            this(status, body, false);
        }

        CapturingTransport(int status, byte[] body, boolean failFuture) {
            this.status = status;
            this.body = body;
            this.failFuture = failFuture;
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            if (failFuture) {
                return CompletableFuture.failedFuture(new InterruptedException("executor shutdown"));
            }
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

    private ContextPath invocationPath(Context ctx, String segment) {
        return ctx.basePath().addSegment(segment);
    }

    // ---- Function invocations (GET, entity result) ----

    @Test
    void invokeFunctionSendsGetAndDeserializesEntityResult() {
        CapturingTransport transport = new CapturingTransport(200,
                "{\"IcaoCode\":\"KSEA\"}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);

        Map<?, ?> result = EntityOperations.invokeSync(ctx,
                invocationPath(ctx, "GetNearestAirport(lat=47.61357,lon=-122.19375)"),
                HttpMethod.GET, null, Map.class);

        assertEquals(HttpMethod.GET, transport.lastRequest.method());
        assertEquals("https://example.com/GetNearestAirport(lat=47.61357,lon=-122.19375)",
                transport.lastRequest.url());
        assertEquals("KSEA", result.get("IcaoCode"));
    }

    // ---- Action invocations (POST, JSON body) ----

    @Test
    void invokeActionSendsPostWithJsonBodyAndDeserializesResult() {
        CapturingTransport transport = new CapturingTransport(200,
                "{\"TripId\":7}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);
        byte[] requestBody = "{\"userName\":\"scott\",\"tripId\":42}".getBytes(StandardCharsets.UTF_8);

        Map<?, ?> result = EntityOperations.invokeSync(ctx,
                invocationPath(ctx, "ShareTrip"), HttpMethod.POST, requestBody, Map.class);

        assertEquals(HttpMethod.POST, transport.lastRequest.method());
        assertEquals("https://example.com/ShareTrip", transport.lastRequest.url());
        assertEquals("application/json", header(transport.lastRequest, "Content-Type"));
        assertArrayEquals(requestBody, transport.lastRequest.body());
        assertEquals(7, result.get("TripId"));
    }

    @Test
    void invokeActionWithoutBodySendsNoContentTypeAndReturnsNullOnEmptyResponse() {
        CapturingTransport transport = new CapturingTransport(204, new byte[0]);
        Context ctx = context(transport);

        Map<?, ?> result = EntityOperations.invokeSync(ctx,
                invocationPath(ctx, "ResetDataSource"), HttpMethod.POST, null, Map.class);

        assertNull(header(transport.lastRequest, "Content-Type"));
        assertNull(result);
    }

    // ---- Primitive results ----

    @Test
    void invokePrimitiveUnwrapsValueWrapperObject() {
        CapturingTransport transport = new CapturingTransport(200,
                "{\"value\":\"hello world\"}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);

        String result = EntityOperations.invokePrimitiveSync(ctx,
                invocationPath(ctx, "GetGreeting(name=%27Bob%27)"),
                HttpMethod.GET, null, String.class);

        assertEquals("hello world", result);
    }

    @Test
    void invokePrimitiveAcceptsBareLiteralRoot() {
        CapturingTransport transport = new CapturingTransport(200,
                "42".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);

        Integer result = EntityOperations.invokePrimitiveSync(ctx,
                invocationPath(ctx, "GetCount"), HttpMethod.GET, null, Integer.class);

        assertEquals(42, result);
    }

    @Test
    void invokePrimitiveCollectionUnwrapsValueArrayToTypedList() {
        CapturingTransport transport = new CapturingTransport(200,
                "{\"value\":[\"a\",\"b\"]}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);

        List<?> result = EntityOperations.invokePrimitiveCollectionSync(ctx,
                invocationPath(ctx, "GetTags"), HttpMethod.GET, null, String.class);

        assertEquals(List.of("a", "b"), result);
    }

    @Test
    void invokePrimitiveReturnsNullOnEmptyResponseBody() {
        CapturingTransport transport = new CapturingTransport(204, new byte[0]);
        Context ctx = context(transport);

        String result = EntityOperations.invokePrimitiveSync(ctx,
                invocationPath(ctx, "GetNothing"), HttpMethod.GET, null, String.class);

        assertNull(result);
    }

    // ---- Typed error surfacing ----

    @Test
    void typedErrorStatusSurfacesThroughInvocation() {
        CapturingTransport transport = new CapturingTransport(404,
                ("{\"error\":{\"code\":\"ResourceNotFound\",\"message\":\"not found here\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);

        NotFoundException ex = assertThrows(NotFoundException.class, () ->
                EntityOperations.invokeSync(ctx, invocationPath(ctx, "Missing"), HttpMethod.GET, null, Map.class));
        assertNotNull(ex.getError());
        assertEquals("not found here", ex.getError().getMessage());
    }

    @Test
    void typedErrorStatusSurfacesThroughPrimitiveInvocation() {
        CapturingTransport transport = new CapturingTransport(500,
                ("{\"error\":{\"code\":\"Internal\",\"message\":\"boom\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);

        ODataException ex = assertThrows(ODataException.class, () ->
                EntityOperations.invokePrimitiveSync(ctx, invocationPath(ctx, "Broken"),
                        HttpMethod.GET, null, String.class));
        assertTrue(ex.getMessage().contains("boom") || (ex.getError() != null
                && ex.getError().getMessage().equals("boom")));
    }

    // ---- Interruption contract (lesson 160) ----

    @Test
    void interruptedAsyncTaskRestoresInterruptFlagOnCallingThread() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, new byte[0], true);
        Context ctx = context(transport);

        try {
            EntityOperations.invokeSync(ctx, invocationPath(ctx, "People"), HttpMethod.GET, null, Map.class);
            fail("expected ODataException");
        } catch (ODataException expected) {
            assertTrue(Thread.currentThread().isInterrupted(),
                    "interrupt flag must be restored on the calling thread");
            assertEquals(ODataException.class, expected.getClass());
        } finally {
            Thread.interrupted(); // clear flag for other tests
        }
    }

    // ---- Async parity ----

    @Test
    void invokeAsyncCompletesWithDeserializedResult() throws Exception {
        CapturingTransport transport = new CapturingTransport(200,
                "{\"IcaoCode\":\"KLAX\"}".getBytes(StandardCharsets.UTF_8));
        Context ctx = context(transport);

        CompletableFuture<Map> future = EntityOperations.invokeAsync(ctx,
                invocationPath(ctx, "GetNearestAirport(lat=34.05,lon=-118.24)"),
                HttpMethod.GET, null, Map.class);

        Map<?, ?> result = future.join();
        assertEquals("KLAX", result.get("IcaoCode"));
    }

    // ---- Action parameter body ----

    @Test
    void buildActionBodySerializesParameterMapAsJsonObject() {
        byte[] body = EntityOperations.buildActionBody(Map.of("tripId", 42, "userName", "scott"));

        String json = new String(body, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"tripId\":42"), json);
        assertTrue(json.contains("\"userName\":\"scott\""), json);
    }

    @Test
    void buildActionBodyPreservesNestedStructuredValues() {
        java.util.List<String> tags = List.of("a", "b");
        byte[] body = EntityOperations.buildActionBody(Map.of("tags", tags));

        assertEquals("{\"tags\":[\"a\",\"b\"]}", new String(body, StandardCharsets.UTF_8));
    }
}
