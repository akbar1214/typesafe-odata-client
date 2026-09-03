package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sealed {@link Expandable} interface names the concept "things that can appear in
 * $expand". Both implementors must render a bare navigation as the plain segment —
 * the generated request's single {@code expand(Expandable...)} overload relies on that.
 */
class ExpandableTest {

    @Test
    void bareNavQueryRendersPlainSegment() {
        Expandable<Object> expandable = NavQuery.of("Trips");
        assertEquals("Trips", expandable.toODataExpand());
    }

    @Test
    void bareCollectionPropertyRendersPlainSegment() {
        Expandable<Object> expandable =
                new CollectionProperty<>("Friends", Object.class, Object.class);
        assertEquals("Friends", expandable.toODataExpand());
    }

    @Test
    void navQueryWithOptionsRendersFullClause() {
        Expandable<Object> expandable = NavQuery.of("Trips").top(2);
        assertEquals("Trips($top=2)", expandable.toODataExpand());
    }
}
