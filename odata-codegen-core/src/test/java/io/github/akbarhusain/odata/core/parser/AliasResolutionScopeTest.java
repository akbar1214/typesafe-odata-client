package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An OData alias is a prefix substitution for the schema that DECLARES it. It must never
 * shadow a real namespace, and it must never be applied to a name that is already fully
 * qualified.
 *
 * <p>The rewrite keyed only on the first dot-separated segment, so a legitimate
 * fully-qualified cross-schema reference whose leading segment happened to equal some
 * schema's alias was rewritten — and the corruption compounded with each additional
 * prefixed segment, and applied a second time in the post-pass.
 */
class AliasResolutionScopeTest {

    private static final String HEADER =
            "<edmx:Edmx Version=\"4.0\" xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\">"
                    + "<edmx:DataServices>";
    private static final String FOOTER = "</edmx:DataServices></edmx:Edmx>";
    private static final String EDM = "xmlns=\"http://docs.oasis-open.org/odata/ns/edm\"";

    /**
     * The alias is the FIRST segment of a namespace that is itself two segments, which is
     * what made the naive rewrite corrupt a valid reference.
     */
    @Test
    void fullyQualifiedReferenceIsNotRewrittenWhenItsPrefixMatchesAnAlias() {
        CsdlModel model = parse(HEADER
                + "<Schema Namespace=\"Contoso.Model\" Alias=\"Contoso\" " + EDM + ">"
                + "<ComplexType Name=\"Address\"><Property Name=\"City\" Type=\"Edm.String\"/></ComplexType>"
                + "<EntityType Name=\"Employee\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                + "<Property Name=\"Home\" Type=\"Contoso.Model.Address\"/>"
                + "</EntityType>"
                + "</Schema>"
                + "<Schema Namespace=\"Other\" " + EDM + ">"
                + "<EntityType Name=\"Foo\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"Home\" Type=\"Contoso.Model.Address\"/>"
                + "<NavigationProperty Name=\"Peer\" Type=\"Contoso.Model.Employee\"/>"
                + "</EntityType>"
                + "</Schema>"
                + FOOTER);

        var other = model.schemas().stream()
                .filter(s -> s.namespace().equals("Other")).findFirst().orElseThrow();
        var foo = other.entityTypes().stream()
                .filter(e -> e.name().equals("Foo")).findFirst().orElseThrow();
        assertEquals("Contoso.Model.Address", foo.properties().get(1).edmType(),
                "a fully-qualified reference must survive an alias-prefix coincidence");
        assertEquals("Contoso.Model.Employee", foo.navigationProperties().get(0).type(),
                "nav targets must survive too");
    }

    /**
     * The real alias reference — the short form, as used from within the declaring schema
     * and from other schemas — must still be rewritten. Without this the previous test
     * would pass simply because nothing was resolved at all.
     */
    @Test
    void genuineAliasReferenceIsStillRewritten() {
        CsdlModel model = parse(HEADER
                + "<Schema Namespace=\"Contoso.Model\" Alias=\"Contoso\" " + EDM + ">"
                + "<ComplexType Name=\"Address\"><Property Name=\"City\" Type=\"Edm.String\"/></ComplexType>"
                + "</Schema>"
                + "<Schema Namespace=\"Other\" " + EDM + ">"
                + "<EntityType Name=\"Foo\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"Home\" Type=\"Contoso.Address\"/>"
                + "</EntityType>"
                + "</Schema>"
                + FOOTER);

        var other = model.schemas().stream()
                .filter(s -> s.namespace().equals("Other")).findFirst().orElseThrow();
        var foo = other.entityTypes().get(0);
        assertEquals("Contoso.Model.Address", foo.properties().get(1).edmType(),
                "the alias short form must resolve to the declaring namespace");
    }

    /**
     * Order independence: the referencing schema may be parsed BEFORE the schema that
     * declares the alias. This is why the post-pass exists at all, and it must keep
     * working with the new namespace check.
     */
    @Test
    void aliasDeclaredInALaterSchemaStillResolves() {
        CsdlModel model = parse(HEADER
                + "<Schema Namespace=\"Other\" " + EDM + ">"
                + "<EntityType Name=\"Foo\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<Property Name=\"Home\" Type=\"Late.Address\"/>"
                + "</EntityType>"
                + "</Schema>"
                + "<Schema Namespace=\"Later\" Alias=\"Late\" " + EDM + ">"
                + "<ComplexType Name=\"Address\"><Property Name=\"City\" Type=\"Edm.String\"/></ComplexType>"
                + "</Schema>"
                + FOOTER);

        var other = model.schemas().stream()
                .filter(s -> s.namespace().equals("Other")).findFirst().orElseThrow();
        assertEquals("Later.Address", other.entityTypes().get(0).properties().get(1).edmType());
    }

    /** A collection wrapper must be unwrapped, rewritten and re-wrapped exactly once. */
    @Test
    void collectionWrappedAliasReferenceIsRewrittenOnce() {
        CsdlModel model = parse(HEADER
                + "<Schema Namespace=\"Contoso.Model\" Alias=\"Contoso\" " + EDM + ">"
                + "<EntityType Name=\"Employee\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.String\" Nullable=\"false\"/>"
                + "</EntityType>"
                + "</Schema>"
                + "<Schema Namespace=\"Other\" " + EDM + ">"
                + "<EntityType Name=\"Foo\">"
                + "<Key><PropertyRef Name=\"Id\"/></Key>"
                + "<Property Name=\"Id\" Type=\"Edm.Int32\" Nullable=\"false\"/>"
                + "<NavigationProperty Name=\"Reports\" Type=\"Collection(Contoso.Employee)\"/>"
                + "</EntityType>"
                + "</Schema>"
                + FOOTER);

        var other = model.schemas().stream()
                .filter(s -> s.namespace().equals("Other")).findFirst().orElseThrow();
        assertEquals("Collection(Contoso.Model.Employee)",
                other.entityTypes().get(0).navigationProperties().get(0).type());
    }

    private static CsdlModel parse(String xml) {
        try {
            return new StaxCsdlParser().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("parse failed", e);
        }
    }
}
