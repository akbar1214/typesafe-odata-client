package io.github.akbarhusain.odata.core;

import io.github.akbarhusain.odata.core.generator.ContainerGenerator;
import io.github.akbarhusain.odata.core.generator.EntityGenerator;
import io.github.akbarhusain.odata.core.generator.EnumGenerator;
import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-4 core review fixes (code-review-round4-core.md): H1 constant sanitization +
 * enum wire-name round-trip, H2 Filterable nav allocation, M1 container collisions,
 * M2 resolved enum literals, M3 duplicate Key rejection, M4 builder nav change
 * tracking, M5 PropertyRef@Name required. All verified by compiling generated output.
 */
class Round4CoreFixesTest {

    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<edmx:Edmx Version=\"4.0\" xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\">"
            + "<edmx:DataServices>";
    private static final String FOOTER = "</edmx:DataServices></edmx:Edmx>";
    private static final String NS = "<Schema Namespace=\"Ns\" xmlns=\"http://docs.oasis-open.org/odata/ns/edm\">";

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private CsdlModel.SchemaModel schema(String body) throws Exception {
        return parse(HEADER + NS + body + "</Schema>" + FOOTER).schemas().get(0);
    }

    // ------------------------------------------------------------------
    // H1: constants and enum members sanitize non-identifier characters
    // ------------------------------------------------------------------

