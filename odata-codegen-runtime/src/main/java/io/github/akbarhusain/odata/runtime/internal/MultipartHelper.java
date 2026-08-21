package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.batch.Changeset;
import io.github.akbarhusain.odata.runtime.exception.ODataException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes/decodes OData v4 multipart/mixed batch requests and responses.
 *
 * <p>All framing is byte-based: operation bodies are copied verbatim into the encoded
 * request and decoded response bodies are returned byte-for-byte. Never round-trip
 * payloads through {@code String} — that corrupts binary content (media uploads).</p>
 *
 * <p>Decoding fails loudly: undecodable parts, missing delimiters, and bodies without
 * any boundary throw {@link ODataException} rather than returning empty/partial results.</p>
 */
public class MultipartHelper {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOUBLE_LF = "\n\n".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern STATUS_LINE_PATTERN = Pattern.compile("HTTP/\\d\\.\\d\\s+(\\d{3})\\s*(.*)");
    private static final Pattern HEADER_PATTERN = Pattern.compile("^([\\w-]+):\\s*(.*)");
    // boundary parameter may be quoted: boundary="abc" (RFC 2046) — quotes are not part of the value
    // allow spaces around = : boundary = "abc" is legal
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile("boundary\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))");

    private MultipartHelper() {}

