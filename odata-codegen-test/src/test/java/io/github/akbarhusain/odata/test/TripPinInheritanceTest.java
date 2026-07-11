package io.github.akbarhusain.odata.test;

import com.example.trippin.complex.AirportLocation;
import com.example.trippin.complex.City;
import com.example.trippin.complex.EventLocation;
import com.example.trippin.complex.Location;
import com.example.trippin.container.DefaultContainer;
import com.example.trippin.entity.Airport;
import com.example.trippin.entity.Event;
import com.example.trippin.entity.Flight;
import com.example.trippin.entity.PlanItem;
import com.example.trippin.entity.PublicTransportation;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ODataEntityType;
import io.github.akbarhusain.odata.runtime.entity.ODataType;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises generated inheritance for the TripPin service:
 * <ul>
 *   <li>Complex types: {@code EventLocation}/{@code AirportLocation} extend {@code Location}.</li>
 *   <li>Entities: {@code Flight → PublicTransportation → PlanItem}, {@code Event → PlanItem}.</li>
 * </ul>
 */
class TripPinInheritanceTest {

    static DefaultContainer client;
    static Context context;

    @BeforeAll
    static void setup() {
        context = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .transport(new JdkHttpTransport())
                .build();
        client = new DefaultContainer(context);
    }

    // --- Complex-type inheritance ---

    @Test
    void complexType_subtypesAreInstancesOfBase() {
        City city = City.builder().countryRegion("US").name("Lander").region("WY").build();

        EventLocation event = new EventLocation("100 Main St", city, "Building A", null);
        AirportLocation airport = new AirportLocation("Airport Rd", city, null, null);

        assertInstanceOf(Location.class, event, "EventLocation should be a Location");
        assertInstanceOf(Location.class, airport, "AirportLocation should be a Location");
        assertInstanceOf(ODataType.class, event, "EventLocation should implement ODataType");
    }

    @Test
    void complexType_baseVariableHoldsSubtypeAndExposesInheritedProperties() {
        City city = City.builder().countryRegion("US").name("Lander").region("WY").build();

        // Polymorphic assignment: a Location reference holds an EventLocation
        Location loc = new EventLocation("100 Main St", city, "Building A", null);

        // Inherited getters are accessible via the base type
        assertEquals("100 Main St", loc.getAddress());
        assertEquals("Lander", loc.getCity().getName());

        // Own property is reachable after a downcast
        assertInstanceOf(EventLocation.class, loc);
        assertEquals("Building A", ((EventLocation) loc).getBuildingInfo().orElse(null));
    }

    @Test
    void complexType_subtypeWithMethodPreservesInheritedFields() {
        City city = City.builder().countryRegion("US").name("Lander").region("WY").build();
        EventLocation original = new EventLocation("100 Main St", city, "Building A", null);

        // Changing the own property preserves inherited fields and returns the subtype
        EventLocation updated = original.withBuildingInfo("Building B");
        assertEquals("Building B", updated.getBuildingInfo().orElse(null));
        assertEquals("100 Main St", updated.getAddress(), "inherited Address preserved");
        assertEquals("Lander", updated.getCity().getName(), "inherited City preserved");

        // Changing an inherited property also returns the subtype (not the base)
        EventLocation moved = original.withAddress("200 Main St");
        assertEquals(EventLocation.class, moved.getClass());
        assertEquals("200 Main St", moved.getAddress());
        assertEquals("Building A", moved.getBuildingInfo().orElse(null), "own field preserved");
    }

    @Test
    void complexType_baseBuilderProducesBaseType() {
        City city = City.builder().countryRegion("US").name("Lander").region("WY").build();
        Location loc = Location.builder().address("1 Base St").city(city).build();

        assertEquals(Location.class, loc.getClass());
        assertEquals("1 Base St", loc.getAddress());
    }

    @Test
    void complexType_liveAirportLocationDeserializesWithInheritedFields() {
        // Airport.Location is typed as AirportLocation (a Location subtype)
        CollectionPage<Airport> page = client.airports().top(1).get();
        assertFalse(page.currentPage().isEmpty(), "Should have at least one airport");

        Airport airport = page.currentPage().get(0);
        AirportLocation location = airport.getLocation();
        assertNotNull(location, "Airport should have a Location");

        // AirportLocation is-a Location, so inherited properties are populated
        assertInstanceOf(Location.class, location);
        assertNotNull(location.getAddress(), "inherited Address should deserialize");
        assertNotNull(location.getCity(), "inherited City should deserialize");
        assertNotNull(location.getCity().getName(), "nested City.Name should deserialize");
    }

