package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.github.akbarhusain.odata.runtime.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedLiteralConformanceTest {

    @Test
    void guidKeyRejectsPathAndQueryInjection() {
        assertThrows(IllegalArgumentException.class, () -> new ContextPath("https://svc")
                .addSegment("Things").addKey("Id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/../Admin", "Edm.Guid"));
        assertThrows(IllegalArgumentException.class, () -> OperationPath.parameter(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa?x=1", "Edm.Guid"));
    }

    @Test
    void guidKeyAndOperationAcceptUuidShape() {
        UUID guid = UUID.fromString("0c5a8d5a-6ab4-4e32-a4c7-53b0bd123456");
        assertEquals(guid.toString(), ContextPath.formatTypedValue(guid, "Edm.Guid"));
        assertEquals(guid.toString(), OperationPath.parameter(guid.toString(), "Edm.Guid"));
    }

    @Test
    void typedTemporalLiteralsUseODataSyntax() {
        assertEquals("2024-02-29", ContextPath.formatTypedValue(LocalDate.of(2024, 2, 29), "Edm.Date"));
        assertEquals("2024-01-01T10:15Z", ContextPath.formatTypedValue(
                "2024-01-01T10:15Z", "Edm.DateTimeOffset"));
        assertEquals("10:15:00", ContextPath.formatTypedValue(LocalTime.of(10, 15), "Edm.TimeOfDay"));
        assertEquals("10:15:30.001000000", ContextPath.formatTypedValue(
                LocalTime.of(10, 15, 30, 1_000_000), "Edm.TimeOfDay"));
        assertEquals("duration'PT1H30M'", ContextPath.formatTypedValue(
                Duration.ofMinutes(90), "Edm.Duration"));
    }

    @Test
    void temporalLiteralsRejectInvalidRangesAndDurations() {
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue("2023-02-29", "Edm.Date"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue("2024-13-01", "Edm.Date"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue("24:00", "Edm.TimeOfDay"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue("duration'P'", "Edm.Duration"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue("duration'P1X'", "Edm.Duration"));
    }

    @Test
    void binaryAndGeographyLiteralsUseODataForms() {
        assertEquals("binary'AQI'", ContextPath.formatTypedValue(new byte[]{1, 2}, "Edm.Binary"));
        assertEquals("binary'AQI='", ContextPath.formatTypedValue("AQI=", "Edm.Binary"));
        assertEquals("geography'SRID=4326;Point(1 2)'", ContextPath.formatTypedValue(
                "SRID=4326;Point(1 2)", "Edm.GeographyPoint"));
        assertEquals("geometry'Point(1 2)'", ContextPath.formatTypedValue(
                "geometry'Point(1 2)'", "Edm.GeometryPoint"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue(
                "SRID=4326;Point(1 2)?x=1", "Edm.GeographyPoint"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue(
                "Point(1 2 3)", "Edm.GeographyPoint"));
    }

    @Test
    void geographicCollectionAndMultiLineLiteralsValidate() {
        assertEquals("geography'SRID=4326;LineString(1 2,3 4)'", ContextPath.formatTypedValue(
                "SRID=4326;LineString(1 2,3 4)", "Edm.GeographyLineString"));
        assertEquals("geography'SRID=4326;MultiLineString((1 2,3 4),(5 6,7 8))'", ContextPath.formatTypedValue(
                "SRID=4326;MultiLineString((1 2,3 4),(5 6,7 8))", "Edm.GeographyMultiLineString"));
        assertEquals("geography'SRID=4326;Collection(Point(1 2),LineString(3 4,5 6))'", ContextPath.formatTypedValue(
                "SRID=4326;Collection(Point(1 2),LineString(3 4,5 6))", "Edm.GeographyCollection"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue(
                "SRID=4326;Collection(Point(1 2)", "Edm.GeographyCollection"));
    }

    @Test
    void geographicLiteralsRequireSridButGeometryMayOmitIt() {
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue(
                "Point(1 2)", "Edm.GeographyPoint"));
        assertEquals("geometry'Point(1 2)'", ContextPath.formatTypedValue(
                "Point(1 2)", "Edm.GeometryPoint"));
    }

    @Test
    void geographicSpacesAreEncodedBeforeHttpRequest() {
        String operation = "Find(geography'SRID=4326;Point(1 2)')";
        String url = new ContextPath("https://example.test")
                .addSegment(operation)
                .toUrl();
        assertTrue(url.contains("Point(1%202)"), url);
        assertDoesNotThrow(() -> HttpRequest.builder().url(url).build());

        String keyUrl = new ContextPath("https://example.test")
                .addSegment("Things")
                .addKey("Location", "SRID=4326;Point(1 2)", "Edm.GeographyPoint")
                .toUrl();
        assertTrue(keyUrl.contains("Point(1%202)"), keyUrl);
        assertDoesNotThrow(() -> HttpRequest.builder().url(keyUrl).build());
    }

    /**
     * OData ABNF v4.01: {@code decimalValue = [ SIGN ] 1*DIGIT [ "." 1*DIGIT ]
     * [ "e" [ SIGN ] 1*DIGIT ] / nanInfinity}. The exponent is LEGAL for Edm.Decimal
     * (it was previously rejected, which sent a spec-conformant value out as a server 400)
     * while {@code nanInfinity} is not — Edm.Decimal has no infinity or NaN form.
     */
    @Test
    void decimalLiteralsAcceptExponentButRejectNanInfinityAndMalformedSyntax() {
        for (String value : List.of("1e3", "1E+3")) {
            assertEquals(value, ContextPath.formatTypedValue(value, "Edm.Decimal"), value);
        }
        for (String value : List.of("NaN", "INF", "-INF", "1.", ".5", "+", "1.2.3")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ContextPath.formatTypedValue(value, "Edm.Decimal"), value);
        }
        assertThrows(IllegalArgumentException.class,
                () -> ContextPath.formatTypedValue(Double.NaN, "Edm.Decimal"));
        assertThrows(IllegalArgumentException.class,
                () -> ContextPath.formatTypedValue(Double.POSITIVE_INFINITY, "Edm.Decimal"));
    }

    @Test
    void decimalLiteralsAcceptPlainFiniteValues() {
        assertEquals("1.25", ContextPath.formatTypedValue("1.25", "Edm.Decimal"));
        assertEquals("1000", ContextPath.formatTypedValue(new BigDecimal("1E+3"), "Edm.Decimal"));
        assertEquals("0.00001", ContextPath.formatTypedValue(new BigDecimal("1E-5"), "Edm.Decimal"));
    }

    @Test
    void singleLiteralKeepsFloatPrecision() {
        assertEquals("1.2", ContextPath.formatTypedValue(1.2f, "Edm.Single"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue("60:00:00", "Edm.TimeOfDay"));
    }

    @Test
    void numericInfinityUsesODataSpellingOnlyForFloatingTypes() {
        assertEquals("INF", ContextPath.formatTypedValue(Double.POSITIVE_INFINITY, "Edm.Double"));
        assertEquals("-INF", ContextPath.formatTypedValue(Float.NEGATIVE_INFINITY, "Edm.Single"));
        assertThrows(IllegalArgumentException.class, () -> ContextPath.formatTypedValue(
                Double.POSITIVE_INFINITY, "Edm.Int32"));
    }

    @Test
    void stringKeyPathEncodesUriUnsafeCharactersButRetainsODataQuoteDoubling() {
        String url = new ContextPath("https://svc").addSegment("People")
                .addKey("Name", "O'Brien[value]", "Edm.String").toUrl();
        assertEquals("https://svc/People('O''Brien%5Bvalue%5D')", url);
        assertDoesNotThrow(() -> java.net.URI.create(url));
    }

    @Test
    void localeDoesNotChangeTemporalLiteralDigits() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"));
            assertEquals("10:15:30.001000000", ContextPath.formatTypedValue(
                    LocalTime.of(10, 15, 30, 1_000_000), "Edm.TimeOfDay"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
