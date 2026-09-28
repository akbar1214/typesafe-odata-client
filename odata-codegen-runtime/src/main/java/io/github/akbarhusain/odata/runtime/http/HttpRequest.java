package io.github.akbarhusain.odata.runtime.http;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record HttpRequest(
    HttpMethod method,
    String url,
    Map<String, List<String>> headers,
    byte[] body,
    Duration connectTimeout,
    Duration readTimeout
) {
    public HttpRequest {
        method = Objects.requireNonNull(method, "HTTP method must not be null");
        url = requireUrl(url);
        headers = HttpHeaders.immutableCopy(Objects.requireNonNull(headers, "HTTP headers must not be null"));
        body = body == null ? null : body.clone();
        connectTimeout = requireTimeout(connectTimeout, "connectTimeout");
        readTimeout = requireTimeout(readTimeout, "readTimeout");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    @Override
    public String toString() {
        return "HttpRequest[method=" + method
                + ", url=" + redactUrl(url)
                + ", headers=" + redactHeaders(headers)
                + ", body=<redacted>"
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static String requireUrl(String url) {
        Objects.requireNonNull(url, "HTTP URL must not be null");
        if (url.isBlank()) {
            throw new IllegalArgumentException("HTTP URL must not be blank");
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f || Character.isWhitespace(c) || c == '\\') {
                throw new IllegalArgumentException("HTTP URL contains an invalid request-target character");
            }
        }
        for (int i = 0; i < url.length() - 2; i++) {
            if (url.charAt(i) == '%') {
                int high = Character.digit(url.charAt(i + 1), 16);
                int low = Character.digit(url.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    int decoded = (high << 4) | low;
                    if (decoded == '\r' || decoded == '\n' || decoded == '\0') {
                        throw new IllegalArgumentException("HTTP URL contains an encoded control character");
                    }
                }
            }
        }
        try {
            URI uri = URI.create(url);
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("HTTP URL must be an absolute HTTP(S) URI without a fragment");
            }
            URI normalized = uri.normalize();
            String path = normalized.getRawPath();
            if (path != null) {
                // RFC 3986 normalization only collapses literal dot segments; a server may
                // still percent-decode %2e to '.' and traverse, so decode encoded dots before
                // checking (matches the literal-segment check below).
                String decodedPath = path.replaceAll("(?i)%2e", ".");
                for (String segment : decodedPath.split("/", -1)) {
                    if (segment.equals("..")) {
                        throw new IllegalArgumentException("HTTP URL traversal escapes the request target");
                    }
                }
            }
            url = normalized.toString();
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("HTTP URL ")) {
                throw e;
            }
            throw new IllegalArgumentException("HTTP URL is not a valid absolute HTTP(S) URI");
        }
        return url;
    }

    private static Duration requireTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("HTTP " + name + " must be a positive Duration");
        }
        return timeout;
    }

    private static String redactUrl(String url) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return url;
        }
        int fragmentStart = url.indexOf('#', queryStart);
        String query = fragmentStart < 0 ? url.substring(queryStart + 1)
                : url.substring(queryStart + 1, fragmentStart);
        StringBuilder redacted = new StringBuilder(url.substring(0, queryStart + 1));
        String[] pairs = query.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                redacted.append('&');
            }
            String pair = pairs[i];
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (isSensitiveName(name)) {
                redacted.append(name);
                if (equals >= 0) {
                    redacted.append("=[REDACTED]");
                }
            } else {
                redacted.append(pair);
            }
        }
        if (fragmentStart >= 0) {
            redacted.append(url, fragmentStart, url.length());
        }
        return redacted.toString();
    }

    private static Map<String, List<String>> redactHeaders(Map<String, List<String>> headers) {
        LinkedHashMap<String, List<String>> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            redacted.put(entry.getKey(), isSensitiveName(entry.getKey())
                    ? List.of("[REDACTED]")
                    : entry.getValue());
        }
        return Map.copyOf(redacted);
    }

    private static boolean isSensitiveName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("authorization")
                || lower.equals("proxy-authorization")
                || lower.contains("cookie")
                || lower.contains("key")
                || lower.contains("auth")
                || lower.contains("apikey")
                || lower.contains("api_key")
                || lower.contains("access-token")
                || lower.contains("access_token")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("password")
                || lower.contains("signature")
                || lower.equals("sig")
                || lower.contains("credential")
                || lower.contains("session")
                || lower.startsWith("x-amz-")
                || lower.equals("code");
    }

    public static final class Builder {
        private HttpMethod method = HttpMethod.GET;
        private String url = "";
        private final LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        private byte[] body;
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration readTimeout = Duration.ofSeconds(60);

        public Builder method(HttpMethod method) {
            this.method = method;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder header(String name, String value) {
            HttpHeaders.requireRequestName(name);
            HttpHeaders.requireRequestValue(name, value);
            String existing = findHeaderName(name);
            if (existing == null) {
                headers.put(name, new ArrayList<>(List.of(value)));
            } else {
                headers.get(existing).add(value);
            }
            return this;
        }

        public Builder headers(Map<String, List<String>> headers) {
            Objects.requireNonNull(headers, "headers");
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String name = HttpHeaders.requireName(entry.getKey());
                List<String> values = Objects.requireNonNull(entry.getValue(),
                        "header values for " + name);
                for (String value : values) {
                    header(name, value);
                }
            }
            return this;
        }

        public Builder body(byte[] body) {
            this.body = body == null ? null : body.clone();
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = requireTimeout(timeout, "connectTimeout");
            return this;
        }

        public Builder readTimeout(Duration timeout) {
            this.readTimeout = requireTimeout(timeout, "readTimeout");
            return this;
        }

        public HttpRequest build() {
            return new HttpRequest(method, url, headers, body, connectTimeout, readTimeout);
        }

        private String findHeaderName(String name) {
            for (String existing : headers.keySet()) {
                if (existing.equalsIgnoreCase(name)) {
                    return existing;
                }
            }
            return null;
        }
    }
}
