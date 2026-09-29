package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A generated request class must import another schema's request classes from THAT schema's
 * package.
 *
 * <p>Regression: {@code RequestGenerator} kept its "which namespace am I writing" in a mutable
 * field set by {@code initEffectiveSchemas(schema)}, and its RESOLUTION helpers
 * ({@code resolveEntityType}, {@code keyParamSpecs}, ...) are called mid-render with a nav's
 * DECLARING schema. For an inherited navigation declared in another schema that overwrote the
 * output namespace, so every later {@code basePackageForType} call saw the foreign namespace as
 * local and substituted the LOCAL package — emitting
 * {@code <local>.collection.request.CountEntityCollectionRequest} for a class that lives in
 * {@code <foreign>.collection.request}. The result did not compile.
 *
 * <p>The failure is order-dependent, and the fixture is shaped around exactly why:
 * <ul>
 *   <li>Both clobber sites sit behind an {@code isCollectionType} guard, so the navigations
 *       must be COLLECTION-typed — any number of single-valued inherited navigations
 *       reproduces nothing, because the helpers are never called for them;</li>
 *   <li>per navigation the package lookup runs BEFORE that navigation's own clobbering call,
 *       so the FIRST navigation is correct and every later one is wrong — one collection
 *       navigation cannot reproduce it either.</li>
 * </ul>
 *
 * <p>The fixture is self-guarding against a later "simplification": single-valuing the
 * navigations drops the collection-request imports these assertions require, so the test
 * fails loudly rather than silently pinning nothing (verified by mutating the fixture).
 *
 * <p>Both tests fail before the fix — the content assertions and the compile referee alike.
 * The referee's distinct contribution is the method BODIES ({@code new XCollectionRequest(...)}
 * and the keyed overloads), which no content assertion reads, plus the rest of the client.
 */
class CrossSchemaInheritedNavImportTest {

    private static final String EDMX_HEAD =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
                    + "<edmx:DataServices>";

    /** {@code InfineonPJM.IMilestone} inherits three navigations declared in {@code Infineon}. */
    private static String metadata() {
        StringBuilder navs = new StringBuilder();
        for (String target : new String[]{"CountEntity", "IContainer", "Extra"}) {
            navs.append("<NavigationProperty Name=\"Nav").append(target)
                    .append("\" Type=\"Collection(Infineon.").append(target).append(")\"/>");
        }
        StringBuilder sets = new StringBuilder();
        for (String target : new String[]{"CountEntity", "IContainer", "Extra"}) {
            sets.append("<EntitySet Name=\"").append(target).append("s\" EntityType=\"Infineon.")
                    .append(target).append("\"/>");
        }
        return EDMX_HEAD
                + "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\" Namespace=\"Infineon\">"
                + "<EntityType Name=\"CountEntity\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/></EntityType>"
                + "<EntityType Name=\"IContainer\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/></EntityType>"
                + "<EntityType Name=\"Extra\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/></EntityType>"
                + "<EntityType Name=\"Base\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + navs
                + "</EntityType>"
                + "</Schema>"
                + "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\" Namespace=\"InfineonPJM\">"
                + "<EntityType Name=\"IMilestone\" BaseType=\"Infineon.Base\">"
                + "<Property Name=\"Name\" Type=\"Edm.String\"/></EntityType>"
                + "<EntityContainer Name=\"Container\">"
                + sets
                + "<EntitySet Name=\"IMilestones\" EntityType=\"InfineonPJM.IMilestone\"/>"
                + "</EntityContainer>"
                + "</Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
    }

    /** Each schema gets its OWN output package, which is what makes a wrong package visible. */
    private static Path generate(Path out) throws Exception {
        Path file = out.resolve("metadata.xml");
        Files.writeString(file, metadata(), StandardCharsets.UTF_8);
        CsdlModel model = new StaxCsdlParser().parse(Files.newInputStream(file));
        Map<String, String> packages = new LinkedHashMap<>();
        packages.put("Infineon", "com.infineon");
        packages.put("InfineonPJM", "com.infineon.pjm");
        new Generator(out, packages, "com.infineon.pjm").generate(model);
        return out.resolve("com/infineon/pjm/entity/request/IMilestoneEntityRequest.java");
    }

    @Test
    void inheritedNavsImportTheForeignRequestPackages(@TempDir Path out) throws Exception {
        String source = Files.readString(generate(out));

        for (String target : new String[]{"CountEntity", "IContainer", "Extra"}) {
            assertTrue(source.contains("import com.infineon.collection.request."
                            + target + "CollectionRequest;"),
                    "the inherited nav to Infineon." + target + " must import its collection "
                            + "request from the schema that declares it");
            assertTrue(source.contains("import com.infineon.entity.request."
                            + target + "EntityRequest;"),
                    "and its entity request likewise");
        }
        assertFalse(source.contains("import com.infineon.pjm.collection.request."),
                "no request class of another schema may be imported from the local package");
        assertFalse(source.contains("import com.infineon.pjm.entity.request."),
                "no request class of another schema may be imported from the local package");
    }

    @Test
    void theGeneratedClientCompiles(@TempDir Path out) throws Exception {
        generate(out);
        String errors = CompilationHarness.compileAll(out);
        if (errors != null && !errors.isBlank()) {
            fail("generated cross-schema client must compile, but javac reported:\n" + errors);
        }
    }
}
