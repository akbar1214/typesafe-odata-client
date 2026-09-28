package io.github.akbarhusain.odata.runtime.batch;

import io.github.akbarhusain.odata.runtime.http.HttpMethod;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Changeset(List<BatchOperation> operations) {
    public Changeset {
        Objects.requireNonNull(operations, "operations must not be null");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("changeset must contain at least one operation");
        }
        List<BatchOperation> copy = List.copyOf(operations);
        Set<String> ids = new HashSet<>();
        for (BatchOperation operation : copy) {
            Objects.requireNonNull(operation, "changeset operation must not be null");
            if (operation.method() == HttpMethod.GET) {
                throw new IllegalArgumentException("GET operations are not allowed in a changeset");
            }
            if (operation.contentId() != null && !ids.add(BatchOperation.canonicalContentId(operation.contentId()))) {
                throw new IllegalArgumentException("duplicate Content-ID in changeset: " + operation.contentId());
            }
        }
        operations = copy;
    }

    public int size() {
        return operations.size();
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }
}
