package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmedRegressionTest {

    @Test
    void operationParametersThatFoldToContextGetUniqueNamesAndCompile(@TempDir Path tempDir) throws Exception {
        CsdlModel.FunctionModel function = new CsdlModel.FunctionModel("Do", false, false, null,
                List.of(
                        new CsdlModel.ParameterModel("context", "Edm.String", false),
                        new CsdlModel.ParameterModel("context_", "Edm.String", false),
                        new CsdlModel.ParameterModel("basePath", "Edm.String", false),
                        new CsdlModel.ParameterModel("body", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "app").generate(operationModel(function));

        String code = Files.readString(out.resolve("app/operation/DoFunctionRequest.java"));
        assertTrue(code.contains("String context_"), code);
        assertTrue(code.contains("String context__2"), code);
        assertNull(CompilationHarness.compileAll(out), code);
    }

    @Test
    void failedGenerationRemovesNewFilesAndRestoresPriorOutput(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out");
        Generator generator = new Generator(out, Map.of(), "com.example");
        generator.generate(model(entity("Good", List.of())));
        List<Path> committed = generator.writtenFiles();
        Path prior = out.resolve("com/example/entity/Good.java");
        String priorCode = Files.readString(prior);

        CsdlModel.EntityTypeModel broken = new CsdlModel.EntityTypeModel("Broken", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Missing", "NS.DoesNotExist", true, null, List.of()),
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        assertThrows(IllegalStateException.class,
                () -> generator.generate(model(entity("New", List.of()), broken)));

        assertEquals(committed, generator.writtenFiles());
        assertEquals(priorCode, Files.readString(prior));
        assertFalse(Files.exists(out.resolve("com/example/entity/New.java")));
        assertFalse(Files.exists(out.resolve("com/example/entity/request/NewEntityRequest.java")));
        assertFalse(Files.exists(out.resolve("com/example/collection/request/NewCollectionRequest.java")));
    }

    @Test
    void enumAndTypedefOfEnumKeysGenerateAndCompile(@TempDir Path tempDir) throws Exception {
        CsdlModel.EnumTypeModel status = new CsdlModel.EnumTypeModel("Status", "Edm.Int32", false,
                List.of(new CsdlModel.EnumMemberModel("Active", 1), new CsdlModel.EnumMemberModel("Inactive", 0)));
        CsdlModel.EntityTypeModel direct = keyedEntity("Direct", "Status", "NS.Status");
        CsdlModel.EntityTypeModel alias = keyedEntity("Alias", "StatusAlias", "NS.StatusAlias");
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Keys", null,
                List.of(new CsdlModel.EntitySetModel("Directs", "NS.Direct", List.of(), List.of()),
                        new CsdlModel.EntitySetModel("Aliases", "NS.Alias", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(direct, alias), List.of(), List.of(status),
                List.of(new CsdlModel.TypeDefinitionModel("StatusAlias", "NS.Status")),
                List.of(), List.of(), List.of(container));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));

        String containerCode = Files.readString(out.resolve("com/example/container/Keys.java"));
        assertTrue(containerCode.contains("com.example.enums.Status status"), containerCode);
        assertTrue(containerCode.contains("com.example.enums.Status statusAlias"), containerCode);
        assertNull(CompilationHarness.compileAll(out), containerCode);
    }

    @Test
    void closedTypesDoNotReserveDynamicPropertyMethods(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel closedEntity = entity("ClosedEntity", List.of(
                new CsdlModel.PropertyModel("DynamicProperty", "Edm.String", true, null, List.of()),
                new CsdlModel.PropertyModel("PutDynamicProperty", "Edm.String", true, null, List.of())));
        CsdlModel.ComplexTypeModel closedComplex = new CsdlModel.ComplexTypeModel("ClosedComplex", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("DynamicProperty", "Edm.String", true, null, List.of()),
                        new CsdlModel.PropertyModel("PutDynamicProperty", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Closed", null,
                List.of(new CsdlModel.EntitySetModel("ClosedEntities", "NS.ClosedEntity", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(closedEntity), List.of(closedComplex), List.of(), List.of(), List.of(), List.of(),
                List.of(container));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));
        String entityCode = Files.readString(out.resolve("com/example/entity/ClosedEntity.java"));
        String complexCode = Files.readString(out.resolve("com/example/complex/ClosedComplex.java"));
        assertFalse(entityCode.contains("setDynamicProperty(String name, Object value)"), entityCode);
        assertFalse(complexCode.contains("setDynamicProperty(String name, Object value)"), complexCode);
        assertTrue(entityCode.contains("getDynamicProperty()"), entityCode);
        assertTrue(complexCode.contains("getDynamicProperty()"), complexCode);
        assertNull(CompilationHarness.compileAll(out), entityCode + complexCode);
    }

    @Test
    void openTypesStillReserveDynamicPropertyMethods() {
        CsdlModel.EntityTypeModel open = new CsdlModel.EntityTypeModel("OpenEntity", null,
                true, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("DynamicProperty", "Edm.String", true, null, List.of()),
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(open), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example").generate(open, schema));
    }

    @Test
    void countValueRejectsApplyInsteadOfSilentlyDroppingIt(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel thing = entity("Thing", List.of());
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(new CsdlModel.EntitySetModel("Things", "NS.Thing", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(thing), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(container));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(new CsdlModel(List.of(schema), List.of()));
        String code = Files.readString(out.resolve("com/example/collection/request/ThingCollectionRequest.java"));
        assertTrue(code.contains("if (applyExpr != null)"), code);
        assertTrue(code.contains("countValue cannot be combined with $apply"), code);
        assertFalse(code.contains("tmp.applyExpr = null;"), code);
        assertNull(CompilationHarness.compileAll(out), code);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{out.resolve(".classes").toUri().toURL()},
                getClass().getClassLoader())) {
            io.github.akbarhusain.odata.runtime.entity.Context context =
                    io.github.akbarhusain.odata.runtime.entity.Context.builder()
                            .baseUrl("https://example.test/root").build();
            Class<?> containerClass = Class.forName("com.example.container.Container", true, loader);
            Object client = containerClass.getConstructor(
                    io.github.akbarhusain.odata.runtime.entity.Context.class).newInstance(context);
            Object request = client.getClass().getMethod("things").invoke(client);
            request = request.getClass().getMethod("apply", String.class)
                    .invoke(request, "filter(Id gt 0)");
            Object appliedRequest = request;
            java.lang.reflect.InvocationTargetException error = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> appliedRequest.getClass().getMethod("countValue").invoke(appliedRequest));
            assertTrue(error.getCause() instanceof IllegalArgumentException, String.valueOf(error.getCause()));
        }
    }

    @Test
    void nullableCollectionElementsSurviveBuilderAndWithCopies(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel holder = entity("Holder", List.of(
                new CsdlModel.PropertyModel("Values", "Collection(Edm.String)", true, null, List.of())));
        CsdlModel.ComplexTypeModel complex = new CsdlModel.ComplexTypeModel("Info", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Values", "Collection(Edm.String)", true, null, List.of())),
                List.of());
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(new CsdlModel.EntitySetModel("Holders", "NS.Holder", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(holder), List.of(complex), List.of(), List.of(), List.of(), List.of(), List.of(container));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").withGenerateWithMethods(true)
                .generate(new CsdlModel(List.of(schema), List.of()));
        assertNull(CompilationHarness.compileAll(out));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{out.resolve(".classes").toUri().toURL()},
                getClass().getClassLoader())) {
            List<String> values = new ArrayList<>(Arrays.asList("one", null));
            Class<?> holderClass = Class.forName("com.example.entity.Holder", true, loader);
            Object builder = holderClass.getMethod("builder").invoke(null);
            builder.getClass().getMethod("values", List.class).invoke(builder, values);
            builder.getClass().getMethod("id", Integer.class).invoke(builder, 1);
            Object built = builder.getClass().getMethod("build").invoke(builder);
            assertEquals(values, holderClass.getMethod("getValues").invoke(built));
            @SuppressWarnings({"rawtypes", "unchecked"})
            List<Object> builtValues = (List) holderClass.getMethod("getValues").invoke(built);
            assertThrows(UnsupportedOperationException.class, () -> builtValues.add("later"));

            Object instance = holderClass.getConstructor().newInstance();
            Object changed = holderClass.getMethod("withValues", List.class).invoke(instance, values);
            assertEquals(values, holderClass.getMethod("getValues").invoke(changed));

            Class<?> infoClass = Class.forName("com.example.complex.Info", true, loader);
            Object complexBuilder = infoClass.getMethod("builder").invoke(null);
            complexBuilder.getClass().getMethod("values", List.class).invoke(complexBuilder, values);
            Object complexBuilt = complexBuilder.getClass().getMethod("build").invoke(complexBuilder);
            assertEquals(values, infoClass.getMethod("getValues").invoke(complexBuilt));
        }
    }

    @Test
    void reusedContainerGeneratorDoesNotPinTheFirstSchema() {
        CsdlModel.EntityTypeModel first = entity("First", List.of());
        CsdlModel.EntityTypeModel second = entity("Second", List.of());
        CsdlModel.ContainerModel firstContainer = new CsdlModel.ContainerModel("FirstContainer", null,
                List.of(new CsdlModel.EntitySetModel("Firsts", "NS.First.First", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.ContainerModel secondContainer = new CsdlModel.ContainerModel("SecondContainer", null,
                List.of(new CsdlModel.EntitySetModel("Seconds", "NS.Second.Second", List.of(), List.of())),
                List.of(), List.of(), List.of());
        CsdlModel.SchemaModel firstSchema = new CsdlModel.SchemaModel("NS.First", null,
                List.of(first), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(firstContainer));
        CsdlModel.SchemaModel secondSchema = new CsdlModel.SchemaModel("NS.Second", null,
                List.of(second), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(secondContainer));

        ContainerGenerator generator = new ContainerGenerator("com.example", Map.of(), "com.example");
        String firstCode = generator.generate(firstContainer, firstSchema);
        String secondCode = generator.generate(secondContainer, secondSchema);
        assertTrue(firstCode.contains("FirstCollectionRequest"), firstCode);
        assertTrue(secondCode.contains("SecondCollectionRequest"), secondCode);
        assertFalse(secondCode.contains("FirstCollectionRequest"), secondCode);
    }

    @Test
    void unsupportedBoundOperationWarningIsEmittedOncePerResolver() {
        CsdlModel.EntityTypeModel first = entity("First", List.of());
        CsdlModel.EntityTypeModel second = entity("Second", List.of());
        CsdlModel.FunctionModel invalid = new CsdlModel.FunctionModel("InvalidBinding", true, false, null,
                List.of(new CsdlModel.ParameterModel("value", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(first, second), List.of(), List.of(), List.of(), List.of(invalid), List.of(), List.of());

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            RequestGenerator generator = new RequestGenerator("com.example");
            generator.generateEntityRequest(first, schema);
            generator.generateEntityRequest(second, schema);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setErr(original);
        }
        String warnings = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, count(warnings, "Skipping bound operation 'InvalidBinding'"), warnings);
    }

    private static int count(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static CsdlModel.EntityTypeModel keyedEntity(String name, String keyName, String keyType) {
        return new CsdlModel.EntityTypeModel(name, null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of(keyName))),
                List.of(new CsdlModel.PropertyModel(keyName, keyType, false, null, List.of())), List.of());
    }

    private static CsdlModel.EntityTypeModel entity(String name, List<CsdlModel.PropertyModel> properties) {
        List<CsdlModel.PropertyModel> all = new ArrayList<>(properties);
        all.add(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()));
        return new CsdlModel.EntityTypeModel(name, null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))), all, List.of());
    }

    private static CsdlModel model(CsdlModel.EntityTypeModel... entities) {
        return new CsdlModel(List.of(new CsdlModel.SchemaModel("NS", null, List.of(entities),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of())), List.of());
    }

    private static CsdlModel operationModel(CsdlModel.FunctionModel... functions) {
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("Container", null,
                List.of(), List.of(),
                List.of(new CsdlModel.FunctionImportModel("Do", "NS.Do", null, false)), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(), List.of(), List.of(), List.of(), List.of(functions), List.of(), List.of(container));
        return new CsdlModel(List.of(schema), List.of());
    }
}
