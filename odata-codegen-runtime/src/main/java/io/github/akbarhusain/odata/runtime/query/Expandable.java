package io.github.akbarhusain.odata.runtime.query;

/**
 * Things that can appear in a {@code $expand} clause: a collection navigation property
 * ({@link NavCollectionProperty}, bare segment) or a navigation with chained options
 * ({@link NavQuery}, {@code Name($select=...)}). The type parameter is the SOURCE entity
 * the expansion is scoped to, so {@code Expandable<? super E>} accepts only navigations
 * belonging to {@code E} or one of its base types.
 *
 * <p>Note: a structural collection constant (e.g. {@code Collection(Edm.String)}) is a
 * {@link SelectableCollectionProperty} — select-only — or, for {@code Edm.Stream}, a plain
 * {@link CollectionProperty}. Neither implements {@code Expandable}, so a structural
 * collection cannot be passed to {@code expand()} directly: a structural collection is not
 * a navigation.
 *
 * <p>Known limitation: passing one through a chained {@link CollectionProperty} builder
 * still compiles, because those builders return {@link NavQuery}, which IS
 * {@code Expandable} — {@code expand(tags.top(5))} renders the invalid
 * {@code $expand=Tags($top=5)}. Closing it means moving the {@code NavQuery}-returning
 * builders off {@code CollectionProperty} onto {@link NavCollectionProperty}, so a
 * structural collection cannot reach them; until that refactor lands this form is
 * reachable and unguarded.
 *
 * @param <E> the source entity type the expansion is scoped to
 */
public sealed interface Expandable<E> permits NavQuery, NavCollectionProperty {

    /** The OData {@code $expand} segment this value renders to. */
    String toODataExpand();
}
