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

        NavProperty.NavQuery<Object> selectAndFilter = nav.select(name);
        NavProperty.NavQuery<Object> withFilter = new NavProperty.NavQuery<>(
                selectAndFilter.edmName(),
                selectAndFilter.selects(),
                java.util.List.of(budget.greaterThan(5000).toODataExpression()),
                selectAndFilter.orderings(),
                selectAndFilter.topOption());
        NavProperty.NavQuery<Object> withOrder = new NavProperty.NavQuery<>(
                withFilter.edmName(),
                withFilter.selects(),
                withFilter.filters(),
                java.util.List.of(name.getODataPath()),
                withFilter.topOption());
        NavProperty.NavQuery<Object> withTop = new NavProperty.NavQuery<>(
                withOrder.edmName(),
                withOrder.selects(),
                withOrder.filters(),
                withOrder.orderings(),
                "$top=5");
        assertEquals("Trips($select=Name;$filter=Budget gt 5000;$orderby=Name;$top=5)", withTop.toODataExpand());
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
}
