package io.github.akbarhusain.odata.runtime.query;

class OrderedProperty implements OrderExpression<Object, Object> {
    private final String expression;
    private final boolean ascending;

    OrderedProperty(String expression, boolean ascending) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("order expression must not be blank");
        }
        this.expression = expression;
        this.ascending = ascending;
    }

    @Override
    public String toODataExpression() { return expression; }

    @Override
    public String getODataPath() {
        return ascending ? expression : expression + " desc";
    }

    @Override
    public OrderExpression<Object, Object> asc() {
        return new OrderedProperty(expression, true);
    }

    @Override
    public OrderExpression<Object, Object> desc() {
        return new OrderedProperty(expression, false);
    }
}
