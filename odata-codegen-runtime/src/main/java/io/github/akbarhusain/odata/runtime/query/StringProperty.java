package io.github.akbarhusain.odata.runtime.query;

public final class StringProperty<E> implements PropertyExpression<E, String> {
    private final String edmName;
    private final Class<E> entityType;

    public StringProperty(String edmName, Class<E> entityType) {
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

    // Equality operators
    public FilterExpression<E> equalTo(String value) {
        if (value == null) {
            return isNull();
        }
        return new RawFilterExpression(edmName + " eq '" + escape(value) + "'");
    }

    public FilterExpression<E> notEqualTo(String value) {
        if (value == null) {
            return isNotNull();
        }
        return new RawFilterExpression(edmName + " ne '" + escape(value) + "'");
    }

    // String-specific operators
    public FilterExpression<E> contains(String value) {
        return new RawFilterExpression("contains(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression<E> startsWith(String value) {
        return new RawFilterExpression("startswith(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression<E> endsWith(String value) {
        return new RawFilterExpression("endswith(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression<E> matchesPattern(String regex) {
        return new RawFilterExpression("matchesPattern(" + edmName + ",'" + escape(regex) + "')");
    }

    // String functions
    public NumberExpression<Integer, E> length() {
        return new NumberExpression<>("length(" + edmName + ")", entityType);
    }

    public StringProperty<E> toLower() {
        return new StringProperty<>("tolower(" + edmName + ")", entityType);
    }

    public StringProperty<E> toUpper() {
        return new StringProperty<>("toupper(" + edmName + ")", entityType);
    }

    public StringProperty<E> trim() {
        return new StringProperty<>("trim(" + edmName + ")", entityType);
    }

    public StringProperty<E> concat(String value) {
        return new StringProperty<>("concat(" + edmName + ",'" + escape(value) + "')", entityType);
    }

    public StringProperty<E> concat(StringProperty<E> other) {
        return new StringProperty<>("concat(" + edmName + "," + other.toODataExpression() + ")", entityType);
    }

    public FilterExpression<E> equalToIgnoreCase(String value) {
        if (value == null) {
            return isNull();
        }
        return new RawFilterExpression("tolower(" + edmName + ") eq '" + escape(value) + "'");
    }

    public FilterExpression<E> notEqualToIgnoreCase(String value) {
        if (value == null) {
            return isNotNull();
        }
        return new RawFilterExpression("tolower(" + edmName + ") ne '" + escape(value) + "'");
    }

    public NumberExpression<Integer, E> indexOf(String value) {
        return new NumberExpression<>("indexof(" + edmName + ",'" + escape(value) + "')", entityType);
    }

    public StringProperty<E> substring(int start) {
        return new StringProperty<>("substring(" + edmName + "," + start + ")", entityType);
    }

    public StringProperty<E> substring(int start, int length) {
        return new StringProperty<>("substring(" + edmName + "," + start + "," + length + ")", entityType);
    }

    // Lexicographic comparison operators (valid OData for strings)
    public FilterExpression<E> greaterThan(String value) {
        return new RawFilterExpression(edmName + " gt '" + escape(value) + "'");
    }

    public FilterExpression<E> greaterThanOrEqualTo(String value) {
        return new RawFilterExpression(edmName + " ge '" + escape(value) + "'");
    }

    public FilterExpression<E> lessThan(String value) {
        return new RawFilterExpression(edmName + " lt '" + escape(value) + "'");
    }

    public FilterExpression<E> lessThanOrEqualTo(String value) {
        return new RawFilterExpression(edmName + " le '" + escape(value) + "'");
    }

    // Null checks
    public FilterExpression<E> isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }
}
