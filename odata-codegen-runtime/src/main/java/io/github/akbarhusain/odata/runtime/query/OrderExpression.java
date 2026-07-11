package io.github.akbarhusain.odata.runtime.query;

public interface OrderExpression<T> extends Expression<T> {
    OrderExpression<T> asc();
    OrderExpression<T> desc();
    OrderExpression<T> nullsFirst();
    OrderExpression<T> nullsLast();
    String getODataPath();
    default String getEdmName() {
        throw new UnsupportedOperationException("Only property constants have an EDM name, not computed expressions");
    }
}
