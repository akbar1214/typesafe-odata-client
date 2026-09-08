package io.github.akbarhusain.odata.core.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CSDL §4.1: Edm.Byte is an UNSIGNED 8-bit integer (0..255); Edm.SByte is the signed one.
 * Mapping Edm.Byte to Java's signed {@code Byte} made every value above 127 fail to
 * deserialize ("Numeric value (200) out of range of Java byte").
 */
class NamesEdmByteTest {

    @Test
    void edmByteIsUnsignedAndNeedsAWiderJavaType() {
        assertEquals("Short", Names.edmTypeToSimpleJavaType("Edm.Byte"));
    }

    @Test
    void edmSByteStaysSigned() {
        assertEquals("Byte", Names.edmTypeToSimpleJavaType("Edm.SByte"));
    }
}
