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
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    // HttpClient's connect timeout is per-client, so clients are cached per requested
    // duration — HttpRequest.connectTimeout is honored without allocating a client per request
    private static final ConcurrentHashMap<Duration, HttpClient> CLIENTS_BY_CONNECT_TIMEOUT =
            new ConcurrentHashMap<>();

    static HttpClient clientFor(Duration connectTimeout) {
        Duration effective = connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        return CLIENTS_BY_CONNECT_TIMEOUT.computeIfAbsent(effective, d -> HttpClient.newBuilder()
                .connectTimeout(d)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

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
                java.net.http.HttpResponse<InputStream> resp = clientFor(request.connectTimeout()).send(
                        buildJdkRequest(request).build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());

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

    /**
     * Single request builder shared by {@code submit()} and {@code stream()} — the two
     * paths previously drifted (different header ordering, case-sensitive Accept check).
     * OData-MaxVersion is 4.01 because the client emits 4.01 payload forms (the
     * {@code @odata.id} control URL in $ref bodies); OData-Version stays 4.0.
     */
    private Builder buildJdkRequest(io.github.akbarhusain.odata.runtime.http.HttpRequest request) {
        Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(request.readTimeout());

        boolean hasMaxVersion = request.headers().keySet().stream()
                .anyMatch(k -> k != null && k.equalsIgnoreCase("OData-MaxVersion"));
        boolean hasVersion = request.headers().keySet().stream()
                .anyMatch(k -> k != null && k.equalsIgnoreCase("OData-Version"));
        if (!hasMaxVersion) {
            builder.header("OData-MaxVersion", "4.01");
        }
        if (!hasVersion) {
            builder.header("OData-Version", "4.0");
        }
        boolean hasAccept = request.headers().keySet().stream()
                .anyMatch(k -> k != null && k.equalsIgnoreCase("Accept"));
        if (!hasAccept) {
            builder.header("Accept", "application/json");
        }
        for (var entry : request.headers().entrySet()) {
            for (String value : entry.getValue()) {
                builder.header(entry.getKey(), value);
            }
        }

        byte[] body = request.body();
        switch (request.method()) {
            case GET -> builder.GET();
            case DELETE -> builder.DELETE();
            case POST -> builder.POST(body != null
                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                    : java.net.http.HttpRequest.BodyPublishers.noBody());
            case PUT -> builder.PUT(body != null
                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                    : java.net.http.HttpRequest.BodyPublishers.noBody());
            case PATCH -> builder.method("PATCH", body != null
                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                    : java.net.http.HttpRequest.BodyPublishers.noBody());
            default -> builder.method(request.method().name(), body != null
                    ? java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)
                    : java.net.http.HttpRequest.BodyPublishers.noBody());
        }
        return builder;
    }

    private HttpResponse execute(io.github.akbarhusain.odata.runtime.http.HttpRequest request) throws Exception {
        java.net.http.HttpResponse<byte[]> resp = clientFor(request.connectTimeout()).send(
                buildJdkRequest(request).build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        Map<String, List<String>> headers = new HashMap<>();
        resp.headers().map().forEach((k, v) -> headers.put(k, v));

        return new HttpResponse(resp.statusCode(), headers, resp.body());
    }
}
