package io.github.akbarhusain.odata.runtime.query;

import java.util.function.Function;
import java.util.function.Supplier;

public final class CollectionProperty<E, T, F> extends NavProperty<E, T> {
    private final Class<T> elementType;
    private final Supplier<F> filterableFactory;

    public CollectionProperty(String edmName, Class<E> entityType) {
        this(edmName, entityType, null, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType) {
        this(edmName, entityType, elementType, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType, Supplier<F> filterableFactory) {
        super(edmName, entityType, elementType);
        this.elementType = elementType;
        this.filterableFactory = filterableFactory;
    }

    public Class<T> getElementType() { return elementType; }
    public Supplier<F> getFilterableFactory() { return filterableFactory; }

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
