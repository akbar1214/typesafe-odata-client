package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A collection-valued STRUCTURAL property — {@code Collection(Edm.String)},
 * {@code Collection(Complex)} — can be named in {@code $select}, so it needs a
 * descriptor that {@code select(...)} accepts.
 *
 * <p>It could not be named before: the generated constant is a {@link CollectionProperty},
 * which implemented no query capability at all, so
 * {@code client.people().select(Person.EMAILS)} did not compile. Real metadata has these in
 * quantity (TripPin {@code Person.Emails}, {@code Person.AddressInfo}, {@code Trip.Tags}).
 *
 * <p>Why this is a SELECT-ONLY capability, pinned below:
 * <ul>
 *   <li>not {@link OrderExpression} — a collection has no primitive result value to sort
 *       on, and OData v4.01 Part 1 §11.2.6.2 requires one;</li>
 *   <li>not {@link FilterExpression} — a collection is not a boolean predicate.</li>
 * </ul>
 *
 * <p>Why NAVIGATION properties are deliberately left out, also pinned below: selecting one
 * is a silent no-op on a real service. Verified against the live TripPin service —
 * {@code GET /People('russellwhyte')?$select=Emails} returns {@code 200} WITH an
 * {@code Emails} array, while {@code ?$select=Trips} and {@code ?$select=Photo} return
 * {@code 200} with the navigation property ABSENT from the payload entirely. So
 * {@code select(Person.TRIPS)} would compile, succeed, and hand back nothing — exactly the
 * failure mode a typed query API exists to prevent. A navigation belongs in
 * {@code expand(...)}.
 */
class SelectableCollectionPropertyTest {

    static class Person {
    }

    static class Trip {
    }

    @Test
    void carriesTheCSDLPropertyPath() {
        SelectableCollectionProperty<Person, String, CollectionProperty.FilterableElement<String>, Object> emails =
                new SelectableCollectionProperty<>("Emails", Person.class, String.class,
                        CollectionProperty.FilterableElement::new, null, "Edm.String");
        assertEquals("Emails", emails.getEdmName());
    }

    @Test
    void isSelectableBecauseItIsASelectableExpression() {
        SelectableExpression<Person> emails = new SelectableCollectionProperty<>("Emails", Person.class);
        assertEquals("Emails", emails.getEdmName());
    }

    @Test
    void isSelectableInsideANestedExpand() {
        // The realistic use: $select a collection property of an EXPANDED navigation —
        // People('x')?$expand=Trips($select=Tags), verified against the live service.
        SelectableCollectionProperty<Trip, String, CollectionProperty.FilterableElement<String>, Object> tags =
                new SelectableCollectionProperty<>("Tags", Trip.class);
        assertEquals("Trips($select=Tags)",
                NavQuery.<Person, Trip, Object>of("Trips").select(tags).toODataExpand());
    }

    @Test
    void isNotAUsableOrderExpression() {
        // Structural regression guard. A collection cannot be sorted (no primitive result
        // value), so if this type ever starts implementing PropertyExpression or
        // OrderExpression, orderBy(...) would silently accept it and emit invalid OData.
        assertFalse(OrderExpression.class.isAssignableFrom(SelectableCollectionProperty.class),
                "SelectableCollectionProperty must NOT be orderable: a collection has no "
                        + "primitive result value to sort on (OData v4.01 Part 1 s11.2.6.2)");
    }

    @Test
    void isNotAUsableFilterPredicate() {
        assertFalse(FilterExpression.class.isAssignableFrom(SelectableCollectionProperty.class),
                "a collection is not a boolean predicate");
    }

    @Test
    void navigationCollectionsAreNotSelectable() {
        // Deliberate exclusion, not an oversight. $select=Trips is a silent no-op on the
        // live service (200, navigation absent), so letting it compile would be a trap.
        // NavCollectionProperty extends CollectionProperty, so this guards the seam: if
        // selectability were ever hoisted onto the base class, nav collections would
        // silently become selectable too and this test would catch it.
        assertFalse(SelectableExpression.class.isAssignableFrom(NavCollectionProperty.class),
                "a navigation collection belongs in expand(...) -- $select on it returns "
                        + "nothing (verified: live TripPin omits the navigation from the payload)");
    }

    @Test
    void singleValuedNavigationPropertiesAreNotSelectable() {
        // Same reasoning for a single-valued nav: ?$select=Photo returns 200 with Photo absent.
        assertFalse(SelectableExpression.class.isAssignableFrom(NavQuery.class),
                "a single-valued navigation belongs in expand(...)");
    }

    @Test
    void remainsAssignableToItsBaseType() {
        // Source compatibility: existing code declaring the base type still compiles.
        CollectionProperty<Person, String, CollectionProperty.FilterableElement<String>, Object> p =
                new SelectableCollectionProperty<>("Emails", Person.class);
        assertEquals("Emails", p.getEdmName());
    }

    @Test
    void keepsItsFilterableRole() {
        SelectableCollectionProperty<Person, String, CollectionProperty.FilterableElement<String>, Object> emails =
                new SelectableCollectionProperty<>("Emails", Person.class, String.class,
                        CollectionProperty.FilterableElement::new, null, "Edm.String");
        assertEquals("Emails/any(x: x eq 'a')", emails.contains("a").toODataExpression());
        assertEquals("Emails/$count", emails.length().getODataPath());
    }

    @Test
    void rejectsABlankPropertyName() {
        assertThrows(IllegalArgumentException.class,
                () -> new SelectableCollectionProperty<>("", Person.class));
        assertThrows(IllegalArgumentException.class,
                () -> new SelectableCollectionProperty<>("   ", Person.class));
        assertThrows(IllegalArgumentException.class,
                () -> new SelectableCollectionProperty<>(null, Person.class));
    }

    @Test
    void existingScalarDescriptorsRemainSelectable() {
        // Regression witness: widening/adding descriptors must not demote the scalar ones.
        StringProperty<Person> name = new StringProperty<>("Name", Person.class);
        assertTrue(SelectableExpression.class.isAssignableFrom(StringProperty.class));
        assertEquals("Name", name.getEdmName());
    }
}
