package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorIntegrationTest {

    static CsdlModel trippinModel;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = GeneratorIntegrationTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            trippinModel = parser.parse(is);
        }
    }

    @Test
    void generatesTripPinClient(@TempDir Path tempDir) throws Exception {
        String namespace = "Microsoft.OData.SampleService.Models.TripPin";
        String basePackage = "com.example.trippin";

        Map<String, String> schemaPackages = Map.of(namespace, basePackage);
        Generator generator = new Generator(tempDir, schemaPackages);
        generator.generate(trippinModel);

        // Verify key files were generated
        String pkgDir = basePackage.replace('.', '/');
        Path entityDir = tempDir.resolve(pkgDir + "/entity");
        Path enumDir = tempDir.resolve(pkgDir + "/enums");
        Path complexDir = tempDir.resolve(pkgDir + "/complex");
        Path containerDir = tempDir.resolve(pkgDir + "/container");
        Path schemaDir = tempDir.resolve(pkgDir + "/schema");

        assertTrue(Files.exists(entityDir.resolve("Person.java")), "Person.java should exist");
        assertTrue(Files.exists(entityDir.resolve("Trip.java")), "Trip.java should exist");
        assertTrue(Files.exists(entityDir.resolve("Photo.java")), "Photo.java should exist");
        assertTrue(Files.exists(entityDir.resolve("Airline.java")), "Airline.java should exist");
        assertTrue(Files.exists(entityDir.resolve("Airport.java")), "Airport.java should exist");
        assertTrue(Files.exists(entityDir.resolve("Flight.java")), "Flight.java should exist");

        assertTrue(Files.exists(enumDir.resolve("PersonGender.java")), "PersonGender.java should exist");

        assertTrue(Files.exists(complexDir.resolve("City.java")), "City.java should exist");
        assertTrue(Files.exists(complexDir.resolve("Location.java")), "Location.java should exist");

        assertTrue(Files.exists(containerDir.resolve("DefaultContainer.java")), "DefaultContainer.java should exist");
        assertTrue(Files.exists(schemaDir.resolve("ServiceSchemaInfo.java")), "ServiceSchemaInfo.java should exist");

        // Verify Person.java content
        String personCode = Files.readString(entityDir.resolve("Person.java"));
        assertTrue(personCode.contains("public final class Person"), "Should have final class");
        assertTrue(personCode.contains("implements ODataEntityType"), "Should implement ODataEntityType");
        assertTrue(personCode.contains("public static final StringProperty<Person> FIRST_NAME"), "Should have FIRST_NAME property");
        assertTrue(personCode.contains("public static final CollectionProperty<Person, Trip> TRIPS"), "Should have TRIPS collection property");
        assertTrue(personCode.contains("public TripCollectionRequest trips()") || personCode.contains("throw new UnsupportedOperationException"), "Should have trips() nav method or UnsupportedOperationException");
        assertTrue(personCode.contains("public static Builder builder()"), "Should have builder");
        assertTrue(personCode.contains("public Person withFirstName"), "Should have withFirstName");

        // Verify DefaultContainer.java content
        String containerCode = Files.readString(containerDir.resolve("DefaultContainer.java"));
        assertTrue(containerCode.contains("private final Context context"), "Should have Context field");
        assertTrue(containerCode.contains("public DefaultContainer(Context context)"), "Should have Context constructor");
        assertTrue(containerCode.contains("public PersonCollectionRequest people()"), "Should have people() method");
        assertTrue(containerCode.contains("public PhotoCollectionRequest photos()"), "Should have photos() method");
        assertTrue(containerCode.contains("public PersonEntityRequest me()"), "Should have me() singleton");

        // Verify ServiceSchemaInfo.java content
        String schemaInfoCode = Files.readString(schemaDir.resolve("ServiceSchemaInfo.java"));
        assertTrue(schemaInfoCode.contains("implements io.github.akbarhusain.odata.runtime.entity.SchemaInfo"), "Should implement SchemaInfo");
        assertTrue(schemaInfoCode.contains("Person.class"), "Should map Person");
        assertTrue(schemaInfoCode.contains("Trip.class"), "Should map Trip");
    }
}
