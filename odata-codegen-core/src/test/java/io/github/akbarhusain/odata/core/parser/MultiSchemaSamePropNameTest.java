package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
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
 * Tests that when two schemas have properties with the same name but different types,
 * the generated code uses the correct import for each.
 */
class MultiSchemaSamePropNameTest {

    static CsdlModel model;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = MultiSchemaSamePropNameTest.class
                .getResourceAsStream("/multi-schema-same-prop-name-metadata.xml")) {
            model = parser.parse(is);
        }
    }

    @Test
    void samePropertyNameDifferentTypesGetCorrectImports(@TempDir Path tempDir) throws Exception {
        Generator generator = new Generator(tempDir, Map.of(
                "Dup.Main", "com.example.main",
                "Dup.Other", "com.example.other"
        ));
        generator.generate(model);

        // Person (Main) "Address" property is HomeAddress
        File personFile = tempDir.resolve("com/example/main/entity/Person.java").toFile();
        assertTrue(personFile.exists(), "Person.java should exist");
        String personCode = Files.readString(personFile.toPath());

        assertTrue(personCode.contains("import com.example.main.complex.HomeAddress"),
                "Person must import HomeAddress from Main package. Got:\n" + personCode);

        // Company (Other) "Address" property is OfficeAddress
        File companyFile = tempDir.resolve("com/example/other/entity/Company.java").toFile();
        assertTrue(companyFile.exists(), "Company.java should exist");
        String companyCode = Files.readString(companyFile.toPath());

        assertTrue(companyCode.contains("import com.example.other.complex.OfficeAddress"),
                "Company must import OfficeAddress from Other package. Got:\n" + companyCode);
    }

    @Test
    void samePropertyNameDifferentTypesCompile(@TempDir Path tempDir) throws Exception {
        Generator generator = new Generator(tempDir, Map.of(
                "Dup.Main", "com.example.main",
                "Dup.Other", "com.example.other"
        ));
        generator.generate(model);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler not available");

        StringWriter compilerOutput = new StringWriter();
        PrintWriter compilerWriter = new PrintWriter(compilerOutput);

        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<File> classpath = findClasspathJars();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);

        List<File> javaFiles;
        try (Stream<Path> paths = Files.walk(tempDir)) {
            javaFiles = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .toList();
        }

        Iterable<? extends JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjects(javaFiles.toArray(new File[0]));

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

        if (!success) {
            System.err.println("Compilation output:\n" + output);
        }

        assertTrue(success, "Code with same-name properties across schemas should compile. Errors:\n" + output);
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
