package io.github.akbarhusain.odata.runtime.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Optional;
import java.util.Set;

public interface ODataEntityType extends ODataType {
    @JsonIgnore
    Set<String> getChangedFields();

    @JsonIgnore
    Object getKey();

    /**
     * Returns the ETag value from @odata.etag if present.
     * Used for optimistic concurrency with If-Match headers.
     */
    @JsonIgnore
    default Optional<String> getETag() {
        return Optional.empty();
    }
}
