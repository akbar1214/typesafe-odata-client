package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * RED (TDD, Batch A1): {@code OperationGenerator.ancestorQualifiedNames} walks
 * the base chain in an unbounded {@code while} loop — worse than the recursive
 * walkers, a cycle here appends forever (OOM) instead of overflowing the stack.
 * The timeout keeps the RED run bounded; post-fix the call throws immediately.
 */
class BoundAncestorCycleTest {

    @Test
    void boundAncestorCycleFailsLoudly() {
        CsdlModel.EntityTypeModel a = new CsdlModel.EntityTypeModel("A", "NS.B",
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel b = new CsdlModel.EntityTypeModel("B", "NS.A",
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(a, b), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThrows(IllegalStateException.class,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> new OperationGenerator("com.example", Map.of(), null, List.of(schema))
                                .boundOperationsFor(a, schema)),
                "ancestor walk over a BaseType cycle must fail loudly, not loop forever");
    }
}
