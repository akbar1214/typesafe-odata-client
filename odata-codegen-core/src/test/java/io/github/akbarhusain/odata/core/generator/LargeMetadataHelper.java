package io.github.akbarhusain.odata.core.generator;

/**
 * Generates valid CSDL metadata XML with a configurable number of entity types
 * for performance testing.
 */
public class LargeMetadataHelper {

    public static String generateMetadata(int entityCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        sb.append("<edmx:Edmx Version=\"4.0\" xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\">");
        sb.append("<edmx:DataServices>");
        sb.append("<Schema Namespace=\"LargeTest\" xmlns=\"http://docs.oasis-open.org/odata/ns/edm\">");

        // Enum type
        sb.append("<EnumType Name=\"Status\">");
        sb.append("<Member Name=\"Active\" Value=\"0\"/>");
        sb.append("<Member Name=\"Inactive\" Value=\"1\"/>");
        sb.append("</EnumType>");

        // Complex type
        sb.append("<ComplexType Name=\"Address\">");
        sb.append("<Property Name=\"Street\" Type=\"Edm.String\" Nullable=\"false\"/>");
        sb.append("<Property Name=\"City\" Type=\"Edm.String\" Nullable=\"false\"/>");
        sb.append("<Property Name=\"ZipCode\" Type=\"Edm.String\"/>");
        sb.append("</ComplexType>");

        // Entity types
        for (int i = 0; i < entityCount; i++) {
            sb.append("<EntityType Name=\"Entity").append(i).append("\">");
            sb.append("<Key><PropertyRef Name=\"Id\"/></Key>");
            sb.append("<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"Name\" Type=\"Edm.String\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"Description\" Type=\"Edm.String\"/>");
            sb.append("<Property Name=\"Price\" Type=\"Edm.Decimal\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"IsActive\" Type=\"Edm.Boolean\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"CreatedAt\" Type=\"Edm.DateTimeOffset\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"Score\" Type=\"Edm.Double\"/>");
            sb.append("<Property Name=\"Tags\" Type=\"Collection(Edm.String)\"/>");
            sb.append("<Property Name=\"Address\" Type=\"LargeTest.Address\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"Status\" Type=\"LargeTest.Status\" Nullable=\"false\"/>");
            // Nav to previous entity (except first)
            if (i > 0) {
                sb.append("<NavigationProperty Name=\"Related\" Type=\"LargeTest.Entity").append(i - 1).append("\"/>");
            }
            sb.append("</EntityType>");
        }

        // Entity container
        sb.append("<EntityContainer Name=\"TestContainer\">");
        for (int i = 0; i < entityCount; i++) {
            sb.append("<EntitySet Name=\"Entity").append(i).append("Set\" EntityType=\"LargeTest.Entity").append(i).append("\"/>");
        }
        sb.append("</EntityContainer>");

        sb.append("</Schema>");
        sb.append("</edmx:DataServices>");
        sb.append("</edmx:Edmx>");
        return sb.toString();
    }

