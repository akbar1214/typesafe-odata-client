package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FunctionImport / ActionImport generation (request-object style): each import
 * generates {@code <Name>FunctionRequest} / {@code <Name>ActionRequest} in the
 * {@code .operation} package; the container emits one accessor per import.
 */
class OperationGeneratorTest {

    private final CsdlModel trippin;
    private final CsdlModel.SchemaModel schema;

    private static CsdlModel load(String path) {
        try (InputStream is = OperationGeneratorTest.class.getResourceAsStream(path)) {
            return new StaxCsdlParser().parse(is);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    OperationGeneratorTest() {
        this.trippin = load("/trippin-metadata.xml");
        this.schema = trippin.schemas().get(0);
    }

    private OperationGenerator generator() {
        return new OperationGenerator("com.example.trippin", Map.of(), "com.example.trippin", trippin.schemas());
    }

    private CsdlModel.FunctionImportModel fi(String name) {
        return schema.containers().get(0).functionImports().stream()
                .filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private CsdlModel.ActionImportModel ai(String name) {
        return schema.containers().get(0).actionImports().stream()
                .filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    // ---- TripPin GetNearestAirport: params lat/lon Edm.Double → Airport ----

    @Test
    void functionImportGeneratesTypedOperationClassInOperationPackage() throws Exception {
        String code = generator().generateFunctionImportRequest(fi("GetNearestAirport"), schema);
        assertTrue(code.contains("package com.example.trippin.operation;"),
                "operation requests live in the .operation package");
        assertTrue(code.contains("public final class GetNearestAirportFunctionRequest"),
                "class name is <ImportName>FunctionRequest");
    }

    @Test
    void functionImportConstructorTypesParametersFromEdmType() throws Exception {
        String code = generator().generateFunctionImportRequest(fi("GetNearestAirport"), schema);
        assertTrue(code.contains(
                        "public GetNearestAirportFunctionRequest(Context context, double lat, double lon)"),
                "one Java-typed constructor parameter per CSDL Parameter");
        assertTrue(code.contains("OperationPath.segment(\"GetNearestAirport\""),
                "invocation segment is built from the IMPORT name");
        assertTrue(code.contains("\"lat=\" + OperationPath.parameter(lat, \"Edm.Double\")"));
        assertTrue(code.contains("\"lon=\" + OperationPath.parameter(lon, \"Edm.Double\")"));
    }

    @Test
    void functionImportEntityResultExecutesAsGetAndDeserializesToReturnType() throws Exception {
        String code = generator().generateFunctionImportRequest(fi("GetNearestAirport"), schema);
        assertEquals(1, count(code, "public Airport execute()"), "single sync execute()");
        assertTrue(code.contains(
                "EntityOperations.invokeSync(context, contextPath, HttpMethod.GET, null, Airport.class)"));
        assertTrue(code.contains("public CompletableFuture<Airport> executeAsync()"),
                "async parity required");
    }

    // ---- TripPin ResetDataSource: parameterless void action ----

    @Test
    void actionImportWithoutParametersExecutesVoidPost() throws Exception {
        String code = generator().generateActionImportRequest(ai("ResetDataSource"), schema);
        assertTrue(code.contains("public final class ResetDataSourceActionRequest"));
        assertTrue(code.contains("public ResetDataSourceActionRequest(Context context)"),
                "parameterless action constructor takes only Context");
        assertTrue(code.contains("public void execute()"), "no ReturnType → void execute()");
        assertTrue(code.contains("EntityOperations.invokeVoidSync(context, contextPath, HttpMethod.POST, null)"));
        assertFalse(code.contains("executeAsync"), "void async deferred (documented scope)");
    }

    // ---- Return-kind matrix on synthetic metadata ----

    /**
     * Builds a two-schema CSDL: operation defined in {@code Ops.NS}, container import in
     * {@code App.NS} referencing it by qualified name — exercises cross-schema resolution.
     */
    private static CsdlModel synthetic(String opName, String returnType, boolean nullableReturn,
                                       String importName) {
        CsdlModel.FunctionModel fn = new CsdlModel.FunctionModel(opName, false, false, null,
                List.of(), nullableReturn ? new CsdlModel.ReturnTypeModel(returnType, true)
                                          : new CsdlModel.ReturnTypeModel(returnType, false));
        CsdlModel.SchemaModel opSchema = new CsdlModel.SchemaModel("Ops.NS", null,
                List.of(), List.of(), List.of(), List.of(), List.of(fn), List.of(), List.of());
        CsdlModel.SchemaModel appSchema = new CsdlModel.SchemaModel("App.NS", null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new CsdlModel.ContainerModel("DefaultContainer", List.of(), List.of(),
                        List.of(new CsdlModel.FunctionImportModel(importName, "Ops.NS." + opName, null, false)),
                        List.of())));
        return new CsdlModel(List.of(appSchema, opSchema), List.of());
    }

    private static String syntheticCode(CsdlModel model, String importName) throws Exception {
        CsdlModel.SchemaModel appSchema = model.schemas().get(0);
        return new OperationGenerator("com.example.testapp", Map.of(), "com.example.testapp", model.schemas())
                .generateFunctionImportRequest(
                        appSchema.containers().get(0).functionImports().get(0), appSchema);
    }

    @Test
    void nonNullablePrimitiveReturnIsBareTypedResult() throws Exception {
        String code = syntheticCode(synthetic("TotalOrders", "Edm.Int32", false, "Total"),
                "Total");
        assertEquals(1, count(code, "public Integer execute()"),
                "non-nullable primitive result → bare Integer (boxed per repo convention)");
        assertTrue(code.contains(
                "EntityOperations.invokePrimitiveSync(context, contextPath, HttpMethod.GET, null, Integer.class)"));
    }

    @Test
    void nullablePrimitiveReturnWrapsInOptional() throws Exception {
        String code = syntheticCode(synthetic("MaybeName", "Edm.String", true, "Maybe"),
                "Maybe");
        assertEquals(1, count(code, "public Optional<String> execute()"),
                "nullable ReturnType → Optional<T>");
        assertTrue(code.contains("Optional.ofNullable("),
                "execute wraps the possibly-null primitive in Optional.ofNullable");
        assertTrue(code.contains(
                "EntityOperations.invokePrimitiveSync(context, contextPath, HttpMethod.GET, null, String.class)"));
    }

    @Test
    void collectionOfPrimitiveReturnRendersTypedListViaCollectionVariant() throws Exception {
        String code = syntheticCode(synthetic("AllTags", "Collection(Edm.String)", false, "Tags"),
                "Tags");
        assertEquals(1, count(code, "public List<String> execute()"));
        assertTrue(code.contains(
                "EntityOperations.invokePrimitiveCollectionSync(context, contextPath,"));
        assertTrue(code.contains("HttpMethod.GET, null, String.class);"));
    }

    private static int count(String haystack, String needle) {
        int n = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { n++; idx += needle.length(); }
        return n;
    }
}
