package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2: when several schemas share one output package (the normal case when the
 * Maven plugin passes a single basePackage), one aggregate ServiceSchemaInfo
 * must be generated containing all schemas' types — not one per-schema file
 * overwriting the previous.
 */
class GeneratorSchemaInfoAggregationTest {

    private static final String METADATA = """
        <?xml version="1.0" encoding="utf-8"?>
        <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
          <edmx:DataServices>
            <Schema Namespace="Ns.One" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EntityType Name="Thing">
                <Key><PropertyRef Name="Id"/></Key>
                <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
              </EntityType>
              <ComplexType Name="ThingInfo">
                <Property Name="Label" Type="Edm.String"/>
              </ComplexType>
            </Schema>
            <Schema Namespace="Ns.Two" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EntityType Name="Other">
                <Key><PropertyRef Name="Id"/></Key>
                <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
              </EntityType>
              <EnumType Name="OtherKind">
                <Member Name="Alpha"/>
              </EnumType>
            </Schema>
          </edmx:DataServices>
        </edmx:Edmx>
        """;

    private CsdlModel parse() throws Exception {
        return new io.github.akbarhusain.odata.core.parser.StaxCsdlParser().parse(
                new ByteArrayInputStream(METADATA.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sharedBasePackageProducesSingleAggregateSchemaInfo(@TempDir Path tempDir) throws Exception {
        Generator generator = new Generator(tempDir, Map.of(), "com.example.shared");
        generator.generate(parse());

        Path schemaDir = tempDir.resolve("com/example/shared/schema");
        assertTrue(Files.exists(schemaDir.resolve("ServiceSchemaInfo.java")),
                "ServiceSchemaInfo.java should exist");

        try (Stream<Path> files = Files.walk(tempDir, 6)) {
            long count = files.filter(p -> p.getFileName() != null
                    && p.getFileName().toString().equals("ServiceSchemaInfo.java")).count();
            assertEquals(1, count, "exactly one ServiceSchemaInfo for the shared package");
        }

        String code = Files.readString(schemaDir.resolve("ServiceSchemaInfo.java"));
        assertTrue(code.contains("\"Ns.One.Thing\""), "aggregate must register Ns.One.Thing");
        assertTrue(code.contains("\"Ns.One.ThingInfo\""), "aggregate must register Ns.One.ThingInfo");
        assertTrue(code.contains("\"Ns.Two.Other\""), "aggregate must register Ns.Two.Other");
        assertTrue(code.contains("\"Ns.Two.OtherKind\""), "aggregate must register Ns.Two.OtherKind");
    }

    @Test
    void distinctBasePackagesProduceOneSchemaInfoPerPackage(@TempDir Path tempDir) throws Exception {
        Generator generator = new Generator(tempDir, Map.of(
                "Ns.One", "com.example.one",
                "Ns.Two", "com.example.two"));
        generator.generate(parse());

        String one = Files.readString(tempDir.resolve("com/example/one/schema/ServiceSchemaInfo.java"));
        assertTrue(one.contains("\"Ns.One.Thing\""));
        assertFalse(one.contains("\"Ns.Two."), "Ns.One's registry must not contain Ns.Two types");

        String two = Files.readString(tempDir.resolve("com/example/two/schema/ServiceSchemaInfo.java"));
        assertTrue(two.contains("\"Ns.Two.Other\""));
        assertFalse(two.contains("\"Ns.One."), "Ns.Two's registry must not contain Ns.One types");
    }
}
