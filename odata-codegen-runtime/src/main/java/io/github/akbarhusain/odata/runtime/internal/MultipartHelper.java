package io.github.akbarhusain.odata.runtime.internal;

import io.github.akbarhusain.odata.runtime.batch.BatchOperation;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.batch.Changeset;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultipartHelper {

    private static final String CRLF = "\r\n";
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
        StringBuilder sb = new StringBuilder();

        for (Object entry : entries) {
            sb.append("--").append(boundary).append(CRLF);
            if (entry instanceof Changeset cs) {
                String csBoundary = generateChangesetBoundary();
                sb.append("Content-Type: multipart/mixed; boundary=").append(csBoundary).append(CRLF);
                sb.append(CRLF);
                sb.append(new String(encodeChangeset(csBoundary, cs.operations()), StandardCharsets.UTF_8));
            } else if (entry instanceof BatchOperation op) {
                sb.append("Content-Type: application/http").append(CRLF);
                sb.append("Content-Transfer-Encoding: binary").append(CRLF);
                sb.append(CRLF);
                encodeOperation(sb, op);
            }
            sb.append(CRLF);
        }

        sb.append("--").append(boundary).append("--").append(CRLF);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeChangeset(String boundary, List<BatchOperation> operations) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < operations.size(); i++) {
            BatchOperation op = operations.get(i);
            sb.append("--").append(boundary).append(CRLF);
            sb.append("Content-Type: application/http").append(CRLF);
            sb.append("Content-Transfer-Encoding: binary").append(CRLF);
            sb.append("Content-ID: ").append(i + 1).append(CRLF);
            sb.append(CRLF);
            encodeOperation(sb, op);
            sb.append(CRLF);
        }

        sb.append("--").append(boundary).append("--").append(CRLF);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeRequest(String boundary, List<BatchOperation> operations) {
        StringBuilder sb = new StringBuilder();

        for (BatchOperation op : operations) {
            sb.append("--").append(boundary).append(CRLF);
            sb.append("Content-Type: application/http").append(CRLF);
            sb.append("Content-Transfer-Encoding: binary").append(CRLF);
            sb.append(CRLF);
            encodeOperation(sb, op);
            sb.append(CRLF);
        }

        sb.append("--").append(boundary).append("--").append(CRLF);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void encodeOperation(StringBuilder sb, BatchOperation op) {
        sb.append(op.method().name()).append(" ").append(op.url()).append(" HTTP/1.1").append(CRLF);
        if (op.body() != null && op.body().length > 0) {
            sb.append("Content-Type: application/json").append(CRLF);
        }
        for (var entry : op.headers().entrySet()) {
            for (String value : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(value).append(CRLF);
            }
        }
        sb.append(CRLF);
        if (op.body() != null && op.body().length > 0) {
            sb.append(new String(op.body(), StandardCharsets.UTF_8));
        }
    }

    public static List<BatchResult<?>> decodeResponse(String boundary, byte[] body) {
        if (body == null || body.length == 0) {
            return List.of();
        }

        String bodyStr = new String(body, StandardCharsets.UTF_8);
        List<BatchResult<?>> results = new ArrayList<>();
        decodeParts(boundary, bodyStr, 0, bodyStr.length(), results);
        return results;
    }

    private static int decodeParts(String boundary, String bodyStr, int startPos, int endPos, List<BatchResult<?>> results) {
        String delimiter = "--" + boundary;
        String endDelimiter = delimiter + "--";

        int currentPos = bodyStr.indexOf(delimiter, startPos);
        if (currentPos < 0 || currentPos >= endPos) {
            return endPos;
        }

        currentPos += delimiter.length();

        while (currentPos < endPos) {
            // Skip CRLF or LF after delimiter
            if (currentPos + 1 < endPos
                    && bodyStr.charAt(currentPos) == '\r'
                    && bodyStr.charAt(currentPos + 1) == '\n') {
                currentPos += 2;
            } else if (currentPos < endPos
                    && bodyStr.charAt(currentPos) == '\n') {
                currentPos += 1;
            }

            // Check for end delimiter
            if (bodyStr.startsWith(endDelimiter, currentPos)) {
                break;
            }

            // Find the next delimiter (start of next part or end delimiter)
            int nextDelimPos = bodyStr.indexOf(delimiter, currentPos);
            if (nextDelimPos < 0 || nextDelimPos > endPos) {
                break;
            }

            // Extract part content
            int partEnd = nextDelimPos;
            if (partEnd >= 2
                    && bodyStr.charAt(partEnd - 2) == '\r'
                    && bodyStr.charAt(partEnd - 1) == '\n') {
                partEnd -= 2;
            } else if (partEnd >= 1
                    && bodyStr.charAt(partEnd - 1) == '\n') {
                partEnd -= 1;
            }

            String part = bodyStr.substring(currentPos, partEnd);
            if (!part.isBlank()) {
                decodePartOrNested(part, results);
            }

            // Move past the next delimiter
            currentPos = nextDelimPos + delimiter.length();
        }

        return currentPos;
    }

    private static void decodePartOrNested(String part, List<BatchResult<?>> results) {
        // Check if this part is itself a multipart/mixed (changeset)
        // by looking at the part headers before the blank line
        int separatorIdx = part.indexOf(CRLF + CRLF);
        int separatorLen = 4;
        if (separatorIdx < 0) {
            separatorIdx = part.indexOf("\n\n");
            separatorLen = 2;
        }
        if (separatorIdx < 0) {
            return;
        }

        String partHeaders = part.substring(0, separatorIdx);
        String partContent = part.substring(separatorIdx + separatorLen);

        // Check for nested multipart/mixed (changeset)
        Matcher contentTypeMatcher = HEADER_PATTERN.matcher("");
        for (String line : partHeaders.split("\\r?\\n")) {
            contentTypeMatcher = HEADER_PATTERN.matcher(line);
            if (contentTypeMatcher.matches() && "Content-Type".equalsIgnoreCase(contentTypeMatcher.group(1))) {
                String ctValue = contentTypeMatcher.group(2);
                Matcher boundaryMatcher = BOUNDARY_PATTERN.matcher(ctValue);
                if (ctValue.contains("multipart/mixed") && boundaryMatcher.find()) {
                    String nestedBoundary = boundaryMatcher.group(1);
                    // Recursively decode the nested multipart
                    decodeParts(nestedBoundary, partContent, 0, partContent.length(), results);
                    return;
                }
                break;
            }
        }

        // Not a nested multipart — decode as a regular HTTP part.
        // partContent contains: HTTP/1.1 200 OK\r\nHeaders\r\n\r\nBody
        BatchResult<?> result = decodeSinglePart(partContent);
        if (result != null) {
            results.add(result);
        }
    }

    private static BatchResult<?> decodeSinglePart(String httpBlock) {
        // httpBlock format:
        // HTTP/1.1 200 OK\r\n
        // Response-Header: value\r\n
        // \r\n
        // {body}

        httpBlock = httpBlock.strip();
        if (httpBlock.isEmpty()) {
            return null;
        }

        String[] lines = httpBlock.split("\\r?\\n", -1);
        if (lines.length == 0) {
            return null;
        }

        Matcher statusMatcher = STATUS_LINE_PATTERN.matcher(lines[0].strip());
        if (!statusMatcher.matches()) {
            return null;
        }

        int statusCode = Integer.parseInt(statusMatcher.group(1));

        Map<String, List<String>> headers = new HashMap<>();
        byte[] responseBody = null;
        int bodyStartLine = lines.length;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                bodyStartLine = i + 1;
                break;
            }
            Matcher headerMatcher = HEADER_PATTERN.matcher(line);
            if (headerMatcher.matches()) {
                headers.computeIfAbsent(headerMatcher.group(1), k -> new ArrayList<>())
                        .add(headerMatcher.group(2));
            }
        }

        if (bodyStartLine < lines.length) {
            StringBuilder bodyBuilder = new StringBuilder();
            for (int i = bodyStartLine; i < lines.length; i++) {
                if (i > bodyStartLine) {
                    bodyBuilder.append("\n");
                }
                bodyBuilder.append(lines[i]);
            }
            String bodyStr = bodyBuilder.toString().strip();
            if (!bodyStr.isEmpty()) {
                responseBody = bodyStr.getBytes(StandardCharsets.UTF_8);
            }
        }

        return new BatchResult<>(statusCode, Collections.unmodifiableMap(headers), responseBody, Object.class);
    }
}
