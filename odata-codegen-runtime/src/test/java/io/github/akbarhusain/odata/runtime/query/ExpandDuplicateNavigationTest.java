package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One navigation, one entry. {@code $expand} is a comma-separated list of expand ITEMS,
 * and two items naming the same navigation ask for the same expansion twice with divergent
 * option groups ({@code $expand=Trips($select=Name),Trips($select=Budget)}) — a request no
 * service answers consistently. The builders therefore reject a second entry for a
 * navigation already expanded at the same level, and point at the single-chain spelling.
 *
 * <p>The identity key is the item's navigation PATH with its option group stripped, so a
 * cast segment is part of the identity: {@code PlanItems} and {@code PlanItems/NS.Flight}
 * are two different items and may be expanded together. An exact duplicate stays silently
 * idempotent (a no-op, not a conflict).
 */
class ExpandDuplicateNavigationTest {

    /** Owner of the child navigations under test. */
    private static NavQuery<Object, Object, Object> person() {
        return NavQuery.of("Person");
    }

    private static NavQuery<Object, Object, Object> nav(String name) {
        return NavQuery.of(name);
    }

    private static StringProperty<Object> prop(String name) {
        return new StringProperty<>(name, null);
    }

    @Test
    void secondExpandOfTheSameNavigationIsRejected() {
        NavQuery<Object, Object, Object> withTrips =
                person().expand(nav("Trips").select(prop("Name")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withTrips.expand(nav("Trips").select(prop("Budget"))));
        assertTrue(e.getMessage().contains("Trips"), e.getMessage());
    }

    @Test
    void twoEntriesForOneNavigationInASingleCallAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> person().expand(nav("Trips"), nav("Trips").select(prop("Name"))));
    }

    @Test
    void exactDuplicateStaysIdempotent() {
        NavQuery<Object, Object, Object> trips = nav("Trips");
        NavQuery<Object, Object, Object> expanded = person().expand(trips).expand(trips);
        assertEquals("Person($expand=Trips)", expanded.toODataExpand());
    }

    @Test
    void differentNavigationsAreDistinct() {
        NavQuery<Object, Object, Object> expanded =
                person().expand(nav("Trips"), nav("Flights"));
        assertEquals("Person($expand=Trips,Flights)", expanded.toODataExpand());
    }

    /** A cast segment changes the item: {@code PlanItems/NS.Flight} is not {@code PlanItems}. */
    @Test
    void castSegmentIsPartOfTheNavigationKey() {
        NavQuery<Object, Object, Object> expanded =
                nav("PlanItems").expand(NavQuery.raw("PlanItems/ODataDemo.FeaturedProduct"));
        assertEquals("PlanItems($expand=PlanItems/ODataDemo.FeaturedProduct)",
                expanded.toODataExpand());
    }

    /**
     * A raw root may already carry an option group; the key ignores it, so it still
     * collides with a plain entry for the same navigation at the same level.
     */
    @Test
    void rawRootWithAnOptionGroupCollidesAtTheSameLevel() {
        NavQuery<Object, Object, Object> withRaw = person().expand(NavQuery.raw("Trips($select=abc)"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withRaw.expand(nav("Trips").select(prop("Budget"))));
        assertTrue(e.getMessage().contains("Trips"), e.getMessage());
    }

    @Test
    void structuralCollectionExpandRejectsTwoEntriesForOneNavigation() {
        CollectionProperty<Object, Object, Object, Object> tags =
                new CollectionProperty<>("Tags", Object.class, Object.class, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> tags.expand(nav("Tags").top(1), nav("Tags").top(2)));
    }

    /**
     * The invariant belongs to the record, not to one builder method: a hand-built NavQuery
     * carrying two entries for one navigation is equally unanswerable.
     */
    @Test
    void handBuiltNavQueryWithTwoEntriesForOneNavigationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new NavQuery<>("Person", List.of(), List.of(), List.of(), null, null, null,
                        List.of("Trips", "Trips($top=1)")));
    }

    @Test
    void messageNamesTheNavigationAndBothRenderings() {
        NavQuery<Object, Object, Object> withTrips =
                person().expand(nav("Trips").select(prop("Name")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withTrips.expand(nav("Trips").select(prop("Budget"))));
        String m = e.getMessage();
        assertTrue(m.contains("Trips"), m);
        assertTrue(m.contains("Trips($select=Name)"), m);
        assertTrue(m.contains("Trips($select=Budget)"), m);
    }

    /**
     * A raw root may hold SEVERAL items — {@code expandOption = expandItem *(COMMA
     * expandItem)} — so its contents are split before keying. Otherwise
     * {@code raw("Trips($top=1),Flights")} smuggles a second entry for Trips past the
     * check and renders an unanswerable value.
     */
    @Test
    void rawRootWithTopLevelCommasIsItemizedBeforeValidation() {
        NavQuery<Object, Object, Object> smuggle =
                person().expand(NavQuery.raw("Trips($top=1),Flights"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> smuggle.expand(nav("Trips").select(prop("Name"))));
        assertTrue(e.getMessage().contains("Trips"), e.getMessage());
    }

    /** Same smuggle arriving in ONE call as the first entry for the navigation. */
    @Test
    void aRawRootSmugglingSeveralItemsIsCaughtInASingleCall() {
        assertThrows(IllegalArgumentException.class,
                () -> person().expand(nav("Trips"), NavQuery.raw("Trips($top=1),Flights")));
    }

    @Test
    void aRawRootThatDuplicatesOneOfItsOwnItemsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> person().expand(NavQuery.raw("Trips,Trips($top=1)")));
    }

    /** Commas inside an option group (or a literal) are not item separators. */
    @Test
    void commasInsideOptionGroupsAndLiteralsAreNotSplit() {
        assertEquals("Trips", Expandable.navKey("Trips($filter=Name eq 'a,b')"));
        assertEquals("Trips", Expandable.navKey("Trips($expand=Friends($top=1),Photos)"));
        // no over-splitting: the nested Friends entry must not collide with a top-level one
        NavQuery<Object, Object, Object> nested = person()
                .expand(NavQuery.raw("Trips($expand=Friends($top=1),Photos)"))
                .expand(nav("Flights"));
        assertEquals("Person($expand=Trips($expand=Friends($top=1),Photos),Flights)",
                nested.toODataExpand());
    }

    /** A padded root is never a distinct legal navigation. */
    @Test
    void paddedRawRootsKeyTheSameAsUnpaddedOnes() {
        assertEquals(Expandable.navKey("Trips"), Expandable.navKey("Trips "));
        NavQuery<Object, Object, Object> withTrips = person().expand(nav("Trips"));
        assertThrows(IllegalArgumentException.class,
                () -> withTrips.expand(NavQuery.raw("Trips($top=1) ")));
    }

    @Test
    void thePublicHelpersTolerateNullListsSymmetrically() {
        assertDoesNotThrow(() -> Expandable.requireDistinctExpand(null, "Trips"));
        assertDoesNotThrow(() -> Expandable.requireDistinctExpands(null));
    }

    @Test
    void navKeyStripsOnlyTheTrailingOptionGroup() {
        assertEquals("Trips", Expandable.navKey("Trips"));
        assertEquals("Trips", Expandable.navKey("Trips($select=Name;$top=3)"));
        assertEquals("PlanItems/ODataDemo.FeaturedProduct",
                Expandable.navKey("PlanItems/ODataDemo.FeaturedProduct($expand=x)"));
        assertEquals("Versions/ABC.Doc", Expandable.navKey("Versions/ABC.Doc($expand=abc)"));
    }
}