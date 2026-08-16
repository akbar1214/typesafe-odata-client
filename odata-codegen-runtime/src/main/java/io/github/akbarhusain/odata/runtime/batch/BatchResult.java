package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.serialization.Serializer;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record BatchResult<T>(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body,
    Type targetType,
    String contentId
) {
    /**
     * @param contentId the part-level Content-ID of the response part (null when the
     *                  server sent none — e.g. standalone, non-changeset parts). Used to
     *                  correlate changeset responses with requests.
     */
    public BatchResult {
        body = body != null ? body.clone() : null;
    }

    /** Defensive copy — the internal array must not be mutable through the accessor. */
    @Override
    public byte[] body() {
        return body != null ? body.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BatchResult other)) {
            return false;
        }
        return statusCode == other.statusCode
                && Objects.equals(headers, other.headers)
                && java.util.Arrays.equals(body, other.body)
                && Objects.equals(targetType, other.targetType)
                && Objects.equals(contentId, other.contentId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(statusCode, headers, targetType, contentId);
        result = 31 * result + java.util.Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "BatchResult[" + statusCode
                + (contentId != null ? ", contentId=" + contentId : "")
                + (body != null ? ", bodyLength=" + body.length : "") + "]";
    }

    public BatchResult(int statusCode, Map<String, List<String>> headers, byte[] body, Type targetType) {
        this(statusCode, headers, body, targetType, null);
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean isDeleted() {
        return statusCode == 204;
    }

    public String getText() {
        return body != null ? new String(body, java.nio.charset.StandardCharsets.UTF_8) : null;
    }

    public T getEntity(Serializer serializer) {
        if (body == null || body.length == 0) {
            return null;
        }
        return serializer.deserialize(body, targetType);
    }

    public String getHeader(String name) {
        List<String> values = headers.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }
}
