package io.github.akbarhusain.odata.runtime.entity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record ContextPath(
    String basePath,
    List<Segment> segments
) {
    public ContextPath {
        Objects.requireNonNull(basePath);
        Objects.requireNonNull(segments);
        segments = List.copyOf(segments);
    }

    public ContextPath(String basePath) {
        this(basePath, List.of());
    }

    public ContextPath addSegment(String segment) {
        requireName(segment, "segment");
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
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("key name must not be blank");
        }
        requireNoControl(name, "key name");
        if (value == null) {
            throw new IllegalArgumentException("key '" + name + "' value must not be null");
        }
        if (edmType == null || edmType.isBlank()) {
            throw new IllegalArgumentException("key '" + name + "' edmType must not be blank");
        }
        formatTypedValue(value, edmType);
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
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("key name must not be blank");
        }
        requireNoControl(name, "key name");
        if (value == null) {
            throw new IllegalArgumentException("key '" + name + "' value must not be null");
        }
        Segment last = segments.get(segments.size() - 1);
        Segment updated = new Segment(last.name(), append(last.keys(), new KeyValuePair(name, value)), last.queries());
        List<Segment> newSegments = new ArrayList<>(segments);
        newSegments.set(newSegments.size() - 1, updated);
        return new ContextPath(basePath, List.copyOf(newSegments));
    }

    public ContextPath addQuery(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("query parameter name must not be blank");
        }
        requireNoControl(name, "query parameter name");
        if (value == null) {
            throw new IllegalArgumentException("query parameter '" + name + "' value must not be null");
        }
        if ("$search".equals(name)) {
            validateSearchTerm(value);
        }
        // OData v4.01 Part 2 §5.1: "The same system query option, irrespective of casing
        // or whether or not it is prefixed with a $, MUST NOT be specified more than once
        // for any resource." A URL with two copies is therefore non-conformant, and in
        // practice services answer it inconsistently (400, or silently honouring one of
        // the two). It is reachable without any mistake on the caller's part: chaining an
        // option onto an @odata.nextLink that already carried it, or adding a query after
        // appending another segment (which places it on a different segment than the
        // earlier copy). Last-writer-wins is what re-applying an option means, and it
        // keeps CUSTOM options repeatable, which the spec does not forbid.
        // The superseded copy may live on ANY segment — a nextLink's query segment and
        // the segment a later addQuery landed on are different ones — so strip across
        // all of them, not just the one the new query is about to join.
        if (segments.isEmpty()) {
            return new ContextPath(basePath, List.of(
                    new Segment("", List.of(), List.of(new KeyValuePair(name, value)))));
        }
        String systemKey = systemOptionKey(name);
        List<Segment> rewritten = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            rewritten.add(systemKey == null ? segment
                    : new Segment(segment.name(), segment.keys(), withoutSystem(segment.queries(), systemKey)));
        }
        Segment last = rewritten.get(rewritten.size() - 1);
        List<KeyValuePair> merged = new ArrayList<>(last.queries());
        merged.add(new KeyValuePair(name, value));
        rewritten.set(rewritten.size() - 1, new Segment(last.name(), last.keys(), List.copyOf(merged)));
        return new ContextPath(basePath, rewritten);
    }

    private static List<KeyValuePair> withoutSystem(List<KeyValuePair> queries, String systemKey) {
        if (queries.isEmpty()) {
            return queries;
        }
        List<KeyValuePair> result = new ArrayList<>(queries.size());
        for (KeyValuePair pair : queries) {
            if (systemKey.equals(systemOptionKey(pair.name()))) {
                continue;
            }
            result.add(pair);
        }
        return List.copyOf(result);
    }

    /**
     * Drops every SYSTEM query option that is not in {@code allowed}, plus any service
     * continuation token. Names are matched case-insensitively and with the {@code $}
     * prefix optional (OData 4.01 services accept both spellings).
     *
     * <p>Used where a resource path accepts a strict subset of the system options —
     * {@code /$count} permits only {@code $filter} and {@code $search} (§5.1) and its count
     * "MUST NOT be affected by {@code $top}, {@code $skip}, {@code $orderby}, or
     * {@code $expand}" (§4.8). A nextLink-derived path carries exactly the disallowed
     * paging options plus a {@code $skiptoken}, so this is what makes
     * {@code nextPage(link).countValue()} emit a legal URL.
     *
     * <p>CUSTOM options and PARAMETER ALIASES are preserved deliberately. They are the
     * caller's explicit instruction, and a retained {@code $filter} may well reference an
     * alias ({@code $filter=contains(@w,Title)&@w='x'}) — dropping the alias would leave a
     * dangling reference, which is a worse URL than the one being fixed.
     */
    public ContextPath retainSystemQueryOptions(String... allowed) {
        java.util.Set<String> permitted = new java.util.HashSet<>();
        for (String name : allowed) {
            String key = systemOptionKey(name);
            if (key != null) {
                permitted.add(key);
            }
        }
        List<Segment> rewritten = new ArrayList<>(segments.size());
        boolean changed = false;
        for (Segment segment : segments) {
            List<KeyValuePair> kept = new ArrayList<>(segment.queries().size());
            for (KeyValuePair pair : segment.queries()) {
                String key = systemOptionKey(pair.name());
                boolean droppable = key != null
                        ? !permitted.contains(key)
                        : isContinuationToken(pair.name());
                if (droppable) {
                    changed = true;
                } else {
                    kept.add(pair);
                }
            }
            rewritten.add(new Segment(segment.name(), segment.keys(), List.copyOf(kept)));
        }
        return changed ? new ContextPath(basePath, rewritten) : this;
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

    /**
     * Canonical identity of a system query option, or {@code null} when {@code name} is a
     * custom option or a parameter alias. The canonical form keeps the leading {@code $} so
     * an alias ({@code @p0}) or custom name never collides with an option sharing its text.
     */
    private static String systemOptionKey(String name) {
        if (name.isEmpty()) {
            return null;
        }
        String bare = name.charAt(0) == '$' ? name.substring(1) : name;
        return isSystemQueryOption(bare) ? "$" + bare.toLowerCase(Locale.ROOT) : null;
    }

    /**
     * True for a SYSTEM query option. Deliberately narrow: an over-broad list silently
     * collapses CUSTOM options, which the spec permits to be named anything not starting
     * with {@code $} or {@code @} — including {@code value}, which is a PATH SEGMENT
     * ({@code /$value}, §4.7) and a symbol usable inside {@code $select}/{@code $expand},
     * never a query option. {@code $id} IS one (§4.3.4: "The entity-id MUST be specified
     * using the system query option $id").
     */
    private static boolean isSystemQueryOption(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "filter", "select", "expand", "orderby", "top", "skip", "count",
                 "search", "apply", "format", "schemaversion", "index", "compute",
                 "id" -> true;
            default -> false;
        };
    }

    /**
     * Continuation tokens services put on a page link. They are not standard system
     * options, but they are equally meaningless outside server-driven paging, so a
     * resource path that does not page drops them too.
     */
    private static boolean isContinuationToken(String name) {
        // Only the '$'-prefixed spelling counts: that is the convention services use
        // ($skiptoken, $deltatoken), and requiring it keeps a custom option that happens
        // to be called 'page' or 'cursor' out of scope.
        if (name.isEmpty() || name.charAt(0) != '$') {
            return false;
        }
        return switch (name.substring(1).toLowerCase(Locale.ROOT)) {
            case "skiptoken", "deltatoken", "opaque" -> true;
            default -> false;
        };
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
            // OData ABNF: queryOptions = queryOption *( "&" queryOption ). ';' is NOT a
            // query separator — it separates options INSIDE a parenthesized $expand /
            // $select group (Trips($top=2;$select=Name)), so splitting on it tears
            // every nextLink that carries nested expand options apart
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
                sb.append(encodePathSpaces(segment.name()));

                if (!segment.keys().isEmpty()) {
                    sb.append("(");
                    if (segment.keys().size() == 1) {
                        sb.append(encodePathSpaces(formatValue(segment.keys().get(0).value())));
                    } else {
                        for (int i = 0; i < segment.keys().size(); i++) {
                            if (i > 0) sb.append(",");
                            KeyValuePair kv = segment.keys().get(i);
                            sb.append(kv.name()).append("=").append(encodePathSpaces(formatValue(kv.value())));
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

    private static String encodePathSpaces(String value) {
        return value.indexOf(' ') < 0 ? value : value.replace(" ", "%20");
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

    private record TypedValue(Object value, String edmType) {}

    private static String formatValue(Object valueOrTyped) {
        if (valueOrTyped instanceof TypedValue typed) {
            return formatTypedValue(typed.value(), typed.edmType());
        }
        if (valueOrTyped instanceof String s) {
            return ODataLiteral.isGuid(s) ? s : ODataLiteral.format(s, "Edm.String");
        }
        return String.valueOf(valueOrTyped);
    }

    public static String formatTypedValue(Object value, String edmType) {
        return ODataLiteral.format(value, edmType);
    }

    public static String formatTemporal(Object value) {
        return ODataLiteral.formatTemporal(value);
    }

    public static String enumWireName(Enum<?> e) {
        return e instanceof ODataEnumValue w ? w.wireName() : e.name();
    }

    private static void requireName(String value, String kind) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(kind + " name must not be blank");
        }
    }

    private static void requireNoControl(String value, String kind) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException(kind + " contains a control character");
            }
        }
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
        public Segment {
            if (name == null || ( !name.isEmpty() && name.isBlank())) {
                throw new IllegalArgumentException("segment name must not be blank");
            }
            requireNoControl(name, "segment name");
            keys = List.copyOf(keys);
            queries = List.copyOf(queries);
        }

        public Segment(String name, List<KeyValuePair> keys) {
            this(name, keys, List.of());
        }
    }

    public record KeyValuePair(String name, Object value) {
        public KeyValuePair {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("key/value name must not be blank");
            }
            requireNoControl(name, "key/value name");
            if (value == null) {
                throw new IllegalArgumentException("key/value value must not be null");
            }
        }
    }
}
