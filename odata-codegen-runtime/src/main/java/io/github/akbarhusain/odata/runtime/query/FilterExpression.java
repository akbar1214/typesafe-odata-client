package io.github.akbarhusain.odata.runtime.query;

public interface FilterExpression extends Expression<Boolean> {
    FilterExpression and(FilterExpression other);
    FilterExpression or(FilterExpression other);
    FilterExpression not();

    static FilterExpression of(String raw) {
        return new RawFilterExpression(raw);
    }
}
