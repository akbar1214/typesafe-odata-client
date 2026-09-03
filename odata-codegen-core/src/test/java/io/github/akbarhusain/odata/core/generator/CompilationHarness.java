package io.github.akbarhusain.odata.core.generator;

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
import java.util.stream.Stream;

/**
 * Shared javac referee for generated-output compilation tests (lesson 120:
 * compile-or-it-didn't-happen — content assertions stay green while the compile
 * breaks). Compiles every {@code .java} file under a generation root against the
 * sibling reactor runtime plus the jars the generated client depends on.
 *
 * <p>Extracted from the per-test copies (CrossSchemaSimpleNameCompilationTest and
 * friends); new compilation tests should use this instead of copying the harness.
 */
public final class CompilationHarness {

    private CompilationHarness() {}

    /**
     * Compiles every {@code .java} file under {@code root}.
     *
     * @return {@code null} when everything compiled, otherwise the compiler output
     */
    public static String compileAll(Path root) {
        List<File> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(p.toFile()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to collect generated sources under " + root, e);
        }
        if (javaFiles.isEmpty()) {
            return "no generated sources found under " + root;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return "no system Java compiler available";
        }
        StringWriter compilerOutput = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
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
            return success ? null : compilerOutput.toString();
        } catch (Exception e) {
            return "compiler setup failed: " + e;
        }
    }

    /** Convenience for assert-style callers: {@code assertTrue(CompilationHarness.compiles(out))}. */
    public static boolean compiles(Path root) {
        return compileAll(root) == null;
    }

    // Resolving an artifact walks the whole ~/.m2 tree — cache per JVM (surefire runs
    // many referee tests in one fork; without the cache every test re-walks)
    private static final java.util.concurrent.ConcurrentHashMap<String, File> JAR_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static List<File> findClasspathJars() {
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
            if (JAR_CACHE.containsKey(id)) {
                File cached = JAR_CACHE.get(id);
                if (cached != null) {
                    classpath.add(cached);
                }
                continue;
            }
            Path jar = findJar(mavenRepo, id);
            File file = jar != null ? jar.toFile() : null;
            JAR_CACHE.put(id, file);
            if (file != null) {
                classpath.add(file);
            }
        }
        // current reactor runtime FIRST — the ~/.m2 snapshot may predate new runtime types
        Path siblingClasses = Path.of("..", "odata-codegen-runtime", "target", "classes");
        if (Files.isReadable(siblingClasses)) {
            classpath.add(0, siblingClasses.toFile());
        }
        return classpath;
    }

    private static Path findJar(Path mavenRepo, String artifactId) {
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
