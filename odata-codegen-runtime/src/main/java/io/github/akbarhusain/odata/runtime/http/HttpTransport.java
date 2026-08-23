package io.github.akbarhusain.odata.runtime.http;

import java.util.concurrent.CompletableFuture;

public interface HttpTransport {
    CompletableFuture<HttpResponse> submit(HttpRequest request);

    /**
     * Opens a raw response stream (media downloads). The returned InputStream is the
     * caller's responsibility: consume and CLOSE it, or the underlying connection is
     * not released back to the client's pool. Errors arrive as a failed future with
     * the typed {@code ODataException} already mapped from the error body.
     */
    CompletableFuture<java.io.InputStream> stream(HttpRequest request);

    static HttpTransport createDefault() {
        return new JdkHttpTransport();
    }
}
