package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.CompilationHarness;
import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A property that OData can {@code $select} but not compare must still get a descriptor.
 *
 * <p>Complex, {@code Edm.Binary} and geography/geometry structural properties produced a
 * field, getter, setter and Builder entry but NO constant and NO {@code Selector} field,
 * because {@code getPropertyConstantType} returns null for them. The entity therefore
 * exposed data the typed query API could not name.
 *
 * <p>Judged by javac: the fix emits a type the generated code references, so a content
 * assertion would pass even if that type did not exist.
 */
class SelectablePropertyGenerationTest {

    private static final String EDMX_HEAD =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
                    + "<edmx:DataServices>";
    private static final String EDM = "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    private static String metadata() {
        return EDMX_HEAD
                + EDM + " Namespace=\"PTC.Workflow\">"
                + "<EnumType Name=\"EnumType\" UnderlyingType=\"Edm.Int32\">"
                + "<Member Name=\"Active\" Value=\"1\"/><Member Name=\"Inactive\" Value=\"2\"/></EnumType>"
                + "<TypeDefinition Name=\"Status\" UnderlyingType=\"PTC.Workflow.EnumType\"/>"
                + "<ComplexType Name=\"ProcessData\">"
                + "<Property Name=\"Reason\" Type=\"Edm.String\"/>"
                + "<Property Name=\"Role\" Type=\"PTC.Workflow.EnumType\"/></ComplexType>"
                + "<EntityType Name=\"WorkItem\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"ProcessData\" Type=\"PTC.Workflow.ProcessData\"/>"
                + "<Property Name=\"Photo\" Type=\"Edm.Binary\"/>"
                + "<Property Name=\"Where\" Type=\"Edm.GeographyPoint\"/>"
                + "<Property Name=\"Status\" Type=\"PTC.Workflow.Status\"/>"
                + "<Property Name=\"Title\" Type=\"Edm.String\"/>"
                + "</EntityType></Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
    }

    private static String generate(Path out) throws Exception {
        Path file = out.resolve("metadata.xml");
        Files.writeString(file, metadata(), StandardCharsets.UTF_8);
        CsdlModel model = new StaxCsdlParser().parse(Files.newInputStream(file));
        new Generator(out, Map.of(), "com.ptc").generate(model);
        return Files.readString(out.resolve("com/ptc/entity/WorkItem.java"));
    }

    /**
     * Asserts the constant is declared with its own Java name and carries the CSDL WIRE
     * name in the constructor -- both halves, because getting either wrong produces output
     * that compiles but selects the wrong property.
     */
    private static void assertSelectableConstant(String source, String constantName, String wireName) {
        assertTrue(source.contains("public static final SelectableProperty<WorkItem> " + constantName
                        + " = new SelectableProperty<>(\"" + wireName + "\", WorkItem.class);"),
                constantName + " must be declared as a SelectableProperty naming the wire path '"
                        + wireName + "'");
        assertTrue(source.contains("public final SelectableProperty<WorkItem> " + constantName
                        + " = WorkItem." + constantName + ";"),
                constantName + " must also appear in the Selector class so the $select lambda "
                        + "can name it");
    }

    @Test
    void complexTypedPropertyIsSelectable(@TempDir Path out) throws Exception {
        assertSelectableConstant(generate(out), "PROCESS_DATA", "ProcessData");
    }

    @Test
    void binaryPropertyIsSelectable(@TempDir Path out) throws Exception {
        assertSelectableConstant(generate(out), "PHOTO", "Photo");
    }

    @Test
    void geographyPropertyIsSelectable(@TempDir Path out) throws Exception {
        assertSelectableConstant(generate(out), "WHERE", "Where");
    }

    @Test
    void filterablePropertiesKeepTheirRicherDescriptors(@TempDir Path out) throws Exception {
        // The regression witness: the select-only descriptor must not REPLACE the ones
        // that already carried comparison and ordering operators.
        String source = generate(out);
        assertTrue(source.contains("StringProperty<WorkItem> TITLE"), "a string keeps StringProperty");
        assertTrue(source.contains("NumberProperty<WorkItem, Integer> ID"), "an int keeps NumberProperty");
        assertTrue(source.contains("EnumProperty<WorkItem, EnumType> STATUS"),
                "a TypeDefinition-of-enum keeps EnumProperty -- resolveTypeDefinition runs "
                        + "first, so the typedef resolves to the underlying enum");
        assertFalse(source.contains("SelectableProperty<WorkItem> TITLE"),
                "a string must not be downgraded to the select-only descriptor");
        assertFalse(source.contains("SelectableProperty<WorkItem> STATUS"),
                "a typedef-of-enum must not be downgraded to the select-only descriptor");
    }

    @Test
    void theSelectOnlyDescriptorStaysOutOfTheFilterableClass(@TempDir Path out) throws Exception {
        // Filterable exists for any()/all() lambdas, where only a COMPARABLE descriptor is
        // meaningful. Brace-balanced slice rather than a fixed window, so the assertion
        // cannot silently stop matching after a formatting change.
        String source = generate(out);
        int start = source.indexOf("class Filterable {");
        assertTrue(start > 0, "expected a Filterable class");
        int depth = 0;
        int end = -1;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) { end = i; break; }
        }
        assertTrue(end > start, "expected a balanced Filterable class body");
        String block = source.substring(start, end);
        assertFalse(block.contains("SelectableProperty"),
                "Filterable is for any()/all() and must not gain select-only fields");
        assertTrue(block.contains("TITLE"), "sanity: Filterable still carries the string field");
    }

    @Test
    void theGeneratedClientCompilesWithTheNewDescriptor(@TempDir Path out) throws Exception {
        generate(out);
        String errors = CompilationHarness.compileAll(out);
        if (errors != null && !errors.isBlank()) {
            fail("generated client must compile, but javac reported:\n" + errors);
        }
    }

    @Test
    void anEntityTypeNamedSelectablePropertyIsRenamedAway(@TempDir Path out) throws Exception {
        // The wildcard import io.github...runtime.query.* means a generated type sharing a
        // runtime descriptor's simple name would hijack that name in every same-package
        // file. Names.JDK_CLASS_NAMES exists to prevent exactly this.
        Path dir = Files.createTempDirectory("nameclash");
        String doc = EDMX_HEAD
                + EDM + " Namespace=\"Clash\">"
                + "<EntityType Name=\"SelectableProperty\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "</EntityType>"
                + "<EntityType Name=\"Holder\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<NavigationProperty Name=\"T\" Type=\"Clash.SelectableProperty\"/>"
                + "</EntityType></Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
        Path file = dir.resolve("m.xml");
        Files.writeString(file, doc, StandardCharsets.UTF_8);
        CsdlModel model = new StaxCsdlParser().parse(Files.newInputStream(file));
        new Generator(dir, Map.of(), "com.clash").generate(model);

        assertTrue(Files.exists(dir.resolve("com/clash/entity/SelectableProperty_.java")),
                "the generated class must be renamed away from the runtime descriptor's simple name");
        String errors = CompilationHarness.compileAll(dir);
        if (errors != null && !errors.isBlank()) {
            fail("generated client must compile, but javac reported:\n" + errors);
        }
    }
}
