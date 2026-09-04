package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link CollectionProperty} works with a user-supplied typed
 * {@code Filterable} class for compile-safe {@code any}/{@code all} lambdas, and that
 * the selector-lambda overloads (select / filter / orderBy / expand) render identically
 * to their constant forms, fail fast without a factory, and compose at full depth.
 */
class CollectionPropertyTypedLambdaTest {

    /** Minimal typed filterable for a Trip-like element. */
    static class TripFilterable {
        public final NumberProperty<TripFilterable, Integer> budget =
                new NumberProperty<>("x/Budget", null);
        public final StringProperty<TripFilterable> name =
                new StringProperty<>("x/Name", null);
    }

    /**
     * Minimal typed selector for a Trip-like element (no prefix — request-level names).
     * Property constants are typed against the ENTITY (Trip), mirroring the generated
     * Selector: {@code public final StringProperty<Trip> NAME = Trip.NAME;}.
     */
    static class TripSelector {
        public final StringProperty<Trip> name = new StringProperty<>("Name", null);
        public final NumberProperty<Trip, Integer> budget = new NumberProperty<>("Budget", null);
        public final CollectionProperty<Trip, PlanItem, PlanItemFilterable, PlanItemSelector> PLAN_ITEMS =
                new CollectionProperty<>("PlanItems", Trip.class, PlanItem.class,
                        PlanItemFilterable::new, PlanItemSelector::new);
    }

    static class PlanItemFilterable {
        public final StringProperty<PlanItemFilterable> name =
                new StringProperty<>("x/PlanItemName", null);
    }

    static class PlanItemSelector {
        public final StringProperty<PlanItem> name =
                new StringProperty<>("PlanItemName", null);
    }

    static class Person {}
    static class Trip {}
    static class PlanItem {}
    static class Doc extends Trip {}

    static class DocSelector {
        public final StringProperty<Doc> title = new StringProperty<>("Title", null);
    }

    private static CollectionProperty<Person, Trip, TripFilterable, TripSelector> trips() {
        return new CollectionProperty<>("Trips", Person.class, Trip.class,
                TripFilterable::new, TripSelector::new);
    }

    @Test
    void anyWithTypedFilterable() {
        CollectionProperty<Object, TripFilterable, TripFilterable, ?> trips =
                new CollectionProperty<>("Trips", Object.class, TripFilterable.class, TripFilterable::new);

        FilterExpression<Object> expr = trips.any(t -> t.budget.greaterThan(500));

        assertEquals("Trips/any(x: x/Budget gt 500)", expr.toODataExpression());
    }

    @Test
    void allWithTypedFilterable() {
        CollectionProperty<Object, TripFilterable, TripFilterable, ?> trips =
                new CollectionProperty<>("Trips", Object.class, TripFilterable.class, TripFilterable::new);

        FilterExpression<Object> expr = trips.all(t -> t.name.startsWith("A"));

        assertEquals("Trips/all(x: startswith(x/Name,'A'))", expr.toODataExpression());
    }

    // ------------------------------------------------------------------
    // Selector lambdas render identically to the constant forms
    // ------------------------------------------------------------------

    @Test
    void selectLambdaRendersIdenticallyToConstant() {
        assertEquals("Trips($select=Name)",
                trips().select(t -> t.name).toODataExpand());
        assertEquals(trips().select(new StringProperty<>("Name", null)).toODataExpand(),
                trips().select(t -> t.name).toODataExpand(),
                "lambda and constant select must render identically");
    }

    @Test
    void selectLambdaWithMultipleProperties() {
        assertEquals("Trips($select=Name,Budget)",
                trips().select(t -> t.name, t -> t.budget).toODataExpand());
    }

    @Test
    void filterLambdaRendersIdenticallyToConstant() {
        assertEquals("Trips($filter=Budget gt 500)",
                trips().filter(t -> t.budget.greaterThan(500)).toODataExpand());
    }

