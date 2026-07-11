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
                sb.append("?");
                for (int i = 0; i < segment.queries().size(); i++) {
                    if (i > 0) sb.append("&");
                    KeyValuePair kv = segment.queries().get(i);
                    sb.append(encodeQueryParam(kv.name()));
                    sb.append("=");
                    sb.append(encodeQueryParam(String.valueOf(kv.value())));
                }
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
