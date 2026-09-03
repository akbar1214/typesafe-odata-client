package io.github.akbarhusain.odata.core.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L15: the module-keyword list is complete. L18: collection unwrapping tolerates
 * whitespace and rejects malformed/nested forms. L21: generated type names cannot
 * shadow the runtime query classes imported via wildcard.
 */
class NamesPolishTest {

    @Test
    void l15ModuleKeywordsUsesProvidesTransitiveAreReserved() {
        assertEquals("uses_", Names.toJavaFieldName("uses"));
        assertEquals("provides_", Names.toJavaFieldName("provides"));
        assertEquals("transitive_", Names.toJavaFieldName("transitive"));
        assertEquals("normal", Names.toJavaFieldName("normal"));
    }

    @Test
    void l18UnwrapTrimsWhitespaceInsideCollection() {
        assertEquals("Edm.String", Names.unwrapCollectionType("Collection( Edm.String )"));
        assertEquals("NS.Thing", Names.unwrapCollectionType("Collection(NS.Thing)"));
    }

    @Test
    void l18MalformedCollectionTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Names.unwrapCollectionType("Collection(Edm.String"),
                "missing ')' previously truncated the element type to 'Edm.Strin'");
    }

    @Test
    void l18NestedCollectionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Names.unwrapCollectionType("Collection(Collection(Edm.String))"));
    }

    @Test
    void l21RuntimeQueryClassNamesAreShadowed() {
        assertEquals("StringProperty_", Names.entityClassName("StringProperty"));
        assertEquals("NavProperty", Names.complexTypeClassName("NavProperty"));
        // NavProperty was removed from the runtime (lambda-query API): the class no longer
        // exists, so an entity named NavProperty is legal again; NavQuery/Expandable joined the list
        assertEquals("NavQuery_", Names.complexTypeClassName("NavQuery"));
        assertEquals("Expandable_", Names.complexTypeClassName("Expandable"));
        assertEquals("EnumProperty_", Names.enumClassName("EnumProperty"));
        assertEquals("Person", Names.entityClassName("Person"), "normal names unchanged");
    }
}
