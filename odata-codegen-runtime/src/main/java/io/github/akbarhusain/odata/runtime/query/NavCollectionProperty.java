package io.github.akbarhusain.odata.runtime.query;

import java.util.function.Supplier;

public final class NavCollectionProperty<E, T, F, Sel> extends CollectionProperty<E, T, F, Sel> implements Expandable<E> {

    public NavCollectionProperty(String edmName, Class<E> entityType) {
        super(edmName, entityType);
    }

    public NavCollectionProperty(String edmName, Class<E> entityType, Class<T> elementType) {
        super(edmName, entityType, elementType);
    }

    public NavCollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                                 Supplier<F> filterableFactory) {
        super(edmName, entityType, elementType, filterableFactory);
    }

    public NavCollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                                 Supplier<F> filterableFactory, Supplier<Sel> selectorFactory) {
        super(edmName, entityType, elementType, filterableFactory, selectorFactory);
    }

    public NavCollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                                 Supplier<F> filterableFactory, Supplier<Sel> selectorFactory,
                                 String elementEdmType) {
        super(edmName, entityType, elementType, filterableFactory, selectorFactory, elementEdmType);
    }

    @Override
    public String toODataExpand() {
        return getEdmName();
    }
}
