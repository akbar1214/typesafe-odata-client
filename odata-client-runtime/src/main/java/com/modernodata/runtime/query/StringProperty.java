package com.modernodata.runtime.query;

public final class StringProperty<E> implements OrderExpression<String> {
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

    // Comparison operators
    public FilterExpression equalTo(String value) {
        return new RawFilterExpression(edmName + " eq '" + escape(value) + "'");
    }

    public FilterExpression notEqualTo(String value) {
        return new RawFilterExpression(edmName + " ne '" + escape(value) + "'");
    }

    public FilterExpression greaterThan(String value) {
        return new RawFilterExpression(edmName + " gt '" + escape(value) + "'");
    }

    public FilterExpression greaterThanOrEqualTo(String value) {
        return new RawFilterExpression(edmName + " ge '" + escape(value) + "'");
    }

    public FilterExpression lessThan(String value) {
        return new RawFilterExpression(edmName + " lt '" + escape(value) + "'");
    }

    public FilterExpression lessThanOrEqualTo(String value) {
        return new RawFilterExpression(edmName + " le '" + escape(value) + "'");
    }

    public FilterExpression contains(String value) {
        return new RawFilterExpression("contains(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression startsWith(String value) {
        return new RawFilterExpression("startswith(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression endsWith(String value) {
        return new RawFilterExpression("endswith(" + edmName + ",'" + escape(value) + "')");
    }

    public FilterExpression matchesPattern(String regex) {
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

    public NumberExpression<Integer, E> indexOf(String value) {
        return new NumberExpression<>("indexof(" + edmName + ",'" + escape(value) + "')", entityType);
    }

    public StringProperty<E> substring(int start) {
        return new StringProperty<>("substring(" + edmName + "," + start + ")", entityType);
    }

    public StringProperty<E> substring(int start, int length) {
        return new StringProperty<>("substring(" + edmName + "," + start + "," + length + ")", entityType);
    }

    // Null checks
    public FilterExpression isNull() {
        return new RawFilterExpression(edmName + " eq null");
    }

    public FilterExpression isNotNull() {
        return new RawFilterExpression(edmName + " ne null");
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }
}
