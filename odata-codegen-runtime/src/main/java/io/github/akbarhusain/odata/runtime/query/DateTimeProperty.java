package io.github.akbarhusain.odata.runtime.query;

/**
 * Property for OData date/time types (Edm.DateTimeOffset, Edm.Date, Edm.Duration, Edm.TimeOfDay).
 * Generates unquoted datetime literals in OData filter expressions.
 *
 * OData datetime literals are NOT quoted:
 *   OrderDate ge 1998-01-01T00:00:00Z
 *   not: OrderDate ge '1998-01-01T00:00:00Z'
 */
public final class DateTimeProperty<E> implements PropertyExpression<String> {
    private final String edmName;
    private final Class<E> entityType;

    public DateTimeProperty(String edmName, Class<E> entityType) {
        this.edmName = edmName;
        this.entityType = entityType;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }

    @Override
    public String toODataExpression() { return edmName; }

    @Override
    public String getODataPath() { return edmName; }

    @Override
    public OrderExpression<String> asc() { return cast(new OrderedProperty(edmName, true)); }

    @Override
    public OrderExpression<String> desc() { return cast(new OrderedProperty(edmName, false)); }

    @Override
    public OrderExpression<String> nullsFirst() { return cast(new OrderedProperty(edmName, true, true, false)); }

    @Override
    public OrderExpression<String> nullsLast() { return cast(new OrderedProperty(edmName, true, false, true)); }

    @SuppressWarnings("unchecked")
    private OrderExpression<String> cast(OrderExpression<?> expr) {
        return (OrderExpression<String>) expr;
    }

    // OData datetime comparisons - values are NOT quoted
    public FilterExpression equalTo(String value) {
        return new RawFilterExpression(edmName + " eq " + value);
    }

    public FilterExpression notEqualTo(String value) {
        return new RawFilterExpression(edmName + " ne " + value);
    }

    public FilterExpression greaterThan(String value) {
        return new RawFilterExpression(edmName + " gt " + value);
    }

    public FilterExpression greaterThanOrEqualTo(String value) {
        return new RawFilterExpression(edmName + " ge " + value);
    }

    public FilterExpression lessThan(String value) {
        return new RawFilterExpression(edmName + " lt " + value);
    }

    public FilterExpression lessThanOrEqualTo(String value) {
        return new RawFilterExpression(edmName + " le " + value);
    }

    public FilterExpression isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }
}
