package io.github.akbarhusain.odata.core.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OData JSON Format §4.5.3: when an entity (or complex value) is of a type DERIVED from
 * the declared type of its container — posting a Flight to PlanItems, an EventLocation
 * inside AddressInfo — the payload MUST carry {@code "@odata.type": "#NS.Type"}; without
 * it services deserialize the base type and drop every subtype property. Types with no
 * BaseType keep their payloads annotation-free (the annotation is optional there and some
 * services reject unexpected control information).
 */
class TypeAnnotationSerializationTest {

    private static final String NS = "Microsoft.OData.SampleService.Models.TripPin";
    private static final String PKG = "com.example.trippin";

    private URLClassLoader generateAndCompileTripPin(Path tempDir) throws Exception {
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(NS, PKG), PKG).withGenerateWithMethods(true).generate(model);
        String errors = CompilationHarness.compileAll(out);
        assertNull(errors, "TripPin client must compile. Errors:\n" + errors);
        return new URLClassLoader(new URL[]{out.resolve(".classes").toUri().toURL()},
                getClass().getClassLoader());
    }

    private static Object instance(ClassLoader loader, String fqn) throws Exception {
        return Class.forName(fqn, true, loader).getDeclaredConstructor().newInstance();
    }

    @Test
    void derivedEntityCarriesTypeAnnotationAndBaseDoesNot(@TempDir Path tempDir) throws Exception {
        try (URLClassLoader loader = generateAndCompileTripPin(tempDir)) {
            ObjectMapper mapper = JacksonSerializer.newODataMapper();
            String flight = mapper.writeValueAsString(instance(loader, PKG + ".entity.Flight"));
            String planItem = mapper.writeValueAsString(instance(loader, PKG + ".entity.PlanItem"));

            assertTrue(flight.contains("\"@odata.type\":\"#" + NS + ".Flight\""),
                    "derived entity payloads must name their type: " + flight);
            assertFalse(planItem.contains("@odata.type"),
                    "root types stay annotation-free: " + planItem);
        }
    }

    @Test
    void derivedComplexTypeCarriesTypeAnnotationAndBaseDoesNot(@TempDir Path tempDir) throws Exception {
        try (URLClassLoader loader = generateAndCompileTripPin(tempDir)) {
            ObjectMapper mapper = JacksonSerializer.newODataMapper();
            String eventLocation = mapper.writeValueAsString(instance(loader, PKG + ".complex.EventLocation"));
            String location = mapper.writeValueAsString(instance(loader, PKG + ".complex.Location"));

            assertTrue(eventLocation.contains("\"@odata.type\":\"#" + NS + ".EventLocation\""),
                    "derived complex payloads must name their type: " + eventLocation);
            assertFalse(location.contains("@odata.type"), location);
        }
    }

    @Test
    void incomingTypeAnnotationIsNotCapturedAsADynamicProperty(@TempDir Path tempDir) throws Exception {
        try (URLClassLoader loader = generateAndCompileTripPin(tempDir)) {
            Class<?> flightClass = Class.forName(PKG + ".entity.Flight", true, loader);
            Object flight = JacksonSerializer.newODataMapper().readValue(
                    "{\"@odata.type\":\"#" + NS + ".Flight\",\"PlanItemId\":7,\"FlightNumber\":\"AA26\"}",
                    flightClass);

            assertEquals("AA26", flightClass.getMethod("getFlightNumber").invoke(flight));
            Object unmapped = flightClass.getMethod("getUnmappedFields").invoke(flight);
            assertFalse(((Map<?, ?>) unmapped).containsKey("@odata.type"),
                    "control information must not leak into unmappedFields: " + unmapped);
        }
    }
}
