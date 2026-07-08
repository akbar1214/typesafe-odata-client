package io.github.akbarhusain.odata.runtime.entity;

import java.util.Map;

public interface ODataType {
    String odataTypeName();
    Map<String, Object> getUnmappedFields();
    ContextPath getContextPath();
}
