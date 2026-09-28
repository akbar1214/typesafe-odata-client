package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.entity.SchemaInfo;
import io.github.akbarhusain.odata.runtime.serialization.Serializer;

import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public class BatchResponse implements Iterable<BatchResult<?>> {
    private final List<BatchResult<?>> results;
    private final List<BatchResult<?>> wireOrder;

    public BatchResponse(List<BatchResult<?>> results) {
        this(results, results);
    }

    public BatchResponse(List<BatchResult<?>> results, List<BatchResult<?>> wireOrder) {
        this.results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
        this.wireOrder = List.copyOf(Objects.requireNonNull(wireOrder, "wireOrder must not be null"));
    }

    public int size() {
        return results.size();
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }

    public BatchResult<?> get(int index) {
        return results.get(index);
    }

    public List<BatchResult<?>> results() {
        return results;
    }

    public List<BatchResult<?>> wireOrder() {
        return wireOrder;
    }

    public List<BatchResult<?>> wireResults() {
        return wireOrder;
    }

    /**
     * Returns the result carrying the given Content-ID or related ID, or null when no
     * submitted operation has that ID. The argument must be non-null and non-blank.
     */
    public BatchResult<?> getByContentId(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            throw new IllegalArgumentException("contentId must not be null or blank");
        }
        String normalized = BatchOperation.canonicalContentId(contentId);
        for (BatchResult<?> result : results) {
            if (Objects.equals(normalized, result.contentId())
                    || result.relatedContentIds().contains(normalized)) {
                return result;
            }
        }
        return null;
    }

    public BatchResult<?> getByRelatedContentId(String contentId) {
        return getByContentId(contentId);
    }

    public <T> BatchResult<T> get(int index, Class<T> type) {
        return copy(results.get(index), type);
    }

    public <T> BatchResult<T> get(int index, Type type) {
        return copy(results.get(index), type);
    }

    public <T> List<BatchResult<T>> getAll(Class<T> type) {
        List<BatchResult<T>> typed = new java.util.ArrayList<>(results.size());
        for (BatchResult<?> result : results) {
            typed.add(copy(result, type));
        }
        return List.copyOf(typed);
    }

    public <T> List<BatchResult<T>> getAll(Type type) {
        List<BatchResult<T>> typed = new java.util.ArrayList<>(results.size());
        for (BatchResult<?> result : results) {
            typed.add(copy(result, type));
        }
        return List.copyOf(typed);
    }

    @SuppressWarnings("unchecked")
    public <T> T getEntity(int index, Serializer serializer, SchemaInfo schemaInfo) {
        return (T) get(index).getEntity(serializer, schemaInfo);
    }

    public <T> T getEntity(int index, Class<T> type, Serializer serializer, SchemaInfo schemaInfo) {
        return get(index, type).getEntity(serializer, schemaInfo);
    }

    @SuppressWarnings("unchecked")
    public <T> T getEntity(String contentId, Class<T> type, Serializer serializer, SchemaInfo schemaInfo) {
        BatchResult<?> result = getByContentId(contentId);
        return result == null ? null : (T) result.withType(type).getEntity(serializer, schemaInfo);
    }

    @SuppressWarnings("unchecked")
    public <T> T getEntity(String contentId, Serializer serializer, SchemaInfo schemaInfo) {
        BatchResult<?> result = getByContentId(contentId);
        if (result == null) {
            return null;
        }
        return (T) result.getEntity(serializer, schemaInfo);
    }

    @Override
    public Iterator<BatchResult<?>> iterator() {
        return results.iterator();
    }

    @SuppressWarnings("unchecked")
    private static <T> BatchResult<T> copy(BatchResult<?> raw, Type type) {
        return new BatchResult<>(raw.statusCode(), raw.headers(), raw.body(), type, raw.contentId(),
                raw.relatedContentIds(), raw.contentIdGroup(), raw.wireIndex());
    }
}
