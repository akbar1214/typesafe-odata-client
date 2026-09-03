package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch C10): the container merge keys namespaces by container
 * RECORD (value equality), so two identical-valued containers from different
 * schemas collapse last-wins. {@code Two.C} (identical to {@code One.C})
 * extending unqualified {@code Base} then resolves {@code One.Base} instead
 * of {@code Two.Base}. Resolution must use each container's EXACT owning
 * schema (identity, not value equality).
 */
class ContainerNamespaceCollapseTest {

    @Test
    void unqualifiedExtendsResolvesInOwningSchema() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
                  <edmx:DataServices>
                    <Schema Namespace="One" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityContainer Name="C" Extends="Base">
                        <EntitySet Name="SharedSet" EntityType="One.Foo"/>
                      </EntityContainer>
                      <EntityContainer Name="Base">
                        <EntitySet Name="OneBaseSet" EntityType="One.Foo"/>
                      </EntityContainer>
                    </Schema>
                    <Schema Namespace="Two" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                      <EntityContainer Name="C" Extends="Base">
                        <EntitySet Name="SharedSet" EntityType="One.Foo"/>
                      </EntityContainer>
                      <EntityContainer Name="Base">
                        <EntitySet Name="TwoBaseSet" EntityType="Two.Foo"/>
                      </EntityContainer>
                    </Schema>
                  </edmx:DataServices>
                </edmx:Edmx>
                """;
        CsdlModel model = new StaxCsdlParser().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        CsdlModel.ContainerModel c = model.schemas().stream()
                .filter(s -> s.namespace().equals("Two"))
                .flatMap(s -> s.containers().stream())
                .filter(ct -> ct.name().equals("C"))
                .findFirst().orElseThrow();

        assertTrue(c.entitySets().stream().anyMatch(s -> s.name().equals("TwoBaseSet")),
                "Two.C extends Base must merge Two.Base, not One.Base: " + c.entitySets());
        assertTrue(c.entitySets().stream().noneMatch(s -> s.name().equals("OneBaseSet")),
                "One.Base must not leak into Two.C: " + c.entitySets());
    }
}
