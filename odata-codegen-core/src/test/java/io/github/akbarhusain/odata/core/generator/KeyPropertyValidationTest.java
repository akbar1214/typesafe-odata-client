package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeyPropertyValidationTest {

    @Test
    void nullableKeyPropertyFailsGeneration() {
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("Bad", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.String", true, null, List.of())), List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example").generate(entity, schema(entity)));
        assertTrue(ex.getMessage().contains("Id") && ex.getMessage().contains("non-null"), ex.getMessage());
    }

    @Test
    void collectionAndStructuredKeyPropertiesFailGeneration() {
        CsdlModel.EntityTypeModel collection = new CsdlModel.EntityTypeModel("Bad", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Collection(Edm.String)", false, null, List.of())), List.of());
        assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example").generate(collection, schema(collection)));

        CsdlModel.ComplexTypeModel complex = new CsdlModel.ComplexTypeModel("Address", null, false, false,
                List.of(new CsdlModel.PropertyModel("City", "Edm.String", true, null, List.of())), List.of());
        CsdlModel.EntityTypeModel structured = new CsdlModel.EntityTypeModel("Bad", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "NS.Address", false, null, List.of())), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(structured), List.of(complex),
                List.of(), List.of(), List.of(), List.of(), List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example", Map.of(), "com.example", List.of(schema))
                        .generate(structured, schema));
        assertTrue(ex.getMessage().contains("Id") && ex.getMessage().contains("scalar"), ex.getMessage());
    }

    private static CsdlModel.SchemaModel schema(CsdlModel.EntityTypeModel entity) {
        return new CsdlModel.SchemaModel("NS", null, List.of(entity), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
