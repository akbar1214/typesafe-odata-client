package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTransactionTest {

    @Test
    void failedGenerationKeepsLastSuccessfulWrittenManifest(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out");
        Generator generator = new Generator(out, Map.of(), "com.example");
        generator.generate(model(entity("Good", List.of())));
        List<Path> committed = generator.writtenFiles();
        assertTrue(committed.stream().anyMatch(p -> p.toString().endsWith("Good.java")));

        CsdlModel.EntityTypeModel bad = entity("Bad", List.of(
                new CsdlModel.PropertyModel("Broken", "NS.Missing", true, null, List.of())));
        assertThrows(IllegalStateException.class, () -> generator.generate(model(bad)));
        assertEquals(committed, generator.writtenFiles());
    }

    @Test
    void staleDeletionFailureIsFatalAndDoesNotCommitNewManifest(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out");
        Generator generator = new Generator(out, Map.of(), "com.example");
        generator.generate(model(entity("Old", List.of())));
        List<Path> committed = generator.writtenFiles();
        Path stale = out.resolve("com/example/entity/Old.java");
        Files.delete(stale);
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("child.txt"), "keep");

        assertThrows(java.io.IOException.class, () -> generator.generate(model(entity("New", List.of()))));
        assertEquals(committed, generator.writtenFiles());
    }

    @Test
    void caseOnlyGeneratedFileCollisionFailsPortably(@TempDir Path tempDir) {
        Path out = tempDir.resolve("out");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new Generator(out, Map.of(), "com.example")
                        .generate(model(entity("Foo", List.of()), entity("foo", List.of()))));
        assertTrue(ex.getMessage().toLowerCase().contains("case") || ex.getMessage().contains("Foo"), ex.getMessage());
    }

    @Test
    void regularPriorFileIsRestoredOnMidCommitFailure(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out");
        Generator generator = new Generator(out, Map.of(), "com.example");
        generator.generate(model(entity("Good", List.of())));
        Path good = out.resolve("com/example/entity/Good.java");
        String original = Files.readString(good);

        // A directory where Other.java will be written makes that write fail AFTER Good.java
        // has been rewritten, forcing a rollback of Good.java's (changed) content.
        Files.createDirectories(out.resolve("com/example/entity/Other.java"));

        CsdlModel.EntityTypeModel changedGood = entity("Good", List.of(
                new CsdlModel.PropertyModel("Extra", "Edm.String", true, null, List.of())));
        assertThrows(java.io.IOException.class,
                () -> generator.generate(model(changedGood, entity("Other", List.of()))));

        assertEquals(original, Files.readString(good),
                "Good.java must be restored to its pre-commit content after a failed commit");
    }

    private static CsdlModel.EntityTypeModel entity(String name, List<CsdlModel.PropertyModel> properties) {
        return new CsdlModel.EntityTypeModel(name, null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))), concat(properties,
                new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())), List.of());
    }

    private static List<CsdlModel.PropertyModel> concat(List<CsdlModel.PropertyModel> properties,
                                                         CsdlModel.PropertyModel id) {
        java.util.ArrayList<CsdlModel.PropertyModel> result = new java.util.ArrayList<>(properties);
        result.add(id);
        return result;
    }

    private static CsdlModel model(CsdlModel.EntityTypeModel... entities) {
        return new CsdlModel(List.of(new CsdlModel.SchemaModel("NS", null, List.of(entities), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of())), List.of());
    }
}
