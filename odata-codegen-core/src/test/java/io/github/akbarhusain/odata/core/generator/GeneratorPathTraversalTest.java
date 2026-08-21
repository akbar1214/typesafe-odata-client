package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H7: Generator path traversal via hostile packageName.
 * Generator.writeCode does dir = outputDir.resolve(packageName.replace('.','/'))
 * without validation, so basePackage="../../evil" writes outside target.
 * Expected after fix: Generator should reject hostile package names with IllegalArgumentException.
 * Currently: no validation -> writes outside, test fails.
 */
class GeneratorPathTraversalTest {

    private CsdlModel simpleModel() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityContainer Name="Container">
                    <EntitySet Name="Foos" EntityType="NS.Test.Foo"/>
                  </EntityContainer>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void hostileBasePackageWithDotDotIsRejected(@TempDir Path tmp) throws Exception {
        CsdlModel model = simpleModel();
        Generator gen = new Generator(tmp, Map.of(), "../../evil");
        // H7: should throw IllegalArgumentException for traversal, currently does NOT
        assertThrows(IllegalArgumentException.class, () -> gen.generate(model),
                "H7: basePackage '../../evil' must be rejected (path traversal)");
    }

    @Test
    void hostileSchemaPackageWithSlashIsRejected(@TempDir Path tmp) throws Exception {
        CsdlModel model = simpleModel();
        Map<String,String> pkgs = Map.of("NS.Test", "com/test/../evil");
        Generator gen = new Generator(tmp, pkgs, "com.test");
        assertThrows(IllegalArgumentException.class, () -> gen.generate(model),
                "H7: schemaPackages value with '/' and '..' must be rejected");
    }

    @Test
    void absolutePackageIsRejected(@TempDir Path tmp) throws Exception {
        CsdlModel model = simpleModel();
        Generator gen = new Generator(tmp, Map.of(), "/tmp/evil");
        assertThrows(IllegalArgumentException.class, () -> gen.generate(model),
                "H7: absolute basePackage '/tmp/evil' must be rejected");
    }
}
