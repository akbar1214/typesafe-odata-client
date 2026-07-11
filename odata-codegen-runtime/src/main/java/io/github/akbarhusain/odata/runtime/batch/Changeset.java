package io.github.akbarhusain.odata.runtime.batch;

import java.util.Collections;
import java.util.List;

/**
 * A group of batch operations that are executed atomically in a changeset.
 * Within a changeset, all operations succeed or fail as a unit.
 */
public record Changeset(
    List<BatchOperation> operations
) {
    public Changeset {
        operations = List.copyOf(operations);
    }

    public int size() {
        return operations.size();
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }
}
