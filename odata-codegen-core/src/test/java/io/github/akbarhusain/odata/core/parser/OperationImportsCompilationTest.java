package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.generator.CompilationHarness;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: the FULL TripPin client — including the two generated operation-request
 * classes (GetNearestAirportFunctionRequest, ResetDataSourceActionRequest) and the
 * container accessors that reference them — must compile against the runtime.
 */
class OperationImportsCompilationTest {

    @Test
    void generatedOperationRequestsCompileWithFullClient(@TempDir Path tempDir) throws Exception {
        CsdlModel model = parse("/trippin-metadata.xml");
        new Generator(tempDir,
                Map.of("Microsoft.OData.SampleService.Models.TripPin", "com.example.trippin"),
                "com.example.trippin").generate(model);

        Path opPkg = tempDir.resolve("com/example/trippin/operation");
        assertTrue(Files.exists(opPkg.resolve("GetNearestAirportFunctionRequest.java")));
        assertTrue(Files.exists(opPkg.resolve("ResetDataSourceActionRequest.java")));

        List<File> javaFiles = collectJavaFiles(tempDir);
        assertFalse(javaFiles.isEmpty());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        StringWriter compilerOutput = new StringWriter();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<File> classpath = CompilationHarness.findClasspathJars();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);

        Iterable<? extends JavaFileObject> units =
                fileManager.getJavaFileObjects(javaFiles.toArray(new File[0]));
        List<String> options = List.of(
                "-d", tempDir.resolve("classes").toString(),
                "-classpath", classpath.stream().map(File::getAbsolutePath)
                        .reduce((a, b) -> a + File.pathSeparator + b).orElse("")
        );
        JavaCompiler.CompilationTask task = compiler.getTask(
                new PrintWriter(compilerOutput), fileManager, null, options, null, units);
        boolean success = task.call();
        assertTrue(success, "Generated client with operation imports must compile. Errors:\n"
                + compilerOutput);
    }

    private static CsdlModel parse(String resource) {
        try (InputStream is = OperationImportsCompilationTest.class.getResourceAsStream(resource)) {
            return new io.github.akbarhusain.odata.core.parser.StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<File> collectJavaFiles(Path root) throws Exception {
        List<File> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
        }
        return files;
    }

}
