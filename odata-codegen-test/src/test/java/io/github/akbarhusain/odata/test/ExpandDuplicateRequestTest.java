package io.github.akbarhusain.odata.test;

import com.example.trippin.container.DefaultContainer;
import com.example.trippin.entity.Person;
import com.example.trippin.entity.Trip;
import io.github.akbarhusain.odata.runtime.entity.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral (offline) proof against the GENERATED client: one navigation, one
 * {@code $expand} entry per level. A content assertion on the generated source cannot
 * prove the throw happens on the user's chain, so this drives the real request objects
 * and reads back {@code buildContext().toRelativeUrl()} for the cases that stay legal.
 */
class ExpandDuplicateRequestTest {

    private final DefaultContainer client = new DefaultContainer(
            Context.builder().baseUrl("https://services.odata.org/V4/TripPinService").build());

    private String url(com.example.trippin.collection.request.PersonCollectionRequest r) {
        return r.buildContext().toRelativeUrl();
    }

    @Test
    void twoNavigationsInOneCallRemainLegal() {
        assertEquals("People?$expand=Trips,Photo", url(client.people().expand(Person.TRIPS, Person.PHOTO)));
    }

    @Test
    void chainedDistinctNavigationsRemainLegal() {
        assertEquals("People?$expand=Trips,Photo",
                url(client.people().expand(p -> p.TRIPS).expand(p -> p.PHOTO)));
    }

    @Test
    void aSecondEntryForOneNavigationIsRejected() {
        var first = client.people().expand(Person.TRIPS.select(Trip.NAME));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> first.expand(Person.TRIPS.select(Trip.BUDGET)));
        assertTrue(e.getMessage().contains("Trips"), e.getMessage());
        assertTrue(e.getMessage().contains("Trips($select=Name)"), e.getMessage());
        assertTrue(e.getMessage().contains("Trips($select=Budget)"), e.getMessage());
    }

    @Test
    void twoCollidingArgumentsInOneCallAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> client.people().expand(Person.TRIPS, Person.TRIPS.select(Trip.NAME)));
    }

    @Test
    void anIdenticalRepeatIsStillANoOp() {
        assertEquals("People?$expand=Trips",
                url(client.people().expand(Person.TRIPS).expand(Person.TRIPS)));
    }

    @Test
    void nestedLevelRejectsASecondEntryForOneNavigation() {
        var trips = Person.TRIPS.expand(Trip.PLAN_ITEMS);
        assertThrows(IllegalArgumentException.class, () -> trips.expand(Trip.PLAN_ITEMS.select(
                com.example.trippin.entity.PlanItem.PLAN_ITEM_ID)));
    }

    @Test
    void nestedDistinctNavigationsRemainLegal() {
        assertEquals("People?$expand=Trips($expand%3DPlanItems,Photos)",
                url(client.people().expand(Person.TRIPS.expand(Trip.PLAN_ITEMS, Trip.PHOTOS))));
    }

    /**
     * Entity requests carry their own expands list, so they get the same rule — a case a
     * collection-request-only test would miss.
     */
    @Test
    void entityRequestAppliesTheSameRule() {
        assertEquals("People('scottketchum')?$expand=Trips,Photo",
                client.people("scottketchum").expand(Person.TRIPS, Person.PHOTO)
                        .buildContext().toRelativeUrl());
        var first = client.people("scottketchum").expand(Person.TRIPS.select(Trip.NAME));
        assertThrows(IllegalArgumentException.class,
                () -> first.expand(Person.TRIPS.select(Trip.BUDGET)));
    }
}