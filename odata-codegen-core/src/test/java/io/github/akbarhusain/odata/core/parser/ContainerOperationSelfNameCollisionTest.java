package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.CompilationHarness;
import io.github.akbarhusain.odata.core.generator.Generator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Lesson 201: a per-file resolver deciding "is this name ambiguous?" must treat the file
 * itself as a claimant of every name it declares. {@code EntityGenerator},
 * {@code ComplexTypeGenerator} and {@code RequestGenerator} do; {@code ContainerGenerator}
 * and {@code OperationGenerator} did not, so legal CSDL names equal to a generated
 * request/operation class produced a self-import ("X is already defined in this compilation
 * unit") or a double import of one simple name (ambiguous reference).
 *
 * <p>Judged by javac: the defects are literally "the generated file does not compile".
 */
class ContainerOperationSelfNameCollisionTest {

    private static final String EDMX_HEAD =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
                    + "<edmx:DataServices>";
    private static final String EDM = "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    private static void generate(Path out, String schemaBody) throws Exception {
        Path file = out.resolve("metadata.xml");
        Files.writeString(file, EDMX_HEAD + EDM + " Namespace=\"NS\">" + schemaBody + "</Schema>"
                + "</edmx:DataServices></edmx:Edmx>", StandardCharsets.UTF_8);
        var model = new StaxCsdlParser().parse(Files.newInputStream(file));
        new Generator(out, Map.of(), "com.ns").generate(model);
    }

    private static void assertCompiles(Path out) {
        String errors = CompilationHarness.compileAll(out);
        if (errors != null && !errors.isBlank()) {
            fail("generated client must compile, but javac reported:\n" + errors);
        }
    }

    private static void assertNoDuplicateSimpleImports(String source) {
        Set<String> seen = new HashSet<>();
        for (String line : source.lines().toList()) {
            if (!line.startsWith("import ") || line.endsWith(".*;")) {
                continue;
            }
            String fqn = line.substring("import ".length(), line.length() - 1);
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            assertTrue(seen.add(simple), "duplicate import of simple name '" + simple + "': " + line);
        }
    }

    @Test
    void containerNamedLikeItsCollectionRequestDoesNotImportItself(@TempDir Path out) throws Exception {
        generate(out, "<EntityType Name=\"Target\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/></EntityType>"
                + "<EntityContainer Name=\"TargetCollectionRequest\">"
                + "<EntitySet Name=\"Targets\" EntityType=\"NS.Target\"/></EntityContainer>");
        String source = Files.readString(out.resolve("com/ns/container/TargetCollectionRequest.java"));
        assertFalse(source.contains("import com.ns.collection.request.TargetCollectionRequest;"),
                "the container's own class must never be imported into its compilation unit");
        assertTrue(source.contains("com.ns.collection.request.TargetCollectionRequest targets()"),
                "the contested collection request must be referenced fully-qualified");
        assertCompiles(out);
    }

    @Test
    void operationRequestClassCollidingWithAParameterTypeDoesNotImportItself(@TempDir Path out) throws Exception {
        generate(out, "<EntityType Name=\"GetStuffFunctionRequest\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/></EntityType>"
                + "<Function Name=\"GetStuff\"><Parameter Name=\"e\" Type=\"NS.GetStuffFunctionRequest\"/>"
                + "<ReturnType Type=\"Edm.String\"/></Function>"
                + "<EntityContainer Name=\"Container\">"
                + "<FunctionImport Name=\"GetStuff\" Function=\"NS.GetStuff\"/></EntityContainer>");
        String source = Files.readString(out.resolve("com/ns/operation/GetStuffFunctionRequest.java"));
        assertFalse(source.contains("import com.ns.entity.GetStuffFunctionRequest;"),
                "the operation class must not import a type whose simple name it declares itself");
        assertTrue(source.contains("com.ns.entity.GetStuffFunctionRequest e"),
                "the contested parameter type must be referenced fully-qualified");
        assertCompiles(out);
    }

    @Test
    void containerEntityAndOperationClassesWithOneSimpleNameCompile(@TempDir Path out) throws Exception {
        generate(out, "<EntityType Name=\"GetStuffFunctionRequest\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/></EntityType>"
                + "<Function Name=\"GetStuff\"><Parameter Name=\"e\" Type=\"NS.GetStuffFunctionRequest\"/>"
                + "<ReturnType Type=\"Edm.String\"/></Function>"
                + "<EntityContainer Name=\"Container\">"
                + "<EntitySet Name=\"Requests\" EntityType=\"NS.GetStuffFunctionRequest\"/>"
                + "<FunctionImport Name=\"GetStuff\" Function=\"NS.GetStuff\"/></EntityContainer>");
        String source = Files.readString(out.resolve("com/ns/container/Container.java"));
        assertFalse(source.contains("import com.ns.entity.GetStuffFunctionRequest;"),
                "a contested entity reference must be fully-qualified, not imported");
        assertFalse(source.contains("import com.ns.operation.GetStuffFunctionRequest;"),
                "a contested operation reference must be fully-qualified, not imported");
        assertNoDuplicateSimpleImports(source);
        assertCompiles(out);
    }
}
