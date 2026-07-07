package com.modernodata.runtime.batch;

import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.entity.ContextPath;
import com.modernodata.runtime.exception.ODataException;
import com.modernodata.runtime.http.*;
import com.modernodata.runtime.internal.MultipartHelper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class BatchRequest {
    private final Context context;
    private final List<BatchOperation> operations = new ArrayList<>();

    public BatchRequest(Context context) {
        this.context = context;
    }

    public BatchRequest add(BatchOperation operation) {
        operations.add(operation);
        return this;
    }

    public int size() {
        return operations.size();
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    public BatchResponse execute() {
        if (operations.isEmpty()) {
            return new BatchResponse(List.of());
        }

        String boundary = MultipartHelper.generateBoundary();
        List<BatchOperation> resolvedOps = resolveOperations(operations);
        byte[] body = MultipartHelper.encodeRequest(boundary, resolvedOps);

        ContextPath batchPath = context.basePath().addSegment("$batch");

        Map<String, List<String>> headers = new HashMap<>();
        headers.putAll(toMultiMap(context.authProvider().getHeaders()));
        headers.put("Content-Type", List.of("multipart/mixed; boundary=" + boundary));
        headers.put("Accept", List.of("multipart/mixed"));

        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(batchPath.toUrl())
                .headers(headers)
                .body(body)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .build();

        HttpTransport transport = context.transport();

        for (HttpInterceptor interceptor : context.interceptors()) {
            HttpResponse response = interceptor.intercept(request, transport);
            return parseResponse(response, boundary);
        }

        try {
            HttpResponse response = transport.submit(request).join();
            return parseResponse(response, boundary);
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new ODataException("Batch request failed: " + cause.getMessage(), cause);
        }
    }

    public CompletableFuture<BatchResponse> executeAsync() {
        if (operations.isEmpty()) {
            return CompletableFuture.completedFuture(new BatchResponse(List.of()));
        }

        String boundary = MultipartHelper.generateBoundary();
        List<BatchOperation> resolvedOps = resolveOperations(operations);
        byte[] body = MultipartHelper.encodeRequest(boundary, resolvedOps);

        ContextPath batchPath = context.basePath().addSegment("$batch");

        Map<String, List<String>> headers = new HashMap<>();
        headers.putAll(toMultiMap(context.authProvider().getHeaders()));
        headers.put("Content-Type", List.of("multipart/mixed; boundary=" + boundary));
        headers.put("Accept", List.of("multipart/mixed"));

        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(batchPath.toUrl())
                .headers(headers)
                .body(body)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .build();

        HttpTransport transport = context.transport();

        for (HttpInterceptor interceptor : context.interceptors()) {
            HttpResponse response = interceptor.intercept(request, transport);
            return CompletableFuture.completedFuture(parseResponse(response, boundary));
        }

        return transport.submit(request)
                .thenApply(response -> parseResponse(response, boundary));
    }

    private BatchResponse parseResponse(HttpResponse response, String boundary) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ODataException(response.statusCode(),
                    "Batch request failed with HTTP " + response.statusCode() + ": " + response.getText());
        }

        String contentType = "";
        for (var entry : response.headers().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Content-Type")) {
                contentType = entry.getValue().stream().findFirst().orElse("");
                break;
            }
        }

        if (!contentType.contains("multipart/mixed")) {
            throw new ODataException(response.statusCode(),
                    "Expected multipart/mixed response but got: " + contentType);
        }

        String responseBoundary = extractBoundary(contentType);
        if (responseBoundary == null) {
            responseBoundary = boundary;
        }

        List<BatchResult<?>> results = MultipartHelper.decodeResponse(responseBoundary, response.body());
        return new BatchResponse(results);
    }

    private static String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                return trimmed.substring("boundary=".length()).strip();
            }
        }
        return null;
    }

    private static Map<String, List<String>> toMultiMap(Map<String, String> singleMap) {
        Map<String, List<String>> result = new HashMap<>();
        for (var entry : singleMap.entrySet()) {
            result.put(entry.getKey(), List.of(entry.getValue()));
        }
        return result;
    }

    private List<BatchOperation> resolveOperations(List<BatchOperation> ops) {
        String serviceRoot = context.baseUrl();
        if (serviceRoot.endsWith("/")) {
            serviceRoot = serviceRoot.substring(0, serviceRoot.length() - 1);
        }
        final String baseUrl = serviceRoot;

        return ops.stream().map(op -> {
            String url = op.url();
            if (!url.startsWith("http")) {
                url = baseUrl + "/" + url;
            }
            if (op.body() != null && op.body().length > 0) {
                return new BatchOperation(op.method(), url, op.headers(), op.body());
            }
            return new BatchOperation(op.method(), url, op.headers(), null);
        }).toList();
    }
}
