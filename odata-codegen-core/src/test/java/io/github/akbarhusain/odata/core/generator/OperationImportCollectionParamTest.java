package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Collection-typed FUNCTION parameters ride OData parameter aliases (URL Conventions
 * §5.1.1): a collection literal cannot be embedded inline in the invocation path, so
 * the generated request builds {@code ByIds(ids=@p0)?@p0=['a','b']} — the pair in the
 * segment references the alias, and the alias value travels as a query option. Scalar
 * parameters stay inline. Previously ANY collection parameter failed generation
 * ("cannot be embedded in an invocation URL").
 */
class OperationImportCollectionParamTest {

    private static CsdlModel model(CsdlModel.FunctionModel... functions) {
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("DefaultContainer",
                List.of(), List.of(),
                List.of(new CsdlModel.FunctionImportModel("byIds", "NS.ByIds", null, false)),
                List.of());
        CsdlModel.SchemaModel ns = new CsdlModel.SchemaModel("NS", null, List.of(), List.of(),
                List.of(), List.of(), List.of(functions), List.of(), List.of(container));
        return new CsdlModel(List.of(ns), List.of());
    }

    private static CsdlModel.FunctionModel fn(List<CsdlModel.ParameterModel> params) {
        return new CsdlModel.FunctionModel("ByIds", false, false, null, params,
                new CsdlModel.ReturnTypeModel("Edm.Int32", false));
    }

    private static CsdlModel.SchemaModel schema(CsdlModel m) {
        return m.schemas().get(0);
    }

    private static String generate(CsdlModel m) {
        return new OperationGenerator("app", Map.of(), "app", m.schemas())
                .generateFunctionImportRequest(
                        schema(m).containers().get(0).functionImports().get(0), schema(m));
    }

    @Test
    void collectionParameterMapsToListAndAliasPair() {
        String code = generate(model(fn(List.of(
                new CsdlModel.ParameterModel("ids", "Collection(Edm.String)", false)))));
        assertTrue(code.contains("public ByIdsFunctionRequest(Context context, List<String> ids)"),
                "collection parameter maps to List<element>: " + code);
        assertTrue(code.contains("import java.util.List;"));
        assertTrue(code.contains("__pairs.add(\"ids=@p0\");"),
                "the SEGMENT pair references the alias, never an inline literal");
        assertTrue(code.contains(
                "addQuery(\"@p0\", OperationPath.collectionParameter(ids, \"Edm.String\"))"),
                "the alias value rides a query option, elements formatted by Edm type");
        assertTrue(code.contains("OperationPath.segment(\"byIds\""),
                "invocation segment is still the IMPORT name");
        assertFalse(code.contains("ids=\" + OperationPath.parameter"),
                "no inline scalar rendering for a collection parameter");
    }

    @Test
    void requiredCollectionParameterKeepsNonNullGuard() {
        String code = generate(model(fn(List.of(
                new CsdlModel.ParameterModel("ids", "Collection(Edm.String)", false)))));
        assertTrue(code.contains(
                "throw new IllegalArgumentException(\"Parameter 'ids' is non-nullable and must not be null\")"),
                "a non-nullable collection still guards against null like scalars do");
    }

    @Test
    void mixedParamsAliasOnlyTheCollections() {
        String code = generate(model(fn(List.of(
                new CsdlModel.ParameterModel("min", "Edm.Double", false),
                new CsdlModel.ParameterModel("tags", "Collection(Edm.String)", false)))));
        assertTrue(code.contains("\"min=\" + OperationPath.parameter(min, \"Edm.Double\")"),
                "scalar parameters stay inline");
        assertTrue(code.contains("__pairs.add(\"tags=@p0\");"));
        assertTrue(code.contains("addQuery(\"@p0\", OperationPath.collectionParameter(tags, \"Edm.String\"))"));
        assertFalse(code.contains("@p1"), "aliases are numbered per collection parameter only");
    }

    @Test
    void nullableCollectionParameterOmitsPairAndAliasWhenNull() {
        String code = generate(model(fn(List.of(
                new CsdlModel.ParameterModel("ids", "Collection(Edm.Int32)", true)))));
        assertTrue(code.contains("if (ids != null) {\n            __pairs.add(\"ids=@p0\");"),
                "null omits the segment pair");
        assertTrue(code.contains(
                "if (ids != null) {\n            __path = __path.addQuery(\"@p0\", "
                        + "OperationPath.collectionParameter(ids, \"Edm.Int32\"))"));
        assertTrue(code.contains("public ByIdsFunctionRequest(Context context, List<Integer> ids)"),
                "nullable collection element boxes to Integer");
    }

    @Test
    void enumCollectionParameterUsesQualifiedElementType() {
        CsdlModel.EnumTypeModel color = new CsdlModel.EnumTypeModel("Color", "Edm.Int32", false,
                List.of(new CsdlModel.EnumMemberModel("Red", 0L)));
        CsdlModel.FunctionModel f = fn(List.of(
                new CsdlModel.ParameterModel("colors", "Collection(NS.Color)", false)));
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("DefaultContainer",
                List.of(), List.of(),
                List.of(new CsdlModel.FunctionImportModel("byIds", "NS.ByIds", null, false)),
                List.of());
        CsdlModel.SchemaModel ns = new CsdlModel.SchemaModel("NS", null, List.of(), List.of(),
                List.of(color), List.of(), List.of(f), List.of(), List.of(container));
        CsdlModel m = new CsdlModel(List.of(ns), List.of());

        String code = new OperationGenerator("app", Map.of(), "app", m.schemas())
                .generateFunctionImportRequest(container.functionImports().get(0), ns);
        assertTrue(code.contains("List<Color> colors"));
        assertTrue(code.contains("import app.enums.Color;"));
        assertTrue(code.contains(
                "addQuery(\"@p0\", OperationPath.collectionParameter(colors, \"NS.Color\"))"),
                "enum elements format with the qualified Edm name, like scalar enum params");
    }

    @Test
    void containerAccessorPassesCollectionThrough() {
        CsdlModel m = model(fn(List.of(
                new CsdlModel.ParameterModel("ids", "Collection(Edm.String)", false))));
        String code = new ContainerGenerator("app", Map.of(), "app", m.schemas())
                .generate(schema(m).containers().get(0), schema(m));
        assertTrue(code.contains("public ByIdsFunctionRequest byIds(List<String> ids)"));
        assertTrue(code.contains("return new ByIdsFunctionRequest(context, ids);"));
        assertTrue(code.contains("import java.util.List;"),
                "the container needs List for the accessor signature");
    }
}
