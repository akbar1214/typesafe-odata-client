package io.github.akbarhusain.odata.runtime.query;

public interface OrderExpression<E, T> extends Expression<T> {
    OrderExpression<E, T> asc();
    OrderExpression<E, T> desc();
    String getODataPath();
}
