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
 * Two contested-resolution gaps (round-7 review of the TypeRefs work, lesson 181):
 * (a) typedef NAVIGATION targets must resolve through the TypeDefinition chain before
 * computing import-candidate FQNs — a typedef has no generated class, so importing or
 * registering {@code <pkg>.entity.<Typedef>} produces uncompilable output while the
 * emitted field types reference the UNDERLYING class; (b) BASE types referenced by
 * {@code extends} (and their imports) must join the same contested-name resolution —
 * a base simple name colliding with any other reference double-imports or leaves an
 * ambiguous simple {@code extends}. Compile-or-it-didn't-happen (lesson 120).
 */
class TypedefNavAndContestedBaseCompilationTest {

    // ------------------------------------------------------------------
    // (a) typedef navigation targets resolve to the UNDERLYING generated class
    // ------------------------------------------------------------------

    @Test
    void typedefNavTargetImportsUnderlyingGeneratedClass(@TempDir Path tmp) throws Exception {
        CsdlModel.ComplexTypeModel address = new CsdlModel.ComplexTypeModel("Address", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Street", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel holder = new CsdlModel.EntityTypeModel("Holder", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Homes",
                        "Collection(Data.MyAddr)", null, false, false, List.of(), List.of())));
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("C",
                List.of(new CsdlModel.EntitySetModel("Holders", "Data.Holder", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel data = new CsdlModel.SchemaModel("Data", null,
                List.of(holder), List.of(address), List.of(),
                List.of(new CsdlModel.TypeDefinitionModel("MyAddr", "Data.Address")),
                List.of(), List.of(), List.of(container));

        Path out = tmp.resolve("out");
        new Generator(out, Map.of("Data", "com.example"), "com.example").generate(model(data));

        String source = Files.readString(out.resolve("com/example/entity/Holder.java"));
        assertFalse(source.contains("MyAddr"),
                "a typedef has no generated class — every reference must resolve to Address:\n" + source);
        assertTrue(source.contains("import com.example.complex.Address;"),
                "the UNDERLYING class must be imported:\n" + source);
        assertTrue(source.contains("List<Address> getHomes()"),
                "getter type resolves through the typedef chain:\n" + source);

        assertTrue(compiles(out), "typedef nav target must generate compilable code");
    }

    // ------------------------------------------------------------------
    // (b) base types join contested-name resolution (entity)
    // ------------------------------------------------------------------

    @Test
    void contestedEntityBaseTypeIsResolvedLikeEveryReference(@TempDir Path tmp) throws Exception {
        CsdlModel.EntityTypeModel oneBase = entity("Base", null);
        CsdlModel.EntityTypeModel twoBase = entity("Base", null);
        // Sub extends the same-schema Base AND navigates to Two.Base — two packages
        // contribute the simple name `Base` to Sub's file
        CsdlModel.EntityTypeModel sub = new CsdlModel.EntityTypeModel("Sub", "One.Base",
                false, false, false,
                List.of(),
                List.of(new CsdlModel.PropertyModel("Name", "Edm.String", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Others",
                        "Collection(Two.Base)", null, false, false, List.of(), List.of())));
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("C",
                List.of(new CsdlModel.EntitySetModel("Subs", "One.Sub", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(oneBase, sub), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(container));
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(twoBase), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"), null)
                .withGenerateWithMethods(true)
                .generate(model(one, two));

        String source = Files.readString(out.resolve("com/p1/entity/Sub.java"));
        long baseImports = source.lines().filter(l -> l.startsWith("import ")
                && l.endsWith(".entity.Base;")).count();
        assertEquals(0, baseImports,
                "contested simple name Base must be referenced fully-qualified, never imported:\n" + source);
        assertTrue(source.contains("extends com.p1.entity.Base"),
                "the extends clause carries the FQN under contention:\n" + source);
        assertTrue(source.contains("com.p2.entity.Base"),
                "the nav target FQN is printed:\n" + source);

        assertTrue(compiles(out), "contested entity base must generate compilable code");
    }

    // ------------------------------------------------------------------
    // (b) base types join contested-name resolution (complex)
    // ------------------------------------------------------------------

    @Test
    void contestedComplexBaseTypeIsResolvedLikeEveryReference(@TempDir Path tmp) throws Exception {
        CsdlModel.ComplexTypeModel oneBase = complex("Base");
        CsdlModel.ComplexTypeModel twoBase = complex("Base");
        CsdlModel.ComplexTypeModel info = new CsdlModel.ComplexTypeModel("Info", "One.Base",
                false, false,
                List.of(new CsdlModel.PropertyModel("Tag", "Edm.String", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Others",
                        "Collection(Two.Base)", null, false, false, List.of(), List.of())));
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(), List.of(oneBase, info), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(), List.of(twoBase), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"), null)
                .withGenerateWithMethods(true)
                .generate(model(one, two));

        String source = Files.readString(out.resolve("com/p1/complex/Info.java"));
        long baseImports = source.lines().filter(l -> l.startsWith("import ")
                && l.endsWith(".complex.Base;")).count();
        assertEquals(0, baseImports,
                "contested simple name Base must be referenced fully-qualified, never imported:\n" + source);
        assertTrue(source.contains("extends com.p1.complex.Base"),
                "the extends clause carries the FQN under contention:\n" + source);
        assertTrue(source.contains("com.p2.complex.Base"),
                "the nav target FQN is printed:\n" + source);

        assertTrue(compiles(out), "contested complex base must generate compilable code");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static CsdlModel.EntityTypeModel entity(String name, String baseType) {
        return new CsdlModel.EntityTypeModel(name, baseType,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
    }

    private static CsdlModel.ComplexTypeModel complex(String name) {
        return new CsdlModel.ComplexTypeModel(name, null, false, false,
                List.of(new CsdlModel.PropertyModel("Value", "Edm.String", true, null, List.of())),
                List.of());
    }

    private static CsdlModel model(CsdlModel.SchemaModel... schemas) {
        return new CsdlModel(List.of(schemas), List.of());
    }

    private boolean compiles(Path out) throws Exception {
        List<File> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(out)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(p.toFile()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
