package io.github.akbarhusain.odata.runtime.query;

public class NumberExpression<N, E> implements OrderExpression<E, N> {
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
    public OrderExpression<E, N> asc() { return cast(new OrderedProperty(expression, true)); }

    @Override
    public OrderExpression<E, N> desc() { return cast(new OrderedProperty(expression, false)); }

    @Override
    public OrderExpression<E, N> nullsFirst() { return cast(new OrderedProperty(expression, true, true, false)); }

    @Override
    public OrderExpression<E, N> nullsLast() { return cast(new OrderedProperty(expression, true, false, true)); }

    @SuppressWarnings("unchecked")
    private OrderExpression<E, N> cast(OrderExpression<?, ?> expr) {
        return (OrderExpression<E, N>) expr;
    }

    public FilterExpression<E> equalTo(N value) {
        if (value == null) {
            return isNull();
        }
        return new RawFilterExpression(expression + " eq " + formatValue(value));
    }

    public FilterExpression<E> notEqualTo(N value) {
        if (value == null) {
            return isNotNull();
        }
        return new RawFilterExpression(expression + " ne " + formatValue(value));
    }

    public FilterExpression<E> greaterThan(N value) {
        return new RawFilterExpression(expression + " gt " + formatValue(value));
    }

    public FilterExpression<E> greaterThanOrEqualTo(N value) {
        return new RawFilterExpression(expression + " ge " + formatValue(value));
    }

    public FilterExpression<E> lessThan(N value) {
        return new RawFilterExpression(expression + " lt " + formatValue(value));
    }

    public FilterExpression<E> lessThanOrEqualTo(N value) {
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

    public NumberExpression<N, E> negate() {
        return new NumberExpression<>("(-" + expression + ")", entityType);
    }

    public FilterExpression<E> isNull() {
        return new RawFilterExpression(expression + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(expression + " ne null");
    }

    private static String formatValue(Object value) {
        return String.valueOf(value);
    }
}
