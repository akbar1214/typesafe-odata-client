package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.generator.CompilationHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LargeEntityCompilationTest {

    @Test
    void wideEntityCompiles(@TempDir Path tempDir) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/large-entity-metadata.xml")) {
            model = parser.parse(is);
        }
        Generator generator = new Generator(tempDir, Map.of("BigModel", "com.big"));
        generator.generate(model);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        StringWriter out = new StringWriter();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        List<File> cp = CompilationHarness.findClasspathJars();
        fm.setLocation(javax.tools.StandardLocation.CLASS_PATH, cp);
        List<File> javaFiles;
        try (Stream<Path> paths = Files.walk(tempDir)) {
            javaFiles = paths.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList();
        }
        var task = compiler.getTask(out, fm, null, List.of(
                "-d", tempDir.resolve("classes").toString(),
                "-classpath", cp.stream().map(File::getAbsolutePath).collect(java.util.stream.Collectors.joining(File.pathSeparator)),
                "-sourcepath", tempDir.toString()
        ), null, fm.getJavaFileObjects(javaFiles.toArray(new File[0])));
        boolean ok = task.call();
        if (!ok) System.out.println("COMPILATION ERRORS:\n" + out);
        assertTrue(ok, "Wide entity should compile. Errors:\n" + out);
    }

}
