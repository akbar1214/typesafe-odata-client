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

class LargeMetadataMultiSchemaPerformanceTest {

    @Test
    void generate10Schemas1000EntitiesEachWithCrossSchemaInheritance(@TempDir Path tempDir) throws Exception {
        int entitiesPerSchema = 1000;
        int schemaCount = 10; // S0 (standalone) + S1..S9 (each extends previous schema)
        int totalEntities = schemaCount * entitiesPerSchema; // 10000

        System.out.println("\n=== Multi-Schema Performance Test: " + schemaCount
                + " schemas, " + totalEntities + " entities ===");

        // Generate metadata XML
        long t0 = System.currentTimeMillis();
        String xml = LargeMetadataHelper.generateMultiSchemaMetadata(entitiesPerSchema, schemaCount);
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
            int entityCount = model.schemas().stream().mapToInt(s -> s.entityTypes().size()).sum();
            int parsedSchemas = model.schemas().size();
            System.out.println("Parse: " + (t3 - t2) + " ms (" + parsedSchemas + " schemas, " + entityCount + " entities)");
        }

        // Generate — use a common base package, all entity names are unique across schemas
        String basePackage = "com.perftest.generated";
        Generator generator = new Generator(tempDir, Map.of(), basePackage);

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

        // Verify cross-schema inheritance chain:
        // S1.E1_0 extends S0.E0_0, S2.E2_0 extends S1.E1_0, etc.
        Path entityDir = tempDir.resolve("com/perftest/generated/entity");

        // S1 entity extends S0 entity — one level cross-schema
        String e1_0 = Files.readString(entityDir.resolve("E1_0.java"));
        assertTrue(e1_0.contains("extends E0_0"), "E1_0 should extend E0_0 from previous schema");
        assertTrue(e1_0.contains("import " + basePackage + ".entity.E0_0"),
                "E1_0 should import E0_0 from the base schema package");

        // S9 entity extends S8 entity — multiple level cross-schema chain
        String e9_0 = Files.readString(entityDir.resolve("E9_0.java"));
        assertTrue(e9_0.contains("extends E8_0"), "E9_0 should extend E8_0 from previous schema");
        assertTrue(e9_0.contains("import " + basePackage + ".entity.E8_0"),
                "E9_0 should import E8_0 from the base schema package");

        // S0 entity is standalone (no extends clause)
        String e0_0 = Files.readString(entityDir.resolve("E0_0.java"));
        assertTrue(e0_0.contains("public class E0_0"), "E0_0 should be a standalone class");
        assertTrue(e0_0.contains("implements ODataEntityType"), "E0_0 should implement ODataEntityType");

        // S9 entity must exist
        assertTrue(entityDir.resolve("E9_999.java").toFile().exists(), "E9_999.java should exist");

        System.out.println("Total time: " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println("=== End Multi-Schema Performance Test ===\n");

        // Assert reasonable performance: 10 schemas × 1000 entities with cross-schema chains
        // should generate within 5 seconds (currently ~2s on Mac)
        assertTrue(genTime < 5_000,
                "Generation took too long: " + genTime + " ms (limit: 5000 ms)");
    }
}
