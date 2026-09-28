package io.github.akbarhusain.odata.runtime.query;

import io.github.akbarhusain.odata.runtime.entity.ContextPath;

import java.math.BigDecimal;

public class NumberExpression<N, E> implements OrderExpression<E, N> {
    private final String expression;
    private final Class<E> entityType;
    private final String edmType;

    public NumberExpression(String expression, Class<E> entityType) {
        this(expression, entityType, null);
    }

    public NumberExpression(String expression, Class<E> entityType, String edmType) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("number expression must not be blank");
        }
        if (edmType != null && edmType.isBlank()) {
            throw new IllegalArgumentException("number Edm type must not be blank");
        }
        this.expression = expression;
        this.entityType = entityType;
        this.edmType = edmType;
    }

    protected Class<E> getEntityType() { return entityType; }
    protected String getEdmType() { return edmType; }

    @Override
    public String toODataExpression() { return expression; }

    @Override
    public String getODataPath() { return expression; }

    @Override
    public OrderExpression<E, N> asc() { return cast(new OrderedProperty(expression, true)); }

    @Override
    public OrderExpression<E, N> desc() { return cast(new OrderedProperty(expression, false)); }

    @SuppressWarnings("unchecked")
    private OrderExpression<E, N> cast(OrderExpression<?, ?> expr) {
        return (OrderExpression<E, N>) expr;
    }

    public FilterExpression<E> equalTo(N value) {
        if (value == null) return isNull();
        return new RawFilterExpression(expression + " eq " + formatValue(value));
    }

    public FilterExpression<E> notEqualTo(N value) {
        if (value == null) return isNotNull();
        return new RawFilterExpression(expression + " ne " + formatValue(value));
    }

    public FilterExpression<E> greaterThan(N value) {
        if (value == null) throw new IllegalArgumentException("greaterThan value must not be null");
        return new RawFilterExpression(expression + " gt " + formatValue(value));
    }

    public FilterExpression<E> greaterThanOrEqualTo(N value) {
        if (value == null) throw new IllegalArgumentException("greaterThanOrEqualTo value must not be null");
        return new RawFilterExpression(expression + " ge " + formatValue(value));
    }

    public FilterExpression<E> lessThan(N value) {
        if (value == null) throw new IllegalArgumentException("lessThan value must not be null");
        return new RawFilterExpression(expression + " lt " + formatValue(value));
    }

    public FilterExpression<E> lessThanOrEqualTo(N value) {
        if (value == null) throw new IllegalArgumentException("lessThanOrEqualTo value must not be null");
        return new RawFilterExpression(expression + " le " + formatValue(value));
    }

    public NumberExpression<N, E> add(N value) {
        requireValue(value, "add");
        return new NumberExpression<>("(" + expression + " add " + formatValue(value) + ")", entityType, edmType);
    }

    public NumberExpression<N, E> subtract(N value) {
        requireValue(value, "subtract");
        return new NumberExpression<>("(" + expression + " sub " + formatValue(value) + ")", entityType, edmType);
    }

    public NumberExpression<N, E> multiply(N value) {
        requireValue(value, "multiply");
        return new NumberExpression<>("(" + expression + " mul " + formatValue(value) + ")", entityType, edmType);
    }

    public NumberExpression<N, E> divide(N value) {
        requireValue(value, "divide");
        String op = floatingExpression() || value instanceof Double || value instanceof Float
                || value instanceof BigDecimal ? " divby " : " div ";
        return new NumberExpression<>("(" + expression + op + formatValue(value) + ")", entityType, edmType);
    }

    public NumberExpression<N, E> modulo(N value) {
        requireValue(value, "modulo");
        return new NumberExpression<>("(" + expression + " mod " + formatValue(value) + ")", entityType, edmType);
    }

    public NumberExpression<N, E> negate() {
        return new NumberExpression<>("(-" + expression + ")", entityType, edmType);
    }

    public NumberExpression<N, E> ceiling() {
        requireRoundingAllowed("ceiling");
        return new NumberExpression<>("(ceiling(" + expression + "))", entityType, edmType);
    }

    public NumberExpression<N, E> floor() {
        requireRoundingAllowed("floor");
        return new NumberExpression<>("(floor(" + expression + "))", entityType, edmType);
    }

    public NumberExpression<N, E> round() {
        requireRoundingAllowed("round");
        return new NumberExpression<>("(round(" + expression + "))", entityType, edmType);
    }

    public FilterExpression<E> isNull() {
        return new RawFilterExpression(expression + " eq null");
    }

    public FilterExpression<E> isNotNull() {
        return new RawFilterExpression(expression + " ne null");
    }

    protected String formatValue(Object value) {
        if (edmType != null && edmType.startsWith("Edm.") && !floatingExpression()
                && (value instanceof Double || value instanceof Float || value instanceof BigDecimal)) {
            String type = value instanceof Float ? "Edm.Single"
                    : value instanceof BigDecimal ? "Edm.Decimal" : "Edm.Double";
            return ContextPath.formatTypedValue(value, type);
        }
        return ContextPath.formatTypedValue(value, edmType);
    }

    protected static String formatValueStatic(Object value) {
        return ContextPath.formatTypedValue(value, null);
    }

    private void requireValue(Object value, String operation) {
        if (value == null) throw new IllegalArgumentException(operation + " value must not be null");
    }

    private boolean floatingExpression() {
        return "Edm.Single".equals(edmType) || "Edm.Double".equals(edmType) || "Edm.Decimal".equals(edmType);
    }

    private void requireRoundingAllowed(String operation) {
        if (edmType != null && !roundingType() && edmType.startsWith("Edm.")) {
            throw new IllegalArgumentException(operation + "() is not valid for Edm type " + edmType
                    + "; only Edm.Double and Edm.Decimal are defined");
        }
    }

    private boolean roundingType() {
        return "Edm.Double".equals(edmType) || "Edm.Decimal".equals(edmType);
    }
}
