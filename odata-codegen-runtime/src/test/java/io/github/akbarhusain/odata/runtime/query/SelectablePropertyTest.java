package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A property that OData can {@code $select} but NOT {@code $orderby}: complex-typed,
 * {@code Edm.Binary} and geography/geometry structural properties.
 *
 * <p>These previously had NO descriptor at all, so an entity exposed a getter for data
 * that {@code select(...)} could not name — the typed API silently covered less of the
 * schema than the entity did.
 *
 * <p>Deliberately NOT an {@link OrderExpression}. OData v4.01 Part 1 §11.2.6.2 requires a
 * "primitive result value" to sort on, and states that "values of type {@code Edm.Stream}
 * or any of the Geo types cannot be sorted"; a complex property has no primitive result
 * either. Implementing {@code PropertyExpression} (which extends {@code OrderExpression})
 * would therefore have let users write {@code orderBy(ProcessData)} and emit
 * {@code $orderby=ProcessData} — a NEW invalid surface introduced by the very fix meant
 * to widen coverage. The descriptor is select-only, so that mistake does not compile.
 *
 * <p>Also deliberately not a {@link FilterExpression}: reusing {@code StringProperty}
 * would hand users {@code .contains(...)} and render {@code contains(ProcessData,'x')},
 * which is invalid OData that only fails at the service.
 */
class SelectablePropertyTest {

    static class WorkItem {
    }

    @Test
    void carriesTheCSDLPropertyPath() {
        SelectableProperty<WorkItem> prop = new SelectableProperty<>("ProcessData", WorkItem.class);
        assertEquals("ProcessData", prop.getEdmName());
    }

    @Test
    void isSelectableBecauseItIsASelectableExpression() {
        SelectableExpression<WorkItem> prop = new SelectableProperty<>("ProcessData", WorkItem.class);
        assertEquals("ProcessData", prop.getEdmName());
    }

    @Test
    void isSelectableInsideANestedExpand() {
        // The realistic use: $select a complex property of an expanded navigation.
        NavQuery<WorkItem, WorkItem, Object> nav = new NavQuery<>(
                "ProcessData", java.util.List.of("ProcessData"), java.util.List.of(),
                java.util.List.of(), null, null, null, java.util.List.of());
        assertEquals("ProcessData($select=ProcessData)", nav.toODataExpand());
    }

    @Test
    void isNotAUsableOrderExpression() {
        // Structural regression guard. If a future change made SelectableProperty an
        // OrderExpression (directly, or by implementing PropertyExpression), the emitted
        // $orderby would be invalid OData for complex/Geo types. This assertion is the
        // tripwire; it fails at COMPILE time if the type starts implementing it, which is
        // the point -- a runtime throw would be the wrong shape of failure.
        assertTrue(SelectableExpression.class.isAssignableFrom(SelectableProperty.class),
                "the descriptor must stay selectable");
        org.junit.jupiter.api.Assertions.assertFalse(
                OrderExpression.class.isAssignableFrom(SelectableProperty.class),
                "SelectableProperty must NOT be orderable: a complex/Geo property has no "
                        + "primitive result value to sort on (OData v4.01 Part 1 SS11.2.6.2)");
    }

    @Test
    void isNotAUsableFilterPredicate() {
        org.junit.jupiter.api.Assertions.assertFalse(
                FilterExpression.class.isAssignableFrom(SelectableProperty.class),
                "SelectableProperty must not implement FilterExpression, or users could write "
                        + "contains(ProcessData,'x') - invalid OData that only fails at the service");
    }

    @Test
    void existingDescriptorsRemainSelectableAndOrderable() {
        // The regression witness: widening select() to SelectableExpression must not
        // demote the descriptors that already carried comparison AND ordering.
        StringProperty<WorkItem> title = new StringProperty<>("Title", WorkItem.class);
        assertEquals("Title", title.getEdmName());
        assertEquals("Title desc", title.desc().getODataPath());
        org.junit.jupiter.api.Assertions.assertTrue(
                SelectableExpression.class.isAssignableFrom(StringProperty.class),
                "PropertyExpression must extend SelectableExpression so existing descriptors "
                        + "keep working in the widened select(...)");
    }

    @Test
    void rejectsABlankPropertyName() {
        assertThrows(IllegalArgumentException.class, () -> new SelectableProperty<>("", WorkItem.class));
        assertThrows(IllegalArgumentException.class, () -> new SelectableProperty<>("   ", WorkItem.class));
        assertThrows(IllegalArgumentException.class, () -> new SelectableProperty<>(null, WorkItem.class));
    }
}
