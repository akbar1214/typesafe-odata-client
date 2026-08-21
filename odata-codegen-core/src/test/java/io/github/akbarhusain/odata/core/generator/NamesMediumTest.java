package io.github.akbarhusain.odata.core.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3: sanitizeClassName drops '-' -> collision A-B vs AB
 */
class NamesMediumTest {

    @Test
    void m3_sanitizeClassNameDashMapsToUnderscore() {
        // Names.entityClassName delegates to sanitizeClassName
        String abDash = Names.entityClassName("A-B");
        String ab = Names.entityClassName("AB");
        // M3: A-B currently drops '-' -> "AB", colliding with AB
        assertEquals("A_B", abDash, "M3: A-B should sanitize to A_B, not AB (currently drops '-')");
        assertNotEquals(abDash, ab, "M3: A-B and AB should not collide");
    }

    @Test
    void m3_complexTypeDashAlso() {
        String abDash = Names.complexTypeClassName("A-B");
        assertEquals("A_B", abDash, "M3: complexType A-B -> A_B");
    }

    @Test
    void m3_keepsValidNames() {
        assertEquals("Foo", Names.entityClassName("Foo"));
        assertEquals("FooBar", Names.entityClassName("FooBar"));
    }
}