    /**
     * Generates multi-schema CSDL metadata where each entity inherits from a
     * DIFFERENT entity in a DIFFERENT schema, creating 1000 independent
     * 10-deep inheritance chains across 10 schemas.
     *
     * Schema S0 has 1000 standalone entities (E0_0...E0_{N-1}) with their own keys.
     * Schema S1 has E1_i extending S0.E0_i.
     * Schema S2 has E2_i extending S1.E1_i.
     * ...and so on up to S9.
     *
     * Every inheritance relationship crosses schema boundaries.
     * Key and base properties are resolved by walking the full chain.
     */
    public static String generateMultiSchemaMetadata(int entitiesPerSchema, int schemaCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        sb.append("<edmx:Edmx Version=\"4.0\" xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\">");
        sb.append("<edmx:DataServices>");

        String baseNs = "PerfTest";

        // --- Schema S0 ---
        String ns0 = "PerfTest.S0";
        sb.append("<Schema Namespace=\"").append(ns0).append("\" xmlns=\"http://docs.oasis-open.org/odata/ns/edm\">");

        // Enum and complex types shared across all schemas
        sb.append("<EnumType Name=\"Status\">");
        sb.append("<Member Name=\"Active\" Value=\"0\"/>");
        sb.append("<Member Name=\"Inactive\" Value=\"1\"/>");
        sb.append("</EnumType>");
        sb.append("<ComplexType Name=\"Address\">");
        sb.append("<Property Name=\"Street\" Type=\"Edm.String\" Nullable=\"false\"/>");
        sb.append("<Property Name=\"City\" Type=\"Edm.String\" Nullable=\"false\"/>");
        sb.append("</ComplexType>");

        for (int i = 0; i < entitiesPerSchema; i++) {
            sb.append("<EntityType Name=\"E0_").append(i).append("\">");
            sb.append("<Key><PropertyRef Name=\"Id\"/></Key>");
            sb.append("<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"Name\" Type=\"Edm.String\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"Description\" Type=\"Edm.String\"/>");
            sb.append("<Property Name=\"IsActive\" Type=\"Edm.Boolean\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"CreatedAt\" Type=\"Edm.DateTimeOffset\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"Score\" Type=\"Edm.Double\"/>");
            sb.append("<Property Name=\"Tags\" Type=\"Collection(Edm.String)\"/>");
            sb.append("<Property Name=\"Address\" Type=\"").append(baseNs).append(".S0.Address\" Nullable=\"false\"/>");
            sb.append("<Property Name=\"Status\" Type=\"").append(baseNs).append(".S0.Status\" Nullable=\"false\"/>");
            // Nav to entity at same index in S0 itself (circular for self-schema ref)
            sb.append("<NavigationProperty Name=\"Related\" Type=\"").append(ns0).append(".E0_").append(i).append("\"/>");
            sb.append("</EntityType>");
        }

        sb.append("<EntityContainer Name=\"Container0\">");
        for (int i = 0; i < entitiesPerSchema; i++) {
            sb.append("<EntitySet Name=\"E0_").append(i).append("Set\" EntityType=\"").append(ns0).append(".E0_").append(i).append("\"/>");
        }
        sb.append("</EntityContainer>");
        sb.append("</Schema>");

        // --- Schemas S1 through S_{schemaCount-1} ---
        // Each entity E{j}_{i} extends E{j-1}_{i} from the previous schema
        for (int j = 1; j < schemaCount; j++) {
            String ns = "PerfTest.S" + j;
            String prevNs = "PerfTest.S" + (j - 1);

            sb.append("<Schema Namespace=\"").append(ns).append("\" xmlns=\"http://docs.oasis-open.org/odata/ns/edm\">");

            // Cross-schema: each entity extends E{j-1}_{i} from S{j-1}
            for (int i = 0; i < entitiesPerSchema; i++) {
                String entityName = "E" + j + "_" + i;
                String baseType = prevNs + ".E" + (j - 1) + "_" + i;
                sb.append("<EntityType Name=\"").append(entityName)
                  .append("\" BaseType=\"").append(baseType).append("\">");
                sb.append("<Property Name=\"OwnValue").append(j).append("\" Type=\"Edm.Int32\" Nullable=\"false\"/>");
                sb.append("<Property Name=\"OwnActive").append(j).append("\" Type=\"Edm.Boolean\" Nullable=\"false\"/>");
                // Cross-schema nav to entity at same index in S0
                sb.append("<NavigationProperty Name=\"RefS0\" Type=\"").append(ns0).append(".E0_").append(i).append("\"/>");
                sb.append("</EntityType>");
            }

            sb.append("<EntityContainer Name=\"Container").append(j).append("\">");
            for (int i = 0; i < entitiesPerSchema; i++) {
                sb.append("<EntitySet Name=\"E").append(j).append("_").append(i)
                  .append("Set\" EntityType=\"").append(ns).append(".E").append(j).append("_").append(i).append("\"/>");
            }
            sb.append("</EntityContainer>");
            sb.append("</Schema>");
        }

        sb.append("</edmx:DataServices>");
        sb.append("</edmx:Edmx>");
        return sb.toString();
    }
}
