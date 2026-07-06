package com.modernodata.runtime.internal;

import com.modernodata.runtime.batch.BatchOperation;
import com.modernodata.runtime.batch.BatchResult;
import com.modernodata.runtime.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultipartHelper {

    private static final String CRLF = "\r\n";
    private static final Pattern STATUS_LINE_PATTERN = Pattern.compile("HTTP/\\d\\.\\d\\s+(\\d{3})\\s*(.*)");
    private static final Pattern HEADER_PATTERN = Pattern.compile("^([\\w-]+):\\s*(.*)");

    private MultipartHelper() {}

    public static String generateBoundary() {
        return "batch_" + UUID.randomUUID().toString().replace("-", "");
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

        String delimiter = "--" + boundary;
        String endDelimiter = delimiter + "--";

        // Find first occurrence of the delimiter
        int currentPos = bodyStr.indexOf(delimiter);
        if (currentPos < 0) {
            return List.of();
        }

        // Advance past the first delimiter
        currentPos += delimiter.length();

        while (currentPos < bodyStr.length()) {
            // Skip CRLF or LF after delimiter
            if (currentPos + 1 < bodyStr.length()
                    && bodyStr.charAt(currentPos) == '\r'
                    && bodyStr.charAt(currentPos + 1) == '\n') {
                currentPos += 2;
            } else if (currentPos < bodyStr.length()
                    && bodyStr.charAt(currentPos) == '\n') {
                currentPos += 1;
            }

            // Check for end delimiter
            if (bodyStr.startsWith(endDelimiter, currentPos)) {
                break;
            }

            // Find the next delimiter (start of next part or end delimiter)
            int nextDelimPos = bodyStr.indexOf(delimiter, currentPos);
            if (nextDelimPos < 0) {
                break;
            }

            // Extract part content (from currentPos up to the next delimiter)
            // The CRLF/LF before the delimiter is part of the delimiter boundary, not the content
            int partEnd = nextDelimPos;
            // Strip trailing CRLF or LF before the delimiter
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
                BatchResult<?> result = decodePart(part);
                if (result != null) {
                    results.add(result);
                }
            }

            // Move past the next delimiter
            currentPos = nextDelimPos + delimiter.length();
        }

        return results;
    }

    private static BatchResult<?> decodePart(String part) {
        // Part format:
        // Content-Type: application/http\r\n
        // Content-Transfer-Encoding: binary\r\n
        // \r\n
        // HTTP/1.1 200 OK\r\n
        // Content-Type: application/json\r\n
        // \r\n
        // {body}

        // Find blank line separating part headers from HTTP content
        int separatorIdx = part.indexOf(CRLF + CRLF);
        int separatorLen = 4;
        if (separatorIdx < 0) {
            separatorIdx = part.indexOf("\n\n");
            separatorLen = 2;
        }
        if (separatorIdx < 0) {
            return null;
        }

        String httpBlock = part.substring(separatorIdx + separatorLen).strip();
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
