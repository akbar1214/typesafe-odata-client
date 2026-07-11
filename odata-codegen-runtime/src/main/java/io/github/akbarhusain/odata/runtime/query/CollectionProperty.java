package io.github.akbarhusain.odata.runtime.query;

import java.util.function.Function;

public final class CollectionProperty<E, T> extends NavProperty<E, T> {
    private final Class<T> elementType;

    public CollectionProperty(String edmName, Class<E> entityType) {
        this(edmName, entityType, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType) {
        super(edmName, entityType, elementType);
        this.elementType = elementType;
    }

    public Class<T> getElementType() { return elementType; }

    public FilterExpression any(Function<FilterableElement<T>, FilterExpression> predicate) {
        FilterableElement<T> element = new FilterableElement<>();
        FilterExpression result = predicate.apply(element);
        return new RawFilterExpression(edmName + "/any(x: " + result.toODataExpression() + ")");
    }

    public FilterExpression all(Function<FilterableElement<T>, FilterExpression> predicate) {
        FilterableElement<T> element = new FilterableElement<>();
        FilterExpression result = predicate.apply(element);
        return new RawFilterExpression(edmName + "/all(x: " + result.toODataExpression() + ")");
    }

    public static class FilterableElement<T> {
        private String prefix = "x";

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
    }
}
