package com.modernodata.runtime.http;

import java.util.concurrent.CompletableFuture;

public interface HttpTransport {
    CompletableFuture<HttpResponse> submit(HttpRequest request);
    CompletableFuture<java.io.InputStream> stream(HttpRequest request);

    static HttpTransport createDefault() {
        return new JdkHttpTransport();
    }
}
