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
        Objects.requireNonNull(headers, "headers must not be null");
        rejectLineBreaks("url", url);
        for (var entry : headers.entrySet()) {
            rejectLineBreaks("header name", entry.getKey());
            for (String value : entry.getValue()) {
                rejectLineBreaks("header '" + entry.getKey() + "'", value);
            }
        }
        // Defensive copies: callers keep no window into the record's state
        // (deep copy — a shallow map copy would still share the mutable value lists)
        Map<String, List<String>> copied = new java.util.LinkedHashMap<>();
        for (var entry : headers.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        headers = Collections.unmodifiableMap(copied);
        body = body != null ? body.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        // Records over byte[] components get identity equality — two logically identical
        // operations compared unequal
        if (!(o instanceof BatchOperation other)) {
            return false;
        }
        return method == other.method
                && Objects.equals(url, other.url)
                && Objects.equals(headers, other.headers)
                && java.util.Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(method, url, headers);
        result = 31 * result + java.util.Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "BatchOperation[" + method + " " + url
                + (body != null ? ", bodyLength=" + body.length : "") + "]";
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
        // Also reject percent-encoded CRLF/NUL: %0D, %0A, %00 (case-insensitive) -> decoded as control
        for (int i = 0; i < value.length() - 2; i++) {
            if (value.charAt(i) == '%') {
                char h1 = value.charAt(i + 1);
                char h2 = value.charAt(i + 2);
                int d1 = Character.digit(h1, 16);
                int d2 = Character.digit(h2, 16);
                if (d1 != -1 && d2 != -1) {
                    int decoded = (d1 << 4) | d2;
                    if (decoded == '\r' || decoded == '\n' || decoded == '\0') {
                        throw new IllegalArgumentException(
                                what + " must not contain encoded CR/LF/NUL (batch header injection): " + value);
                    }
                }
            }
        }
    }

    public static BatchOperation get(String url) {
        Objects.requireNonNull(url, "url must not be null");
        return new BatchOperation(HttpMethod.GET, url, Map.of(), null);
    }

    public static BatchOperation get(String url, Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.GET, url, headers, null);
    }

    public static BatchOperation post(String url, byte[] body) {
        return post(url, body, Map.of());
    }

    public static BatchOperation post(String url, byte[] body, Map<String, List<String>> headers) {
        Objects.requireNonNull(url, "url must not be null");
        return new BatchOperation(HttpMethod.POST, url, headers, body);
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
        return new BatchOperation(HttpMethod.PATCH, url, headers, body);
    }

    public static BatchOperation put(String url, byte[] body) {
        Objects.requireNonNull(url, "url must not be null");
        return new BatchOperation(HttpMethod.PUT, url, Map.of(), body);
    }

    public static BatchOperation delete(String url) {
        return new BatchOperation(HttpMethod.DELETE, url, Map.of(), null);
    }
}
