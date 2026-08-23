package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ODataEntityType;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7: GET discards response headers, so services that return the concurrency token
 * ONLY as an {@code ETag} header (no {@code @odata.etag} body annotation) left users
 * unable to call patchWithETag/deleteWithETag after a plain GET. executeAndGetEntity
 * must capture the header onto the entity when the entity does not already carry an
 * etag from the body.
 */
class EntityOperationsEtagHeaderTest {

    /** Mirrors generated root-entity shape: etag field + interface override. */
    static final class Widget implements ODataEntityType {
        String etag;
        private String userName;

        @com.fasterxml.jackson.annotation.JsonProperty("UserName")
        public String getUserName() { return userName; }

        @com.fasterxml.jackson.annotation.JsonProperty("UserName")
        public void setUserName(String value) { this.userName = value; }

        @Override public String odataTypeName() { return "NS.Widget"; }
        @Override public Set<String> getChangedFields() { return Set.of(); }
        @Override public Object getKey() { return "w1"; }
        @Override public Map<String, Object> getUnmappedFields() { return Map.of(); }
        @Override public io.github.akbarhusain.odata.runtime.entity.ContextPath getContextPath() { return null; }

        @Override
        public Optional<String> getETag() {
            return Optional.ofNullable(etag);
        }

        /** Same shape generated entities emit for the @odata.etag body annotation. */
        @com.fasterxml.jackson.annotation.JsonProperty("@odata.etag")
        public void setEtag(String value) {
            this.etag = value;
        }

        // Same shape the generator emits on root entity classes
        @Override
        public void applyETagFromResponse(String value) {
            if (value != null && !value.isEmpty()) {
                this.etag = value;
            }
        }
    }

    private Context contextReturning(HttpResponse response) {
        return Context.builder()
                .baseUrl("http://example.com")
                .transport(new HttpTransport() {
                    @Override public CompletableFuture<HttpResponse> submit(HttpRequest r) {
                        return CompletableFuture.completedFuture(response);
                    }
                    @Override public CompletableFuture<InputStream> stream(HttpRequest r) {
                        return CompletableFuture.failedFuture(new UnsupportedOperationException());
                    }
                })
                .authProvider(AuthProvider.none())
                .build();
    }

    private static HttpResponse ok(byte[] body, Map<String, List<String>> headers) {
        return new HttpResponse(200, headers, body);
    }

    @Test
    void etagHeaderIsCapturedOntoEntityAfterGet() {
        Context ctx = contextReturning(ok(
                "{\"UserName\":\"w1\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("ETag", List.of("W/\"abc123\""))));

        Widget w = EntityOperations.executeAndGetEntity(ctx, ctx.basePath().addSegment("Widget"), Widget.class);

        assertNotNull(w, "entity should deserialize");
        assertEquals(Optional.of("W/\"abc123\""), w.getETag(),
                "header-only ETag must be captured so patchWithETag works after a GET");
    }

    @Test
    void bodyAnnotationTakesPrecedenceOverHeader() {
        String body = "{\"UserName\":\"w1\",\"@odata.etag\":\"W/\\\"body-etag\\\"\"}";
        Context ctx = contextReturning(ok(body.getBytes(StandardCharsets.UTF_8),
                Map.of("ETag", List.of("W/\"header-etag\""))));

        Widget w = EntityOperations.executeAndGetEntity(ctx, ctx.basePath().addSegment("Widget"), Widget.class);

        assertEquals(Optional.of("W/\"body-etag\""), w.getETag(),
                "the more specific @odata.etag body annotation wins");
    }

    @Test
    void noEtagHeaderLeavesEntityUnchanged() {
        Context ctx = contextReturning(ok(
                "{\"UserName\":\"w1\"}".getBytes(StandardCharsets.UTF_8), Map.of()));
        Widget w = EntityOperations.executeAndGetEntity(ctx, ctx.basePath().addSegment("Widget"), Widget.class);
        assertEquals(Optional.empty(), w.getETag());
    }

    @Test
    void capturedEtagFlowsIntoIfMatchOnPatchWithEtag() {
        List<String> ifMatchSeen = new ArrayList<>();
        Context ctx = Context.builder()
                .baseUrl("http://example.com")
                .transport(new HttpTransport() {
                    int calls = 0;
                    @Override public CompletableFuture<HttpResponse> submit(HttpRequest r) {
                        if (calls++ == 0) {
                            return CompletableFuture.completedFuture(ok(
                                    "{\"UserName\":\"w1\"}".getBytes(StandardCharsets.UTF_8),
                                    Map.of("ETag", List.of("W/\"seq-2\""))));
                        }
                        ifMatchSeen.addAll(r.headers().getOrDefault("If-Match", List.of()));
                        return CompletableFuture.completedFuture(new HttpResponse(204, Map.of(), null));
                    }
                    @Override public CompletableFuture<InputStream> stream(HttpRequest r) {
                        return CompletableFuture.failedFuture(new UnsupportedOperationException());
                    }
                })
                .authProvider(AuthProvider.none())
                .build();

        Widget fetched = EntityOperations.executeAndGetEntity(ctx,
                ctx.basePath().addSegment("Widget"), Widget.class);
        EntityOperations.executePatchEntityWithETag(ctx, ctx.basePath().addSegment("Widget"),
                fetched, Widget.class, fetched.getETag().orElse(null));

        assertEquals(List.of("W/\"seq-2\""), ifMatchSeen,
                "the captured etag must be usable as If-Match for optimistic concurrency");
    }
}
