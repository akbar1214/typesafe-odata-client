package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestOperationContainerAuditTest {

    @Test
    void operationAndContainerUseOwningSchemaForContestedStructuredTypes(@TempDir Path tempDir) throws Exception {
        CsdlModel model = crossSchemaModel(false);
        Path out = generate(tempDir, model);

        assertNull(CompilationHarness.compileAll(out));
        String operation = Files.readString(out.resolve("com/a/operation/DoFunctionRequest.java"));
        String container = Files.readString(out.resolve("com/a/container/Container.java"));
        assertTrue(operation.contains("com.a.complex.Payload"), operation);
        assertTrue(operation.contains("com.b.complex.Payload"), operation);
        assertTrue(container.contains("com.a.complex.Payload"), container);
        assertTrue(container.contains("com.b.complex.Payload"), container);
    }

    @Test
    void unqualifiedComplexInheritancePrefersTheDeclaringSchema(@TempDir Path tempDir) throws Exception {
        CsdlModel.ComplexTypeModel baseA = new CsdlModel.ComplexTypeModel("Base", null, false, false,
                List.of(new CsdlModel.PropertyModel("A", "Edm.String", true, null, List.of())), List.of());
        CsdlModel.ComplexTypeModel baseB = new CsdlModel.ComplexTypeModel("Base", null, false, false,
                List.of(new CsdlModel.PropertyModel("B", "Edm.String", true, null, List.of())), List.of());
        CsdlModel.ComplexTypeModel derivedB = new CsdlModel.ComplexTypeModel("Derived", "Base", false, false,
                List.of(), List.of());
        CsdlModel.SchemaModel a = new CsdlModel.SchemaModel("NS.A", null, List.of(), List.of(baseA), List.of(),
                List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel b = new CsdlModel.SchemaModel("NS.B", null, List.of(), List.of(baseB, derivedB), List.of(),
                List.of(), List.of(), List.of(), List.of());
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of("NS.A", "com.a", "NS.B", "com.b"), "com.fallback")
                .generate(new CsdlModel(List.of(a, b), List.of()));
        assertNull(CompilationHarness.compileAll(out));
        assertTrue(Files.exists(out.resolve("com/b/complex/Derived.java")));
    }

    @Test
    void unqualifiedInheritedBoundOperationUsesTheAuthoritativeBaseSchema(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel baseA = entity("Base");
        CsdlModel.EntityTypeModel baseB = entity("Base");
        CsdlModel.EntityTypeModel derivedB = new CsdlModel.EntityTypeModel("Derived", "Base", false, false, false,
                List.of(), List.of(), List.of());
        CsdlModel.FunctionModel aOperation = new CsdlModel.FunctionModel("AOnly", true, false, null,
                List.of(new CsdlModel.ParameterModel("base", "NS.A.Base", false)), new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.FunctionModel bOperation = new CsdlModel.FunctionModel("BOnly", true, false, null,
                List.of(new CsdlModel.ParameterModel("base", "NS.B.Base", false)), new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.SchemaModel a = new CsdlModel.SchemaModel("NS.A", null, List.of(baseA), List.of(), List.of(),
                List.of(), List.of(aOperation), List.of(), List.of());
        CsdlModel.SchemaModel b = new CsdlModel.SchemaModel("NS.B", null, List.of(baseB, derivedB), List.of(), List.of(),
                List.of(), List.of(bOperation), List.of(), List.of());
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of("NS.A", "com.a", "NS.B", "com.b"), "com.fallback")
                .generate(new CsdlModel(List.of(a, b), List.of()));
        assertNull(CompilationHarness.compileAll(out));
        assertTrue(Files.exists(out.resolve("com/b/operation/DerivedBOnlyFunctionRequest.java")));
        assertTrue(Files.readString(out.resolve("com/b/entity/request/DerivedEntityRequest.java"))
                .contains("bOnly("));
    }

    @Test
    void operationParametersCannotShadowContextOrBasePath(@TempDir Path tempDir) throws Exception {
        CsdlModel.FunctionModel function = new CsdlModel.FunctionModel("Do", false, false, null,
                List.of(new CsdlModel.ParameterModel("context", "Edm.String", false),
                        new CsdlModel.ParameterModel("basePath", "Edm.String", false),
                        new CsdlModel.ParameterModel("body", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel model = operationImportModel(function);
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "app").generate(model);
        assertNull(CompilationHarness.compileAll(out));
        String code = Files.readString(out.resolve("app/operation/DoFunctionRequest.java"));
        assertTrue(code.contains("String context_"), code);
        assertTrue(code.contains("String basePath_"), code);
        assertTrue(code.contains("String body_"), code);
    }

    @Test
    void identicalParameterIdentityWithDifferentReturnTypesIsRejectedClearly() {
        CsdlModel.FunctionModel stringResult = new CsdlModel.FunctionModel("Do", false, false, null,
                List.of(new CsdlModel.ParameterModel("x", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.FunctionModel intResult = new CsdlModel.FunctionModel("Do", false, false, null,
                List.of(new CsdlModel.ParameterModel("x", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.Int32", false));
        CsdlModel model = operationImportModel(stringResult, intResult);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OperationGenerator("app", Map.of(), "app", model.schemas())
                        .generateFunctionImportRequests(model.schemas().get(0).containers().get(0).functionImports().get(0),
                                model.schemas().get(0)));
        assertTrue(ex.getMessage().toLowerCase().contains("return"), ex.getMessage());
    }

    @Test
    void duplicateParameterNamesInOneFunctionAreRejected() {
        CsdlModel.FunctionModel function = new CsdlModel.FunctionModel("Do", false, false, null,
                List.of(new CsdlModel.ParameterModel("x", "Edm.String", false),
                        new CsdlModel.ParameterModel("x", "Edm.Int32", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel model = operationImportModel(function);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OperationGenerator("app", Map.of(), "app", model.schemas())
                        .generateFunctionImportRequests(model.schemas().get(0).containers().get(0).functionImports().get(0),
                                model.schemas().get(0)));
        assertTrue(ex.getMessage().toLowerCase().contains("parameter"), ex.getMessage());
    }

    @Test
    void duplicateParameterNamesInUnboundActionAreRejected() {
        CsdlModel.ActionModel action = new CsdlModel.ActionModel("Do", false, null,
                List.of(new CsdlModel.ParameterModel("x", "Edm.String", false),
                        new CsdlModel.ParameterModel("x", "Edm.Int32", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(), List.of(), List.of(),
                List.of(new CsdlModel.ActionImportModel("Do", "NS.Do", null)));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(action), List.of(container));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OperationGenerator("app", Map.of(), "app", List.of(schema))
                        .generateActionImportRequest(container.actionImports().get(0), schema));
        assertTrue(ex.getMessage().toLowerCase().contains("parameter"), ex.getMessage());
    }

    @Test
    void duplicateParameterNamesInBoundFunctionAreRejected() {
        CsdlModel.EntityTypeModel thing = entity("Thing");
        CsdlModel.FunctionModel function = new CsdlModel.FunctionModel("Do", true, false, null,
                List.of(new CsdlModel.ParameterModel("thing", "NS.Thing", false),
                        new CsdlModel.ParameterModel("x", "Edm.String", false),
                        new CsdlModel.ParameterModel("x", "Edm.Int32", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(thing), List.of(), List.of(),
                List.of(), List.of(function), List.of(), List.of());
        OperationGenerator generator = new OperationGenerator("com.example", Map.of(), "com.example", List.of(schema));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> generator.boundOperationsFor(thing, schema));
        assertTrue(ex.getMessage().toLowerCase().contains("parameter"), ex.getMessage());
    }

    @Test
    void legalTypeOnlyOverloadsStillGenerateAndCompile(@TempDir Path tempDir) throws Exception {
        CsdlModel.FunctionModel stringArg = new CsdlModel.FunctionModel("Do", false, false, null,
                List.of(new CsdlModel.ParameterModel("x", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.FunctionModel intArg = new CsdlModel.FunctionModel("Do", false, false, null,
                List.of(new CsdlModel.ParameterModel("x", "Edm.Int32", false)),
                new CsdlModel.ReturnTypeModel("Edm.Int32", false));
        CsdlModel model = operationImportModel(stringArg, intArg);
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "app").generate(model);
        assertTrue(Files.exists(out.resolve("app/operation/DoByXFunctionRequest.java")));
        assertTrue(Files.exists(out.resolve("app/operation/DoByX_2FunctionRequest.java")));
        assertNull(CompilationHarness.compileAll(out));
    }

    @Test
    void caseOnlyBoundOperationNamesGetDeterministicUniqueRequests(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel thing = entity("Thing");
        CsdlModel.FunctionModel upper = new CsdlModel.FunctionModel("Do", true, false, null,
                List.of(new CsdlModel.ParameterModel("thing", "NS.Thing", false),
                        new CsdlModel.ParameterModel("value", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.FunctionModel lower = new CsdlModel.FunctionModel("do", true, false, null,
                List.of(new CsdlModel.ParameterModel("thing", "NS.Thing", false),
                        new CsdlModel.ParameterModel("value", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(thing), List.of(), List.of(),
                List.of(), List.of(upper, lower), List.of(), List.of());
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));
        assertTrue(Files.exists(out.resolve("com/example/operation/ThingDoFunctionRequest.java")));
        assertTrue(Files.exists(out.resolve("com/example/operation/ThingdoFunctionRequest_2.java")));
        assertNull(CompilationHarness.compileAll(out));
    }

    @Test
    void generatedTopAndSkipRejectNegativeValuesAtRuntime(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel thing = entity("Thing");
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(new CsdlModel.EntitySetModel("Things", "NS.Thing", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(thing), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(container));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));
        assertNull(CompilationHarness.compileAll(out));
        try (URLClassLoader loader = new URLClassLoader(new URL[]{out.resolve(".classes").toUri().toURL()},
                getClass().getClassLoader())) {
            io.github.akbarhusain.odata.runtime.entity.Context context =
                    io.github.akbarhusain.odata.runtime.entity.Context.builder()
                            .baseUrl("https://example.test/root").build();
            Class<?> containerClass = Class.forName("com.example.container.Container", true, loader);
            Object client = containerClass.getConstructor(
                    io.github.akbarhusain.odata.runtime.entity.Context.class).newInstance(context);
            Object request = client.getClass().getMethod("things").invoke(client);
            java.lang.reflect.InvocationTargetException top = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> request.getClass().getMethod("top", int.class).invoke(request, -1));
            assertTrue(top.getCause() instanceof IllegalArgumentException);
            java.lang.reflect.InvocationTargetException skip = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> request.getClass().getMethod("skip", int.class).invoke(request, -1));
            assertTrue(skip.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    void entityBatchGetUsesTheSameSelectAndExpandContext(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel target = entity("Target");
        CsdlModel.EntityTypeModel thing = new CsdlModel.EntityTypeModel("Thing", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Name", "Edm.String", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Targets", "NS.Target", null,
                        false, true, List.of(), List.of())));
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(new CsdlModel.EntitySetModel("Things", "NS.Thing", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(thing, target), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(container));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));
        assertNull(CompilationHarness.compileAll(out));
        try (URLClassLoader loader = new URLClassLoader(new URL[]{out.resolve(".classes").toUri().toURL()},
                getClass().getClassLoader())) {
            io.github.akbarhusain.odata.runtime.entity.Context context =
                    io.github.akbarhusain.odata.runtime.entity.Context.builder()
                            .baseUrl("https://example.test/root").build();
            Class<?> containerClass = Class.forName("com.example.container.Container", true, loader);
            Object client = containerClass.getConstructor(
                    io.github.akbarhusain.odata.runtime.entity.Context.class).newInstance(context);
            Object request = client.getClass().getMethod("things").invoke(client);
            Class<?> propertyArray = java.lang.reflect.Array.newInstance(
                    Class.forName("io.github.akbarhusain.odata.runtime.query.PropertyExpression"), 0).getClass();
            Class<?> expandableArray = java.lang.reflect.Array.newInstance(
                    Class.forName("io.github.akbarhusain.odata.runtime.query.Expandable"), 0).getClass();
            Object property = Class.forName("com.example.entity.Thing", true, loader)
                    .getField("NAME").get(null);
            Object expandable = Class.forName("com.example.entity.Thing", true, loader)
                    .getField("TARGETS").get(null);
            Object propertyArrayValue = java.lang.reflect.Array.newInstance(propertyArray.getComponentType(), 1);
            java.lang.reflect.Array.set(propertyArrayValue, 0, property);
            Object expandableArrayValue = java.lang.reflect.Array.newInstance(expandableArray.getComponentType(), 1);
            java.lang.reflect.Array.set(expandableArrayValue, 0, expandable);
            request = request.getClass().getMethod("select", propertyArray).invoke(request, propertyArrayValue);
            request = request.getClass().getMethod("expand", expandableArray).invoke(request, expandableArrayValue);
            Object operation = request.getClass().getMethod("toBatchOperation").invoke(request);
            String url = (String) operation.getClass().getMethod("url").invoke(operation);
            assertTrue(url.contains("$select=Name"), url);
            assertTrue(url.contains("$expand=Targets"), url);
        }
    }

    @Test
    void typedefOfComplexCollectionUsesTargetFilterable(@TempDir Path tempDir) throws Exception {
        CsdlModel.ComplexTypeModel address = payload("Address");
        CsdlModel.EntityTypeModel holder = new CsdlModel.EntityTypeModel("Holder", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Items", "Collection(Alias)", true, null, List.of())), List.of());
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(new CsdlModel.EntitySetModel("Holders", "NS.Holder", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(holder), List.of(address),
                List.of(), List.of(new CsdlModel.TypeDefinitionModel("Alias", "NS.Address")),
                List.of(), List.of(), List.of(container));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));
        assertNull(CompilationHarness.compileAll(out));
        String code = Files.readString(out.resolve("com/example/entity/Holder.java"));
        assertTrue(code.contains("Address.Filterable"), code);
    }

    @Test
    void reusedRequestGeneratorResolvesTheCurrentSchemaForUnqualifiedBases() {
        CsdlModel.EntityTypeModel baseA = entity("Base");
        CsdlModel.EntityTypeModel baseB = entity("Base");
        CsdlModel.EntityTypeModel derivedB = new CsdlModel.EntityTypeModel("Derived", "Base", false, false, false,
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel a = new CsdlModel.SchemaModel("NS.A", null, List.of(baseA), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel b = new CsdlModel.SchemaModel("NS.B", null, List.of(baseB, derivedB), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        RequestGenerator generator = new RequestGenerator("com.example", Map.of("NS.A", "com.a", "NS.B", "com.b"),
                "com.fallback", List.of(a, b));
        generator.generateEntityRequest(baseA, a);
        String code = generator.generateEntityRequest(derivedB, b);
        assertTrue(code.contains("public final class DerivedEntityRequest"), code);
    }

    @Test
    void requestGeneratorWithoutAllSchemasRebuildsIndexesForEachSchema() {
        CsdlModel.EntityTypeModel targetA = entity("TargetA");
        CsdlModel.EntityTypeModel targetB = entity("TargetB");
        CsdlModel.EntityTypeModel baseA = new CsdlModel.EntityTypeModel("Base", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("AOnly", "NS.A.TargetA", null, false, true, List.of(), List.of())));
        CsdlModel.EntityTypeModel baseB = new CsdlModel.EntityTypeModel("Base", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("BOnly", "NS.B.TargetB", null, false, true, List.of(), List.of())));
        CsdlModel.EntityTypeModel derivedB = new CsdlModel.EntityTypeModel("Derived", "Base", false, false, false,
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel a = new CsdlModel.SchemaModel("NS.A", null, List.of(targetA, baseA), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel b = new CsdlModel.SchemaModel("NS.B", null, List.of(targetB, baseB, derivedB), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        RequestGenerator generator = new RequestGenerator("com.example");
        generator.generateEntityRequest(baseA, a);
        String code = generator.generateEntityRequest(derivedB, b);
        assertTrue(code.contains("bOnly("), code);
        assertTrue(!code.contains("aOnly("), code);
    }

    @Test
    void hasStreamAndNamedMediaDoNotEmitDuplicateMethods(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel media = new CsdlModel.EntityTypeModel("Media", null, false, false, true,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Media", "Edm.Stream", true, null, List.of())), List.of());
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(new CsdlModel.EntitySetModel("Media", "NS.Media", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(media), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(container));
        Path out = generate(tempDir, new CsdlModel(List.of(schema), List.of()));
        assertNull(CompilationHarness.compileAll(out));
    }

    @Test
    void containerAccessorCannotShadowObjectMethod() {
        CsdlModel.EntityTypeModel thing = entity("Thing");
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(new CsdlModel.EntitySetModel("toString", "NS.Thing", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(thing), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(container));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ContainerGenerator("com.example", Map.of(), "com.example", List.of(schema))
                        .generate(container, schema));
        assertTrue(ex.getMessage().contains("toString"), ex.getMessage());
    }

    @Test
    void entityRequestAccessorCannotShadowGeneratedMethod() {
        CsdlModel.EntityTypeModel target = entity("Target");
        CsdlModel.EntityTypeModel thing = new CsdlModel.EntityTypeModel("Thing", null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("get", "NS.Target", null, false, true, List.of(), List.of())));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(target, thing), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RequestGenerator("com.example", Map.of(), "com.example", List.of(schema))
                        .generateEntityRequest(thing, schema));
        assertTrue(ex.getMessage().contains("get"), ex.getMessage());
        assertTrue(ex.getMessage().contains("collides"), ex.getMessage());
    }

    @Test
    void boundOperationAccessorCannotShadowObjectMethod() {
        CsdlModel.EntityTypeModel thing = entity("Thing");
        CsdlModel.FunctionModel function = new CsdlModel.FunctionModel("getClass", true, false, null,
                List.of(new CsdlModel.ParameterModel("thing", "NS.Thing", false)), new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(thing), List.of(), List.of(),
                List.of(), List.of(function), List.of(), List.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RequestGenerator("com.example", Map.of(), "com.example", List.of(schema))
                        .generateEntityRequest(thing, schema));
        assertTrue(ex.getMessage().contains("getClass"), ex.getMessage());
    }

    @Test
    void boundOperationRequestAndEntityAccessorUseContestedStructuredTypes(@TempDir Path tempDir) throws Exception {
        CsdlModel model = crossSchemaModel(true);
        Path out = generate(tempDir, model);

        assertNull(CompilationHarness.compileAll(out));
        String operation = Files.readString(out.resolve("com/a/operation/ThingDoBoundFunctionRequest.java"));
        String request = Files.readString(out.resolve("com/a/entity/request/ThingEntityRequest.java"));
        assertTrue(operation.contains("com.a.complex.Payload"), operation);
        assertTrue(operation.contains("com.b.complex.Payload"), operation);
        assertTrue(request.contains("com.a.complex.Payload"), request);
        assertTrue(request.contains("com.b.complex.Payload"), request);
    }

    private static CsdlModel operationImportModel(CsdlModel.FunctionModel... functions) {
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(), List.of(),
                List.of(new CsdlModel.FunctionImportModel("Do", "NS.Do", null, false)), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(), List.of(), List.of(),
                List.of(), List.of(functions), List.of(), List.of(container));
        return new CsdlModel(List.of(schema), List.of());
    }

    private static Path generate(Path tempDir, CsdlModel model) throws Exception {
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of("NS.A", "com.a", "NS.B", "com.b"), "com.fallback")
                .generate(model);
        return out;
    }

    private static CsdlModel crossSchemaModel(boolean bound) {
        CsdlModel.EntityTypeModel thingA = entity("Thing");
        CsdlModel.EntityTypeModel thingB = entity("Thing");
        CsdlModel.ComplexTypeModel payloadA = payload("Payload");
        CsdlModel.ComplexTypeModel payloadB = payload("Payload");
        CsdlModel.FunctionModel unbound = new CsdlModel.FunctionModel("Do", false, false, null,
                List.of(new CsdlModel.ParameterModel("left", "NS.A.Payload", false),
                        new CsdlModel.ParameterModel("right", "NS.B.Payload", false)),
                new CsdlModel.ReturnTypeModel("NS.A.Payload", false));
        List<CsdlModel.FunctionModel> functions = new java.util.ArrayList<>();
        functions.add(unbound);
        if (bound) {
            functions.add(new CsdlModel.FunctionModel("DoBound", true, false, null,
                    List.of(new CsdlModel.ParameterModel("thing", "NS.A.Thing", false),
                            new CsdlModel.ParameterModel("left", "NS.A.Payload", false),
                            new CsdlModel.ParameterModel("right", "NS.B.Payload", false)),
                    new CsdlModel.ReturnTypeModel("NS.A.Payload", false)));
        }
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(new CsdlModel.EntitySetModel("Things", "NS.A.Thing", List.of(), List.of())),
                List.of(), List.of(new CsdlModel.FunctionImportModel("Do", "NS.A.Do", null, false)), List.of());
        CsdlModel.SchemaModel a = new CsdlModel.SchemaModel("NS.A", null, List.of(thingA),
                List.of(payloadA), List.of(), List.of(), functions, List.of(), List.of(container));
        CsdlModel.SchemaModel b = new CsdlModel.SchemaModel("NS.B", null, List.of(thingB),
                List.of(payloadB), List.of(), List.of(), List.of(), List.of(), List.of());
        return new CsdlModel(List.of(a, b), List.of());
    }

    private static CsdlModel.EntityTypeModel entity(String name) {
        return new CsdlModel.EntityTypeModel(name, null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())), List.of());
    }

    private static CsdlModel.ComplexTypeModel payload(String name) {
        return new CsdlModel.ComplexTypeModel(name, null, false, false,
                List.of(new CsdlModel.PropertyModel("City", "Edm.String", true, null, List.of())), List.of());
    }
}
