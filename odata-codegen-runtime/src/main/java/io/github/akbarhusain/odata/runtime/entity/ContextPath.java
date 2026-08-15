package io.github.akbarhusain.odata.runtime.entity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ContextPath(
    String basePath,
    List<Segment> segments
) {
    public ContextPath {
        Objects.requireNonNull(basePath);
        segments = List.copyOf(segments);
    }

    public ContextPath(String basePath) {
        this(basePath, List.of());
    }

    public ContextPath addSegment(String segment) {
        return new ContextPath(basePath, append(segments, new Segment(segment, List.of())));
    }

    public ContextPath addKey(String name, Object value) {
        if (segments.isEmpty()) {
            throw new IllegalStateException("Cannot add key without a segment");
        }
        Segment last = segments.get(segments.size() - 1);
        Segment updated = new Segment(last.name(), append(last.keys(), new KeyValuePair(name, value)), last.queries());
        List<Segment> newSegments = new ArrayList<>(segments);
        newSegments.set(newSegments.size() - 1, updated);
        return new ContextPath(basePath, List.copyOf(newSegments));
    }

    public ContextPath addQuery(String name, String value) {
        // Queries are stored in the last segment or as a special trailing segment
        // For simplicity, store them as a Segment with name="" and keys as queries
        if (!segments.isEmpty()) {
            Segment last = segments.get(segments.size() - 1);
            Segment updated = new Segment(last.name(), last.keys(),
                    append(last.queries(), new KeyValuePair(name, value)));
            List<Segment> newSegments = new ArrayList<>(segments);
            newSegments.set(newSegments.size() - 1, updated);
            return new ContextPath(basePath, List.copyOf(newSegments));
        }
        return new ContextPath(basePath, append(segments,
                new Segment("", List.of(), List.of(new KeyValuePair(name, value)))));
    }

    public ContextPath clearQueries() {
        List<Segment> newSegments = new ArrayList<>();
        for (Segment s : segments) {
            newSegments.add(new Segment(s.name(), s.keys(), List.of()));
        }
        return new ContextPath(basePath, List.copyOf(newSegments));
    }

    /**
     * Creates a ContextPath from an OData @odata.nextLink value.
     * Handles absolute URLs and URLs relative to the current base path, and parses
     * any query string into the trailing query segment so that chaining further
     * query options (filter, top, etc.) produces a single valid '?' in the URL.
     */
    public ContextPath fromNextLink(String nextLink) {
        if (nextLink == null || nextLink.trim().isEmpty()) {
            throw new IllegalArgumentException("nextLink cannot be null or empty");
        }
        String trimmed = nextLink.trim();
        String pathPart = trimmed;
        String queryPart = null;
        int queryIdx = trimmed.indexOf('?');
        if (queryIdx >= 0) {
            pathPart = trimmed.substring(0, queryIdx);
            queryPart = trimmed.substring(queryIdx + 1);
        }
        String base;
        if (pathPart.startsWith("http://") || pathPart.startsWith("https://")) {
            base = pathPart;
        } else {
            String root = basePath;
            while (root.endsWith("/")) {
                root = root.substring(0, root.length() - 1);
            }
            base = pathPart.startsWith("/") ? root + pathPart : root + "/" + pathPart;
        }
        ContextPath result = new ContextPath(base);
        if (queryPart != null && !queryPart.isEmpty()) {
            for (String pair : queryPart.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String name = eq >= 0 ? pair.substring(0, eq) : pair;
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                result = result.addQuery(decodePercent(name), decodePercent(value));
            }
        }
        return result;
    }

    /**
     * Decodes only {@code %HH} escapes. NextLink query strings are percent-encoded, not
     * form-encoded: a literal {@code +} inside a value (common in $skiptoken continuation
     * tokens) must survive as a plus, so URLDecoder — which maps {@code +} to space —
     * must not be used here. Malformed escapes are left verbatim.
     */
    private static String decodePercent(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int h1 = Character.digit(value.charAt(i + 1), 16);
                int h2 = Character.digit(value.charAt(i + 2), 16);
                if (h1 >= 0 && h2 >= 0) {
                    sb.append((char) ((h1 << 4) | h2));
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Appends a {@code $count} segment to the resource path, preserving any query
     * parameters (they render after all segments — see {@link #toUrl()}). Produces
     * URLs such as {@code /People/$count?$filter=Age gt 25}.
     */
    public ContextPath addCountSegment() {
        return addSegment("$count");
    }

    public String toUrl() {
        StringBuilder sb = new StringBuilder(basePath);
        appendSegments(sb);
        return sb.toString();
    }

    public String toRelativeUrl() {
        StringBuilder sb = new StringBuilder();
        appendSegments(sb);
        return sb.toString();
    }

    private void appendSegments(StringBuilder sb) {
        // Queries render once, AFTER all segments: emitting them right after their owning
        // segment produces a '?' mid-URL (invalid) whenever a segment is appended later
        // (e.g. addQuery("$skiptoken",...).addSegment("$ref")).
        List<KeyValuePair> deferredQueries = null;
        for (Segment segment : segments) {
            if (!segment.name().isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') sb.append("/");
                sb.append(segment.name());

                if (!segment.keys().isEmpty()) {
                    sb.append("(");
                    if (segment.keys().size() == 1) {
                        sb.append(formatValue(segment.keys().get(0).value()));
                    } else {
                        for (int i = 0; i < segment.keys().size(); i++) {
                            if (i > 0) sb.append(",");
                            KeyValuePair kv = segment.keys().get(i);
                            sb.append(kv.name()).append("=").append(formatValue(kv.value()));
                        }
                    }
                    sb.append(")");
                }
            }

            if (!segment.queries().isEmpty()) {
                if (deferredQueries == null) {
                    deferredQueries = new ArrayList<>();
                }
                deferredQueries.addAll(segment.queries());
            }
        }

        if (deferredQueries != null) {
            sb.append("?");
            for (int i = 0; i < deferredQueries.size(); i++) {
                if (i > 0) sb.append("&");
                KeyValuePair kv = deferredQueries.get(i);
                sb.append(encodeQueryParam(kv.name()));
                sb.append("=");
                sb.append(encodeQueryParam(String.valueOf(kv.value())));
            }
        }
    }

    private static String encodeQueryParam(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
        // Restore OData-safe characters that URLEncoder encodes
        // Single-pass replacement via StringBuilder instead of 9 chained String.replace calls
        StringBuilder sb = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                char h1 = Character.toUpperCase(encoded.charAt(i + 1));
                char h2 = Character.toUpperCase(encoded.charAt(i + 2));
                String seq = "" + h1 + h2;
                switch (seq) {
                    case "24" -> sb.append('$');
                    case "27" -> sb.append('\'');
                    case "28" -> sb.append('(');
                    case "29" -> sb.append(')');
                    case "2C" -> sb.append(',');
                    case "2F" -> sb.append('/');
                    case "3A" -> sb.append(':');
                    case "3D" -> sb.append('=');
                    case "40" -> sb.append('@');
                    default -> { sb.append('%'); sb.append(h1); sb.append(h2); }
                }
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern GUID_PATTERN = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static String formatValue(Object value) {
        if (value instanceof String s) {
            // Edm.Guid keys are written unquoted (e.g. Advertisements(<guid>)); services reject
            // the quoted form ('<guid>') and the guid'...' literal (OData Demo returns 400).
            if (GUID_PATTERN.matcher(s).matches()) return s;
            return "'" + encodeKeyValue(s) + "'";
        }
        return String.valueOf(value);
    }

    private static String encodeKeyValue(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\'' -> sb.append("''");
                case '&'  -> sb.append("%26");
                case '?'  -> sb.append("%3F");
                case '#'  -> sb.append("%23");
                case '%'  -> sb.append("%25");
                case ' '  -> sb.append("%20");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    @SafeVarargs
    private static <T> List<T> append(List<T> list, T... items) {
        var result = new java.util.ArrayList<>(list);
        Collections.addAll(result, items);
        return List.copyOf(result);
    }

    private static <T> List<T> append(List<T> list, T item) {
        var result = new java.util.ArrayList<>(list);
        result.add(item);
        return List.copyOf(result);
    }

    public record Segment(String name, List<KeyValuePair> keys, List<KeyValuePair> queries) {
        public Segment(String name, List<KeyValuePair> keys) {
            this(name, keys, List.of());
        }
    }

    public record KeyValuePair(String name, Object value) {}
}
