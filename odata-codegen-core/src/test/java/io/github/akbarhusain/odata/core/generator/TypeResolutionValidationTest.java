package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypeResolutionValidationTest {

    @Test
    void unqualifiedTypedefsResolveInDeclaringSchemaBeforeGlobalAmbiguity(@TempDir Path tempDir) throws Exception {
        CsdlModel.SchemaModel a = schema("NS.A",
                entity("Thing", List.of(new CsdlModel.PropertyModel("Local", "Local", true, null, List.of()))),
                List.of(new CsdlModel.TypeDefinitionModel("Local", "Edm.String")));
        CsdlModel.SchemaModel b = schema("NS.B",
                entity("Thing", List.of(new CsdlModel.PropertyModel("Local", "Local", true, null, List.of()))),
                List.of(new CsdlModel.TypeDefinitionModel("Local", "Edm.Int32")));

        Path out = tempDir.resolve("out");
        new Generator(out, Map.of("NS.A", "com.a", "NS.B", "com.b"), "com.example")
                .generate(new CsdlModel(List.of(a, b), List.of()));
        assertTrue(Files.readString(out.resolve("com/a/entity/Thing.java")).contains("String local;"));
        assertTrue(Files.readString(out.resolve("com/b/entity/Thing.java")).contains("Integer local;"));
        assertNull(CompilationHarness.compileAll(out));
    }

    @Test
    void unqualifiedModelTypesResolveInDeclaringSchemaBeforeGlobalFallback(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel aShared = entity("Shared", List.of());
        CsdlModel.EntityTypeModel bShared = entity("Shared", List.of());
        CsdlModel.SchemaModel a = new CsdlModel.SchemaModel("NS.A", null,
                List.of(entity("Target", List.of(new CsdlModel.PropertyModel("Ref", "Shared", true, null, List.of()))), aShared),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel b = new CsdlModel.SchemaModel("NS.B", null,
                List.of(entity("Target", List.of(new CsdlModel.PropertyModel("Ref", "Shared", true, null, List.of()))), bShared),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of("NS.A", "com.a", "NS.B", "com.b"), "com.example")
                .generate(new CsdlModel(List.of(a, b), List.of()));
        assertTrue(Files.readString(out.resolve("com/a/entity/Target.java")).contains("import com.a.entity.Shared;"));
        assertTrue(Files.readString(out.resolve("com/b/entity/Target.java")).contains("import com.b.entity.Shared;"));
        assertNull(CompilationHarness.compileAll(out));
    }

    @Test
    void typedefChainsAreSchemaLocalAndOrderIndependent(@TempDir Path tempDir) throws Exception {
        CsdlModel.SchemaModel a = schema("NS.A",
                entity("Thing", List.of(new CsdlModel.PropertyModel("Value", "Alias", true, null, List.of()))),
                List.of(new CsdlModel.TypeDefinitionModel("Alias", "Local"),
                        new CsdlModel.TypeDefinitionModel("Local", "Edm.String")));
        CsdlModel.SchemaModel b = schema("NS.B", List.of(),
                List.of(new CsdlModel.TypeDefinitionModel("Local", "Edm.Int32")));

        Path first = tempDir.resolve("first");
        new Generator(first, Map.of("NS.A", "com.a", "NS.B", "com.b"), "com.example")
                .generate(new CsdlModel(List.of(a, b), List.of()));
        Path second = tempDir.resolve("second");
        new Generator(second, Map.of("NS.A", "com.a", "NS.B", "com.b"), "com.example")
                .generate(new CsdlModel(List.of(b, a), List.of()));

        assertTrue(Files.readString(first.resolve("com/a/entity/Thing.java")).contains("String value;"));
        assertTrue(Files.readString(second.resolve("com/a/entity/Thing.java")).contains("String value;"));
        assertNull(CompilationHarness.compileAll(first));
        assertNull(CompilationHarness.compileAll(second));
    }

    @Test
    void unknownEntityPropertyTypeFailsWithMemberAndType(@TempDir Path tempDir) {
        CsdlModel.EntityTypeModel entity = entity("Bad",
                List.of(new CsdlModel.PropertyModel("Broken", "NS.Missing", true, null, List.of())), List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new Generator(tempDir.resolve("out"), Map.of(), "com.example")
                        .generate(model(entity)));
        assertTrue(ex.getMessage().contains("Broken") && ex.getMessage().contains("NS.Missing"), ex.getMessage());
    }

    @Test
    void unknownCollectionElementAndNavigationTypesFail(@TempDir Path tempDir) {
        CsdlModel.EntityTypeModel property = entity("BadProperty", List.of(
                new CsdlModel.PropertyModel("Broken", "Collection(NS.Missing)", true, null, List.of())), List.of());
        IllegalStateException propertyError = assertThrows(IllegalStateException.class,
                () -> new Generator(tempDir.resolve("property"), Map.of(), "com.example").generate(model(property)));
        assertTrue(propertyError.getMessage().contains("Broken") && propertyError.getMessage().contains("NS.Missing"));

        CsdlModel.EntityTypeModel nav = entity("BadNav", List.of(), List.of(
                new CsdlModel.NavigationPropertyModel("Broken", "Collection(NS.Missing)", null,
                        false, true, List.of(), List.of())));
        IllegalStateException navError = assertThrows(IllegalStateException.class,
                () -> new Generator(tempDir.resolve("nav"), Map.of(), "com.example").generate(model(nav)));
        assertTrue(navError.getMessage().contains("Broken") && navError.getMessage().contains("NS.Missing"));
    }

    @Test
    void unknownBaseTypesFailForEntityAndComplex(@TempDir Path tempDir) {
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("Bad", "NS.MissingBase", false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())), List.of());
        IllegalStateException entityError = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example", Map.of(), "com.example", List.of(schema("NS", entity)))
                        .generate(entity, schema("NS", entity)));
        assertTrue(entityError.getMessage().contains("Bad") && entityError.getMessage().contains("NS.MissingBase"));

        CsdlModel.ComplexTypeModel complex = new CsdlModel.ComplexTypeModel("BadComplex", "NS.MissingBase", false, false,
                List.of(), List.of());
        IllegalStateException complexError = assertThrows(IllegalStateException.class,
                () -> new ComplexTypeGenerator("com.example", Map.of(), "com.example", List.of(schemaComplex(complex)))
                        .generate(complex, schemaComplex(complex)));
        assertTrue(complexError.getMessage().contains("BadComplex") && complexError.getMessage().contains("NS.MissingBase"));
    }

    @Test
    void unknownTypedefAndUnderlyingTypeFail(@TempDir Path tempDir) {
        CsdlModel.EntityTypeModel missingAlias = entity("BadAlias", List.of(
                new CsdlModel.PropertyModel("Value", "MissingAlias", true, null, List.of())), List.of());
        IllegalStateException aliasError = assertThrows(IllegalStateException.class,
                () -> new Generator(tempDir.resolve("alias"), Map.of(), "com.example").generate(model(missingAlias)));
        assertTrue(aliasError.getMessage().contains("MissingAlias"));

        CsdlModel.EntityTypeModel broken = entity("BadUnderlying", List.of(
                new CsdlModel.PropertyModel("Value", "Alias", true, null, List.of())), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(broken), List.of(), List.of(),
                List.of(new CsdlModel.TypeDefinitionModel("Alias", "NS.MissingUnderlying")),
                List.of(), List.of(), List.of());
        IllegalStateException underlyingError = assertThrows(IllegalStateException.class,
                () -> new Generator(tempDir.resolve("underlying"), Map.of(), "com.example")
                        .generate(new CsdlModel(List.of(schema), List.of())));
        assertTrue(underlyingError.getMessage().contains("Alias") && underlyingError.getMessage().contains("NS.MissingUnderlying"));
    }

    @Test
    void unusedUnknownTypedefStillFailsGeneration(@TempDir Path tempDir) {
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(), List.of(), List.of(),
                List.of(new CsdlModel.TypeDefinitionModel("Broken", "NS.MissingUnderlying")),
                List.of(), List.of(), List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new Generator(tempDir.resolve("out"), Map.of(), "com.example")
                        .generate(new CsdlModel(List.of(schema), List.of())));
        assertTrue(ex.getMessage().contains("Broken") && ex.getMessage().contains("NS.MissingUnderlying"));
    }

    private static CsdlModel.EntityTypeModel entity(String name, List<CsdlModel.PropertyModel> properties) {
        return entity(name, properties, List.of());
    }

    private static CsdlModel.EntityTypeModel entity(String name, List<CsdlModel.PropertyModel> properties,
                                                       List<CsdlModel.NavigationPropertyModel> navs) {
        java.util.ArrayList<CsdlModel.PropertyModel> all = new java.util.ArrayList<>(properties);
        all.add(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()));
        return new CsdlModel.EntityTypeModel(name, null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))), all, navs);
    }

    private static CsdlModel.SchemaModel schema(String namespace, CsdlModel.EntityTypeModel... entities) {
        return new CsdlModel.SchemaModel(namespace, null, List.of(entities), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static CsdlModel.SchemaModel schema(String namespace, CsdlModel.EntityTypeModel entity,
                                                 List<CsdlModel.TypeDefinitionModel> definitions) {
        return new CsdlModel.SchemaModel(namespace, null, List.of(entity), List.of(), List.of(), definitions,
                List.of(), List.of(), List.of());
    }

    private static CsdlModel.SchemaModel schema(String namespace, List<CsdlModel.EntityTypeModel> entities,
                                                 List<CsdlModel.TypeDefinitionModel> definitions) {
        return new CsdlModel.SchemaModel(namespace, null, entities, List.of(), List.of(), definitions,
                List.of(), List.of(), List.of());
    }

    private static CsdlModel.SchemaModel schemaComplex(CsdlModel.ComplexTypeModel complex) {
        return new CsdlModel.SchemaModel("NS", null, List.of(), List.of(complex), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static CsdlModel model(CsdlModel.EntityTypeModel... entities) {
        return new CsdlModel(List.of(schema("NS", entities)), List.of());
    }
}
