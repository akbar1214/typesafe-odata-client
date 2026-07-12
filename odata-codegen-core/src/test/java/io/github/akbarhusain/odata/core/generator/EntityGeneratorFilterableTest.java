package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that generated entities expose a typed {@code Filterable} inner class
 * for type-safe {@code any}/{@code all} lambda operators on collection properties.
 */
class EntityGeneratorFilterableTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private String generateEntity(String entityName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = EntityGeneratorFilterableTest.class
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
    void entityDeclaresFilterableInnerClass() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("public static class Filterable"),
                "Generated entity should declare a Filterable inner class");
    }

    @Test
    void collectionNavPropertyUsesTypedFilterable() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("public static final CollectionProperty<Person, Trip, Trip.Filterable> TRIPS"),
                "Collection navigation property should use the target entity's Filterable type");
        assertTrue(code.contains("new CollectionProperty<>(\"Trips\", Person.class, Trip.class, Trip.Filterable::new)"),
                "Collection navigation property should pass the target Filterable factory");
    }

    @Test
    void filterableExposesTypedProperties() throws Exception {
        String code = generateEntity("Trip");
        assertTrue(code.contains("public static class Filterable"),
                "Trip should declare a Filterable inner class");
        assertTrue(code.contains("public final NumberProperty<Trip,"),
                "Trip.Filterable should expose typed number properties");
    }

    @Test
    void filterablePrefixesPropertyNamesWithLambdaVariable() throws Exception {
        String code = generateEntity("Trip");
        assertTrue(code.contains("new NumberProperty<>(\"x/Budget\","),
                "Trip.Filterable properties should be prefixed with the lambda variable");
    }

    @Test
    void filterableIncludesCollectionNavPropertiesForNestedLambdas() throws Exception {
        String code = generateEntity("Trip");
        assertTrue(code.contains("public final CollectionProperty<Trip, PlanItem, PlanItem.Filterable> PLAN_ITEMS"),
                "Trip.Filterable should expose collection navigation properties for nested any/all");
    }
}
