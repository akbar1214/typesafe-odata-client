package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-pipeline wiring: {@code Generator.generate} must emit one file per container
 * function/action import under {@code <base>.operation}, in addition to the accessors
 * embedded in the generated container class.
 */
class GeneratorOperationFilesTest {

    @Test
    void generateWritesOperationRequestClassesForEveryImport(@TempDir Path out) throws Exception {
        CsdlModel model = load("/trippin-metadata.xml");
        new Generator(out,
                Map.of("Microsoft.OData.SampleService.Models.TripPin", "com.example.trippin"),
                "com.example.trippin").generate(model);

        assertTrue(Files.exists(out.resolve(Path.of("com", "example", "trippin",
                        "operation", "GetNearestAirportFunctionRequest.java"))),
                "function import request class must be generated");
        assertTrue(Files.exists(out.resolve(Path.of("com", "example", "trippin",
                        "operation", "ResetDataSourceActionRequest.java"))),
                "action import request class must be generated");
        String containerCode = Files.readString(out.resolve(Path.of("com", "example",
                "trippin", "container", "DefaultContainer.java")));
        assertTrue(containerCode.contains("getNearestAirport(double lat, double lon)"));
        assertTrue(containerCode.contains("resetDataSource()"));
    }

    private static CsdlModel load(String resource) {
        try (InputStream is = GeneratorOperationFilesTest.class.getResourceAsStream(resource)) {
            return new StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
