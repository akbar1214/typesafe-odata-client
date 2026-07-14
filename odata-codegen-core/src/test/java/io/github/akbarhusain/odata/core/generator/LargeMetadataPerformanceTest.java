package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeMetadataPerformanceTest {

    @Test
    void generate2000EntitiesPerformance(@TempDir Path tempDir) throws Exception {
        int entityCount = 2000;
        System.out.println("\n=== LargeMetadata Performance Test: " + entityCount + " entities ===");

        // Generate metadata XML
        long t0 = System.currentTimeMillis();
        String xml = LargeMetadataHelper.generateMetadata(entityCount);
        long t1 = System.currentTimeMillis();
        System.out.println("XML generation: " + (t1 - t0) + " ms (" + xml.length() + " bytes)");

        // Parse
        StaxCsdlParser parser = new StaxCsdlParser();
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        CsdlModel model;
        try (InputStream is = new ByteArrayInputStream(xmlBytes)) {
            long t2 = System.currentTimeMillis();
            model = parser.parse(is);
            long t3 = System.currentTimeMillis();
            System.out.println("Parse: " + (t3 - t2) + " ms (" + model.schemas().get(0).entityTypes().size() + " entities)");
        }

        // Generate
        String basePackage = "com.largetest.generated";
        Map<String, String> schemaPackages = Map.of("LargeTest", basePackage);
        Generator generator = new Generator(tempDir, schemaPackages);

        long t4 = System.currentTimeMillis();
        generator.generate(model);
        long t5 = System.currentTimeMillis();
        long genTime = t5 - t4;
        System.out.println("Generation: " + genTime + " ms");

        // Count generated files
        long fileCount;
        try (Stream<Path> files = Files.walk(tempDir).filter(p -> p.toString().endsWith(".java"))) {
            fileCount = files.count();
        }
        System.out.println("Generated files: " + fileCount);

        // Verify files exist
        Path entityDir = tempDir.resolve(basePackage.replace('.', '/') + "/entity");
        assertTrue(Files.exists(entityDir.resolve("Entity0.java")), "Entity0.java should exist");
        assertTrue(Files.exists(entityDir.resolve("Entity" + (entityCount - 1) + ".java")),
                "Entity" + (entityCount - 1) + ".java should exist");

        // Verify generated file has the right structure
        String entityCode = Files.readString(entityDir.resolve("Entity0.java"));
        assertTrue(entityCode.contains("public final class Entity0"), "Should have final class");
        assertTrue(entityCode.contains("implements ODataEntityType"), "Should implement ODataEntityType");
        assertTrue(entityCode.contains("public static final StringProperty<Entity0> NAME"), "Should have NAME property");

        System.out.println("Total time: " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println("=== End LargeMetadata Performance Test ===\n");

        // Assert reasonable performance: 2000 entities should generate within 60 seconds
        assertTrue(genTime < 60_000, "Generation took too long: " + genTime + " ms (limit: 60000 ms)");
    }
}
