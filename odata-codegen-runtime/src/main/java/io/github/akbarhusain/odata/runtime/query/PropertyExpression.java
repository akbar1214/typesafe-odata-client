package io.github.akbarhusain.odata.runtime.query;

public interface PropertyExpression<E, T> extends OrderExpression<E, T> {
    String getEdmName();
}
