package io.github.akbarhusain.odata.runtime.client;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structured FUNCTION parameters ride OData parameter aliases as JSON literals
 * (URL Conventions §5.1.1): complex values cannot be embedded inline in the
 * invocation path, so the generated request references {@code @pN} in the segment
 * and serializes the instance into the alias query option — the same mapper action
 * bodies use.
 */
class EntityOperationsJsonParameterTest {

    @Test
    void serializesStructuredValueAsCompactJson() {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("Street", "Main");
        address.put("City", "Oslo");
        assertEquals("{\"Street\":\"Main\",\"City\":\"Oslo\"}", EntityOperations.jsonParameter(address),
                "compact JSON literal for the alias query value");
    }

    @Test
    void serializesCollectionAsJsonArray() {
        assertEquals("[\"a\",1]", EntityOperations.jsonParameter(List.of("a", 1)),
                "structured collections serialize as one JSON array literal");
    }

    @Test
    void rejectsNullLikeOtherParameterFormatters() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EntityOperations.jsonParameter(null));
        assertTrue(ex.getMessage().contains("must not be null"), ex.getMessage());
        assertTrue(ex.getMessage().contains("omitted"),
                "nullable parameters must be omitted from the invocation: " + ex.getMessage());
    }
}
