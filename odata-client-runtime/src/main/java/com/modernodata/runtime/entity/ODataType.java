package com.modernodata.runtime.entity;

import java.util.Map;

public interface ODataType {
    String odataTypeName();
    Map<String, Object> getUnmappedFields();
    ContextPath getContextPath();
}
