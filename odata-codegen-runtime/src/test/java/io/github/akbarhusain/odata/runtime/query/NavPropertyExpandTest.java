package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavPropertyExpandTest {

    @Test
    void simpleExpand() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        assertEquals("Trips", nav.getEdmName());
    }

    @Test
    void navQuerySimple() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        NavProperty.NavQuery<Object> query = nav.select();
        assertEquals("Trips", query.toODataExpand());
    }

    @Test
    void navQueryWithSelect() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        StringProperty<Object> name = new StringProperty<>("Name", null);
        StringProperty<Object> budget = new StringProperty<>("Budget", null);
        NavProperty.NavQuery<Object> query = nav.select(name, budget);
        assertEquals("Trips($select=Name,Budget)", query.toODataExpand());
    }

    @Test
    void navQueryWithFilter() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        NumberProperty<Object, Integer> budget = new NumberProperty<>("Budget", null);
        NavProperty.NavQuery<Object> query = nav.filter(budget.greaterThan(5000));
        assertEquals("Trips($filter=Budget gt 5000)", query.toODataExpand());
    }

    @Test
    void navQueryWithOrderBy() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        StringProperty<Object> name = new StringProperty<>("Name", null);
        NavProperty.NavQuery<Object> query = nav.orderBy(name);
        assertEquals("Trips($orderby=Name)", query.toODataExpand());
    }

    @Test
    void navQueryWithTop() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        NavProperty.NavQuery<Object> query = nav.top(5);
        assertEquals("Trips($top=5)", query.toODataExpand());
    }

    @Test
    void navQueryWithMultipleOptions() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        StringProperty<Object> name = new StringProperty<>("Name", null);
        NumberProperty<Object, Integer> budget = new NumberProperty<>("Budget", null);

        NavProperty.NavQuery<Object> query = nav.select(name)
                .filter(budget.greaterThan(5000))
                .orderBy(name)
                .top(5);
        assertEquals("Trips($select=Name;$filter=Budget gt 5000;$orderby=Name;$top=5)", query.toODataExpand());
    }

    @Test
    void collectionPropertyAsExpandable() {
        CollectionProperty<Object, Object> col = new CollectionProperty<>("Friends", Object.class, Object.class);
        assertEquals("Friends", col.getEdmName());
        assertEquals(Object.class, col.getEntityType());
        assertEquals(Object.class, col.getElementType());

        NavProperty.NavQuery<Object> query = col.select();
        assertEquals("Friends", query.toODataExpand());
    }

    @Test
    void collectionPropertyExpandWithSelect() {
        CollectionProperty<Object, Object> col = new CollectionProperty<>("Friends", Object.class, Object.class);
        StringProperty<Object> firstName = new StringProperty<>("FirstName", null);
        NavProperty.NavQuery<Object> query = col.select(firstName);
        assertEquals("Friends($select=FirstName)", query.toODataExpand());
    }

    @Test
    void navQueryWithOrderByDescending() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        StringProperty<Object> name = new StringProperty<>("Name", null);
        // desc() returns an OrderExpression whose getODataPath() already includes " desc"
        NavProperty.NavQuery<Object> query = nav.orderBy(name.desc());
        assertEquals("Trips($orderby=Name desc)", query.toODataExpand());
    }

    @Test
    void navQueryWithOrderByDescendingNavQuery() {
        NavProperty<Object, Object> nav = new NavProperty<>("Trips", Object.class, Object.class);
        StringProperty<Object> name = new StringProperty<>("Name", null);
        // NavQuery.orderBy() should produce the same output as NavProperty.orderBy()
        NavProperty.NavQuery<Object> query = nav.select().orderBy(name.desc());
        assertEquals("Trips($orderby=Name desc)", query.toODataExpand());
    }
}
