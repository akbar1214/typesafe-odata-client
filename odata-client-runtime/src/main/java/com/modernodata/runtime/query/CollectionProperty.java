package com.modernodata.runtime.query;

import java.util.function.Function;

public final class CollectionProperty<E, T> {
    private final String edmName;
    private final Class<E> entityType;
    private final Class<T> elementType;

    public CollectionProperty(String edmName, Class<E> entityType) {
        this(edmName, entityType, null);
    }

    public CollectionProperty(String edmName, Class<E> entityType, Class<T> elementType) {
        this.edmName = edmName;
        this.entityType = entityType;
        this.elementType = elementType;
    }

    public String getEdmName() { return edmName; }
    public Class<E> getEntityType() { return entityType; }

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
