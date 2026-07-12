package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code select}, {@code orderBy}, and {@code expand} are narrowed
 * to the entity type: cross-entity property/navigation usage must fail to compile.
 */
class QueryTypeSafetyCompilationTest {

    static CsdlModel trippinModel;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = QueryTypeSafetyCompilationTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            trippinModel = parser.parse(is);
        }
    }

    @Test
    void crossEntitySelectOrderByExpandFailsToCompile(@TempDir Path tempDir) throws Exception {
        String namespace = "Microsoft.OData.SampleService.Models.TripPin";
        String basePackage = "com.example.trippin";

        Generator generator = new Generator(tempDir, Map.of(namespace, basePackage));
        generator.generate(trippinModel);

        List<File> javaFiles;
        try (Stream<Path> paths = Files.walk(tempDir)) {
            javaFiles = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .toList();
        }

        assertFalse(javaFiles.isEmpty(), "Should have generated Java files");

        // Add a source file that uses cross-entity properties / navs
        Path badSource = tempDir.resolve("BadQuery.java");
        Files.writeString(badSource, """
                import com.example.trippin.container.DefaultContainer;
                import com.example.trippin.entity.Person;
                import com.example.trippin.entity.Trip;
                import io.github.akbarhusain.odata.runtime.entity.Context;

                public class BadQuery {
                    public static void main(String[] args) {
                        Context ctx = Context.builder().baseUrl("https://example.org").build();
                        DefaultContainer client = new DefaultContainer(ctx);
                        client.people().select(Trip.NAME);
                        client.people().orderBy(Trip.BUDGET.desc());
                        client.people().expand(Trip.FLIGHTS);
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler not available - run with JDK not JRE");

        StringWriter compilerOutput = new StringWriter();
        PrintWriter compilerWriter = new PrintWriter(compilerOutput);
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        List<File> classpath = findClasspathJars();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);

        Path sourceOutput = tempDir.resolve("generated-classes");
        Files.createDirectories(sourceOutput);
        fileManager.setLocation(javax.tools.StandardLocation.SOURCE_OUTPUT,
                List.of(sourceOutput.toFile()));

        List<File> allSources = new java.util.ArrayList<>(javaFiles);
        allSources.add(badSource.toFile());

        Iterable<? extends javax.tools.JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjects(allSources.toArray(new File[0]));

        List<String> options = List.of(
                "-d", tempDir.resolve("classes").toString(),
                "-classpath", classpath.stream().map(File::getAbsolutePath)
                        .collect(java.util.stream.Collectors.joining(File.pathSeparator)),
                "-sourcepath", tempDir.toString()
        );

        JavaCompiler.CompilationTask task = compiler.getTask(
                compilerWriter, fileManager, null, options, null, compilationUnits);

        boolean success = task.call();
        String output = compilerOutput.toString();

        assertFalse(success, "Cross-entity select/orderBy/expand should fail to compile");
        assertTrue(output.contains("select") || output.contains("orderBy") || output.contains("expand"),
                "Compiler errors should mention the narrowed query methods. Output:\n" + output);
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
                "slf4j-api"
        );

        List<File> classpath = artifactIds.stream()
                .map(id -> findJar(mavenRepo, id))
                .filter(p -> p != null)
                .map(Path::toFile)
                .collect(java.util.ArrayList::new, java.util.ArrayList::add, java.util.ArrayList::addAll);

        Path siblingClasses = Path.of("..", "odata-codegen-runtime", "target", "classes");
        if (java.nio.file.Files.isReadable(siblingClasses)) {
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
