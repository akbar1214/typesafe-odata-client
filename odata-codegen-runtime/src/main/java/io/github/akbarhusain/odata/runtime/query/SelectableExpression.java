package io.github.akbarhusain.odata.runtime.query;

/**
 * The narrowest capability a property descriptor can have: it can be NAMED in a
 * {@code $select}.
 *
 * <p>Deliberately narrower than {@link PropertyExpression}. OData v4.01 Part 1
 * §11.2.6.2 requires a "primitive result value" to sort on, and states that "values of
 * type {@code Edm.Stream} or any of the Geo types cannot be sorted"; a complex property
 * has no primitive result either. So complex, binary and geography properties are
 * selectable but NOT orderable, and widening {@code select(...)} to {@code OrderExpression}
 * would have handed users {@code orderBy(ProcessData)} — a new invalid-URL surface
 * introduced by the very change meant to widen coverage.
 *
 * <p>{@link PropertyExpression} extends this, so every descriptor that already carried
 * comparison and ordering operators stays usable in {@code select(...)} unchanged.
 */
public interface SelectableExpression<E> {
    /** The CSDL property path, e.g. {@code "ProcessData"}. */
    String getEdmName();
}
