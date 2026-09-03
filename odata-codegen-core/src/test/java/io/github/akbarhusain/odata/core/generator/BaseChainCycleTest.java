package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch A1): base-type chains are walked recursively with no cycle
 * detection — a hostile {@code A <-> B} (or self-referential {@code A -> A})
 * BaseType currently ends in StackOverflowError instead of a loud,
 * context-carrying failure like every other structural problem in this codebase.
 */
class BaseChainCycleTest {

    private static CsdlModel.PropertyModel prop(String name, String type) {
        return new CsdlModel.PropertyModel(name, type, true, null, List.of());
    }

    private static CsdlModel.SchemaModel cyclicEntitySchema() {
        CsdlModel.EntityTypeModel a = new CsdlModel.EntityTypeModel("A", "NS.B",
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(prop("Id", "Edm.Int32")),
                List.of());
        CsdlModel.EntityTypeModel b = new CsdlModel.EntityTypeModel("B", "NS.A",
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(prop("Id", "Edm.Int32")),
                List.of());
        return new CsdlModel.SchemaModel("NS", null,
                List.of(a, b), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static CsdlModel.SchemaModel selfCyclicEntitySchema() {
        CsdlModel.EntityTypeModel a = new CsdlModel.EntityTypeModel("A", "NS.A",
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(prop("Id", "Edm.Int32")),
                List.of());
        return new CsdlModel.SchemaModel("NS", null,
                List.of(a), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static CsdlModel.SchemaModel cyclicComplexSchema() {
        CsdlModel.ComplexTypeModel a = new CsdlModel.ComplexTypeModel("A", "NS.B",
                false, false, List.of(prop("X", "Edm.String")), List.of());
        CsdlModel.ComplexTypeModel b = new CsdlModel.ComplexTypeModel("B", "NS.A",
                false, false, List.of(prop("Y", "Edm.String")), List.of());
        return new CsdlModel.SchemaModel("NS", null,
                List.of(), List.of(a, b), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void entityBaseCycleFailsLoudly() {
        CsdlModel.SchemaModel schema = cyclicEntitySchema();
        CsdlModel.EntityTypeModel a = schema.entityTypes().get(0);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example", Map.of(), null, List.of(schema))
                        .generate(a, schema),
                "A<->B BaseType cycle must fail loudly, not StackOverflow");
        assertTrue(ex.getMessage().contains("NS.A") || ex.getMessage().contains("NS.B"),
                "the cycle must name an involved type: " + ex.getMessage());
    }

    @Test
    void entitySelfCycleFailsLoudly() {
        CsdlModel.SchemaModel schema = selfCyclicEntitySchema();
        CsdlModel.EntityTypeModel a = schema.entityTypes().get(0);
        assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example", Map.of(), null, List.of(schema))
                        .generate(a, schema),
                "self-referential BaseType must fail loudly, not StackOverflow");
    }

    @Test
    void complexBaseCycleFailsLoudly() {
        CsdlModel.SchemaModel schema = cyclicComplexSchema();
        CsdlModel.ComplexTypeModel a = schema.complexTypes().get(0);
        assertThrows(IllegalStateException.class,
                () -> new ComplexTypeGenerator("com.example", Map.of(), null, List.of(schema))
                        .generate(a, schema),
                "complex A<->B BaseType cycle must fail loudly, not StackOverflow");
    }

    @Test
    void requestGeneratorBaseCycleFailsLoudly() {
        CsdlModel.SchemaModel schema = cyclicEntitySchema();
        CsdlModel.EntityTypeModel a = schema.entityTypes().get(0);
        assertThrows(IllegalStateException.class,
                () -> new RequestGenerator("com.example", Map.of(), null, List.of(schema))
                        .generateEntityRequest(a, schema),
                "request generation over a BaseType cycle must fail loudly, not StackOverflow");
    }
}
