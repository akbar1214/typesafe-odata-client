package io.github.akbarhusain.odata.runtime.query;

/**
 * A property OData can {@code $select} but that cannot be compared or sorted:
 * complex-typed, {@code Edm.Binary}, and geography/geometry structural properties.
 *
 * <p>These previously had no descriptor at all, so a generated entity exposed a getter for
 * data that {@code select(...)} could not name — the typed API silently covered less of the
 * schema than the entity did.
 *
 * <p>Deliberately implements ONLY {@link SelectableExpression}:
 * <ul>
 *   <li>not {@link OrderExpression} — a complex/Geo property has no primitive result value
 *       to sort on (OData v4.01 Part 1 §11.2.6.2), so {@code orderBy(ProcessData)} must not
 *       compile rather than emitting {@code $orderby=ProcessData};</li>
 *   <li>not {@link FilterExpression} — reusing {@code StringProperty} would hand users
 *       {@code .contains(...)} and render {@code contains(ProcessData,'x')}, which is
 *       invalid OData that only fails at the service.</li>
 * </ul>
 */
public final class SelectableProperty<E> implements SelectableExpression<E> {

    private final String edmName;
    private final Class<E> entityType;

    public SelectableProperty(String edmName, Class<E> entityType) {
        if (edmName == null || edmName.isBlank()) {
            throw new IllegalArgumentException("selectable property name must not be blank");
        }
        this.edmName = edmName;
        this.entityType = entityType;
    }

    @Override
    public String getEdmName() {
        return edmName;
    }

    public Class<E> getEntityType() {
        return entityType;
    }

    @Override
    public String toString() {
        return edmName;
    }
}
