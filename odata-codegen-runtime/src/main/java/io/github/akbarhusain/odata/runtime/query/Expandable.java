package io.github.akbarhusain.odata.runtime.query;

import java.util.ArrayList;
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
     * <p>The key is stripped, so a padded {@link #raw(String)} root cannot pose as a
     * distinct navigation.
     *
     * <p>Boundary: a raw root that contains a parenthesized group followed by another one
     * — {@code "A(B)($top=1)"} — keys as {@code "A(B)"} and would not collide with a plain
     * {@code "A(B)"}. Such a root is not valid OData to begin with (chained builders merge
     * into one group), so this is garbage in via the escape hatch rather than a reachable
     * duplicate.
     */
    static String navKey(String rendered) {
        Objects.requireNonNull(rendered, "rendered expand item must not be null");
        String path = rendered.strip();
        int open = NavQuery.trailingOptionGroupOpen(path);
        return open < 0 ? path : path.substring(0, open);
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
     * <p>Both sides are ITEMIZED first: {@code expandOption = expandItem *(COMMA
     * expandItem)}, so one collected string may hold several items — a
     * {@link #raw(String)} root such as {@code "Trips($top=1),Flights"} smuggles a second
     * entry for {@code Trips} past a plain string comparison. Commas inside an option
     * group or a string literal are not separators, so a raw root that duplicates one of
     * its OWN items is caught too. Itemization is TOP-LEVEL only: commas inside an option
     * group belong to that item's own nested {@code $expand}, which is checked only when
     * that nested value was built through the typed builders.
     *
     * <p>Emitted into generated request classes, hence public.
     */
    static void requireDistinctExpand(List<String> existing, String rendered) {
        // Fast path: nothing collected and a single item cannot conflict. Keeps the common
        // case allocation-free — a request builds NavQuery on every chained option call.
        if ((existing == null || existing.isEmpty()) && !holdsSeveralItems(rendered)) {
            return;
        }
        List<String> items = new ArrayList<>();
        if (existing != null) {
            for (String prior : existing) {
                items.addAll(expandItems(prior));
            }
        }
        items.addAll(expandItems(rendered));
        rejectDuplicateItems(items);
    }

    /** Whole-list form of {@link #requireDistinctExpand(List, String)}. */
    static void requireDistinctExpands(List<String> entries) {
        if (entries == null || entries.isEmpty()
                || (entries.size() == 1 && !holdsSeveralItems(entries.get(0)))) {
            return;
        }
        List<String> items = new ArrayList<>();
        for (String entry : entries) {
            items.addAll(expandItems(entry));
        }
        rejectDuplicateItems(items);
    }

    private static void rejectDuplicateItems(List<String> items) {
        for (int i = 0; i < items.size(); i++) {
            for (int j = 0; j < i; j++) {
                String prior = items.get(j);
                String added = items.get(i);
                if (prior.equals(added)) {
                    continue;
                }
                String key = navKey(added);
                if (key.equals(navKey(prior))) {
                    throw duplicateExpand(key, prior, added);
                }
            }
        }
    }

    private static boolean holdsSeveralItems(String rendered) {
        return rendered != null && rendered.indexOf(',') >= 0;
    }

    /**
     * The individual {@code expandItem}s in one rendered value, split on TOP-LEVEL commas
     * only. The doubled-quote literal rule mirrors {@link NavQuery#trailingOptionGroupOpen}
     * — an OData string escapes a quote by doubling it, so a comma or parenthesis inside a
     * literal belongs to the literal.
     */
    private static List<String> expandItems(String rendered) {
        if (rendered == null) {
            return List.of();
        }
        int depth = 0;
        int start = 0;
        boolean inLiteral = false;
        List<String> split = null;
        for (int i = 0; i < rendered.length(); i++) {
            char c = rendered.charAt(i);
            if (inLiteral) {
                if (c == '\'') {
                    if (i + 1 < rendered.length() && rendered.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inLiteral = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inLiteral = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == ',' && depth == 0) {
                if (split == null) {
                    split = new ArrayList<>();
                }
                split.add(rendered.substring(start, i));
                start = i + 1;
            }
        }
        if (split == null) {
            return List.of(rendered);
        }
        split.add(rendered.substring(start));
        return split;
    }

    private static IllegalArgumentException duplicateExpand(String key, String prior, String added) {
        return new IllegalArgumentException("navigation '" + key
                + "' is already expanded at this level: $expand allows one entry per navigation, and '"
                + prior + "' is already present, so '" + added + "' cannot be added as well."
                + " Chain every option onto a single expansion of '" + key
                + "' (nav.select(...).expand(...).top(...)) instead of expanding it twice.");
    }
}
