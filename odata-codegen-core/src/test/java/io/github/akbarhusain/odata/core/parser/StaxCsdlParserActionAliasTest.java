package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H1: parseAction ReturnType missing resolveTypeRef.
 * Alias-qualified Type in Action ReturnType must be resolved like Function.
 */
class StaxCsdlParserActionAliasTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void actionReturnTypeAliasIsResolved() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Com.Example.Model" Alias="self" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Person">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <Action Name="DoSomething" IsBound="true">
                    <Parameter Name="bindingParameter" Type="self.Person"/>
                    <ReturnType Type="self.Person"/>
                  </Action>
                  <Action Name="UnboundAction">
                    <ReturnType Type="Collection(self.Person)"/>
                  </Action>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        CsdlModel model = parse(xml);
        var schema = model.schemas().get(0);
        var bound = schema.actions().stream().filter(a -> a.name().equals("DoSomething")).findFirst().orElseThrow();
        assertNotNull(bound.returnType(), "bound action should have returnType");
        assertEquals("Com.Example.Model.Person", bound.returnType().type(),
                "H1: Action ReturnType alias 'self.Person' must resolve to real namespace (like Function does)");

        var unbound = schema.actions().stream().filter(a -> a.name().equals("UnboundAction")).findFirst().orElseThrow();
        assertEquals("Collection(Com.Example.Model.Person)", unbound.returnType().type(),
                "H1: Collection(self.Person) must be resolved inside wrapper");
    }

    @Test
    void functionReturnTypeAliasIsResolvedForComparison() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="Com.Example.Model" Alias="self" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Person">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <Function Name="GetPerson" IsBound="true" IsComposable="false">
                    <Parameter Name="bindingParameter" Type="self.Person"/>
                    <ReturnType Type="self.Person"/>
                  </Function>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        CsdlModel model = parse(xml);
        var fn = model.schemas().get(0).functions().get(0);
        assertEquals("Com.Example.Model.Person", fn.returnType().type(),
                "Function ReturnType already resolves alias — Action should match");
    }
}
