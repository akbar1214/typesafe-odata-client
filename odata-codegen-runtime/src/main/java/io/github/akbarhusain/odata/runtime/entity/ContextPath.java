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

    /**
     * Adds a key with its Edm type so the literal renders per the OData ABNF instead of
     * by value-shape heuristics: Edm.String is always quoted (even when UUID-shaped),
     * Edm.Guid/Date/DateTimeOffset/TimeOfDay are bare, Edm.Duration is
     * {@code duration'...'}, and qualified enum types render {@code NS.Enum'Member'}.
     */
    public ContextPath addKey(String name, Object value, String edmType) {
        if (segments.isEmpty()) {
            throw new IllegalStateException("Cannot add key without a segment");
        }
        Segment last = segments.get(segments.size() - 1);
        Segment updated = new Segment(last.name(),
                append(last.keys(), new KeyValuePair(name, new TypedValue(value, edmType))),
                last.queries());
        List<Segment> newSegments = new ArrayList<>(segments);
        newSegments.set(newSegments.size() - 1, updated);
        return new ContextPath(basePath, List.copyOf(newSegments));
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
        if ("$search".equals(name)) {
            validateSearchTerm(value);
        }
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

    /**
     * $search has its own grammar (terms, quoted phrases, AND/OR/NOT, parentheses) which
     * we pass through verbatim — but control characters would corrupt the URL. The full
     * grammar is allowed; anything below 0x20 or DEL is rejected.
     */
    private static void validateSearchTerm(String term) {
        if (term == null) {
            throw new IllegalArgumentException("$search term must not be null");
        }
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException(
                        "$search term contains a control character at index " + i
                                + " — use the OData $search grammar (terms, \"quoted phrases\", AND/OR/NOT)");
            }
        }
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
        // Fragments are not part of an OData request URL; strip before splitting
        int hashIdx = trimmed.indexOf('#');
        if (hashIdx >= 0) {
            trimmed = trimmed.substring(0, hashIdx);
        }
        String pathPart = trimmed;
        String queryPart = null;
        int queryIdx = trimmed.indexOf('?');
        if (queryIdx >= 0) {
            pathPart = trimmed.substring(0, queryIdx);
            queryPart = trimmed.substring(queryIdx + 1);
        }
        String base;
        if (pathPart.regionMatches(true, 0, "http", 0, 4)) {
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
            // The OData URL grammar allows ';' as a query-option separator too
            for (String pair : queryPart.split("[&;]")) {
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
     * must not be used here. Malformed escapes are left verbatim. Bytes are collected
     * and decoded once as UTF-8 — per-char decoding turns multi-byte sequences
     * ({@code %C3%A9}) into mojibake.
     */
    private static String decodePercent(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(value.length());
        StringBuilder verbatim = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int h1 = Character.digit(value.charAt(i + 1), 16);
                int h2 = Character.digit(value.charAt(i + 2), 16);
                if (h1 >= 0 && h2 >= 0) {
                    flushVerbatim(bytes, verbatim);
                    bytes.write((h1 << 4) | h2);
                    i += 2;
                    continue;
                }
            }
            verbatim.append(c);
        }
        if (verbatim.length() > 0) {
            flushVerbatim(bytes, verbatim);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Verbatim chars are ASCII-safe here: they came from the URL's own characters. */
    private static void flushVerbatim(java.io.ByteArrayOutputStream bytes, StringBuilder verbatim) {
        for (int i = 0; i < verbatim.length(); i++) {
            char c = verbatim.charAt(i);
            // Non-ASCII verbatim chars (already-decoded text, not raw percent escapes)
            // cannot go through the byte stream — append after decoding instead.
            if (c < 0x80) {
                bytes.write(c);
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(utf8);
            }
        }
        verbatim.setLength(0);
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

    /** Carries the Edm type alongside the raw key value so formatting is type-driven. */
    private record TypedValue(Object value, String edmType) {}

    private static String formatValue(Object valueOrTyped) {
        if (valueOrTyped instanceof TypedValue typed && typed.edmType() != null && !typed.edmType().isBlank()) {
            return formatTypedValue(typed.value(), typed.edmType());
        }
        Object value = valueOrTyped instanceof TypedValue typed ? typed.value() : valueOrTyped;
        // legacy untyped path (direct addKey callers): original heuristics
        if (value instanceof String s) {
            // Edm.Guid keys are written unquoted (e.g. Advertisements(<guid>)); services reject
            // the quoted form ('<guid>') and the guid'...' literal (OData Demo returns 400).
            if (GUID_PATTERN.matcher(s).matches()) return s;
            return "'" + encodeKeyValue(s) + "'";
        }
        return String.valueOf(value);
    }

    static String formatTypedValue(Object value, String edmType) {
        return switch (edmType) {
            case "Edm.String" -> "'" + encodeKeyValue(String.valueOf(value)) + "'";
            case "Edm.Guid" -> String.valueOf(value);
            case "Edm.DateTimeOffset", "Edm.Date" -> String.valueOf(value); // bare ISO literals
            case "Edm.TimeOfDay" -> value instanceof java.time.LocalTime t
                    ? t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                    : String.valueOf(value);
            case "Edm.Duration" -> "duration'" + value + "'";
            case "Edm.Boolean", "Edm.Byte", "Edm.SByte", "Edm.Int16", "Edm.Int32", "Edm.Int64",
                 "Edm.Single", "Edm.Double", "Edm.Decimal" -> String.valueOf(value);
            default -> {
                if (value instanceof Enum<?> e) {
                    yield edmType + "'" + enumWireName(e) + "'";
                }
                if (value instanceof String s) yield "'" + encodeKeyValue(s) + "'";
                yield String.valueOf(value);
            }
        };
    }

    /**
     * Enum literals must carry the CSDL member name; sanitized Java identifiers
     * ({@code A-B} → {@code A_B}) would be rejected by services. Generated enums
     * implement {@link ODataEnumValue}; plain enums keep the {@code name()} fallback.
     */
    public static String enumWireName(Enum<?> e) {
        return e instanceof ODataEnumValue w ? w.wireName() : e.name();
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
                case '/'  -> sb.append("%2F");
                case '+'  -> sb.append("%2B");
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
