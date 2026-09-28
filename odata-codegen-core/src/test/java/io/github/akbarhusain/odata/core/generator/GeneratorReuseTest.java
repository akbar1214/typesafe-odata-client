package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorReuseTest {

    @Test
    void constantAllocationDoesNotLeakAcrossTypes() {
        CsdlModel.EntityTypeModel first = entity("First", "Budget");
        CsdlModel.EntityTypeModel second = entity("Second", "budget");
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(first, second),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        EntityGenerator generator = new EntityGenerator("com.example", Map.of(), "com.example", List.of(schema));
        generator.setGenerateWithMethods(true);

        String firstCode = generator.generate(first, schema);
        String secondCode = generator.generate(second, schema);
        assertTrue(firstCode.contains("public static final NumberProperty<First, Integer> BUDGET ="), firstCode);
        assertTrue(secondCode.contains("public static final NumberProperty<Second, Integer> BUDGET ="), secondCode);
        assertTrue(!secondCode.contains("NumberProperty<Second, Integer> BUDGET_2 ="), secondCode);
    }

    private static CsdlModel.EntityTypeModel entity(String name, String property) {
        return new CsdlModel.EntityTypeModel(name, null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel(property, "Edm.Int32", false, null, List.of())),
                List.of());
    }
}
