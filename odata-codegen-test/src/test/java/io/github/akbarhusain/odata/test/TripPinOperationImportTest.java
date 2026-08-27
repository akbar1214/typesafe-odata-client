package io.github.akbarhusain.odata.test;

import com.example.trippin.container.DefaultContainer;
import com.example.trippin.entity.Airport;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live round-trip for the generated function-import request class — TripPin's
 * GetNearestAirport is read-only, so it is safe to exercise against the shared
 * public service (unlike the destructive ResetDataSource action, which is
 * deliberately NOT invoked here).
 */
@Tag("live-service")
class TripPinOperationImportTest {

    static DefaultContainer client;

    @BeforeAll
    static void setup() {
        client = new DefaultContainer(Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .transport(new JdkHttpTransport())
                .build());
    }

    @Test
    void getNearestAirportRoundTrip() {
        Airport airport = client.getNearestAirport(47.61357, -122.19375).execute();

        assertNotNull(airport, "function must return an Airport entity");
        assertFalse(airport.getIcaoCode().isEmpty(), "IcaoCode (key) must be materialized");
        assertNotNull(airport.getName());
    }
}
