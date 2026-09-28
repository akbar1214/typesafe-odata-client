package io.github.akbarhusain.odata.runtime.http;

import io.github.akbarhusain.odata.runtime.auth.ApiKeyAuthProvider;
import io.github.akbarhusain.odata.runtime.auth.BearerAuthProvider;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpReviewFixesTest {

    @Test
    void bearerAndApiKeyProvidersRejectEmptySecrets() {
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthProvider(() -> "").getHeaders());
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthProvider(() -> null).getHeaders());
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyAuthProvider(() -> "").getHeaders());
        assertDoesNotThrow(() -> new BearerAuthProvider(() -> "t").getHeaders());
        assertDoesNotThrow(() -> new ApiKeyAuthProvider(() -> "k").getHeaders());
    }

    @Test
    void apiKeyProviderRejectsProhibitedHeaderNamesAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyAuthProvider(() -> "k", "Host"));
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyAuthProvider(() -> "k", "Content-Length"));
        assertDoesNotThrow(() -> new ApiKeyAuthProvider(() -> "k", "x-api-key"));
    }

    @Test
    void contextRejectsUserInfo() {
        assertThrows(IllegalArgumentException.class,
                () -> Context.builder().baseUrl("https://user:pass@example.test/service").build());
    }

    @Test
    void prohibitedHeadersNarrowedToJdkAndHopByHop() {
        // Legal, useful request headers must be allowed.
        assertDoesNotThrow(() -> HttpHeaders.requireRequestName("Content-Encoding"));
        assertDoesNotThrow(() -> HttpHeaders.requireRequestName("Content-Range"));
        assertDoesNotThrow(() -> HttpHeaders.requireRequestName("Proxy-Authorization"));
        // JDK-managed / hop-by-hop headers stay blocked.
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.requireRequestName("Content-Length"));
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.requireRequestName("Host"));
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.requireRequestName("Connection"));
    }

    @Test
    void executorSaturationSurfacesAsTypedODataException() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("full");
        };
        JdkHttpTransport transport = new JdkHttpTransport(rejecting);
        HttpRequest request = HttpRequest.builder().url("https://example.test/People").build();

        CompletableFuture<HttpResponse> submit = transport.submit(request);
        assertTrue(submit.isCompletedExceptionally());
        CompletionException submitError = assertThrows(CompletionException.class, submit::join);
        assertInstanceOf(ODataException.class, submitError.getCause());

        CompletableFuture<java.io.InputStream> stream = transport.stream(request);
        CompletionException streamError = assertThrows(CompletionException.class, stream::join);
        assertInstanceOf(ODataException.class, streamError.getCause());
    }

    @Test
    void redactionKeepsInnocuousNamesAndMasksCredentials() {
        HttpRequest request = HttpRequest.builder()
                .url("https://example.test/People?$skiptoken=abc123&monkey=1&access_token=topsecret")
                .build();
        String text = request.toString();

        assertTrue(text.contains("$skiptoken=abc123"), text);
        assertTrue(text.contains("monkey=1"), text);
        assertFalse(text.contains("topsecret"), text);
    }
}
