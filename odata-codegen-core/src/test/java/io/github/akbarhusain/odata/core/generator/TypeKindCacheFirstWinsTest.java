package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H11: TypeKind cache putIfAbsent first-wins cross-schema.
 * Names.buildTypeKindMap uses putIfAbsent for simple name "Address", so order matters.
 * Two schemas both define "Address" as different kinds -> simple lookup is order-dependent.
 * Expected after fix: order-independent (e.g., UNKNOWN or per-namespace resolution), not first-wins.
 * Currently: order-dependent -> test fails.
 *
 * Also covers TypeDefinition simple fallback: first Length typedef wins over second.
 */
class TypeKindCacheFirstWinsTest {

    private SchemaModel schema(String ns, List<EntityTypeModel> entities, List<ComplexTypeModel> complexes, List<TypeDefinitionModel> tds) {
        return new SchemaModel(ns, null, entities, complexes, List.of(), tds, List.of(), List.of(), List.of());
    }

    @Test
    void simpleNameLookupIsOrderDependent() {
        // Schema A: Complex Address
        SchemaModel aComplex = schema("NS.A",
                List.of(),
                List.of(new ComplexTypeModel("Address", null, false, false, List.of(), List.of())),
                List.of());
        // Schema B: Entity Address
        SchemaModel bEntity = schema("NS.B",
                List.of(new EntityTypeModel("Address", null, false, false, false, List.of(), List.of(), List.of())),
                List.of(),
                List.of());

        var mapAB = Names.buildTypeKindMap(List.of(aComplex, bEntity));
        var mapBA = Names.buildTypeKindMap(List.of(bEntity, aComplex));

        // H11: simple name "Address" should be deterministic/order-independent.
        // Currently mapAB simple = COMPLEX (first), mapBA simple = ENTITY (first) -> not equal
        assertEquals(mapAB.get("Address"), mapBA.get("Address"),
                "H11: simple name 'Address' lookup must be order-independent, but AB=" + mapAB.get("Address") + " BA=" + mapBA.get("Address"));

        // Qualified keys must still be correct regardless
        assertEquals(Names.TypeKind.COMPLEX, mapAB.get("NS.A.Address"));
        assertEquals(Names.TypeKind.ENTITY, mapAB.get("NS.B.Address"));
    }

    @Test
    void typeDefinitionSimpleFallbackFirstWins() {
        // NS.A Length -> Int32, NS.B Length -> Double (same simple name, different underlying)
        var tdA = new TypeDefinitionModel("Length", "Edm.Int32");
        var tdB = new TypeDefinitionModel("Length", "Edm.Double");
        SchemaModel sA = schema("NS.A", List.of(), List.of(), List.of(tdA));
        SchemaModel sB = schema("NS.B", List.of(), List.of(), List.of(tdB));

        // Drive the real AbstractTypeGenerator.resolveTypeDefinition via a minimal subclass
        String resolvedAB = resolveWithOrder(List.of(sA, sB), "Length");
        String resolvedBA = resolveWithOrder(List.of(sB, sA), "Length");

        // H11: unqualified "Length" must not be order-dependent; currently it is
        assertEquals(resolvedAB, resolvedBA,
                "H11: TypeDefinition simple fallback order-dependent: AB Length=" + resolvedAB + " BA Length=" + resolvedBA);

        // Qualified lookups must still be correct regardless of order
        assertEquals("Edm.Int32", resolveWithOrder(List.of(sA, sB), "NS.A.Length"));
        assertEquals("Edm.Double", resolveWithOrder(List.of(sA, sB), "NS.B.Length"));
    }

    private static String resolveWithOrder(List<SchemaModel> schemas, String type) {
        TestGen gen = new TestGen(schemas);
        return gen.resolve(type);
    }

    static class TestGen extends AbstractTypeGenerator {
        TestGen(List<SchemaModel> schemas) {
            super("com.test", java.util.Map.of(), "com.test", schemas);
            this.effectiveSchemas = schemas;
        }
        String resolve(String edmType) {
            return resolveTypeDefinition(edmType, effectiveSchemas.get(0));
        }
    }
}
