package com.modernodata.runtime.entity;

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
        Segment updated = new Segment(last.name(), append(last.keys(), new KeyValuePair(name, value)));
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
                if (sb.length() > 0 && !sb.toString().endsWith("/")) sb.append("/");
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
        encoded = encoded.replace("%24", "$");
        encoded = encoded.replace("%27", "'");
        encoded = encoded.replace("%28", "(");
        encoded = encoded.replace("%29", ")");
        encoded = encoded.replace("%2C", ",");
        encoded = encoded.replace("%2F", "/");
        encoded = encoded.replace("%3A", ":");
        encoded = encoded.replace("%3D", "=");
        encoded = encoded.replace("%40", "@");
        return encoded;
    }

    private static String formatValue(Object value) {
        if (value instanceof String s) return "'" + s + "'";
        return String.valueOf(value);
    }

    @SafeVarargs
    private static <T> List<T> append(List<T> list, T... items) {
        var result = new java.util.ArrayList<>(list);
        Collections.addAll(result, items);
        return List.copyOf(result);
    }

    public record Segment(String name, List<KeyValuePair> keys, List<KeyValuePair> queries) {
        public Segment(String name, List<KeyValuePair> keys) {
            this(name, keys, List.of());
        }
    }

    public record KeyValuePair(String name, Object value) {}
}
