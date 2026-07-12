package io.github.akbarhusain.odata.runtime.query;

import java.util.ArrayList;
import java.util.List;

public class NavProperty<E, T> {
    protected final String edmName;
    protected final Class<E> entityType;
    protected final Class<T> navType;

    public NavProperty(String edmName, Class<E> entityType, Class<T> navType) {
        this.edmName = edmName;
        this.entityType = entityType;
        this.navType = navType;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }
    public Class<T> getNavType() { return navType; }

    public NavQuery<E, T> select(PropertyExpression<? super T, ?>... properties) {
        List<String> selects = new ArrayList<>();
        for (var prop : properties) {
            selects.add(prop.getEdmName());
        }
        return new NavQuery<>(edmName, selects, List.of(), List.of(), null, List.of());
    }

    public NavQuery<E, T> filter(FilterExpression<? super T> predicate) {
        return new NavQuery<>(edmName, List.of(), List.of(predicate.toODataExpression()), List.of(), null, List.of());
    }

    public NavQuery<E, T> orderBy(OrderExpression<? super T, ?>... expressions) {
        List<String> orders = new ArrayList<>();
        for (var expr : expressions) {
            orders.add(expr.getODataPath());
        }
        return new NavQuery<>(edmName, List.of(), List.of(), orders, null, List.of());
    }

    public NavQuery<E, T> top(int count) {
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), "$top=" + count, List.of());
    }

    public NavQuery<E, T> expand(NavQuery<? super T, ?>... queries) {
        List<String> expands = new ArrayList<>();
        for (var q : queries) {
            expands.add(q.toODataExpand());
        }
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, expands);
    }

    public NavQuery<E, T> expand(NavProperty<? super T, ?>... properties) {
        List<String> expands = new ArrayList<>();
        for (var p : properties) {
            expands.add(p.getEdmName());
        }
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, expands);
    }

    public record NavQuery<S, T>(
        String edmName,
        List<String> selects,
        List<String> filters,
        List<String> orderings,
        String topOption,
        List<String> expands
    ) {
        public NavQuery<S, T> select(PropertyExpression<? super T, ?>... properties) {
            List<String> newSelects = new ArrayList<>(this.selects);
            for (var prop : properties) {
                newSelects.add(prop.getEdmName());
            }
            return new NavQuery<>(edmName, newSelects, filters, orderings, topOption, expands);
        }

        public NavQuery<S, T> filter(FilterExpression<? super T> predicate) {
            List<String> newFilters = new ArrayList<>(this.filters);
            newFilters.add(predicate.toODataExpression());
            return new NavQuery<>(edmName, selects, newFilters, orderings, topOption, expands);
        }

        public NavQuery<S, T> orderBy(OrderExpression<? super T, ?>... expressions) {
            List<String> newOrderings = new ArrayList<>(this.orderings);
            for (var expr : expressions) {
                newOrderings.add(expr.getODataPath());
            }
            return new NavQuery<>(edmName, selects, filters, newOrderings, topOption, expands);
        }

        public NavQuery<S, T> top(int count) {
            return new NavQuery<>(edmName, selects, filters, orderings, "$top=" + count, expands);
        }

        public NavQuery<S, T> expand(NavQuery<? super T, ?>... queries) {
            List<String> newExpands = new ArrayList<>(this.expands);
            for (var q : queries) {
                newExpands.add(q.toODataExpand());
            }
            return new NavQuery<>(edmName, selects, filters, orderings, topOption, newExpands);
        }

        public NavQuery<S, T> expand(NavProperty<? super T, ?>... properties) {
            List<String> newExpands = new ArrayList<>(this.expands);
            for (var p : properties) {
                newExpands.add(p.getEdmName());
            }
            return new NavQuery<>(edmName, selects, filters, orderings, topOption, newExpands);
        }

        public String toODataExpand() {
            StringBuilder sb = new StringBuilder(edmName);
            List<String> options = new ArrayList<>();
            if (!selects.isEmpty()) {
                options.add("$select=" + String.join(",", selects));
            }
            if (!filters.isEmpty()) {
                options.add("$filter=" + String.join(" and ", filters));
            }
            if (!orderings.isEmpty()) {
                options.add("$orderby=" + String.join(",", orderings));
            }
            if (topOption != null) {
                options.add(topOption);
            }
            if (!expands.isEmpty()) {
                options.add("$expand=" + String.join(",", expands));
            }
            if (!options.isEmpty()) {
                sb.append("(").append(String.join(";", options)).append(")");
            }
            return sb.toString();
        }
    }
}
