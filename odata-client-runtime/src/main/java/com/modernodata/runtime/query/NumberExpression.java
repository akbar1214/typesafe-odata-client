package com.modernodata.runtime.query;

public class NumberExpression<N, E> implements OrderExpression<N> {
    private final String expression;
    private final Class<E> entityType;

    public NumberExpression(String expression, Class<E> entityType) {
        this.expression = expression;
        this.entityType = entityType;
    }

    @Override
    public String toODataExpression() { return expression; }

    @Override
    public String getODataPath() { return expression; }

    @Override
    public OrderExpression<N> asc() { return cast(new OrderedProperty(expression, true)); }

    @Override
    public OrderExpression<N> desc() { return cast(new OrderedProperty(expression, false)); }

    @Override
    public OrderExpression<N> nullsFirst() { return cast(new OrderedProperty(expression, true, true, false)); }

    @Override
    public OrderExpression<N> nullsLast() { return cast(new OrderedProperty(expression, true, false, true)); }

    @SuppressWarnings("unchecked")
    private OrderExpression<N> cast(OrderExpression<?> expr) {
        return (OrderExpression<N>) expr;
    }

    public FilterExpression equalTo(N value) {
        return new RawFilterExpression(expression + " eq " + formatValue(value));
    }

    public FilterExpression notEqualTo(N value) {
        return new RawFilterExpression(expression + " ne " + formatValue(value));
    }

    public FilterExpression greaterThan(N value) {
        return new RawFilterExpression(expression + " gt " + formatValue(value));
    }

    public FilterExpression greaterThanOrEqualTo(N value) {
        return new RawFilterExpression(expression + " ge " + formatValue(value));
    }

    public FilterExpression lessThan(N value) {
        return new RawFilterExpression(expression + " lt " + formatValue(value));
    }

    public FilterExpression lessThanOrEqualTo(N value) {
        return new RawFilterExpression(expression + " le " + formatValue(value));
    }

    public NumberExpression<N, E> add(N value) {
        return new NumberExpression<>("(" + expression + " add " + formatValue(value) + ")", entityType);
    }

    public NumberExpression<N, E> subtract(N value) {
        return new NumberExpression<>("(" + expression + " sub " + formatValue(value) + ")", entityType);
    }

    public NumberExpression<N, E> multiply(N value) {
        return new NumberExpression<>("(" + expression + " mul " + formatValue(value) + ")", entityType);
    }

    public NumberExpression<N, E> divide(N value) {
        return new NumberExpression<>("(" + expression + " div " + formatValue(value) + ")", entityType);
    }

    public NumberExpression<N, E> modulo(N value) {
        return new NumberExpression<>("(" + expression + " mod " + formatValue(value) + ")", entityType);
    }

    public FilterExpression isNull() {
        return new RawFilterExpression(expression + " eq null");
    }

    public FilterExpression isNotNull() {
        return new RawFilterExpression(expression + " ne null");
    }

    private static String formatValue(Object value) {
        if (value instanceof String s) return "'" + s + "'";
        if (value instanceof Float f) return f + "f";
        if (value instanceof Double d) return d + "d";
        return String.valueOf(value);
    }
}
