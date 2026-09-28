package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review follow-ups on PR #32: cyclic TypeDefinition UnderlyingType chains, duplicate
 * qualified container names, and the shared Collection(...) validation used by both
 * parse time and the alias post-pass.
 */
class StaxCsdlParserReviewHardeningTest {

    private static final String HEADER = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
            """;
    private static final String FOOTER = """
              </edmx:DataServices>
            </edmx:Edmx>
            """;

    private static CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void selfCyclicTypeDefinitionRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <TypeDefinition Name="A" UnderlyingType="A"/>
                </Schema>
                """ + FOOTER));
        assertTrue(ex.getMessage().contains("Cyclic"), ex.getMessage());
        assertTrue(ex.getMessage().contains("NS.Test.A"), ex.getMessage());
    }

    @Test
    void twoNodeCyclicTypeDefinitionRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <TypeDefinition Name="A" UnderlyingType="B"/>
                  <TypeDefinition Name="B" UnderlyingType="A"/>
                </Schema>
                """ + FOOTER));
        assertTrue(ex.getMessage().contains("Cyclic"), ex.getMessage());
    }

    @Test
    void crossSchemaCyclicTypeDefinitionRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(HEADER + """
                <Schema Namespace="One" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <TypeDefinition Name="A" UnderlyingType="Two.B"/>
                </Schema>
                <Schema Namespace="Two" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <TypeDefinition Name="B" UnderlyingType="One.A"/>
                </Schema>
                """ + FOOTER));
        assertTrue(ex.getMessage().contains("Cyclic"), ex.getMessage());
    }

    @Test
    void acyclicTypeDefinitionChainAccepted() throws Exception {
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <TypeDefinition Name="A" UnderlyingType="B"/>
                  <TypeDefinition Name="B" UnderlyingType="Edm.String"/>
                </Schema>
                """ + FOOTER);
        assertEquals(2, model.schemas().get(0).typeDefinitions().size());
    }

    @Test
    void duplicateQualifiedContainerNameRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityContainer Name="C">
                    <EntitySet Name="A" EntityType="NS.Test.E"/>
                  </EntityContainer>
                  <EntityContainer Name="C">
                    <EntitySet Name="B" EntityType="NS.Test.E"/>
                  </EntityContainer>
                </Schema>
                <Schema Namespace="Other" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityContainer Name="D" Extends="NS.Test.C"/>
                </Schema>
                """ + FOOTER));
        assertTrue(ex.getMessage().contains("NS.Test.C"), ex.getMessage());
    }

    @Test
    void sameContainerNameInDifferentNamespacesAccepted() throws Exception {
        CsdlModel model = parse(HEADER + """
                <Schema Namespace="One" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityContainer Name="C">
                    <EntitySet Name="A" EntityType="One.E"/>
                  </EntityContainer>
                </Schema>
                <Schema Namespace="Two" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityContainer Name="D" Extends="One.C"/>
                  <EntityContainer Name="C">
                    <EntitySet Name="B" EntityType="Two.E"/>
                  </EntityContainer>
                </Schema>
                """ + FOOTER);
        assertEquals(2, model.schemas().size());
    }

    @Test
    void nestedCollectionTypeRejectedBySharedValidation() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(HEADER + """
                <Schema Namespace="NS.Test" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="Bad" Type="Collection(Collection(Edm.String))"/>
                  </EntityType>
                </Schema>
                """ + FOOTER));
        assertTrue(ex.getMessage().contains("Collection"), ex.getMessage());
    }
}
