package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.batch.Changeset;

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
 */
public class MultipartHelper {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOUBLE_LF = "\n\n".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern STATUS_LINE_PATTERN = Pattern.compile("HTTP/\\d\\.\\d\\s+(\\d{3})\\s*(.*)");
    private static final Pattern HEADER_PATTERN = Pattern.compile("^([\\w-]+):\\s*(.*)");
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile("boundary=([^;\\s]+)");

    private MultipartHelper() {}

    public static String generateBoundary() {
        return "batch_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateChangesetBoundary() {
        return "changeset_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static byte[] encodeBatchRequest(String boundary, List<Object> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object entry : entries) {
            write(out, "--" + boundary + "\r\n");
            if (entry instanceof Changeset cs) {
                String csBoundary = generateChangesetBoundary();
                write(out, "Content-Type: multipart/mixed; boundary=" + csBoundary + "\r\n");
                write(out, "\r\n");
                out.writeBytes(encodeChangeset(csBoundary, cs.operations()));
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < operations.size(); i++) {
            BatchOperation op = operations.get(i);
            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Type: application/http\r\n");
            write(out, "Content-Transfer-Encoding: binary\r\n");
            write(out, "Content-ID: " + (i + 1) + "\r\n");
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
        if (op.body() != null && op.body().length > 0) {
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
        byte[] endDelimiter = concat(delimiter, "--".getBytes(StandardCharsets.US_ASCII));
        int pos = indexOf(body, delimiter, startPos, endPos);
        if (pos < 0 || pos >= endPos) {
            return;
        }
        pos += delimiter.length;

        while (pos < endPos) {
            // Skip CRLF or LF after the delimiter
            if (pos + 1 < endPos && body[pos] == '\r' && body[pos + 1] == '\n') {
                pos += 2;
            } else if (pos < endPos && body[pos] == '\n') {
                pos += 1;
            }

            // End delimiter terminates the multipart body
            if (startsWith(body, pos, endDelimiter)) {
                break;
            }

            int next = indexOf(body, delimiter, pos, endPos);
            if (next < 0 || next > endPos) {
                break;
            }

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

            pos = next + delimiter.length;
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
            return;
        }

        String partHeaders = new String(part, 0, separatorIdx, StandardCharsets.UTF_8);
        byte[] partContent = Arrays.copyOfRange(part, separatorIdx + separatorLen, part.length);

        // Check for nested multipart/mixed (changeset)
        for (String line : partHeaders.split("\\r?\\n")) {
            Matcher contentTypeMatcher = HEADER_PATTERN.matcher(line);
            if (contentTypeMatcher.matches() && "Content-Type".equalsIgnoreCase(contentTypeMatcher.group(1))) {
                String ctValue = contentTypeMatcher.group(2);
                Matcher boundaryMatcher = BOUNDARY_PATTERN.matcher(ctValue);
                if (ctValue.contains("multipart/mixed") && boundaryMatcher.find()) {
                    decodeParts(("--" + boundaryMatcher.group(1)).getBytes(StandardCharsets.US_ASCII),
                            partContent, 0, partContent.length, results);
                    return;
                }
                break;
            }
        }

        // Not a nested multipart — decode as a regular HTTP part.
        // partContent contains: HTTP/1.1 200 OK\r\nHeaders\r\n\r\nBody (body verbatim).
        BatchResult<?> result = decodeSinglePart(partContent);
        if (result != null) {
            results.add(result);
        }
    }

    private static BatchResult<?> decodeSinglePart(byte[] httpBlock) {
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
        if (lines.length == 0) {
            return null;
        }

        Matcher statusMatcher = STATUS_LINE_PATTERN.matcher(lines[0].strip());
        if (!statusMatcher.matches()) {
            return null;
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

        byte[] responseBody = bodyStart < httpBlock.length
                ? Arrays.copyOfRange(httpBlock, bodyStart, httpBlock.length)
                : null;
        if (responseBody != null && responseBody.length == 0) {
            responseBody = null;
        }

        return new BatchResult<>(statusCode, Collections.unmodifiableMap(headers), responseBody, Object.class);
    }

    // ------------------------------------------------------------------
    // Byte utilities
    // ------------------------------------------------------------------

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

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
