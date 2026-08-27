package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loud-failure policy for operation imports (lessons H11/M2/156): unknown references,
 * bound-function references, and ambiguous simple names all fail at GENERATION time
 * with messages naming the import.
 */
class OperationImportValidationTest {

    private static CsdlModel.SchemaModel schema(String ns, List<CsdlModel.FunctionModel> fns,
                                                List<CsdlModel.ActionModel> actions,
                                                List<CsdlModel.ContainerModel> containers) {
        return new CsdlModel.SchemaModel(ns, null, List.of(), List.of(), List.of(),
                List.of(), fns, actions, containers);
    }

    private static CsdlModel.FunctionModel unboundFn(String name, String type, boolean nullable) {
        return new CsdlModel.FunctionModel(name, false, false, null, List.of(),
                new CsdlModel.ReturnTypeModel(type, nullable));
    }

    private static CsdlModel model(CsdlModel.SchemaModel... schemas) {
        return new CsdlModel(List.of(schemas), List.of());
    }

    @Test
    void unknownOperationReferenceFailsNamingImportAndReference() {
        CsdlModel m = model(
                schema("Ops.NS", List.of(unboundFn("Real", "Edm.String", false)), List.of(), List.of()),
                schema("App.NS", List.of(), List.of(), List.of(new CsdlModel.ContainerModel("C",
                        List.of(), List.of(),
                        List.of(new CsdlModel.FunctionImportModel("Gone", "Ops.NS.Missing", null, false)),
                        List.of()))));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new OperationGenerator("app", Map.of(), "app", m.schemas())
                        .generateFunctionImportRequest(m.schemas().get(1).containers().get(0)
                                .functionImports().get(0), m.schemas().get(1)));
        assertTrue(ex.getMessage().contains("Gone"), ex.getMessage());
        assertTrue(ex.getMessage().contains("unknown"), ex.getMessage());
    }

    @Test
    void boundFunctionReferenceFailsWithBoundMessage() {
        CsdlModel.FunctionModel bound = new CsdlModel.FunctionModel("Bad", true, false, null,
                List.of(), new CsdlModel.ReturnTypeModel("Edm.String", false));
        CsdlModel m = model(
                schema("Ops.NS", List.of(bound), List.of(), List.of()),
                schema("App.NS", List.of(), List.of(), List.of(new CsdlModel.ContainerModel("C",
                        List.of(), List.of(),
                        List.of(new CsdlModel.FunctionImportModel("B", "Ops.NS.Bad", null, false)),
                        List.of()))));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new OperationGenerator("app", Map.of(), "app", m.schemas())
                        .generateFunctionImportRequest(m.schemas().get(1).containers().get(0)
                                .functionImports().get(0), m.schemas().get(1)));
        assertTrue(ex.getMessage().contains("bound"), ex.getMessage());
    }

    @Test
    void ambiguousSimpleNameFailsDemandingQualification() {
        CsdlModel.FunctionModel a = unboundFn("Shared", "Edm.Int32", false);
        CsdlModel m = model(
                schema("NS.A", List.of(a), List.of(), List.of()),
                schema("NS.B", List.of(unboundFn("Shared", "Edm.Int32", false), a), List.of(),
                        List.of(new CsdlModel.ContainerModel("C", List.of(), List.of(),
                                List.of(new CsdlModel.FunctionImportModel("X", "Shared", null, false)),
                                List.of()))));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new OperationGenerator("app", Map.of(), "app", m.schemas())
                        .generateFunctionImportRequest(m.schemas().get(1).containers().get(0)
                                .functionImports().get(0), m.schemas().get(1)));
        assertTrue(ex.getMessage().contains("namespace-qualified"), ex.getMessage());
    }

    @Test
    void boundActionReferenceFailsWithBoundMessage() {
        // parity with the function check: action imports must reference UNBOUND actions
        CsdlModel.ActionModel bound = new CsdlModel.ActionModel("Bad", true, null,
                List.of(new CsdlModel.ParameterModel("person", "NS.Person", false)), null);
        CsdlModel m = model(
                schema("Ops.NS", List.of(), List.of(bound), List.of()),
                schema("App.NS", List.of(), List.of(), List.of(new CsdlModel.ContainerModel("C",
                        List.of(), List.of(), List.of(),
                        List.of(new CsdlModel.ActionImportModel("B", "Ops.NS.Bad", null))))));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new OperationGenerator("app", Map.of(), "app", m.schemas())
                        .generateActionImportRequest(m.schemas().get(1).containers().get(0)
                                .actionImports().get(0), m.schemas().get(1)));
        assertTrue(ex.getMessage().contains("bound"), ex.getMessage());
    }

    @Test
    void unresolvableFunctionParameterTypeStillFailsNamingImportAndParameter() {
        // Structured parameters now ride JSON parameter aliases (URL Conventions §5.1.1,
        // see OperationImportCollectionParamTest); only types that do not resolve to a
        // known kind (primitive, enum, complex, entity) still fail generation
        CsdlModel.FunctionModel bad = new CsdlModel.FunctionModel("ByWho", false, false, null,
                List.of(new CsdlModel.ParameterModel("who", "Ns.Nonexistent", false)),
                new CsdlModel.ReturnTypeModel("Edm.Int32", false));
        CsdlModel.SchemaModel ns = new CsdlModel.SchemaModel("NS", null, List.of(),
                List.of(), List.of(), List.of(), List.of(bad), List.of(),
                List.of(new CsdlModel.ContainerModel("C", List.of(), List.of(),
                        List.of(new CsdlModel.FunctionImportModel("byWho", "NS.ByWho", null, false)),
                        List.of())));
        CsdlModel m = model(ns);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new OperationGenerator("app", Map.of(), "app", m.schemas())
                        .generateFunctionImportRequest(
                                m.schemas().get(0).containers().get(0).functionImports().get(0),
                                m.schemas().get(0)));
        assertTrue(ex.getMessage().contains("byWho"), ex.getMessage());
        assertTrue(ex.getMessage().contains("who"), "parameter must be named: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Ns.Nonexistent"),
                "the offending type must be named: " + ex.getMessage());
    }
}
