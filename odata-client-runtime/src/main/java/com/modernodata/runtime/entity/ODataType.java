package com.modernodata.runtime.entity;

import java.util.Map;
import java.util.Optional;

public interface ODataType {
    String odataTypeName();
    Map<String, Object> getUnmappedFields();
    ContextPath getContextPath();
}
