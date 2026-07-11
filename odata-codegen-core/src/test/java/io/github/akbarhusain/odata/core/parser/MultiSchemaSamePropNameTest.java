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

    @Test
    void crossSchemaImportComplexTypeInProperty(@TempDir Path tempDir) throws Exception {
        StaxCsdlParser p = new StaxCsdlParser();
        CsdlModel crossModel;
        try (InputStream is = getClass().getResourceAsStream("/cross-schema-import-metadata.xml")) {
            crossModel = p.parse(is);
        }

        Generator generator = new Generator(tempDir, Map.of(
                "Shared", "com.shared",
                "CompanyNS", "com.company",
                "PersonNS", "com.person"
        ));
        generator.generate(crossModel);

        // Company (CompanyNS) has nav MainOffice of type Shared.Address and property HQ of type Shared.ContactInfo
        File companyFile = tempDir.resolve("com/company/entity/Company.java").toFile();
        assertTrue(companyFile.exists(), "Company.java should exist");
        String companyCode = Files.readString(companyFile.toPath());
        assertTrue(companyCode.contains("import com.shared.complex.ContactInfo"),
                "Company must import ContactInfo from Shared. Got:\n" + companyCode);
        assertTrue(companyCode.contains("import com.shared.complex.Address"),
                "Company must import Address from Shared (nav MainOffice is Shared.Address). Got:\n" + companyCode);

        // Person (PersonNS) has property Home of type Shared.Address — should import from com.shared.complex
        File personFile = tempDir.resolve("com/person/entity/Person.java").toFile();
        assertTrue(personFile.exists(), "Person.java should exist");
        String personCode = Files.readString(personFile.toPath());
        assertTrue(personCode.contains("import com.shared.complex.Address"),
                "Person must import Address from Shared. Got:\n" + personCode);
        assertTrue(personCode.contains("import com.shared.complex.ContactInfo"),
                "Person must import ContactInfo from Shared. Got:\n" + personCode);

        // Company request should NOT generate entity request for MainOffice nav (it's a complex type nav)
        File companyReqFile = tempDir.resolve("com/company/entity/request/CompanyEntityRequest.java").toFile();
        assertTrue(companyReqFile.exists(), "CompanyEntityRequest.java should exist");
        String companyReqCode = Files.readString(companyReqFile.toPath());
        assertFalse(companyReqCode.contains("AddressEntityRequest"),
                "CompanyEntityRequest must NOT reference AddressEntityRequest (Address is a complex type). Got:\n" + companyReqCode);

        // Person request should NOT generate entity request for Mailing nav (it's a complex type nav)
        File personReqFile = tempDir.resolve("com/person/entity/request/PersonEntityRequest.java").toFile();
        assertTrue(personReqFile.exists(), "PersonEntityRequest.java should exist");
        String personReqCode = Files.readString(personReqFile.toPath());
        assertFalse(personReqCode.contains("AddressEntityRequest"),
                "PersonEntityRequest must NOT reference AddressEntityRequest (Address is a complex type). Got:\n" + personReqCode);

        // Compile to verify everything
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        StringWriter compilerOutput = new StringWriter();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<File> classpath = findClasspathJars();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);
        List<File> javaFiles;
        try (Stream<Path> paths = Files.walk(tempDir)) {
            javaFiles = paths.filter(p2 -> p2.toString().endsWith(".java")).map(Path::toFile).toList();
        }
        List<String> options = List.of(
                "-d", tempDir.resolve("classes").toString(),
                "-classpath", classpath.stream().map(File::getAbsolutePath).collect(java.util.stream.Collectors.joining(File.pathSeparator)),
                "-sourcepath", tempDir.toString()
        );
        JavaCompiler.CompilationTask task = compiler.getTask(
                compilerOutput, fileManager, null, options, null,
                fileManager.getJavaFileObjects(javaFiles.toArray(new File[0])));
        boolean success = task.call();
        if (!success) {
            System.err.println("Cross-schema compilation errors:\n" + compilerOutput);
        }
        assertTrue(success, "Cross-schema imports should compile. Errors:\n" + compilerOutput);
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
