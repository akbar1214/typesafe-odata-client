package io.github.akbarhusain.odata.runtime.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for the OData v4 {@code $apply} system query option.
 *
 * <p>Each call appends a transformation; {@link #toODataApply()} renders them slash-separated, e.g.
 * {@code groupby((Category))/aggregate(Price with sum as Total)}. Transformations follow OData
 * URL-convention grammar:</p>
 *
 * <ul>
 *   <li>{@code filter(<predicate>)}</li>
 *   <li>{@code groupby((prop1, prop2))}</li>
 *   <li>{@code aggregate(prop with sum as Total, ...)}</li>
 *   <li>{@code compute(expr as Alias, ...)}</li>
 *   <li>{@code orderby(prop desc, ...)}</li>
 *   <li>{@code top(n)} / {@code skip(n)}</li>
 * </ul>
 */
public final class ApplyBuilder implements ApplyExpression {

    private final List<String> transformations = new ArrayList<>();

    public ApplyBuilder filter(String rawPredicate) {
        transformations.add("filter(" + rawPredicate + ")");
        return this;
    }

    public <E> ApplyBuilder filter(FilterExpression<E> predicate) {
        transformations.add("filter(" + predicate.toODataExpression() + ")");
        return this;
    }

    public ApplyBuilder groupBy(String... properties) {
        transformations.add("groupby((" + String.join(", ", properties) + "))");
        return this;
    }

    public ApplyBuilder groupBy(PropertyExpression<?>... properties) {
        StringBuilder sb = new StringBuilder("groupby((");
        for (int i = 0; i < properties.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(properties[i].getEdmName());
        }
        sb.append("))");
        transformations.add(sb.toString());
        return this;
    }

    public ApplyBuilder aggregate(String... aggregations) {
        transformations.add("aggregate(" + String.join(", ", aggregations) + ")");
        return this;
    }

    public ApplyBuilder compute(String... computations) {
        transformations.add("compute(" + String.join(", ", computations) + ")");
        return this;
    }

    public ApplyBuilder orderBy(String... properties) {
        transformations.add("orderby(" + String.join(", ", properties) + ")");
        return this;
    }

    public ApplyBuilder top(int n) {
        transformations.add("top(" + n + ")");
        return this;
    }

    public ApplyBuilder skip(int n) {
        transformations.add("skip(" + n + ")");
        return this;
    }

    @Override
    public String toODataApply() {
        return String.join("/", transformations);
    }
}
