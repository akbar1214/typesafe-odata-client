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

    /**
     * Receives the {@code ETag} response header after a GET so header-only etag
     * services still support patchWithETag/deleteWithETag. Default is a no-op;
     * generated root entity classes override it to store the value. The runtime only
     * calls this when the entity carries no etag from the body annotation.
     */
    default void applyETagFromResponse(String etag) {
        // no-op by default
    }
}
