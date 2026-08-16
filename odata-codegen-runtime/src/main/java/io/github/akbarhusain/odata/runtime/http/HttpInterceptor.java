package io.github.akbarhusain.odata.runtime.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

public interface HttpInterceptor {
    HttpResponse intercept(HttpRequest request, HttpTransport delegate);

    /**
     * Streaming variant of {@link #intercept}. The default implementation falls back
     * to buffering the response through {@link #intercept} — interceptors that want
     * true streaming (e.g. media downloads) should override this and delegate to
     * {@code delegate.stream(request)}.
     */
    default CompletableFuture<InputStream> stream(HttpRequest request, HttpTransport delegate) {
        try {
            HttpResponse response = intercept(request, delegate);
            // Without this check, a registered interceptor would silently turn an HTTP
            // error (e.g. 404 on a media download) into a stream of the error body —
            // error semantics must not change just by adding an interceptor
            if (!response.isSuccessful()) {
                throw io.github.akbarhusain.odata.runtime.exception.ODataException.fromResponse(response);
            }
            return CompletableFuture.completedFuture(
                    new ByteArrayInputStream(response.body() == null ? new byte[0] : response.body()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                    "Interceptor failed: " + e.getMessage(), e);
        }
    }
}
