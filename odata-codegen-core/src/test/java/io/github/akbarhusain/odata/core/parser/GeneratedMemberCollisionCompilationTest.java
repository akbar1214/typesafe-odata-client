package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.CompilationHarness;
import io.github.akbarhusain.odata.core.generator.Generator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Ordinary CSDL names that collide with members the generator itself emits.
 *
 * <p>Three shapes, all legal metadata, all uncompilable output before this change:
 * a property named {@code Changed} (a duplicate field in {@code Builder}), a property
 * named {@code e} (a Builder local shadowing the field, giving {@code e.e = e;}), and an
 * entity type named {@code ODataException} (an ambiguous import in its request file).
 *
 * <p>Judged by javac, not by string assertions — a green {@code contains("private
 * OffsetDateTime changed;")} is precisely what the first bug produces.
 */
class GeneratedMemberCollisionCompilationTest {

    private static final String EDMX_HEAD =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
                    + "<edmx:DataServices>";
    private static final String EDM = "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    private static void generate(Path dir, String schemas) throws Exception {
        String doc = EDMX_HEAD + schemas + "</edmx:DataServices></edmx:Edmx>";
        Path metadata = dir.resolve("metadata.xml");
        Files.writeString(metadata, doc, StandardCharsets.UTF_8);
        var model = new StaxCsdlParser().parse(Files.newInputStream(metadata));
        new Generator(dir, Map.of(), "com.x").generate(model);
    }

    private static void assertCompiles(Path dir) {
        String errors = CompilationHarness.compileAll(dir);
        if (errors != null && !errors.isBlank()) {
            fail("generated client must compile, but javac reported:\n" + errors);
        }
    }

    // ------------------------------------------------------------------
    // A property named Changed duplicates the Builder's change-tracking set
    // ------------------------------------------------------------------

    @Test
    void aPropertyNamedChangedDoesNotCollideWithTheBuildersChangeSet(@TempDir Path dir) throws Exception {
        generate(dir, EDM + " Namespace=\"Audit\">"
                + "<EntityType Name=\"T\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"Changed\" Type=\"Edm.DateTimeOffset\"/>"
                + "<Property Name=\"ChangedBy\" Type=\"Edm.String\"/>"
                + "<NavigationProperty Name=\"ChangedRef\" Type=\"Audit.T\" Nullable=\"true\"/>"
                + "</EntityType></Schema>");

        String source = Files.readString(dir.resolve("com/x/entity/T.java"));
        assertTrue(source.contains("getChanged()"),
                "the public getter must keep the metadata's name:\n" + source.lines()
                        .filter(l -> l.contains("Changed")).limit(4)
                        .reduce("", (a, b) -> a + b + "\n"));
        assertCompiles(dir);
    }

    @Test
    void aComplexTypePropertyNamedChangedIsUnaffected(@TempDir Path dir) throws Exception {
        // The complex-type Builder declares no change set, so this shape ALREADY
        // compiled. It is the regression witness that the fix was applied to the
        // entity Builder's own member rather than to the shared field-name policy:
        // reserving the name globally would have renamed this working getter.
        generate(dir, EDM + " Namespace=\"Audit\">"
                + "<ComplexType Name=\"C\">"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\"/>"
                + "<Property Name=\"Changed\" Type=\"Edm.DateTimeOffset\"/>"
                + "</ComplexType></Schema>");

        String source = Files.readString(dir.resolve("com/x/complex/C.java"));
        assertTrue(source.contains("getChanged()"), "complex types must keep the plain getter name");
        assertFalse(source.contains("getChanged_()"),
                "reserving the name globally would have needlessly renamed a working getter");
        assertCompiles(dir);
    }

    // ------------------------------------------------------------------
    // A property named `e` collides with Builder.build()'s local
    // ------------------------------------------------------------------

    @Test
    void aPropertyNamedLowercaseEIsNotSelfAssignedInBuild(@TempDir Path dir) throws Exception {
        generate(dir, EDM + " Namespace=\"Short\">"
                + "<EntityType Name=\"T\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"e\" Type=\"Edm.String\"/>"
                + "<Property Name=\"e2\" Type=\"Edm.String\"/>"
                + "<NavigationProperty Name=\"e3\" Type=\"Short.T\" Nullable=\"true\"/>"
                + "</EntityType></Schema>");

        String source = Files.readString(dir.resolve("com/x/entity/T.java"));
        assertFalse(source.contains("e.e = e;"),
                "the RHS must be the Builder field, not the local entity:\n" + source.lines()
                        .filter(l -> l.trim().startsWith("e.")).limit(6)
                        .reduce("", (a, b) -> a + b + "\n"));
        assertTrue(source.contains("e.e = this.e;"), "expected the pinned reference");
        assertCompiles(dir);
    }

    @Test
    void aSelfTypedNavigationNamedLowercaseEIsNotSilentlySelfAssigned(@TempDir Path dir) throws Exception {
        // The silent variant: when the types are compatible this COMPILES while
        // overwriting the staged value with the new entity, so a content assertion on the
        // old output would have been the only way to see it -- hence the explicit check.
        generate(dir, EDM + " Namespace=\"Selfref\">"
                + "<EntityType Name=\"T\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<NavigationProperty Name=\"e\" Type=\"Selfref.T\" Nullable=\"true\"/>"
                + "</EntityType></Schema>");

        String source = Files.readString(dir.resolve("com/x/entity/T.java"));
        assertFalse(source.contains("e.e = e;"),
                "a self-typed nav named 'e' silently corrupted the built instance");
        assertCompiles(dir);
    }

    @Test
    void aComplexTypePropertyNamedLowercaseEIsAlsoPinned(@TempDir Path dir) throws Exception {
        generate(dir, EDM + " Namespace=\"Shortc\">"
                + "<ComplexType Name=\"C\">"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\"/>"
                + "<Property Name=\"e\" Type=\"Edm.String\"/>"
                + "</ComplexType></Schema>");

        String source = Files.readString(dir.resolve("com/x/complex/C.java"));
        assertFalse(source.contains("e.e = e;"));
        assertCompiles(dir);
    }

    @Test
    void aCollectionPropertyNamedLowercaseEIsDefensivelyCopiedFromTheBuilder(@TempDir Path dir) throws Exception {
        // The collection branch of build() had the same bare-name RHS inside
        // new ArrayList<>(...), so the defensive copy was copying the local.
        generate(dir, EDM + " Namespace=\"Coll\">"
                + "<EntityType Name=\"T\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"e\" Type=\"Collection(Edm.String)\"/>"
                + "</EntityType></Schema>");

        String source = Files.readString(dir.resolve("com/x/entity/T.java"));
        assertTrue(source.contains("new java.util.ArrayList<>(this.e)"),
                "the defensive copy must read the Builder field:\n" + source.lines()
                        .filter(l -> l.contains("ArrayList")).limit(3)
                        .reduce("", (a, b) -> a + b + "\n"));
        assertCompiles(dir);
    }

    // ------------------------------------------------------------------
    // An entity type named ODataException
    // ------------------------------------------------------------------

    @Test
    void anEntityTypeNamedODataExceptionDoesNotCollideWithTheRuntimeType(@TempDir Path dir) throws Exception {
        generate(dir, EDM + " Namespace=\"Err\">"
                + "<EntityType Name=\"ODataException\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"Message\" Type=\"Edm.String\"/>"
                + "</EntityType>"
                + "<EntityType Name=\"Holder\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<NavigationProperty Name=\"T\" Type=\"Err.ODataException\"/>"
                + "</EntityType></Schema>");

        assertTrue(Files.exists(dir.resolve("com/x/entity/ODataException_.java")),
                "the generated class must be renamed away from the runtime type's simple name");
        assertCompiles(dir);
    }
}
