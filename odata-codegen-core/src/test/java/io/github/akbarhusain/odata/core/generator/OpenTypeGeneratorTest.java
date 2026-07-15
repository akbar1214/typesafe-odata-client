package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class OpenTypeGeneratorTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private CsdlModel.SchemaModel schema() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = OpenTypeGeneratorTest.class.getResourceAsStream("/trippin-metadata.xml")) {
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

    private String complexType(String name) throws Exception {
        CsdlModel.SchemaModel schema = schema();
        CsdlModel.ComplexTypeModel type = schema.complexTypes().stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow();
        ComplexTypeGenerator gen = new ComplexTypeGenerator("com.example.trippin");
        gen.setGenerateWithMethods(true);
        return gen.generate(type, schema);
    }

    @Test
    void openRootEntityCapturesDynamicProperties() throws Exception {
        String code = entity("Person");
        assertTrue(code.contains("JsonAnySetter"),
                "Open entity should have a @JsonAnySetter for dynamic properties");
        assertTrue(code.contains("JsonAnyGetter"),
                "Open entity should round-trip dynamic props via @JsonAnyGetter");
        assertTrue(code.contains("public Optional<Object> getDynamicProperty(String name)"),
                "Open entity should expose getDynamicProperty");
        assertTrue(code.contains("public <T> Optional<T> getDynamicProperty(String name, Class<T> type)"),
                "Open entity should expose the typed getDynamicProperty(String, Class) overload");
        assertTrue(code.contains("this.unmappedFields = new java.util.HashMap<>()"),
                "Open root must initialize a mutable unmappedFields map");
        assertTrue(code.contains("Collections.unmodifiableMap(unmappedFields)"),
                "getUnmappedFields should return an unmodifiable view");
    }

    @Test
    void nonOpenEntityIsUnchanged() throws Exception {
        String code = entity("Airline");
        // All entities now use no-args constructor + @JsonProperty setters for deserialization
        assertTrue(code.contains("public Airline()"),
                "Non-open entity must have a public no-args constructor");
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty"),
                "Non-open entity uses @JsonProperty setters for deserialization");
        assertFalse(code.contains("JsonAnySetter"), "Non-open entity must not have @JsonAnySetter");
        assertFalse(code.contains("getDynamicProperty"), "Non-open entity must not expose getDynamicProperty");
        assertTrue(code.contains("this.unmappedFields = java.util.Map.of()"),
                "Non-open entity keeps the zero-alloc empty map");
    }

    @Test
    void openSubtypeOfNonOpenBaseCapturesViaInheritedRootMap() throws Exception {
        // Event (open) extends PlanItem (NOT open). The mutable map lives in the PlanItem root,
        // and Event owns the any-setter.
        String plan = entity("PlanItem");
        assertTrue(plan.contains("protected java.util.Map<String, Object> unmappedFields;"),
                "Root PlanItem declares the unmappedFields field");
        assertTrue(plan.contains("this.unmappedFields = new java.util.HashMap<>()"),
                "PlanItem root must use a mutable map because open subtype Event exists");
        assertTrue(plan.contains("public PlanItem()"),
                "PlanItem must have a public no-args constructor");

        String event = entity("Event");
        assertTrue(event.contains("JsonAnySetter"),
                "Open subtype Event owns the any-setter");
        assertTrue(event.contains("public void setDynamicProperty(String name, Object value)"),
                "Event any-setter mutates the inherited root map");
    }

    @Test
    void builderForNonOpenEntityUsesZeroAllocMap() throws Exception {
        String code = entity("Airline");
        assertTrue(code.contains("private java.util.Map<String, Object> unmappedFields = java.util.Map.of()"),
                "Builder for non-open entity must not allocate a HashMap");
    }

    @Test
    void nonOpenComplexTypeDoesNotReferenceUnmappedFieldsInWith() throws Exception {
        // City is a non-open complex type in TripPin. Its with*() should NOT reference unmappedFields.
        CsdlModel.SchemaModel schema = schema();
        CsdlModel.ComplexTypeModel cityType = schema.complexTypes().stream()
                .filter(e -> e.name().equals("City"))
                .findFirst()
                .orElseThrow();
        ComplexTypeGenerator gen = new ComplexTypeGenerator("com.example.trippin");
        gen.setGenerateWithMethods(true);
        String code = gen.generate(cityType, schema);
        assertFalse(code.contains("unmappedFields"),
                "Non-open complex type City must not reference unmappedFields anywhere");
        assertFalse(code.contains("HashMap"),
                "Non-open complex type must not allocate HashMap");
    }

    @Test
    void openComplexTypeCapturesDynamicProperties() throws Exception {
        String location = complexType("Location");
        assertTrue(location.contains("protected java.util.Map<String, Object> unmappedFields;"),
                "Open complex root declares unmappedFields");
        assertTrue(location.contains("JsonAnySetter"),
                "Open complex type captures dynamic props");
        assertTrue(location.contains("this.unmappedFields = new java.util.HashMap<>()"),
                "Open complex root uses a mutable map");
        assertTrue(location.contains("public <T> Optional<T> getDynamicProperty(String name, Class<T> type)"),
                "Open complex type exposes the typed getDynamicProperty(String, Class) overload");

        // Verify with*() preserves unmappedFields for open complex types via defensive copy
        assertTrue(location.contains("new java.util.HashMap<>(unmappedFields)"),
                "Open complex type with*() must copy unmappedFields to preserve dynamic props");
        assertTrue(location.contains("public Location()"),
                "Open complex type must have a public no-args constructor");

        // EventLocation extends open Location — inherits the any-setter, no duplicate.
        String eventLocation = complexType("EventLocation");
        assertFalse(eventLocation.contains("public void setProperty(String key, Object value)"),
                "Open subtype must not duplicate the inherited any-setter");
        assertTrue(eventLocation.contains("JsonAnyGetter"),
                "Open subtype still annotates its getUnmappedFields override for serialization");

        // Verify with*() on the subtype preserves unmappedFields via defensive copy
        assertTrue(eventLocation.contains("new java.util.HashMap<>(unmappedFields)"),
                "Open subtype with*() must copy unmappedFields to preserve dynamic props");
        assertTrue(eventLocation.contains("public EventLocation()"),
                "Open subtype must have a public no-args constructor");
    }
}
