package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.EnumTypeModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CSDL spec: an enum Member without Value defaults to the previous member's
 * value plus one (or 0 for the first member) — not to the member count.
 */
class StaxCsdlParserEnumMemberValueTest {

    private static final String METADATA = """
        <?xml version="1.0" encoding="utf-8"?>
        <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
          <edmx:DataServices>
            <Schema Namespace="TestNS" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EnumType Name="Mixed">
                <Member Name="None" Value="0"/>
                <Member Name="Low" Value="10"/>
                <Member Name="Medium"/>
                <Member Name="High"/>
                <Member Name="Explicit" Value="100"/>
                <Member Name="After"/>
              </EnumType>
              <EnumType Name="AllImplicit">
                <Member Name="A"/>
                <Member Name="B"/>
                <Member Name="C"/>
              </EnumType>
            </Schema>
          </edmx:DataServices>
        </edmx:Edmx>
        """;

    private EnumTypeModel parseEnum(String enumName) throws Exception {
        CsdlModel model = new StaxCsdlParser().parse(
                new ByteArrayInputStream(METADATA.getBytes(StandardCharsets.UTF_8)));
        return model.schemas().get(0).enumTypes().stream()
                .filter(e -> e.name().equals(enumName)).findFirst().orElseThrow();
    }

    @Test
    void implicitValueIsPreviousPlusOne() throws Exception {
        EnumTypeModel mixed = parseEnum("Mixed");
        assertEquals(0, mixed.members().get(0).value(), "None");
        assertEquals(10, mixed.members().get(1).value(), "Low");
        assertEquals(11, mixed.members().get(2).value(),
                "Medium must default to Low(10) + 1, not member count 2");
        assertEquals(12, mixed.members().get(3).value(), "High must default to Medium(11) + 1");
        assertEquals(100, mixed.members().get(4).value(), "Explicit");
        assertEquals(101, mixed.members().get(5).value(), "After must default to Explicit(100) + 1");
    }

    @Test
    void allImplicitValuesCountFromZero() throws Exception {
        EnumTypeModel allImplicit = parseEnum("AllImplicit");
        assertEquals(0, allImplicit.members().get(0).value(), "A");
        assertEquals(1, allImplicit.members().get(1).value(), "B");
        assertEquals(2, allImplicit.members().get(2).value(), "C");
    }
}
