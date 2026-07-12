package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that generated collection requests narrow {@code select}, {@code orderBy},
 * and {@code expand} to properties/navigations scoped to the entity (or its base types),
 * mirroring the existing {@code filter(FilterExpression<? super E>)} design.
 */
class RequestGeneratorNarrowQueryTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private String generateCollectionRequest(String entityName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = RequestGeneratorNarrowQueryTest.class
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
            return new RequestGenerator("com.example.trippin").generateCollectionRequest(type, schema);
        }
    }

    @Test
    void selectIsScopedToEntity() throws Exception {
        String code = generateCollectionRequest("Person");
        assertTrue(code.contains("select(PropertyExpression<? super Person, ?>"),
                "select() should accept only properties scoped to Person or its base types");
    }

    @Test
    void orderByIsScopedToEntity() throws Exception {
        String code = generateCollectionRequest("Person");
        assertTrue(code.contains("orderBy(OrderExpression<? super Person, ?>"),
                "orderBy() should accept only order expressions scoped to Person or its base types");
    }

    @Test
    void expandNavPropertyIsScopedToEntity() throws Exception {
        String code = generateCollectionRequest("Person");
        assertTrue(code.contains("expand(NavProperty<? super Person, ?>"),
                "expand(NavProperty) should accept only navigation properties scoped to Person or its base types");
    }

    @Test
    void expandNavQueryIsScopedToEntity() throws Exception {
        String code = generateCollectionRequest("Person");
        assertTrue(code.contains("expand(NavProperty.NavQuery<? super Person, ?>"),
                "expand(NavQuery) should accept only navigation queries scoped to Person or its base types");
    }

    @Test
    void inheritedEntityUsesSuperBoundedSignatures() throws Exception {
        String code = generateCollectionRequest("Flight");
        assertTrue(code.contains("select(PropertyExpression<? super Flight, ?>"),
                "Subtype collection request should still use ? super bound for select");
        assertTrue(code.contains("orderBy(OrderExpression<? super Flight, ?>"),
                "Subtype collection request should still use ? super bound for orderBy");
        assertTrue(code.contains("expand(NavProperty<? super Flight, ?>"),
                "Subtype collection request should still use ? super bound for expand");
    }
}
