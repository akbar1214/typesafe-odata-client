package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedDynamicPropertyContractTest {

    @Test
    void openComplexTypeExposesSafeDynamicUpdateAndPresenceAwareRead(@TempDir Path tempDir) throws Exception {
        CsdlModel model;
        try (InputStream input = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(input);
        }
        Path output = tempDir.resolve("generated");
        new Generator(output, Map.of(), "com.example").withGenerateWithMethods(true).generate(model);
        assertNull(CompilationHarness.compileAll(output));

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{output.resolve(".classes").toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> location = Class.forName("com.example.complex.Location", true, loader);
            Object instance = location.getDeclaredConstructor().newInstance();
            var setter = location.getMethod("setDynamicProperty", String.class, Object.class);
            var present = location.getMethod("hasDynamicProperty", String.class);
            var getter = location.getMethod("getDynamicProperty", String.class);

            setter.invoke(instance, "Nullable", null);
            assertTrue((Boolean) present.invoke(instance, "Nullable"));
            assertTrue(((java.util.Optional<?>) getter.invoke(instance, "Nullable")).isEmpty());
            assertTrue(((Map<?, ?>) location.getMethod("getUnmappedFields").invoke(instance))
                    .containsKey("Nullable"));

            setter.invoke(instance, "Nullable", "present");
            assertEquals("present", ((java.util.Optional<?>) getter.invoke(instance, "Nullable")).orElseThrow());
        }
    }
}
