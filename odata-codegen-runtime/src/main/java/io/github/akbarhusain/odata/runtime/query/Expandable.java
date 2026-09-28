package io.github.akbarhusain.odata.runtime.query;

/**
 * Things that can appear in a {@code $expand} clause: a collection navigation property
 * ({@link NavCollectionProperty}, bare segment) or a navigation with chained options
 * ({@link NavQuery}, {@code Name($select=...)}). The type parameter is the SOURCE entity
 * the expansion is scoped to, so {@code Expandable<? super E>} accepts only navigations
 * belonging to {@code E} or one of its base types.
 *
 * <p>Note: a structural collection constant (e.g. {@code Collection(Edm.String)}) is a
 * plain {@link CollectionProperty} and is deliberately NOT {@code Expandable} — structural
 * collections cannot appear in {@code $expand}. Code that passed one to {@code expand()}
 * must drop the call (the resulting query was invalid OData).
 *
 * @param <E> the source entity type the expansion is scoped to
 */
public sealed interface Expandable<E> permits NavQuery, NavCollectionProperty {

    /** The OData {@code $expand} segment this value renders to. */
    String toODataExpand();
}
