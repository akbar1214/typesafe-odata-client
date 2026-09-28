package io.github.akbarhusain.odata.runtime.query;

record RawFilterExpression<E>(String odata) implements FilterExpression<E> {
    RawFilterExpression {
        if (odata == null || odata.isBlank()) {
            throw new IllegalArgumentException("raw filter expression must not be blank");
        }
    }

    @Override
    public String toODataExpression() {
        return odata;
    }

    @Override
    public FilterExpression<E> and(FilterExpression<E> other) {
        if (other == null) throw new IllegalArgumentException("other filter must not be null");
        return new RawFilterExpression<>("(" + odata + ") and (" + other.toODataExpression() + ")");
    }

    @Override
    public FilterExpression<E> or(FilterExpression<E> other) {
        if (other == null) throw new IllegalArgumentException("other filter must not be null");
        return new RawFilterExpression<>("(" + odata + ") or (" + other.toODataExpression() + ")");
    }

    @Override
    public FilterExpression<E> not() {
        return new RawFilterExpression<>("not (" + odata + ")");
    }
}
