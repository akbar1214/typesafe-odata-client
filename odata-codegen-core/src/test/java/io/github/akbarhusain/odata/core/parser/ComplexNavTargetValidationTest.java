package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.CompilationHarness;
import io.github.akbarhusain.odata.core.generator.Generator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CSDL §12.2 requires a NavigationProperty to target an entity or complex type. The entity
 * path rejects enum/primitive targets loudly in {@code RequestGenerator.isNonEntityNav}, but
 * complex types have no request layer — their nav targets were never validated, so a
 * complex type with an enum-target collection nav emitted {@code Color.Filterable}
 * (no such inner class) and an {@code Edm.String} nav emitted {@code String_} (a sanitized
 * JDK-shadow name that is never generated). Generation reported success and committed
 * uncompilable output instead of failing loudly like the entity path does.
 *
 * <p>Judged by javac: the bugs are "generated code does not compile", which content
 * assertions alone cannot prove.
 */
class ComplexNavTargetValidationTest {

    private static final String EDMX_HEAD =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
                    + "<edmx:DataServices>";
    private static final String EDM = "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    private static String metadata() {
        return EDMX_HEAD
                + EDM + " Namespace=\"NS\">"
                + "<EnumType Name=\"Color\"><Member Name=\"Red\" Value=\"0\"/></EnumType>"
                + "<ComplexType Name=\"Wrapper\">"
                + "<NavigationProperty Name=\"Colors\" Type=\"Collection(NS.Color)\"/>"
                + "<NavigationProperty Name=\"Single\" Type=\"Edm.String\"/>"
                + "</ComplexType>"
                + "<EntityType Name=\"Holder\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"W\" Type=\"NS.Wrapper\"/>"
                + "</EntityType></Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
    }

    private static void generate(Path out) throws Exception {
        Path file = out.resolve("metadata.xml");
        Files.writeString(file, metadata(), StandardCharsets.UTF_8);
        var model = new StaxCsdlParser().parse(Files.newInputStream(file));
        new Generator(out, Map.of(), "com.ns").generate(model);
    }

    @Test
    void enumNavTargetOnComplexTypeIsRejectedLoudly(@TempDir Path out) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> generate(out),
                "an enum-target navigation property is invalid CSDL and must abort generation");
        assertTrue(error.getMessage().contains("Colors") && error.getMessage().contains("CSDL 12.2"),
                "the failure must name the offending navigation property: " + error.getMessage());
    }

    @Test
    void primitiveNavTargetOnComplexTypeIsRejectedLoudly(@TempDir Path out) {
        // "Edm.String" resolves to UNKNOWN (primitives are not in the type-kind map) —
        // the same branch as the enum case. Without validation this emitted `String_`.
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> generate(out));
        assertTrue(error.getMessage().contains("non-navigable"), error.getMessage());
    }

    @Test
    void validEntityAndComplexNavTargetsStillGenerateAndCompile(@TempDir Path out) throws Exception {
        String doc = EDMX_HEAD
                + EDM + " Namespace=\"NS\">"
                + "<ComplexType Name=\"Addr\"><Property Name=\"Street\" Type=\"Edm.String\"/></ComplexType>"
                + "<EntityType Name=\"Target\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "</EntityType>"
                + "<ComplexType Name=\"NavHolder\">"
                + "<Property Name=\"Label\" Type=\"Edm.String\"/>"
                + "<NavigationProperty Name=\"One\" Type=\"NS.Target\"/>"
                + "<NavigationProperty Name=\"Many\" Type=\"Collection(NS.Target)\"/>"
                + "<NavigationProperty Name=\"Inline\" Type=\"NS.Addr\"/>"
                + "</ComplexType>"
                + "<EntityType Name=\"Owner\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"H\" Type=\"NS.NavHolder\"/>"
                + "</EntityType></Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
        Path file = out.resolve("metadata.xml");
        Files.writeString(file, doc, StandardCharsets.UTF_8);
        var model = new StaxCsdlParser().parse(Files.newInputStream(file));
        new Generator(out, Map.of(), "com.ns").generate(model);

        String errors = CompilationHarness.compileAll(out);
        if (errors != null && !errors.isBlank()) {
            fail("entity and complex nav targets are legal and must compile:\n" + errors);
        }
    }
}
