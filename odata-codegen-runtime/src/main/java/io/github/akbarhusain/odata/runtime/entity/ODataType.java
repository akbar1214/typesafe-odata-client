package io.github.akbarhusain.odata.runtime.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

public interface ODataType {
    String odataTypeName();

    @JsonIgnore
    Map<String, Object> getUnmappedFields();

    @JsonIgnore
    ContextPath getContextPath();
}
