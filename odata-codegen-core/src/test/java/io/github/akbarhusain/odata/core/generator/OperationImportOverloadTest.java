package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OData v4 identifies an unbound function overload by its PARAMETER NAMES: a single
 * FunctionImport exposes every same-name unbound overload, and the invocation URL
 * picks one ({@code IsSiteAdmin(username='x')} vs {@code IsSiteAdmin(userId='y')}).
 * Generation must emit one request class + one container accessor per overload —
 * same-name functions are overloads, not ambiguity. Same-name unbound ACTIONS are
 * invalid CSDL (actions cannot be overloaded by parameter names) and still fail loudly.
 */
class OperationImportOverloadTest {

    // ------------------------------------------------------------------
    // Model builders
    // ------------------------------------------------------------------

    private static CsdlModel.ParameterModel param(String name, String type) {
        return new CsdlModel.ParameterModel(name, type, true);
    }

    private static CsdlModel.FunctionModel fn(String name, List<CsdlModel.ParameterModel> params) {
        return new CsdlModel.FunctionModel(name, false, false, null, params,
                new CsdlModel.ReturnTypeModel("Edm.Boolean", false));
    }

    private static CsdlModel.FunctionModel boundFn(String name, String bindingType) {
        return new CsdlModel.FunctionModel(name, true, false, null,
                List.of(new CsdlModel.ParameterModel("binding", bindingType, false)),
                new CsdlModel.ReturnTypeModel("Edm.Boolean", false));
    }

    private static CsdlModel model(CsdlModel.FunctionModel... functions) {
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("DefaultContainer",
                List.of(), List.of(),
                List.of(new CsdlModel.FunctionImportModel("IsSiteAdmin",
                        functions[0] == null ? null : "NS.IsSiteAdmin", null, false)),
                List.of());
        CsdlModel.SchemaModel ns = new CsdlModel.SchemaModel("NS", null, List.of(), List.of(),
                List.of(), List.of(), List.of(functions), List.of(), List.of(container));
        return new CsdlModel(List.of(ns), List.of());
    }

    private static CsdlModel.SchemaModel schema(CsdlModel m) {
        return m.schemas().get(0);
    }

    private static CsdlModel.FunctionImportModel fi(CsdlModel m) {
        return schema(m).containers().get(0).functionImports().get(0);
    }

    private static OperationGenerator generator(CsdlModel m) {
        return new OperationGenerator("app", Map.of(), "app", m.schemas());
    }

    // ------------------------------------------------------------------
    // Overloaded function imports: one request class per overload
    // ------------------------------------------------------------------

    private static CsdlModel usernameUserIdOverloads() {
        return model(
                fn("IsSiteAdmin", List.of(param("username", "Edm.String"))),
                fn("IsSiteAdmin", List.of(param("userId", "Edm.String"))));
    }

    @Test
    void overloadedFunctionImportGeneratesOneRequestClassPerOverload() {
        CsdlModel m = usernameUserIdOverloads();
        List<OperationGenerator.GeneratedOperationRequest> reqs =
                generator(m).generateFunctionImportRequests(fi(m), schema(m));

        assertEquals(List.of("IsSiteAdminByUsernameFunctionRequest", "IsSiteAdminByUserIdFunctionRequest"),
                reqs.stream().map(OperationGenerator.GeneratedOperationRequest::className).toList(),
                "class names disambiguate overloads by parameter names");

        String byUsername = reqs.get(0).code();
        assertTrue(byUsername.contains(
                "public IsSiteAdminByUsernameFunctionRequest(Context context, String username)"));
        assertTrue(byUsername.contains("\"username=\" + OperationPath.parameter(username, \"Edm.String\")"),
                "wire pair uses the overload's own parameter name");
        assertTrue(byUsername.contains("public Boolean execute()"),
                "non-nullable primitive results stay unboxed-free (boxed, no Optional) per decision 94");
        assertFalse(byUsername.contains("userId"), "first overload must not reference the second's parameter");

        String byUserId = reqs.get(1).code();
        assertTrue(byUserId.contains(
                "public IsSiteAdminByUserIdFunctionRequest(Context context, String userId)"));
        assertTrue(byUserId.contains("\"userId=\" + OperationPath.parameter(userId, \"Edm.String\")"));
        assertFalse(byUserId.contains("username"));
        assertTrue(byUserId.contains("OperationPath.segment(\"IsSiteAdmin\""),
                "overloads share ONE import segment — the parameters select the overload");
    }

