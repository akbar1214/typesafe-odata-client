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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type reference must resolve to the package of the schema that DECLARES the type, not
 * the package of the schema currently being generated.
 *
 * <p>{@code basePackageForType} short-circuited to the generating schema's package whenever
 * the type's namespace equalled the declaring schema's namespace — which is true by
 * definition, because callers pass the declaring schema. On split-merge metadata, where
 * two schemas map to two different packages via {@code schemaPackages}, every cross-package
 * reference therefore resolved to the wrong package: inherited members of a cross-package
 * base, and operations owned by another schema, referenced classes that are never
 * generated (uncompilable), or — when both schemas declared the same simple name —
 * compiled while silently referring to the wrong type.
 */
class CrossSchemaPackageResolutionTest {

    @Test
    void inheritedMemberOfCrossPackageBaseResolvesToBasePackage(@TempDir Path tmp) throws Exception {
        String xml = """
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="Base" Alias="b" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <ComplexType Name="Home"><Property Name="City" Type="Edm.String"/></ComplexType>
                      <EntityType Name="Employee">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.String" Nullable="false"/>
                        <Property Name="Home" Type="b.Home"/>
                        <NavigationProperty Name="Reports" Type="Collection(b.Employee)"/>
                      </EntityType>
                      <EntityContainer Name="BContainer">
                        <EntitySet Name="Employees" EntityType="b.Employee"/>
                      </EntityContainer>
                    </Schema>
                    <Schema Namespace="Derived" Alias="d" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="Boss" BaseType="b.Employee">
                        <Property Name="Budget" Type="Edm.Double"/>
                      </EntityType>
                      <EntityContainer Name="DContainer">
                        <EntitySet Name="Bosses" EntityType="d.Boss"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        CsdlModel model = parse(xml);
        Path out = tmp.resolve("out");
        new Generator(out, Map.of("Base", "com.basepkg", "Derived", "com.derivedpkg"))
                .withGenerateWithMethods(true)
                .generate(model);

        String boss = Files.readString(out.resolve("com/derivedpkg/entity/Boss.java"));
        // The inherited complex type and nav target are DECLARED in Base, so they must be
        // referenced through com.basepkg. Assert the full token: a bare contains() on
        // "com.basepkg.complex.Home" is a substring of a longer wrong reference, and
        // contains("com.derivedpkg.entity.Boss") would pass vacuously against Boss.java.
        assertTrue(boss.contains("com.basepkg.complex.Home"),
                "inherited complex type must resolve to the base schema's package:\n" + boss);
        assertTrue(boss.contains("com.basepkg.entity.Employee"),
                "inherited nav target must resolve to the base schema's package:\n" + boss);
        assertFalse(boss.contains("com.derivedpkg.complex.Home"),
                "no class is generated in com.derivedpkg.complex:\n" + boss);
        assertFalse(boss.contains("com.derivedpkg.entity.Employee"),
                "no class is generated in com.derivedpkg.entity.Employee:\n" + boss);

        assertNull(CompilationHarness.compileAll(out), "generated client must compile");
    }

    /**
     * An operation's request class is written into the package of the schema that DECLARES
     * the operation (decision 94 keys operation packages to the owning schema's namespace),
     * while the parameters it renders are types of the same schema. So both must resolve to
     * the declaring schema's package — never the package of whichever schema happens to be
     * generating. Pre-fix, the second lookup short-circuited on the *declaring* schema's
     * namespace, which always matched, so a cross-package reference resolved to the
     * generating package.
     *
     * <p>The request class lands beside its parameter types; the assertion below pins both
     * halves — the file's own location and the imports it emits.
     */
    @Test
    void operationParameterTypesResolveThroughTheDeclaringSchemasPackage(@TempDir Path tmp) throws Exception {
        String xml = """
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="Base" Alias="b" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EnumType Name="Color"><Member Name="Red" Value="0"/></EnumType>
                      <EntityType Name="Employee">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.String" Nullable="false"/>
                      </EntityType>
                      <Function Name="Find">
                        <Parameter Name="employee" Type="b.Employee"/>
                        <Parameter Name="color" Type="b.Color"/>
                        <ReturnType Type="Edm.String"/>
                      </Function>
                      <EntityContainer Name="BContainer">
                        <EntitySet Name="Employees" EntityType="b.Employee"/>
                      </EntityContainer>
                    </Schema>
                    <Schema Namespace="Api" Alias="a" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityContainer Name="ApiContainer" Extends="b.BContainer">
                        <FunctionImport Name="Find" Function="b.Find" IncludeInServiceDocument="true"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        CsdlModel model = parse(xml);
        Path out = tmp.resolve("out");
        new Generator(out, Map.of("Base", "com.basepkg", "Api", "com.apipkg"))
                .withGenerateWithMethods(true)
                .generate(model);

        // The request class is generated into the OWNING schema's package, so its parameter
        // types must resolve there too. An unbound function's parameters are not a request
        // method's signature, so pin the URL-literal type rendering instead.
        Path functionFile = out.resolve("com/basepkg/operation/FindFunctionRequest.java");
        assertTrue(Files.exists(functionFile),
                "operation request belongs to the declaring schema's package");
        String fn = Files.readString(functionFile);
        assertTrue(fn.contains("com.basepkg.entity.Employee"),
                "entity parameter resolves through the declaring schema's package:\n" + fn);
        assertTrue(fn.contains("com.basepkg.enums.Color"),
                "enum parameter resolves through the declaring schema's package:\n" + fn);
        assertFalse(fn.contains("com.apipkg.entity.Employee"),
                "com.apipkg.entity.Employee is never generated:\n" + fn);
        assertFalse(fn.contains("com.apipkg.enums.Color"),
                "com.apipkg.enums.Color is never generated:\n" + fn);

        // The generated ApiContainer is the cross-package consumer: its accessor for the
        // import must reference the request class in the OWNING schema's package.
        String container = Files.readString(out.resolve("com/apipkg/container/ApiContainer.java"));
        assertTrue(container.contains("com.basepkg.operation.FindFunctionRequest"),
                "the accessor references the owning schema's request class:\n" + container);
        assertFalse(container.contains("com.apipkg.operation.FindFunctionRequest"),
                "no request class is generated in com.apipkg.operation:\n" + container);

        assertNull(CompilationHarness.compileAll(out), "generated client must compile");
    }

    private static CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
