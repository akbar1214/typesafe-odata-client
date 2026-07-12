package io.github.akbarhusain.odata.runtime.query;

public interface OrderExpression<E, T> extends Expression<T> {
    OrderExpression<E, T> asc();
    OrderExpression<E, T> desc();
    OrderExpression<E, T> nullsFirst();
    OrderExpression<E, T> nullsLast();
    String getODataPath();
}
