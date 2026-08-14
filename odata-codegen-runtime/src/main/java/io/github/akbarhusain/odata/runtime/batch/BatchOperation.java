package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.http.HttpMethod;

import java.util.*;

public record BatchOperation(
    HttpMethod method,
    String url,
    Map<String, List<String>> headers,
    byte[] body
) {
    public BatchOperation {
        // URLs and headers are written verbatim into multipart batch framing; CR/LF/NUL
        // would inject forged headers or whole requests (M5)
        Objects.requireNonNull(url, "url must not be null");
        rejectLineBreaks("url", url);
        for (var entry : headers.entrySet()) {
            rejectLineBreaks("header name", entry.getKey());
            for (String value : entry.getValue()) {
                rejectLineBreaks("header '" + entry.getKey() + "'", value);
            }
        }
    }

    private static void rejectLineBreaks(String what, String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                throw new IllegalArgumentException(
                        what + " must not contain CR/LF/NUL (batch header injection): " + value);
            }
        }
    }

    public static BatchOperation get(String url) {
        Objects.requireNonNull(url, "url must not be null");
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
