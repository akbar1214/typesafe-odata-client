package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch A4): {@code RequestGenerator.findBase} lacked the
 * cross-schema unqualified fallback the entity/complex generators have, so
 * inherited navs/$ref/stream methods silently vanished on requests for
 * unqualified cross-schema subtypes. Parity: resolve them the same way.
 */
class RequestGeneratorBaseParityTest {

    @Test
    void unqualifiedCrossSchemaBaseNavsResolve() {
        CsdlModel.EntityTypeModel order = new CsdlModel.EntityTypeModel("Order", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel base = new CsdlModel.EntityTypeModel("Base", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Orders",
                        "Collection(One.Order)", null, false, false, List.of(), List.of())));
        CsdlModel.EntityTypeModel derived = new CsdlModel.EntityTypeModel("Derived", "Base",
                false, false, false,
                List.of(),
                List.of(),
                List.of());
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(base, order), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(derived), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String code = new RequestGenerator("com.example", Map.of(), null, List.of(one, two))
                .generateEntityRequest(derived, two);

        assertTrue(code.contains("orders() {"),
                "inherited navs must resolve through an unqualified cross-schema base:\n" + code);
    }
}
