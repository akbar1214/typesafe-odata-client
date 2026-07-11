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

    public NavQuery<T> select(PropertyExpression<?>... properties) {
        List<String> selects = new ArrayList<>();
        for (var prop : properties) {
            selects.add(prop.getEdmName());
        }
        return new NavQuery<>(edmName, selects, List.of(), List.of(), null);
    }

    public NavQuery<T> filter(FilterExpression predicate) {
        return new NavQuery<>(edmName, List.of(), List.of(predicate.toODataExpression()), List.of(), null);
    }

    public NavQuery<T> orderBy(OrderExpression<?>... expressions) {
        List<String> orders = new ArrayList<>();
        for (var expr : expressions) {
            orders.add(expr.getODataPath() + (isDescending(expr) ? " desc" : ""));
        }
        return new NavQuery<>(edmName, List.of(), List.of(), orders, null);
    }

    public NavQuery<T> top(int count) {
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), "$top=" + count);
    }

    private boolean isDescending(OrderExpression<?> expr) {
        String path = expr.getODataPath();
        return path.endsWith(" desc");
    }

    public record NavQuery<T>(
        String edmName,
        List<String> selects,
        List<String> filters,
        List<String> orderings,
        String topOption
    ) {
        public NavQuery<T> select(PropertyExpression<?>... properties) {
            List<String> newSelects = new ArrayList<>(this.selects);
            for (var prop : properties) {
                newSelects.add(prop.getEdmName());
            }
            return new NavQuery<>(edmName, newSelects, filters, orderings, topOption);
        }

        public NavQuery<T> filter(FilterExpression predicate) {
            List<String> newFilters = new ArrayList<>(this.filters);
            newFilters.add(predicate.toODataExpression());
            return new NavQuery<>(edmName, selects, newFilters, orderings, topOption);
        }

        public NavQuery<T> orderBy(OrderExpression<?>... expressions) {
            List<String> newOrderings = new ArrayList<>(this.orderings);
            for (var expr : expressions) {
                newOrderings.add(expr.getODataPath());
            }
            return new NavQuery<>(edmName, selects, filters, newOrderings, topOption);
        }

        public NavQuery<T> top(int count) {
            return new NavQuery<>(edmName, selects, filters, orderings, "$top=" + count);
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
            if (!options.isEmpty()) {
                sb.append("(").append(String.join(";", options)).append(")");
            }
            return sb.toString();
        }
    }
}
