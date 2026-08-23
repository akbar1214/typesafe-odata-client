package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2: Alias scope single-schema — cross-schema alias refs not resolved.
 * Schema B uses alias declared in Schema A (a.Foo) — must resolve to real namespace.
 */
class StaxCsdlParserCrossSchemaAliasTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void crossSchemaAliasInPropertyIsResolved() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS.A" Alias="a" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Foo">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                  <ComplexType Name="Addr">
                    <Property Name="Street" Type="Edm.String"/>
                  </ComplexType>
                </Schema>
                <Schema Namespace="NS.B" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Bar">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="FooRef" Type="a.Foo"/>
                    <Property Name="Tags" Type="Collection(a.Foo)"/>
                    <NavigationProperty Name="FooNav" Type="a.Foo"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        CsdlModel model = parse(xml);
        var bar = model.schemas().stream().filter(s -> s.namespace().equals("NS.B"))
                .flatMap(s -> s.entityTypes().stream())
                .filter(e -> e.name().equals("Bar")).findFirst().orElseThrow();

        var fooRef = bar.properties().stream().filter(p -> p.name().equals("FooRef")).findFirst().orElseThrow();
        assertEquals("NS.A.Foo", fooRef.edmType(),
                "H2: cross-schema alias 'a.Foo' must resolve to 'NS.A.Foo'");

        var tags = bar.properties().stream().filter(p -> p.name().equals("Tags")).findFirst().orElseThrow();
        assertEquals("Collection(NS.A.Foo)", tags.edmType(),
                "H2: Collection wrapper must be preserved while resolving inner alias");

        var nav = bar.navigationProperties().stream().filter(n -> n.name().equals("FooNav")).findFirst().orElseThrow();
        assertEquals("NS.A.Foo", nav.type(),
                "H2: NavigationProperty alias must also resolve cross-schema");
    }

    @Test
    void crossSchemaAliasInCollectionComplex() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS.A" Alias="a" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <ComplexType Name="MyAddr">
                    <Property Name="Street" Type="Edm.String"/>
                  </ComplexType>
                </Schema>
                <Schema Namespace="NS.B" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Person">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="Home" Type="a.MyAddr"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        CsdlModel model = parse(xml);
        var person = model.schemas().stream().filter(s -> s.namespace().equals("NS.B"))
                .flatMap(s -> s.entityTypes().stream()).findFirst().orElseThrow();
        assertEquals("NS.A.MyAddr", person.properties().get(1).edmType(),
                "H2: cross-schema complex type alias must resolve");
    }
}
