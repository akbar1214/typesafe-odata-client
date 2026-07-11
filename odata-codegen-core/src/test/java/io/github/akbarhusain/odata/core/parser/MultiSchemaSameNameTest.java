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
 * Tests that when two schemas have types with the same simple name (e.g. both have "Address"),
 * the generated code uses the correct import for each schema's type.
 */
class MultiSchemaSameNameTest {

    static CsdlModel model;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = MultiSchemaSameNameTest.class
                .getResourceAsStream("/multi-schema-same-name-metadata.xml")) {
            model = parser.parse(is);
        }
    }

    @Test
    void sameNameComplexTypesGetCorrectImports(@TempDir Path tempDir) throws Exception {
        Generator generator = new Generator(tempDir, Map.of(
                "MultiSchema.OrgA", "com.example.orga",
                "MultiSchema.OrgB", "com.example.orgb"
        ));
        generator.generate(model);

        // Person (OrgA) should import OrgA's Address, not OrgB's
        File personFile = tempDir.resolve("com/example/orga/entity/Person.java").toFile();
        assertTrue(personFile.exists(), "Person.java should exist");
        String personCode = Files.readString(personFile.toPath());

        assertTrue(personCode.contains("import com.example.orga.complex.Address"),
                "Person must import OrgA.Address, not OrgB.Address. Got:\n" + personCode);
        assertFalse(personCode.contains("import com.example.orgb.complex.Address"),
                "Person must NOT import OrgB.Address. Got:\n" + personCode);

        // Company (OrgB) should import OrgB's Address, not OrgA's
        File companyFile = tempDir.resolve("com/example/orgb/entity/Company.java").toFile();
        assertTrue(companyFile.exists(), "Company.java should exist");
        String companyCode = Files.readString(companyFile.toPath());

        assertTrue(companyCode.contains("import com.example.orgb.complex.Address"),
                "Company must import OrgB.Address, not OrgA.Address. Got:\n" + companyCode);
        assertFalse(companyCode.contains("import com.example.orga.complex.Address"),
                "Company must NOT import OrgA.Address. Got:\n" + companyCode);
    }

    @Test
    void sameNameComplexTypesCompile(@TempDir Path tempDir) throws Exception {
        Generator generator = new Generator(tempDir, Map.of(
                "MultiSchema.OrgA", "com.example.orga",
                "MultiSchema.OrgB", "com.example.orgb"
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

        assertTrue(success, "Code with same-name types across schemas should compile. Errors:\n" + output);
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
