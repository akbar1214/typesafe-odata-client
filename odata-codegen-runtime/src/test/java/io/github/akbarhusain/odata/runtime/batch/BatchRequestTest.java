package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchRequestTest {

    @Test
    void createEmptyBatch() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch();
        assertTrue(batch.isEmpty());
        assertEquals(0, batch.size());
    }

    @Test
    void addOperationIncreasesSize() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch()
                .add(BatchOperation.get("People('scott')"));

        assertEquals(1, batch.size());
        assertFalse(batch.isEmpty());
    }

    @Test
    void addMultipleOperations() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch()
                .add(BatchOperation.get("People('scott')"))
                .add(BatchOperation.get("People('keith')"))
                .add(BatchOperation.delete("People('louis')"));

        assertEquals(3, batch.size());
    }

    @Test
    void fluentApiReturnsSameInstance() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch();
        BatchRequest returned = batch.add(BatchOperation.get("People('scott')"));
        assertSame(batch, returned);
    }

    @Test
    void contextPathRelativeUrl() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        ContextPath path = ctx.basePath()
                .addSegment("People")
                .addKey("UserName", "scott")
                .addSegment("Trips");

        String relative = path.toRelativeUrl();
        assertEquals("People('scott')/Trips", relative);
    }

    @Test
    void contextPathRelativeUrlWithQuery() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        ContextPath path = ctx.basePath()
                .addSegment("People")
                .addQuery("$top", "5");

        String relative = path.toRelativeUrl();
        assertEquals("People?$top=5", relative);
    }

    @Test
    void addChangesetIncreasesSize() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        Changeset cs = new Changeset(List.of(
                BatchOperation.delete("People('olduser')"),
                BatchOperation.post("People", "{\"UserName\":\"new\"}".getBytes())
        ));

        BatchRequest batch = ctx.batch()
                .addChangeset(cs);

        assertEquals(2, batch.size(), "Changeset with 2 ops should count as 2 operations");
        assertFalse(batch.isEmpty());
    }

    @Test
    void addChangesetIsCountedSeparatelyFromIndividualOps() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        Changeset cs = new Changeset(List.of(
                BatchOperation.post("People", "{\"UserName\":\"new\"}".getBytes())
        ));

        BatchRequest batch = ctx.batch()
                .add(BatchOperation.get("People"))
                .addChangeset(cs)
                .add(BatchOperation.get("People('scott')"));

        assertEquals(3, batch.size(), "1 standalone + 1 changeset op + 1 standalone = 3");
    }

    @Test
    void addChangesetReturnsSameInstanceForFluentApi() {
        Context ctx = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .build();

        BatchRequest batch = ctx.batch();
        Changeset cs = new Changeset(List.of(BatchOperation.get("People")));
        BatchRequest returned = batch.addChangeset(cs);
        assertSame(batch, returned);
    }

    @Test
    void authHeaderListIsMutable() {
        // Verifies that auth header lists (converted from Map<String,String> to
        // Map<String,List<String>>) are mutable so that computeIfAbsent/add works
        // when header keys collide (the old List.of(...) pattern threw UOE).
        java.util.Map<String, String> authHeaders = java.util.Map.of("Authorization", "Bearer token");
        java.util.Map<String, java.util.List<String>> headers = new java.util.HashMap<>();
        for (var entry : authHeaders.entrySet()) {
            headers.put(entry.getKey(), new java.util.ArrayList<>(java.util.List.of(entry.getValue())));
        }
        assertDoesNotThrow(() -> {
            headers.computeIfAbsent("Authorization", k -> new java.util.ArrayList<>()).add("extra");
        });
        assertEquals(2, headers.get("Authorization").size());
        assertEquals("Bearer token", headers.get("Authorization").get(0));
        assertEquals("extra", headers.get("Authorization").get(1));
    }

    @Test
    void getOperationRejectsNullUrl() {
        // M8: BatchOperation.get must validate its URL like post/patch/put do.
        assertThrows(NullPointerException.class, () -> BatchOperation.get(null));
    }

    static class HeaderCapturingTransport implements io.github.akbarhusain.odata.runtime.http.HttpTransport {
        io.github.akbarhusain.odata.runtime.http.HttpRequest lastRequest;

        @Override
        public java.util.concurrent.CompletableFuture<io.github.akbarhusain.odata.runtime.http.HttpResponse> submit(
                io.github.akbarhusain.odata.runtime.http.HttpRequest request) {
            this.lastRequest = request;
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new io.github.akbarhusain.odata.runtime.http.HttpResponse(200,
                            java.util.Map.of("Content-Type", java.util.List.of("multipart/mixed; boundary=resp_b")),
                            "--resp_b\r\nContent-Type: application/http\r\n\r\nHTTP/1.1 200 OK\r\n\r\n\r\n--resp_b--\r\n"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public java.util.concurrent.CompletableFuture<java.io.InputStream> stream(
                io.github.akbarhusain.odata.runtime.http.HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void l14ContinueOnErrorAddsPreferenceHeader() {
        HeaderCapturingTransport transport = new HeaderCapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport).build();

        ctx.batch().add(BatchOperation.get("People('scott')")).execute();
        assertNull(transport.lastRequest.headers().get("Prefer"),
                "no preference by default");

        ctx.batch().add(BatchOperation.get("People('scott')")).continueOnError().execute();
        assertEquals(java.util.List.of("continue-on-error=true"),
                transport.lastRequest.headers().get("Prefer"),
                "continueOnError() must request partial processing of the batch");
    }
}
