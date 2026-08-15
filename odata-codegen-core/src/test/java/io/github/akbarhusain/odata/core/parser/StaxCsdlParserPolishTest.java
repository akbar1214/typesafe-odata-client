package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L16: EntityContainer Extends is merged (inherited entity sets survive). L17: parsed
 * model lists are immutable. L18: whitespace in type refs is tolerated. L19: PropertyRef
 * aliases are captured. L25: TypeDefinition UnderlyingType is required.
 */
class StaxCsdlParserPolishTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void l16ContainerExtendsMergesInheritedEntitySets() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Base.Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Thing">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityContainer Name="BaseContainer">
                    <EntitySet Name="Things" EntityType="Base.Ns.Thing"/>
                    <Singleton Name="Me" Type="Base.Ns.Thing"/>
                  </EntityContainer>
                </Schema>
                <Schema Namespace="Derived.Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Other">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityContainer Name="DerivedContainer" Extends="Base.Ns.BaseContainer">
                    <EntitySet Name="Others" EntityType="Derived.Ns.Other"/>
                    <EntitySet Name="Things" EntityType="Derived.Ns.Other"/>
                  </EntityContainer>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        var derived = model.schemas().get(1).containers().get(0);
        assertEquals(2, derived.entitySets().size(),
                "inherited 'Things' merged with own 'Others'");
        assertEquals("Others", derived.entitySets().get(0).name(), "inherited sets come first");
        assertEquals("Derived.Ns.Other", derived.entitySets().get(1).entityType(),
                "own set with the same name overrides the inherited one");
        assertEquals(1, derived.singletons().size(), "inherited singletons merged");
    }

    @Test
    void l16UnknownExtendsFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                      <edmx:DataServices>
                        <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                          <EntityType Name="T"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                          <EntityContainer Name="C" Extends="No.Such.Container">
                            <EntitySet Name="Ts" EntityType="Ns.T"/>
                          </EntityContainer>
                        </Schema>
                      </edmx:DataServices>
                    </edmx:Edmx>
                    """));
    }

    @Test
    void l17ParsedModelListsAreImmutable() throws Exception {
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
        assertThrows(UnsupportedOperationException.class,
                () -> model.schemas().add(null),
                "post-parse mutation must not corrupt the model");
        assertThrows(UnsupportedOperationException.class,
                () -> model.schemas().get(0).entityTypes().get(0).properties().add(null));
    }

    @Test
    void l18WhitespaceInTypeAttributeIsTolerated() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="T">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="Tags" Type="Collection( Edm.String )"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        var tags = model.schemas().get(0).entityTypes().get(0).properties().get(1);
        assertEquals("Collection(Edm.String)", tags.edmType(),
                "type attributes are trimmed");
    }

    @Test
    void l19PropertyRefAliasIsCaptured() throws Exception {
        CsdlModel model = parse("""
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="T">
                    <Key><PropertyRef Name="Id" Alias="k"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """);
        assertEquals("k", model.schemas().get(0).entityTypes().get(0).keys().get(0).aliases().get(0));
    }

    @Test
    void l25TypeDefinitionUnderlyingTypeIsRequired() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                      <edmx:DataServices>
                        <Schema Namespace="Ns" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                          <TypeDefinition Name="Length"/>
                        </Schema>
                      </edmx:DataServices>
                    </edmx:Edmx>
                    """));
        assertTrue(ex.getMessage().contains("UnderlyingType"),
                "clear parse-time error: " + ex.getMessage());
    }
}
