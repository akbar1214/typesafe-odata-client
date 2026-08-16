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
 *
 * <p>This is a mutable, <b>non-thread-safe</b> builder: configure it on one thread, then
 * hand the rendered {@link #toODataApply()} string (immutable) to the request. Rendering
 * iterates a snapshot, so a concurrent append cannot corrupt an in-flight render.</p>
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

    public ApplyBuilder groupBy(PropertyExpression<?, ?>... properties) {
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
        requireNonNegative("top", n);
        transformations.add("top(" + n + ")");
        return this;
    }

    public ApplyBuilder skip(int n) {
        requireNonNegative("skip", n);
        transformations.add("skip(" + n + ")");
        return this;
    }

    private static void requireNonNegative(String what, int n) {
        if (n < 0) {
            throw new IllegalArgumentException(what + "(" + n + ") is not valid $apply syntax; n must be >= 0");
        }
    }

    @Override
    public String toODataApply() {
        // snapshot: a concurrent append must not corrupt an in-flight render
        return String.join("/", List.copyOf(transformations));
    }
}
