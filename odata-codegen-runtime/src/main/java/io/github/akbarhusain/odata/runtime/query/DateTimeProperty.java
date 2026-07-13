package io.github.akbarhusain.odata.runtime.query;

/**
 * Property for OData date/time types (Edm.DateTimeOffset, Edm.Date, Edm.Duration, Edm.TimeOfDay).
 * Generates unquoted datetime literals in OData filter expressions.
 *
 * OData datetime literals are NOT quoted:
 *   OrderDate ge 1998-01-01T00:00:00Z
 *   not: OrderDate ge '1998-01-01T00:00:00Z'
 */
public final class DateTimeProperty<E> implements PropertyExpression<E, String> {
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
    public OrderExpression<E, String> asc() { return cast(new OrderedProperty(edmName, true)); }

    @Override
    public OrderExpression<E, String> desc() { return cast(new OrderedProperty(edmName, false)); }

    @Override
    public OrderExpression<E, String> nullsFirst() { return cast(new OrderedProperty(edmName, true, true, false)); }

    @Override
    public OrderExpression<E, String> nullsLast() { return cast(new OrderedProperty(edmName, true, false, true)); }

    @SuppressWarnings("unchecked")
    private OrderExpression<E, String> cast(OrderExpression<?, ?> expr) {
        return (OrderExpression<E, String>) expr;
    }

    // OData datetime comparisons - values are NOT quoted
    public FilterExpression<E> equalTo(String value) {
        if (value == null) {
            return isNull();
        }
        return new RawFilterExpression(edmName + " eq " + value);
    }

    public FilterExpression<E> notEqualTo(String value) {
        if (value == null) {
            return isNotNull();
        }
        return new RawFilterExpression(edmName + " ne " + value);
    }

    public FilterExpression<E> greaterThan(String value) {
        return new RawFilterExpression(edmName + " gt " + value);
    }

    public FilterExpression<E> greaterThanOrEqualTo(String value) {
        return new RawFilterExpression(edmName + " ge " + value);
    }

    public FilterExpression<E> lessThan(String value) {
        return new RawFilterExpression(edmName + " lt " + value);
    }

    public FilterExpression<E> lessThanOrEqualTo(String value) {
        return new RawFilterExpression(edmName + " le " + value);
    }

    public FilterExpression<E> isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    // Date/time extraction functions
    public NumberExpression<Integer, E> year() {
        return new NumberExpression<>("year(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> month() {
        return new NumberExpression<>("month(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> day() {
        return new NumberExpression<>("day(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> hour() {
        return new NumberExpression<>("hour(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> minute() {
        return new NumberExpression<>("minute(" + edmName + ")", entityType);
    }

    public NumberExpression<Integer, E> second() {
        return new NumberExpression<>("second(" + edmName + ")", entityType);
    }

    public DateTimeProperty<E> date() {
        return new DateTimeProperty<>("date(" + edmName + ")", entityType);
    }

    public DateTimeProperty<E> time() {
        return new DateTimeProperty<>("time(" + edmName + ")", entityType);
    }
}
