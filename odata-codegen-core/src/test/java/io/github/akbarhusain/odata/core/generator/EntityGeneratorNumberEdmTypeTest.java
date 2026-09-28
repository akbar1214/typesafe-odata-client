package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7 generator-side coverage: NumberProperty constants must carry the resolved Edm
 * type so {@code divide()} can choose div vs divby by PROPERTY type (not just value
 * type). The runtime side is covered by NumberExpressionMediumTest.
 */
class EntityGeneratorNumberEdmTypeTest {

    private CsdlModel.EntityTypeModel product() {
        return new CsdlModel.EntityTypeModel("Product", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Price", "Edm.Double", true, null, List.of()),
                        new CsdlModel.PropertyModel("Stock", "Edm.Decimal", true, null, List.of())
                ),
                List.of());
    }

    private CsdlModel.SchemaModel schemaWith(CsdlModel.EntityTypeModel et) {
        return new CsdlModel.SchemaModel("NS.Test", null,
                List.of(et), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void doublePropertyConstantCarriesEdmType() {
        var schema = schemaWith(product());
        String code = new EntityGenerator("com.test")
                .generate(schema.entityTypes().get(0), schema);

        assertTrue(code.contains("new NumberProperty<>(\"Price\", Product.class, \"Edm.Double\")"),
                "static constant must pass the Edm type for divby selection:\n" + code);
        assertTrue(code.contains("new NumberProperty<>(\"x/Price\", Product.class, \"Edm.Double\")"),
                "Filterable field must pass the Edm type too:\n" + code);
    }

    @Test
    void temporalConstantsCarryTheirEdmTypes() {
        CsdlModel.EntityTypeModel type = new CsdlModel.EntityTypeModel("Temporal", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("At", "Edm.DateTimeOffset", true, null, List.of()),
                        new CsdlModel.PropertyModel("Day", "Edm.Date", true, null, List.of()),
                        new CsdlModel.PropertyModel("Clock", "Edm.TimeOfDay", true, null, List.of()),
                        new CsdlModel.PropertyModel("Elapsed", "Edm.Duration", true, null, List.of())),
                List.of());
        String code = new EntityGenerator("com.test").generate(type, schemaWith(type));

        assertTrue(code.contains("new DateTimeProperty<>(\"At\", Temporal.class, \"Edm.DateTimeOffset\")"), code);
        assertTrue(code.contains("new DateTimeProperty<>(\"Day\", Temporal.class, \"Edm.Date\")"), code);
        assertTrue(code.contains("new DateTimeProperty<>(\"Clock\", Temporal.class, \"Edm.TimeOfDay\")"), code);
        assertTrue(code.contains("new DateTimeProperty<>(\"Elapsed\", Temporal.class, \"Edm.Duration\")"), code);
        assertTrue(code.contains("new DateTimeProperty<>(\"x/At\", Temporal.class, \"Edm.DateTimeOffset\")"), code);
    }

    @Test
    void decimalAndInt32ConstantsCarryEdmType() {
        var schema = schemaWith(product());
        String code = new EntityGenerator("com.test")
                .generate(schema.entityTypes().get(0), schema);

        assertTrue(code.contains("\"Edm.Decimal\""), "Decimal property must carry its Edm type:\n" + code);
        assertTrue(code.contains("new NumberProperty<>(\"Id\", Product.class, \"Edm.Int32\")"),
                "Int32 property must carry its Edm type:\n" + code);
    }
}