    @Test
    void orderByLambdaRendersIdenticallyToConstant() {
        assertEquals("Trips($orderby=Name)",
                trips().orderBy(t -> t.name).toODataExpand());
        assertEquals("Trips($orderby=Name desc)",
                trips().orderBy(t -> t.name.desc()).toODataExpand());
    }

    @Test
    void expandLambdaRendersBareNav() {
        assertEquals("Trips($expand=PlanItems)",
                trips().expand(t -> t.PLAN_ITEMS).toODataExpand());
    }

    @Test
    void expandLambdaWithNestedOptions() {
        assertEquals("Trips($expand=PlanItems($select=PlanItemName))",
                trips().expand(t -> t.PLAN_ITEMS.select(p -> p.name)).toODataExpand());
    }

    @Test
    void fullDepthLambdaChainComposes() {
        // hop 1 (CollectionProperty lambda) + hop 2 (NavQuery lambda) + nested select:
        // each hop's factory arrives with the value, so one Sel parameter composes recursively
        assertEquals("Trips($select=Name;$expand=PlanItems($select=PlanItemName))",
                trips().select(t -> t.name)
                        .expand(t -> t.PLAN_ITEMS.select(p -> p.name))
                        .toODataExpand());
    }

    @Test
    void lambdaChainsMixFreelyWithConstants() {
        assertEquals("Trips($select=Name;$filter=Budget gt 500;$top=2)",
                trips().select(t -> t.name)
                        .filter(new NumberProperty<>("Budget", null).greaterThan(500))
                        .top(2)
                        .toODataExpand());
    }

    // ------------------------------------------------------------------
    // NavQuery carries its own lambda overloads (hop-2+ enabler)
    // ------------------------------------------------------------------

    @Test
    void navQueryLambdasRenderIdenticallyToConstants() {
        NavQuery<Person, Trip, TripSelector> trips = NavQuery.of("Trips", TripSelector::new);
        assertEquals("Trips($select=Name)", trips.select(t -> t.name).toODataExpand());
        assertEquals("Trips($filter=Name eq 'A')", trips.filter(t -> t.name.equalTo("A")).toODataExpand());
        assertEquals("Trips($orderby=Name)", trips.orderBy(t -> t.name).toODataExpand());
    }

    @Test
    void navQueryExpandLambdaUsesCarriedFactory() {
        NavQuery<Person, Trip, TripSelector> trips = NavQuery.of("Trips", TripSelector::new);
        NavQuery<Person, Trip, TripSelector> query = trips.expand(t -> t.PLAN_ITEMS.select(p -> p.name));
        assertEquals("Trips($expand=PlanItems($select=PlanItemName))", query.toODataExpand());
    }

    // ------------------------------------------------------------------
    // Factory-less construction: constants chain fine, lambdas fail fast
    // ------------------------------------------------------------------

