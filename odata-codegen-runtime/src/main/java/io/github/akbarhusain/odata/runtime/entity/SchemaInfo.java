package io.github.akbarhusain.odata.runtime.entity;

public interface SchemaInfo {
    Class<?> getClassFromTypeWithNamespace(String name);
}
