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

    @Test
    void untypedStringsAreQuotedByDefault() {
        // Date/time-shaped strings must not be silently treated as temporal literals.
        assertEquals("'2024-01-01'", ODataLiteral.format("2024-01-01", null));
        assertEquals("'10:15'", ODataLiteral.format("10:15", null));
        assertEquals("0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001",
                ODataLiteral.format("0c5a0f6d-f3e8-4e11-9e4c-7d2a9a61b001", null));
        // Explicit temporal intent still renders unquoted.
        assertEquals("duration'PT1H'", ODataLiteral.formatTemporal(Duration.ofHours(1)));
    }

    @Test
    void ringClosureComparesCoordinatesNumerically() {
        assertEquals("geography'SRID=4326;Polygon((0 0,0 1,1 1,0.0 0.0))'",
                ODataLiteral.format("SRID=4326;Polygon((0 0,0 1,1 1,0.0 0.0))", "Edm.GeographyPolygon"));
        assertEquals("geography'SRID=4326;Polygon((0 0,0 1,1 1,0  0))'",
                ODataLiteral.format("SRID=4326;Polygon((0 0,0 1,1 1,0  0))", "Edm.GeographyPolygon"));
    }

    @Test
    void positionsMayCarryZAndMOrdinates() {
        assertEquals("geography'SRID=4326;Point(1 2 3)'",
                ODataLiteral.format("SRID=4326;Point(1 2 3)", "Edm.GeographyPoint"));
        assertThrows(IllegalArgumentException.class, () -> ODataLiteral.format(
                "SRID=4326;Point(1 2 3 4 5)", "Edm.GeographyPoint"));
    }
}
