package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SchemaModel} is a record (value equality), so a schema instance that is equal to one
 * in the generator's schema list must be treated as a member of that list — otherwise the
 * shared generators are rebuilt per container and cross-schema operation resolution silently
 * degrades to single-schema mode.
 */
class ContainerGeneratorSchemaEqualityTest {

    private static CsdlModel.ParameterModel param(String name, String type) {
        return new CsdlModel.ParameterModel(name, type, true);
    }

    private static CsdlModel twoSchemaModel() {
        CsdlModel.FunctionModel fn = new CsdlModel.FunctionModel("IsSiteAdmin", false, false, null,
                List.of(param("username", "Edm.String")),
                new CsdlModel.ReturnTypeModel("Edm.Boolean", false));
        CsdlModel.SchemaModel a = new CsdlModel.SchemaModel("NS.A", null, List.of(), List.of(),
                List.of(), List.of(), List.of(fn), List.of(), List.of());
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("DefaultContainer",
                List.of(), List.of(),
                List.of(new CsdlModel.FunctionImportModel("IsSiteAdmin", "NS.A.IsSiteAdmin", null, false)),
                List.of());
        CsdlModel.SchemaModel b = new CsdlModel.SchemaModel("NS.B", null, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(container));
        return new CsdlModel(List.of(a, b), List.of());
    }

    @Test
    void valueEqualSchemaInstanceStillResolvesCrossSchemaImports() {
        CsdlModel m = twoSchemaModel();
        CsdlModel.SchemaModel b = m.schemas().get(1);
        // A value-equal but DISTINCT copy of schema B (not the instance held in m.schemas())
        CsdlModel.SchemaModel bCopy = new CsdlModel.SchemaModel(b.namespace(), b.alias(),
                b.entityTypes(), b.complexTypes(), b.enumTypes(), b.typeDefinitions(),
                b.functions(), b.actions(), b.containers());
        assertEquals(b, bCopy, "copy must be value-equal");
        assertNotSame(b, bCopy, "copy must be a distinct instance");

        String code = new ContainerGenerator("app", Map.of(), "app", m.schemas())
                .generate(bCopy.containers().get(0), bCopy);

        assertTrue(code.contains("isSiteAdmin"),
                "the cross-schema function import must still resolve for a value-equal schema:\n" + code);
    }
}
