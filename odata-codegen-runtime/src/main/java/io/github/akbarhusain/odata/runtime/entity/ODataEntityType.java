package io.github.akbarhusain.odata.runtime.entity;

import java.util.Optional;
import java.util.Set;

public interface ODataEntityType extends ODataType {
    Set<String> getChangedFields();
    Object getKey();

    /**
     * Returns the ETag value from @odata.etag if present.
     * Used for optimistic concurrency with If-Match headers.
     */
    default Optional<String> getETag() {
        return Optional.empty();
    }
}
