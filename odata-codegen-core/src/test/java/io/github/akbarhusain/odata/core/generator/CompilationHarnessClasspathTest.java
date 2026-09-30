package io.github.akbarhusain.odata.core.generator;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classpath the javac referee compiles against must be resolved from the artifacts THIS
 * JVM loaded — not from whichever matching file a {@code ~/.m2} directory walk reaches first.
 * On the review machine the walk picked {@code jackson-databind} 2.13.4.2 (the POM pins
 * 2.17.0), {@code jackson-annotations} 2.18.2, and the AWS SDK's shaded
 * {@code third-party-jackson-core} in place of {@code jackson-core}.
 *
 * <p>This pins the mechanism: reverting to the walk would put a different file for the anchor
 * artifacts on the classpath (on this machine, a different version), failing the containment
 * check. It cannot make the walk itself deterministic — that is enforced by construction.
 */
class CompilationHarnessClasspathTest {

    @Test
    void classpathUsesTheLoadedArtifactLocations() throws Exception {
        List<File> classpath = CompilationHarness.findClasspathJars();
        for (Class<?> anchor : List.of(
                com.fasterxml.jackson.annotation.JsonProperty.class,
                com.fasterxml.jackson.databind.ObjectMapper.class,
                com.fasterxml.jackson.core.JsonParser.class)) {
            File loadedFrom = new File(
                    anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertTrue(classpath.contains(loadedFrom),
                    anchor.getSimpleName() + " must be compiled against the file it is loaded from: "
                            + loadedFrom);
        }
        assertFalse(classpath.stream().anyMatch(f -> f.getPath().contains("third-party-jackson-core")),
                "the AWS SDK's shaded jackson-core must never be on the referee classpath: " + classpath);
    }
}
