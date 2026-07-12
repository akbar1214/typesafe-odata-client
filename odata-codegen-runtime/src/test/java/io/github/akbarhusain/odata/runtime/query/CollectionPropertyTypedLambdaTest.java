package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link CollectionProperty} works with a user-supplied typed
 * {@code Filterable} class for compile-safe {@code any}/{@code all} lambdas.
 */
class CollectionPropertyTypedLambdaTest {

    /** Minimal typed filterable for a Trip-like element. */
    static class TripFilterable {
        public final NumberProperty<TripFilterable, Integer> budget =
                new NumberProperty<>("x/Budget", null);
        public final StringProperty<TripFilterable> name =
                new StringProperty<>("x/Name", null);
    }

    @Test
    void anyWithTypedFilterable() {
        CollectionProperty<Object, TripFilterable, TripFilterable> trips =
                new CollectionProperty<>("Trips", Object.class, TripFilterable.class, TripFilterable::new);

        FilterExpression<Object> expr = trips.any(t -> t.budget.greaterThan(500));

        assertEquals("Trips/any(x: x/Budget gt 500)", expr.toODataExpression());
    }

    @Test
    void allWithTypedFilterable() {
        CollectionProperty<Object, TripFilterable, TripFilterable> trips =
                new CollectionProperty<>("Trips", Object.class, TripFilterable.class, TripFilterable::new);

        FilterExpression<Object> expr = trips.all(t -> t.name.startsWith("A"));

        assertEquals("Trips/all(x: startswith(x/Name,'A'))", expr.toODataExpression());
    }
}
