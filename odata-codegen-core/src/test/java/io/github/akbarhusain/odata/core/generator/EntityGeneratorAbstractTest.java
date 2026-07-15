package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract entity types (Abstract="true") cannot be instantiated, so the generator must
 * NOT emit copy-on-write with*() methods (which would call `new AbstractX(...)` and fail
 * to compile). This was a latent defect (lesson 53) — no real metadata exercised it until now.
 */
class EntityGeneratorAbstractTest {

    private static final String NAMESPACE = "Test.Models";
    private static final String BASE_PACKAGE = "com.example.test";

    private CsdlModel.SchemaModel schema() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = EntityGeneratorAbstractTest.class
                .getResourceAsStream("/abstract-entity-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            return model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private String generate(String typeName) throws Exception {
        CsdlModel.SchemaModel schema = schema();
        CsdlModel.EntityTypeModel type = schema.entityTypes().stream()
                .filter(e -> e.name().equals(typeName))
                .findFirst()
                .orElseThrow();
        EntityGenerator gen = new EntityGenerator(BASE_PACKAGE);
        gen.setGenerateWithMethods(true);
        return gen.generate(type, schema);
    }

    @Test
    void abstractBaseDeclaresAbstractClassAndNoWithMethods() throws Exception {
        String animal = generate("Animal");

        assertTrue(animal.contains("public abstract class Animal"),
                "Abstract entity should be declared `public abstract class`");
        assertFalse(animal.contains("public Animal withName("),
                "Abstract entity must NOT generate with*() (would emit `new Animal(...)`)");
        assertFalse(animal.contains("public Animal withSpecies("),
                "Abstract entity must NOT generate with*()");
        // Still gets property constants and getters
        assertTrue(animal.contains("public static final NumberProperty<Animal, Integer> ID"),
                "Abstract entity should still expose property constants");
        assertTrue(animal.contains("public Optional<String> getName()"),
                "Abstract entity should still expose getters");
    }

    @Test
    void concreteSubtypeExtendsAbstractBaseAndHasWithMethods() throws Exception {
        String cat = generate("Cat");

        assertTrue(cat.contains("public class Cat extends Animal"),
                "Concrete subtype should extend the abstract base");
        assertFalse(cat.contains("abstract class Cat"),
                "Concrete subtype should not be abstract");
        assertTrue(cat.contains("public Cat withLivesIndoors(Boolean value)"),
                "Subtype should generate its own with*()");
        assertTrue(cat.contains("public Cat withName(String value)"),
                "Subtype should generate inherited with*()");
        // with* reconstructs the subtype (not the abstract base)
        assertTrue(cat.contains("new Cat("),
                "Subtype with*() should reconstruct the concrete subtype");
        assertFalse(cat.contains("new Animal("),
                "Subtype with*() must not construct the abstract base");
    }

    @Test
    void generatedAbstractHierarchyCompiles(@TempDir Path tempDir) throws Exception {
        String animal = generate("Animal");
        String cat = generate("Cat");

        Path pkgDir = tempDir.resolve("src").resolve("com").resolve("example").resolve("test");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Animal.java"), animal);
        Files.writeString(pkgDir.resolve("Cat.java"), cat);

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
        fm.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);
        Path classesOut = tempDir.resolve("classes");
        Files.createDirectories(classesOut);
        fm.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(classesOut.toFile()));

        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(
                pkgDir.resolve("Animal.java").toFile(),
                pkgDir.resolve("Cat.java").toFile());

        JavaCompiler.CompilationTask task = compiler.getTask(
                new PrintWriter(out), fm, null,
                List.of("-sourcepath", tempDir.resolve("src").toString()), null, units);
        boolean success = task.call();
        if (!success) {
            System.err.println("Compilation failed:\n" + out);
        }
        assertTrue(success, "Generated abstract entity hierarchy must compile. Errors:\n" + out);
    }
}
