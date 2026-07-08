package io.github.akbarhusain.odata.runtime.http;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.Builder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class JdkHttpTransport implements HttpTransport {

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();
    private static final Executor DEFAULT_EXECUTOR = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r, "odata-http-" + THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    private final Executor executor;

    public JdkHttpTransport() {
        this(DEFAULT_EXECUTOR);
    }

    JdkHttpTransport(Executor executor) {
        this.executor = executor;
    }

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public CompletableFuture<HttpResponse> submit(io.github.akbarhusain.odata.runtime.http.HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execute(request);
            } catch (Exception e) {
                throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                        "HTTP request failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<InputStream> stream(io.github.akbarhusain.odata.runtime.http.HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Builder builder = java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create(request.url()))
                        .timeout(request.readTimeout());

                for (var entry : request.headers().entrySet()) {
                    for (String value : entry.getValue()) {
                        builder.header(entry.getKey(), value);
                    }
                }

                builder.header("OData-MaxVersion", "4.0");
                builder.header("OData-Version", "4.0");
                builder.header("Accept", "application/json");

                switch (request.method()) {
                    case GET -> builder.GET();
                    case POST -> {
                        if (request.body() != null) {
                            builder.POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(request.body()));
                        } else {
                            builder.POST(java.net.http.HttpRequest.BodyPublishers.noBody());
                        }
                    }
                    case PATCH -> {
                        if (request.body() != null) {
                            builder.method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofByteArray(request.body()));
                        } else {
                            builder.method("PATCH", java.net.http.HttpRequest.BodyPublishers.noBody());
                        }
                    }
                    case DELETE -> builder.DELETE();
                    default -> builder.method(request.method().name(),
                            request.body() != null
                                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(request.body())
                                    : java.net.http.HttpRequest.BodyPublishers.noBody());
                }

                java.net.http.HttpResponse<InputStream> resp = httpClient.send(
                        builder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());

                if (resp.statusCode() >= 400) {
                    byte[] errorBody = resp.body().readAllBytes();
                    Map<String, List<String>> responseHeaders = new HashMap<>();
                    resp.headers().map().forEach((k, v) -> responseHeaders.put(k, v));
                    HttpResponse errorResponse = new HttpResponse(resp.statusCode(), responseHeaders, errorBody);
                    throw io.github.akbarhusain.odata.runtime.exception.ODataException.fromResponse(errorResponse);
                }
                return resp.body();
            } catch (io.github.akbarhusain.odata.runtime.exception.ODataException e) {
                throw e;
            } catch (Exception e) {
                throw new io.github.akbarhusain.odata.runtime.exception.ODataException(
                        "HTTP stream failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    private HttpResponse execute(io.github.akbarhusain.odata.runtime.http.HttpRequest request) throws Exception {
        Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(request.readTimeout());

        builder.header("OData-MaxVersion", "4.0");
        builder.header("OData-Version", "4.0");
        builder.header("Accept", "application/json");

        for (var entry : request.headers().entrySet()) {
            for (String value : entry.getValue()) {
                builder.header(entry.getKey(), value);
            }
        }

        byte[] body = request.body();
        switch (request.method()) {
            case GET -> builder.GET();
            case POST -> {
                if (body != null) {
                    builder.POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
                } else {
                    builder.POST(java.net.http.HttpRequest.BodyPublishers.noBody());
                }
            }
            case PUT -> {
                if (body != null) {
                    builder.PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
                } else {
                    builder.PUT(java.net.http.HttpRequest.BodyPublishers.noBody());
                }
            }
            case PATCH -> {
                if (body != null) {
                    builder.method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
                } else {
                    builder.method("PATCH", java.net.http.HttpRequest.BodyPublishers.noBody());
                }
            }
            case DELETE -> builder.DELETE();
            default -> builder.method(request.method().name(),
                    body != null
                            ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                            : java.net.http.HttpRequest.BodyPublishers.noBody());
        }

        java.net.http.HttpResponse<byte[]> resp = httpClient.send(
                builder.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        Map<String, List<String>> headers = new HashMap<>();
        resp.headers().map().forEach((k, v) -> headers.put(k, v));

        return new HttpResponse(resp.statusCode(), headers, resp.body());
    }
}
