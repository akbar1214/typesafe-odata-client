package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import io.github.akbarhusain.odata.runtime.entity.ODataEntityType;
import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.http.HttpHeaders;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record BatchResult<T>(
    int statusCode,
    Map<String, List<String>> headers,
    byte[] body,
    Type targetType,
    String contentId,
    Set<String> relatedContentIds,
    String contentIdGroup,
    int wireIndex
) {
    public BatchResult {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 100 and 599");
        }
        headers = headers == null
                ? HttpHeaders.immutableResponseCopy(Map.of())
                : HttpHeaders.immutableResponseCopy(headers);
        body = body == null ? null : body.clone();
        contentId = normalizeId(contentId);
        contentIdGroup = normalizeGroup(contentIdGroup);
        LinkedHashSet<String> related = new LinkedHashSet<>();
        if (relatedContentIds != null) {
            for (String id : relatedContentIds) {
                String normalized = normalizeId(id);
                if (normalized == null || normalized.isBlank()) {
                    throw new IllegalArgumentException("related Content-ID must not be blank");
                }
                related.add(normalized);
            }
        } else if (contentId != null) {
            related.add(contentId);
        }
        relatedContentIds = Collections.unmodifiableSet(related);
        if (wireIndex < -1) {
            throw new IllegalArgumentException("wireIndex must be >= -1");
        }
    }

    public BatchResult(int statusCode, Map<String, List<String>> headers, byte[] body, Type targetType) {
        this(statusCode, headers, body, targetType, null, null, null, -1);
    }

    public BatchResult(int statusCode, Map<String, List<String>> headers, byte[] body, Type targetType,
                       String contentId) {
        this(statusCode, headers, body, targetType, contentId, null, null, -1);
    }

    public BatchResult(int statusCode, Map<String, List<String>> headers, byte[] body, Type targetType,
                       String contentId, Set<String> relatedContentIds) {
        this(statusCode, headers, body, targetType, contentId, relatedContentIds, null, -1);
    }

    public BatchResult(int statusCode, Map<String, List<String>> headers, byte[] body, Type targetType,
                       String contentId, Set<String> relatedContentIds, String contentIdGroup) {
        this(statusCode, headers, body, targetType, contentId, relatedContentIds, contentIdGroup, -1);
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    public Set<String> relatedContentIdSet() {
        return relatedContentIds;
    }

    public Set<String> relatedIds() {
        return relatedContentIds;
    }

    public String groupId() {
        return contentIdGroup;
    }

    public String contentIdGroupId() {
        return contentIdGroup;
    }

    public int wireOrderIndex() {
        return wireIndex;
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
                && Objects.equals(contentId, other.contentId)
                && Objects.equals(relatedContentIds, other.relatedContentIds)
                && Objects.equals(contentIdGroup, other.contentIdGroup)
                && wireIndex == other.wireIndex;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(statusCode, headers, targetType, contentId, relatedContentIds,
                contentIdGroup, wireIndex);
        result = 31 * result + java.util.Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "BatchResult[" + statusCode
                + (contentId != null ? ", contentId=" + contentId : "")
                + (contentIdGroup != null ? ", group=" + contentIdGroup : "")
                + (body != null ? ", bodyLength=" + body.length : "")
                + (wireIndex >= 0 ? ", wireIndex=" + wireIndex : "") + "]";
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
        return getEntity(serializer, null);
    }

    public T getEntity(Serializer serializer, SchemaInfo schemaInfo) {
        Objects.requireNonNull(serializer, "serializer must not be null");
        if (body == null || body.length == 0 || targetType == null) {
            return null;
        }
        T entity = EntityOperations.deserializeBatchEntity(body, serializer, targetType, schemaInfo);
        applyEtag(entity);
        return entity;
    }

    public <R> BatchResult<R> withType(Type type) {
        return new BatchResult<>(statusCode, headers, body, type, contentId, relatedContentIds,
                contentIdGroup, wireIndex);
    }

    public BatchResult<T> withWireIndex(int index) {
        return new BatchResult<>(statusCode, headers, body, targetType, contentId, relatedContentIds,
                contentIdGroup, index);
    }

    public BatchResult<T> withContentIdAndGroup(String id, String group) {
        return new BatchResult<>(statusCode, headers, body, targetType, id, Set.of(id), group, wireIndex);
    }

    public BatchResult<T> withRelatedContentIds(Set<String> ids, String group) {
        return new BatchResult<>(statusCode, headers, body, targetType, contentId, ids, group, wireIndex);
    }

    public String getHeader(String name) {
        List<String> values = headers.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    private void applyEtag(Object entity) {
        if (!(entity instanceof ODataEntityType odata) || odata.getETag().isPresent()) {
            return;
        }
        String etag = getHeader("ETag");
        if (etag != null && !etag.isBlank()) {
            odata.applyETagFromResponse(etag);
        }
    }

    private static String normalizeId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = BatchOperation.canonicalContentId(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Content-ID must not be blank");
        }
        return normalized;
    }

    private static String normalizeGroup(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Content-ID group must not be blank");
        }
        return normalized;
    }
}
