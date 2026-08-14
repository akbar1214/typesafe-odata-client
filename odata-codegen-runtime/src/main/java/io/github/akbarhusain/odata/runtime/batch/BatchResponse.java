package io.github.akbarhusain.odata.runtime.batch;

import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;

public class BatchResponse implements Iterable<BatchResult<?>> {
    private final List<BatchResult<?>> results;

    public BatchResponse(List<BatchResult<?>> results) {
        this.results = List.copyOf(results);
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

    /**
     * Returns the result for the response part carrying the given Content-ID, or null.
     * Content-IDs come from part-level headers (changeset responses); standalone parts
     * without a Content-ID are not reachable through this lookup.
     */
    public BatchResult<?> getByContentId(String contentId) {
        for (BatchResult<?> result : results) {
            if (contentId.equals(result.contentId())) {
                return result;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T> BatchResult<T> get(int index, Class<T> type) {
        BatchResult<?> raw = results.get(index);
        return new BatchResult<>(raw.statusCode(), raw.headers(), raw.body(), type);
    }

    public <T> BatchResult<T> get(int index, Type type) {
        BatchResult<?> raw = results.get(index);
        return new BatchResult<>(raw.statusCode(), raw.headers(), raw.body(), type);
    }

    @SuppressWarnings("unchecked")
    public <T> List<BatchResult<T>> getAll(Class<T> type) {
        return results.stream()
            .map(r -> new BatchResult<T>(r.statusCode(), r.headers(), r.body(), type))
            .toList();
    }

    @Override
    public Iterator<BatchResult<?>> iterator() {
        return results.iterator();
    }
}
