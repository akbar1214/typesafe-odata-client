package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that complex-type generation uses the simplified no-args-constructor +
 * setter deserialization strategy.
 */
class ComplexTypeGeneratorSimplifiedDeserializationTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private String generateComplexType(String name) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = ComplexTypeGeneratorSimplifiedDeserializationTest.class
                .getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.ComplexTypeModel type = schema.complexTypes().stream()
                    .filter(e -> e.name().equals(name))
                    .findFirst()
                    .orElseThrow();
            return new ComplexTypeGenerator("com.example.trippin").generate(type, schema);
        }
    }

    @Test
    void complexTypeHasNoJsonCreatorConstructor() throws Exception {
        String code = generateComplexType("Location");
        assertFalse(code.contains("@JsonCreator"),
                "Simplified complex type should not use @JsonCreator constructor");
    }

    @Test
    void complexTypeHasPublicNoArgsConstructor() throws Exception {
        String code = generateComplexType("Location");
        assertTrue(code.contains("public Location()"),
                "Simplified complex type should have a public no-args constructor");
    }

    @Test
    void complexTypeSettersHaveJsonPropertyAnnotation() throws Exception {
        String code = generateComplexType("Location");
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty("),
                "Complex type setters should be annotated with @JsonProperty");
    }

    @Test
    void complexTypeFieldsAreNotFinal() throws Exception {
        String code = generateComplexType("Location");
        assertFalse(code.contains("protected final"),
                "Simplified complex type fields must be mutable for setter-based deserialization");
    }
}
