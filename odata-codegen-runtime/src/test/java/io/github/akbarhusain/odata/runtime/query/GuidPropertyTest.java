package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H6: Edm.Guid filter literals must be the bare 8-4-4-4-12 value, unquoted —
 * `Id eq 0d67...` — matching what ContextPath already does for GUID keys.
 * The quoted string form (`Id eq '...'`) is a type error rejected by services.
 */
class GuidPropertyTest {

    private static final String GUID = "0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001";

    static class Entity {}

    private final GuidProperty<Entity> id = new GuidProperty<>("Id", Entity.class);

    @Test
    void equalToRendersUnquotedGuid() {
        assertEquals("Id eq " + GUID, id.equalTo(GUID).toODataExpression());
    }

    @Test
    void notEqualToRendersUnquotedGuid() {
        assertEquals("Id ne " + GUID, id.notEqualTo(GUID).toODataExpression());
    }

    @Test
    void nullValueRoutesToNullChecks() {
        assertEquals("Id eq null", id.equalTo(null).toODataExpression());
        assertEquals("Id ne null", id.notEqualTo(null).toODataExpression());
    }

    @Test
    void explicitNullChecksRender() {
        assertEquals("Id eq null", id.isNull().toODataExpression());
        assertEquals("Id ne null", id.isNotNull().toODataExpression());
    }

    @Test
    void invalidGuidLiteralThrows() {
        assertThrows(IllegalArgumentException.class, () -> id.equalTo("not-a-guid"));
        assertThrows(IllegalArgumentException.class, () -> id.equalTo(GUID + "' or '1' eq '1"));
    }

    @Test
    void orderByUsesEdmName() {
        assertEquals("Id", id.asc().getODataPath());
        assertEquals("Id desc", id.desc().getODataPath());
        assertEquals("Id", id.getEdmName());
    }

    @Test
    void andCompositionKeepsUnquotedLiterals() {
        FilterExpression<Entity> expr = id.equalTo(GUID).and(id.isNull());
        assertEquals("(Id eq " + GUID + ") and (Id eq null)", expr.toODataExpression());
    }
}
