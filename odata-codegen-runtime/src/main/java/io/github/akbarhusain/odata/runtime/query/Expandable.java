package io.github.akbarhusain.odata.runtime.query;

import java.util.List;
import java.util.Objects;

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

    /**
     * The navigation an already-rendered {@code $expand} item targets: its path with the
     * trailing option group removed, so {@code "Trips($select=Name)"} keys as
     * {@code "Trips"}. The scan is the one {@link NavQuery} uses to find an option group
     * (quote- and nesting-aware), so a literal containing a parenthesis cannot shift it.
     *
     * <p>A cast segment belongs to the path: {@code "PlanItems/NS.Flight($top=1)"} keys as
     * {@code "PlanItems/NS.Flight"} and therefore does not collide with a plain
     * {@code "PlanItems"} entry.
     *
     * <p>Boundary: the key is the <em>rendered</em> path with ONE trailing group stripped,
     * so a {@link #raw(String)} root that already contains a parenthesized group followed
     * by another one — {@code "A(B)($top=1)"} — keys as {@code "A(B)"} and would not
     * collide with a plain {@code "A(B)"}. Such a root is not valid OData to begin with
     * (chained builders merge into one group), so this is garbage in via the escape hatch
     * rather than a reachable duplicate.
     */
    static String navKey(String rendered) {
        Objects.requireNonNull(rendered, "rendered expand item must not be null");
        int open = NavQuery.trailingOptionGroupOpen(rendered);
        return open < 0 ? rendered : rendered.substring(0, open);
    }

    /**
     * Rejects a second {@code $expand} entry for a navigation already expanded at this
     * level, given the entries collected so far.
     *
     * <p>{@code $expand} items are a set: two entries naming one navigation carry two
     * different option groups for the same expansion
     * ({@code $expand=Trips($select=Name),Trips($select=Budget)}), which services answer
     * inconsistently. An <em>exact</em> duplicate is a no-op rather than a conflict, so it
     * stays allowed and is collapsed by the callers.
     *
     * <p>Emitted into generated request classes, hence public.
     */
    static void requireDistinctExpand(List<String> existing, String rendered) {
        if (existing == null || existing.isEmpty()) {
            return;
        }
        String key = navKey(rendered);
        for (String prior : existing) {
            if (prior == null || rendered.equals(prior)) {
                continue;
            }
            if (key.equals(navKey(prior))) {
                throw duplicateExpand(key, prior, rendered);
            }
        }
    }

    /** Whole-list form of {@link #requireDistinctExpand(List, String)}. */
    static void requireDistinctExpands(List<String> items) {
        for (int i = 0; i < items.size(); i++) {
            requireDistinctExpand(items.subList(0, i), items.get(i));
        }
    }

    private static IllegalArgumentException duplicateExpand(String key, String prior, String added) {
        return new IllegalArgumentException("navigation '" + key
                + "' is already expanded at this level: $expand allows one entry per navigation, and '"
                + prior + "' is already present, so '" + added + "' cannot be added as well."
                + " Chain every option onto a single expansion of '" + key
                + "' (nav.select(...).expand(...).top(...)) instead of expanding it twice.");
    }
}
