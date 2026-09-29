package io.github.akbarhusain.odata.runtime.query;

/**
 * A property descriptor that can be named in {@code $select}, compared and sorted.
 *
 * <p>Extends {@link SelectableExpression} so that {@code select(...)} can be widened to the
 * narrower capability without touching any existing descriptor. Implementing this interface
 * means the property is ALSO orderable, which is why a complex/Geo property must use
 * {@link SelectableProperty} instead: OData requires a primitive result value to sort on
 * (v4.01 Part 1 §11.2.6.2).
 */
public interface PropertyExpression<E, T> extends SelectableExpression<E>, OrderExpression<E, T> {
    @Override
    String getEdmName();
}
