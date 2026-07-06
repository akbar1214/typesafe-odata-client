package com.modernodata.runtime.entity;

public interface SchemaInfo {
    Class<?> getClassFromTypeWithNamespace(String name);
}
