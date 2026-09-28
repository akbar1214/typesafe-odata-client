package io.github.akbarhusain.odata.runtime.http;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HttpHeaders {

    private static final Set<String> PROHIBITED_REQUEST_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            "content-range", "content-encoding", "expect", "max-forwards",
            "content-transfer-encoding", "content-id");

    private HttpHeaders() {
    }

    public static String requireName(String name) {
        Objects.requireNonNull(name, "header name");
        if (name.isEmpty() || !isToken(name)) {
            throw new IllegalArgumentException("Invalid HTTP header name: " + printable(name));
        }
        return name;
    }

    public static String requireRequestName(String name) {
        String valid = requireName(name);
        if (PROHIBITED_REQUEST_HEADERS.contains(valid.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("HTTP request header is not allowed: " + valid);
        }
        return valid;
    }

    public static String requireValue(String name, String value) {
        Objects.requireNonNull(value, "header value for " + name);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0' || (c < 0x20 && c != '\t') || c == 0x7f) {
                throw new IllegalArgumentException("Invalid HTTP header value for " + name);
            }
        }
        return value;
    }

    public static String requireRequestValue(String name, String value) {
        String valid = requireValue(name, value);
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("HTTP request header value must not be empty for " + name);
        }
        return valid;
    }

    public static Map<String, List<String>> immutableCopy(Map<String, List<String>> source) {
        return immutableCopy(source, false, true);
    }

    public static Map<String, List<String>> immutableResponseCopy(Map<String, List<String>> source) {
        return immutableCopy(source, true, false);
    }

    private static Map<String, List<String>> immutableCopy(Map<String, List<String>> source,
                                                              boolean responseHeaders,
                                                              boolean requestHeaders) {
        if (source == null) {
            return new CaseInsensitiveMap(Map.of(), responseHeaders, requestHeaders);
        }
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String name = requireHeaderName(entry.getKey(), responseHeaders, requestHeaders);
            List<String> sourceValues = Objects.requireNonNull(entry.getValue(),
                    "header values for " + name);
            if (requestHeaders && sourceValues.isEmpty()) {
                throw new IllegalArgumentException("HTTP request header must contain a value for " + name);
            }
            List<String> values = new ArrayList<>(sourceValues.size());
            for (String value : sourceValues) {
                values.add(requestHeaders ? requireRequestValue(name, value) : requireValue(name, value));
            }
            String canonical = canonicalName(name);
            String existingName = findName(copy, canonical);
            if (existingName == null) {
                copy.put(canonical, Collections.unmodifiableList(values));
            } else {
                List<String> existing = new ArrayList<>(copy.get(existingName));
                existing.addAll(values);
                copy.put(existingName, Collections.unmodifiableList(existing));
            }
        }
        if (responseHeaders) {
            List<String> contentTypes = findName(copy, "Content-Type") == null
                    ? null : copy.get(findName(copy, "Content-Type"));
            if (contentTypes != null && contentTypes.size() > 1
                    && contentTypes.stream().distinct().count() > 1) {
                throw new IllegalArgumentException("HTTP response contains conflicting Content-Type headers");
            }
        }
        return new CaseInsensitiveMap(copy, responseHeaders, requestHeaders);
    }

    private static String requireHeaderName(String name, boolean responseHeaders, boolean requestHeaders) {
        if (responseHeaders && name != null && name.startsWith(":") && name.length() > 1) {
            String pseudo = ":" + requireName(name.substring(1));
            return canonicalName(pseudo);
        }
        return requestHeaders ? requireRequestName(name) : canonicalName(requireName(name));
    }

    private static String findName(Map<String, ?> headers, String name) {
        for (String existing : headers.keySet()) {
            if (existing.equalsIgnoreCase(name)) {
                return existing;
            }
        }
        return null;
    }

    private static String canonicalName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "content-type" -> "Content-Type";
            case "content-id" -> "Content-ID";
            case "content-transfer-encoding" -> "Content-Transfer-Encoding";
            case "accept" -> "Accept";
            case "authorization" -> "Authorization";
            case "proxy-authorization" -> "Proxy-Authorization";
            case "if-match" -> "If-Match";
            case "etag" -> "ETag";
            case "retry-after" -> "Retry-After";
            case "prefer" -> "Prefer";
            case "odata-version" -> "OData-Version";
            case "odata-maxversion" -> "OData-MaxVersion";
            default -> name;
        };
    }

    private static boolean isToken(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean alphaNumeric = c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9';
            if (!alphaNumeric && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String printable(String value) {
        return value.replace('\r', '?').replace('\n', '?').replace('\0', '?');
    }

    private static final class CaseInsensitiveMap extends AbstractMap<String, List<String>> {
        private final Map<String, List<String>> delegate;

        private CaseInsensitiveMap(Map<String, List<String>> source, boolean responseHeaders,
                                   boolean requestHeaders) {
            LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                String name = requireHeaderName(entry.getKey(), responseHeaders, requestHeaders);
                List<String> values = Objects.requireNonNull(entry.getValue(),
                        "header values for " + name);
                if (requestHeaders && values.isEmpty()) {
                    throw new IllegalArgumentException("HTTP request header must contain a value for " + name);
                }
                List<String> copiedValues = new ArrayList<>(values.size());
                for (String value : values) {
                    copiedValues.add(requestHeaders
                            ? requireRequestValue(name, value) : requireValue(name, value));
                }
                String canonical = canonicalName(name);
                String existing = findName(copy, canonical);
                if (existing == null) {
                    copy.put(canonical, Collections.unmodifiableList(copiedValues));
                } else {
                    List<String> merged = new ArrayList<>(copy.get(existing));
                    merged.addAll(copiedValues);
                    copy.put(existing, Collections.unmodifiableList(merged));
                }
            }
            delegate = Collections.unmodifiableMap(copy);
        }

        @Override
        public Set<Entry<String, List<String>>> entrySet() {
            return delegate.entrySet();
        }

        @Override
        public List<String> get(Object key) {
            if (!(key instanceof String)) {
                return null;
            }
            return delegate.get(findName(delegate, (String) key));
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String && findName(delegate, (String) key) != null;
        }

        @Override
        public boolean containsValue(Object value) {
            return delegate.containsValue(value);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }
    }
}
