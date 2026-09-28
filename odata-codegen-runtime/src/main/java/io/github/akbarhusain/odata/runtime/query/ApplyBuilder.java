package io.github.akbarhusain.odata.runtime.query;

import java.util.ArrayList;
import java.util.List;

public final class ApplyBuilder implements ApplyExpression {

    private final List<String> transformations = new ArrayList<>();

    public synchronized ApplyBuilder filter(String rawPredicate) {
        transformations.add("filter(" + requireText(rawPredicate, "filter") + ")");
        return this;
    }

    public synchronized <E> ApplyBuilder filter(FilterExpression<E> predicate) {
        if (predicate == null) throw new IllegalArgumentException("filter predicate must not be null");
        transformations.add("filter(" + requireText(predicate.toODataExpression(), "filter") + ")");
        return this;
    }

    public synchronized ApplyBuilder groupBy() {
        throw new IllegalArgumentException("groupBy requires at least one property");
    }

    public synchronized ApplyBuilder groupBy(String... properties) {
        transformations.add("groupby((" + join(properties, "groupBy") + "))");
        return this;
    }

    public synchronized ApplyBuilder groupBy(PropertyExpression<?, ?>... properties) {
        if (properties == null || properties.length == 0) {
            throw new IllegalArgumentException("groupBy requires at least one property");
        }
        List<String> names = new ArrayList<>(properties.length);
        for (PropertyExpression<?, ?> property : properties) {
            if (property == null) throw new IllegalArgumentException("groupBy property must not be null");
            names.add(requireText(property.getEdmName(), "groupBy property"));
        }
        transformations.add("groupby((" + String.join(", ", names) + "))");
        return this;
    }

    public synchronized ApplyBuilder aggregate(String... aggregations) {
        transformations.add("aggregate(" + join(aggregations, "aggregate") + ")");
        return this;
    }

    public synchronized ApplyBuilder compute(String... computations) {
        transformations.add("compute(" + join(computations, "compute") + ")");
        return this;
    }

    public synchronized ApplyBuilder orderBy(String... properties) {
        transformations.add("orderby(" + join(properties, "orderBy") + ")");
        return this;
    }

    public synchronized ApplyBuilder top(int n) {
        requireNonNegative("top", n);
        transformations.add("top(" + n + ")");
        return this;
    }

    public synchronized ApplyBuilder skip(int n) {
        requireNonNegative("skip", n);
        transformations.add("skip(" + n + ")");
        return this;
    }

    @Override
    public synchronized String toODataApply() {
        return transformations.isEmpty() ? "" : String.join("/", transformations);
    }

    private static String join(String[] values, String operation) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException(operation + " requires at least one value");
        }
        List<String> result = new ArrayList<>(values.length);
        for (String value : values) {
            result.add(requireText(value, operation));
        }
        return String.join(", ", result);
    }

    private static String requireText(String value, String operation) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(operation + " value must not be blank");
        }
        return value;
    }

    private static void requireNonNegative(String what, int n) {
        if (n < 0) {
            throw new IllegalArgumentException(what + "(" + n + ") is not valid $apply syntax; n must be >= 0");
        }
    }
}
