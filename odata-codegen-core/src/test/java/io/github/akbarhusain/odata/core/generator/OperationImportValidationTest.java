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
}
