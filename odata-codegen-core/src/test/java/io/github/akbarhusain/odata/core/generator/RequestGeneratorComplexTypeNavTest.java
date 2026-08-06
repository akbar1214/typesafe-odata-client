package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that complex-type navigation properties are correctly skipped in request
 * generation even when the RequestGenerator is constructed without the full schema list
 * (i.e. the 1-arg constructor where allSchemas is empty). Complex types are inline data,
 * not navigable entities, so no entity-request code should be emitted for them.
 */
class RequestGeneratorComplexTypeNavTest {

    private CsdlModel.SchemaModel buildSchema() {
        CsdlModel.ComplexTypeModel address = new CsdlModel.ComplexTypeModel(
                "OfficeAddress", null, false, false,
                List.of(new CsdlModel.PropertyModel("City", "Edm.String", true, null, List.of())),
                List.of());

        CsdlModel.EntityTypeModel person = new CsdlModel.EntityTypeModel(
                "Person", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.String", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel(
                        "OfficeAddress", "TestNS.OfficeAddress", null, false, true,
                        List.of(), List.of())));

        return new CsdlModel.SchemaModel(
                "TestNS", null,
                List.of(person),
                List.of(address),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void complexTypeNavIsSkippedWithEmptyAllSchemas() {
        CsdlModel.SchemaModel schema = buildSchema();
        CsdlModel.EntityTypeModel person = schema.entityTypes().get(0);

        // Use the ONLY schema-sensitive constructor (empty allSchemas) to reproduce the latent bug.
        RequestGenerator gen = new RequestGenerator("com.example.test");
        String code = gen.generateEntityRequest(person, schema);

        // Complex-type nav targets should NOT produce an entity-request import or nav method.
        assertFalse(code.contains("OfficeAddressEntityRequest"),
                "Complex-type navigation property must not generate an entity request import: \n" + code);
        assertFalse(code.contains("officeAddress()"),
                "Complex-type navigation property must not generate a nav method: \n" + code);
        assertTrue(code.contains("public Person get()"),
                "Entity request class should still be generated normally");
    }
}