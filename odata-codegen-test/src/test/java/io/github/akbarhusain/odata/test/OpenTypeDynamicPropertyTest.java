package io.github.akbarhusain.odata.test;

import com.example.trippin.complex.City;
import com.example.trippin.entity.Event;
import com.example.trippin.entity.Person;
import com.example.odata.entity.Category;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises generated OpenType support: undeclared JSON fields on {@code OpenType="true"} entities
 * are captured into {@code unmappedFields} on deserialization and round-tripped on serialization.
 *
 * <ul>
 *   <li>TripPin {@code Person} — open root entity.</li>
 *   <li>TripPin {@code Event} — open entity extending the non-open {@code PlanItem} root.</li>
 *   <li>OData Demo {@code Category} — open root entity.</li>
 * </ul>
 */
class OpenTypeDynamicPropertyTest {

    private final JacksonSerializer serializer = new JacksonSerializer();

    private byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void openRootEntityCapturesDynamicProperties() {
        String json = "{"
                + "\"UserName\":\"russellwhyte\","
                + "\"FirstName\":\"Russell\","
                + "\"LastName\":\"Whyte\","
                + "\"Nickname\":\"Rusty\","
                + "\"Age\":37"
                + "}";

        Person person = serializer.deserialize(bytes(json), Person.class);

        assertEquals("russellwhyte", person.getUserName());
        assertEquals("Russell", person.getFirstName());

        Map<String, Object> dynamic = person.getUnmappedFields();
        assertEquals("Rusty", dynamic.get("Nickname"), "undeclared string field captured");
        assertEquals(37, ((Number) dynamic.get("Age")).intValue(), "undeclared numeric field captured");

        assertEquals("Rusty", person.getDynamicProperty("Nickname").orElseThrow());
        assertTrue(person.getDynamicProperty("Missing").isEmpty());
    }

    @Test
    void odataControlAnnotationsAreNotCapturedAsDynamic() {
        String json = "{"
                + "\"@odata.id\":\"People('x')\","
                + "\"@odata.editLink\":\"People('x')\","
                + "\"UserName\":\"x\","
                + "\"FirstName\":\"X\","
                + "\"LastName\":\"Y\","
                + "\"Extra\":\"kept\""
                + "}";

        Person person = serializer.deserialize(bytes(json), Person.class);

        Map<String, Object> dynamic = person.getUnmappedFields();
        assertTrue(dynamic.containsKey("Extra"));
        assertFalse(dynamic.containsKey("@odata.id"), "@odata.* control fields must be filtered out");
        assertFalse(dynamic.containsKey("@odata.editLink"));
    }

    @Test
    void unmappedFieldsViewIsUnmodifiable() {
        Person person = serializer.deserialize(
                bytes("{\"UserName\":\"x\",\"FirstName\":\"X\",\"LastName\":\"Y\",\"Foo\":\"bar\"}"),
                Person.class);
        assertThrows(UnsupportedOperationException.class,
                () -> person.getUnmappedFields().put("hack", "no"));
    }

    @Test
    void dynamicPropertyConvertsToTargetClass() {
        String json = "{"
                + "\"UserName\":\"x\","
                + "\"FirstName\":\"X\","
                + "\"LastName\":\"Y\","
                + "\"HomeCity\":{\"CountryRegion\":\"US\",\"Name\":\"Lander\",\"Region\":\"WY\"}"
                + "}";

        Person person = serializer.deserialize(bytes(json), Person.class);

        City city = person.getDynamicProperty("HomeCity", City.class).orElseThrow();
        assertEquals("Lander", city.getName());
        assertEquals("US", city.getCountryRegion());

        // Absent property -> empty, regardless of requested type
        assertTrue(person.getDynamicProperty("Missing", City.class).isEmpty());
    }

    @Test
    void dynamicPropertyCoercesNumberToTargetClass() {
        Person person = serializer.deserialize(
                bytes("{\"UserName\":\"x\",\"FirstName\":\"X\",\"LastName\":\"Y\",\"Age\":37}"),
                Person.class);

        // Jackson binds the unknown number as Integer; convertValue coerces to Long.
        Long age = person.getDynamicProperty("Age", Long.class).orElseThrow();
        assertEquals(37L, age);
    }

    @Test
    void openSubtypeOfNonOpenBaseCapturesDynamicProperties() {
        String json = "{"
                + "\"PlanItemId\":1,"
                + "\"ConfirmationCode\":\"C1\","
                + "\"Description\":\"Dinner\","
                + "\"Rating\":5"
                + "}";

        Event event = serializer.deserialize(bytes(json), Event.class);

        assertEquals("Dinner", event.getDescription().orElse(null));
        assertEquals(5, ((Number) event.getUnmappedFields().get("Rating")).intValue(),
                "open subtype captures dynamic props into the inherited root map");
    }

    @Test
    void dynamicPropertiesRoundTripOnSerialization() {
        Category category = serializer.deserialize(
                bytes("{\"ID\":1,\"Name\":\"Food\",\"Loyalty\":\"gold\"}"),
                Category.class);

        String out = new String(serializer.serialize(category, Category.class), StandardCharsets.UTF_8);
        assertTrue(out.contains("\"Loyalty\":\"gold\""),
                "dynamic property should be re-serialized as a top-level field, was: " + out);
    }

    @Test
    void openDemoCategoryCapturesDynamicProperties() {
        String json = "{\"ID\":1,\"Name\":\"Food\",\"Featured\":true}";

        Category category = serializer.deserialize(bytes(json), Category.class);

        assertEquals("Food", category.getName().orElse(null));
        assertEquals(Boolean.TRUE, category.getUnmappedFields().get("Featured"));
    }
}
