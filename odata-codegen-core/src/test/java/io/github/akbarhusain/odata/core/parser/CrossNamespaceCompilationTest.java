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
 * P0-3/P0-4/P0-5: Cross-namespace references, TypeDefinition, and Int64 enum.
 * Tests that generated code compiles when:
 * - A type references a complex type from a different schema/namespace
 * - A property uses a TypeDefinition (resolved to underlying type)
 * - An enum has Int64 member values exceeding Integer.MAX_VALUE
 */
class CrossNamespaceCompilationTest {

    static CsdlModel crossNsModel;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = CrossNamespaceCompilationTest.class
                .getResourceAsStream("/cross-namespace-metadata.xml")) {
            crossNsModel = parser.parse(is);
        }
    }

    @Test
    void crossNamespaceCodeCompilesAgainstRuntime(@TempDir Path tempDir) throws Exception {
        Map<String, String> schemaPackages = Map.of(
                "CrossNs.Main", "com.example.crossns.main",
                "CrossNs.Shared", "com.example.crossns.shared"
        );

        Generator generator = new Generator(tempDir, schemaPackages);
        generator.generate(crossNsModel);

        // Find all generated Java files
        List<File> javaFiles;
        try (Stream<Path> paths = Files.walk(tempDir)) {
            javaFiles = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .toList();
        }

        assertFalse(javaFiles.isEmpty(), "Should have generated Java files");

        // Verify Person.java imports Address from the correct namespace package
        File personFile = javaFiles.stream()
                .filter(f -> f.getName().equals("Person.java"))
                .findFirst()
                .orElseThrow();
        String personCode = Files.readString(personFile.toPath());

        assertTrue(personCode.contains("import com.example.crossns.shared.complex.Address"),
                "Person must import Address from CrossNs.Shared package, not CrossNs.Main. Got:\n" + personCode);

        // Verify PersonEntityRequest imports the cross-namespace nav target from the correct package
        File personRequestFile = javaFiles.stream()
                .filter(f -> f.getName().equals("PersonEntityRequest.java"))
                .findFirst()
                .orElseThrow();
        String personRequestCode = Files.readString(personRequestFile.toPath());

        assertTrue(personRequestCode.contains("import com.example.crossns.shared.entity.request.SharedTagEntityRequest"),
                "PersonEntityRequest must import SharedTagEntityRequest from CrossNs.Shared package, not CrossNs.Main. Got:\n" + personRequestCode);

        // Verify the TypeDefinition filter constant resolves to StringProperty
        assertTrue(personCode.contains("CONTACT_EMAIL")
                        && personCode.contains("new StringProperty<>(\"ContactEmail\""),
                "TypeDefinition property 'ContactEmail' should emit a StringProperty filter constant. Got:\n" + personCode);

        // Verify TypeDefinition "Email" resolves to String
        assertTrue(personCode.contains("String contactEmail"),
                "TypeDefinition 'Email' (underlying Edm.String) should resolve to String field. Got:\n" + personCode);
        assertFalse(personCode.contains("Email contactEmail"),
                "TypeDefinition 'Email' should NOT remain as a class reference. Got:\n" + personCode);

        // Verify Int64 enum compiles (Huge value exceeds Integer.MAX_VALUE)
        File bigFlagsFile = javaFiles.stream()
                .filter(f -> f.getName().equals("BigFlags.java"))
                .findFirst()
                .orElseThrow();
        String bigFlagsCode = Files.readString(bigFlagsFile.toPath());

        assertTrue(bigFlagsCode.contains("Huge(1099511627776L)"),
                "Int64 enum value 1099511627776 should use long literal. Got:\n" + bigFlagsCode);

        // Compile all generated code
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler not available");

        StringWriter compilerOutput = new StringWriter();
        PrintWriter compilerWriter = new PrintWriter(compilerOutput);

        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<File> classpath = findClasspathJars();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);

        Path sourceOutput = tempDir.resolve("generated-classes");
        Files.createDirectories(sourceOutput);
        fileManager.setLocation(javax.tools.StandardLocation.SOURCE_OUTPUT,
                List.of(sourceOutput.toFile()));

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

        assertTrue(success, "Cross-namespace + TypeDefinition + Int64 enum code should compile. Errors:\n" + output);
    }

    @Test
    void basePackageUsedAsFallbackWhenSchemaPackagesEmpty(@TempDir Path tempDir) throws Exception {
        // No schemaPackages, but basePackage is set — all schemas should use basePackage
        Generator generator = new Generator(tempDir, Map.of(), "com.example.fallback");
        generator.generate(crossNsModel);

        // CrossNs.Main entities should be under com.example.fallback.entity
        assertTrue(Files.exists(tempDir.resolve("com/example/fallback/entity/Person.java")),
                "Person should be under com.example.fallback.entity when basePackage is the fallback");

        // CrossNs.Shared types should also be under com.example.fallback (same fallback for all)
        assertTrue(Files.exists(tempDir.resolve("com/example/fallback/complex/Address.java")),
                "Address (CrossNs.Shared) should be under com.example.fallback.complex when basePackage is the fallback");
        assertTrue(Files.exists(tempDir.resolve("com/example/fallback/entity/SharedTag.java")),
                "SharedTag (CrossNs.Shared) should be under com.example.fallback.entity when basePackage is the fallback");
    }

    @Test
    void schemaPackageOverridesBasePackage(@TempDir Path tempDir) throws Exception {
        // basePackage = com.example.fallback, but explicit mapping for CrossNs.Shared
        Map<String, String> schemaPackages = Map.of(
                "CrossNs.Shared", "com.example.shared"
        );
        Generator generator = new Generator(tempDir, schemaPackages, "com.example.fallback");
        generator.generate(crossNsModel);

        // CrossNs.Main (not in map) falls back to basePackage
        assertTrue(Files.exists(tempDir.resolve("com/example/fallback/entity/Person.java")),
                "Person (CrossNs.Main) should use basePackage fallback");

        // CrossNs.Shared (in map) uses the explicit mapping
        assertTrue(Files.exists(tempDir.resolve("com/example/shared/complex/Address.java")),
                "Address (CrossNs.Shared) should use the explicit schemaPackages mapping");
        assertTrue(Files.exists(tempDir.resolve("com/example/shared/entity/SharedTag.java")),
                "SharedTag (CrossNs.Shared) should use the explicit schemaPackages mapping");
        assertFalse(Files.exists(tempDir.resolve("com/example/fallback/complex/Address.java")),
                "Address should NOT be under basePackage when schemaPackages has an explicit mapping");
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
