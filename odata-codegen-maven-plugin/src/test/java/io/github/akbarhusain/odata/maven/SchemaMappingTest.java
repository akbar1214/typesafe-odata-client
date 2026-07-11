package io.github.akbarhusain.odata.maven;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SchemaMapping configuration and its integration with the Generator.
 */
class SchemaMappingTest {

    static CsdlModel crossNsModel;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = SchemaMappingTest.class
                .getResourceAsStream("/cross-namespace-metadata.xml")) {
            crossNsModel = parser.parse(is);
        }
    }

    @Test
    void schemaMappingConvertsToMap() {
        List<SchemaMapping> mappings = List.of(
                new SchemaMapping("CrossNs.Main", "com.example.main"),
                new SchemaMapping("CrossNs.Shared", "com.example.shared")
        );

        Map<String, String> packages = new HashMap<>();
        for (SchemaMapping mapping : mappings) {
            packages.put(mapping.getNamespace(), mapping.getPackageName());
        }

        assertEquals(2, packages.size());
        assertEquals("com.example.main", packages.get("CrossNs.Main"));
        assertEquals("com.example.shared", packages.get("CrossNs.Shared"));
    }

    @Test
    void generatorProducesCorrectOutputFromSchemaMappings(@TempDir Path tempDir) throws Exception {
        List<SchemaMapping> mappings = List.of(
                new SchemaMapping("CrossNs.Main", "com.example.crossns.main"),
                new SchemaMapping("CrossNs.Shared", "com.example.crossns.shared")
        );

        Map<String, String> packages = new HashMap<>();
        for (SchemaMapping mapping : mappings) {
            packages.put(mapping.getNamespace(), mapping.getPackageName());
        }

        Generator generator = new Generator(tempDir, packages);
        generator.generate(crossNsModel);

        assertTrue(Files.exists(tempDir.resolve("com/example/crossns/main/entity/Person.java")),
                "CrossNs.Main entity should be in com.example.crossns.main.entity");
        assertTrue(Files.exists(tempDir.resolve("com/example/crossns/shared/complex/Address.java")),
                "CrossNs.Shared complex type should be in com.example.crossns.shared.complex");
        assertTrue(Files.exists(tempDir.resolve("com/example/crossns/main/container/Container.java")),
                "Container should be generated in CrossNs.Main package");
    }

    @Test
    void emptyMappingsListUsesDefaultPackageNames(@TempDir Path tempDir) throws Exception {
        List<SchemaMapping> emptyMappings = new ArrayList<>();

        Map<String, String> packages = new HashMap<>();
        for (SchemaMapping mapping : emptyMappings) {
            packages.put(mapping.getNamespace(), mapping.getPackageName());
        }

        Generator generator = new Generator(tempDir, packages);
        generator.generate(crossNsModel);

        // With no mappings and no basePackage, namespaces use default: toLowerCase + dots→underscores
        // "CrossNs.Main" → "crossns_main"
        assertTrue(Files.exists(tempDir.resolve("crossns_main/entity/Person.java")),
                "CrossNs.Main entity should use default package name (crossns_main.entity)");
        assertTrue(Files.exists(tempDir.resolve("crossns_shared/complex/Address.java")),
                "CrossNs.Shared complex type should use default package name (crossns_shared.complex)");
    }

    @Test
    void schemaMappingGettersAndSetters() {
        SchemaMapping mapping = new SchemaMapping();
        mapping.setNamespace("My.Namespace");
        mapping.setPackageName("com.example.my");

        assertEquals("My.Namespace", mapping.getNamespace());
        assertEquals("com.example.my", mapping.getPackageName());
    }

    @Test
    void schemaMappingConstructorSetsFields() {
        SchemaMapping mapping = new SchemaMapping("Test.Ns", "com.test");

        assertEquals("Test.Ns", mapping.getNamespace());
        assertEquals("com.test", mapping.getPackageName());
    }
}
