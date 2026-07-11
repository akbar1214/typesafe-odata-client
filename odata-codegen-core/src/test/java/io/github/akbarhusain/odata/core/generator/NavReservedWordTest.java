package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that navigation property names that are Java reserved words (e.g., "class")
 * or that would collide with {@link Object} method names (e.g., "getClass") are
 * properly sanitized in getter and with* method names (finding #10, #22).
 */
class NavReservedWordTest {

    private static final String NAMESPACE = "NavTest";

    private String entity(String name) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = NavReservedWordTest.class
                .getResourceAsStream("/nav-reserved-words-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.EntityTypeModel type = schema.entityTypes().stream()
                    .filter(e -> e.name().equals(name))
                    .findFirst()
                    .orElseThrow();
            return new EntityGenerator("com.navtest").generate(type, schema);
        }
    }

    @Test
    void navNamedClassDoesNotProduceGetClass() throws Exception {
        String code = entity("Source");
        assertFalse(code.contains("public Optional<Target> getClass()"),
                "Nav property 'class' must NOT produce getClass() — collides with Object.getClass()");
        assertTrue(code.contains("getClass_") || code.contains("getClass_()"),
                "Nav property 'class' should produce getClass_() — sanitized against Object method collision");
    }

    @Test
    void navNamedClassHasSanitizedWithMethod() throws Exception {
        String code = entity("Source");
        assertTrue(code.contains("withClass("),
                "Nav property 'class' should produce withClass() method");
        assertFalse(code.contains("withClass_"),
                "'class' is not a reserved word for 'with' prefix — should not be over-sanitized");
    }

    @Test
    void navNamedGetClassAlsoSanitized() throws Exception {
        String code = entity("Source");
        // "getClass" nav name → getGetClass() — does NOT collide with Object.getClass()
        // (different method name). But should still compile correctly.
        assertTrue(code.contains("getGetClass"),
                "Nav property 'getClass' should produce getGetClass() method");
    }
}