    @Test
    void loneFunctionImportKeepsUnsuffixedClassName() {
        CsdlModel m = model(fn("IsSiteAdmin", List.of(param("username", "Edm.String"))));
        List<OperationGenerator.GeneratedOperationRequest> reqs =
                generator(m).generateFunctionImportRequests(fi(m), schema(m));
        assertEquals(1, reqs.size());
        assertEquals("IsSiteAdminFunctionRequest", reqs.get(0).className(),
                "a lone overload must not change the historical class name");
    }

    @Test
    void multiParameterOverloadSuffixJoinsAllParameterNames() {
        CsdlModel m = model(
                fn("IsSiteAdmin", List.of(
                        new CsdlModel.ParameterModel("a", "Edm.Int32", false),
                        new CsdlModel.ParameterModel("b", "Edm.String", false))),
                fn("IsSiteAdmin", List.of(new CsdlModel.ParameterModel("c", "Edm.Double", false))));
        List<OperationGenerator.GeneratedOperationRequest> reqs =
                generator(m).generateFunctionImportRequests(fi(m), schema(m));
        assertEquals(List.of("IsSiteAdminByAAndBFunctionRequest", "IsSiteAdminByCFunctionRequest"),
                reqs.stream().map(OperationGenerator.GeneratedOperationRequest::className).toList());
        assertTrue(reqs.get(0).code().contains(
                "public IsSiteAdminByAAndBFunctionRequest(Context context, int a, String b)"));
    }

    @Test
    void overloadsIdenticalInParameterNamesButNotTypesGenerate() {
        // ODATA-500: overloads are identified by the ORDERED SET of parameter types —
        // same names with different types are legal CSDL, distinguishable by literal form
        CsdlModel m = model(
                fn("IsSiteAdmin", List.of(param("x", "Edm.String"))),
                fn("IsSiteAdmin", List.of(param("x", "Edm.Int32"))));
        List<OperationGenerator.GeneratedOperationRequest> reqs =
                generator(m).generateFunctionImportRequests(fi(m), schema(m));
        assertEquals(2, reqs.size());
        assertTrue(reqs.stream().anyMatch(r -> r.className().equals("IsSiteAdminByXFunctionRequest")));
        assertTrue(reqs.stream().anyMatch(r -> r.className().equals("IsSiteAdminByX_2FunctionRequest")),
                "type-only overloads dedupe deterministically: "
                        + reqs.stream().map(OperationGenerator.GeneratedOperationRequest::className).toList());
    }

