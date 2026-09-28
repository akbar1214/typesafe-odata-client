package io.github.akbarhusain.odata.runtime.query;

import io.github.akbarhusain.odata.runtime.entity.ContextPath;

import java.time.Duration;
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
public class CollectionProperty<E, T, F, Sel> {
    private final String edmName;
    private final Class<E> entityType;
    private final Class<T> elementType;
    private final Supplier<F> filterableFactory;
    private final Supplier<Sel> selectorFactory;
    private final String elementEdmType;

    public CollectionProperty(String edmName, Class<E> entityType) {
        this(edmName, entityType, null, null, null, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType) {
        this(edmName, entityType, elementType, null, null, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType, Supplier<F> filterableFactory) {
        this(edmName, entityType, elementType, filterableFactory, null, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                              Supplier<F> filterableFactory, Supplier<Sel> selectorFactory) {
        this(edmName, entityType, elementType, filterableFactory, selectorFactory, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                              Supplier<F> filterableFactory, Supplier<Sel> selectorFactory,
                              String elementEdmType) {
        if (edmName == null || edmName.isBlank()) {
            throw new IllegalArgumentException("collection property name must not be blank");
        }
        if (elementEdmType != null && elementEdmType.isBlank()) {
            throw new IllegalArgumentException("collection element Edm type must not be blank");
        }
        this.edmName = edmName;
        this.entityType = entityType;
        this.elementType = elementType;
        this.filterableFactory = filterableFactory;
        this.selectorFactory = selectorFactory;
        this.elementEdmType = elementEdmType;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }
    public Class<T> getElementType() { return elementType; }
    public Supplier<F> getFilterableFactory() { return filterableFactory; }
    public String getElementEdmType() { return elementEdmType; }

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
        if (subtype == null) {
            throw new IllegalArgumentException("subtype must not be null");
        }
        return NavQuery.requireQualifiedCast(qualifiedCast);
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
        if (properties == null) throw new IllegalArgumentException("properties must not be null");
        List<String> selects = new ArrayList<>();
        for (var prop : properties) {
            if (prop == null) throw new IllegalArgumentException("select property must not be null");
            selects.add(NavQuery.selectableName(prop));
        }
        return new NavQuery<>(edmName, selects, List.of(), List.of(), null, null, null,
                List.of(), null, selectorFactory);
    }

    public NavQuery<E, T, Sel> filter(FilterExpression<? super T> predicate) {
        if (predicate == null) throw new IllegalArgumentException("filter predicate must not be null");
        return new NavQuery<>(edmName, List.of(), List.of(predicate.toODataExpression()),
                List.of(), null, null, null, List.of(), null, selectorFactory);
    }

    public NavQuery<E, T, Sel> orderBy(OrderExpression<? super T, ?>... expressions) {
        if (expressions == null) throw new IllegalArgumentException("order expressions must not be null");
        List<String> orderings = new ArrayList<>();
        for (var expr : expressions) {
            if (expr == null) throw new IllegalArgumentException("order expression must not be null");
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
        if (expandables == null) throw new IllegalArgumentException("expandables must not be null");
        List<String> expands = new ArrayList<>();
        for (var e : expandables) {
            if (e == null) throw new IllegalArgumentException("expandable must not be null");
            String rendered = e.toODataExpand();
            if (!expands.contains(rendered)) expands.add(rendered);
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
        if (selectors == null) throw new IllegalArgumentException("select selectors must not be null");
        Sel selector = selector("select");
        PropertyExpression<? super T, ?>[] resolved = new PropertyExpression[selectors.length];
        for (int i = 0; i < selectors.length; i++) {
            if (selectors[i] == null) throw new IllegalArgumentException("select selector must not be null");
            resolved[i] = selectors[i].apply(selector);
        }
        return select(resolved);
    }

    public NavQuery<E, T, Sel> filter(
            Function<? super Sel, ? extends FilterExpression<? super T>> predicate) {
        if (predicate == null) throw new IllegalArgumentException("filter selector must not be null");
        return filter(predicate.apply(selector("filter")));
    }

    @SafeVarargs
    public final NavQuery<E, T, Sel> orderBy(
            Function<? super Sel, ? extends OrderExpression<? super T, ?>>... expressions) {
        if (expressions == null) throw new IllegalArgumentException("order selectors must not be null");
        Sel selector = selector("orderBy");
        OrderExpression<? super T, ?>[] resolved = new OrderExpression[expressions.length];
        for (int i = 0; i < expressions.length; i++) {
            if (expressions[i] == null) throw new IllegalArgumentException("order selector must not be null");
            resolved[i] = expressions[i].apply(selector);
        }
        return orderBy(resolved);
    }

    public NavQuery<E, T, Sel> expand(
            Function<? super Sel, ? extends Expandable<? super T>> query) {
        if (query == null) throw new IllegalArgumentException("expand selector must not be null");
        return expand(query.apply(selector("expand")));
    }

    private Sel selector(String operation) {
        if (selectorFactory == null) {
            throw new IllegalStateException("CollectionProperty '" + edmName
                    + "' has no selector factory; construct it with the element type's "
                    + "Selector::new (generated property constants provide one) "
                    + "(operation: " + operation + ")");
        }
        Sel value = selectorFactory.get();
        if (value == null) {
            throw new IllegalStateException("selector factory returned null for CollectionProperty '"
                    + edmName + "' (operation: " + operation + ")");
        }
        return value;
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
        if (predicate == null) {
            throw new IllegalArgumentException(operator + " predicate must not be null");
        }
        if (filterableFactory == null) {
            throw new IllegalStateException("CollectionProperty '" + edmName
                    + "' has no filterable factory; construct it with the element type's Filterable::new "
                    + "(generated property constants provide one)");
        }
        int depth = LAMBDA_DEPTH.get();
        F probe = filterableFactory.get();
        if (probe == null) {
            throw new IllegalStateException("Filterable factory returned null for '" + edmName + "'");
        }
        String baseAlias = probe instanceof FilterableElement<?> fe ? fe.prefix() : "x";
        String alias = depth == 0 ? baseAlias : baseAlias + depth;
        requireODataIdentifier(alias);
        LAMBDA_DEPTH.set(depth + 1);
        try {
            FilterExpression<T> result = predicate.apply(probe);
            if (result == null) {
                throw new IllegalArgumentException(operator + " predicate returned null");
            }
            String expr = result.toODataExpression();
            if (!baseAlias.equals(alias)) {
                expr = rebindAlias(expr, baseAlias, alias);
            }
            // Reject only genuinely invalid predicates: a bare property path that is not
            // qualified by the lambda variable (`any(x: Name eq 'a')`). Constant/alias-free
            // predicates such as `any(x: true)` and `any(x: 1 eq 1)` are legal OData and pass.
            if (referencesUnqualifiedPath(expr, baseAlias, alias)) {
                throw new IllegalArgumentException(operator + " predicate must reference the bound variable '"
                        + alias + "/...' (or use '" + alias + "' directly)");
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
                    && (i == 0 || !isODataIdentifierPart(expr.charAt(i - 1)))) {
                out.append(newAlias).append('/');
                i += target.length();
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static final java.util.Set<String> LAMBDA_KEYWORDS = java.util.Set.of(
            "and", "or", "not", "eq", "ne", "gt", "ge", "lt", "le", "in", "has",
            "add", "sub", "mul", "div", "divby", "mod", "true", "false", "null");

    /**
     * True when {@code expression} contains an operand that is a bare property name not part of a
     * path — e.g. {@code Name eq 'a'} instead of {@code x/Name eq 'a'} (invalid OData). Any
     * {@code root/...} path (outer or nested-lambda alias), function calls, operators,
     * boolean/null literals and qualified enum/namespace literals are accepted, so
     * constant/alias-free predicates such as {@code true} are not rejected.
     */
    private static boolean referencesUnqualifiedPath(String expression, String baseAlias, String alias) {
        // The aliases a nested lambda actually introduces in THIS expression, i.e. the
        // identifier immediately following each "any(" / "all(". Collected here rather
        // than assumed from the alias shape, so a depth-shaped token that nothing binds is
        // still caught.
        java.util.Set<String> boundAliasesInScope = new java.util.HashSet<>();
        java.util.regex.Matcher binding = NESTED_BINDING.matcher(expression);
        while (binding.find()) {
            boundAliasesInScope.add(binding.group(1));
        }
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '\'') { // string / duration literal
                i++;
                while (i < expression.length()) {
                    if (expression.charAt(i) == '\'') {
                        if (i + 1 < expression.length() && expression.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (!isODataIdentifierStart(c)) {
                i++;
                continue;
            }
            int start = i;
            i++;
            while (i < expression.length() && isODataIdentifierPart(expression.charAt(i))) {
                i++;
            }
            String token = expression.substring(start, i);
            char prev = start > 0 ? expression.charAt(start - 1) : '\0';
            if (prev == '.') {
                continue; // namespace/enum segment
            }
            if (prev == '$') {
                // A '$'-prefixed SYSTEM SEGMENT: $count, $filter, $it, $this, $root (URL
                // Conventions §11.2.4.3). Only the ones the grammar defines as implicit
                // variables or path segments are accepted — an arbitrary $Name is not a
                // legal OData token and would smuggle an unqualified path past this guard.
                if (SYSTEM_SEGMENTS.contains(token)) {
                    continue;
                }
                return true;
            }
            if (i < expression.length() && expression.charAt(i) == '/') {
                // any path root is qualified (x/Name, x1/Value, x/Names/$count, ...) — consume
                // the whole path, including '$'-prefixed system segments such as $count.
                while (i < expression.length()
                        && (expression.charAt(i) == '/'
                        || expression.charAt(i) == '$'
                        || isODataIdentifierPart(expression.charAt(i)))) {
                    i++;
                }
                continue;
            }
            if (token.equals(alias) || boundAliasesInScope.contains(token)) {
                // The bound variable itself, used as a bare path (`x eq 'a'`). A nested
                // lambda in this predicate introduces its own depth alias
                // (`any(x1: x1 eq 'a')`), and the outer guard scans that rendered text, so
                // the aliases INTRODUCED by a nested any/all in this expression count as
                // bound. A depth-shaped name that no nested lambda actually binds is not
                // accepted — `x1 eq 'a'` in a predicate with no inner lambda is invalid
                // OData, and accepting it would weaken the check this guard exists for.
                continue;
            }
            int j = i;
            while (j < expression.length() && Character.isWhitespace(expression.charAt(j))) {
                j++;
            }
            if (j < expression.length()) {
                char next = expression.charAt(j);
                if (next == '(' || next == '\'' || next == '.' || next == ':') {
                    continue; // function call / enum literal / qualified name / lambda binding
                }
            }
            if (LAMBDA_KEYWORDS.contains(token.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * The identifier bound by a nested lambda: the segment after "any(" or "all(" up to
     * the ":" that introduces the predicate.
     */
    private static final java.util.regex.Pattern NESTED_BINDING =
            java.util.regex.Pattern.compile("\\b(?:any|all)\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:");

    /** System segments and implicit variables the OData grammar defines with a '$'. */
    private static final java.util.Set<String> SYSTEM_SEGMENTS = java.util.Set.of(
            "count", "filter", "expand", "select", "orderby", "top", "skip", "search",
            "apply", "compute", "it", "this", "root");

    private static void requireODataIdentifier(String value) {
        if (value == null || value.isEmpty() || !isODataIdentifierStart(value.charAt(0))) {
            throw new IllegalArgumentException("Invalid OData lambda alias: " + value);
        }
        for (int i = 1; i < value.length(); i++) {
            if (!isODataIdentifierPart(value.charAt(i))) {
                throw new IllegalArgumentException("Invalid OData lambda alias: " + value);
            }
        }
    }

    private static boolean isODataIdentifierStart(char c) {
        int type = Character.getType(c);
        return c == '_' || Character.isLetter(c) || type == Character.LETTER_NUMBER;
    }

    private static boolean isODataIdentifierPart(char c) {
        int type = Character.getType(c);
        return isODataIdentifierStart(c) || Character.isDigit(c) || type == Character.LETTER_NUMBER
                || type == Character.DECIMAL_DIGIT_NUMBER || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.FORMAT;
    }

    /**
     * Membership test rendered as a lambda over the collection —
     * {@code Emails/any(x: x eq 'a')}. OData's {@code contains()} is a STRING function
     * (URL Conventions §5.1.1.5); applying it to a collection is a type error on every
     * conformant service.
     */
    public FilterExpression<E> contains(T value) {
        if (value == null) {
            // No null literal is valid as an element comparison here — filter for null
            // elements explicitly (e.g. any(x: x eq null)) instead of passing null.
            throw new IllegalArgumentException("contains value must not be null");
        }
        if ("Edm.Binary".equals(elementEdmType) || value instanceof byte[]) {
            throw new IllegalArgumentException("contains() does not support Edm.Binary elements: "
                    + "OData eq is invalid for binary values");
        }
        // Unique per nesting depth so a contains() inside an any()/all() predicate never
        // shadows the enclosing lambda variable (same scheme as lambda())
        int depth = LAMBDA_DEPTH.get();
        String alias = depth == 0 ? "x" : "x" + depth;
        return new RawFilterExpression<>(edmName + "/any(" + alias + ": " + alias + " eq "
                + formatElement(value) + ")");
    }

    /**
     * The collection's size as a filterable expression — {@code Emails/$count}
     * (URL Conventions §5.1.1.4). {@code length()} is a string function in OData.
     */
    public NumberExpression<Integer, E> length() {
        return new NumberExpression<>(edmName + "/$count", entityType, "Edm.Int32");
    }

    private static String queryString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @SuppressWarnings("unchecked")
    private String formatElement(T value) {
        if (value == null) {
            return "null";
        }
        if (elementEdmType != null) {
            if ("Edm.String".equals(elementEdmType) && value instanceof String s) {
                return queryString(s);
            }
            return ContextPath.formatTypedValue(value, elementEdmType);
        }
        if (value instanceof CharSequence) {
            return queryString(value.toString());
        }
        if (value instanceof byte[]) {
            return ContextPath.formatTypedValue(value, "Edm.Binary");
        }
        if (value instanceof java.time.LocalDate) {
            return ContextPath.formatTypedValue(value, "Edm.Date");
        }
        if (value instanceof java.time.LocalTime) {
            return ContextPath.formatTypedValue(value, "Edm.TimeOfDay");
        }
        if (value instanceof java.time.OffsetDateTime) {
            return ContextPath.formatTypedValue(value, "Edm.DateTimeOffset");
        }
        if (value instanceof Duration) {
            return ContextPath.formatTypedValue(value, "Edm.Duration");
        }
        if (value instanceof Boolean) {
            return ContextPath.formatTypedValue(value, "Edm.Boolean");
        }
        if (value instanceof Number) {
            String type = value instanceof Float ? "Edm.Single"
                    : value instanceof java.math.BigDecimal ? "Edm.Decimal"
                    : value instanceof Double ? "Edm.Double"
                    : value instanceof Long ? "Edm.Int64"
                    : value instanceof Short ? "Edm.Int16"
                    : value instanceof Byte ? "Edm.SByte" : "Edm.Int32";
            return ContextPath.formatTypedValue(value, type);
        }
        if (value instanceof Enum<?> e) {
            throw new IllegalArgumentException("collection element enum type is required for contains(): "
                    + e.getClass().getName());
        }
        throw new IllegalArgumentException("collection element type is required for contains(): "
                + value.getClass().getName());
    }

    /**
     * Stringly-typed filterable element for primitive collection types (e.g. {@code Collection(Edm.String)}).
     * Entity and complex-type collections should use the generated per-type {@code Filterable} class instead.
     */
    public static class FilterableElement<T> {
        private String prefix = "x";

        public FilterableElement() {}

        public FilterableElement(String prefix) {
            requireODataIdentifier(prefix);
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
