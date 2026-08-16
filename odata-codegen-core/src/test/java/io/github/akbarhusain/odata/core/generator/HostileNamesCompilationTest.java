package io.github.akbarhusain.odata.core.generator;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hostile-but-legal CSDL names (XML NCNames allow '-', '.', spaces) must generate code
 * that COMPILES — constants, enum members, Filterable fields, and key-accessor methods
 * all derive Java identifiers from raw metadata names. Every test here compiles the
 * generated output with javac; string assertions alone have historically passed while
 * a different unsanitized path still broke compilation.
 */
class HostileNamesCompilationTest {

    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<edmx:Edmx Version=\"4.0\" xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\">"
            + "<edmx:DataServices>";
    private static final String FOOTER = "</edmx:DataServices></edmx:Edmx>";

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void dashAndDotNamesProduceValidIdentifiersAndCompile(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.p").generate(parse(HEADER + """
            <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
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

        assertTrue(compileAll(out), "output must compile (previously FIRST-NAME / A-B(0L) / tByFirst-Name)");
    }

    @Test
    void filterableNavConstantsAreDeduplicated(@TempDir Path tmp) throws Exception {
        // property BUDGET + navigation budget: fields differ (bUDGET vs budget) so the
        // field-collision guard does not fire, but the constants collide — the Filterable
        // nav field must use the allocated BUDGET_2, not a second BUDGET
        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.p").generate(parse(HEADER + """
            <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
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

    /** Compiles every .java under dir against .m2, failing with the compiler errors. */
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
