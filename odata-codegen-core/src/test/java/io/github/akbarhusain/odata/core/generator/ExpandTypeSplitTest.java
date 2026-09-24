package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpandTypeSplitTest {

    @Test
    void structuralCollectionsAreNotExpandableButNavigationCollectionsAre(@TempDir Path tempDir) throws Exception {
        CsdlModel model = model();
        new Generator(tempDir, Map.of(), "app").generate(model);

        String person = Files.readString(tempDir.resolve("app/entity/Person.java"));
        assertTrue(person.contains("public static final CollectionProperty<Person, String"), person);
        assertTrue(person.contains("public static final NavCollectionProperty<Person, Friend"), person);
        assertTrue(person.contains("new CollectionProperty<>(\"Tags\", Person.class, String.class, "
                + "CollectionProperty.FilterableElement::new, null, \"Edm.String\")"), person);

        Files.writeString(tempDir.resolve("StructuralExpand.java"), """
                import app.collection.request.PersonCollectionRequest;
                import app.entity.Person;
                class StructuralExpand {
                    void expand(PersonCollectionRequest request) {
                        request.expand(Person.TAGS);
                    }
                }
                """);
        String errors = CompilationHarness.compileAll(tempDir);
        assertNotNull(errors, "structural collection must not satisfy $expand");
    }

    private static CsdlModel model() {
        CsdlModel.EntityTypeModel friend = new CsdlModel.EntityTypeModel(
                "Friend", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel person = new CsdlModel.EntityTypeModel(
                "Person", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Tags", "Collection(Edm.String)", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Friends", "Collection(NS.Friend)", null,
                        false, false, List.of(), List.of())));
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel(
                "Container", List.of(new CsdlModel.EntitySetModel("People", "NS.Person", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(person, friend), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(container));
        return new CsdlModel(List.of(schema), List.of());
    }
}
