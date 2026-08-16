package io.github.akbarhusain.odata.core.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H3: @JsonProperty setters were emitted only for concrete types, so a concrete
 * subtype of an abstract base had no setter anywhere for the base's own properties
 * and Jackson silently dropped them (FAIL_ON_UNKNOWN_PROPERTIES=false). These tests
 * compile generated abstract hierarchies and prove the base properties round-trip.
 */
class AbstractHierarchyDeserializationTest {

    private static final String NAMESPACE = "Test.Models";
    private static final String BASE_PACKAGE = "com.example.test";

    private CsdlModel.SchemaModel parseSchema(String resource) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = AbstractHierarchyDeserializationTest.class.getResourceAsStream(resource)) {
            assertNotNull(is, resource + " not found on classpath");
            CsdlModel model = parser.parse(is);
            return model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst().orElseThrow();
        }
    }

    /** Compiles generated sources and returns a loader for the compiled classes. */
    private URLClassLoader compileAndLoad(Path tempDir, List<Path> sources, String pkgDir) throws Exception {
        Path srcDir = tempDir.resolve("src").resolve(pkgDir);
        Files.createDirectories(srcDir);
        List<File> units = new ArrayList<>();
        for (Path source : sources) {
            Path target = srcDir.resolve(source.getFileName().toString());
            Files.writeString(target, Files.readString(source));
            units.add(target.toFile());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler not available - run with a JDK");

        List<File> classpath = new ArrayList<>();
        Path siblingClasses = Path.of("target", "..", "odata-codegen-runtime", "target", "classes").normalize();
        if (Files.isReadable(siblingClasses)) {
            classpath.add(siblingClasses.toFile());
        }
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        try (Stream<Path> jars = Files.walk(m2)) {
            jars.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("-sources") && !p.toString().contains("-javadoc"))
                    .map(Path::toFile)
                    .forEach(classpath::add);
        }

        StringWriter out = new StringWriter();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        fm.setLocation(StandardLocation.CLASS_PATH, classpath);
        Path classesOut = tempDir.resolve("classes");
        Files.createDirectories(classesOut);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesOut.toFile()));

        JavaCompiler.CompilationTask task = compiler.getTask(
                new PrintWriter(out), fm, null, null, null,
                fm.getJavaFileObjects(units.toArray(File[]::new)));
        assertTrue(task.call(), "Generated abstract hierarchy must compile. Errors:\n" + out);

        return new URLClassLoader(new URL[]{classesOut.toUri().toURL()},
                AbstractHierarchyDeserializationTest.class.getClassLoader());
    }

    private static Object get(Object entity, String getter) throws Exception {
        Object result = entity.getClass().getMethod(getter).invoke(entity);
        return result instanceof Optional<?> opt ? opt.orElse(null) : result;
    }

    @Test
    void abstractEntityBasePropertiesSurviveDeserialization(@TempDir Path tempDir) throws Exception {
        CsdlModel.SchemaModel schema = parseSchema("/abstract-entity-metadata.xml");
        CsdlModel.EntityTypeModel animal = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Animal")).findFirst().orElseThrow();
        CsdlModel.EntityTypeModel cat = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Cat")).findFirst().orElseThrow();

        EntityGenerator gen = new EntityGenerator(BASE_PACKAGE);
        gen.setGenerateWithMethods(true);
        Path animalSrc = tempDir.resolve("Animal.java");
        Path catSrc = tempDir.resolve("Cat.java");
        Files.writeString(animalSrc, gen.generate(animal, schema));
        Files.writeString(catSrc, gen.generate(cat, schema));

        try (URLClassLoader loader = compileAndLoad(tempDir, List.of(animalSrc, catSrc), "com/example/test/entity")) {
            Class<?> catClass = Class.forName(BASE_PACKAGE + ".entity.Cat", true, loader);
            Object instance = new ObjectMapper().readValue(
                    "{\"Id\":1,\"Name\":\"Tom\",\"Species\":\"Felis catus\",\"LivesIndoors\":true}", catClass);

            assertEquals(1, get(instance, "getId"), "base Id (non-nullable) must be populated");
            assertEquals("Tom", get(instance, "getName"),
                    "base Name declared on the ABSTRACT Animal must not be silently dropped");
            assertEquals("Felis catus", get(instance, "getSpecies"),
                    "base Species declared on the ABSTRACT Animal must not be silently dropped");
            assertEquals(true, get(instance, "getLivesIndoors"), "own Cat property must be populated");
        }
    }

    @Test
    void abstractComplexBasePropertiesSurviveDeserialization(@TempDir Path tempDir) throws Exception {
        CsdlModel.SchemaModel schema = parseSchema("/abstract-complex-metadata.xml");
        CsdlModel.ComplexTypeModel shape = schema.complexTypes().stream()
                .filter(c -> c.name().equals("Shape")).findFirst().orElseThrow();
        CsdlModel.ComplexTypeModel circle = schema.complexTypes().stream()
                .filter(c -> c.name().equals("Circle")).findFirst().orElseThrow();

        ComplexTypeGenerator gen = new ComplexTypeGenerator(BASE_PACKAGE);
        Path shapeSrc = tempDir.resolve("Shape.java");
        Path circleSrc = tempDir.resolve("Circle.java");
        Files.writeString(shapeSrc, gen.generate(shape, schema));
        Files.writeString(circleSrc, gen.generate(circle, schema));

        try (URLClassLoader loader = compileAndLoad(tempDir, List.of(shapeSrc, circleSrc), "com/example/test/complex")) {
            Class<?> circleClass = Class.forName(BASE_PACKAGE + ".complex.Circle", true, loader);
            Object instance = new ObjectMapper().readValue(
                    "{\"Name\":\"Unit circle\",\"Radius\":1.5}", circleClass);

            assertEquals("Unit circle", get(instance, "getName"),
                    "base Name declared on the ABSTRACT Shape must not be silently dropped");
            assertEquals(1.5, get(instance, "getRadius"), "own Circle property must be populated");
        }
    }
}
