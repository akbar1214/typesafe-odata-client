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
            // Pin class output under the generation root: javac's unpinned default is
            // compiler/CWD-dependent, and stray .class trees must never land in the repo.
            // Lives inside the caller's @TempDir so JUnit cleans it up.
            Path classes = root.resolve(".classes");
            Files.createDirectories(classes);
            fileManager.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
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

    // Resolving a fallback artifact walks the whole ~/.m2 tree — cache per JVM (surefire
    // runs many referee tests in one fork; without the cache every miss re-walks).
    private static final java.util.concurrent.ConcurrentHashMap<String, File> JAR_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Sentinel for a not-found artifact: ConcurrentHashMap rejects null values, and a
     *  miss must be cacheable or every referee test re-walks ~/.m2 (the sentinel is
     *  never a real path). Absent artifacts are EXPECTED — e.g. the runtime snapshot
     *  on a fresh checkout, where the sibling target/classes entry carries the load. */
    private static final File MISSING = new File("<absent-artifact>");

    /**
     * The dependency files every generated client is compiled against, resolved from this
     * JVM's own classpath so the versions match the build's dependency management exactly.
     *
     * <p>A {@code ~/.m2} directory walk cannot promise that: it returns whichever matching
     * file the filesystem reaches first — a different version of the same artifact, or a
     * shaded copy from an unrelated dependency (e.g. the AWS SDK's
     * {@code software/amazon/awssdk/third-party-jackson-core} matches
     * {@code "jackson-core"}). The anchors below load from the exact files Surefire put on
     * the test classpath; the {@code ~/.m2} walk remains only as a fallback for anchors
     * whose code source is unavailable.
     *
     * <p>Public so that compile-AND-LOAD tests (which need the same files on the classpath
     * to load what they compiled) reuse this instead of rebuilding their own classpath.
     */
    public static List<File> findClasspathJars() {
        List<File> classpath = new ArrayList<>();
        for (Anchor anchor : CLASSPATH_ANCHORS) {
            File dependency = jarOf(anchor.type());
            if (dependency == null) {
                dependency = cachedM2Jar(anchor.artifactId());
            }
            if (dependency != null && !classpath.contains(dependency)) {
                classpath.add(dependency);
            }
        }
        // Current reactor runtime FIRST — the resolved class source may be the installed
        // snapshot, while the sibling reactor output is the freshest when present.
        Path siblingClasses = Path.of("..", "odata-codegen-runtime", "target", "classes");
        File runtime = Files.isReadable(siblingClasses)
                ? siblingClasses.toFile()
                : jarOf(io.github.akbarhusain.odata.runtime.entity.ODataType.class);
        if (runtime != null) {
            classpath.remove(runtime);
            classpath.add(0, runtime);
        }
        return classpath;
    }

    /** A class whose loaded jar is the deterministic source for an artifact. */
    private record Anchor(Class<?> type, String artifactId) {
    }

    private static final List<Anchor> CLASSPATH_ANCHORS = List.of(
            new Anchor(com.fasterxml.jackson.annotation.JsonProperty.class, "jackson-annotations"),
            new Anchor(com.fasterxml.jackson.databind.ObjectMapper.class, "jackson-databind"),
            new Anchor(com.fasterxml.jackson.core.JsonParser.class, "jackson-core"),
            new Anchor(com.fasterxml.jackson.datatype.jdk8.Jdk8Module.class, "jackson-datatype-jdk8"),
            new Anchor(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule.class, "jackson-datatype-jsr310"),
            new Anchor(com.fasterxml.jackson.module.paramnames.ParameterNamesModule.class,
                    "jackson-module-parameter-names"),
            new Anchor(org.slf4j.LoggerFactory.class, "slf4j-api"));

    /** The jar (or class directory) this JVM loaded {@code type} from, or null. */
    private static File jarOf(Class<?> type) {
        try {
            java.security.CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            File location = new File(codeSource.getLocation().toURI());
            return location.exists() ? location : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static File cachedM2Jar(String artifactId) {
        File cached = JAR_CACHE.get(artifactId);
        if (cached == null) {
            Path mavenRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
            Path jar = findJar(mavenRepo, artifactId);
            cached = jar != null ? jar.toFile() : MISSING;
            JAR_CACHE.put(artifactId, cached);
        }
        return cached == MISSING ? null : cached;
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
