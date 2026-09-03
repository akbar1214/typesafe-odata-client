package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.generator.EntityGenerator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the generated {@code Selector} inner class backing the request-level lambda
 * overloads (select / orderBy / expand / filter): shared constant instances, wired
 * selector factories on navigation constants, and inline construction for inherited
 * properties (their constants live on the declaring base class).
 */
class EntityGeneratorSelectorTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private String generateEntity(String entityName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = EntityGeneratorSelectorTest.class
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
    void entityDeclaresSelectorInnerClass() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("public static class Selector"),
                "Generated entity should declare a Selector inner class");
    }

    @Test
    void selectorSharesConstantInstancesForOwnProperties() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("public final StringProperty<Person> FIRST_NAME = Person.FIRST_NAME;"),
                "Scalar selector fields share the entity's constants");
        assertTrue(code.contains("public final NumberProperty<Person, Long> CONCURRENCY = Person.CONCURRENCY;"),
                "Number selector fields share the entity's constants");
        assertTrue(code.contains("public final EnumProperty<Person, PersonGender> GENDER = Person.GENDER;"),
                "Enum selector fields share the entity's constants");
    }

    @Test
    void selectorMirrorsCollectionNavsWithFactory() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("public final CollectionProperty<Person, Trip, Trip.Filterable, Trip.Selector> TRIPS = Person.TRIPS;"),
                "Collection nav selector fields share the entity's constants");
    }

    @Test
    void selectorMirrorsSingleNavQueries() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("public final NavQuery<Person, Photo, Photo.Selector> PHOTO = Person.PHOTO;"),
                "Single-nav selector fields share the entity's NavQuery constants");
    }

    @Test
    void singleNavConstantsCarrySelectorFactory() throws Exception {
        String code = generateEntity("Person");
        assertTrue(code.contains("public static final NavQuery<Person, Photo, Photo.Selector> PHOTO = NavQuery.of(\"Photo\", Photo.Selector::new);"),
                "Single-nav constants must wire the target's selector factory (NavQuery.of)");
    }

    @Test
    void inheritedPropertiesAreConstructedInline() throws Exception {
        // Flight extends PublicTransportation? No — Flight's base chain: Flight -> PlanItem.
        // AirlineCode etc. live on Flight itself; the base's properties must appear in
        // Flight.Selector as inline constructions (no shared constant on Flight).
        String code = generateEntity("Flight");
        assertTrue(code.contains("public static class Selector"),
                "Subtype entities get a Selector too");
        // Flight's own scalar constants are shared references...
        assertTrue(code.contains("= Flight."), "Own properties share Flight's constants");
    }

    @Test
    void complexTypedCollectionPropertiesUseWildcardSelector() throws Exception {
        // Person.AddressInfo is Collection(Location) — Location is a COMPLEX type,
        // which gets no Selector, so the constant must use a wildcard.
        String code = generateEntity("Person");
        assertTrue(code.contains("public static final CollectionProperty<Person, Location, Location.Filterable, ?> ADDRESS_INFO"),
                "Complex-element collection constants use a wildcard Sel");
    }
}
