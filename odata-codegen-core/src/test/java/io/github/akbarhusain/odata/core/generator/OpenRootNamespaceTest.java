package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch A3): the open-type root lookup is keyed by SIMPLE class name,
 * so two schemas declaring same-named roots collapse to ONE namespace
 * (last-wins). A non-open root can then inherit the open root's mutable-map
 * init. Resolution must use the type's EXACT owning schema.
 */
class OpenRootNamespaceTest {

    private CsdlModel sameNameModel() {
        CsdlModel.EntityTypeModel openFoo = new CsdlModel.EntityTypeModel("Foo", null,
                true, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel closedFoo = new CsdlModel.EntityTypeModel("Foo", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(openFoo), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(closedFoo), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new CsdlModel(List.of(one, two), List.of());
    }

    @Test
    void openRootHandlingFollowsExactSchema(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"))
                .generate(sameNameModel());

        String openSource = Files.readString(out.resolve("com/p1/entity/Foo.java"));
        assertTrue(openSource.contains("new java.util.HashMap<>()"),
                "the OPEN root must hold a mutable unmappedFields map:\n" + openSource);
        assertTrue(openSource.contains("@com.fasterxml.jackson.annotation.JsonAnySetter"),
                "the OPEN root must capture dynamic properties:\n" + openSource);

        String closedSource = Files.readString(out.resolve("com/p2/entity/Foo.java"));
        assertTrue(closedSource.contains("java.util.Map.of()"),
                "the NON-OPEN root must not inherit the open root's mutable map:\n" + closedSource);
        assertFalse(closedSource.contains("@com.fasterxml.jackson.annotation.JsonAnySetter"),
                "the NON-OPEN root must not capture dynamic properties:\n" + closedSource);
    }
}
