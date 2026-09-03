package io.github.akbarhusain.odata.test;

import com.example.trippin.entity.Person;
import com.example.trippin.entity.Trip;
import com.example.trippin.container.DefaultContainer;
import io.github.akbarhusain.odata.runtime.entity.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Behavioral equivalence (offline): a selector-lambda-built request chain must render
 * EXACTLY the URL its constant-built twin renders — no network involved, the generated
 * requests expose their query rendering through buildContext().toRelativeUrl().
 */
class LambdaConstantUrlEquivalenceTest {

    private final DefaultContainer client = new DefaultContainer(
            Context.builder().baseUrl("https://services.odata.org/V4/TripPinService").build());

    private String url(com.example.trippin.collection.request.PersonCollectionRequest request) {
        return request.buildContext().toRelativeUrl();
    }

    @Test
    void selectLambdaMatchesConstantUrl() {
        String constant = url(client.people().select(Person.FIRST_NAME, Person.LAST_NAME));
        String lambda = url(client.people().select(p -> p.FIRST_NAME, p -> p.LAST_NAME));
        assertEquals(constant, lambda);
        assertEquals("$select=FirstName,LastName", constant.substring(constant.indexOf('?') + 1));
    }

    @Test
    void orderByAndFilterLambdasMatchConstantUrl() {
        assertEquals(
                url(client.people().orderBy(Person.USER_NAME.asc())),
                url(client.people().orderBy(p -> p.USER_NAME.asc())));
        assertEquals(
                url(client.people().filter(Person.FIRST_NAME.equalTo("Scott"))),
                url(client.people().filter(p -> p.FIRST_NAME.equalTo("Scott"))));
    }

    @Test
    void bareExpandLambdaMatchesConstantUrl() {
        assertEquals(
                url(client.people().expand(Person.PHOTO)),
                url(client.people().expand(p -> p.PHOTO)));
        assertEquals(
                url(client.people().expand(Person.TRIPS)),
                url(client.people().expand(p -> p.TRIPS)));
    }

    @Test
    void nestedExpandLambdaMatchesConstantUrl() {
        assertEquals(
                url(client.people().expand(Person.TRIPS.select(Trip.NAME))),
                url(client.people().expand(p -> p.TRIPS.select(t -> t.NAME))));
        assertEquals(
                url(client.people().expand(Person.TRIPS
                        .select(Trip.NAME)
                        .filter(Trip.BUDGET.greaterThan(500f))
                        .top(2)
                        .expand(Trip.PLAN_ITEMS.select(com.example.trippin.entity.PlanItem.PLAN_ITEM_ID)))),
                url(client.people().expand(p -> p.TRIPS
                        .select(t -> t.NAME)
                        .filter(t -> t.BUDGET.greaterThan(500f))
                        .top(2)
                        .expand(u -> u.PLAN_ITEMS.select(v -> v.PLAN_ITEM_ID)))));
    }

    @Test
    void entityRequestLambdasMatchConstantUrl() {
        String constant = client.people("russellwhyte")
                .expand(Person.TRIPS.select(Trip.NAME)).buildContext().toRelativeUrl();
        String lambda = client.people("russellwhyte")
                .expand(p -> p.TRIPS.select(t -> t.NAME)).buildContext().toRelativeUrl();
        assertEquals(constant, lambda);
    }
}
