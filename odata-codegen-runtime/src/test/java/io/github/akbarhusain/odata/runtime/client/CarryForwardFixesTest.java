package io.github.akbarhusain.odata.runtime.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.entity.ODataEntityType;
import io.github.akbarhusain.odata.runtime.entity.ODataType;
import io.github.akbarhusain.odata.runtime.exception.NotFoundException;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Carry-forward fixes: M4 (partial PATCH honors changedFields), M10 (interceptor chain
 * cached per Context), M3 (typed error for non-2xx batch), and baseUrl validation.
 */
class CarryForwardFixesTest {

    static class CapturingTransport implements HttpTransport {
        HttpRequest lastRequest;

        @Override
        public CompletableFuture<HttpResponse> submit(HttpRequest request) {
            this.lastRequest = request;
            return CompletableFuture.completedFuture(new HttpResponse(204, Map.of(), new byte[0]));
        }

        @Override
        public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    /** Minimal entity implementing the runtime contract so changedFields is honored. */
    public static class FakePerson implements ODataEntityType {
        @JsonProperty("UserName") public String userName;
        @JsonProperty("FirstName") public String firstName;
        @JsonProperty("Age") public Integer age;
        public final java.util.Set<String> changed;

        FakePerson(Set<String> changed) {
            this.changed = changed;
        }

        @Override public String odataTypeName() { return "NS.FakePerson"; }
        @Override public Map<String, Object> getUnmappedFields() { return Map.of(); }
        @Override public ContextPath getContextPath() { return null; }
        @Override public Set<String> getChangedFields() { return changed; }
        @Override public Object getKey() { return userName; }
    }

    @Test
    void m4PatchSendsOnlyChangedFieldsWhenTracked() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport).build();
        FakePerson person = new FakePerson(new java.util.HashSet<>(Set.of("Age")));
        person.age = 30;

        EntityOperations.executePatchEntity(ctx,
                ctx.basePath().addSegment("People").addKey("UserName", "scott"), person, FakePerson.class);

        String body = new String(transport.lastRequest.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("Age"), "changed field must be sent: " + body);
        assertFalse(body.contains("UserName"), "unchanged fields must be dropped from the partial PATCH: " + body);
        assertFalse(body.contains("FirstName"), "unchanged fields must be dropped: " + body);
    }

    @Test
    void m4PatchSendsFullBodyWhenNothingTracked() {
        CapturingTransport transport = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com").transport(transport).build();
        // e.g. a GET-deserialized entity mutated via setters tracks nothing
        FakePerson person = new FakePerson(Set.of());
        person.userName = "scott";
        person.firstName = "Scott";
        person.age = 30;

        EntityOperations.executePatchEntity(ctx,
                ctx.basePath().addSegment("People").addKey("UserName", "scott"), person, FakePerson.class);

        String body = new String(transport.lastRequest.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("Age") && body.contains("UserName") && body.contains("FirstName"),
                "untracked entities keep full-body merge semantics: " + body);
    }

    @Test
    void m10InterceptorChainIsCachedPerContext() {
        io.github.akbarhusain.odata.runtime.http.HttpInterceptor interceptor = (req, delegate) ->
                delegate.submit(req).join();
        HttpTransport real = new CapturingTransport();
        Context ctx = Context.builder().baseUrl("https://example.com")
                .transport(real).interceptors(List.of(interceptor)).build();

        assertSame(EntityOperations.buildTransportChain(ctx, real),
                EntityOperations.buildTransportChain(ctx, real),
                "the same Context must reuse its interceptor chain instead of rebuilding wrappers per request");
        assertSame(real, EntityOperations.buildTransportChain(
                Context.builder().baseUrl("https://example.com").transport(real).build(), real),
                "zero-interceptor fast path still returns the real transport");
    }

    @Test
    void m3Non2xxBatchResponseThrowsTypedException() {
        HttpTransport failing = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(404, Map.of(),
                        "{\"error\":{\"code\":\"NotFound\",\"message\":\"gone\"}}".getBytes(
                                StandardCharsets.UTF_8)));
            }

            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        Context ctx = Context.builder().baseUrl("https://example.com").transport(failing).build();
        io.github.akbarhusain.odata.runtime.batch.BatchRequest batch = ctx.batch()
                .add(io.github.akbarhusain.odata.runtime.batch.BatchOperation.get("People('scott')"));

        assertThrows(NotFoundException.class, batch::execute,
                "batch failures use the typed hierarchy like every other response path");
    }

    @Test
    void blankBaseUrlFailsAtBuild() {
        assertThrows(IllegalArgumentException.class,
                () -> Context.builder().build(),
                "a blank baseUrl previously produced opaque URI.create failures on every request");
    }
}
