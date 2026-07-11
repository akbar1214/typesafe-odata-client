package io.github.akbarhusain.odata.runtime.query;

public interface PropertyExpression<T> extends OrderExpression<T> {
    String getEdmName();
}
