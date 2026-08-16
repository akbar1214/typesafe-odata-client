package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7: v4 nested referential constraints parse into the model (previously landed with
 * null fields). Warnings: unknown schema elements are reported instead of silently
 * dropped into a dead list.
 */
class CarryForwardParserTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void m7V4NestedReferentialConstraintsParse() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Order">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityType Name="OrderLine">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="OrderId" Type="Edm.Int32"/>
                    <Property Name="LineNumber" Type="Edm.Int32"/>
                    <NavigationProperty Name="Order" Type="Ns.Order">
                      <ReferentialConstraint>
                        <Principal>
                          <PropertyRef Name="Id"/>
                        </Principal>
                        <Dependent>
                          <PropertyRef Name="OrderId"/>
                        </Dependent>
                      </ReferentialConstraint>
                    </NavigationProperty>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        var nav = model.schemas().get(0).entityTypes().stream()
                .filter(e -> e.name().equals("OrderLine")).findFirst().orElseThrow()
                .navigationProperties().get(0);
        assertEquals(1, nav.referentialConstraints().size());
        var constraint = nav.referentialConstraints().get(0);
        assertEquals("OrderId", constraint.property(), "dependent property");
        assertEquals("Id", constraint.referencedProperty(), "principal property");
    }

    @Test
    void m7MismatchedPrincipalDependentCountsFail() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                      <edmx:DataServices>
                        <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                          <EntityType Name="A"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                          <EntityType Name="B">
                            <Key><PropertyRef Name="Id"/></Key>
                            <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                            <NavigationProperty Name="To" Type="Ns.A">
                              <ReferentialConstraint>
                                <Principal><PropertyRef Name="Id"/></Principal>
                                <Dependent><PropertyRef Name="X"/><PropertyRef Name="Y"/></Dependent>
                              </ReferentialConstraint>
                            </NavigationProperty>
                          </EntityType>
                        </Schema>
                      </edmx:DataServices>
                    </edmx:Edmx>
                    """));
    }

    @Test
    void unknownSchemaElementProducesWarning() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="T">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <SomeVendorExtension>data</SomeVendorExtension>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("SomeVendorExtension")),
                "skipped elements are reported: " + model.warnings());
    }
}
