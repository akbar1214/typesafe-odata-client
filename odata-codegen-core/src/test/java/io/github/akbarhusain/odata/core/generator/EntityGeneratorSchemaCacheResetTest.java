package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A single {@link EntityGenerator} (single-schema mode, {@code allSchemas} empty) must
 * rebuild its schema/type caches when the owning schema changes — otherwise an
 * unqualified same-simple-name base resolves against the previously generated schema.
 */
class EntityGeneratorSchemaCacheResetTest {

    private static final String TWO_SCHEMAS = """
        <?xml version="1.0" encoding="utf-8"?>
        <edmx:Edmx Version="4.0" xmlns:edmx="http://docs.oasis-open.org/odata/ns/edmx">
          <edmx:DataServices>
            <Schema Namespace="NS.A" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EntityType Name="Base">
                <Key><PropertyRef Name="Id"/></Key>
                <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                <Property Name="NameA" Type="Edm.String"/>
              </EntityType>
              <EntityType Name="Child" BaseType="Base">
                <Property Name="Extra" Type="Edm.String"/>
              </EntityType>
            </Schema>
            <Schema Namespace="NS.B" xmlns="http://docs.oasis-open.org/odata/ns/edm">
              <EntityType Name="Base">
                <Key><PropertyRef Name="Id"/></Key>
                <Property Name="Id" Type="Edm.Int32" Nullable="false"/>
                <Property Name="NameB" Type="Edm.String"/>
              </EntityType>
              <EntityType Name="Child" BaseType="Base">
                <Property Name="Extra" Type="Edm.String"/>
              </EntityType>
            </Schema>
          </edmx:DataServices>
        </edmx:Edmx>
        """;

    private static CsdlModel.EntityTypeModel child(CsdlModel.SchemaModel schema) {
        return schema.entityTypes().stream()
                .filter(e -> e.name().equals("Child"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void reusedSingleSchemaGeneratorRebuildsCachesOnSchemaChange() throws Exception {
        CsdlModel model = new StaxCsdlParser().parse(
                new ByteArrayInputStream(TWO_SCHEMAS.getBytes(StandardCharsets.UTF_8)));
        CsdlModel.SchemaModel schemaA = model.schemas().get(0);
        CsdlModel.SchemaModel schemaB = model.schemas().get(1);

        // Single-schema mode (allSchemas empty) — the generator owns effectiveSchemas,
        // so a schema switch must invalidate the caches built for the previous schema.
        EntityGenerator generator = new EntityGenerator("com", Map.of(), null);
        generator.generate(child(schemaA), schemaA);

        String childB = generator.generate(child(schemaB), schemaB);
        assertTrue(childB.contains("NAME_B"),
                "NS.B.Child must inherit NS.B.Base's NameB after the schema switch, got:\n" + childB);
        assertFalse(childB.contains("NAME_A"),
                "a stale schema cache must not leak NS.A.Base into NS.B.Child:\n" + childB);
    }
}
