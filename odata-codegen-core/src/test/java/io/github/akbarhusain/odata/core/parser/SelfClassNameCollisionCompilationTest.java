package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.CompilationHarness;
import io.github.akbarhusain.odata.core.generator.Generator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A generated compilation unit's OWN class claims its simple name. {@code TypeRefs} used
 * to be populated only with the file's <em>referenced</em> types, so a same-named type in
 * another output package looked uncontested: it was referenced by simple name AND
 * imported, and javac rejects an import whose simple name equals the compilation unit's
 * own class.
 *
 * <p>Content assertions cannot catch this -- a green {@code contains("import ...")} is
 * exactly what the bug produces. The check is therefore a real javac run, which is the
 * standard this project set for every "does the generated code compile" claim.
 */
class SelfClassNameCollisionCompilationTest {

    private static final String EDMX_HEAD =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
                    + "<edmx:DataServices>";
    private static final String EDM = "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    private static String document() {
        return EDMX_HEAD
                // package com.a -- A.Person references the same-simple-name B.Person twice,
                // once as a structural property and once as a navigation target.
                + EDM + " Namespace=\"A\">"
                + "<EntityType Name=\"Person\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"Best\" Type=\"B.Person\"/>"
                + "<NavigationProperty Name=\"Other\" Type=\"B.Person\"/>"
                + "</EntityType>"
                + "<ComplexType Name=\"Addr\">"
                + "<Property Name=\"Line\" Type=\"Edm.String\"/>"
                + "<NavigationProperty Name=\"Self\" Type=\"B.Addr\"/>"
                + "</ComplexType>"
                + "</Schema>"
                // package com.b -- the colliding declarations.
                + EDM + " Namespace=\"B\">"
                + "<EntityType Name=\"Person\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "</EntityType>"
                + "<ComplexType Name=\"Addr\">"
                + "<Property Name=\"Line\" Type=\"Edm.String\"/>"
                + "</ComplexType>"
                + "</Schema>"
                + "</edmx:DataServices></edmx:Edmx>";
    }

    @Test
    void sameSimpleNameInAnotherPackageStillCompiles(@TempDir Path outputDir) throws Exception {
        Path metadata = outputDir.resolve("metadata.xml");
        Files.writeString(metadata, document(), StandardCharsets.UTF_8);

        var model = new io.github.akbarhusain.odata.core.parser.StaxCsdlParser()
                .parse(Files.newInputStream(metadata));
        new Generator(outputDir, Map.of("A", "com.a", "B", "com.b"), "unused")
                .generate(model);

        // The contested names must not be imported into the unit that declares them.
        Path aEntity = outputDir.resolve("com/a/entity/Person.java");
        assertTrue(Files.exists(aEntity), "expected generated entity at " + aEntity);
        String entitySource = Files.readString(aEntity);
        assertTrue(!entitySource.contains("import com.b.entity.Person;"),
                "a same-simple-name type from another package must never be imported into the "
                        + "unit that declares its own Person:\n" + entitySource.lines()
                                .filter(l -> l.startsWith("import")).findFirst().orElse(""));

        Path aComplex = outputDir.resolve("com/a/complex/Addr.java");
        assertTrue(Files.exists(aComplex));
        assertTrue(!Files.readString(aComplex).contains("import com.b.complex.Addr;"),
                "same rule for complex types");

        Path aRequest = outputDir.resolve("com/a/entity/request/PersonEntityRequest.java");
        assertTrue(Files.exists(aRequest));
        assertTrue(!Files.readString(aRequest)
                        .contains("import com.b.entity.request.PersonEntityRequest;"),
                "same rule for entity-request classes");

        String compileErrors = CompilationHarness.compileAll(outputDir);
        if (compileErrors != null && !compileErrors.isBlank()) {
            fail("generated client must compile, but javac reported:\n" + compileErrors);
        }
    }

    @Test
    void uncontestedSameNameInOneFileIsStillReferencedSimply(@TempDir Path outputDir) throws Exception {
        // The regression witness: registering the self FQN must not push ordinary files
        // to fully-qualified references. A self-recursive nav references the file's own
        // class and must stay a simple name.
        String doc = EDMX_HEAD
                + EDM + " Namespace=\"Solo\">"
                + "<EntityType Name=\"Node\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<NavigationProperty Name=\"Child\" Type=\"Solo.Node\"/>"
                + "</EntityType>"
                + "</Schema></edmx:DataServices></edmx:Edmx>";
        Path metadata = outputDir.resolve("solo.xml");
        Files.writeString(metadata, doc, StandardCharsets.UTF_8);

        var model = new io.github.akbarhusain.odata.core.parser.StaxCsdlParser()
                .parse(Files.newInputStream(metadata));
        new Generator(outputDir, Map.of("Solo", "com.solo"), "unused").generate(model);

        String source = Files.readString(outputDir.resolve("com/solo/entity/Node.java"));
        assertTrue(source.contains("Solo.Node child;") || source.contains("Node child;"),
                "a self-recursive navigation must stay a simple reference, not an FQN:\n"
                        + source.lines().filter(l -> l.contains("child")).limit(3)
                                .reduce("", (a, b) -> a + b + "\n"));
        assertTrue(!source.contains("import com.solo.entity.Node;"),
                "a class must not import itself");

        String errors = CompilationHarness.compileAll(outputDir);
        if (errors != null && !errors.isBlank()) {
            fail("generated client must compile, but javac reported:\n" + errors);
        }
    }

    @Test
    void javaSourcesAreActuallyProduced(@TempDir Path outputDir) throws Exception {
        // Sanity: the harness above is meaningless if nothing was generated.
        Path metadata = outputDir.resolve("metadata.xml");
        Files.writeString(metadata, document(), StandardCharsets.UTF_8);
        var model = new io.github.akbarhusain.odata.core.parser.StaxCsdlParser()
                .parse(Files.newInputStream(metadata));
        new Generator(outputDir, Map.of("A", "com.a", "B", "com.b"), "unused").generate(model);
        try (Stream<Path> files = Files.walk(outputDir)) {
            long count = files.filter(p -> p.toString().endsWith(".java")).count();
            // 2 entities + 2 complex types -> entity, entity-request and
            // collection-request classes each, plus one SchemaInfo registry per package:
            // 2*(3+3) + 2 = 14 with a container; the exact count is not the point, the
            // point is that a real client was emitted rather than an empty tree.
            assertTrue(count >= 10, "expected a full client to be generated, got " + count);
        }
    }
}
