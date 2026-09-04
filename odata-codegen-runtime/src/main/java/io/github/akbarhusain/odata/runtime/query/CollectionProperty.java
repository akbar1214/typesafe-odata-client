package io.github.akbarhusain.odata.runtime.query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A collection-valued navigation or structural property. Doubly usable:
 * {@code any}/{@code all} build collection filter lambdas against the {@code F}
 * filterable type, while the constant and selector-lambda builders
 * ({@code select}/{@code filter}/{@code orderBy}/{@code top}/.../{@code expand}/{@code as})
 * open a {@link NavQuery} with chained options — the selector lambdas need the
 * {@code Sel} factory wired (generated property constants provide it; hand-built
 * 4-arg constructions carry none and fail fast on the lambda overloads).
 *
 * @param <E>   the entity type the property belongs to
 * @param <T>   the element type
 * @param <F>   the filterable type used by any/all lambdas
 * @param <Sel> the element's selector type used by the NavQuery lambda overloads
 */
public final class CollectionProperty<E, T, F, Sel> implements Expandable<E> {
    private final String edmName;
    private final Class<E> entityType;
    private final Class<T> elementType;
    private final Supplier<F> filterableFactory;
    private final Supplier<Sel> selectorFactory;

    public CollectionProperty(String edmName, Class<E> entityType) {
        this(edmName, entityType, null, null, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType) {
        this(edmName, entityType, elementType, null, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType, Supplier<F> filterableFactory) {
        this(edmName, entityType, elementType, filterableFactory, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                              Supplier<F> filterableFactory, Supplier<Sel> selectorFactory) {
        this.edmName = edmName;
        this.entityType = entityType;
        this.elementType = elementType;
        this.filterableFactory = filterableFactory;
        this.selectorFactory = selectorFactory;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }
    public Class<T> getElementType() { return elementType; }
    public Supplier<F> getFilterableFactory() { return filterableFactory; }

    /** A bare collection navigation expands to its plain segment: {@code Friends}. */
    @Override
    public String toODataExpand() {
        return edmName;
    }

    // ------------------------------------------------------------------
    // Casts
    // ------------------------------------------------------------------

    /**
     * Casts the collection's element type to a subtype: {@code Versions/ABC.Doc}.
     * The selector factory is dropped — chain the 3-arg form to keep lambda
     * overloads on the subtype.
     */
    public <S2 extends T> NavQuery<E, S2, ?> as(String qualifiedCast, Class<S2> subtype) {
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null, null,
                List.of(), requireCast(qualifiedCast, subtype), null);
    }

    /**
     * Casts the collection's element type to a subtype AND narrows the selector
     * factory with it, keeping lambda overloads enabled against the subtype's selector.
     */
    public <S2 extends T, Sel2> NavQuery<E, S2, Sel2> as(String qualifiedCast, Class<S2> subtype,
                                                         Supplier<Sel2> selectorFactory) {
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null, null,
                List.of(), requireCast(qualifiedCast, subtype), selectorFactory);
    }

    private static String requireCast(String qualifiedCast, Object subtype) {
        if (qualifiedCast == null || qualifiedCast.isBlank()) {
            throw new IllegalArgumentException("qualifiedCast must not be blank");
        }
        if (subtype == null) {
            throw new IllegalArgumentException("subtype must not be null");
        }
        return qualifiedCast;
    }

    // ------------------------------------------------------------------
    // Constant builders (open a NavQuery carrying the selector factory)
    // ------------------------------------------------------------------

    /**
     * Bridges the zero-arg call: with only varargs overloads present, {@code select()}
     * would be ambiguous between the constant and lambda forms (both accept zero args).
     */
    public NavQuery<E, T, Sel> select() {
        return select(new PropertyExpression[0]);
    }

    /** Same zero-arg bridge as {@link #select()}. */
    public NavQuery<E, T, Sel> orderBy() {
        return orderBy(new OrderExpression[0]);
    }

    public NavQuery<E, T, Sel> select(PropertyExpression<? super T, ?>... properties) {
        List<String> selects = new ArrayList<>();
        for (var prop : properties) {
            selects.add(NavQuery.selectableName(prop));
        }
        return new NavQuery<>(edmName, selects, List.of(), List.of(), null, null, null,
                List.of(), null, selectorFactory);
    }

    public NavQuery<E, T, Sel> filter(FilterExpression<? super T> predicate) {
        return new NavQuery<>(edmName, List.of(), List.of(predicate.toODataExpression()),
                List.of(), null, null, null, List.of(), null, selectorFactory);
    }

    public NavQuery<E, T, Sel> orderBy(OrderExpression<? super T, ?>... expressions) {
        List<String> orderings = new ArrayList<>();
        for (var expr : expressions) {
            orderings.add(expr.getODataPath());
        }
        return new NavQuery<>(edmName, List.of(), List.of(), orderings, null, null, null,
                List.of(), null, selectorFactory);
    }

    public NavQuery<E, T, Sel> top(int count) {
        NavQuery.requireNonNegative("top", count);
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), "$top=" + count,
                null, null, List.of(), null, selectorFactory);
    }

    public NavQuery<E, T, Sel> skip(int count) {
        NavQuery.requireNonNegative("skip", count);
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null,
                "$skip=" + count, null, List.of(), null, selectorFactory);
    }

    /** Requests the inline count within the expansion: {@code Trips($count=true)}. */
    public NavQuery<E, T, Sel> count() {
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null,
                "$count=true", List.of(), null, selectorFactory);
    }

    /** Same zero-arg bridge as {@link #select()}. */
    public NavQuery<E, T, Sel> expand() {
        return expand(new Expandable[0]);
    }

    public NavQuery<E, T, Sel> expand(Expandable<? super T>... expandables) {
        List<String> expands = new ArrayList<>();
        for (var e : expandables) {
            expands.add(e.toODataExpand());
        }
        return new NavQuery<>(edmName, List.of(), List.of(), List.of(), null, null, null,
                expands, null, selectorFactory);
    }

    // ------------------------------------------------------------------
    // Selector-lambda overloads (fail fast when no factory was supplied)
    // ------------------------------------------------------------------

    @SafeVarargs
    public final NavQuery<E, T, Sel> select(
            Function<? super Sel, ? extends PropertyExpression<? super T, ?>>... selectors) {
        Sel selector = selector("select");
        PropertyExpression<? super T, ?>[] resolved = new PropertyExpression[selectors.length];
        for (int i = 0; i < selectors.length; i++) {
            resolved[i] = selectors[i].apply(selector);
        }
        return select(resolved);
    }

    public NavQuery<E, T, Sel> filter(
            Function<? super Sel, ? extends FilterExpression<? super T>> predicate) {
        return filter(predicate.apply(selector("filter")));
    }

    @SafeVarargs
    public final NavQuery<E, T, Sel> orderBy(
            Function<? super Sel, ? extends OrderExpression<? super T, ?>>... expressions) {
        Sel selector = selector("orderBy");
        OrderExpression<? super T, ?>[] resolved = new OrderExpression[expressions.length];
        for (int i = 0; i < expressions.length; i++) {
            resolved[i] = expressions[i].apply(selector);
        }
        return orderBy(resolved);
    }

    public NavQuery<E, T, Sel> expand(
            Function<? super Sel, ? extends Expandable<? super T>> query) {
        return expand(query.apply(selector("expand")));
    }

    private Sel selector(String operation) {
        if (selectorFactory == null) {
            throw new IllegalStateException("CollectionProperty '" + edmName
                    + "' has no selector factory; construct it with the element type's "
                    + "Selector::new (generated property constants provide one) "
                    + "(operation: " + operation + ")");
        }
        return selectorFactory.get();
    }

    // ------------------------------------------------------------------
    // Collection filter lambdas (any / all)
    // ------------------------------------------------------------------

    public FilterExpression<E> any(Function<F, FilterExpression<T>> predicate) {
        return lambda("any", predicate);
    }

    public FilterExpression<E> all(Function<F, FilterExpression<T>> predicate) {
        return lambda("all", predicate);
    }

    private static final ThreadLocal<Integer> LAMBDA_DEPTH = ThreadLocal.withInitial(() -> 0);

    private FilterExpression<E> lambda(String operator, Function<F, FilterExpression<T>> predicate) {
        if (filterableFactory == null) {
            throw new IllegalStateException("CollectionProperty '" + edmName
                    + "' has no filterable factory; construct it with the element type's Filterable::new "
                    + "(generated property constants provide one)");
        }
        int depth = LAMBDA_DEPTH.get();
        // Need element to know its base alias (FilterableElement may have custom prefix like "d")
        // Create element first to determine base alias, then compute unique alias for this depth
        F probe = filterableFactory.get();
        String baseAlias = probe instanceof FilterableElement<?> fe ? fe.prefix() : "x";
        String alias = depth == 0 ? baseAlias : baseAlias + depth;
        if (alias.isEmpty() || !Character.isJavaIdentifierStart(alias.charAt(0))) {
            throw new IllegalArgumentException("Invalid lambda alias '" + alias + "': must be a simple identifier");
        }
        for (int i = 1; i < alias.length(); i++) {
            if (!Character.isJavaIdentifierPart(alias.charAt(i))) {
                throw new IllegalArgumentException("Invalid lambda alias '" + alias + "': must be a simple identifier");
            }
        }
        LAMBDA_DEPTH.set(depth + 1);
        try {
            // Reuse probe as element for predicate (already created)
            FilterExpression<T> result = predicate.apply(probe);
            String expr = result.toODataExpression();
            // Rebind hard-coded prefix to the unique alias for nested lambdas
            if (!baseAlias.equals(alias)) {
                expr = rebindAlias(expr, baseAlias, alias);
            }
            return new RawFilterExpression<>(edmName + "/" + operator + "(" + alias + ": " + expr + ")");
        } finally {
            LAMBDA_DEPTH.set(depth);
        }
    }

    /**
     * Rewrites lambda variable references {@code baseAlias/} to {@code newAlias/} outside
     * of quoted string literals. A reference is matched only when not preceded by an
     * identifier character, so {@code 'x/y'} literals and longer paths like {@code Max/}
     * are left untouched instead of being silently corrupted.
     */
    private static String rebindAlias(String expr, String baseAlias, String newAlias) {
        String target = baseAlias + "/";
        StringBuilder out = new StringBuilder(expr.length());
        boolean inLiteral = false;
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (inLiteral) {
                out.append(c);
                if (c == '\'') {
                    // '' inside a literal is an escaped quote, not the terminator
                    if (i + 1 < expr.length() && expr.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i++;
                    } else {
                        inLiteral = false;
                    }
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inLiteral = true;
                out.append(c);
                i++;
                continue;
            }
            if (expr.startsWith(target, i)
                    && (i == 0 || !isIdentifierPart(expr.charAt(i - 1)))) {
                out.append(newAlias).append('/');
                i += target.length();
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    public FilterExpression<E> contains(T value) {
        if (value == null) {
            // No null literal is valid inside contains(...) — filter for null elements
            // explicitly (e.g. any(x: x eq null)) instead of passing null here.
            throw new IllegalArgumentException("contains value must not be null");
        }
        return new RawFilterExpression<>("contains(" + edmName + "," + formatElement(value) + ")");
    }

    public NumberExpression<Integer, E> length() {
        return new NumberExpression<>("length(" + edmName + ")", entityType);
    }

    @SuppressWarnings("unchecked")
    private String formatElement(T value) {
        if (value == null) {
            return "null";
        }
        if (elementType != null && CharSequence.class.isAssignableFrom(elementType)) {
            return "'" + String.valueOf(value).replace("'", "''") + "'";
        }
        if (value instanceof String) {
            return "'" + String.valueOf(value).replace("'", "''") + "'";
        }
        return String.valueOf(value);
    }

    /**
     * Stringly-typed filterable element for primitive collection types (e.g. {@code Collection(Edm.String)}).
     * Entity and complex-type collections should use the generated per-type {@code Filterable} class instead.
     */
    public static class FilterableElement<T> {
        private String prefix = "x";

        public FilterableElement() {}

        public FilterableElement(String prefix) {
            this.prefix = prefix;
        }

        /** The lambda alias this element's properties assume ({@code prefix/Name}). */
        public String prefix() {
            return prefix;
        }

        public StringProperty<T> stringField(String edmName) {
            return new StringProperty<>(prefix + "/" + edmName, null);
        }

        public NumberProperty<T, Long> longField(String edmName) {
            return new NumberProperty<>(prefix + "/" + edmName, null);
        }

        public NumberProperty<T, Integer> intField(String edmName) {
            return new NumberProperty<>(prefix + "/" + edmName, null);
        }

        public NumberProperty<T, Double> doubleField(String edmName) {
            return new NumberProperty<>(prefix + "/" + edmName, null);
        }

        public NumberProperty<T, Float> floatField(String edmName) {
            return new NumberProperty<>(prefix + "/" + edmName, null);
        }

        public BooleanProperty<T> booleanField(String edmName) {
            return new BooleanProperty<>(prefix + "/" + edmName, null);
        }

        public DateTimeProperty<T> dateTimeField(String edmName) {
            return new DateTimeProperty<>(prefix + "/" + edmName, null);
        }
    }
}
