package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.http.HttpMethod;

import java.util.*;

public record BatchOperation(
    HttpMethod method,
    String url,
    Map<String, List<String>> headers,
    byte[] body
) {
    public static BatchOperation get(String url) {
        return new BatchOperation(HttpMethod.GET, url, Map.of(), null);
    }

    public static BatchOperation get(String url, Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.GET, url, Collections.unmodifiableMap(headers), null);
    }

    public static BatchOperation post(String url, byte[] body) {
        return post(url, body, Map.of());
    }

    public static BatchOperation post(String url, byte[] body, Map<String, List<String>> headers) {
        Objects.requireNonNull(url, "url must not be null");
        return new BatchOperation(HttpMethod.POST, url, Collections.unmodifiableMap(headers), body != null ? body.clone() : null);
    }

    public static BatchOperation patch(String url, byte[] body) {
        return patch(url, body, null);
    }

    public static BatchOperation patch(String url, byte[] body, String etag) {
        Objects.requireNonNull(url, "url must not be null");
        Map<String, List<String>> headers = new HashMap<>();
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", List.of(etag));
        }
        return new BatchOperation(HttpMethod.PATCH, url, Collections.unmodifiableMap(headers), body != null ? body.clone() : null);
    }

    public static BatchOperation put(String url, byte[] body) {
        Objects.requireNonNull(url, "url must not be null");
        return new BatchOperation(HttpMethod.PUT, url, Map.of(), body != null ? body.clone() : null);
    }

    public static BatchOperation delete(String url) {
        return new BatchOperation(HttpMethod.DELETE, url, Map.of(), null);
    }
}
