package com.modernodata.runtime;

import com.modernodata.runtime.batch.BatchOperation;
import com.modernodata.runtime.batch.BatchRequest;
import com.modernodata.runtime.batch.BatchResponse;
import com.modernodata.runtime.batch.BatchResult;
import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.entity.ContextPath;
import com.modernodata.runtime.http.HttpResponse;
import com.modernodata.runtime.http.HttpTransport;
import com.modernodata.runtime.http.JdkHttpTransport;
import com.modernodata.runtime.internal.RequestHelper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TripPinIntegrationTest {

    static Context tripPinContext;
    static ObjectMapper mapper;

    @BeforeAll
    static void setup() {
        HttpTransport transport = new JdkHttpTransport();
        tripPinContext = Context.builder()
                .baseUrl("https://services.odata.org/V4/TripPinService")
                .transport(transport)
                .build();

        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
    }

    record Person(String UserName, String FirstName, String LastName) {}
    record Trip(int TripId, String Name) {}

    @Test
    void getPeopleCollection() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("People")
                .addQuery("$top", "3");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());
        assertTrue(response.isSuccessful());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"), "Response should have 'value' array");
        assertTrue(root.get("value").isArray(), "'value' should be an array");
        assertTrue(root.get("value").size() > 0, "Should have at least one person");

        JsonNode firstPerson = root.get("value").get(0);
        assertTrue(firstPerson.has("UserName"), "Person should have UserName");
        assertTrue(firstPerson.has("FirstName"), "Person should have FirstName");
    }

    @Test
    void getPersonByKey() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("People")
                .addKey("UserName", "scottketchum");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode person = mapper.readTree(response.body());
        assertEquals("scottketchum", person.get("UserName").asText());
        assertEquals("Scott", person.get("FirstName").asText());
        assertEquals("Ketchum", person.get("LastName").asText());
    }

    @Test
    void getPersonTrips() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("People")
                .addKey("UserName", "scottketchum")
                .addSegment("Trips");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"), "Response should have 'value' array");
        assertTrue(root.get("value").size() > 0, "Scott should have trips");
    }

    @Test
    void filterPeopleByName() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("People")
                .addQuery("$filter", "FirstName eq 'Scott'")
                .addQuery("$top", "1");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.get("value").size() > 0, "Should find Scott");
        assertEquals("Scott", root.get("value").get(0).get("FirstName").asText());
    }

    @Test
    void selectFields() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("People")
                .addQuery("$select", "UserName,FirstName")
                .addQuery("$top", "1");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        JsonNode person = root.get("value").get(0);
        assertTrue(person.has("UserName"), "Should have UserName");
        assertTrue(person.has("FirstName"), "Should have FirstName");
        assertFalse(person.has("LastName"), "Should NOT have LastName (not selected)");
    }

    @Test
    void orderByPeople() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("People")
                .addQuery("$orderby", "LastName desc")
                .addQuery("$top", "3");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.get("value").size() > 0);

        String prevLastName = null;
        for (JsonNode person : root.get("value")) {
            String lastName = person.get("LastName").asText();
            if (prevLastName != null) {
                assertTrue(lastName.compareTo(prevLastName) <= 0,
                        "Should be descending: " + lastName + " <= " + prevLastName);
            }
            prevLastName = lastName;
        }
    }

    @Test
    void countPeople() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("People")
                .addQuery("$count", "true")
                .addQuery("$top", "1");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("@odata.count"), "Response should have @odata.count");
        int count = root.get("@odata.count").asInt();
        assertTrue(count > 0, "Count should be positive");
    }

    @Test
    void getAirlines() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("Airlines");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"));
        assertTrue(root.get("value").size() > 0, "Should have airlines");
    }

    @Test
    void getAirports() throws Exception {
        ContextPath path = tripPinContext.basePath()
                .addSegment("Airports")
                .addQuery("$top", "2");

        HttpResponse response = RequestHelper.executeSync(
                tripPinContext,
                com.modernodata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"));
        assertTrue(root.get("value").size() > 0);

        JsonNode airport = root.get("value").get(0);
        assertTrue(airport.has("IataCode"), "Airport should have IataCode");
        assertTrue(airport.has("Name"), "Airport should have Name");
    }

    @Test
    void batchGetMultipleEntities() {
        BatchResponse response = tripPinContext.batch()
                .add(BatchOperation.get("People('scottketchum')"))
                .add(BatchOperation.get("People('scottketchum')?$select=UserName,FirstName"))
                .execute();

        assertEquals(2, response.size());

        BatchResult<?> scottResult = response.get(0);
        assertTrue(scottResult.isSuccessful(), "Scott GET should succeed: " + scottResult.statusCode());
        assertNotNull(scottResult.body());

        BatchResult<?> scottSelectResult = response.get(1);
        assertTrue(scottSelectResult.isSuccessful(), "Scott SELECT should succeed: " + scottSelectResult.statusCode());
        assertNotNull(scottSelectResult.body());
    }

    @Test
    void batchGetEntityAndCollection() {
        BatchResponse response = tripPinContext.batch()
                .add(BatchOperation.get("People('scottketchum')"))
                .add(BatchOperation.get("People('scottketchum')/Trips?$top=2"))
                .execute();

        assertEquals(2, response.size());

        BatchResult<?> personResult = response.get(0);
        assertTrue(personResult.isSuccessful());

        BatchResult<?> tripsResult = response.get(1);
        assertTrue(tripsResult.isSuccessful());
        assertNotNull(tripsResult.body());

        String body = new String(tripsResult.body());
        assertTrue(body.contains("value"), "Collection response should have 'value'");
    }

    @Test
    void batchGetAirlinesAndAirports() {
        BatchResponse response = tripPinContext.batch()
                .add(BatchOperation.get("Airlines"))
                .add(BatchOperation.get("Airports?$top=2"))
                .execute();

        assertEquals(2, response.size());
        assertTrue(response.get(0).isSuccessful());
        assertTrue(response.get(1).isSuccessful());
    }

    @Test
    void batchWithSelect() {
        BatchResponse response = tripPinContext.batch()
                .add(BatchOperation.get("People('scottketchum')?$select=UserName,FirstName"))
                .execute();

        assertEquals(1, response.size());
        BatchResult<?> result = response.get(0);
        assertTrue(result.isSuccessful());

        String body = result.getText();
        assertTrue(body.contains("UserName"));
        assertTrue(body.contains("FirstName"));
    }

    @Test
    void batchWithFilter() {
        BatchResponse response = tripPinContext.batch()
                .add(BatchOperation.get("People?$filter=FirstName eq 'Scott'&$top=1"))
                .execute();

        assertEquals(1, response.size());
        BatchResult<?> result = response.get(0);
        assertTrue(result.isSuccessful());

        String body = result.getText();
        assertTrue(body.contains("value"));
    }
}
