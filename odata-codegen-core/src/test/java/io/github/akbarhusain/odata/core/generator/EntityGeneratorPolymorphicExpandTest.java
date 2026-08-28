package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityGeneratorPolymorphicExpandTest {

    @Test
    void emitsTypedCastConstantForNavigationTargetSubtype() {
        CsdlModel.SchemaModel schema = schema();

        String code = new EntityGenerator("app", Map.of(), "app", List.of(schema))
                .generate(schema.entityTypes().get(0), schema);

        assertTrue(code.contains(
                "public static final NavProperty.NavQuery<Container, Doc> VERSIONS_AS_DOC"
                        + " = VERSIONS.as(\"NS.Doc\", Doc.class);"), code);
        assertTrue(code.contains("import app.entity.Doc;"),
                "the cast constant needs the generated subtype import: " + code);
    }

    @Test
    void doesNotEmitCastConstantWhenNavigationTargetHasNoSubtype() {
        CsdlModel.SchemaModel schema = schemaWithoutSubtype();

        String code = new EntityGenerator("app", Map.of(), "app", List.of(schema))
                .generate(schema.entityTypes().get(0), schema);

        assertFalse(code.contains("_AS_"), code);
    }

    private static CsdlModel.SchemaModel schema() {
        CsdlModel.EntityTypeModel container = new CsdlModel.EntityTypeModel("Container", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Versions", "Collection(NS.Version)",
                        null, false, false, List.of(), List.of())));
        CsdlModel.EntityTypeModel version = new CsdlModel.EntityTypeModel("Version", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel doc = new CsdlModel.EntityTypeModel("Doc", "NS.Version",
                false, false, false, List.of(), List.of(), List.of());
        return new CsdlModel.SchemaModel("NS", null, List.of(container, version, doc),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static CsdlModel.SchemaModel schemaWithoutSubtype() {
        CsdlModel.EntityTypeModel container = new CsdlModel.EntityTypeModel("Container", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Versions", "Collection(NS.Version)",
                        null, false, false, List.of(), List.of())));
        CsdlModel.EntityTypeModel version = new CsdlModel.EntityTypeModel("Version", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        return new CsdlModel.SchemaModel("NS", null, List.of(container, version),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
