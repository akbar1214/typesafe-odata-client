package io.github.akbarhusain.odata.core.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1: PropertyRef@Name null -> NPE in ReferentialConstraint
 * M2: Unqualified container Extends nondeterministic
 */
class StaxCsdlParserMediumTest {

    // M1: <PropertyRef> without Name inside ReferentialConstraint should fail with clear message, not NPE/null
    @Test
    void m1_propertyRefMissingNameThrows() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="Order">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                      </EntityType>
                      <EntityType Name="Item">
                        <Key><PropertyRef Name="Id"/></Key>
                        <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        <NavigationProperty Name="Order" Type="NS.Test.Order">
                          <ReferentialConstraint>
                            <Principal Role="Order"><PropertyRef /></Principal>
                            <Dependent Role="Item"><PropertyRef Name="OrderId"/></Dependent>
                          </ReferentialConstraint>
                        </NavigationProperty>
                        <Property Name="OrderId" Type="Edm.Int32"/>
                      </EntityType>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        StaxCsdlParser parser = new StaxCsdlParser();
        Exception ex = assertThrows(Exception.class, () ->
                parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))),
                "M1: PropertyRef without Name should throw, not allow null");
        // Should be IllegalArgumentException with message mentioning PropertyRef and Name, not NPE
        assertTrue(ex.getMessage().contains("Name") || ex.getMessage().contains("PropertyRef"),
                "M1: exception should mention missing Name, got: " + ex.getMessage());
        assertFalse(ex instanceof NullPointerException, "M1: should not be NPE, should be IllegalArgumentException");
    }

    // M2: Unqualified Extends with two candidates should be ambiguous -> fail, not nondeterministic pick
    @Test
    void m2_unqualifiedExtendsAmbiguousThrows() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="NS.A" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityContainer Name="SharedContainer">
                        <EntitySet Name="Foos" EntityType="NS.A.Foo"/>
                      </EntityContainer>
                      <EntityType Name="Foo"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                    </Schema>
                    <Schema Namespace="NS.B" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityContainer Name="SharedContainer">
                        <EntitySet Name="Bars" EntityType="NS.B.Bar"/>
                      </EntityContainer>
                      <EntityType Name="Bar"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                    </Schema>
                    <Schema Namespace="NS.C" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityContainer Name="Derived" Extends="SharedContainer">
                        <EntitySet Name="Foos" EntityType="NS.A.Foo"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        StaxCsdlParser parser = new StaxCsdlParser();
        // M2: Unqualified Extends="SharedContainer" is ambiguous (exists in NS.A and NS.B) -> should throw
        Exception ex = assertThrows(Exception.class, () ->
                parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))),
                "M2: ambiguous unqualified Extends should throw, not pick nondeterministically");
        assertTrue(ex.getMessage().contains("SharedContainer") || ex.getMessage().contains("ambiguous") || ex.getMessage().contains("Extends"),
                "M2: message should mention ambiguous container, got: " + ex.getMessage());
    }

    @Test
    void m2_qualifiedExtendsWorks() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="NS.A" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityContainer Name="Base">
                        <EntitySet Name="Foos" EntityType="NS.A.Foo"/>
                      </EntityContainer>
                      <EntityType Name="Foo"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                    </Schema>
                    <Schema Namespace="NS.B" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityContainer Name="Derived" Extends="NS.A.Base">
                        <EntitySet Name="Bars" EntityType="NS.B.Bar"/>
                      </EntityContainer>
                      <EntityType Name="Bar"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        StaxCsdlParser parser = new StaxCsdlParser();
        var model = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        // Should succeed and merge base sets
        assertNotNull(model);
        // Derived should have Foos from base + Bars
        var derived = model.schemas().stream().filter(s -> s.namespace().equals("NS.B")).findFirst().get()
                .containers().get(0);
        assertTrue(derived.entitySets().size() >= 2, "Derived should have merged entitySets, got: " + derived.entitySets());
    }

    // Duplicate alias mapping to DIFFERENT namespaces: silent first-wins would resolve
    // the second schema's own references against the wrong namespace (order-dependent)
    @Test
    void m2_duplicateAliasDifferentNamespacesThrows() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="NS.A" Alias="shared" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="Foo"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                    </Schema>
                    <Schema Namespace="NS.B" Alias="shared" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="Bar"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        StaxCsdlParser parser = new StaxCsdlParser();
        Exception ex = assertThrows(Exception.class, () ->
                parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))),
                "duplicate alias with conflicting namespaces must fail loudly");
        assertTrue(ex.getMessage().contains("Alias") && ex.getMessage().contains("shared"),
                "message should name the conflicting alias, got: " + ex.getMessage());
    }

    @Test
    void m2_uniqueAliasStillAccepted() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="NS.A" Alias="a1" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityType Name="Foo"><Key><PropertyRef Name="Id"/></Key><Property Name="Id" Type="Edm.Int32" Nullable="false"/></EntityType>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        // A unique alias must parse without error
        assertDoesNotThrow(() ->
                new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }
}
