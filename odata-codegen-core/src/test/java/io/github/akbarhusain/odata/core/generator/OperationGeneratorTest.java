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
        assertTrue(code.contains("EntityOperations.invokeSync(context, contextPath, HttpMethod.GET, null, "
                + "Airport.class, ServiceSchemaInfo.INSTANCE)"),
                "entity results pass the registry for polymorphic @odata.type reads (decision 46 parity)");
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

    // ---- Structured/enum/collection parameter shapes (review round 6: H1/M1/M2) ----

    /**
     * Schema with an enum (Color), a complex (Address) and an entity (Person) so imports
     * can be asserted for parameter and result shapes TripPin lacks.
     */
    private static CsdlModel.SchemaModel opsSchema() {
        CsdlModel.EnumTypeModel color = new CsdlModel.EnumTypeModel("Color", "Edm.Int32", false,
                List.of(new CsdlModel.EnumMemberModel("Red", 0)));
        CsdlModel.ComplexTypeModel address = new CsdlModel.ComplexTypeModel("Address", null,
                false, false, List.of(), List.of());
        CsdlModel.EntityTypeModel person = new CsdlModel.EntityTypeModel("Person", null,
                false, false, false, List.of(), List.of(), List.of());
        return new CsdlModel.SchemaModel("NS", null, List.of(person), List.of(address),
                List.of(color), List.of(), List.of(), List.of(), List.of());
    }

    private static CsdlModel.ContainerModel container(CsdlModel.FunctionImportModel fi,
                                                      CsdlModel.ActionImportModel ai) {
        return new CsdlModel.ContainerModel("DefaultContainer", List.of(), List.of(),
                fi == null ? List.of() : List.of(fi), ai == null ? List.of() : List.of(ai));
    }

    private static CsdlModel model(CsdlModel.SchemaModel schema, CsdlModel.ContainerModel container) {
        return new CsdlModel(List.of(new CsdlModel.SchemaModel(schema.namespace(), schema.alias(),
                schema.entityTypes(), schema.complexTypes(), schema.enumTypes(), schema.typeDefinitions(),
                schema.functions(), schema.actions(), List.of(container))), List.of());
    }

    private String functionCode(CsdlModel.FunctionModel fn, String importName) throws Exception {
        CsdlModel.SchemaModel schema = opsSchema();
        schema = new CsdlModel.SchemaModel(schema.namespace(), schema.alias(), schema.entityTypes(),
                schema.complexTypes(), schema.enumTypes(), schema.typeDefinitions(),
                List.of(fn), schema.actions(), schema.containers());
        CsdlModel m = model(schema, container(
                new CsdlModel.FunctionImportModel(importName, "NS." + fn.name(), null, false), null));
        CsdlModel.SchemaModel app = m.schemas().get(0);
        return new OperationGenerator("com.example.app", Map.of(), "com.example.app", m.schemas())
                .generateFunctionImportRequest(app.containers().get(0).functionImports().get(0), app);
    }

    private String actionCode(CsdlModel.ActionModel ac, String importName) throws Exception {
        CsdlModel.SchemaModel schema = opsSchema();
        schema = new CsdlModel.SchemaModel(schema.namespace(), schema.alias(), schema.entityTypes(),
                schema.complexTypes(), schema.enumTypes(), schema.typeDefinitions(),
                schema.functions(), List.of(ac), schema.containers());
        CsdlModel m = model(schema, container(null,
                new CsdlModel.ActionImportModel(importName, "NS." + ac.name(), null)));
        CsdlModel.SchemaModel app = m.schemas().get(0);
        return new OperationGenerator("com.example.app", Map.of(), "com.example.app", m.schemas())
                .generateActionImportRequest(app.containers().get(0).actionImports().get(0), app);
    }

    @Test
    void enumFunctionParameterImportsTheEnumClass() throws Exception {
        CsdlModel.FunctionModel fn = new CsdlModel.FunctionModel("PickColor", false, false, null,
                List.of(new CsdlModel.ParameterModel("c", "NS.Color", false)),
                new CsdlModel.ReturnTypeModel("Edm.String", false));

        String code = functionCode(fn, "pickColor");

        assertTrue(code.contains("import com.example.app.enums.Color;"),
                "enum parameters need their import: " + code);
        assertTrue(code.contains("public PickColorFunctionRequest(Context context, Color c)"));
        assertTrue(code.contains("OperationPath.parameter(c, \"NS.Color\")"),
                "enum literals format via the qualified Edm name");
    }

    @Test
    void structuredActionParameterImportsTheComplexClass() throws Exception {
        CsdlModel.ActionModel ac = new CsdlModel.ActionModel("ShipTo", false, null,
                List.of(new CsdlModel.ParameterModel("addr", "NS.Address", false)),
                new CsdlModel.ReturnTypeModel("Edm.Int32", false));

        String code = actionCode(ac, "shipTo");

        assertTrue(code.contains("import com.example.app.complex.Address;"),
                "structured action parameters need their import: " + code);
        assertTrue(code.contains("public ShipToActionRequest(Context context, Address addr)"));
    }

    @Test
    void actionCollectionParameterRendersTypedListWithImport() throws Exception {
        CsdlModel.ActionModel ac = new CsdlModel.ActionModel("AddTags", false, null,
                List.of(new CsdlModel.ParameterModel("tags", "Collection(Edm.String)", false)),
                new CsdlModel.ReturnTypeModel("Edm.Int32", false));

        String code = actionCode(ac, "addTags");

        assertTrue(code.contains("public AddTagsActionRequest(Context context, List<String> tags)"),
                "collection action parameters map to List<element>: " + code);
        assertTrue(code.contains("import java.util.List;"));
        assertFalse(code.contains("String_"), "no garbage types from Collection(...) name mangling");
    }

    @Test
    void actionCollectionOfStructuredParameterRendersTypedListWithElementImport() throws Exception {
        CsdlModel.ActionModel ac = new CsdlModel.ActionModel("Bulk", false, null,
                List.of(new CsdlModel.ParameterModel("items", "Collection(NS.Address)", false)),
                null);

        String code = actionCode(ac, "bulk");

        assertTrue(code.contains("public BulkActionRequest(Context context, List<Address> items)"),
                "structured collection elements keep their generated type: " + code);
        assertTrue(code.contains("import com.example.app.complex.Address;"));
        assertTrue(code.contains("import java.util.List;"));
    }

    @Test
    void wireParameterNamesUseCsdLNamesNotSanitizedFieldNames() throws Exception {
        CsdlModel.FunctionModel fn = new CsdlModel.FunctionModel("Find", false, false, null,
                List.of(new CsdlModel.ParameterModel("class", "Edm.String", false),
                        new CsdlModel.ParameterModel("First-Name", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("Edm.Int32", false));

        String code = functionCode(fn, "find");

        assertTrue(code.contains("__pairs.add(\"class=\" + OperationPath.parameter(class_, \"Edm.String\"))"),
                "wire name is the CSDL parameter name, Java identifier is only local: " + code);
        assertTrue(code.contains("__pairs.add(\"First-Name=\" + OperationPath.parameter(first_Name, \"Edm.String\"))"));
    }

    @Test
    void actionBodyKeysUseCsdLParameterNames() throws Exception {
        CsdlModel.ActionModel ac = new CsdlModel.ActionModel("Rename", false, null,
                List.of(new CsdlModel.ParameterModel("new-name", "Edm.String", false)),
                null);

        String code = actionCode(ac, "rename");

        assertTrue(code.contains("__params.put(\"new-name\", new_name)"));
    }

    // ---- H3 wiring: complex/enum results unwrap the value envelope; M4: SchemaInfo ----

    @Test
    void complexReturnUsesWrappedVariantWithSchemaInfo() throws Exception {
        CsdlModel.FunctionModel fn = new CsdlModel.FunctionModel("HomeAddress", false, false, null,
                List.of(new CsdlModel.ParameterModel("who", "Edm.String", false)),
                new CsdlModel.ReturnTypeModel("NS.Address", false));

        String code = functionCode(fn, "homeAddress");

        assertTrue(code.contains("EntityOperations.invokeComplexSync(context, contextPath, "
                        + "HttpMethod.GET, null, Address.class, ServiceSchemaInfo.INSTANCE)"),
                "complex results are value-wrapped on the wire — routed to the wrapped variant: " + code);
        assertTrue(code.contains("EntityOperations.invokeComplexAsync(context, contextPath, HttpMethod.GET, "
                + "null, Address.class, ServiceSchemaInfo.INSTANCE)"),
                "async parity uses the wrapped variant too");
        assertTrue(code.contains("import com.example.app.complex.Address;"));
        assertTrue(code.contains("import com.example.app.schema.ServiceSchemaInfo;"),
                "polymorphic @odata.type reads need the registry import");
    }

    @Test
    void entityCollectionReturnPassesSchemaInfo() throws Exception {
        CsdlModel.FunctionModel fn = new CsdlModel.FunctionModel("AllPeople", false, false, null,
                List.of(), new CsdlModel.ReturnTypeModel("Collection(NS.Person)", false));

        String code = functionCode(fn, "allPeople");

        assertTrue(code.contains("EntityOperations.executeAndGetCollection(\n"
                + "                context, contextPath, Person.class, ServiceSchemaInfo.INSTANCE)"),
                "collection reads pass the registry for polymorphic elements: " + code);
        assertTrue(code.contains("import com.example.app.schema.ServiceSchemaInfo;"));
        assertTrue(code.contains("import io.github.akbarhusain.odata.runtime.paging.CollectionPage;"),
                "object-collection results reference CollectionPage — the import is required: " + code);
    }
}
