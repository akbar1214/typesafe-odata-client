package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedOutputNameSafetyTest {

    @Test
    void selfPropertyDoesNotImportItsOwnClass(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel entity = entity("Foo",
                List.of(new CsdlModel.PropertyModel("Self", "NS.Foo", true, null, List.of())),
                List.of());
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(model(entity));

        String code = Files.readString(out.resolve("com/example/entity/Foo.java"));
        assertFalse(code.contains("import com.example.entity.Foo;"), code);
        assertNull(CompilationHarness.compileAll(out), code);
    }

    @Test
    void customEntityNamedLikeJavaBuiltinIsNotSkippedByNavImport(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel string = entity("String", List.of(), List.of());
        CsdlModel.EntityTypeModel holder = entity("Holder", List.of(),
                List.of(new CsdlModel.NavigationPropertyModel("Next", "NS.String", null,
                        false, true, List.of(), List.of())));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(model(string, holder));

        String code = Files.readString(out.resolve("com/example/entity/Holder.java"));
        assertTrue(code.contains("import com.example.entity.String_;"), code);
        assertNull(CompilationHarness.compileAll(out), code);
    }

    @Test
    void lifecycleGetterNamesAreRejectedOrReservedBeforeEmission() {
        for (String property : List.of("Key", "ETag")) {
            CsdlModel.EntityTypeModel entity = entity("Foo",
                    List.of(new CsdlModel.PropertyModel(property, "Edm.String", true, null, List.of())),
                    List.of());
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new EntityGenerator("com.example").generate(entity, schema(entity)),
                    property);
            assertTrue(ex.getMessage().contains(property), ex.getMessage());
        }
        for (String property : List.of("ContextPath", "UnmappedFields", "ChangedFields")) {
            CsdlModel.EntityTypeModel entity = entity("Foo",
                    List.of(new CsdlModel.PropertyModel(property, "Edm.String", true, null, List.of())),
                    List.of());
            String code = new EntityGenerator("com.example").generate(entity, schema(entity));
            assertTrue(code.contains("get" + Character.toUpperCase(property.charAt(0))
                    + property.substring(1) + "_"), code);
        }
    }

    @Test
    void requestNavigationNamesCannotCollideWithEmittedMethods() {
        CsdlModel.EntityTypeModel target = entity("Target", List.of(), List.of());
        CsdlModel.EntityTypeModel source = entity("Source", List.of(),
                List.of(new CsdlModel.NavigationPropertyModel("Get", "NS.Target", null,
                        false, true, List.of(), List.of())));
        CsdlModel.SchemaModel model = schema(source, target);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RequestGenerator("com.example", Map.of(), "com.example", List.of(model))
                        .generateEntityRequest(source, model));
        assertTrue(ex.getMessage().contains("Get"), ex.getMessage());
    }

    @Test
    void samePropertyAndNavigationNameIsRejected() {
        CsdlModel.EntityTypeModel target = entity("Target", List.of(), List.of());
        CsdlModel.EntityTypeModel source = entity("Source",
                List.of(new CsdlModel.PropertyModel("Link", "Edm.String", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Link", "NS.Target", null,
                        false, true, List.of(), List.of())));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example").generate(source, schema(source, target)));
        assertTrue(ex.getMessage().contains("Link"), ex.getMessage());
    }

    @Test
    void classAndClassPropertyNamesCannotFoldIntoOneGetter() {
        CsdlModel.EntityTypeModel entity = entity("Foo", List.of(
                new CsdlModel.PropertyModel("class", "Edm.String", true, null, List.of()),
                new CsdlModel.PropertyModel("Class", "Edm.String", true, null, List.of())), List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example").generate(entity, schema(entity)));
        assertTrue(ex.getMessage().contains("class") && ex.getMessage().contains("Class"), ex.getMessage());
    }

    @Test
    void collaboratorNamesCompileAcrossAllGeneratedFileKinds(@TempDir Path tempDir) throws Exception {
        List<CsdlModel.EntityTypeModel> entities = List.of(
                entity("Context", List.of(), List.of()),
                entity("List", List.of(), List.of()),
                entity("Map", List.of(), List.of()),
                entity("SchemaInfo", List.of(), List.of()),
                entity("EntityOperations", List.of(), List.of()),
                entity("StringProperty", List.of(), List.of()),
                entity("ODataEntityType", List.of(), List.of()),
                entity("Optional", List.of(), List.of()),
                entity("CollectionPage", List.of(), List.of()),
                entity("BatchOperation", List.of(), List.of()),
                entity("String", List.of(), List.of()),
                entity("Holder", List.of(
                        new CsdlModel.PropertyModel("Value", "NS.Context", true, null, List.of()),
                        new CsdlModel.PropertyModel("Address", "NS.Address", true, null, List.of()),
                        new CsdlModel.PropertyModel("Related", "Collection(NS.List)", true, null, List.of())),
                        List.of(new CsdlModel.NavigationPropertyModel("Context", "NS.Context", null,
                                false, true, List.of(), List.of()))));
        CsdlModel.ComplexTypeModel address = new CsdlModel.ComplexTypeModel("Address", null,
                false, false, List.of(new CsdlModel.PropertyModel("City", "Edm.String", true, null, List.of())), List.of());
        CsdlModel.EnumTypeModel color = new CsdlModel.EnumTypeModel("Color", "Edm.Int32", false,
                List.of(new CsdlModel.EnumMemberModel("Red", 0)));
        List<CsdlModel.EntitySetModel> sets = entities.stream()
                .map(e -> new CsdlModel.EntitySetModel(e.name() + "s", "NS." + e.name(), List.of(), List.of()))
                .toList();
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                sets, List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, entities,
                List.of(address), List.of(color), List.of(), List.of(), List.of(), List.of(container));

        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));
        String errors = CompilationHarness.compileAll(out);
        assertNull(errors, errors);
    }

    @Test
    void operationAndNestedCollaboratorNamesCompile(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel context = entity("Context", List.of(
                new CsdlModel.PropertyModel("Address", "NS.Address", true, null, List.of()),
                new CsdlModel.PropertyModel("Color", "NS.Color", true, null, List.of()),
                new CsdlModel.PropertyModel("Items", "Collection(NS.Context)", true, null, List.of())), List.of(
                new CsdlModel.NavigationPropertyModel("Next", "NS.Context", null,
                        false, true, List.of(), List.of())));
        CsdlModel.EntityTypeModel list = entity("List", List.of(), List.of());
        CsdlModel.ComplexTypeModel address = new CsdlModel.ComplexTypeModel("Address", null, false, false,
                List.of(new CsdlModel.PropertyModel("City", "Edm.String", true, null, List.of())), List.of());
        CsdlModel.EnumTypeModel color = new CsdlModel.EnumTypeModel("Color", "Edm.Int32", false,
                List.of(new CsdlModel.EnumMemberModel("Red", 0)));
        CsdlModel.FunctionModel function = new CsdlModel.FunctionModel("EntityOperations", false, false, null,
                List.of(new CsdlModel.ParameterModel("context", "NS.Context", false)),
                new CsdlModel.ReturnTypeModel("NS.Address", false));
        CsdlModel.ActionModel action = new CsdlModel.ActionModel("BatchOperation", false, null,
                List.of(new CsdlModel.ParameterModel("color", "NS.Color", false)), null);
        CsdlModel.FunctionModel bound = new CsdlModel.FunctionModel("Bound", true, false, null,
                List.of(new CsdlModel.ParameterModel("target", "NS.Context", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Context", null,
                List.of(new CsdlModel.EntitySetModel("Contexts", "NS.Context", List.of(), List.of()),
                        new CsdlModel.EntitySetModel("Lists", "NS.List", List.of(), List.of())),
                List.of(), List.of(new CsdlModel.FunctionImportModel("Do", "NS.EntityOperations", null, false)),
                List.of(new CsdlModel.ActionImportModel("Act", "NS.BatchOperation", null)));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(context, list),
                List.of(address), List.of(color), List.of(), List.of(function, bound), List.of(action), List.of(container));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").withGenerateWithMethods(true)
                .generate(new CsdlModel(List.of(schema), List.of()));
        assertNull(CompilationHarness.compileAll(out));
    }

    private static CsdlModel.EntityTypeModel entity(String name,
                                                       List<CsdlModel.PropertyModel> properties,
                                                       List<CsdlModel.NavigationPropertyModel> navs) {
        return new CsdlModel.EntityTypeModel(name, null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                concat(properties, new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                navs);
    }

    private static List<CsdlModel.PropertyModel> concat(List<CsdlModel.PropertyModel> properties,
                                                         CsdlModel.PropertyModel id) {
        java.util.ArrayList<CsdlModel.PropertyModel> result = new java.util.ArrayList<>(properties);
        result.add(id);
        return result;
    }

    private static CsdlModel.SchemaModel schema(CsdlModel.EntityTypeModel... entities) {
        return new CsdlModel.SchemaModel("NS", null, List.of(entities), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    private static CsdlModel model(CsdlModel.EntityTypeModel... entities) {
        return new CsdlModel(List.of(schema(entities)), List.of());
    }
}
