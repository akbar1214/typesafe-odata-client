package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M17: schema Alias is resolved so alias-qualified type references normalize to the real
 * namespace. M19: required CSDL attributes fail loudly at parse time instead of NPE-ing
 * deep inside the generators.
 */
class StaxCsdlParserAliasAndValidationTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String ALIAS_METADATA = """
        <?xml version="1.0" encoding="utf-8"?>
        <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
          <edmx:DataServices>
            <Schema Namespace="Com.Example.Model" Alias="self" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EntityType Name="Person">
                <Key><PropertyRef Name="Id"/></Key>
                <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                <Property Name="Address" Type="self.HomeAddress"/>
                <Property Name="Tags" Type="Collection(self.Tag)"/>
                <NavigationProperty Name="BestFriend" Type="self.Person"/>
              </EntityType>
              <EntityType Name="Employee" BaseType="self.Person">
                <Property Name="Cube" Type="Edm.String"/>
              </EntityType>
              <ComplexType Name="HomeAddress">
                <Property Name="Street" Type="Edm.String"/>
              </ComplexType>
              <EnumType Name="Tag">
                <Member Name="Red"/>
              </EnumType>
              <EntityContainer Name="Container">
                <EntitySet Name="People" EntityType="self.Person"/>
              </EntityContainer>
            </Schema>
          </edmx:DataServices>
        </edmx:Edmx>
        """;

    @Test
    void m17AliasQualifiedPropertyTypeResolvesToNamespace() throws Exception {
        CsdlModel model = parse(ALIAS_METADATA);
        var person = model.schemas().get(0).entityTypes().stream()
                .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();

        var address = person.properties().stream()
                .filter(p -> p.name().equals("Address")).findFirst().orElseThrow();
        assertEquals("Com.Example.Model.HomeAddress", address.edmType(),
                "alias-qualified property types must normalize to the real namespace");

        var tags = person.properties().stream()
                .filter(p -> p.name().equals("Tags")).findFirst().orElseThrow();
        assertEquals("Collection(Com.Example.Model.Tag)", tags.edmType(),
                "collection wrappers must be preserved while resolving the alias");

        var bestFriend = person.navigationProperties().stream()
                .filter(n -> n.name().equals("BestFriend")).findFirst().orElseThrow();
        assertEquals("Com.Example.Model.Person", bestFriend.type());

        var employee = model.schemas().get(0).entityTypes().stream()
                .filter(e -> e.name().equals("Employee")).findFirst().orElseThrow();
        assertEquals("Com.Example.Model.Person", employee.baseType(),
                "alias-qualified BaseType must resolve");

        var people = model.schemas().get(0).containers().get(0).entitySets().get(0);
        assertEquals("Com.Example.Model.Person", people.entityType(),
                "alias-qualified EntitySet@EntityType must resolve");
    }

    @Test
    void m19MissingRequiredAttributesFailLoudly() {
        String missingNamespace = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Person"/>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse(missingNamespace));
        assertTrue(ex.getMessage().contains("Schema") && ex.getMessage().contains("Namespace"),
                "message must name the offending element/attribute: " + ex.getMessage());

        String missingType = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Person">
                    <Property Name="Id"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        ex = assertThrows(IllegalArgumentException.class, () -> parse(missingType));
        assertTrue(ex.getMessage().contains("Type"), "message must name the missing attribute: " + ex.getMessage());
    }

    @Test
    void m19InvalidEnumValueFailsWithEnumAndMemberContext() {
        String badValue = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EnumType Name="Color">
                    <Member Name="Red" Value="0x10"/>
                  </EnumType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(badValue));
        assertTrue(ex.getMessage().contains("Color") && ex.getMessage().contains("Red"),
                "message must name the enum and member: " + ex.getMessage());
    }
}
