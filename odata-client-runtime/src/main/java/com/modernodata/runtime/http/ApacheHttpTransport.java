package com.modernodata.runtime.http;

import com.modernodata.runtime.exception.ODataException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.Builder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ApacheHttpTransport implements HttpTransport {

    private final HttpClient client;

    public ApacheHttpTransport() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public ApacheHttpTransport(Duration connectTimeout, Duration readTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execute(request);
            } catch (Exception e) {
                throw new ODataException("HTTP request failed: " + e.getMessage(), e);
            }
        });
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
                    throw new ODataException("HTTP " + response.statusCode() + " from " + request.url());
                }
                return response.body();
            } catch (ODataException e) {
                throw e;
            } catch (Exception e) {
                throw new ODataException("HTTP stream failed: " + e.getMessage(), e);
            }
        });
    }

    private HttpResponse execute(HttpRequest request) throws IOException, InterruptedException {
        java.net.http.HttpRequest.Builder reqBuilder = buildRequest(request);

        java.net.http.HttpResponse<byte[]> response = client.send(
                reqBuilder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        Map<String, List<String>> headers = new HashMap<>(response.headers().map());

        return new HttpResponse(response.statusCode(), headers, response.body());
    }

    private java.net.http.HttpRequest.Builder buildRequest(HttpRequest request) {
        Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(request.readTimeout())
                .header("OData-MaxVersion", "4.0")
                .header("OData-Version", "4.0")
                .header("Accept", "application/json");

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