    @Test
    void factorylessConstantsChainButLambdasFailFast() {
        // hand-built 4-arg form carries no selector factory, though the declared
        // type promises one — the fail-fast catches the lie at runtime, not silently
        CollectionProperty<Person, Trip, TripFilterable, TripSelector> factoryless =
                new CollectionProperty<>("Trips", Person.class, Trip.class, TripFilterable::new);
        assertEquals("Trips($top=2)", factoryless.top(2).toODataExpand(),
                "constant builders must chain without a factory");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> factoryless.select(t -> t.name));
        assertTrue(ex.getMessage().contains("Trips"), ex.getMessage());
        assertThrows(IllegalStateException.class, () -> factoryless.filter(t -> t.name.equalTo("A")));
        assertThrows(IllegalStateException.class, () -> factoryless.orderBy(t -> t.name));
        assertThrows(IllegalStateException.class, () -> factoryless.expand(t -> t.PLAN_ITEMS));
    }

    @Test
    void navQueryOfWithoutFactoryFailsFastOnLambdas() {
        NavQuery<Person, Trip, TripSelector> factoryless = NavQuery.of("Trips");
        assertEquals("Trips($top=1)", factoryless.top(1).toODataExpand(),
                "constants chain fine on factory-less queries");
        assertThrows(IllegalStateException.class, () -> factoryless.select(t -> t.name));
        assertThrows(IllegalStateException.class, () -> factoryless.expand(t -> t.PLAN_ITEMS));
    }

    @Test
    void rawQueryFailsFastOnLambdas() {
        NavQuery<Person, Trip, TripSelector> raw = NavQuery.raw("Versions/ABC.Doc");
        assertThrows(IllegalStateException.class, () -> raw.select(t -> t.name));
    }

    // ------------------------------------------------------------------
    // as(): 3-arg swaps the factory, 2-arg nulls it
    // ------------------------------------------------------------------

    @Test
    void asTwoArgNullsFactoryAndThreeArgSwapsIt() {
        NavQuery<Person, Trip, TripSelector> versions = NavQuery.of("Versions", TripSelector::new);

        // 2-arg: constants chain, factory is null (selector lambdas are unreachable
        // through the wildcard — which is exactly the intent)
        NavQuery<Person, Doc, ?> cast = versions.as("ABC.Doc", Doc.class);
        assertEquals("Versions/ABC.Doc($top=1)", cast.top(1).toODataExpand());
        assertNull(cast.selectorFactory(), "2-arg as() must null the factory");

        // 3-arg: factory narrows with the type — lambdas work against the subtype selector
        NavQuery<Person, Doc, DocSelector> recast =
                versions.as("ABC.Doc", Doc.class, DocSelector::new);
        assertEquals("Versions/ABC.Doc($select=Title)", recast.select(d -> d.title).toODataExpand());
    }

    @Test
    void collectionPropertyAsForms() {
        CollectionProperty<Person, Trip, TripFilterable, TripSelector> versions = trips();

        NavQuery<Person, Doc, ?> cast2 = versions.as("ABC.Doc", Doc.class);
        assertEquals("Trips", cast2.edmName());
        assertNull(cast2.selectorFactory());
        assertEquals("Trips/ABC.Doc($top=1)", cast2.top(1).toODataExpand());

        NavQuery<Person, Doc, DocSelector> cast3 =
                versions.as("ABC.Doc", Doc.class, DocSelector::new);
        assertEquals("Trips/ABC.Doc($select=Title)", cast3.select(d -> d.title).toODataExpand());
    }

    @Test
    void asRejectsBlankCastAndNullSubtype() {
        NavQuery<Person, Trip, TripSelector> nav = NavQuery.of("Versions", TripSelector::new);
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> nav.as("  ", Doc.class));
        assertTrue(blank.getMessage().contains("qualifiedCast"));
        IllegalArgumentException nullSubtype = assertThrows(IllegalArgumentException.class,
                () -> nav.as("ABC.Doc", null));
        assertTrue(nullSubtype.getMessage().contains("subtype"));
    }

    // ------------------------------------------------------------------
    // Convention pin: identically-built queries assert equal RENDERING,
    // never instance equality (the Supplier component makes record equality
    // meaningless — method refs are distinct objects per evaluation)
    // ------------------------------------------------------------------

    @Test
    void identicallyBuiltQueriesAssertEqualRenderingNeverInstances() {
        CollectionProperty<Person, Trip, TripFilterable, TripSelector> a = trips();
        CollectionProperty<Person, Trip, TripFilterable, TripSelector> b = trips();

        // The Supplier component makes record equality meaningless (method-ref suppliers
        // may or may not be cached — identity is unspecified), so the suite never asserts
        // instance equality in either direction. Rendering is the only supported equality.
        assertEquals(a.select(t -> t.name).toODataExpand(),
                b.select(t -> t.name).toODataExpand(),
                "rendering is the only supported equality");
    }
}
