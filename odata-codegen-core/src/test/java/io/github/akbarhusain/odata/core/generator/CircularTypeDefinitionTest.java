package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircularTypeDefinitionTest {

    @Test
    void selfReferentialTypedefFailsLoudlyInsteadOfOverflowing(@TempDir Path tempDir) {
        CsdlModel.SchemaModel schema = schema(
                List.of(new CsdlModel.TypeDefinitionModel("Loop", "Loop")), "Loop");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new Generator(tempDir.resolve("out"), Map.of(), "com.example")
                        .generate(new CsdlModel(List.of(schema), List.of())));
        assertTrue(ex.getMessage().contains("Circular TypeDefinition"), ex.getMessage());
    }

    @Test
    void twoNodeTypedefCycleFailsLoudly(@TempDir Path tempDir) {
        CsdlModel.SchemaModel schema = schema(
                List.of(new CsdlModel.TypeDefinitionModel("A", "B"),
                        new CsdlModel.TypeDefinitionModel("B", "A")), "A");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new Generator(tempDir.resolve("out"), Map.of(), "com.example")
                        .generate(new CsdlModel(List.of(schema), List.of())));
        assertTrue(ex.getMessage().contains("Circular TypeDefinition"), ex.getMessage());
    }

    private static CsdlModel.SchemaModel schema(
            List<CsdlModel.TypeDefinitionModel> typedefs, String propertyType) {
        return new CsdlModel.SchemaModel("NS", null,
                List.of(new CsdlModel.EntityTypeModel("Thing", null, false, false, false,
                        List.of(new CsdlModel.KeyModel(List.of("Id"))),
                        List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                                new CsdlModel.PropertyModel("Value", propertyType, true, null, List.of())),
                        List.of())),
                List.of(), List.of(), typedefs, List.of(), List.of(), List.of());
    }
}
