package io.github.akbarhusain.odata.runtime.query;

import java.util.function.Supplier;

/**
 * A collection-valued STRUCTURAL property — {@code Collection(Edm.String)},
 * {@code Collection(Complex)} — which OData accepts in {@code $select}.
 *
 * <p>The OData ABNF lists {@code primitiveColProperty} and {@code complexColProperty} as
 * {@code selectProperty} alternatives, so a collection property can be named in
 * {@code $select}:
 *
 * <pre>{@code
 * client.people().select(Person.TAGS);                       // ?$select=Tags
 * client.people().expand(p -> p.FRIENDS.select(t -> t.TAGS)); // ?$expand=Friends($select=Tags)
 * }</pre>
 *
 * <p>Deliberately a SUBCLASS rather than selectability on {@link CollectionProperty} itself,
 * because the discriminator that matters is STRUCTURAL vs NAVIGATION, not collection-ness.
 * {@link NavCollectionProperty} extends {@code CollectionProperty}, so hoisting
 * selectability onto the base would have made navigation properties selectable too.
 *
 * <p>That exclusion is an API POLICY, not a conformance requirement. The ABNF lists
 * {@code navigationProperty} as a {@code selectProperty} alternative too, so
 * {@code ?$select=Trips} is grammar-legal — but it returns no data, because a navigation
 * has nothing to materialize without {@code $expand}. Verified against the live TripPin
 * service: {@code ?$select=Emails} answers with an {@code Emails} array, while
 * {@code ?$select=Trips} and {@code ?$select=Photo} answer {@code 200} with the navigation
 * property ABSENT from the payload. Selecting a navigation would therefore compile, succeed,
 * and hand back nothing — exactly the failure mode a typed query API exists to prevent. It
 * stays a compile error, and the navigation belongs in {@code expand(...)}.
 *
 * <p>Select-only for the same reason as {@link SelectableProperty}: it must not implement
 * {@link OrderExpression} (a collection has no primitive result value to sort on — OData
 * v4.01 Part 1 §11.2.6.2) nor {@link FilterExpression} (a collection is not a predicate).
 * The filter-side operations a collection does support — {@code any}/{@code all},
 * {@code contains}, {@code length} — are inherited unchanged.
 *
 * @param <E>   the entity type the property belongs to
 * @param <T>   the element type
 * @param <F>   the filterable type used by any/all lambdas
 * @param <Sel> the element's selector type used by the NavQuery lambda overloads
 */
public final class SelectableCollectionProperty<E, T, F, Sel>
        extends CollectionProperty<E, T, F, Sel> implements SelectableExpression<E> {

    public SelectableCollectionProperty(String edmName, Class<E> entityType) {
        super(edmName, entityType);
    }

    public SelectableCollectionProperty(String edmName, Class<E> entityType, Class<T> elementType) {
        super(edmName, entityType, elementType);
    }

    public SelectableCollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                                        Supplier<F> filterableFactory) {
        super(edmName, entityType, elementType, filterableFactory);
    }

    public SelectableCollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                                        Supplier<F> filterableFactory, Supplier<Sel> selectorFactory) {
        super(edmName, entityType, elementType, filterableFactory, selectorFactory);
    }

    public SelectableCollectionProperty(String edmName, Class<E> entityType, Class<T> elementType,
                                        Supplier<F> filterableFactory, Supplier<Sel> selectorFactory,
                                        String elementEdmType) {
        super(edmName, entityType, elementType, filterableFactory, selectorFactory, elementEdmType);
    }
}
