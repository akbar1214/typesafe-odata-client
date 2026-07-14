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
}
