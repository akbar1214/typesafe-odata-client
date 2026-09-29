package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.EntityTypeModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.SchemaModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An alias is a prefix substitution for the schema that DECLARES it. CSDL v4.01 §5.1
 * constrains what an alias may be, and §5.1 states the document-wide constraint that an
 * alias "MUST differ from the namespaces of all schemas" and "MUST NOT be one of the
 * reserved values {@code Edm}, {@code odata}, {@code System}, or {@code Transient}".
 */
class StaxCsdlParserAliasGuardTest {

    private static final String EDMX =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\">"
            + "<edmx:DataServices>";
    private static final String EDM =
            "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    private static CsdlModel parseDoc(String body) {
        String xml = EDMX + body + "</edmx:DataServices></edmx:Edmx>";
        try {
            return new StaxCsdlParser().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new AssertionError(e);
        }
    }

    private static String propertyType(CsdlModel model, String namespace, String entity, String property) {
        for (SchemaModel schema : model.schemas()) {
            if (!schema.namespace().equals(namespace)) {
                continue;
            }
            for (EntityTypeModel type : schema.entityTypes()) {
                if (!type.name().equals(entity)) {
                    continue;
                }
                return type.properties().stream()
                        .filter(p -> p.name().equals(property))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no property " + property))
                        .edmType();
            }
        }
        throw new AssertionError("no entity " + namespace + "." + entity);
    }

    // ------------------------------------------------------------------
    // Alias that is a dot-segment of a real namespace
    // ------------------------------------------------------------------

    @Test
    void aliasEqualToTheFirstSegmentOfAnotherNamespaceIsNotApplied() {
        // Schema A declares Namespace="Model.Sub"; Schema B declares
        // Namespace="Contoso.Model" Alias="Model". A reference to "Model.Sub.Foo"
        // is ALREADY fully qualified against A -- it is not using B's alias.
        // CSDL §5.1 only requires the alias to differ from the namespaces
        // themselves ("Model" != "Model.Sub"), so this document is conformant.
        CsdlModel model = parseDoc(
                EDM + " Namespace=\"Model.Sub\">"
                        + "<EntityType Name=\"Foo\"><Key><PropertyRef Name=\"Id\"/></Key>"
                        + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/></EntityType>"
                        + "</Schema>"
                        + EDM + " Namespace=\"Contoso.Model\" Alias=\"Model\">"
                        + "<EntityType Name=\"Bar\"><Key><PropertyRef Name=\"Id\"/></Key>"
                        + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                        + "<Property Name=\"P\" Type=\"Model.Sub.Foo\" Nullable=\"false\"/></EntityType>"
                        + "</Schema>");

        assertEquals("Model.Sub.Foo", propertyType(model, "Contoso.Model", "Bar", "P"),
                "a qualified reference must not be redirected into the alias owner's namespace");
    }

    @Test
    void aGenuineAliasUseInTheSameSituationStillResolves() {
        // The mirror image: "Model.Foo" is NOT a declared namespace, so the alias
        // reading is the only one available and it must still resolve.
        CsdlModel model = parseDoc(
                EDM + " Namespace=\"Model.Sub\">"
                        + "<EntityType Name=\"Foo\"><Key><PropertyRef Name=\"Id\"/></Key>"
                        + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/></EntityType>"
                        + "</Schema>"
                        + EDM + " Namespace=\"Contoso.Model\" Alias=\"Model\">"
                        + "<EntityType Name=\"Bar\"><Key><PropertyRef Name=\"Id\"/></Key>"
                        + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                        + "<Property Name=\"P\" Type=\"Model.Foo\" Nullable=\"false\"/></EntityType>"
                        + "</Schema>");

        assertEquals("Contoso.Model.Foo", propertyType(model, "Contoso.Model", "Bar", "P"));
    }

    @Test
    void aliasResolutionIsIndependentOfSchemaOrder() {
        String first = EDM + " Namespace=\"Model.Sub\">"
                + "<EntityType Name=\"Foo\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/></EntityType>"
                + "</Schema>";
        String second = EDM + " Namespace=\"Contoso.Model\" Alias=\"Model\">"
                + "<EntityType Name=\"Bar\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                + "<Property Name=\"P\" Type=\"Model.Sub.Foo\" Nullable=\"false\"/></EntityType>"
                + "</Schema>";
        CsdlModel reversed = parseDoc(second + first);
        assertEquals("Model.Sub.Foo", propertyType(reversed, "Contoso.Model", "Bar", "P"));
    }

    // ------------------------------------------------------------------
    // Reserved values
    // ------------------------------------------------------------------

    @Test
    void reservedAliasValuesAreRejected() {
        // CSDL v4.01 §5.1: the alias "MUST NOT be one of the reserved values Edm,
        // odata, System, or Transient". Alias="Edm" is the damaging one: it
        // rewrites EVERY primitive reference, so "Edm.String" becomes
        // "My.Ns.String" and the document fails much later with a message that
        // names a property rather than the alias.
        for (String reserved : List.of("Edm", "odata", "System", "Transient")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> parseDoc(EDM + " Namespace=\"My.Ns\" Alias=\"" + reserved + "\">"
                            + "<EntityType Name=\"E\"><Key><PropertyRef Name=\"Id\"/></Key>"
                            + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                            + "</EntityType></Schema>"),
                    "Alias=\"" + reserved + "\" must be rejected at the metadata boundary");
            assertTrue(error.getMessage().contains(reserved),
                    "the message must name the offending value: " + error.getMessage());
        }
    }

    @Test
    void reservedNamespaceValuesAreRejected() {
        for (String reserved : List.of("Edm", "odata", "System", "Transient")) {
            assertThrows(IllegalArgumentException.class,
                    () -> parseDoc(EDM + " Namespace=\"" + reserved + "\">"
                            + "<EntityType Name=\"E\"><Key><PropertyRef Name=\"Id\"/></Key>"
                            + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                            + "</EntityType></Schema>"),
                    "Namespace=\"" + reserved + "\" must be rejected at the metadata boundary");
        }
    }

    @Test
    void aReservedLookingAliasIsNotSilentlyAppliedToPrimitives() {
        // Guards the actual damage, independent of whether the rejection message
        // changes: no reference may resolve to "My.Ns.String" for an Edm type.
        CsdlModel model = parseDoc(EDM + " Namespace=\"My.Ns\">"
                + "<EntityType Name=\"E\"><Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                + "<Property Name=\"N\" Type=\"Edm.Int32\"/></EntityType></Schema>");
        assertEquals("Edm.String", propertyType(model, "My.Ns", "E", "Id"));
        assertEquals("Edm.Int32", propertyType(model, "My.Ns", "E", "N"));
    }
}
