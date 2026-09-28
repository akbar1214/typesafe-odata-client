package io.github.akbarhusain.odata.runtime;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripPinCleanupSupportTest {

    @Test
    void transientNoContentIsPolledBeforeCleanupDeletes() throws Exception {
        SequenceTransport transport = new SequenceTransport(
                response(204),
                new HttpResponse(200, Map.of("ETag", List.of("etag-1")), new byte[0]),
                response(204),
                response(204),
                response(204));
        Context context = context(transport);
        ContextPath path = context.basePath().addSegment("People").addKey("UserName", "test");
        List<String> sleeps = new ArrayList<>();

        TripPinCleanupSupport.deletePerson(context, path, "fallback", 5, 7,
                delay -> sleeps.add(Long.toString(delay)));

        assertEquals(List.of(HttpMethod.GET, HttpMethod.GET, HttpMethod.DELETE,
                HttpMethod.GET, HttpMethod.GET), transport.methods());
        assertEquals(List.of("7", "7"), sleeps);
        assertEquals("etag-1", transport.requests().get(2).headers().get("If-Match").get(0));
    }

    @Test
    void persistentNoContentIsReportedInsteadOfBeingTreatedAsDeletion() {
        SequenceTransport transport = new SequenceTransport(response(204), response(204), response(204));
        Context context = context(transport);
        ContextPath path = context.basePath().addSegment("People").addKey("UserName", "test");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TripPinCleanupSupport.deletePerson(context, path, null, 3, 0, delay -> { }));

        assertTrue(failure.getMessage().contains("could not confirm"));
        assertEquals(List.of(HttpMethod.GET, HttpMethod.GET, HttpMethod.GET), transport.methods());
    }

    @Test
    void successfulActionStillCleansUp() throws Exception {
        SequenceTransport transport = new SequenceTransport(
                new HttpResponse(200, Map.of("ETag", List.of("etag-1")), new byte[0]),
                response(204), response(204), response(204));
        Context context = context(transport);
        ContextPath path = context.basePath().addSegment("People").addKey("UserName", "test");

        TripPinCleanupSupport.withCleanup(context, path, cleanup -> cleanup.requireCleanup());

        assertEquals(List.of(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.GET, HttpMethod.GET),
                transport.methods());
    }

    @Test
    void cleanupFailureIsSuppressedWhenTheTestAlreadyFailed() {
        SequenceTransport transport = new SequenceTransport(response(500));
        Context context = context(transport);
        ContextPath path = context.basePath().addSegment("People").addKey("UserName", "test");
        IllegalStateException original = new IllegalStateException("original assertion");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> TripPinCleanupSupport.withCleanup(context, path, cleanup -> {
                    cleanup.requireCleanup();
                    throw original;
                }));

        assertSame(original, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, thrown.getSuppressed()[0]);
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("TripPin cleanup"));
    }

    @Test
    void cleanupFailureIsReportedWhenTheTestPasses() {
        SequenceTransport transport = new SequenceTransport(response(500));
        Context context = context(transport);
        ContextPath path = context.basePath().addSegment("People").addKey("UserName", "test");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TripPinCleanupSupport.withCleanup(context, path, cleanup -> cleanup.requireCleanup()));

        assertTrue(failure.getMessage().contains("TripPin cleanup"));
    }

    @Test
    void unrelatedLinkFaultIsNotSkipped() {
        assertFalse(TripPinCleanupSupport.isTripPinLinkMutationFault(
                response(500, "database connection failed")));
        assertFalse(TripPinCleanupSupport.isTripPinLinkMutationFault(
                response(400, "Property set method not found")));
        assertTrue(TripPinCleanupSupport.isTripPinLinkMutationFault(
                response(500, "Property set method not found")));
    }

    private static Context context(HttpTransport transport) {
        return Context.builder()
                .baseUrl("https://example.test/service")
                .transport(transport)
                .build();
    }

    private static HttpResponse response(int status) {
        return response(status, "");
    }

    private static HttpResponse response(int status, String body) {
        return new HttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class SequenceTransport implements HttpTransport {
        private final Deque<HttpResponse> responses;
        private final List<HttpRequest> requests = new ArrayList<>();

        private SequenceTransport(HttpResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            requests.add(request);
            if (responses.isEmpty()) {
                throw new AssertionError("No response left for " + request.method());
            }
            return CompletableFuture.completedFuture(responses.removeFirst());
        }

        @Override
        public CompletableFuture<InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }

        private List<HttpMethod> methods() {
            return requests.stream().map(HttpRequest::method).toList();
        }

        private List<HttpRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
