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

    public <S extends T> NavQuery<E, S> as(String qualifiedCast, Class<S> subtype) {
        if (qualifiedCast == null || qualifiedCast.isBlank()) {
            throw new IllegalArgumentException("qualifiedCast must not be blank");
        }
        if (subtype == null) {
            throw new IllegalArgumentException("subtype must not be null");
        }
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null, null,
                List.of(), qualifiedCast);
    }

    public NavQuery<E, T> select(PropertyExpression<? super T, ?>... properties) {
        List<String> selects = new ArrayList<>();
        for (var prop : properties) {
            selects.add(selectableName(prop));
        }
        return new NavQuery<>(edmName, selects, List.of(), List.of(), null, null, null, List.of());
    }

    /**
     * $select accepts structural property paths only — transformation methods
     * ({@code toLower()}, {@code substring()}, {@code date()}, ...) return property-like
     * expressions whose names contain function calls, which are invalid in $select.
     */
    static String selectableName(PropertyExpression<?, ?> prop) {
        String name = prop.getEdmName();
        if (name.indexOf('(') >= 0) {
            throw new IllegalArgumentException("'" + name + "' is not a selectable property "
                    + "($select accepts property paths only; function transformations belong "
                    + "in $filter or $compute)");
        }
        return name;
    }

    public NavQuery<E, T> filter(FilterExpression<? super T> predicate) {
        return new NavQuery<>(edmName, List.of(), List.of(predicate.toODataExpression()), List.of(), null, null, null, List.of());
    }

    public NavQuery<E, T> orderBy(OrderExpression<? super T, ?>... expressions) {
        List<String> orders = new ArrayList<>();
        for (var expr : expressions) {
            orders.add(expr.getODataPath());
        }
        return new NavQuery<>(edmName, List.of(), List.of(), orders, null, null, null, List.of());
    }

    public NavQuery<E, T> top(int count) {
        requireNonNegative("top", count);
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), "$top=" + count, null, null, List.of());
    }

    public NavQuery<E, T> skip(int count) {
        requireNonNegative("skip", count);
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, "$skip=" + count, null, List.of());
    }

    /** Requests the inline count within the expansion: {@code Trips($count=true)}. */
    public NavQuery<E, T> count() {
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null, "$count=true", List.of());
    }

    public NavQuery<E, T> expand(NavQuery<? super T, ?>... queries) {
        List<String> expands = new ArrayList<>();
        for (var q : queries) {
            expands.add(q.toODataExpand());
        }
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null, null, expands);
    }

    public NavQuery<E, T> expand(NavProperty<? super T, ?>... properties) {
        List<String> expands = new ArrayList<>();
        for (var p : properties) {
            expands.add(p.getEdmName());
        }
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null, null, expands);
    }

    /** $top/$skip must be >= 0 — negative values render invalid OData (parity with ApplyBuilder). */
    private static void requireNonNegative(String option, int count) {
        if (count < 0) {
            throw new IllegalArgumentException(option + " must be >= 0, got: " + count);
        }
    }

    public record NavQuery<S, T>(
        String edmName,
        List<String> selects,
        List<String> filters,
        List<String> orderings,
        String topOption,
        String skipOption,
        String countOption,
        List<String> expands,
        String castSegment
    ) {
        public NavQuery(String edmName, List<String> selects, List<String> filters,
                        List<String> orderings, String topOption, String skipOption,
                        String countOption, List<String> expands) {
            this(edmName, selects, filters, orderings, topOption, skipOption, countOption,
                    expands, null);
        }

        public NavQuery {
            // Defensive copies: builder methods hand out mutable lists otherwise
            selects = List.copyOf(selects);
            filters = List.copyOf(filters);
            orderings = List.copyOf(orderings);
            expands = List.copyOf(expands);
        }

        public static <S, T> NavQuery<S, T> raw(String odataExpand) {
            if (odataExpand == null || odataExpand.isBlank()) {
                throw new IllegalArgumentException("odataExpand must not be blank");
            }
            // The raw string is the ROOT path; options chained afterwards still render
            return new NavQuery<>(odataExpand, List.of(), List.of(), List.of(), null, null,
                    null, List.of());
        }

        public NavQuery<S, T> select(PropertyExpression<? super T, ?>... properties) {
            List<String> newSelects = new ArrayList<>(this.selects);
            for (var prop : properties) {
                newSelects.add(selectableName(prop));
            }
            return new NavQuery<>(edmName, newSelects, filters, orderings, topOption, skipOption,
                    countOption, expands, castSegment);
        }

        public NavQuery<S, T> filter(FilterExpression<? super T> predicate) {
            List<String> newFilters = new ArrayList<>(this.filters);
            newFilters.add(predicate.toODataExpression());
            return new NavQuery<>(edmName, selects, newFilters, orderings, topOption, skipOption,
                    countOption, expands, castSegment);
        }

        public NavQuery<S, T> orderBy(OrderExpression<? super T, ?>... expressions) {
            List<String> newOrderings = new ArrayList<>(this.orderings);
            for (var expr : expressions) {
                newOrderings.add(expr.getODataPath());
            }
            return new NavQuery<>(edmName, selects, filters, newOrderings, topOption, skipOption,
                    countOption, expands, castSegment);
        }

        public NavQuery<S, T> top(int count) {
            requireNonNegative("top", count);
            return new NavQuery<>(edmName, selects, filters, orderings, "$top=" + count, skipOption,
                    countOption, expands, castSegment);
        }

        public NavQuery<S, T> skip(int count) {
            requireNonNegative("skip", count);
            return new NavQuery<>(edmName, selects, filters, orderings, topOption, "$skip=" + count,
                    countOption, expands, castSegment);
        }

        /** Requests the inline count within the expansion: {@code Trips($count=true)}. */
        public NavQuery<S, T> count() {
            return new NavQuery<>(edmName, selects, filters, orderings, topOption, skipOption,
                    "$count=true", expands, castSegment);
        }

        public NavQuery<S, T> expand(NavQuery<? super T, ?>... queries) {
            List<String> newExpands = new ArrayList<>(this.expands);
            for (var q : queries) {
                newExpands.add(q.toODataExpand());
            }
            return new NavQuery<>(edmName, selects, filters, orderings, topOption, skipOption,
                    countOption, newExpands, castSegment);
        }

        public NavQuery<S, T> expand(NavProperty<? super T, ?>... properties) {
            List<String> newExpands = new ArrayList<>(this.expands);
            for (var p : properties) {
                newExpands.add(p.getEdmName());
            }
            return new NavQuery<>(edmName, selects, filters, orderings, topOption, skipOption,
                    countOption, newExpands, castSegment);
        }

        public String toODataExpand() {
            String root = edmName;
            if (castSegment != null) {
                root = root + '/' + castSegment;
            }
            // A raw() root may already carry an option group, e.g. raw("A($expand=x)").
            // Chained options must MERGE into that group with ';' — appending a second
            // paren group would emit invalid OData: A($expand=x)($top=1).
            String existingOptions = null;
            int open = trailingOptionGroupOpen(root);
            if (open >= 0) {
                String inner = root.substring(open + 1, root.length() - 1).strip();
                existingOptions = inner.isEmpty() ? null : inner;
                root = root.substring(0, open);
            }
            List<String> options = new ArrayList<>();
            if (!selects.isEmpty()) {
                options.add("$select=" + String.join(",", selects));
            }
            if (!filters.isEmpty()) {
                // Multiple filter() calls are ANDed. Parenthesize each predicate:
                // 'and' binds tighter than 'or', so joining unparenthesized predicates
                // containing 'or' silently changes the query semantics.
                String joined = filters.size() == 1
                        ? filters.get(0)
                        : filters.stream().map(f -> "(" + f + ")")
                                .collect(java.util.stream.Collectors.joining(" and "));
                options.add("$filter=" + joined);
            }
            if (!orderings.isEmpty()) {
                options.add("$orderby=" + String.join(",", orderings));
            }
            if (topOption != null) {
                options.add(topOption);
            }
            if (skipOption != null) {
                options.add(skipOption);
            }
            if (countOption != null) {
                options.add(countOption);
            }
            if (!expands.isEmpty()) {
                options.add("$expand=" + String.join(",", expands));
            }
            if (options.isEmpty()) {
                // no chained options: the root keeps its verbatim shape (with or
                // without its existing option group)
                return existingOptions == null ? root : root + "(" + existingOptions + ")";
            }
            if (existingOptions != null) {
                options.add(0, existingOptions);
            }
            return root + "(" + String.join(";", options) + ")";
        }

        /**
         * Index of the '(' matching a trailing ')' at the top level of the path,
         * or -1 when the path does not end in a parenthesized option group. Scanning
         * backward from the end means nested groups (lambdas, casts) inside the
         * trailing group do not confuse the match.
         */
        private static int trailingOptionGroupOpen(String path) {
            if (!path.endsWith(")")) {
                return -1;
            }
            int depth = 0;
            for (int i = path.length() - 1; i >= 0; i--) {
                char c = path.charAt(i);
                if (c == ')') {
                    depth++;
                } else if (c == '(') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }
}
