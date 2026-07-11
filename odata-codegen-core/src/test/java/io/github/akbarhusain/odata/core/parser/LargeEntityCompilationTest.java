package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Generator;
import io.github.akbarhusain.odata.core.model.CsdlModel;
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
        List<File> cp = findClasspathJars();
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

    private List<File> findClasspathJars() {
        String userHome = System.getProperty("user.home");
        Path mavenRepo = Path.of(userHome, ".m2", "repository");
        List<String> ids = List.of("odata-codegen-runtime","jackson-databind","jackson-core","jackson-annotations","jackson-datatype-jdk8","jackson-datatype-jsr310","jackson-module-parameter-names","slf4j-api");
        List<File> cp = ids.stream().map(id -> { try (Stream<Path> p = Files.walk(mavenRepo)) { return p.filter(x -> x.getFileName().toString().contains(id) && x.toString().endsWith(".jar") && !x.toString().contains("-sources") && !x.toString().contains("-javadoc") && (x.toString().contains("0.1.0-SNAPSHOT") || !id.equals("odata-codegen-runtime"))).findFirst().orElse(null); } catch (Exception e) { return null; } }).filter(java.util.Objects::nonNull).map(Path::toFile).collect(java.util.ArrayList::new, java.util.ArrayList::add, java.util.ArrayList::addAll);
        Path sc = Path.of("..", "odata-codegen-runtime", "target", "classes");
        if (Files.isReadable(sc)) cp.add(0, sc.toFile());
        return cp;
    }
}