    @Test
    void h1DashNamesProduceValidConstantsAndCompile(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.p").generate(parse(HEADER + NS + """
            <EntityType Name="T">
              <Key><PropertyRef Name="First-Name"/></Key>
              <Property Name="First-Name" Type="Edm.String" Nullable="false"/>
              <Property Name="Age.Years" Type="Edm.Int32"/>
            </EntityType>
            <EnumType Name="E"><Member Name="a-b"/><Member Name="c d"/></EnumType>
            <EntityContainer Name="C"><EntitySet Name="Ts" EntityType="Ns.T"/></EntityContainer>
            </Schema>""" + FOOTER));

        String entity = Files.readString(out.resolve("com/p/entity/T.java"));
        assertTrue(entity.contains("StringProperty<T> FIRST_NAME"),
                "dash maps to underscore. Got:\n" + entity);
        assertTrue(entity.contains("NumberProperty<T, Integer> AGE_YEARS"),
                "dot maps to underscore. Got:\n" + entity);

        String enumCode = Files.readString(out.resolve("com/p/enums/E.java"));
        assertTrue(enumCode.contains("A_B(0L)"), "sanitized enum constant. Got:\n" + enumCode);

        assertTrue(compileAll(out), "output must compile (previously FIRST-NAME / A-B(0L))");
    }

    @Test
    void h1SanitizedEnumMembersRoundTripWireNames(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.p").generate(parse(HEADER + NS + """
            <EnumType Name="E"><Member Name="a-b" Value="1"/><Member Name="ok" Value="4"/></EnumType>
            </Schema>""" + FOOTER));

        String enumCode = Files.readString(out.resolve("com/p/enums/E.java"));
        assertTrue(enumCode.contains("A_B(1L)"), "sanitized constant. Got:\n" + enumCode);

        assertTrue(compileAll(out), "enum must compile");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{out.toUri().toURL()},
                Round4CoreFixesTest.class.getClassLoader())) {
            Class<?> e = Class.forName("com.p.enums.E", true, loader);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            Object fromWireName = mapper.readValue("\"a-b\"", e);
            assertEquals("A_B", ((Enum<?>) fromWireName).name(),
                    "the CSDL member name 'a-b' must deserialize to the sanitized constant");
            Object fromPlain = mapper.readValue("\"ok\"", e);
            assertEquals("ok", ((Enum<?>) fromPlain).name(),
                    "un-sanitized members keep mapping by name");
            Object fromNumber = mapper.readValue("1", e);
            assertEquals("A_B", ((Enum<?>) fromNumber).name(),
                    "numeric payloads still map by CSDL value");
        }
    }

    // ------------------------------------------------------------------
    // H2: Filterable nav fields use the allocated constant names
    // ------------------------------------------------------------------

    @Test
    void h2FilterableNavCollisionDeduped(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.p").generate(parse(HEADER + NS + """
            <EntityType Name="Other"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
            <EntityType Name="T">
              <Key><PropertyRef Name="Id"/></Key>
              <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
              <Property Name="BUDGET" Type="Edm.Double"/>
              <NavigationProperty Name="budget" Type="Collection(Ns.Other)"/>
            </EntityType>
            </Schema>""" + FOOTER));

        String entity = Files.readString(out.resolve("com/p/entity/T.java"));
        assertTrue(entity.contains("NumberProperty<T, Double> BUDGET =")
                        && entity.contains("CollectionProperty<T, Other, Other.Filterable> BUDGET_2 ="),
                "Filterable nav fields must use the allocated (deduped) names. Got:\n" + entity);
        assertTrue(compileAll(out), "output must compile (previously two BUDGET fields in Filterable)");
    }

    // ------------------------------------------------------------------
    // M1: container member collisions fail loudly
    // ------------------------------------------------------------------

    @Test
    void m1ContainerMemberCollisionFails() throws Exception {
        CsdlModel model = parse(HEADER + NS + """
            <EntityType Name="T"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
            <EntityContainer Name="C">
              <EntitySet Name="People" EntityType="Ns.T"/>
              <Singleton Name="People" Type="Ns.T"/>
            </EntityContainer>
            </Schema>""" + FOOTER);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ContainerGenerator("com.p").generate(model.schemas().get(0).containers().get(0),
                        model.schemas().get(0)));
        assertTrue(ex.getMessage().contains("People"), "error names the colliding members: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // M2: enum filter literals use the RESOLVED enum type
    // ------------------------------------------------------------------

    @Test
    void m2TypedefOfEnumLiteralUsesResolvedEnum() throws Exception {
        var schema = schema("""
            <EnumType Name="Color"><Member Name="Red" Value="1"/></EnumType>
            <TypeDefinition Name="Tint" UnderlyingType="Ns.Color"/>
            <EntityType Name="T">
              <Key><PropertyRef Name="Id"/></Key>
              <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
              <Property Name="Shade" Type="Ns.Tint"/>
            </EntityType>""");
        String code = new EntityGenerator("com.p").generate(
                schema.entityTypes().stream().filter(e -> e.name().equals("T")).findFirst().orElseThrow(), schema);
        assertTrue(code.contains("\"Ns.Color\""),
                "the EnumProperty literal must use the resolved enum type, not the typedef. Got:\n" + code);
        assertFalse(code.contains("\"Ns.Tint\""), "typedef name must not appear as the literal type");
    }

    // ------------------------------------------------------------------
    // M3: duplicate <Key> elements rejected at parse
    // ------------------------------------------------------------------

    @Test
    void m3MultipleKeyElementsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> schema("""
                    <EntityType Name="T">
                      <Key><PropertyRef Name="A"/></Key>
                      <Key><PropertyRef Name="B"/></Key>
                      <Property Name="A" Type="Edm.String" Nullable="false"/>
                      <Property Name="B" Type="Edm.String" Nullable="false"/>
                    </EntityType>"""));
        assertTrue(ex.getMessage().contains("T") && ex.getMessage().contains("Key"),
                "error names the entity and the problem: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // M4: builder nav setters track changes
    // ------------------------------------------------------------------

    @Test
    void m4BuilderNavSettersTrackChanges() throws Exception {
        var schema = schema("""
            <EntityType Name="Other"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
            <EntityType Name="T">
              <Key><PropertyRef Name="Id"/></Key>
              <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
              <Property Name="Name" Type="Edm.String"/>
              <NavigationProperty Name="Other" Type="Ns.Other"/>
            </EntityType>""");
        String code = new EntityGenerator("com.p").generate(
                schema.entityTypes().stream().filter(e -> e.name().equals("T")).findFirst().orElseThrow(), schema);
        int builderStart = code.indexOf("class Builder");
        String builder = code.substring(builderStart);
        assertTrue(builder.contains("changed.add(\"Other\")"),
                "builder nav setters must record changes for partial PATCH. Builder:\n" + builder);
    }

    // ------------------------------------------------------------------
    // M5: PropertyRef@Name required with context (not NPE)
    // ------------------------------------------------------------------

    @Test
    void m5PropertyRefNameRequired() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> schema("""
                    <EntityType Name="T">
                      <Key><PropertyRef/></Key>
                      <Property Name="A" Type="Edm.String" Nullable="false"/>
                    </EntityType>"""));
        assertTrue(ex.getMessage().contains("Name"),
                "a descriptive parse error, not a bare NPE: " + ex.getMessage());
    }

    /** Compiles every .java under dir against .m2. */
    private static boolean compileAll(Path dir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<File> classpath = new ArrayList<>();
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        try (Stream<Path> jars = Files.walk(m2)) {
            jars.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("-sources") && !p.toString().contains("-javadoc"))
                    .map(Path::toFile).forEach(classpath::add);
        }
        List<File> units;
        try (Stream<Path> files = Files.walk(dir)) {
            units = files.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList();
        }
        StringBuilder errors = new StringBuilder();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        fm.setLocation(StandardLocation.CLASS_PATH, classpath);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(dir.toFile()));
        compiler.getTask(null, fm, d -> {
            if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                errors.append(d.getMessage(null)).append('\n');
            }
        }, null, null, fm.getJavaFileObjectsFromFiles(units)).call();
        if (errors.length() > 0) {
            fail("Generated code failed to compile:" + System.lineSeparator() + errors);
        }
        return !units.isEmpty();
    }
}
