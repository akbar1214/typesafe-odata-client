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
                <Property Name="2FA" Type="Edm.String"/>
              </EntityType>
              <EnumType Name="E"><Member Name="a-b"/><Member Name="c d"/></EnumType>
              <EntityContainer Name="C"><EntitySet Name="Ts" EntityType="Ns.T"/></EntityContainer>
            </Schema>""" + FOOTER));

        String entity = Files.readString(out.resolve("com/p/entity/T.java"));
        assertTrue(entity.contains("StringProperty<T> FIRST_NAME"),
                "dash maps to underscore. Got:\n" + entity);
        assertTrue(entity.contains("NumberProperty<T, Integer> AGE_YEARS"),
                "dot maps to underscore. Got:\n" + entity);
        // digit-leading property: field _2FA, constant must dodge the field name.
        // Pre-fix output declared BOTH `protected String _2FA;` and
        // `public static final StringProperty<T> _2FA` (a javac duplicate) and a
        // content-only test stayed green — the output never compiled.
        assertTrue(entity.contains(" StringProperty<T> _2FA_2 ="),
                "digit-leading constant must dodge its own field name (_2FA_2). Got:\n" + entity);
        assertFalse(entity.contains(" StringProperty<T> _2FA ="),
                "the colliding constant name must be gone:\n" + entity);

        String enumCode = Files.readString(out.resolve("com/p/enums/E.java"));
        assertTrue(enumCode.contains("A_B(0L, \"a-b\")"),
                "sanitized enum constant with its wire name. Got:\n" + enumCode);

        String errors = CompilationHarness.compileAll(out);
        assertNull(errors, "output must compile (previously FIRST-NAME / A-B(0L) / duplicate _2FA):\n"
                + errors);
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
                        && entity.contains("CollectionProperty<T, Other, Other.Filterable, ?> BUDGET_2 ="),
                "Filterable nav fields must use the allocated (deduped) names. Got:\n" + entity);
        String errors = CompilationHarness.compileAll(out);
        assertNull(errors, "output must compile (previously two BUDGET fields in Filterable):\n" + errors);
    }
}
