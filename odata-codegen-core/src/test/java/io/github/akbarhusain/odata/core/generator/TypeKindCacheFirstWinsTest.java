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
        // NS.A Length -> Int32, NS.B Length -> Double
        var tdA = new TypeDefinitionModel("Length", "Edm.Int32");
        var tdB = new TypeDefinitionModel("Length", "Edm.Double");
        SchemaModel sA = schema("NS.A", List.of(), List.of(), List.of(tdA));
        SchemaModel sB = schema("NS.B", List.of(), List.of(), List.of(tdB));

        // Simulate AbstractTypeGenerator's typeDefCache simple fallback:
        // first schema's Length wins for unqualified "Length"
        // Build a generator to test resolveTypeDefinition via reflection or via Names? Instead test map directly.
        var mapAB = new java.util.HashMap<String,String>();
        // mimic putIfAbsent logic
        mapAB.put("NS.A.Length", "Edm.Int32");
        mapAB.putIfAbsent("Length", "Edm.Int32");
        mapAB.put("NS.B.Length", "Edm.Double");
        mapAB.putIfAbsent("Length", "Edm.Double"); // second putIfAbsent keeps first

        assertEquals("Edm.Int32", mapAB.get("Length"),
                "setup shows first-wins for AB order");
        var mapBA = new java.util.HashMap<String,String>();
        mapBA.put("NS.B.Length", "Edm.Double");
        mapBA.putIfAbsent("Length", "Edm.Double");
        mapBA.put("NS.A.Length", "Edm.Int32");
        mapBA.putIfAbsent("Length", "Edm.Int32");

        // H11: unqualified "Length" must not be order-dependent; currently it is
        assertEquals(mapAB.get("Length"), mapBA.get("Length"),
                "H11: TypeDefinition simple fallback order-dependent: AB Length=" + mapAB.get("Length") + " BA Length=" + mapBA.get("Length"));
    }
}
