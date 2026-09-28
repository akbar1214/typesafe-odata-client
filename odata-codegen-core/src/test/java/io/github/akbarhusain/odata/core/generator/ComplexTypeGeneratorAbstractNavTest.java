package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ComplexTypeGeneratorAbstractNavTest {

    private static final String METADATA = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Thing">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <ComplexType Name="Base" Abstract="true">
                    <Property Name="Name" Type="Edm.String"/>
                    <NavigationProperty Name="Ref" Type="NS.Thing"/>
                  </ComplexType>
                  <ComplexType Name="Derived" BaseType="NS.Base">
                    <Property Name="Extra" Type="Edm.String"/>
                  </ComplexType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;

    private String generate(String typeName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        CsdlModel model;
        try (var is = new ByteArrayInputStream(METADATA.getBytes(StandardCharsets.UTF_8))) {
            model = parser.parse(is);
        }
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.ComplexTypeModel type = schema.complexTypes().stream()
                .filter(c -> c.name().equals(typeName))
                .findFirst()
                .orElseThrow();
        ComplexTypeGenerator gen = new ComplexTypeGenerator("com.example.ns");
        gen.setGenerateWithMethods(true);
        return gen.generate(type, schema);
    }

    @Test
    void abstractComplexTypeWithNavDoesNotEmitWithOrNavWith() throws Exception {
        String code = generate("Base");
        assertTrue(code.contains("public abstract class Base"),
                "Base should be abstract");
        assertFalse(code.contains("new Base("),
                "abstract complex type must not construct itself (no with*/navWith)");
        assertFalse(code.contains("public Base withName"),
                "abstract complex type must not get with* methods");
        assertFalse(code.contains("public Base withRef"),
                "abstract complex type must not get nav-with methods");
    }

    @Test
    void concreteSubtypeGetsWithMethods() throws Exception {
        String code = generate("Derived");
        assertTrue(code.contains("new Derived("),
                "concrete subtype should get with* methods");
        assertTrue(code.contains("public Derived withRef("),
                "concrete subtype should get nav-with methods (proves the path is reachable)");
    }
}
