package io.github.akbarhusain.odata.runtime.query;

class OrderedProperty implements OrderExpression<Object> {
    private final String expression;
    private final boolean ascending;
    private final boolean nullsFirstValue;
    private final boolean nullsLastValue;

    OrderedProperty(String expression, boolean ascending) {
        this(expression, ascending, false, false);
    }

    OrderedProperty(String expression, boolean ascending, boolean nullsFirst, boolean nullsLast) {
        this.expression = expression;
        this.ascending = ascending;
        this.nullsFirstValue = nullsFirst;
        this.nullsLastValue = nullsLast;
    }

    @Override
    public String toODataExpression() { return expression; }

    @Override
    public String getODataPath() {
        StringBuilder sb = new StringBuilder(expression);
        if (!ascending) sb.append(" desc");
        if (nullsFirstValue) sb.append(" nulls first");
        if (nullsLastValue) sb.append(" nulls last");
        return sb.toString();
    }

    @Override
    public OrderExpression<Object> asc() {
        return new OrderedProperty(expression, true, nullsFirstValue, nullsLastValue);
    }

    @Override
    public OrderExpression<Object> desc() {
        return new OrderedProperty(expression, false, nullsFirstValue, nullsLastValue);
    }

    @Override
    public OrderExpression<Object> nullsFirst() {
        return new OrderedProperty(expression, ascending, true, false);
    }

    @Override
    public OrderExpression<Object> nullsLast() {
        return new OrderedProperty(expression, ascending, false, true);
    }
}