    public static String generateBoundary() {
        return "batch_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateChangesetBoundary() {
        return "changeset_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static byte[] encodeBatchRequest(String boundary, List<Object> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Content-IDs must be unique across the WHOLE batch request, so changesets share
        // one counter instead of each restarting at 1 (duplicate IDs make $N references
        // and response correlation ambiguous)
        int nextContentId = 1;
        for (Object entry : entries) {
            write(out, "--" + boundary + "\r\n");
            if (entry instanceof Changeset cs) {
                String csBoundary = generateChangesetBoundary();
                write(out, "Content-Type: multipart/mixed; boundary=" + csBoundary + "\r\n");
                write(out, "\r\n");
                out.writeBytes(encodeChangeset(csBoundary, cs.operations(), nextContentId));
                nextContentId += cs.operations().size();
                write(out, "\r\n");
            } else if (entry instanceof BatchOperation op) {
                write(out, "Content-Type: application/http\r\n");
                write(out, "Content-Transfer-Encoding: binary\r\n");
                write(out, "\r\n");
                encodeOperation(out, op);
                write(out, "\r\n");
            }
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    public static byte[] encodeChangeset(String boundary, List<BatchOperation> operations) {
        return encodeChangeset(boundary, operations, 1);
    }

    public static byte[] encodeChangeset(String boundary, List<BatchOperation> operations, int startContentId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < operations.size(); i++) {
            BatchOperation op = operations.get(i);
            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Type: application/http\r\n");
            write(out, "Content-Transfer-Encoding: binary\r\n");
            write(out, "Content-ID: " + (startContentId + i) + "\r\n");
            write(out, "\r\n");
            encodeOperation(out, op);
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    public static byte[] encodeRequest(String boundary, List<BatchOperation> operations) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (BatchOperation op : operations) {
            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Type: application/http\r\n");
            write(out, "Content-Transfer-Encoding: binary\r\n");
            write(out, "\r\n");
            encodeOperation(out, op);
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void encodeOperation(ByteArrayOutputStream out, BatchOperation op) {
        write(out, op.method().name() + " " + op.url() + " HTTP/1.1\r\n");
        boolean hasContentType = op.headers().keySet().stream()
                .anyMatch(k -> k != null && k.equalsIgnoreCase("Content-Type"));
        if (op.body() != null && op.body().length > 0 && !hasContentType) {
            write(out, "Content-Type: application/json\r\n");
        }
        for (var entry : op.headers().entrySet()) {
            for (String value : entry.getValue()) {
                write(out, entry.getKey() + ": " + value + "\r\n");
            }
        }
        write(out, "\r\n");
        if (op.body() != null && op.body().length > 0) {
            out.writeBytes(op.body());
        }
    }

    private static void write(ByteArrayOutputStream out, String ascii) {
        out.writeBytes(ascii.getBytes(StandardCharsets.UTF_8));
    }

    public static List<BatchResult<?>> decodeResponse(String boundary, byte[] body) {
        if (body == null || body.length == 0) {
            return List.of();
        }
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        List<BatchResult<?>> results = new ArrayList<>();
        decodeParts(delimiter, body, 0, body.length, results);
        return results;
    }

    private static void decodeParts(byte[] delimiter, byte[] body, int startPos, int endPos,
                                    List<BatchResult<?>> results) {
        int pos = indexOfBoundary(body, delimiter, startPos, endPos);
        if (pos < 0 || pos >= endPos) {
            throw new ODataException("Malformed multipart response: no opening boundary '"
                    + new String(delimiter, StandardCharsets.US_ASCII) + "' found");
        }
        pos += delimiter.length;

        boolean foundClosing = false;
        while (pos < endPos) {
            // Skip CRLF or LF after the delimiter
            if (pos + 1 < endPos && body[pos] == '\r' && body[pos + 1] == '\n') {
                pos += 2;
            } else if (pos < endPos && body[pos] == '\n') {
                pos += 1;
            }

            int next = indexOfBoundary(body, delimiter, pos, endPos);
            if (next < 0 || next > endPos) {
                throw new ODataException("Malformed multipart response: missing closing boundary '"
                        + new String(delimiter, StandardCharsets.US_ASCII) + "--'");
            }

            // A delimiter match immediately followed by "--" is the closing delimiter
            boolean closing = startsWith(body, next + delimiter.length,
                    "--".getBytes(StandardCharsets.US_ASCII));

            int partEnd = next;
            if (partEnd - 1 >= 0 && body[partEnd - 1] == '\n') {
                partEnd--;
                if (partEnd - 1 >= 0 && body[partEnd - 1] == '\r') {
                    partEnd--;
                }
            }

            if (partEnd > pos && !isBlank(body, pos, partEnd)) {
                decodePartOrNested(Arrays.copyOfRange(body, pos, partEnd), results);
            }

            if (closing) {
                foundClosing = true;
                return;
            }
            pos = next + delimiter.length;
        }
        if (!foundClosing) {
            throw new ODataException("Malformed multipart response: missing closing boundary '"
                    + new String(delimiter, StandardCharsets.US_ASCII) + "--'");
        }
    }

    private static void decodePartOrNested(byte[] part, List<BatchResult<?>> results) {
        int separatorIdx = indexOf(part, CRLFCRLF, 0, part.length);
        int separatorLen = 4;
        if (separatorIdx < 0) {
            separatorIdx = indexOf(part, DOUBLE_LF, 0, part.length);
            separatorLen = 2;
        }
        if (separatorIdx < 0) {
            throw new ODataException("Malformed multipart response: part has no header/body separator");
        }

        String partHeaders = new String(part, 0, separatorIdx, StandardCharsets.UTF_8);
        byte[] partContent = Arrays.copyOfRange(part, separatorIdx + separatorLen, part.length);

        // Part-level Content-ID: per the batch spec, changeset responses carry the
        // Content-ID in the PART headers (not the embedded HTTP response headers) — it is
        // the only way to correlate responses to requests when a changeset fails
        String contentId = null;
        boolean contentTypeSeen = false;

        // Check for nested multipart/mixed (changeset)
        for (String line : partHeaders.split("\\r?\\n")) {
            Matcher headerMatcher = HEADER_PATTERN.matcher(line);
            if (!headerMatcher.matches()) {
                continue;
            }
            String name = headerMatcher.group(1);
            String value = headerMatcher.group(2);
            if ("Content-ID".equalsIgnoreCase(name)) {
                contentId = value.strip();
            } else if ("Content-Type".equalsIgnoreCase(name) && !contentTypeSeen) {
                contentTypeSeen = true;
                Matcher boundaryMatcher = BOUNDARY_PATTERN.matcher(value);
                if (value.contains("multipart/mixed") && boundaryMatcher.find()) {
                    String nestedBoundary = boundaryMatcher.group(1) != null
                            ? boundaryMatcher.group(1) : boundaryMatcher.group(2);
                    decodeParts(("--" + nestedBoundary).getBytes(StandardCharsets.US_ASCII),
                            partContent, 0, partContent.length, results);
                    return;
                }
            }
        }

        // Not a nested multipart — decode as a regular HTTP part.
        // partContent contains: HTTP/1.1 200 OK\r\nHeaders\r\n\r\nBody (body verbatim).
        results.add(decodeSinglePart(partContent, contentId));
    }

    private static BatchResult<?> decodeSinglePart(byte[] httpBlock, String contentId) {
        int separatorIdx = indexOf(httpBlock, CRLFCRLF, 0, httpBlock.length);
        int headerEnd;
        int bodyStart;
        if (separatorIdx >= 0) {
            headerEnd = separatorIdx;
            bodyStart = separatorIdx + 4;
        } else {
            separatorIdx = indexOf(httpBlock, DOUBLE_LF, 0, httpBlock.length);
            if (separatorIdx < 0) {
                headerEnd = httpBlock.length;
                bodyStart = httpBlock.length;
            } else {
                headerEnd = separatorIdx;
                bodyStart = separatorIdx + 2;
            }
        }

        String headerBlock = new String(httpBlock, 0, headerEnd, StandardCharsets.UTF_8);
        String[] lines = headerBlock.split("\\r?\\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new ODataException("Malformed multipart response: empty part");
        }

        Matcher statusMatcher = STATUS_LINE_PATTERN.matcher(lines[0].strip());
        if (!statusMatcher.matches()) {
            throw new ODataException("Malformed multipart response: unparseable status line '"
                    + truncate(lines[0].strip(), 80) + "'");
        }

        int statusCode = Integer.parseInt(statusMatcher.group(1));

        // Case-insensitive header map, matching HttpResponse semantics
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            Matcher headerMatcher = HEADER_PATTERN.matcher(lines[i]);
            if (headerMatcher.matches()) {
                headers.computeIfAbsent(headerMatcher.group(1), k -> new ArrayList<>())
                        .add(headerMatcher.group(2));
            }
        }
        if (contentId != null) {
            headers.computeIfAbsent("Content-ID", k -> new ArrayList<>()).add(contentId);
        }

        byte[] responseBody = bodyStart < httpBlock.length
                ? Arrays.copyOfRange(httpBlock, bodyStart, httpBlock.length)
                : null;
        if (responseBody != null && responseBody.length == 0) {
            responseBody = null;
        }

        return new BatchResult<>(statusCode, Collections.unmodifiableMap(headers), responseBody, Object.class, contentId);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------
    // Byte utilities
    // ------------------------------------------------------------------

    /**
     * Finds a multipart boundary delimiter, honoring RFC 2046: a delimiter is only valid
     * at the start of a line (preceded by {@code \n} or at the very beginning) and must
     * be followed by the close-dash, a line break, or transport padding. Without the
     * line anchor, boundary bytes occurring inside a (binary) body would split parts.
     */
    private static int indexOfBoundary(byte[] body, byte[] delimiter, int from, int to) {
        int start = Math.max(from, 0);
        for (int i = start; i <= to - delimiter.length; i++) {
            if (!startsWith(body, i, delimiter)) {
                continue;
            }
            if (i != 0 && body[i - 1] != '\n') {
                continue;
            }
            if (i + delimiter.length < body.length) {
                byte after = body[i + delimiter.length];
                if (after != '-' && after != '\r' && after != '\n' && after != ' ' && after != '\t') {
                    continue;
                }
            }
            return i;
        }
        return -1;
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
        if (pos + needle.length > haystack.length) {
            return false;
        }
        for (int j = 0; j < needle.length; j++) {
            if (haystack[pos + j] != needle[j]) {
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
}
