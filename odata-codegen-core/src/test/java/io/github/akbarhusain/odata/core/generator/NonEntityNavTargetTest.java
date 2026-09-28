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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSDL §12.2: a NavigationProperty targets an entity type or a complex type. The request
 * layer tested {@code == COMPLEX} where every sibling consumer bails on
 * {@code != ENTITY} (EntityGenerator's nav constant and Selector field,
 * AbstractTypeGenerator's nav-target FQN), so an ENUM or Edm-primitive target fell
 * through the one guard that let it through and emitted
 * {@code import ...ColorEntityRequest} or {@code List<Int32>} — types that are never
 * generated, so the client did not compile.
 *
 * <p>The failure is a compile error in the OUTPUT, so these assertions use the javac
 * CompilationHarness; a {@code contains()} check would pass while the code was broken.
 */
class NonEntityNavTargetTest {

    @Test
    void navigationPropertyTargetingAnEnumFailsLoudly(@TempDir Path tmp) throws Exception {
        CsdlModel model = parse("""
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="Demo" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EnumType Name="Color"><Member Name="Red" Value="0"/></EnumType>
                      <EntityType Name="A">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <NavigationProperty Name="Color" Type="Demo.Color"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="As" EntityType="Demo.A"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new Generator(tmp, Map.of(), "com.example").generate(model));
        assertTrue(failure.getMessage().contains("Navigation property"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("Demo.Color"),
                "the message must name the offending type: " + failure.getMessage());
    }

    @Test
    void navigationPropertyTargetingAPrimitiveFailsLoudly(@TempDir Path tmp) throws Exception {
        CsdlModel model = parse("""
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="Demo" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="A">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <NavigationProperty Name="Scores" Type="Collection(Edm.Int32)"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="As" EntityType="Demo.A"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);

        assertThrows(IllegalStateException.class,
                () -> new Generator(tmp, Map.of(), "com.example").generate(model));
    }

    /**
     * The valid shapes must be unaffected: an entity nav generates a request method, and a
     * complex nav is skipped by the request layer (complex types are inline data with no
     * request class) while still being emitted by the entity generator as a field.
     */
    @Test
    void entityAndComplexNavigationTargetsAreUnaffected(@TempDir Path tmp) throws Exception {
        CsdlModel model = parse("""
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="Demo" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <ComplexType Name="Address"><Property Name="City" Type="Edm.String"/></ComplexType>
                      <EntityType Name="B">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                      </EntityType>
                      <EntityType Name="A">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <NavigationProperty Name="Bs" Type="Collection(Demo.B)"/>
                        <NavigationProperty Name="Home" Type="Demo.Address"/>
                      </EntityType>
                      <EntityContainer Name="Container">
                        <EntitySet Name="As" EntityType="Demo.A"/>
                        <EntitySet Name="Bs" EntityType="Demo.B"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """);

        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(model);

        String request = Files.readString(out.resolve("com/example/entity/request/AEntityRequest.java"));
        assertTrue(request.contains("BCollectionRequest"),
                "an entity nav must still get a collection request:\n" + request);
        assertTrue(request.contains("public BCollectionRequest bs()"), request);
        assertTrue(!request.contains("AddressCollectionRequest"),
                "a complex nav must not get a request class:\n" + request);

        // The entity generator still materializes the complex nav as a typed field.
        String entity = Files.readString(out.resolve("com/example/entity/A.java"));
        assertTrue(entity.contains("Address"), "the complex nav field must still be emitted:\n" + entity);

        assertNull(CompilationHarness.compileAll(out), "generated client must compile");
    }

    private static CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
