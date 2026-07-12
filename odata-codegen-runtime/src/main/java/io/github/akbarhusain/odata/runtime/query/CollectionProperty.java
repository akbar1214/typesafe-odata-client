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
        F element = filterableFactory.get();
        FilterExpression<T> result = predicate.apply(element);
        return new RawFilterExpression<>(edmName + "/any(x: " + result.toODataExpression() + ")");
    }

    public FilterExpression<E> all(Function<F, FilterExpression<T>> predicate) {
        F element = filterableFactory.get();
        FilterExpression<T> result = predicate.apply(element);
        return new RawFilterExpression<>(edmName + "/all(x: " + result.toODataExpression() + ")");
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
