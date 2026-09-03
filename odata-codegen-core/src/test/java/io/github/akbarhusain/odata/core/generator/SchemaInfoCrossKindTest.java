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
 * SchemaInfo registry correctness:
 * 1. The same QUALIFIED name declared by more than one kind (entity Foo + complex Foo +
 *    enum Foo in one namespace — tolerated by the lenient parser) must FAIL generation
 *    loudly: the registry would emit three classes.put("NS.Foo", ...) with one key and
 *    the HashMap silently keeps the last put, misrouting polymorphic deserialization.
 * 2. The LEGAL contested case — same SIMPLE name from different schemas mapped to one
 *    output package (NS1.Foo entity + NS2.Foo complex) — must resolve references
 *    fully-qualified, never double-import, and the whole output must COMPILE
 *    (lesson 120: content assertions stay green while the compile breaks).
 */
class SchemaInfoCrossKindTest {

    @Test
    void sameQualifiedKeyAcrossKindsFailsLoudly(@TempDir Path tmp) {
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("Foo", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel complex = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.EnumTypeModel en = new CsdlModel.EnumTypeModel("Foo", "Edm.Int32", false,
                List.of(new CsdlModel.EnumMemberModel("Alpha", 0)));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(entity), List.of(complex), List.of(en),
                List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new Generator(out, Map.of(), "com.example")
                        .generate(new CsdlModel(List.of(schema), List.of())),
                "three classes.put(\"NS.Foo\", ...) would silently keep the last — fail loudly instead");
        assertTrue(ex.getMessage().contains("NS.Foo"),
                "the ambiguous qualified name must be named: " + ex.getMessage());
    }

    @Test
    void crossSchemaSameSimpleNameResolvesAndCompiles(@TempDir Path tmp) throws Exception {
        CsdlModel.EntityTypeModel entityFoo = new CsdlModel.EntityTypeModel("Foo", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel complexFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel ns1 = new CsdlModel.SchemaModel("NS1", null,
                List.of(entityFoo), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel ns2 = new CsdlModel.SchemaModel("NS2", null,
                List.of(), List.of(complexFoo), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        // BOTH schemas map to the SAME base package — the realistic contested case
        new Generator(out, Map.of("NS1", "com.example", "NS2", "com.example"), "com.example")
                .generate(new CsdlModel(List.of(ns1, ns2), List.of()));

        String code = Files.readString(out.resolve("com/example/schema/SchemaInfo.java"));
        long fooImports = code.lines()
                .filter(l -> l.startsWith("import ") && l.endsWith(".Foo;"))
                .count();
        assertEquals(0, fooImports,
                "contested simple name Foo must be referenced fully-qualified, never imported:\n" + code);
        assertTrue(code.contains("com.example.entity.Foo.class")
                        && code.contains("com.example.complex.Foo.class"),
                "contested registry references must be fully-qualified:\n" + code);

        assertTrue(compiles(out), "legal cross-schema same-name registry must compile");
    }

    private boolean compiles(Path out) throws Exception {
        List<File> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(out)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(p.toFile()));
        }
        assertFalse(javaFiles.isEmpty());
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
