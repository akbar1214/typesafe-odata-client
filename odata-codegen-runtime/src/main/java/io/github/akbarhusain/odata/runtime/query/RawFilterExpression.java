package io.github.akbarhusain.odata.runtime.query;

record RawFilterExpression<E>(String odata) implements FilterExpression<E> {
    @Override
    public String toODataExpression() {
        return odata;
    }

    @Override
    public FilterExpression<E> and(FilterExpression<E> other) {
        return new RawFilterExpression<>("(" + odata + ") and (" + other.toODataExpression() + ")");
    }

    @Override
    public FilterExpression<E> or(FilterExpression<E> other) {
        return new RawFilterExpression<>("(" + odata + ") or (" + other.toODataExpression() + ")");
    }

    @Override
    public FilterExpression<E> not() {
        return new RawFilterExpression<>("not (" + odata + ")");
    }
}
