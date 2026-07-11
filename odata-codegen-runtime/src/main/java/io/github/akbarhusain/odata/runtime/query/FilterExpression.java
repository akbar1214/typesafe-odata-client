package io.github.akbarhusain.odata.runtime.query;

public interface FilterExpression<E> extends Expression<Boolean> {
    FilterExpression<E> and(FilterExpression<E> other);
    FilterExpression<E> or(FilterExpression<E> other);
    FilterExpression<E> not();

    static <E> FilterExpression<E> of(String raw) {
        return new RawFilterExpression<>(raw);
    }
}