    // --- Entity inheritance ---

    @Test
    void entity_flightHierarchyChain() {
        Flight flight = new Flight();
        flight.setPlanItemId(1);
        flight.setConfirmationCode("ABC123");
        flight.setSeatNumber("12A");
        flight.setFlightNumber("AA100");

        assertInstanceOf(PublicTransportation.class, flight, "Flight is a PublicTransportation");
        assertInstanceOf(PlanItem.class, flight, "Flight is a PlanItem");
        assertInstanceOf(ODataEntityType.class, flight, "Flight is an ODataEntityType");
    }

    @Test
    void entity_eventIsPlanItem() {
        Event event = new Event();
        event.setPlanItemId(2);
        event.setConfirmationCode("EVT1");
        event.setDescription("Conference");
        assertInstanceOf(PlanItem.class, event, "Event is a PlanItem");
        assertInstanceOf(ODataEntityType.class, event);
    }

    @Test
    void entity_subtypeExposesInheritedAndOwnProperties() {
        Flight flight = new Flight();
        flight.setPlanItemId(5);
        flight.setConfirmationCode("CONF5");
        flight.setSeatNumber("9C");
        flight.setFlightNumber("UA900");

        // Inherited getters (from PlanItem / PublicTransportation)
        assertEquals(5, flight.getPlanItemId());
        assertEquals("CONF5", flight.getConfirmationCode().orElse(null));
        assertEquals("9C", flight.getSeatNumber().orElse(null));
        // Own getter
        assertEquals("UA900", flight.getFlightNumber());
    }

    @Test
    void entity_subtypeWithMethodReturnsSubtypeAndPreservesInheritedFields() {
        Flight flight = new Flight();
        flight.setPlanItemId(7);
        flight.setConfirmationCode("CONF7");
        flight.setSeatNumber("1A");
        flight.setFlightNumber("DL7");

        // Changing own property returns Flight, inherited fields preserved
        Flight renumbered = flight.withFlightNumber("DL8");
        assertEquals(Flight.class, renumbered.getClass());
        assertEquals("DL8", renumbered.getFlightNumber());
        assertEquals(7, renumbered.getPlanItemId(), "inherited key preserved");
        assertEquals("1A", renumbered.getSeatNumber().orElse(null), "inherited SeatNumber preserved");

        // Changing an inherited property also returns Flight
        Flight reseated = flight.withSeatNumber("2B");
        assertEquals(Flight.class, reseated.getClass());
        assertEquals("2B", reseated.getSeatNumber().orElse(null));
        assertEquals("DL7", reseated.getFlightNumber(), "own field preserved");
    }

    @Test
    void entity_baseAndSubtypePropertyConstantsExist() {
        // Base-type constants
        assertNotNull(PlanItem.PLAN_ITEM_ID);
        assertNotNull(PlanItem.CONFIRMATION_CODE);
        // Intermediate-type constant
        assertNotNull(PublicTransportation.SEAT_NUMBER);
        // Leaf-type constants
        assertNotNull(Flight.FLIGHT_NUMBER);
        assertNotNull(Flight.AIRLINE);
        assertNotNull(Event.DESCRIPTION);
    }

    @Test
    void entity_polymorphicCollectionOfPlanItems() {
        Flight flight = new Flight();
        flight.setPlanItemId(10);
        flight.setFlightNumber("AA1");
        Event event = new Event();
        event.setPlanItemId(11);
        event.setDescription("Sightseeing");

        List<PlanItem> items = new java.util.ArrayList<>();
        items.add(flight);
        items.add(event);
        assertEquals(2, items.size());
        assertInstanceOf(Flight.class, items.get(0));
        assertInstanceOf(Event.class, items.get(1));
        // All are addressable through the shared base getter
        assertEquals(10, items.get(0).getPlanItemId());
        assertEquals(11, items.get(1).getPlanItemId());
    }
}
