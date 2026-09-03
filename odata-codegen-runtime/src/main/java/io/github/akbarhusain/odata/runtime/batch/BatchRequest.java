package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.*;
import io.github.akbarhusain.odata.runtime.internal.MultipartHelper;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class BatchRequest {
    private final Context context;
    private final List<Object> entries = new ArrayList<>();
    private boolean continueOnError;

    public BatchRequest(Context context) {
        this.context = context;
    }

    /**
     * Requests partial processing: the service continues after a failed operation instead
     * of aborting the rest of the batch ({@code Prefer: continue-on-error=true}, OData
     * 4.01). Services advertise support via the Capabilities-V1 batch capabilities.
     */
    public BatchRequest continueOnError() {
        this.continueOnError = true;
        return this;
    }

    public BatchRequest add(BatchOperation operation) {
        entries.add(operation);
        return this;
    }

    public BatchRequest addChangeset(Changeset changeset) {
        entries.add(changeset);
        return this;
    }

    public int size() {
        return entries.stream().mapToInt(e -> e instanceof Changeset c ? c.size() : 1).sum();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public BatchResponse execute() {
        if (entries.isEmpty()) {
            return new BatchResponse(List.of());
        }
        try {
            String boundary = MultipartHelper.generateBoundary();
            HttpResponse response = submitBatch(boundary).join();
            return parseResponse(response, boundary);
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) {
                // join() is not itself interruptible; the async task failed interrupted —
                // restore the flag on the calling thread (parity with EntityOperations)
                Thread.currentThread().interrupt();
                throw new ODataException("Batch request failed: interrupted (" + cause.getMessage() + ")", cause);
            }
            if (cause instanceof RuntimeException re) throw re;
            throw new ODataException("Batch request failed: " + cause.getMessage(), cause);
        }
    }

    public CompletableFuture<BatchResponse> executeAsync() {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(new BatchResponse(List.of()));
        }
        String boundary = MultipartHelper.generateBoundary();
        return submitBatch(boundary)
                .thenApply(response -> parseResponse(response, boundary));
    }

    /** Shared request assembly for the sync and async paths (they had drifted into copies). */
    private CompletableFuture<HttpResponse> submitBatch(String boundary) {
        // Resolve URLs into a LOCAL copy — mutating this.entries consumed the builder
        // as a side effect of execution
        List<Object> resolvedEntries = new ArrayList<>(entries.size());
        String root = context.baseUrl();
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        final String serviceRoot = root;
        for (Object entry : entries) {
            if (entry instanceof BatchOperation op) {
                resolvedEntries.add(resolveOperationUrl(op, serviceRoot));
            } else if (entry instanceof Changeset cs) {
                resolvedEntries.add(new Changeset(cs.operations().stream()
                        .map(op -> resolveOperationUrl(op, serviceRoot))
                        .toList()));
            } else {
                resolvedEntries.add(entry);
            }
        }

        byte[] body = MultipartHelper.encodeBatchRequest(boundary, resolvedEntries);

        ContextPath batchPath = context.basePath().addSegment("$batch");

        Map<String, List<String>> headers = new HashMap<>();
        headers.putAll(toMultiMap(context.authProvider().getHeaders()));
        // Framing headers must win exactly once: an auth provider supplying e.g.
        // lowercase "content-type" must not produce a duplicate Content-Type.
        setHeaderCaseInsensitive(headers, "Content-Type", "multipart/mixed; boundary=" + boundary);
        setHeaderCaseInsensitive(headers, "Accept", "multipart/mixed");
        if (continueOnError) {
            setHeaderCaseInsensitive(headers, "Prefer", "continue-on-error=true");
        }

        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(batchPath.toUrl())
                .headers(headers)
                .body(body)
                .connectTimeout(context.connectTimeout())
                .readTimeout(context.readTimeout())
                .build();

        HttpTransport transport = EntityOperations.buildTransportChain(context, context.transport());
        return transport.submit(request);
    }

    private static BatchOperation resolveOperationUrl(BatchOperation op, String baseUrl) {
        String url = op.url();
        if (!url.regionMatches(true, 0, "http", 0, 4)) {
            url = baseUrl + (url.startsWith("/") ? "" : "/") + url;
        }
        if (op.body() != null && op.body().length > 0) {
            return new BatchOperation(op.method(), url, op.headers(), op.body());
        }
        return new BatchOperation(op.method(), url, op.headers(), null);
    }

    private BatchResponse parseResponse(HttpResponse response, String boundary) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // typed exception with the parsed ODataError, like every other response path
            io.github.akbarhusain.odata.runtime.exception.ODataException typed =
                    io.github.akbarhusain.odata.runtime.exception.ODataException.fromResponse(response);
            if (typed != null) {
                throw typed;
            }
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
            if (trimmed.regionMatches(true, 0, "boundary=", 0, "boundary=".length())) {
                String value = trimmed.substring("boundary=".length()).strip();
                // RFC 2046 allows quoted boundary values; the quotes are not part of the boundary
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private static Map<String, List<String>> toMultiMap(Map<String, String> singleMap) {
        Map<String, List<String>> result = new HashMap<>();
        for (var entry : singleMap.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(List.of(entry.getValue())));
        }
        return result;
    }

    /**
     * Sets a header, replacing any existing entry whose name matches
     * case-insensitively (mirrors EntityOperations: HTTP names are
     * case-insensitive per RFC 9110, and framing headers must win exactly once).
     */
    private static void setHeaderCaseInsensitive(Map<String, List<String>> headers,
                                                 String name, String value) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        headers.put(name, new ArrayList<>(List.of(value)));
    }

}
