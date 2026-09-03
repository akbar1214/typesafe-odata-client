package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * RED (TDD): property-typed references ignore per-file TypeRefs resolution.
 * Two schemas declare complex type "Foo" mapped to different output packages.
 * An entity with properties of BOTH types must reference them fully-qualified
 * (contested simple name) and never double-import — otherwise the generated
 * file has ambiguous/unresolvable references and does not compile.
 */
class ContestedPropertyTypeTest {

    private CsdlModel contestedModel() {
        CsdlModel.ComplexTypeModel oneFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel twoFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Code", "Edm.Int32", true, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel holder = new CsdlModel.EntityTypeModel("Holder", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("First", "One.Foo", true, null, List.of()),
                        new CsdlModel.PropertyModel("Second", "Two.Foo", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(holder), List.of(oneFoo), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(), List.of(twoFoo), List.of(), List.of(), List.of(), List.of(), List.of());
        return new CsdlModel(List.of(one, two), List.of());
    }

    @Test
    void contestedPropertyTypesAreFullyQualified(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"))
                .generate(contestedModel());

        String holderSource = Files.readString(out.resolve("com/p1/entity/Holder.java"));

        // Both Foo types are contested: no plain "Foo" field type may remain —
        // every reference must be fully-qualified (contains a dot).
        assertTrue(holderSource.contains("com.p1.complex.Foo")
                        && holderSource.contains("com.p2.complex.Foo"),
                "contested property types must be fully-qualified:\n" + holderSource);

        long fooImports = holderSource.lines()
                .filter(l -> l.startsWith("import ") && l.endsWith(".complex.Foo;"))
                .count();
        assertEquals(0, fooImports,
                "contested simple names are NEVER imported — every claimant goes FQN:\n" + holderSource);

        assertTrue(compiles(out),
                "contested property types must produce a compiling client");
    }

    @Test
    void contestedCollectionPropertyTypesAreFullyQualified(@TempDir Path tmp) throws Exception {
        CsdlModel.ComplexTypeModel oneFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel twoFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Code", "Edm.Int32", true, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel holder = new CsdlModel.EntityTypeModel("Holder", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Tags", "Collection(One.Foo)", true, null, List.of()),
                        new CsdlModel.PropertyModel("Others", "Collection(Two.Foo)", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(holder), List.of(oneFoo), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(), List.of(twoFoo), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"))
                .generate(new CsdlModel(List.of(one, two), List.of()));

        String holderSource = Files.readString(out.resolve("com/p1/entity/Holder.java"));
        assertTrue(holderSource.contains("List<com.p1.complex.Foo>")
                        && holderSource.contains("List<com.p2.complex.Foo>"),
                "contested collection element types must be fully-qualified:\n" + holderSource);

        assertTrue(compiles(out),
                "contested collection property types must produce a compiling client");
    }

    private boolean compiles(Path out) throws Exception {
        java.util.List<File> javaFiles = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(out)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(p.toFile()));
        }
        assertFalse(javaFiles.isEmpty());
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        StringWriter compilerOutput = new StringWriter();
        javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<File> classpath = findClasspathJars();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);
        Iterable<? extends javax.tools.JavaFileObject> units =
                fileManager.getJavaFileObjects(javaFiles.toArray(new File[0]));
        javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
                new PrintWriter(compilerOutput), fileManager, null, List.of(
                        "-classpath", classpath.stream().map(File::getAbsolutePath)
                                .collect(java.util.stream.Collectors.joining(File.pathSeparator))),
                null, units);
        boolean success = task.call();
        if (!success) {
            System.out.println("Compilation failed:\n" + compilerOutput);
        }
        return success;
    }

    private List<File> findClasspathJars() {
        Path mavenRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        List<String> artifactIds = List.of(
                "odata-codegen-runtime",
                "jackson-databind",
                "jackson-core",
                "jackson-annotations",
                "jackson-datatype-jdk8",
                "jackson-datatype-jsr310",
                "jackson-module-parameter-names",
                "slf4j-api");
        List<File> classpath = new ArrayList<>();
        for (String id : artifactIds) {
            Path jar = findJar(mavenRepo, id);
            if (jar != null) {
                classpath.add(jar.toFile());
            }
        }
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
                    .filter(p -> p.toString().contains("0.1.0-SNAPSHOT")
                            || !artifactId.equals("odata-codegen-runtime"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
