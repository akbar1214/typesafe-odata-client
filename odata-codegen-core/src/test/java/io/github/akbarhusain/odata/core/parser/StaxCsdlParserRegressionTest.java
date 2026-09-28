package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StaxCsdlParserRegressionTest {

    private static final String EDM_NS = "http://docs.oasis-open.org/odata/ns/edm";
    private static final String EDMX_NS = "http://docs.oasis-open.org/odata/ns/edmx";

    private static CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static CsdlModel parse(StaxCsdlParser parser, String xml) throws Exception {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static IllegalArgumentException parseFailure(String xml) {
        return assertThrows(IllegalArgumentException.class, () -> parse(xml));
    }

    private static String document(String body) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="%s">
                %s
                </edmx:Edmx>
                """.formatted(EDMX_NS, body);
    }

    private static String dataServices(String schemas) {
        return "<edmx:DataServices>" + schemas + "</edmx:DataServices>";
    }

    private static String schema(String content) {
        return schema("NS", null, content);
    }

    private static String schema(String namespace, String alias, String content) {
        String aliasAttribute = alias == null ? "" : " Alias=\"" + alias + "\"";
        return "<Schema Namespace=\"" + namespace + "\"" + aliasAttribute
                + " xmlns=\"" + EDM_NS + "\">" + content + "</Schema>";
    }

    private static String operationSchema(String operations) {
        return document(dataServices(schema(operations)));
    }

    private static CsdlModel parseOperations(String operations) throws Exception {
        return parse(operationSchema(operations));
    }

    @Test
    void foreignNamespaceMembersAreWarnedAndSkipped() throws Exception {
        CsdlModel model = parse(document(dataServices("""
                <Schema Namespace="NS" xmlns="%s" xmlns:x="urn:foreign">
                  <x:EntityType Name="ForeignEntity"/>
                  <EntityType Name="Entity">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <x:Property Name="Injected" Type="Edm.String"/>
                    <NavigationProperty Name="Self" Type="NS.Entity">
                      <x:ReferentialConstraint Property="Id" ReferencedProperty="Id"/>
                    </NavigationProperty>
                  </EntityType>
                  <EntityContainer Name="Container">
                    <EntitySet Name="Entities" EntityType="NS.Entity"/>
                    <x:EntitySet Name="Injected" EntityType="NS.Entity"/>
                    <x:FunctionImport Name="Injected" Function="NS.Entity"/>
                  </EntityContainer>
                </Schema>
                """.formatted(EDM_NS))));

        var entity = model.schemas().get(0).entityTypes().get(0);
        assertEquals("Entity", entity.name());
        assertEquals(List.of("Id"), entity.properties().stream()
                .map(io.github.akbarhusain.odata.core.model.CsdlModel.PropertyModel::name).toList());
        assertTrue(entity.navigationProperties().get(0).referentialConstraints().isEmpty());
        assertEquals(1, model.schemas().get(0).containers().get(0).entitySets().size());
        assertTrue(model.schemas().get(0).containers().get(0).functionImports().isEmpty());
        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("urn:foreign")),
                "foreign members must be reported: " + model.warnings());
    }

    @Test
    void unknownQualifiedContainerExtendsDoesNotFallBackToSimpleName() {
        IllegalArgumentException ex = parseFailure(document(dataServices(schema("""
                <EntityType Name="Entity">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                </EntityType>
                <EntityContainer Name="Base">
                  <EntitySet Name="Entities" EntityType="NS.Entity"/>
                </EntityContainer>
                <EntityContainer Name="Derived" Extends="Missing.Base">
                  <EntitySet Name="Others" EntityType="NS.Entity"/>
                </EntityContainer>
                """))));

        assertTrue(ex.getMessage().contains("Missing.Base"), ex.getMessage());
    }

    @Test
    void uniqueUnqualifiedContainerExtendsStillResolves() throws Exception {
        CsdlModel model = parse(document(dataServices(schema("""
                <EntityType Name="Entity">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                </EntityType>
                <EntityContainer Name="Base">
                  <EntitySet Name="Entities" EntityType="NS.Entity"/>
                </EntityContainer>
                <EntityContainer Name="Derived" Extends="Base">
                  <EntitySet Name="Others" EntityType="NS.Entity"/>
                </EntityContainer>
                """))));

        assertEquals(2, model.schemas().get(0).containers().get(1).entitySets().size());
    }

    @Test
    void reusedParserResetsWarnings() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        CsdlModel first = parse(parser, document(dataServices(schema("""
                <Bogus/>
                """))));
        CsdlModel second = parse(parser, document(dataServices(schema("""
                <EntityType Name="Entity">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                </EntityType>
                """))));

        assertFalse(first.warnings().isEmpty());
        assertTrue(second.warnings().isEmpty(), second.warnings().toString());
    }

    @Test
    void reusedParserResetsAliases() throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        parse(parser, document(dataServices(schema("First", "shared", """
                <EntityType Name="First">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                </EntityType>
                """))));
        CsdlModel second = parse(parser, document(dataServices(schema("Second", "shared", """
                <EntityType Name="Second">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                </EntityType>
                """))));

        assertEquals("Second", second.schemas().get(0).entityTypes().get(0).name());
    }

    @Test
    void xsBooleanZeroAndOneAreParsedEverywhereRepresented() throws Exception {
        CsdlModel model = parseOperations("""
                <EntityType Name="Entity" OpenType="1" Abstract="0" HasStream="1">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="0"/>
                  <Property Name="Name" Type="Edm.String" Nullable="1"/>
                  <NavigationProperty Name="Self" Type="NS.Entity" ContainsTarget="1" Nullable="0"/>
                </EntityType>
                <ComplexType Name="Complex" OpenType="1" Abstract="0">
                  <Property Name="Value" Type="Edm.String" Nullable="0"/>
                </ComplexType>
                <EnumType Name="Flags" IsFlags="1">
                  <Member Name="None" Value="0"/>
                </EnumType>
                <Function Name="BoundFunction" IsBound="1" IsComposable="1" EntitySetPath="binding">
                  <Parameter Name="binding" Type="NS.Entity" Nullable="0"/>
                  <ReturnType Type="NS.Entity" Nullable="0"/>
                </Function>
                <Action Name="BoundAction" IsBound="1" EntitySetPath="binding">
                  <Parameter Name="binding" Type="NS.Entity" Nullable="1"/>
                  <ReturnType Type="Edm.String" Nullable="1"/>
                </Action>
                <EntityContainer Name="Container">
                  <EntitySet Name="Entities" EntityType="NS.Entity"/>
                  <FunctionImport Name="BoundFunction" Function="NS.BoundFunction" IncludeInServiceDocument="1"/>
                  <ActionImport Name="BoundAction" Action="NS.BoundAction"/>
                </EntityContainer>
                """);

        var schema = model.schemas().get(0);
        var entity = schema.entityTypes().get(0);
        assertTrue(entity.openType());
        assertFalse(entity.abstractType());
        assertTrue(entity.hasStream());
        assertFalse(entity.properties().get(0).nullable());
        assertTrue(entity.properties().get(1).nullable());
        assertTrue(entity.navigationProperties().get(0).containsTarget());
        assertFalse(entity.navigationProperties().get(0).nullable());
        assertTrue(schema.complexTypes().get(0).openType());
        assertFalse(schema.complexTypes().get(0).abstractType());
        assertFalse(schema.complexTypes().get(0).properties().get(0).nullable());
        assertTrue(schema.enumTypes().get(0).isFlags());
        assertTrue(schema.functions().get(0).isBound());
        assertTrue(schema.functions().get(0).isComposable());
        assertEquals("binding", schema.functions().get(0).entitySetPath());
        assertFalse(schema.functions().get(0).parameters().get(0).nullable());
        assertFalse(schema.functions().get(0).returnType().nullable());
        assertTrue(schema.actions().get(0).isBound());
        assertTrue(schema.actions().get(0).parameters().get(0).nullable());
        assertTrue(schema.actions().get(0).returnType().nullable());
        assertTrue(schema.containers().get(0).functionImports().get(0).includeInServiceDocument());
    }

    @Test
    void functionRequiresExactlyOneTypedReturnType() {
        IllegalArgumentException missing = parseFailure(operationSchema("""
                <Function Name="Required"/>
                """));
        assertTrue(missing.getMessage().contains("Required") && missing.getMessage().contains("ReturnType"),
                missing.getMessage());

        IllegalArgumentException missingType = parseFailure(operationSchema("""
                <Function Name="Required"><ReturnType/></Function>
                """));
        assertTrue(missingType.getMessage().contains("Type"), missingType.getMessage());

        IllegalArgumentException duplicate = parseFailure(operationSchema("""
                <Function Name="Required">
                  <ReturnType Type="Edm.String"/>
                  <ReturnType Type="Edm.Int32"/>
                </Function>
                """));
        assertTrue(duplicate.getMessage().contains("Required") && duplicate.getMessage().contains("ReturnType"),
                duplicate.getMessage());
    }

    @Test
    void actionReturnTypeIsOptionalButAtMostOneAndTyped() throws Exception {
        CsdlModel legal = parseOperations("<Action Name=\"Void\"/>");
        assertNull(legal.schemas().get(0).actions().get(0).returnType());

        IllegalArgumentException missingType = parseFailure(operationSchema("""
                <Action Name="Invalid"><ReturnType/></Action>
                """));
        assertTrue(missingType.getMessage().contains("Type"), missingType.getMessage());

        IllegalArgumentException duplicate = parseFailure(operationSchema("""
                <Action Name="Invalid">
                  <ReturnType Type="Edm.String"/>
                  <ReturnType Type="Edm.Int32"/>
                </Action>
                """));
        assertTrue(duplicate.getMessage().contains("Invalid") && duplicate.getMessage().contains("ReturnType"),
                duplicate.getMessage());
    }

    @Test
    void functionAndActionImportsRequireNamesAndOperationReferences() {
        IllegalArgumentException functionName = parseFailure(operationSchema("""
                <EntityContainer Name="C">
                  <FunctionImport Function="NS.F"/>
                </EntityContainer>
                """));
        assertTrue(functionName.getMessage().contains("Name"), functionName.getMessage());

        IllegalArgumentException functionReference = parseFailure(operationSchema("""
                <EntityContainer Name="C">
                  <FunctionImport Name="F"/>
                </EntityContainer>
                """));
        assertTrue(functionReference.getMessage().contains("Function"), functionReference.getMessage());

        IllegalArgumentException actionName = parseFailure(operationSchema("""
                <EntityContainer Name="C">
                  <ActionImport Action="NS.A"/>
                </EntityContainer>
                """));
        assertTrue(actionName.getMessage().contains("Name"), actionName.getMessage());

        IllegalArgumentException actionReference = parseFailure(operationSchema("""
                <EntityContainer Name="C">
                  <ActionImport Name="A"/>
                </EntityContainer>
                """));
        assertTrue(actionReference.getMessage().contains("Action"), actionReference.getMessage());
    }

    @Test
    void navigationPropertyBindingsRequirePathAndTarget() {
        IllegalArgumentException missingPath = parseFailure(operationSchema("""
                <EntityContainer Name="C">
                  <EntitySet Name="Entities" EntityType="NS.Entity">
                    <NavigationPropertyBinding Target="NS.Entity"/>
                  </EntitySet>
                </EntityContainer>
                """));
        assertTrue(missingPath.getMessage().contains("Path"), missingPath.getMessage());

        IllegalArgumentException missingTarget = parseFailure(operationSchema("""
                <EntityContainer Name="C">
                  <EntitySet Name="Entities" EntityType="NS.Entity">
                    <NavigationPropertyBinding Path="Related"/>
                  </EntitySet>
                </EntityContainer>
                """));
        assertTrue(missingTarget.getMessage().contains("Target"), missingTarget.getMessage());
    }

    @Test
    void keyRequiresAtLeastOneEdmPropertyRef() {
        IllegalArgumentException ex = parseFailure(operationSchema("""
                <EntityType Name="Entity"><Key/></EntityType>
                """));

        assertTrue(ex.getMessage().contains("Key") && ex.getMessage().contains("PropertyRef"),
                ex.getMessage());
    }

    @Test
    void completeReferentialConstraintShapesRemainLegal() throws Exception {
        CsdlModel nested = parse(constraintMetadata("""
                <ReferentialConstraint>
                  <Principal><PropertyRef Name="Id"/></Principal>
                  <Dependent><PropertyRef Name="ParentId"/></Dependent>
                </ReferentialConstraint>
                """));
        CsdlModel legacy = parse(constraintMetadata(
                "<ReferentialConstraint Property=\"ParentId\" ReferencedProperty=\"Id\"/>"));

        assertEquals("ParentId", constraint(nested).property());
        assertEquals("Id", constraint(nested).referencedProperty());
        assertEquals(constraint(nested), constraint(legacy));
    }

    @Test
    void referentialConstraintRequiresOneCompleteShape() {
        List<String> invalid = List.of(
                "<ReferentialConstraint/>",
                "<ReferentialConstraint Property=\"ParentId\"/>",
                "<ReferentialConstraint ReferencedProperty=\"Id\"/>",
                "<ReferentialConstraint Property=\"\" ReferencedProperty=\"Id\"/>",
                "<ReferentialConstraint Property=\"ParentId\" ReferencedProperty=\"\"/>",
                "<ReferentialConstraint><Principal><PropertyRef Name=\"Id\"/></Principal></ReferentialConstraint>",
                "<ReferentialConstraint><Dependent><PropertyRef Name=\"ParentId\"/></Dependent></ReferentialConstraint>",
                "<ReferentialConstraint><PropertyRef Name=\"ParentId\"/></ReferentialConstraint>",
                "<ReferentialConstraint Property=\"ParentId\" ReferencedProperty=\"Id\"><Principal><PropertyRef Name=\"Id\"/></Principal><Dependent><PropertyRef Name=\"ParentId\"/></Dependent></ReferentialConstraint>");

        for (String element : invalid) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parse(constraintMetadata(element)), element);
            assertTrue(ex.getMessage().contains("ReferentialConstraint"), ex.getMessage());
        }
    }

    @Test
    void legalWhitespaceWrappedCollectionTypeStillParses() throws Exception {
        CsdlModel model = parseOperations("""
                <EntityType Name="Entity">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  <Property Name="Tags" Type="Collection( Edm.String )"/>
                </EntityType>
                """);

        assertEquals("Collection(Edm.String)", model.schemas().get(0).entityTypes().get(0)
                .properties().get(1).edmType());
    }

    @Test
    void emptyNestedAndUnbalancedCollectionTypesAreRejected() {
        for (String type : List.of(
                "Collection()",
                "Collection( )",
                "Collection(Collection(Edm.String))",
                "Collection(Edm.String",
                "Collection Edm.String)")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parse(operationSchema("<EntityType Name=\"Entity\"><Property Name=\"Bad\" Type=\""
                            + type + "\"/></EntityType>")), type);
            assertTrue(ex.getMessage().contains("Collection"), ex.getMessage());
        }
    }

    @Test
    void malformedCollectionUnderlyingTypeIsRejectedOnEnumType() {
        IllegalArgumentException ex = parseFailure(operationSchema("""
                <EnumType Name="Invalid" UnderlyingType="Collection()"/>
                """));

        assertTrue(ex.getMessage().contains("EnumType") && ex.getMessage().contains("Collection"),
                ex.getMessage());
    }

    @Test
    void edmxVersionIsRequired() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx xmlns:edmx="%s">
                  <edmx:DataServices/>
                </edmx:Edmx>
                """.formatted(EDMX_NS);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse(xml));
        assertTrue(ex.getMessage().contains("Edmx") && ex.getMessage().contains("Version"), ex.getMessage());
    }

    @Test
    void edmxRequiresExactlyOneDataServices() {
        IllegalArgumentException missing = parseFailure("""
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="%s"/>
                """.formatted(EDMX_NS));
        assertTrue(missing.getMessage().contains("DataServices"), missing.getMessage());

        String schema = schema("""
                <EntityType Name="Entity">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                </EntityType>
                """);
        IllegalArgumentException duplicate = parseFailure(document(dataServices(schema) + dataServices(schema)));
        assertTrue(duplicate.getMessage().contains("DataServices"), duplicate.getMessage());
    }

    @Test
    void unknownRootChildWarns() throws Exception {
        CsdlModel model = parse(document("""
                <edmx:Bogus xmlns:edmx="%s"/>
                %s
                """.formatted(EDMX_NS, dataServices(schema("""
                        <EntityType Name="Entity">
                          <Key><PropertyRef Name="Id"/></Key>
                          <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        </EntityType>
                        """)))));

        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("Bogus")), model.warnings().toString());
    }

    @Test
    void referenceIsSkippedWithoutExternalReferenceSupport() throws Exception {
        CsdlModel model = parse(document("""
                <edmx:Reference Uri="urn:other">
                  <edmx:Include Namespace="Other"/>
                </edmx:Reference>
                %s
                """.formatted(dataServices(schema("""
                        <EntityType Name="Entity">
                          <Key><PropertyRef Name="Id"/></Key>
                          <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                        </EntityType>
                        """)))));

        assertEquals(1, model.schemas().size());
        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("Reference")), model.warnings().toString());
    }

    @Test
    void unknownPropertyAndTypeDefinitionChildrenWarnButAnnotationsStaySilent() throws Exception {
        CsdlModel model = parseOperations("""
                <EntityType Name="Entity">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false">
                    <BogusPropertyChild/>
                    <Annotation Term="NS.Term"/>
                  </Property>
                </EntityType>
                <TypeDefinition Name="Length" UnderlyingType="Edm.Int32">
                  <BogusTypeDefinitionChild/>
                  <Annotation Term="NS.Term"/>
                </TypeDefinition>
                """);

        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("BogusPropertyChild")),
                model.warnings().toString());
        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("BogusTypeDefinitionChild")),
                model.warnings().toString());
        assertFalse(model.warnings().stream().anyMatch(w -> w.contains("Annotation")),
                model.warnings().toString());
    }

    private static String constraintMetadata(String constraint) {
        return document(dataServices(schema("""
                <EntityType Name="Parent">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                </EntityType>
                <EntityType Name="Child">
                  <Key><PropertyRef Name="Id"/></Key>
                  <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  <Property Name="ParentId" Type="Edm.Int32" Nullable="false"/>
                  <NavigationProperty Name="Parent" Type="NS.Parent">
                    %s
                  </NavigationProperty>
                </EntityType>
                """.formatted(constraint))));
    }

    private static io.github.akbarhusain.odata.core.model.CsdlModel.ReferentialConstraintModel constraint(
            CsdlModel model) {
        return model.schemas().get(0).entityTypes().stream()
                .filter(e -> e.name().equals("Child"))
                .findFirst().orElseThrow()
                .navigationProperties().get(0)
                .referentialConstraints().get(0);
    }
}
