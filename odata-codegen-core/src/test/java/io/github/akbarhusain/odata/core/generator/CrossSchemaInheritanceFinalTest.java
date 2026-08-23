package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H3: Cross-schema inheritance always final.
 * Base in NS.A, Derived in NS.B with BaseType="NS.A.Base" — Base must be emitted
 * as non-final (public class, not public final class) so Derived can extend it.
 * Also covers unqualified cross-schema BaseType="Base" as variant.
 */
class CrossSchemaInheritanceFinalTest {

    private CsdlModel parse(String xml) throws Exception {
        return new StaxCsdlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void crossSchemaQualifiedBaseIsNotFinal() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS.A" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Base">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                    <Property Name="Name" Type="Edm.String"/>
                  </EntityType>
                </Schema>
                <Schema Namespace="NS.B" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Derived" BaseType="NS.A.Base">
                    <Property Name="Extra" Type="Edm.String"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        CsdlModel model = parse(xml);
        // Generate into temp dir with shared package to avoid import complexity
        Path tmp = Files.createTempDirectory("gen-h3");
        Generator gen = new Generator(tmp, java.util.Map.of(), "com.test");
        gen.generate(model);

        // Read Base.java
        Path baseFile = tmp.resolve("com/test/entity/Base.java");
        assertTrue(Files.exists(baseFile), "Base.java must be generated");
        String baseCode = Files.readString(baseFile);
        assertTrue(baseCode.contains("public class Base"), "Base code must be public class: " + baseCode.substring(0, Math.min(400, baseCode.length())));
        assertFalse(baseCode.contains("public final class Base"),
                "H3: Base with cross-schema subclass must NOT be final (qualified BaseType)");
        assertFalse(baseCode.contains("public abstract class Base"), "Base is concrete");

        Path derivedFile = tmp.resolve("com/test/entity/Derived.java");
        assertTrue(Files.exists(derivedFile));
        String derivedCode = Files.readString(derivedFile);
        assertTrue(derivedCode.contains("extends Base"), "Derived must extend Base: " + derivedCode.substring(0, 500));
    }

    @Test
    void crossSchemaUnqualifiedBaseIsNotFinal() throws Exception {
        // Variant: Derived uses unqualified BaseType="Base" — parser keeps "Base",
        // EntityGenerator must still find Base in NS.A and mark it non-final.
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS.A" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Base">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
                <Schema Namespace="NS.B" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Derived" BaseType="Base">
                    <Property Name="Extra" Type="Edm.String"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        CsdlModel model = parse(xml);
        Path tmp = Files.createTempDirectory("gen-h3-unq");
        Generator gen = new Generator(tmp, java.util.Map.of(), "com.test");
        gen.generate(model);
        String baseCode = Files.readString(tmp.resolve("com/test/entity/Base.java"));
        assertFalse(baseCode.contains("public final class Base"),
                "H3: unqualified cross-schema Base should also be non-final after fix");
    }

    // Same policy as ambiguous container Extends: an unqualified BaseType matching
    // several schemas' types must fail loudly instead of first-wins (order-dependent)
    @Test
    void unqualifiedAmbiguousBaseFailsLoudly() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
              <edmx:DataServices>
                <Schema Namespace="NS.A" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Base">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
                <Schema Namespace="NS.B" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Base">
                    <Key><PropertyRef Name="Id"/></Key>
                    <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                  </EntityType>
                </Schema>
                <Schema Namespace="NS.C" xmlns="http://docs.oasis-open.org/odata/ns/edm">
                  <EntityType Name="Derived" BaseType="Base">
                    <Property Name="Extra" Type="Edm.String"/>
                  </EntityType>
                </Schema>
              </edmx:DataServices>
            </edmx:Edmx>
            """;
        CsdlModel model = parse(xml);
        Path tmp = Files.createTempDirectory("gen-h3-ambig");
        Generator gen = new Generator(tmp, java.util.Map.of(), "com.test");
        Exception ex = assertThrows(Exception.class, () -> gen.generate(model),
                "unqualified BaseType='Base' with two candidate Base entities must be rejected");
        assertTrue(ex.getMessage().contains("Ambiguous") && ex.getMessage().contains("qualified"),
                "message should explain the ambiguity and the qualified-name remedy, got: "
                        + ex.getMessage());
    }
}
