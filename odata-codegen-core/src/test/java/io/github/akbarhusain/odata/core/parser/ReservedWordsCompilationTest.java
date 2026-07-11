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
 * Tests that properties whose CSDL names are Java reserved words
 * (e.g. "class", "new", "int", "return") generate valid Java identifiers.
 */
class ReservedWordsCompilationTest {

    static CsdlModel reservedModel;

    @BeforeAll
    static void parseMetadata() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = ReservedWordsCompilationTest.class
                .getResourceAsStream("/reserved-words-metadata.xml")) {
            reservedModel = parser.parse(is);
        }
    }

    @Test
    void reservedWordPropertyNamesCompile(@TempDir Path tempDir) throws Exception {
        Generator generator = new Generator(tempDir,
                Map.of("ReservedWords", "com.example.reserved",
                       "ReservedWords.Shared", "com.example.reserved.shared"));
        generator.generate(reservedModel);

        // Verify field names are sanitized (reserved word + "_")
        File entityFile = tempDir.resolve("com/example/reserved/entity/Entity.java").toFile();
        assertTrue(entityFile.exists(), "Entity.java should be generated");
        String code = Files.readString(entityFile.toPath());

        // "class" field should become "class_" (not "class")
        assertTrue(code.contains("String class_;"),
                "Reserved word 'class' should become 'class_' field. Got:\n" + code);
        assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty(\"class\")"),
                "@JsonProperty should preserve the original CSDL name 'class'");

        // Verify Object entity generates as Object_ (not Object, which collides with java.lang.Object)
        File objectFile = tempDir.resolve("com/example/reserved/entity/Object_.java").toFile();
        assertTrue(objectFile.exists(),
                "Entity type 'Object' should produce Object_.java (not Object.java which collides with java.lang.Object)");
        File objectFileBad = tempDir.resolve("com/example/reserved/entity/Object.java").toFile();
        assertFalse(objectFileBad.exists(),
                "Object.java should NOT be generated (collides with java.lang.Object)");

        // Verify cross-namespace import uses the correct package (not Names.toPackageName fallback)
        String entityCode = Files.readString(entityFile.toPath());
        assertTrue(entityCode.contains("import com.example.reserved.shared.complex.SharedAddress"),
                "Cross-namespace import should use schemaPackages mapping. Got:\n" + entityCode);

        // Compile to verify
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

        assertTrue(success, "Code with reserved-word property names should compile. Errors:\n" + output);
    }

    @Test
    void reservedWordFieldNamesAreSanitized(@TempDir Path tempDir) throws Exception {
        Generator generator = new Generator(tempDir,
                Map.of("ReservedWords", "com.example.reserved"));
        generator.generate(reservedModel);

        String code = Files.readString(
                tempDir.resolve("com/example/reserved/entity/Entity.java"));

        // Every reserved word should become "<word>_" as a field name
        String[] reservedWords = {
                "class", "new", "return", "package", "import",
                "abstract", "interface", "try", "catch", "throw",
                "switch", "for", "while", "if", "else",
                "final", "static", "void", "private", "public",
                "protected", "this", "super", "extends", "implements",
                "instanceof", "var", "record", "sealed", "permits",
                "yield", "with", "open", "to", "module",
                "requires", "exports", "native"
        };

        for (String word : reservedWords) {
            String fieldName = word + "_";
            assertTrue(code.contains(" " + fieldName + ";"),
                    "Reserved word '" + word + "' should produce field '" + fieldName + "'");
            // The @JsonProperty should preserve the original name
            assertTrue(code.contains("@com.fasterxml.jackson.annotation.JsonProperty(\"" + word + "\")"),
                    "@JsonProperty for '" + word + "' should use original CSDL name");
        }

        // Verify Object entity class name is sanitized to avoid java.lang.Object collision
        File objectFile = tempDir.resolve("com/example/reserved/entity/Object_.java").toFile();
        assertTrue(objectFile.exists(),
                "Entity type 'Object' should produce Object_.java");
        String objectCode = Files.readString(objectFile.toPath());
        assertTrue(objectCode.contains("public final class Object_"),
                "Class name should be Object_ not Object. Got:\n" + objectCode);
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
