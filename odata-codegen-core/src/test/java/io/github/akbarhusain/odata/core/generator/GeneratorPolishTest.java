package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generator-side polish findings. L19: key refs to nonexistent properties fail at
 * generation. L22: non-nullable primitive getters stay boxed (no unboxing NPE). L23:
 * containment navs get no $ref methods. L24: unqualified enum types render qualified
 * filter literals. L25: cross-schema TypeDefinitions with the same name resolve per
 * namespace.
 */
class GeneratorPolishTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private CsdlModel.SchemaModel schemaOf(CsdlModel model, String namespace) {
        return model.schemas().stream().filter(s -> s.namespace().equals(namespace))
                .findFirst().orElseThrow();
    }

    @Test
    void l19KeyRefToNonexistentPropertyFailsAtGeneration() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="T">
                    <Key><PropertyRef Name="DoesNotExist"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        var type = model.schemas().get(0).entityTypes().get(0);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example.t").generate(type, model.schemas().get(0)));
        assertTrue(ex.getMessage().contains("DoesNotExist"),
                "error names the offending ref: " + ex.getMessage());
    }

    @Test
    void l22NonNullablePrimitiveGetterStaysBoxed() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="T">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        String code = new EntityGenerator("com.example.t")
                .generate(model.schemas().get(0).entityTypes().get(0), model.schemas().get(0));
        assertTrue(code.contains("public Integer getId()"),
                "getter must stay boxed — a lenient service can still deliver null, and a "
                        + "primitive getter NPEs on unboxing. Got:\n" + code);
        assertFalse(code.contains("public int getId()"));
    }

    @Test
    void l23ContainmentNavGetsNoRefMethods() throws Exception {
        // TripPin's Person.Trips declares ContainsTarget="true"
        StaxCsdlParser parser = new StaxCsdlParser();
        CsdlModel.SchemaModel schema;
        try (InputStream is = GeneratorPolishTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            schema = parser.parse(is).schemas().get(0);
        }
        var person = schema.entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
        String code = new RequestGenerator("com.example.trippin")
                .generateEntityRequest(person, schema);

        assertFalse(code.contains("addTripsRef"), "$ref link operations are not defined for "
                + "containment (ContainsTarget) navigation properties");
        assertFalse(code.contains("removeTripsRef"));
        assertTrue(code.contains("public TripCollectionRequest trips()"),
                "the nav method itself stays");
    }

    @Test
    void l24UnqualifiedEnumTypeRendersQualifiedFilterLiteral() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EnumType Name="Color">
                    <Member Name="Red" Value="1"/>
                  </EnumType>
                  <EntityType Name="T">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="Shade" Type="Color"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        String code = new EntityGenerator("com.example.t")
                .generate(model.schemas().get(0).entityTypes().get(0), model.schemas().get(0));
        assertTrue(code.contains("\"Ns.Color\""),
                "enum filter literals need the fully qualified type name (Ns.Color'Red', "
                        + "not Color'Red'). Got:\\n" + code);
    }

    @Test
    void l25SameNamedTypeDefinitionsResolvePerNamespace() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NsA" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <TypeDefinition Name="Length" UnderlyingType="Edm.String"/>
                  <EntityType Name="A1">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="Name" Type="NsA.Length"/>
                  </EntityType>
                </Schema>
                <Schema Namespace="NsB" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <TypeDefinition Name="Length" UnderlyingType="Edm.Int32"/>
                  <EntityType Name="B1">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="Count" Type="NsB.Length"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        EntityGenerator gen = new EntityGenerator("com.example.t", java.util.Map.of(), null,
                model.schemas());
        String a1 = gen.generate(schemaOf(model, "NsA").entityTypes().get(0), schemaOf(model, "NsA"));
        String b1 = gen.generate(schemaOf(model, "NsB").entityTypes().get(0), schemaOf(model, "NsB"));

        assertTrue(a1.contains("StringProperty<A1> NAME"), "NsA.Length is Edm.String. Got:\\n" + a1);
        assertTrue(b1.contains("NumberProperty<B1, Integer> COUNT"),
                "NsB.Length is Edm.Int32 (previously shadowed by the first schema's typedef). Got:\\n" + b1);
    }
}
