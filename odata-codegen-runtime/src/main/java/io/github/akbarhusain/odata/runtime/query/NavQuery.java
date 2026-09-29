package io.github.akbarhusain.odata.runtime.query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A navigation property plus chained expand options: {@code Trips($select=Name;$top=2)}.
 *
 * <p>Built from a generated navigation constant ({@code Person.TRIPS}) or the static
 * factories {@link #of(String, Supplier)}/{@link #raw(String)}. The third type parameter
 * {@code Sel} is the navigation target's selector type; when a selector factory is
 * carried, the {@code select}/{@code filter}/{@code orderBy}/{@code expand} lambda
 * overloads compose at unlimited depth (each hop's factory arrives with the value).
 * Construction without a factory ({@link #of(String)}, {@link #raw(String)}, the 2-arg
 * {@link #as}) still chains constant builders but fails fast on lambda overloads.
 *
 * <p>Note: the {@link Supplier} component makes record {@code equals} meaningless —
 * compare rendered strings ({@link #toODataExpand()}), never instances.
 *
 * @param <S>   the source entity the navigation belongs to
 * @param <T>   the navigation target entity type
 * @param <Sel> the target's selector type used by the lambda overloads
 */
public record NavQuery<S, T, Sel>(
        String edmName,
        List<String> selects,
        List<String> filters,
        List<String> orderings,
        String topOption,
        String skipOption,
        String countOption,
        List<String> expands,
        String castSegment,
        Supplier<Sel> selectorFactory
) implements Expandable<S> {

    public NavQuery(String edmName, List<String> selects, List<String> filters,
                    List<String> orderings, String topOption, String skipOption,
                    String countOption, List<String> expands) {
        this(edmName, selects, filters, orderings, topOption, skipOption, countOption,
                expands, null, null);
    }

    public NavQuery(String edmName, List<String> selects, List<String> filters,
                    List<String> orderings, String topOption, String skipOption,
                    String countOption, List<String> expands, String castSegment) {
        this(edmName, selects, filters, orderings, topOption, skipOption, countOption,
                expands, castSegment, null);
    }

    public NavQuery {
        if (edmName == null || edmName.isBlank()) {
            throw new IllegalArgumentException("navigation name must not be blank");
        }
        selects = copy(selects, "selects");
        filters = copy(filters, "filters");
        orderings = copy(orderings, "orderings");
        expands = copy(expands, "expands");
    }

    private static <T> List<T> copy(List<T> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return List.copyOf(values);
    }

    /**
     * Greenfield construction with the target's selector factory — lambda overloads
     * enabled. Generated navigation constants use this form.
     */
    public static <S, T, Sel> NavQuery<S, T, Sel> of(String edmName, Supplier<Sel> selectorFactory) {
        if (edmName == null || edmName.isBlank()) {
            throw new IllegalArgumentException("navigation name must not be blank");
        }
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null, null,
                List.of(), null, selectorFactory);
    }

    /**
     * Factory-less construction: constant builders chain fine, lambda overloads fail fast.
     */
    public static <S, T, Sel> NavQuery<S, T, Sel> of(String edmName) {
        return of(edmName, null);
    }

    public static <S, T, Sel> NavQuery<S, T, Sel> raw(String odataExpand) {
        if (odataExpand == null || odataExpand.isBlank()) {
            throw new IllegalArgumentException("odataExpand must not be blank");
        }
        for (int i = 0; i < odataExpand.length(); i++) {
            char c = odataExpand.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("odataExpand contains a control character");
            }
        }
        return new NavQuery<>(odataExpand, List.of(), List.of(), List.of(), null, null,
                null, List.of());
    }

    // ------------------------------------------------------------------
    // Casts
    // ------------------------------------------------------------------

    /**
     * Casts the navigation target to a subtype: {@code Versions/ABC.Doc}. The selector
     * factory is dropped — chain the 3-arg form to keep lambda overloads on the subtype.
     */
    public <S2 extends T> NavQuery<S, S2, ?> as(String qualifiedCast, Class<S2> subtype) {
        return new NavQuery<>(edmName, selects, filters, orderings, topOption, skipOption,
                countOption, expands, requireCast(qualifiedCast, subtype), null);
    }

    /**
     * Casts the navigation target to a subtype AND narrows the selector factory with it,
     * keeping lambda overloads enabled against the subtype's selector.
     */
    public <S2 extends T, Sel2> NavQuery<S, S2, Sel2> as(String qualifiedCast, Class<S2> subtype,
                                                         Supplier<Sel2> selectorFactory) {
        return new NavQuery<>(edmName, selects, filters, orderings, topOption, skipOption,
                countOption, expands, requireCast(qualifiedCast, subtype), selectorFactory);
    }

    private static String requireCast(String qualifiedCast, Object subtype) {
        if (subtype == null) {
            throw new IllegalArgumentException("subtype must not be null");
        }
        return requireQualifiedCast(qualifiedCast);
    }

    static String requireQualifiedCast(String qualifiedCast) {
        if (qualifiedCast == null || qualifiedCast.isBlank()) {
            throw new IllegalArgumentException("qualifiedCast must not be blank");
        }
        String[] parts = qualifiedCast.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty() || !isIdentifierStart(part.charAt(0))) {
                throw new IllegalArgumentException("qualifiedCast contains an invalid type name: " + qualifiedCast);
            }
            for (int i = 1; i < part.length(); i++) {
                if (!isIdentifierPart(part.charAt(i))) {
                    throw new IllegalArgumentException("qualifiedCast contains an invalid type name: " + qualifiedCast);
                }
            }
        }
        return qualifiedCast;
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || Character.isDigit(c);
    }

    // ------------------------------------------------------------------
    // Constant builders
    // ------------------------------------------------------------------

    /**
     * Bridges the zero-arg call: with only varargs overloads present, {@code select()}
     * would be ambiguous between the constant and lambda forms (both accept zero args).
     */
    public NavQuery<S, T, Sel> select() {
        return select(new SelectableExpression[0]);
    }

    /** Same zero-arg bridge as {@link #select()}. */
    public NavQuery<S, T, Sel> orderBy() {
        return orderBy(new OrderExpression[0]);
    }

    /**
     * {@code SelectableExpression}, not {@code PropertyExpression}: a complex, binary or
     * geography property is selectable but NOT orderable, so widening this to the ordering
     * capability would have let {@code orderBy(...)} reach it via a widened overload.
     */
    public NavQuery<S, T, Sel> select(SelectableExpression<? super T>... properties) {
        if (properties == null) throw new IllegalArgumentException("properties must not be null");
        List<String> newSelects = new ArrayList<>(this.selects);
        for (var prop : properties) {
            if (prop == null) throw new IllegalArgumentException("select property must not be null");
            newSelects.add(selectableName(prop));
        }
        return new NavQuery<>(edmName, newSelects, filters, orderings, topOption, skipOption,
                countOption, expands, castSegment, selectorFactory);
    }

    public NavQuery<S, T, Sel> filter(FilterExpression<? super T> predicate) {
        if (predicate == null) throw new IllegalArgumentException("filter predicate must not be null");
        List<String> newFilters = new ArrayList<>(this.filters);
        newFilters.add(predicate.toODataExpression());
        return new NavQuery<>(edmName, selects, newFilters, orderings, topOption, skipOption,
                countOption, expands, castSegment, selectorFactory);
    }

    public NavQuery<S, T, Sel> orderBy(OrderExpression<? super T, ?>... expressions) {
        if (expressions == null) throw new IllegalArgumentException("order expressions must not be null");
        List<String> newOrderings = new ArrayList<>(this.orderings);
        for (var expr : expressions) {
            if (expr == null) throw new IllegalArgumentException("order expression must not be null");
            newOrderings.add(expr.getODataPath());
        }
        return new NavQuery<>(edmName, selects, filters, newOrderings, topOption, skipOption,
                countOption, expands, castSegment, selectorFactory);
    }

    public NavQuery<S, T, Sel> top(int count) {
        requireNonNegative("top", count);
        return new NavQuery<>(edmName, selects, filters, orderings, "$top=" + count, skipOption,
                countOption, expands, castSegment, selectorFactory);
    }

    public NavQuery<S, T, Sel> skip(int count) {
        requireNonNegative("skip", count);
        return new NavQuery<>(edmName, selects, filters, orderings, topOption, "$skip=" + count,
                countOption, expands, castSegment, selectorFactory);
    }

    /** Requests the inline count within the expansion: {@code Trips($count=true)}. */
    public NavQuery<S, T, Sel> count() {
        return new NavQuery<>(edmName, selects, filters, orderings, topOption, skipOption,
                "$count=true", expands, castSegment, selectorFactory);
    }

    /** Same zero-arg bridge as {@link #select()}. */
    public NavQuery<S, T, Sel> expand() {
        return expand(new Expandable[0]);
    }

    public NavQuery<S, T, Sel> expand(Expandable<? super T>... expandables) {
        if (expandables == null) throw new IllegalArgumentException("expandables must not be null");
        List<String> newExpands = new ArrayList<>(this.expands);
        for (var e : expandables) {
            if (e == null) throw new IllegalArgumentException("expandable must not be null");
            String rendered = e.toODataExpand();
            if (!newExpands.contains(rendered)) newExpands.add(rendered);
        }
        return new NavQuery<>(edmName, selects, filters, orderings, topOption, skipOption,
                countOption, newExpands, castSegment, selectorFactory);
    }

    // ------------------------------------------------------------------
    // Selector-lambda overloads (fail fast when no factory was supplied)
    // ------------------------------------------------------------------

    @SafeVarargs
    public final NavQuery<S, T, Sel> select(
            Function<? super Sel, ? extends SelectableExpression<? super T>>... selectors) {
        if (selectors == null) throw new IllegalArgumentException("select selectors must not be null");
        Sel selector = selector(selectorFactory, "select");
        SelectableExpression<? super T>[] resolved = new SelectableExpression[selectors.length];
        for (int i = 0; i < selectors.length; i++) {
            if (selectors[i] == null) throw new IllegalArgumentException("select selector must not be null");
            resolved[i] = selectors[i].apply(selector);
        }
        return select(resolved);
    }

    public NavQuery<S, T, Sel> filter(
            Function<? super Sel, ? extends FilterExpression<? super T>> predicate) {
        if (predicate == null) throw new IllegalArgumentException("filter selector must not be null");
        return filter(predicate.apply(selector(selectorFactory, "filter")));
    }

    @SafeVarargs
    public final NavQuery<S, T, Sel> orderBy(
            Function<? super Sel, ? extends OrderExpression<? super T, ?>>... expressions) {
        if (expressions == null) throw new IllegalArgumentException("order selectors must not be null");
        Sel selector = selector(selectorFactory, "orderBy");
        OrderExpression<? super T, ?>[] resolved = new OrderExpression[expressions.length];
        for (int i = 0; i < expressions.length; i++) {
            if (expressions[i] == null) throw new IllegalArgumentException("order selector must not be null");
            resolved[i] = expressions[i].apply(selector);
        }
        return orderBy(resolved);
    }

    public NavQuery<S, T, Sel> expand(
            Function<? super Sel, ? extends Expandable<? super T>> query) {
        if (query == null) throw new IllegalArgumentException("expand selector must not be null");
        return expand(query.apply(selector(selectorFactory, "expand")));
    }

    /**
     * $select accepts structural property paths only — transformation methods
     * ({@code toLower()}, {@code substring()}, {@code date()}, ...) return property-like
     * expressions whose names contain function calls, which are invalid in $select.
     */
    static String selectableName(SelectableExpression<?> prop) {
        if (prop == null) throw new IllegalArgumentException("select property must not be null");
        String name = prop.getEdmName();
        if (name.indexOf('(') >= 0) {
            throw new IllegalArgumentException("'" + name + "' is not a selectable property "
                    + "($select accepts property paths only; function transformations belong "
                    + "in $filter or $compute)");
        }
        return name;
    }

    /** $top/$skip must be >= 0 — negative values render invalid OData (parity with ApplyBuilder). */
    static void requireNonNegative(String option, int count) {
        if (count < 0) {
            throw new IllegalArgumentException(option + " must be >= 0, got: " + count);
        }
    }

    private static <Sel> Sel selector(Supplier<Sel> factory, String operation) {
        if (factory == null) {
            throw new IllegalStateException("no selector factory on this navigation; construct it "
                    + "with NavQuery.of(name, Type.Selector::new) — generated navigation constants "
                    + "provide one (operation: " + operation + ")");
        }
        Sel value = factory.get();
        if (value == null) {
            throw new IllegalStateException("selector factory returned null (operation: " + operation + ")");
        }
        return value;
    }

    @Override
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
        if (path == null || !path.endsWith(")")) {
            return -1;
        }
        int depth = 0;
        int candidate = -1;
        boolean inLiteral = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (inLiteral) {
                if (c == '\'') {
                    if (i + 1 < path.length() && path.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inLiteral = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inLiteral = true;
            } else if (c == '(') {
                if (depth == 0) candidate = i;
                depth++;
            } else if (c == ')') {
                if (depth == 0) return -1;
                depth--;
                if (depth == 0 && path.substring(i + 1).isBlank()) {
                    return candidate;
                }
            }
        }
        return -1;
    }
}
