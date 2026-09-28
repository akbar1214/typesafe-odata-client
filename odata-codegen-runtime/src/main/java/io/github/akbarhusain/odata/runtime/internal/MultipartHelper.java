package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.batch.Changeset;
import io.github.akbarhusain.odata.runtime.exception.ODataException;
import io.github.akbarhusain.odata.runtime.http.HttpHeaders;
import io.github.akbarhusain.odata.runtime.http.HttpMethod;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MultipartHelper {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOUBLE_LF = "\n\n".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern STATUS_LINE_PATTERN = Pattern.compile("HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?!\\d)\\s*(.*)");
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile(
            "(?:^|;)\\s*boundary\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Set<String> URL_SEGMENTS = Set.of(
            "all", "any", "apply", "batch", "content", "count", "delta", "entity", "expand", "filter",
            "format", "id", "levels", "metadata", "orderby", "ref", "root", "schema", "search", "select",
            "skip", "top", "value");

    private MultipartHelper() {
    }

    public static String generateBoundary() {
        return "batch_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateChangesetBoundary() {
        return "changeset_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static byte[] encodeBatchRequest(String boundary, List<Object> entries) {
        String validBoundary = requireBoundary(boundary);
        List<Object> prepared = prepareEntries(entries);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object entry : prepared) {
            write(out, "--" + validBoundary + "\r\n");
            if (entry instanceof Changeset changeset) {
                String changesetBoundary = generateChangesetBoundary();
                write(out, "Content-Type: multipart/mixed; boundary=" + changesetBoundary + "\r\n\r\n");
                out.writeBytes(encodeChangeset(changesetBoundary, changeset.operations()));
                write(out, "\r\n");
            } else if (entry instanceof BatchOperation operation) {
                write(out, "Content-Type: application/http\r\n");
                write(out, "Content-Transfer-Encoding: binary\r\n");
                if (operation.contentId() != null) {
                    write(out, "Content-ID: " + operation.contentId() + "\r\n");
                }
                write(out, "\r\n");
                encodeOperation(out, operation);
                write(out, "\r\n");
            } else {
                throw new IllegalArgumentException("batch entry must be a BatchOperation or Changeset");
            }
        }
        write(out, "--" + validBoundary + "--\r\n");
        return out.toByteArray();
    }

    public static byte[] encodeChangeset(String boundary, List<BatchOperation> operations) {
        return encodeChangeset(boundary, operations, 1);
    }

    public static byte[] encodeChangeset(String boundary, List<BatchOperation> operations, int startContentId) {
        String validBoundary = requireBoundary(boundary);
        if (startContentId < 1) {
            throw new IllegalArgumentException("startContentId must be positive");
        }
        List<BatchOperation> prepared = prepareOperations(operations, startContentId, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (BatchOperation operation : prepared) {
            write(out, "--" + validBoundary + "\r\n");
            write(out, "Content-Type: application/http\r\n");
            write(out, "Content-Transfer-Encoding: binary\r\n");
            write(out, "Content-ID: " + operation.contentId() + "\r\n\r\n");
            encodeOperation(out, operation);
            write(out, "\r\n");
        }
        write(out, "--" + validBoundary + "--\r\n");
        return out.toByteArray();
    }

    public static byte[] encodeRequest(String boundary, List<BatchOperation> operations) {
        String validBoundary = requireBoundary(boundary);
        List<BatchOperation> prepared = prepareOperations(operations, 1, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (BatchOperation operation : prepared) {
            write(out, "--" + validBoundary + "\r\n");
            write(out, "Content-Type: application/http\r\n");
            write(out, "Content-Transfer-Encoding: binary\r\n");
            if (operation.contentId() != null) {
                write(out, "Content-ID: " + operation.contentId() + "\r\n");
            }
            write(out, "\r\n");
            encodeOperation(out, operation);
            write(out, "\r\n");
        }
        write(out, "--" + validBoundary + "--\r\n");
        return out.toByteArray();
    }

    public static List<Object> prepareEntries(List<Object> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        List<Object> source = new ArrayList<>(entries.size());
        Set<String> used = new java.util.HashSet<>();
        for (Object entry : entries) {
            if (entry instanceof BatchOperation operation) {
                Objects.requireNonNull(operation, "batch operation must not be null");
                addExplicitId(used, operation);
                source.add(operation);
            } else if (entry instanceof Changeset changeset) {
                Objects.requireNonNull(changeset, "changeset must not be null");
                source.add(changeset);
                for (BatchOperation operation : changeset.operations()) {
                    addExplicitId(used, operation);
                }
            } else {
                throw new IllegalArgumentException("batch entry must be a BatchOperation or Changeset");
            }
        }

        Counter next = new Counter(1);
        List<Object> assigned = new ArrayList<>(source.size());
        for (Object entry : source) {
            if (entry instanceof BatchOperation operation) {
                assigned.add(assignIfNeeded(operation, next, used, false));
            } else {
                Changeset changeset = (Changeset) entry;
                List<BatchOperation> operations = new ArrayList<>(changeset.size());
                for (BatchOperation operation : changeset.operations()) {
                    operations.add(assignIfNeeded(operation, next, used, true));
                }
                assigned.add(new Changeset(operations));
            }
        }
        return resolveReferences(assigned);
    }

    public static List<BatchResult<?>> decodeResponse(String boundary, byte[] body) {
        return decodeResponseDetailed(boundary, body).results();
    }

    public static DecodedResponse decodeResponseDetailed(String boundary, byte[] body) {
        String validBoundary = requireBoundary(boundary);
        if (body == null || body.length == 0) {
            throw new ODataException("Malformed multipart response: body is empty");
        }
        List<DecodedPart> parts = new ArrayList<>();
        int[] wireIndex = {0};
        int[] groupIndex = {0};
        decodeParts(("--" + validBoundary).getBytes(StandardCharsets.US_ASCII), body, 0, body.length,
                null, parts, wireIndex, groupIndex);
        if (parts.isEmpty()) {
            throw new ODataException("Malformed multipart response: no response parts");
        }
        return new DecodedResponse(parts);
    }

    public static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        List<String> parts = splitContentType(contentType);
        if (parts.isEmpty() || !parts.get(0).strip().equalsIgnoreCase("multipart/mixed")) {
            return null;
        }
        String found = null;
        for (int i = 1; i < parts.size(); i++) {
            String parameter = parts.get(i);
            int equals = parameter.indexOf('=');
            if (equals < 0 || !parameter.substring(0, equals).strip().equalsIgnoreCase("boundary")) {
                continue;
            }
            String value = parameter.substring(equals + 1).strip();
            boolean quoted = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
            if (quoted) {
                value = value.substring(1, value.length() - 1);
            } else if (value.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("unquoted multipart boundary must not contain whitespace");
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException("multipart boundary must not be empty");
            }
            value = requireBoundary(value);
            if (found != null && !found.equals(value)) {
                throw new IllegalArgumentException("conflicting multipart boundary parameters");
            }
            found = value;
        }
        return found;
    }

    public static boolean isMultipartMixed(String contentType) {
        if (contentType == null) {
            return false;
        }
        List<String> parts = splitContentType(contentType);
        return !parts.isEmpty() && parts.get(0).strip().equalsIgnoreCase("multipart/mixed");
    }

    public static String requireBoundary(String boundary) {
        if (boundary == null || boundary.isBlank() || boundary.length() > 70) {
            throw new IllegalArgumentException("multipart boundary must be non-blank and at most 70 characters");
        }
        if (boundary.startsWith("--") || boundary.endsWith(" ") || boundary.endsWith("\t")) {
            throw new IllegalArgumentException("multipart boundary has invalid edge characters");
        }
        for (int i = 0; i < boundary.length(); i++) {
            char c = boundary.charAt(i);
            boolean allowed = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || "'()+_,-./:=? ".indexOf(c) >= 0;
            if (!allowed || c == '\r' || c == '\n' || c == '\0') {
                throw new IllegalArgumentException("multipart boundary contains an invalid character");
            }
        }
        return boundary;
    }

    private static List<BatchOperation> prepareOperations(List<BatchOperation> operations, int start,
                                                            boolean assignIds) {
        Objects.requireNonNull(operations, "operations must not be null");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("multipart operation list must not be empty");
        }
        Set<String> used = new java.util.HashSet<>();
        for (BatchOperation operation : operations) {
            Objects.requireNonNull(operation, "batch operation must not be null");
            if (assignIds && operation.method() == HttpMethod.GET) {
                throw new IllegalArgumentException("GET operations are not allowed in a changeset");
            }
            addExplicitId(used, operation);
        }
        Counter next = new Counter(start);
        List<BatchOperation> assigned = new ArrayList<>(operations.size());
        for (BatchOperation operation : operations) {
            assigned.add(assignIfNeeded(operation, next, used, assignIds));
        }
        return resolveOperationReferences(assigned);
    }

    private static BatchOperation assignIfNeeded(BatchOperation operation, Counter next, Set<String> used,
                                                  boolean assign) {
        if (operation.contentId() != null || !assign) {
            return operation;
        }
        String id;
        do {
            id = Integer.toString(next.value++);
        } while (!used.add(id));
        return operation.withContentId(id);
    }

    private static void addExplicitId(Set<String> used, BatchOperation operation) {
        if (operation.contentId() == null) {
            return;
        }
        String id = BatchOperation.canonicalContentId(operation.contentId());
        if (!used.add(id)) {
            throw new IllegalArgumentException("duplicate Content-ID in batch: " + operation.contentId());
        }
    }

    private static List<Object> resolveReferences(List<Object> entries) {
        Map<String, String> urls = new HashMap<>();
        List<Object> result = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (entry instanceof BatchOperation operation) {
                BatchOperation resolved = resolveOperation(operation, urls);
                if (resolved.contentId() != null) {
                    urls.put(BatchOperation.canonicalContentId(resolved.contentId()), resolved.url());
                }
                result.add(resolved);
            } else {
                Changeset changeset = (Changeset) entry;
                List<BatchOperation> operations = new ArrayList<>(changeset.size());
                for (BatchOperation operation : changeset.operations()) {
                    BatchOperation resolved = resolveOperation(operation, urls);
                    if (resolved.contentId() != null) {
                        urls.put(BatchOperation.canonicalContentId(resolved.contentId()), resolved.url());
                    }
                    operations.add(resolved);
                }
                result.add(new Changeset(operations));
            }
        }
        return List.copyOf(result);
    }

    private static List<BatchOperation> resolveOperationReferences(List<BatchOperation> operations) {
        Map<String, String> urls = new HashMap<>();
        List<BatchOperation> result = new ArrayList<>(operations.size());
        for (BatchOperation operation : operations) {
            BatchOperation resolved = resolveOperation(operation, urls);
            if (resolved.contentId() != null) {
                urls.put(BatchOperation.canonicalContentId(resolved.contentId()), resolved.url());
            }
            result.add(resolved);
        }
        return List.copyOf(result);
    }

    private static BatchOperation resolveOperation(BatchOperation operation, Map<String, String> urls) {
        String source = operation.url();
        // Content-ID references appear in the request path (`POST $1/Orders`); the query
        // string holds system query options ($filter, $skiptoken, $compute, ...) which must
        // never be mistaken for references (OData 4.01 Part 1 §11.7.7.2).
        int queryStart = source.indexOf('?');
        String path = queryStart < 0 ? source : source.substring(0, queryStart);
        String query = queryStart < 0 ? "" : source.substring(queryStart);
        String resolved = resolveReferencesInPath(path, urls);
        String url = resolved + query;
        return url.equals(source) ? operation : operation.withUrl(url);
    }

    private static String resolveReferencesInPath(String source, Map<String, String> urls) {
        StringBuilder result = new StringBuilder(source.length());
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\'' && !doubleQuoted) {
                result.append(c);
                if (singleQuoted && i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                    result.append(source.charAt(++i));
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (c == '"' && !singleQuoted) {
                result.append(c);
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (c != '$' || singleQuoted || doubleQuoted || i > 0 && isReferenceChar(source.charAt(i - 1))) {
                result.append(c);
                continue;
            }
            int end = i + 1;
            while (end < source.length() && isReferenceChar(source.charAt(end))) {
                end++;
            }
            if (end == i + 1) {
                result.append(c);
                continue;
            }
            String id = source.substring(i + 1, end);
            String replacement = urls.get(id);
            if (replacement == null) {
                if (URL_SEGMENTS.contains(id)) {
                    result.append(source, i, end);
                } else {
                    throw new IllegalArgumentException("unresolved changeset Content-ID reference: $" + id);
                }
            } else {
                result.append(replacement);
            }
            i = end - 1;
        }
        return result.toString();
    }

    private static boolean isReferenceChar(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9' || value == '_' || value == '-' || value == '.';
    }

    private static void encodeOperation(ByteArrayOutputStream out, BatchOperation operation) {
        write(out, operation.method().name() + " " + operation.url() + " HTTP/1.1\r\n");
        boolean hasContentType = operation.headers().keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase("Content-Type"));
        if (operation.body() != null && operation.body().length > 0 && !hasContentType
                && operation.method() != HttpMethod.GET && operation.method() != HttpMethod.DELETE) {
            write(out, "Content-Type: application/json\r\n");
        }
        for (Map.Entry<String, List<String>> entry : operation.headers().entrySet()) {
            for (String value : entry.getValue()) {
                write(out, entry.getKey() + ": " + value + "\r\n");
            }
        }
        write(out, "\r\n");
        if (operation.body() != null && operation.body().length > 0) {
            out.writeBytes(operation.body());
        }
    }

    private static void decodeParts(byte[] delimiter, byte[] body, int startPos, int endPos,
                                    String groupId, List<DecodedPart> results, int[] wireIndex,
                                    int[] groupIndex) {
        BoundaryMatch first = findBoundary(body, delimiter, startPos, endPos);
        if (first == null || first.closing()) {
            throw new ODataException("Malformed multipart response: no opening boundary");
        }
        int pos = first.contentStart();
        while (true) {
            BoundaryMatch next = findBoundary(body, delimiter, pos, endPos);
            if (next == null) {
                throw new ODataException("Malformed multipart response: missing closing boundary");
            }
            int partEnd = next.start();
            if (partEnd > pos && body[partEnd - 1] == '\n') {
                partEnd--;
                if (partEnd > pos && body[partEnd - 1] == '\r') {
                    partEnd--;
                }
            }
            if (partEnd <= pos || isBlank(body, pos, partEnd)) {
                throw new ODataException("Malformed multipart response: blank part");
            }
            decodePartOrNested(Arrays.copyOfRange(body, pos, partEnd), groupId, results, wireIndex, groupIndex);
            if (next.closing()) {
                return;
            }
            pos = next.contentStart();
        }
    }

    private static void decodePartOrNested(byte[] part, String groupId, List<DecodedPart> results,
                                           int[] wireIndex, int[] groupIndex) {
        int separator = indexOf(part, CRLFCRLF, 0, part.length);
        int separatorLength = 4;
        if (separator < 0) {
            separator = indexOf(part, DOUBLE_LF, 0, part.length);
            separatorLength = 2;
        }
        if (separator < 0) {
            throw new ODataException("Malformed multipart response: part has no header/body separator");
        }
        Map<String, List<String>> partHeaders = parseHeaders(
                new String(part, 0, separator, StandardCharsets.UTF_8), false);
        String contentId = singleHeader(partHeaders, "Content-ID");
        String contentType = singleHeader(partHeaders, "Content-Type");
        byte[] content = Arrays.copyOfRange(part, separator + separatorLength, part.length);
        if (isMultipartMixed(contentType)) {
            String nestedBoundary = extractBoundary(contentType);
            if (nestedBoundary == null) {
                throw new ODataException("Malformed multipart response: nested multipart has no boundary");
            }
            int before = results.size();
            String nestedGroup = groupId != null ? groupId : "changeset-" + groupIndex[0]++;
            decodeParts(("--" + nestedBoundary).getBytes(StandardCharsets.US_ASCII), content, 0,
                    content.length, nestedGroup, results, wireIndex, groupIndex);
            if (before == results.size()) {
                throw new ODataException("Malformed multipart response: nested multipart has no parts");
            }
            if (contentId != null) {
                if (results.size() != before + 1) {
                    throw new ODataException("Malformed multipart response: nested group has multiple Content-IDs");
                }
                if (results.get(before).result().contentId() == null) {
                    DecodedPart only = results.get(before);
                    results.set(before, new DecodedPart(
                            only.result().withContentIdAndGroup(contentId, nestedGroup), nestedGroup, only.wireIndex()));
                }
            }
            return;
        }
        if (content == null || content.length == 0 && isBlank(part, separator + separatorLength, part.length)) {
            throw new ODataException("Malformed multipart response: empty part body");
        }
        results.add(new DecodedPart(decodeSinglePart(content, contentId, groupId, wireIndex[0]++),
                groupId, wireIndex[0] - 1));
    }

    private static BatchResult<?> decodeSinglePart(byte[] httpBlock, String contentId, String groupId,
                                                   int wireIndex) {
        int separator = indexOf(httpBlock, CRLFCRLF, 0, httpBlock.length);
        int separatorLength = 4;
        int headerEnd;
        int bodyStart;
        if (separator >= 0) {
            headerEnd = separator;
            bodyStart = separator + separatorLength;
        } else {
            separator = indexOf(httpBlock, DOUBLE_LF, 0, httpBlock.length);
            if (separator >= 0) {
                headerEnd = separator;
                bodyStart = separator + 2;
            } else {
                int trimmed = httpBlock.length;
                if (trimmed > 0 && httpBlock[trimmed - 1] == '\n') {
                    trimmed--;
                    if (trimmed > 0 && httpBlock[trimmed - 1] == '\r') {
                        trimmed--;
                    }
                }
                if (trimmed == 0) {
                    throw new ODataException("Malformed multipart response: empty HTTP part");
                }
                headerEnd = trimmed;
                bodyStart = trimmed;
            }
        }
        String headerBlock = new String(httpBlock, 0, headerEnd, StandardCharsets.UTF_8);
        String[] lines = headerBlock.split("\\r?\\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new ODataException("Malformed multipart response: empty HTTP part");
        }
        Matcher status = STATUS_LINE_PATTERN.matcher(lines[0].strip());
        if (!status.matches()) {
            throw new ODataException("Malformed multipart response: unparseable status line");
        }
        int statusCode;
        try {
            statusCode = Integer.parseInt(status.group(1));
        } catch (NumberFormatException e) {
            throw new ODataException("Malformed multipart response: invalid status code", e);
        }
        Map<String, List<String>> headers = parseHeaders(String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length)), true);
        String embeddedContentId = singleHeader(headers, "Content-ID");
        if (contentId != null && embeddedContentId != null
                && !BatchOperation.canonicalContentId(contentId)
                        .equals(BatchOperation.canonicalContentId(embeddedContentId))) {
            throw new ODataException("Malformed multipart response: conflicting Content-ID headers");
        }
        if (contentId != null) {
            headers.put("Content-ID", List.of(contentId));
        }
        byte[] body = bodyStart < httpBlock.length
                ? Arrays.copyOfRange(httpBlock, bodyStart, httpBlock.length) : null;
        if (body != null && body.length == 0) {
            body = null;
        }
        return new BatchResult<>(statusCode, headers, body, Object.class,
                contentId != null ? contentId : embeddedContentId, null, groupId, wireIndex);
    }

    private static Map<String, List<String>> parseHeaders(String headerBlock, boolean response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (headerBlock.isBlank()) {
            return headers;
        }
        for (String line : headerBlock.split("\\r?\\n", -1)) {
            if (line.isBlank()) {
                throw new ODataException("Malformed multipart response: blank header line");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new ODataException("Malformed multipart response: invalid header line");
            }
            String name = line.substring(0, colon).strip();
            if (response && name.startsWith(":") && name.length() > 1) {
                name = ":" + HttpHeaders.requireName(name.substring(1));
            } else {
                name = HttpHeaders.requireName(name);
            }
            String value = line.substring(colon + 1).strip();
            HttpHeaders.requireValue(name, value);
            String existing = null;
            for (String key : headers.keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    existing = key;
                    break;
                }
            }
            if (existing == null) {
                headers.put(name, new ArrayList<>(List.of(value)));
            } else {
                headers.get(existing).add(value);
            }
        }
        for (String key : List.of("Content-Type", "Content-ID")) {
            List<String> values = findHeader(headers, key);
            if (values != null && values.size() > 1
                    && values.stream().distinct().count() > 1) {
                throw new ODataException("Malformed multipart response: conflicting " + key + " headers");
            }
        }
        return headers;
    }

    private static List<String> findHeader(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String singleHeader(Map<String, List<String>> headers, String name) {
        List<String> values = findHeader(headers, name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new ODataException("Malformed multipart response: duplicate " + name + " header");
        }
        return values.get(0);
    }

    private static List<String> splitContentType(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\' && quoted) {
                current.append(c);
                escaped = true;
            } else if (c == '"') {
                current.append(c);
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quoted Content-Type parameter");
        }
        parts.add(current.toString());
        return parts;
    }

    private static BoundaryMatch findBoundary(byte[] body, byte[] delimiter, int from, int to) {
        int start = Math.max(from, 0);
        for (int i = start; i <= to - delimiter.length; i++) {
            if ((i != 0 && body[i - 1] != '\n') || !startsWith(body, i, delimiter)) {
                continue;
            }
            int p = i + delimiter.length;
            boolean closing = p + 1 < to && body[p] == '-' && body[p + 1] == '-';
            if (closing) {
                p += 2;
            }
            while (p < to && (body[p] == ' ' || body[p] == '\t')) {
                p++;
            }
            if (p < to && body[p] == '\r' && p + 1 < to && body[p + 1] == '\n') {
                p += 2;
            } else if (p < to && body[p] == '\n') {
                p++;
            } else if (p != to || !closing) {
                continue;
            }
            return new BoundaryMatch(i, p, closing);
        }
        return null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from, int to) {
        int start = Math.max(from, 0);
        for (int i = start; i <= to - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] haystack, int pos, byte[] needle) {
        if (pos < 0 || pos + needle.length > haystack.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (haystack[pos + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            byte b = bytes[i];
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }

    private static void write(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record BoundaryMatch(int start, int contentStart, boolean closing) {
    }

    private static final class Counter {
        private int value;

        private Counter(int value) {
            this.value = value;
        }
    }

    public record DecodedPart(BatchResult<?> result, String groupId, int wireIndex) {
        public DecodedPart {
            Objects.requireNonNull(result, "result must not be null");
        }
    }

    public static final class DecodedResponse {
        private final List<DecodedPart> parts;

        private DecodedResponse(List<DecodedPart> parts) {
            this.parts = List.copyOf(parts);
        }

        public List<DecodedPart> parts() {
            return parts;
        }

        public List<BatchResult<?>> results() {
            return parts.stream().map(DecodedPart::result).toList();
        }

        public List<BatchResult<?>> wireOrder() {
            return results();
        }
    }
}
