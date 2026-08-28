package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two entities with the SAME simple name in different schemas (split-merge metadata),
 * cross-schema inheritance, and per-schema output packages. The subtype cast constants
 * (decision 18a) reference subtypes by simple name — ambiguous imports and self-name
 * collisions must not be emitted; inherited/copy code must not touch root lifecycle
 * fields across package boundaries. Compile-or-it-didn't-happen (lesson 120).
 */
class CrossSchemaSimpleNameCompilationTest {

    @Test
    void sameSimpleNameAcrossSchemasWithCastConstantsCompiles(@TempDir Path tmp) throws Exception {
        CsdlModel.EntityTypeModel base = new CsdlModel.EntityTypeModel("Base", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        // One.A — extends Base and carries a nav targeting Base: its cast constants see
        // BOTH One.A (self) and Two.A (foreign simple-name collision)
        CsdlModel.EntityTypeModel oneA = new CsdlModel.EntityTypeModel("A", "One.Base",
                false, false, false,
                List.of(),
                List.of(new CsdlModel.PropertyModel("Name", "Edm.String", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Children",
                        "Collection(One.Base)", null, false, false, List.of(), List.of())));
        // Two.A — same simple name, other schema/package, extends the cross-schema base,
        // with its own nav (navWith copy code lands in the foreign package) and an
        // open-type complex property (unmappedFields copy code)
        CsdlModel.EntityTypeModel twoA = new CsdlModel.EntityTypeModel("A", "One.Base",
                true, false, false,
                List.of(),
                List.of(new CsdlModel.PropertyModel("Title", "Edm.String", true, null, List.of()),
                        new CsdlModel.PropertyModel("Address", "Two.Addr", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Items",
                        "Collection(One.Base)", null, false, false, List.of(), List.of())));
        CsdlModel.ComplexTypeModel addr = new CsdlModel.ComplexTypeModel("Addr", null,
                true, false,
                List.of(new CsdlModel.PropertyModel("Street", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(base, oneA), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(twoA), List.of(addr), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel model = new CsdlModel(List.of(one, two), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"))
                .withGenerateWithMethods(true)
                .generate(model);

        List<File> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(out)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(p.toFile()));
        }
        assertFalse(javaFiles.isEmpty());

        String oneASource = Files.readString(out.resolve("com/p1/entity/A.java"));
        // self-reference must not be imported; the foreign same-name subtype must be
        // referenced by its fully-qualified name, never imported
        assertFalse(oneASource.contains("import com.p1.entity.A;"),
                "self-import of the generated class is a compile error:\n" + oneASource);
        assertFalse(oneASource.contains("import com.p2.entity.A;"),
                "importing a foreign type with the same simple name as the generated class "
                        + "collides:\n" + oneASource);
        assertTrue(oneASource.contains("CHILDREN_AS_A_2 = CHILDREN.as(\"Two.A\", com.p2.entity.A.class)"),
                "foreign same-name subtype referenced by FQN:\n" + oneASource);

        // The generated entity in a DIFFERENT schema from its parent: with*/navWith copy
        // code in the foreign package touches the parent's protected lifecycle fields
        // (contextPath, etag, unmappedFields, changedFields) — legal per JLS 6.6.2
        // (subclass access via own-type references) and must compile
        String twoASource = Files.readString(out.resolve("com/p2/entity/A.java"));
        assertTrue(twoASource.contains("    public A withTitle(String value)"),
                "cross-package subtype gets with* copy code:\n" + twoASource);
        assertTrue(twoASource.contains("        e.contextPath = contextPath;\n")
                        && twoASource.contains("        e.etag = etag;\n"),
                "copy code references the parent's protected lifecycle fields:\n" + twoASource);
        assertTrue(twoASource.contains("EntityUtil.mergeChanged(changedFields"), twoASource);
        assertTrue(twoASource.contains("        e.items = value;"),
                "navWith copy code for the subtype's own nav:\n" + twoASource);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        StringWriter compilerOutput = new StringWriter();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<File> classpath = findClasspathJars();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);
        Iterable<? extends javax.tools.JavaFileObject> units =
                fileManager.getJavaFileObjects(javaFiles.toArray(new File[0]));
        JavaCompiler.CompilationTask task = compiler.getTask(
                new PrintWriter(compilerOutput), fileManager, null, List.of(
                        "-classpath", classpath.stream().map(File::getAbsolutePath)
                                .collect(java.util.stream.Collectors.joining(File.pathSeparator))),
                null, units);
        boolean success = task.call();
        assertTrue(success, "generated cross-schema same-name client must compile. Errors:\n"
                + compilerOutput);
    }

    private List<File> findClasspathJars() {
        String userHome = System.getProperty("user.home");
        Path mavenRepo = Path.of(userHome, ".m2", "repository");
        List<String> artifactIds = List.of(
                "odata-codegen-runtime",
                "jackson-databind",
                "jackson-core",
                "jackson-annotations",
                "jackson-datatype-jdk8",
                "jackson-datatype-jsr310",
                "jackson-module-parameter-names",
                "slf4j-api");
        List<File> classpath = artifactIds.stream()
                .map(id -> findJar(mavenRepo, id))
                .filter(p -> p != null)
                .map(Path::toFile)
                .collect(java.util.ArrayList::new, java.util.ArrayList::add, java.util.ArrayList::addAll);
        Path siblingClasses = Path.of("..", "odata-codegen-runtime", "target", "classes");
        if (Files.isReadable(siblingClasses)) {
            classpath.add(0, siblingClasses.toFile());
        }
        return classpath;
    }

    private Path findJar(Path mavenRepo, String artifactId) {
        try (Stream<Path> paths = Files.walk(mavenRepo)) {
            return paths
                    .filter(p -> p.getFileName().toString().contains(artifactId))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("-sources"))
                    .filter(p -> !p.toString().contains("-javadoc"))
                    .filter(p -> p.toString().contains("0.1.0-SNAPSHOT") || !artifactId.equals("odata-codegen-runtime"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
