package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the wide-entity code path (>252 params): @JsonAnySetter switch,
 * no-args constructor, setters, and that the setProperty default case
 * does not throw on unmappedFields when it's a mutable HashMap.
 */
class WideEntityGeneratorTest {

    private static final String NAMESPACE = "BigModel";

    private String generateWideEntity() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = WideEntityGeneratorTest.class
                .getResourceAsStream("/large-entity-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.EntityTypeModel wideEntity = schema.entityTypes().stream()
                    .filter(e -> e.name().equals("WideEntity"))
                    .findFirst()
                    .orElseThrow();
            return new EntityGenerator("com.big").generate(wideEntity, schema);
        }
    }

    @Test
    void wideEntityUsesJsonAnySetterSwitch() throws Exception {
        String code = generateWideEntity();
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonAnySetter"),
                "Wide entity should use @JsonAnySetter for deserialization");
        assertTrue(code.contains("public void setProperty(String key, Object value)"),
                "Wide entity should have setProperty switch method");
        assertFalse(code.contains("@com.fasterxml.jackson.annotation.JsonCreator"),
                "Wide entity should NOT use @JsonCreator (exceeds 255 param limit)");
    }

    @Test
    void wideEntityHasNoArgsConstructor() throws Exception {
        String code = generateWideEntity();
        assertTrue(code.contains("public WideEntity() {"),
                "Wide entity should have public no-args constructor");
    }

    @Test
    void wideEntityHasJsonPropertyOnFields() throws Exception {
        String code = generateWideEntity();
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty(\"Prop1\")"),
                "Wide entity should have @JsonProperty on each field for Jackson deserialization");
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty(\"Id\")"),
                "Wide entity should have @JsonProperty on Id field");
    }

    @Test
    void wideEntityHasSetters() throws Exception {
        String code = generateWideEntity();
        assertTrue(code.contains("public void setId(Integer value)"),
                "Wide entity should have setter for Id");
        assertTrue(code.contains("public void setProp1(String value)"),
                "Wide entity should have setter for Prop1");
    }

    @Test
    void wideEntityUnmappedFieldsIsMutableHashMap() throws Exception {
        String code = generateWideEntity();
        // The default case in setProperty writes to unmappedFields — must be mutable
        assertTrue(code.contains("this.unmappedFields = new java.util.HashMap<>()"),
                "Wide entity no-args constructor must initialize unmappedFields as mutable HashMap");
        assertFalse(code.contains("this.unmappedFields = java.util.Map.of()"),
                "Wide entity must not use immutable Map.of() — setProperty default case writes to it");
    }

    @Test
    void wideEntityChangedFieldsIsMutable() throws Exception {
        String code = generateWideEntity();
        assertTrue(code.contains("this.changedFields = new java.util.HashSet<>()"),
                "Wide entity no-args constructor must initialize changedFields as mutable HashSet");
    }

    @Test
    void wideEntityWithMethodUsesCopyOnWrite() throws Exception {
        String code = generateWideEntity();
        // with* methods should create a new instance via no-args constructor
        assertTrue(code.contains("WideEntity e = new WideEntity();"),
                "Wide entity with* methods should create new instance via no-args constructor");
        // with* should deep-copy unmappedFields (not share by reference)
        assertTrue(code.contains("new java.util.HashMap<>(unmappedFields)"),
                "Wide entity with* methods should deep-copy unmappedFields");
    }

    @Test
    void wideEntityBuilderUsesNoArgsConstructor() throws Exception {
        String code = generateWideEntity();
        assertTrue(code.contains("WideEntity e = new WideEntity();"),
                "Wide entity Builder should use no-args constructor");
        assertTrue(code.contains("public static Builder builder()"),
                "Wide entity should have a Builder");
    }
}