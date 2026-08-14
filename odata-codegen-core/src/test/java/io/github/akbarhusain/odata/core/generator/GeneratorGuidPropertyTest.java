package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H6: Edm.Guid properties must map to GuidProperty (unquoted literals in $filter),
 * not StringProperty (quoted — a type error services reject). TripPin's Trip.ShareId
 * is the only Edm.Guid property in the test metadata.
 */
class GeneratorGuidPropertyTest {

    static CsdlModel.SchemaModel schema;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = GeneratorGuidPropertyTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            schema = model.schemas().get(0);
        }
    }

    private String generateTrip() {
        CsdlModel.EntityTypeModel trip = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Trip")).findFirst().orElseThrow();
        return new EntityGenerator("com.example.trippin").generate(trip, schema);
    }

    @Test
    void guidPropertyConstantUsesGuidProperty() {
        String code = generateTrip();

        assertTrue(code.contains("public static final GuidProperty<Trip> SHARE_ID"),
                "Trip.SHARE_ID (Edm.Guid) must be a GuidProperty. Got:\n" + code);
        assertTrue(code.contains("new GuidProperty<>(\"ShareId\", Trip.class)"),
                "constant must be instantiated as GuidProperty");
        assertFalse(code.contains("StringProperty<Trip> SHARE_ID"),
                "Edm.Guid must NOT map to StringProperty (renders quoted literals)");
    }

    @Test
    void guidFilterableFieldUsesGuidProperty() {
        String code = generateTrip();

        assertTrue(code.contains("public final GuidProperty<Trip> SHARE_ID"),
                "Filterable.SHARE_ID must be a GuidProperty for typed any/all lambdas. Got:\n" + code);
        assertTrue(code.contains("new GuidProperty<>(\"x/ShareId\", Trip.class)"),
                "Filterable field must be instantiated as GuidProperty with the x/ prefix");
    }

    @Test
    void stringPropertiesStillUseStringProperty() {
        String code = generateTrip();

        assertTrue(code.contains("StringProperty<Trip> NAME"),
                "Edm.String properties must remain StringProperty. Got:\n" + code);
    }
}
