package io.github.akbarhusain.odata.runtime.entity;

import io.github.akbarhusain.odata.runtime.auth.ApiKeyAuthProvider;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.auth.AuthProvider;
import io.github.akbarhusain.odata.runtime.auth.BasicAuthProvider;
import io.github.akbarhusain.odata.runtime.auth.BearerAuthProvider;
import io.github.akbarhusain.odata.runtime.http.HttpInterceptor;
import io.github.akbarhusain.odata.runtime.http.HttpRequest;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAndAuthHardeningTest {

    @Test
    void directConstructionCopiesAndValidatesInterceptors() {
        List<HttpInterceptor> source = new ArrayList<>();
        Context context = new Context("https://example.test/root", Serializer.createDefault(),
                transport(), AuthProvider.none(), source, Duration.ofSeconds(1), Duration.ofSeconds(1));
        source.add((request, delegate) -> new HttpResponse(200, Map.of(), new byte[0]));

        assertTrue(context.interceptors().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> context.interceptors().add((request, delegate) -> new HttpResponse(200, Map.of(), new byte[0])));
        assertThrows(NullPointerException.class, () -> new Context("https://example.test/root",
                Serializer.createDefault(), transport(), AuthProvider.none(),
                java.util.Arrays.asList((HttpInterceptor) null), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @Test
    void contextRejectsNonServiceRootUrlsAndNonPositiveTimeouts() {
        for (String baseUrl : List.of("relative/root", "ftp://example.test/root",
                "https://example.test/root?x=1", "https://example.test/root#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> Context.builder().baseUrl(baseUrl).build());
            assertThrows(IllegalArgumentException.class, () -> new Context(baseUrl, Serializer.createDefault(),
                    transport(), AuthProvider.none(), List.of(), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        }
        assertThrows(IllegalArgumentException.class, () -> new Context("https://example.test/root",
                Serializer.createDefault(), transport(), AuthProvider.none(), List.of(), Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> Context.builder().baseUrl("https://example.test")
                .readTimeout(Duration.ZERO).build());
    }

    @Test
    void malformedAuthHeaderIsRejectedBeforeCustomTransport() {
        AtomicBoolean invoked = new AtomicBoolean();
        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                invoked.set(true);
                return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0]));
            }

            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                invoked.set(true);
                return CompletableFuture.completedFuture(new java.io.ByteArrayInputStream(new byte[0]));
            }
        };
        Context context = Context.builder()
                .baseUrl("https://example.test")
                .transport(transport)
                .authProvider(() -> Map.of("Bad Header", "value"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> EntityOperations.executeAsync(context,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                context.basePath().addSegment("People"), null, null));
        assertTrue(!invoked.get());
    }

    @Test
    void authProvidersValidateDependenciesAndHeaderNames() {
        assertThrows(NullPointerException.class, () -> new BearerAuthProvider(null));
        assertThrows(NullPointerException.class, () -> new ApiKeyAuthProvider(null, "X-Key"));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiKeyAuthProvider(() -> "key", "Bad Header"));
        assertThrows(IllegalArgumentException.class, () -> new BasicAuthProvider("user:name", "password"));
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthProvider(() -> "token\r\nvalue").getHeaders());
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyAuthProvider(() -> "key\0").getHeaders());
    }

    private static HttpTransport transport() {
        return new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponse> submit(HttpRequest request) {
                return CompletableFuture.completedFuture(new HttpResponse(200, Map.of(), new byte[0]));
            }

            @Override
            public CompletableFuture<java.io.InputStream> stream(HttpRequest request) {
                return CompletableFuture.completedFuture(new java.io.ByteArrayInputStream(new byte[0]));
            }
        };
    }
}
