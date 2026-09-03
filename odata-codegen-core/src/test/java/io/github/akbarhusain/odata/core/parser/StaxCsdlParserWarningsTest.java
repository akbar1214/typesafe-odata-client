package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch C9): schema-level unknown elements warn, but member-level
 * unknown elements (a typo'd {@code <Proprety>}) vanish silently — and a
 * document with zero schemas parses into an empty model with no signal.
 */
class StaxCsdlParserWarningsTest {

    private static CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String HEADER = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
            """;
    private static final String FOOTER = """
              </edmx:DataServices>
            </edmx:Edmx>
            """;

    @Test
    void unknownEntityTypeMemberWarns() throws Exception {
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Proprety Name="Typo" Type="Edm.String"/>
                  </EntityType>
                </Schema>
                """ + FOOTER);

        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("Proprety")),
                "typo'd member must be reported, not silently dropped: " + model.warnings());
        assertEquals(1, model.schemas().get(0).entityTypes().get(0).properties().size(),
                "known members must still parse around the unknown one");
    }

    @Test
    void unknownContainerMemberWarns() throws Exception {
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <EntityContainer Name="C">
                    <EntitySet Name="Foos" EntityType="NS.Test.Foo"/>
                    <EntitySetTypo Name="Oops" EntityType="NS.Test.Foo"/>
                  </EntityContainer>
                </Schema>
                """ + FOOTER);

        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("EntitySetTypo")),
                "typo'd container member must be reported: " + model.warnings());
        assertEquals(1, model.schemas().get(0).containers().get(0).entitySets().size());
    }

    @Test
    void typoInKeyWarns() throws Exception {
        // A typo'd <PropertyReff> silently loses the key, making the entity
        // keyless downstream — the worst instance of the typo class.
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyReff Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
                """ + FOOTER);

        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("PropertyReff")),
                "typo'd key member must be reported: " + model.warnings());
    }

    @Test
    void typoInNavigationPropertyWarns() throws Exception {
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <NavigationProperty Name="BestFriend" Type="NS.Test.Foo">
                      <ReferentialConstriant Property="Id" ReferencedProperty="Id"/>
                    </NavigationProperty>
                  </EntityType>
                </Schema>
                """ + FOOTER);

        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("ReferentialConstriant")),
                "typo'd nav member must be reported: " + model.warnings());
        assertEquals(1, model.schemas().get(0).entityTypes().get(0).navigationProperties().size(),
                "the nav itself must still parse around the unknown child");
    }

    @Test
    void typoInEnumTypeWarns() throws Exception {
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EnumType Name="Kind">
                    <Member Name="Alpha" Value="0"/>
                    <Membr Name="Beta" Value="1"/>
                    <Member Name="Gamma" Value="2"/>
                  </EnumType>
                </Schema>
                """ + FOOTER);

        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("Membr")),
                "typo'd enum member must be reported: " + model.warnings());
        assertEquals(2, model.schemas().get(0).enumTypes().get(0).members().size(),
                "known members must still parse around the unknown one");
    }

    @Test
    void inlineAnnotationsStaySilent() throws Exception {
        // TripPin nests vocabulary annotations inside EntityType/EntityContainer
        // bodies — legal CSDL the parser discards by design, not a typo signal.
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Annotation Term="Org.OData.Core.V1.Description" String="hi"/>
                  </EntityType>
                </Schema>
                """ + FOOTER);

        assertTrue(model.warnings().isEmpty(),
                "inline annotations must not warn: " + model.warnings());
        assertEquals(1, model.schemas().get(0).entityTypes().get(0).properties().size());
    }

    @Test
    void wrongNamespaceSchemaWarns() throws Exception {
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="NS.Good" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
                <Schema Namespace="NS.Test" xmlns="http://example.com/wrong-namespace">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
                """ + FOOTER);

        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("NS.Test")),
                "a Schema skipped for namespace mismatch must be reported: " + model.warnings());
        assertEquals(1, model.schemas().size(), "wrong-namespace Schema must still be skipped");
        assertEquals("NS.Good", model.schemas().get(0).namespace());
    }

    @Test
    void zeroSchemaDocumentFailsLoudly() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse(HEADER + FOOTER),
                "a document with zero schemas must fail, not yield an empty model");
        assertTrue(ex.getMessage().toLowerCase().contains("schema"),
                "the failure must say what is missing: " + ex.getMessage());
    }
}
