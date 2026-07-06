package com.modernodata.runtime.query;

public interface OrderExpression<T> extends Expression<T> {
    OrderExpression<T> asc();
    OrderExpression<T> desc();
    OrderExpression<T> nullsFirst();
    OrderExpression<T> nullsLast();
    String getODataPath();
}
