package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `enumConstantName` keeps a CSDL member verbatim whenever it is already a legal,
 * non-keyword Java identifier — that preserves the wire name, which is the point. But
 * the generated enum also declares `private final long value`, `private final String
 * wireName` and a static `BY_NAME` map, and those names are legal CSDL NCNames too, so a
 * member called `value` collided with its own field and the output did not compile.
 *
 * <p>Verified with the javac CompilationHarness rather than a string assertion: the
 * failure mode is a compile error in the OUTPUT, which `contains()` cannot detect.
 */
class EnumMemberCollisionTest {

    @Test
    void enumMembersNamedAfterGeneratedFieldsStillCompile(@TempDir Path tmp) throws Exception {
        CsdlModel model = parse("""
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="Demo" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EnumType Name="Color" UnderlyingType="Edm.Int32">
                        <Member Name="Red" Value="0"/>
                        <Member Name="value" Value="1"/>
                        <Member Name="wireName" Value="2"/>
                        <Member Name="BY_NAME" Value="3"/>
                      </EnumType>
                      <EntityType Name="Person">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <Property Name="Favourite" Type="Demo.Color"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="People" EntityType="Demo.Person"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);

        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(model);

        String color = Files.readString(out.resolve("com/example/enums/Color.java"));
        assertFalse(color.contains("private final long value;\n\n    value("),
                "a member must not be emitted with the field's own name:\n" + color);

        // Each colliding member must have been renamed, and the BY_NAME map must carry
        // the CSDL wire name so JSON still round-trips to the right member.
        assertTrue(color.contains("\"value\""), "the wire name must be recorded:\n" + color);
        assertTrue(color.contains("\"wireName\""), "the wire name must be recorded:\n" + color);
        assertTrue(color.contains("\"BY_NAME\""), "the wire name must be recorded:\n" + color);

        assertNull(CompilationHarness.compileAll(out), "generated client must compile");
    }

    /**
     * A member that does NOT collide must keep its name verbatim: the constant is the
     * wire name in the OData JSON format, so renaming it would be a breaking change.
     */
    @Test
    void nonCollidingMembersKeepTheirCSDLNamesVerbatim(@TempDir Path tmp) throws Exception {
        CsdlModel model = parse("""
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="Demo" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EnumType Name="Color" UnderlyingType="Edm.Int32">
                        <Member Name="Red" Value="0"/>
                        <Member Name="DarkBlue" Value="1"/>
                      </EnumType>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);

        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(model);

        String color = Files.readString(out.resolve("com/example/enums/Color.java"));
        assertTrue(color.contains("Red(0L,"), color);
        assertTrue(color.contains("DarkBlue(1L,"), color);
        assertFalse(color.contains("BY_NAME"),
                "a fully verbatim enum needs no wire-name map:\n" + color);
    }

    private static CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
