package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ODataLiteralTest {

    @Test
    void integerLiteralsAreRangeCheckedByEdmType() {
        assertEquals("255", ODataLiteral.format(255, "Edm.Byte"));
        assertEquals("-128", ODataLiteral.format(-128, "Edm.SByte"));
        assertEquals("32767", ODataLiteral.format(32767, "Edm.Int16"));
        assertThrows(IllegalArgumentException.class, () -> ODataLiteral.format(256, "Edm.Byte"));
        assertThrows(IllegalArgumentException.class, () -> ODataLiteral.format(128, "Edm.SByte"));
        assertThrows(IllegalArgumentException.class, () -> ODataLiteral.format(32768, "Edm.Int16"));
        assertThrows(IllegalArgumentException.class, () -> ODataLiteral.format("256", "Edm.Byte"));
    }

    @Test
    void nonUtcOffsetIsPreservedVerbatim() {
        OffsetDateTime value = OffsetDateTime.parse("2024-01-01T10:15:00+05:30");
        assertEquals("2024-01-01T10:15:00+05:30", ODataLiteral.format(value, "Edm.DateTimeOffset"));
    }

    @Test
    void negativeDurationsRenderWithLeadingMinus() {
        assertEquals("duration'-PT1H'", ODataLiteral.format(Duration.ofHours(-1), "Edm.Duration"));
    }

    @Test
    void polygonRingsMustBeClosed() {
        assertEquals("geography'SRID=4326;Polygon((0 0,0 1,1 1,0 0))'",
                ODataLiteral.format("SRID=4326;Polygon((0 0,0 1,1 1,0 0))", "Edm.GeographyPolygon"));
        assertThrows(IllegalArgumentException.class, () -> ODataLiteral.format(
                "SRID=4326;Polygon((0 0,0 1,1 1,1 0))", "Edm.GeographyPolygon"));
    }

    @Test
    void multiPolygonWrapsClosedRings() {
        assertEquals("geography'SRID=4326;MultiPolygon(((0 0,0 1,1 1,0 0)))'",
                ODataLiteral.format("SRID=4326;MultiPolygon(((0 0,0 1,1 1,0 0)))", "Edm.GeographyMultiPolygon"));
        assertThrows(IllegalArgumentException.class, () -> ODataLiteral.format(
                "SRID=4326;MultiPolygon(((0 0,0 1,1 1)))", "Edm.GeographyMultiPolygon"));
    }
}
