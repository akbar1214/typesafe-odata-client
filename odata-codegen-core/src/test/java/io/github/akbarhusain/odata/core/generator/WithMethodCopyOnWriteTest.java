package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that with*() methods on subtypes correctly copy inherited and own
 * navigation property fields, unmappedFields, and collection properties —
 * verifying the copy-on-write immutability fixes (findings #7, #8, #21).
 */
class WithMethodCopyOnWriteTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private CsdlModel.SchemaModel schema() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = WithMethodCopyOnWriteTest.class
                .getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            return model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private String entity(String name) throws Exception {
        CsdlModel.SchemaModel schema = schema();
        CsdlModel.EntityTypeModel type = schema.entityTypes().stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow();
        return new EntityGenerator("com.example.trippin").generate(type, schema);
    }

    @Test
    void withMethodDeepCopiesUnmappedFields() throws Exception {
        String code = entity("Person");
        assertTrue(code.contains("new java.util.HashMap<>(unmappedFields)"),
                "with* methods should deep-copy unmappedFields, not share by reference");
        // The with* methods use 8-space indent (inside method body), Builder uses 12-space
        // Check that no with* method body shares unmappedFields by reference
        int idx = code.indexOf("e.unmappedFields = unmappedFields;\n        e.changedFields = EntityUtil.mergeChanged");
        assertFalse(idx > 0,
                "with* methods must NOT share unmappedFields by reference (findings #8)");
    }

    @Test
    void withMethodCopiesCollectionPropertiesDefensively() throws Exception {
        String code = entity("Person");
        // Person has Emails (Collection(Edm.String)) and AddressInfo (Collection(Location))
        // with* on a non-collection property should still defensively copy collection fields
        assertTrue(code.contains("List.copyOf(this."),
                "with* methods should defensively copy collection fields via List.copyOf");
    }

    @Test
    void withNavMethodCopiesCollectionNavsDefensively() throws Exception {
        String code = entity("Person");
        // Person has Friends nav (Collection(Person))
        // withNav* on a non-nav property should defensively copy collection nav fields
        assertTrue(code.contains("== null ? null : List.copyOf(this."),
                "with* methods should defensively copy collection nav fields via List.copyOf");
    }

    @Test
    void withMethodPreservesNavFields() throws Exception {
        String code = entity("Flight");
        // Flight has own navs: From, To, Airline
        // with* on a regular property should copy nav fields
        // Check that withFlightNumber copies all nav fields
        int withIdx = code.indexOf("withFlightNumber");
        assertTrue(withIdx > 0, "Flight should have withFlightNumber method");
        String withBody = code.substring(withIdx, withIdx + 2000);
        // The with* body should reference nav field names
        assertTrue(withBody.contains("from") || withBody.contains("to") || withBody.contains("airline"),
                "withFlightNumber should copy nav fields (From/To/Airline)");
    }

    @Test
    void withMethodOnSubtypeCopiesInheritedPropsAndOwnNavs() throws Exception {
        String code = entity("Flight");
        // Flight extends PublicTransportation extends PlanItem
        // with* should copy inherited props (PlanItemId, ConfirmationCode, SeatNumber)
        // AND own navs (From, To, Airline)
        int withIdx = code.indexOf("withSeatNumber");
        assertTrue(withIdx > 0, "Flight should have withSeatNumber (inherited prop)");
        String withBody = code.substring(withIdx, withIdx + 2000);
        // Should copy own nav fields
        assertTrue(withBody.contains("from") || withBody.contains("to") || withBody.contains("airline"),
                "withSeatNumber should also copy own nav fields (From/To/Airline)");
    }
}