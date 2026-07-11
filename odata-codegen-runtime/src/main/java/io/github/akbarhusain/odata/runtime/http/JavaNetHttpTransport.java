package io.github.akbarhusain.odata.runtime.http;

import io.github.akbarhusain.odata.runtime.exception.ODataException;

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

public class JavaNetHttpTransport implements HttpTransport {

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();
    private static final Executor DEFAULT_EXECUTOR = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r, "odata-http-" + THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    private final HttpClient client;
    private final Executor executor;

    public JavaNetHttpTransport() {
        this(DEFAULT_EXECUTOR);
    }

    JavaNetHttpTransport(Executor executor) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.executor = executor;
    }

    @Override
    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execute(request);
            } catch (ODataException e) {
                throw e;
            } catch (Exception e) {
                throw new ODataException("HTTP request failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<InputStream> stream(HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.net.http.HttpRequest.Builder reqBuilder = buildRequest(request);
                java.net.http.HttpResponse<InputStream> response = client.send(
                        reqBuilder.build(),
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() >= 400) {
                    byte[] errorBody = response.body().readAllBytes();
                    Map<String, List<String>> responseHeaders = new HashMap<>();
                    response.headers().map().forEach((k, v) -> responseHeaders.put(k, v));
                    HttpResponse errorResponse = new HttpResponse(response.statusCode(), responseHeaders, errorBody);
                    throw ODataException.fromResponse(errorResponse);
                }
                return response.body();
            } catch (ODataException e) {
                throw e;
            } catch (Exception e) {
                throw new ODataException("HTTP stream failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    private HttpResponse execute(HttpRequest request) throws Exception {
        java.net.http.HttpRequest.Builder reqBuilder = buildRequest(request);

        java.net.http.HttpResponse<byte[]> response = client.send(
                reqBuilder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        Map<String, List<String>> headers = new HashMap<>();
        response.headers().map().forEach((k, v) -> headers.put(k, v));

        HttpResponse httpResponse = new HttpResponse(response.statusCode(), headers, response.body());

        if (response.statusCode() >= 400) {
            throw ODataException.fromResponse(httpResponse);
        }
        return httpResponse;
    }

    private java.net.http.HttpRequest.Builder buildRequest(HttpRequest request) {
        Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(request.readTimeout())
                .header("OData-MaxVersion", "4.0")
                .header("OData-Version", "4.0");

        if (!request.headers().containsKey("Accept")) {
            builder.header("Accept", "application/json");
        }

        for (var entry : request.headers().entrySet()) {
            for (String value : entry.getValue()) {
                builder.header(entry.getKey(), value);
            }
        }

        if (request.body() != null && request.body().length > 0) {
            builder.method(request.method().name(),
                    java.net.http.HttpRequest.BodyPublishers.ofByteArray(request.body()));
        } else {
            builder.method(request.method().name(),
                    java.net.http.HttpRequest.BodyPublishers.noBody());
        }

        return builder;
    }
}
