package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.http.HttpHeaders;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record BatchOperation(
    HttpMethod method,
    String url,
    Map<String, List<String>> headers,
    byte[] body,
    String contentId
) {
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://.*");

    public BatchOperation {
        method = Objects.requireNonNull(method, "method must not be null");
        url = requireUrl(url);
        headers = HttpHeaders.immutableCopy(Objects.requireNonNull(headers, "headers must not be null"));
        body = body == null ? null : body.clone();
        contentId = requireContentId(contentId);
    }

    public BatchOperation(HttpMethod method, String url, Map<String, List<String>> headers, byte[] body) {
        this(method, url, headers, body, null);
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BatchOperation other)) {
            return false;
        }
        return method == other.method
                && Objects.equals(url, other.url)
                && Objects.equals(headers, other.headers)
                && Arrays.equals(body, other.body)
                && Objects.equals(contentId, other.contentId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(method, url, headers, contentId);
        result = 31 * result + Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "BatchOperation[" + method + " " + redactUrl(url)
                + (contentId != null ? ", contentId=" + contentId : "")
                + (body != null ? ", bodyLength=" + body.length : "") + "]";
    }

    public BatchOperation withContentId(String value) {
        return new BatchOperation(method, url, headers, body, value);
    }

    public BatchOperation withUrl(String value) {
        return new BatchOperation(method, value, headers, body, contentId);
    }

    public BatchOperation withContentId(int value) {
        return withContentId(Integer.toString(value));
    }

    public static BatchOperation get(String url) {
        return new BatchOperation(HttpMethod.GET, url, Map.of(), null);
    }

    public static BatchOperation get(String url, Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.GET, url, headers, null);
    }

    public static BatchOperation get(String url, Object contentIdOrHeaders) {
        if (contentIdOrHeaders instanceof String contentId) {
            return new BatchOperation(HttpMethod.GET, url, Map.of(), null, contentId);
        }
        if (contentIdOrHeaders instanceof Map<?, ?> headers) {
            return get(url, castHeaders(headers));
        }
        throw new IllegalArgumentException("contentId or headers must be supplied");
    }

    public static BatchOperation getWithContentId(String url, String contentId) {
        return new BatchOperation(HttpMethod.GET, url, Map.of(), null, contentId);
    }

    public static BatchOperation get(String url, String contentId, Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.GET, url, headers, null, contentId);
    }

    public static BatchOperation post(String url, byte[] body) {
        return post(url, body, Map.of());
    }

    public static BatchOperation post(String url, byte[] body, Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.POST, url, headers, body);
    }

    public static BatchOperation post(String url, byte[] body, Object contentIdOrHeaders) {
        if (contentIdOrHeaders instanceof String contentId) {
            return new BatchOperation(HttpMethod.POST, url, Map.of(), body, contentId);
        }
        if (contentIdOrHeaders instanceof Map<?, ?> headers) {
            return post(url, body, castHeaders(headers));
        }
        throw new IllegalArgumentException("contentId or headers must be supplied");
    }

    public static BatchOperation post(String url, byte[] body, String contentId,
                                       Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.POST, url, headers, body, contentId);
    }

    public static BatchOperation postWithContentId(String url, byte[] body, String contentId) {
        return new BatchOperation(HttpMethod.POST, url, Map.of(), body, contentId);
    }

    public static BatchOperation postWithContentType(String url, byte[] body, String contentType) {
        return new BatchOperation(HttpMethod.POST, url, Map.of("Content-Type", List.of(
                requireContentType(contentType))), body);
    }

    public static BatchOperation patch(String url, byte[] body) {
        return patch(url, body, (String) null);
    }

    public static BatchOperation patch(String url, byte[] body, String etag) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", List.of(etag));
        }
        return new BatchOperation(HttpMethod.PATCH, url, headers, body);
    }

    public static BatchOperation patch(String url, byte[] body, String contentId, String etag) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (etag != null && !etag.isEmpty()) {
            headers.put("If-Match", List.of(etag));
        }
        return new BatchOperation(HttpMethod.PATCH, url, headers, body, contentId);
    }

    public static BatchOperation put(String url, byte[] body) {
        return new BatchOperation(HttpMethod.PUT, url, Map.of(), body);
    }

    public static BatchOperation put(String url, byte[] body, Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.PUT, url, headers, body);
    }

    public static BatchOperation put(String url, byte[] body, Object contentIdOrHeaders) {
        if (contentIdOrHeaders instanceof String contentId) {
            return new BatchOperation(HttpMethod.PUT, url, Map.of(), body, contentId);
        }
        if (contentIdOrHeaders instanceof Map<?, ?> headers) {
            return put(url, body, castHeaders(headers));
        }
        throw new IllegalArgumentException("contentId or headers must be supplied");
    }

    public static BatchOperation put(String url, byte[] body, String contentId,
                                     Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.PUT, url, headers, body, contentId);
    }

    public static BatchOperation putWithContentId(String url, byte[] body, String contentId) {
        return new BatchOperation(HttpMethod.PUT, url, Map.of(), body, contentId);
    }

    public static BatchOperation media(String url, byte[] body) {
        return media(url, body, "application/octet-stream");
    }

    public static BatchOperation media(String url, byte[] body, String contentType) {
        return new BatchOperation(HttpMethod.PUT, url, Map.of("Content-Type", List.of(
                requireContentType(contentType))), body);
    }

    public static BatchOperation media(String url, byte[] body, String contentType, String contentId) {
        return new BatchOperation(HttpMethod.PUT, url, Map.of("Content-Type", List.of(
                requireContentType(contentType))), body, contentId);
    }

    public static BatchOperation media(String url, byte[] body, String contentType,
                                       Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.PUT, url, withContentType(headers, contentType), body);
    }

    public static BatchOperation binary(String url, byte[] body) {
        return media(url, body);
    }

    public static BatchOperation binary(String url, byte[] body, String contentType) {
        return media(url, body, contentType);
    }

    public static BatchOperation putMedia(String url, byte[] body, String contentType) {
        return media(url, body, contentType);
    }

    public static BatchOperation postMedia(String url, byte[] body, String contentType) {
        return new BatchOperation(HttpMethod.POST, url, Map.of("Content-Type", List.of(
                requireContentType(contentType))), body);
    }

    public static BatchOperation delete(String url) {
        return new BatchOperation(HttpMethod.DELETE, url, Map.of(), null);
    }

    public static BatchOperation delete(String url, Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.DELETE, url, headers, null);
    }

    public static BatchOperation delete(String url, Object contentIdOrHeaders) {
        if (contentIdOrHeaders instanceof String contentId) {
            return new BatchOperation(HttpMethod.DELETE, url, Map.of(), null, contentId);
        }
        if (contentIdOrHeaders instanceof Map<?, ?> headers) {
            return delete(url, castHeaders(headers));
        }
        throw new IllegalArgumentException("contentId or headers must be supplied");
    }

    public static BatchOperation delete(String url, String contentId,
                                        Map<String, List<String>> headers) {
        return new BatchOperation(HttpMethod.DELETE, url, headers, null, contentId);
    }

    public static BatchOperation deleteWithContentId(String url, String contentId) {
        return new BatchOperation(HttpMethod.DELETE, url, Map.of(), null, contentId);
    }

    public static String canonicalContentId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = requireContentId(value);
        if (normalized.startsWith("<") && normalized.endsWith(">")) {
            normalized = normalized.substring(1, normalized.length() - 1).strip();
        }
        return normalized;
    }

    private static Map<String, List<String>> withContentType(Map<String, List<String>> headers,
                                                               String contentType) {
        Objects.requireNonNull(headers, "headers must not be null");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase("Content-Type")) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        copy.put("Content-Type", List.of(requireContentType(contentType)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> castHeaders(Map<?, ?> headers) {
        return (Map<String, List<String>>) headers;
    }

    private static String requireUrl(String url) {
        Objects.requireNonNull(url, "url must not be null");
        if (url.isBlank() || url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("batch URL must be a non-blank request target without a fragment");
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f || Character.isWhitespace(c) || c == '\\') {
                throw new IllegalArgumentException("batch URL contains an invalid request-target character");
            }
        }
        for (int i = 0; i < url.length() - 2; i++) {
            if (url.charAt(i) != '%') {
                continue;
            }
            int high = Character.digit(url.charAt(i + 1), 16);
            int low = Character.digit(url.charAt(i + 2), 16);
            if (high >= 0 && low >= 0) {
                int decoded = (high << 4) | low;
                if (decoded == '\r' || decoded == '\n' || decoded == '\0') {
                    throw new IllegalArgumentException("batch URL contains an encoded control character");
                }
            }
        }
        if (url.startsWith("//")) {
            throw new IllegalArgumentException("batch URL must not be a network-path reference");
        }
        if (SCHEME.matcher(url).matches()) {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("batch URL is not a valid absolute HTTP(S) URI");
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("batch URL must be a valid absolute HTTP(S) URI");
            }
            URI normalized = uri.normalize();
            if (hasTraversalSegment(normalized.getRawPath())) {
                throw new IllegalArgumentException("batch URL traversal escapes the service root");
            }
            url = normalized.toString();
        } else if (looksLikeScheme(url)) {
            throw new IllegalArgumentException("batch URL has an unsupported or malformed absolute scheme");
        } else if (url.regionMatches(true, 0, "http", 0, 4)) {
            throw new IllegalArgumentException("batch URL has a malformed absolute HTTP scheme");
        } else if (hasEscapingTraversal(url)) {
            throw new IllegalArgumentException("batch URL traversal escapes the service root");
        }
        return url;
    }

    private static boolean looksLikeScheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        for (int i = 0; i < colon; i++) {
            char c = value.charAt(i);
            boolean valid = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || i > 0 && (c >= '0' && c <= '9' || c == '+' || c == '-' || c == '.');
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasEscapingTraversal(String value) {
        int suffix = value.indexOf('?');
        if (suffix < 0) {
            suffix = value.length();
        }
        String path = value.substring(0, suffix);
        int depth = 0;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (depth == 0) {
                    return true;
                }
                depth--;
            } else {
                depth++;
            }
        }
        return false;
    }

    private static boolean hasTraversalSegment(String path) {
        if (path == null) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static String requireContentId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException("Content-ID must be non-blank and at most 256 characters");
        }
        boolean angle = normalized.startsWith("<") || normalized.endsWith(">");
        if (angle && !(normalized.startsWith("<") && normalized.endsWith(">") && normalized.length() > 2)) {
            throw new IllegalArgumentException("Content-ID angle brackets are malformed");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c < 0x20 || c == 0x7f || Character.isWhitespace(c) || c == '\\') {
                throw new IllegalArgumentException("Content-ID contains an invalid character");
            }
        }
        return normalized;
    }

    private static String requireContentType(String value) {
        Objects.requireNonNull(value, "contentType must not be null");
        if (value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("contentType must be a non-blank header value");
        }
        return value;
    }

    private static String redactUrl(String url) {
        int query = url.indexOf('?');
        if (query < 0) {
            return url;
        }
        return url.substring(0, query + 1) + "[REDACTED]";
    }
}
