package io.github.akbarhusain.odata.runtime.query;

/**
 * Things that can appear in a {@code $expand} clause: a collection navigation property
 * ({@link CollectionProperty}, bare segment) or a navigation with chained options
 * ({@link NavQuery}, {@code Name($select=...)}). The type parameter is the SOURCE entity
 * the expansion is scoped to, so {@code Expandable<? super E>} accepts only navigations
 * belonging to {@code E} or one of its base types.
 *
 * @param <E> the source entity type the expansion is scoped to
 */
public sealed interface Expandable<E> permits NavQuery, CollectionProperty {

    /** The OData {@code $expand} segment this value renders to. */
    String toODataExpand();
}