    @Test
    void overloadsIdenticalInNamesAndTypesStillFailLoudly() {
        // Same parameter names AND types — indistinguishable in a URL (invalid CSDL:
        // differs only by return type), must fail at generation naming the import
        CsdlModel m = model(
                fn("IsSiteAdmin", List.of(param("x", "Edm.String"))),
                fn("IsSiteAdmin", List.of(param("x", "Edm.String"))));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                generator(m).generateFunctionImportRequests(fi(m), schema(m)));
        assertTrue(ex.getMessage().contains("IsSiteAdmin"), ex.getMessage());
        assertTrue(ex.getMessage().contains("parameter names"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Bound siblings never compete with an unbound import target
    // ------------------------------------------------------------------

    @Test
    void unboundImportIgnoresBoundSiblingOverload() {
        // bound Foo(NS.Person) + unbound Foo(String) — the import references the unbound one;
        // previously this threw "Ambiguous operation reference"
        CsdlModel m = model(
                boundFn("IsSiteAdmin", "NS.Person"),
                fn("IsSiteAdmin", List.of(param("username", "Edm.String"))));
        List<OperationGenerator.GeneratedOperationRequest> reqs =
                generator(m).generateFunctionImportRequests(fi(m), schema(m));
        assertEquals(1, reqs.size());
        assertEquals("IsSiteAdminFunctionRequest", reqs.get(0).className());
        assertTrue(reqs.get(0).code().contains("String username"));
    }

    // ------------------------------------------------------------------
    // Actions: no parameter-name overloading
    // ------------------------------------------------------------------

    private static CsdlModel actionModel(CsdlModel.ActionModel... actions) {
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("DefaultContainer",
                List.of(), List.of(), List.of(),
                List.of(new CsdlModel.ActionImportModel("zap", "NS.Zap", null)));
        CsdlModel.SchemaModel ns = new CsdlModel.SchemaModel("NS", null, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(actions), List.of(container));
        return new CsdlModel(List.of(ns), List.of());
    }

    private static CsdlModel.ActionModel action(String name, boolean bound) {
        return new CsdlModel.ActionModel(name, bound, null,
                bound ? List.of(new CsdlModel.ParameterModel("binding", "NS.Person", false)) : List.of(),
                null);
    }

    @Test
    void sameNameUnboundActionsFailLoudly() {
        CsdlModel m = actionModel(action("Zap", false), action("Zap", false));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                generator(m).generateActionImportRequest(
                        m.schemas().get(0).containers().get(0).actionImports().get(0), m.schemas().get(0)));
        assertTrue(ex.getMessage().contains("action"), ex.getMessage());
        assertTrue(ex.getMessage().contains("overload"), ex.getMessage());
    }

    @Test
    void unboundActionImportIgnoresBoundSibling() {
        CsdlModel m = actionModel(action("Zap", true), action("Zap", false));
        String code = generator(m).generateActionImportRequest(
                m.schemas().get(0).containers().get(0).actionImports().get(0), m.schemas().get(0));
        assertTrue(code.contains("public final class ZapActionRequest"));
    }

    // ------------------------------------------------------------------
    // Container accessors + full pipeline
    // ------------------------------------------------------------------

    @Test
    void containerEmitsOneAccessorPerOverload() {
        CsdlModel m = usernameUserIdOverloads();
        String code = new ContainerGenerator("app", Map.of(), "app", m.schemas())
                .generate(schema(m).containers().get(0), schema(m));
        assertTrue(code.contains("public IsSiteAdminByUsernameFunctionRequest isSiteAdminByUsername(String username)"),
                code);
        assertTrue(code.contains("return new IsSiteAdminByUsernameFunctionRequest(context, username);"));
        assertTrue(code.contains("public IsSiteAdminByUserIdFunctionRequest isSiteAdminByUserId(String userId)"));
        assertTrue(code.contains("return new IsSiteAdminByUserIdFunctionRequest(context, userId);"));
        assertTrue(code.contains("import app.operation.IsSiteAdminByUsernameFunctionRequest;"));
        assertTrue(code.contains("import app.operation.IsSiteAdminByUserIdFunctionRequest;"));
        assertFalse(code.contains("isSiteAdmin(String"),
                "no unsuffixed accessor exists for an overloaded import");
    }

    @Test
    void overloadedAccessorCollidingWithEntitySetFailsLoudly() {
        // the per-overload accessor names join the existing collision registry
        CsdlModel m = usernameUserIdOverloads();
        CsdlModel.ContainerModel collide = new CsdlModel.ContainerModel("DefaultContainer",
                List.of(new CsdlModel.EntitySetModel("IsSiteAdminByUsername", "NS.Person",
                        List.of(), List.of())),
                List.of(),
                List.of(new CsdlModel.FunctionImportModel("IsSiteAdmin", "NS.IsSiteAdmin", null, false)),
                List.of());
        CsdlModel.SchemaModel ns = new CsdlModel.SchemaModel("NS", null,
                List.of(), List.of(), List.of(), List.of(),
                List.of(fn("IsSiteAdmin", List.of(param("username", "Edm.String"))),
                        fn("IsSiteAdmin", List.of(param("userId", "Edm.String")))),
                List.of(), List.of(collide));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new ContainerGenerator("app", Map.of(), "app", List.of(ns))
                        .generate(collide, ns));
        assertTrue(ex.getMessage().contains("isSiteAdminByUsername"), ex.getMessage());
    }

    @Test
    void generatorWritesOneOperationFilePerOverload() throws Exception {
        CsdlModel m = usernameUserIdOverloads();
        java.nio.file.Path out = java.nio.file.Files.createTempDirectory("opoverload");
        try {
            new Generator(out, Map.of("NS", "app"), "app").generate(m);
            assertTrue(java.nio.file.Files.exists(out.resolve("app/operation/IsSiteAdminByUsernameFunctionRequest.java")));
            assertTrue(java.nio.file.Files.exists(out.resolve("app/operation/IsSiteAdminByUserIdFunctionRequest.java")));
            assertFalse(java.nio.file.Files.exists(out.resolve("app/operation/IsSiteAdminFunctionRequest.java")),
                    "no unsuffixed class exists for an overloaded import");
        } finally {
            deleteRecursively(out);
        }
    }

    private static void deleteRecursively(java.nio.file.Path root) {
        if (!java.nio.file.Files.exists(root)) return;
        try (var walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {}
    }
}
