package io.github.akbarhusain.odata.runtime.query;

/**
 * OData v4 {@code $apply} transformation pipeline (aggregation, {@code $compute}, grouping, ...).
 *
 * <p>Transformations are slash-separated: {@code groupby((Category))/aggregate(Price with sum as Total)}.
 * Use the fluent {@link #builder()} for type-assisted construction, or {@link #of(String)} to pass a
 * raw OData {@code $apply} expression.</p>
 */
public interface ApplyExpression {

    String toODataApply();

    static ApplyExpression of(String raw) {
        return new RawApplyExpression(raw);
    }

    static ApplyBuilder builder() {
        return new ApplyBuilder();
    }
}
