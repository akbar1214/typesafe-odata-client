package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavQueryExpandTest {

    private static class Version {}

    private static class Doc extends Version {
    }

    @Test
    void simpleExpand() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        assertEquals("Trips", nav.edmName());
    }

    @Test
    void navQuerySimple() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        NavQuery<Object, Object, Object> query = nav.select();
        assertEquals("Trips", query.toODataExpand());
    }

    @Test
    void navQueryWithSelect() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        StringProperty<Object> name = new StringProperty<>("Name", null);
        StringProperty<Object> budget = new StringProperty<>("Budget", null);
        NavQuery<Object, Object, Object> query = nav.select(name, budget);
        assertEquals("Trips($select=Name,Budget)", query.toODataExpand());
    }

    @Test
    void navQueryWithFilter() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        NumberProperty<Object, Integer> budget = new NumberProperty<>("Budget", null);
        NavQuery<Object, Object, Object> query = nav.filter(budget.greaterThan(5000));
        assertEquals("Trips($filter=Budget gt 5000)", query.toODataExpand());
    }

    @Test
    void navQueryWithOrderBy() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        StringProperty<Object> name = new StringProperty<>("Name", null);
        NavQuery<Object, Object, Object> query = nav.orderBy(name);
        assertEquals("Trips($orderby=Name)", query.toODataExpand());
    }

    @Test
    void navQueryWithTop() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        NavQuery<Object, Object, Object> query = nav.top(5);
        assertEquals("Trips($top=5)", query.toODataExpand());
    }

    @Test
    void navQueryWithMultipleOptions() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        StringProperty<Object> name = new StringProperty<>("Name", null);
        NumberProperty<Object, Integer> budget = new NumberProperty<>("Budget", null);

        NavQuery<Object, Object, Object> query = nav.select(name)
                .filter(budget.greaterThan(5000))
                .orderBy(name)
                .top(5);
        assertEquals("Trips($select=Name;$filter=Budget gt 5000;$orderby=Name;$top=5)", query.toODataExpand());
    }

    @Test
    void collectionPropertyAsExpandable() {
        CollectionProperty<Object, Object, CollectionProperty.FilterableElement<Object>, ?> col =
                new CollectionProperty<>("Friends", Object.class, Object.class, CollectionProperty.FilterableElement::new);
        assertEquals("Friends", col.getEdmName());
        assertEquals(Object.class, col.getEntityType());
        assertEquals(Object.class, col.getElementType());
        // bare collection nav renders the plain segment (Expandable)
        assertEquals("Friends", col.toODataExpand());

        NavQuery<Object, Object, ?> query = col.select();
        assertEquals("Friends", query.toODataExpand());
    }

    @Test
    void collectionPropertyExpandWithSelect() {
        CollectionProperty<Object, Object, CollectionProperty.FilterableElement<Object>, ?> col =
                new CollectionProperty<>("Friends", Object.class, Object.class, CollectionProperty.FilterableElement::new);
        StringProperty<Object> firstName = new StringProperty<>("FirstName", null);
        NavQuery<Object, Object, ?> query = col.select(firstName);
        assertEquals("Friends($select=FirstName)", query.toODataExpand());
    }

    @Test
    void navQueryWithOrderByDescending() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        StringProperty<Object> name = new StringProperty<>("Name", null);
        // desc() returns an OrderExpression whose getODataPath() already includes " desc"
        NavQuery<Object, Object, Object> query = nav.orderBy(name.desc());
        assertEquals("Trips($orderby=Name desc)", query.toODataExpand());
    }

    @Test
    void navQueryWithOrderByDescendingNavQuery() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        StringProperty<Object> name = new StringProperty<>("Name", null);
        // NavQuery.orderBy() should produce the same output from a chained query
        NavQuery<Object, Object, Object> query = nav.select().orderBy(name.desc());
        assertEquals("Trips($orderby=Name desc)", query.toODataExpand());
    }

    @Test
    void navQueryExpandWithNestedNavQuery() {
        NavQuery<Object, Object, Object> trips = NavQuery.of("Trips");
        NavQuery<Object, Object, Object> planItems = NavQuery.of("PlanItems");
        NavQuery<Object, Object, Object> query = trips.expand(planItems);
        assertEquals("Trips($expand=PlanItems)", query.toODataExpand());
    }

    @Test
    void navQueryAsRendersQualifiedCastAndKeepsSubtypeType() {
        NavQuery<Object, Version, ?> versions = NavQuery.of("Versions");
        NavQuery<Doc, Object, Object> abc = NavQuery.of("abc");

        NavQuery<Object, Doc, ?> query = versions.as("ABC.Doc", Doc.class).expand(abc);

        assertEquals("Versions/ABC.Doc($expand=abc)", query.toODataExpand());
    }

    @Test
    void navQueryExpandWithBareCollectionProperty() {
        // Expandable accepts BOTH implementors: a bare collection nav renders its name
        NavQuery<Object, Object, Object> trips = NavQuery.of("Trips");
        CollectionProperty<Object, Object, CollectionProperty.FilterableElement<Object>, ?> friends =
                new CollectionProperty<>("Friends", Object.class, Object.class, CollectionProperty.FilterableElement::new);
        NavQuery<Object, Object, Object> query = trips.expand(friends);
        assertEquals("Trips($expand=Friends)", query.toODataExpand());
    }

    @Test
    void navQueryRawRendersVerbatim() {
        assertEquals("Versions/ABC.Doc($expand=abc)",
                NavQuery.raw("Versions/ABC.Doc($expand=abc)").toODataExpand());
    }

    @Test
    void navQueryRawComposesWithOptions() {
        NavQuery<Object, Object, Object> abc = NavQuery.of("abc");
        NavQuery<Object, Object, Object> query = NavQuery.<Object, Object, Object>raw("Versions/ABC.Doc")
                .expand(abc);
        assertEquals("Versions/ABC.Doc($expand=abc)", query.toODataExpand(),
                "raw is a root path; chained options must render, not be silently dropped");
    }

    @Test
    void navQueryRawChainsIntoExistingOptionGroup() {
        // a raw string that ALREADY carries an option group must merge chained options
        // with ';' — appending a second paren group would emit invalid OData
        NavQuery<Object, Object, Object> abc2 = NavQuery.of("abc2");
        NavQuery<Object, Object, Object> query = NavQuery
                .<Object, Object, Object>raw("Versions/ABC.Doc($expand=abc)")
                .expand(abc2);
        assertEquals("Versions/ABC.Doc($expand=abc;$expand=abc2)", query.toODataExpand());
    }

    @Test
    void navQueryRawEmptyOptionGroupTakesChainedOptions() {
        NavQuery<Object, Object, Object> query =
                NavQuery.<Object, Object, Object>raw("A()").top(2);
        assertEquals("A($top=2)", query.toODataExpand(),
                "an empty trailing group is replaced by the chained options");
    }

    @Test
    void navQueryRawNestedOptionGroupMergesAtTopLevel() {
        // nested parens inside the existing group (a lambda) must not confuse the merge
        NavQuery<Object, Object, Object> query = NavQuery
                .<Object, Object, Object>raw("Trips($filter=Items/any(d: d/V eq 1))")
                .top(1);
        assertEquals("Trips($filter=Items/any(d: d/V eq 1);$top=1)", query.toODataExpand());
    }

    @Test
    void navQueryAsRejectsBlankCast() {
        NavQuery<Object, Version, ?> versions = NavQuery.of("Versions");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> versions.as("  ", Doc.class));

        assertTrue(ex.getMessage().contains("qualifiedCast"), ex.getMessage());
    }

    @Test
    void navQueryExpandWithNestedNavQueryChained() {
        NavQuery<Object, Object, Object> trips = NavQuery.of("Trips");
        NavQuery<Object, Object, Object> planItems = NavQuery.of("PlanItems");
        NavQuery<Object, Object, Object> query = trips.expand(planItems.select());
        assertEquals("Trips($expand=PlanItems)", query.toODataExpand());
    }

    @Test
    void navQueryExpandChainsNested() {
        NavQuery<Object, Object, Object> trips = NavQuery.of("Trips");
        NavQuery<Object, Object, Object> planItems = NavQuery.of("PlanItems");
        StringProperty<Object> name = new StringProperty<>("Name", null);
        NavQuery<Object, Object, Object> query = trips.select(name)
                .expand(planItems.select());
        assertEquals("Trips($select=Name;$expand=PlanItems)", query.toODataExpand());
    }

    @Test
    void navQueryExpandMultipleNested() {
        NavQuery<Object, Object, Object> trips = NavQuery.of("Trips");
        NavQuery<Object, Object, Object> planItems = NavQuery.of("PlanItems");
        NavQuery<Object, Object, Object> airline = NavQuery.of("Airline");
        NavQuery<Object, Object, Object> query = trips.expand(planItems.select(), airline.select());
        assertEquals("Trips($expand=PlanItems,Airline)", query.toODataExpand());
    }

    @Test
    void navQueryExpandDeepMultiLevel() {
        NavQuery<Object, Object, Object> people = NavQuery.of("People");
        NavQuery<Object, Object, Object> trips = NavQuery.of("Trips");
        NavQuery<Object, Object, Object> planItems = NavQuery.of("PlanItems");
        NavQuery<Object, Object, Object> query = people.expand(
                trips.expand(planItems.select()));
        assertEquals("People($expand=Trips($expand=PlanItems))", query.toODataExpand());
    }

    @Test
    void navQueryExpandDeepWithNestedOptions() {
        NavQuery<Object, Object, Object> people = NavQuery.of("People");
        NavQuery<Object, Object, Object> trips = NavQuery.of("Trips");
        NavQuery<Object, Object, Object> planItems = NavQuery.of("PlanItems");
        StringProperty<Object> name = new StringProperty<>("Name", null);
        NavQuery<Object, Object, Object> query = people
                .expand(trips.select(name).expand(planItems.select(name)));
        assertEquals("People($expand=Trips($select=Name;$expand=PlanItems($select=Name)))",
                query.toODataExpand());
    }

    @Test
    void m7MultipleFiltersAreParenthesizedToPreservePrecedence() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        NumberProperty<Object, Integer> budget = new NumberProperty<>("Budget", null);
        StringProperty<Object> name = new StringProperty<>("Name", null);
        NavQuery<Object, Object, Object> query = nav
                .filter(budget.greaterThan(5000).or(budget.lessThan(100)))
                .filter(name.contains("trip"));
        // RawFilterExpression.or() already parenthesizes its operands; the NavQuery join
        // wraps each predicate so 'and' cannot bind inside the 'or' group
        assertEquals("Trips($filter=((Budget gt 5000) or (Budget lt 100)) and (contains(Name,'trip')))",
                query.toODataExpand(),
                "joined $filter predicates must be parenthesized: unparenthesized 'or' + 'and' "
                        + "changes semantics because 'and' binds tighter");
    }

    @Test
    void l10SkipAndCountOptionsRender() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        assertEquals("Trips($skip=5)", nav.skip(5).toODataExpand());
        assertEquals("Trips($count=true)", nav.count().toODataExpand());

        NavQuery<Object, Object, Object> query = nav.top(2).skip(4).count();
        assertEquals("Trips($top=2;$skip=4;$count=true)", query.toODataExpand());
    }

    @Test
    void l10NavQueryListsAreImmutable() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        NumberProperty<Object, Integer> budget = new NumberProperty<>("Budget", null);
        NavQuery<Object, Object, Object> query = nav.filter(budget.greaterThan(100));

        assertThrows(UnsupportedOperationException.class,
                () -> query.filters().add("injected eq true"),
                "record list components must be defensively copied");
        assertThrows(UnsupportedOperationException.class,
                () -> query.selects().add("Injected"));
    }

    @Test
    void l12SelectRejectsFunctionTransformations() {
        NavQuery<Object, Object, Object> nav = NavQuery.of("Trips");
        StringProperty<Object> name = new StringProperty<>("Name", null);
        DateTimeProperty<Object> startsAt = new DateTimeProperty<>("StartsAt", null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> nav.select(name.toUpper()),
                "$select=tolower(Name) is invalid — only structural property paths are selectable");
        assertTrue(ex.getMessage().contains("toupper(Name)"), "message shows the offending name: " + ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> nav.select(startsAt.date()));
        assertThrows(IllegalArgumentException.class,
                () -> nav.select(name).select(name.toUpper()),
                "NavQuery.select must reject them too");
        assertDoesNotThrow(() -> nav.select(name), "plain properties stay selectable");
    }

    @Test
    void ofWithFactoryKeepsFactoryThroughChaining() {
        NavQuery<Object, Object, SelectorFixture> nav = NavQuery.of("Trips", SelectorFixture::new);
        NavQuery<Object, Object, SelectorFixture> query = nav.top(2);
        assertEquals("Trips($top=2)", query.toODataExpand());
        assertNotNull(query.selectorFactory(), "chaining must propagate the selector factory");
    }

    @Test
    void zeroArgOrderByBridgeStaysLegalOnBothImplementors() {
        // a single varargs overload made zero-arg orderBy() legal before the lambda
        // overloads existed; without the bridge it would match BOTH varargs overloads
        // and be ambiguous — same hazard the select()/expand() bridges cover
        assertEquals("Trips", NavQuery.<Object, Object, Object>of("Trips").orderBy().toODataExpand());
        CollectionProperty<Object, Object, CollectionProperty.FilterableElement<Object>, ?> col =
                new CollectionProperty<>("Friends", Object.class, Object.class, CollectionProperty.FilterableElement::new);
        assertEquals("Friends", col.orderBy().toODataExpand());
    }

    /** Minimal selector fixture for factory wiring tests. */
    static final class SelectorFixture {
    }
}
