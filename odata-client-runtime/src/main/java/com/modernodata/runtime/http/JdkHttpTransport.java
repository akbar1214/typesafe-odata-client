package com.modernodata.runtime.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class JdkHttpTransport implements HttpTransport {

    @Override
    public CompletableFuture<HttpResponse> submit(HttpRequest request) {
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
    public CompletableFuture<InputStream> stream(HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection conn = openConnection(request);
                configureConnection(conn, request);
                int code = conn.getResponseCode();
                if (code >= 400) {
                    throw new com.modernodata.runtime.exception.ODataException(
                            "HTTP " + code + " from " + request.url());
                }
                return conn.getInputStream();
            } catch (com.modernodata.runtime.exception.ODataException e) {
                throw e;
            } catch (Exception e) {
                throw new com.modernodata.runtime.exception.ODataException(
                        "HTTP stream failed: " + e.getMessage(), e);
            }
        });
    }

    private HttpResponse execute(HttpRequest request) throws IOException {
        HttpURLConnection conn = openConnection(request);
        configureConnection(conn, request);

        if (request.body() != null && request.body().length > 0) {
            conn.setDoOutput(true);
            try (var os = conn.getOutputStream()) {
                os.write(request.body());
            }
        }

        int code = conn.getResponseCode();
        Map<String, List<String>> headers = new HashMap<>(conn.getHeaderFields());

        byte[] body;
        try (InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            body = is != null ? is.readAllBytes() : new byte[0];
        }

        return new HttpResponse(code, headers, body);
    }

    private HttpURLConnection openConnection(HttpRequest request) throws IOException {
        URI uri = URI.create(request.url());
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout((int) request.connectTimeout().toMillis());
        conn.setReadTimeout((int) request.readTimeout().toMillis());
        return conn;
    }

    private void configureConnection(HttpURLConnection conn, HttpRequest request) throws IOException {
        conn.setRequestMethod(request.method().name());
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("OData-MaxVersion", "4.0");
        conn.setRequestProperty("OData-Version", "4.0");
        conn.setRequestProperty("Accept", "application/json");

        for (var entry : request.headers().entrySet()) {
            for (String value : entry.getValue()) {
                conn.addRequestProperty(entry.getKey(), value);
            }
        }
    }
}
