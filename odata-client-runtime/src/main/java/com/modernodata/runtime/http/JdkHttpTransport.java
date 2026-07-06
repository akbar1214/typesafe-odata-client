package com.modernodata.runtime.http;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.Builder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class JdkHttpTransport implements HttpTransport {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public CompletableFuture<HttpResponse> submit(com.modernodata.runtime.http.HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execute(request);
            } catch (Exception e) {
                throw new com.modernodata.runtime.exception.ODataException(
                        "HTTP request failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<InputStream> stream(com.modernodata.runtime.http.HttpRequest request) {
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
                    throw new com.modernodata.runtime.exception.ODataException(
                            "HTTP " + resp.statusCode() + " from " + request.url());
                }
                return resp.body();
            } catch (com.modernodata.runtime.exception.ODataException e) {
                throw e;
            } catch (Exception e) {
                throw new com.modernodata.runtime.exception.ODataException(
                        "HTTP stream failed: " + e.getMessage(), e);
            }
        });
    }

    private HttpResponse execute(com.modernodata.runtime.http.HttpRequest request) throws Exception {
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
