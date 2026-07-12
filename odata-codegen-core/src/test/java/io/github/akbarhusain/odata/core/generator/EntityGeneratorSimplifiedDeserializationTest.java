package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that entity generation uses the simplified no-args-constructor + setter
 * deserialization strategy (like davidmoten/odata-client) instead of the hybrid
 * normal/wide entity approach.
 */
class EntityGeneratorSimplifiedDeserializationTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private String generateEntity(String entityName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = EntityGeneratorSimplifiedDeserializationTest.class
                .getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.EntityTypeModel type = schema.entityTypes().stream()
                    .filter(e -> e.name().equals(entityName))
                    .findFirst()
                    .orElseThrow();
            return new EntityGenerator("com.example.trippin").generate(type, schema);
        }
    }

    @Test
    void entityHasNoJsonCreatorConstructor() throws Exception {
        String code = generateEntity("Person");
        assertFalse(code.contains("@JsonCreator"),
                "Simplified entity should not use @JsonCreator constructor");
    }

    @Test
    void entityHasPublicNoArgsConstructor() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("public Person()"),
                "Simplified entity should have a public no-args constructor");
    }

    @Test
    void entitySettersHaveJsonPropertyAnnotation() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty(\"FirstName\")"),
                "Setters should be annotated with @JsonProperty for Jackson deserialization");
    }

    @Test
    void entityHasNoWideEntitySwitch() throws Exception {
        String code = generateEntity("Person");
        assertFalse(code.contains("setProperty(String key, Object value)"),
                "Simplified entity should not emit a wide-entity @JsonAnySetter switch");
        assertFalse(code.contains("switch (key)"),
                "Simplified entity should not contain a property-key switch");
    }

    @Test
    void etagSetterUsesODataAnnotation() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty(\"@odata.etag\")"),
                "ETag setter should use the @odata.etag JSON property name");
    }
}
