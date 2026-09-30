package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link ODataLiteral} against the OData ABNF Construction Rules v4.01 grammar rather
 * than against the previous implementation's behaviour. Every rule quoted below is from
 * that document.
 *
 * <p>The point of the class is that a wrong literal produces a server 400 that is very hard
 * to trace back to its cause, so each grammar production is asserted in both directions:
 * the forms the grammar admits must be emitted, and the forms it excludes must be rejected
 * before the request leaves the JVM.
 */
class ODataLiteralAbnfConformanceTest {

    /**
     * {@code durationValue = [ SIGN ] "P" [ 1*DIGIT "D" ] [ "T" [ 1*DIGIT "H" ] [ 1*DIGIT "M" ] [ 1*DIGIT [ "." 1*DIGIT ] "S" ] ]}
     *
     * <p>The trailing {@code [ … "S" ]} group is optional but the {@code "S"} inside it is
     * not, so a bare seconds component without the suffix has no valid reading.
     */
    @Test
    void durationSecondsRequireTheMandatorySuffix() {
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("duration'PT1'", "Edm.Duration"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("duration'PT1.5'", "Edm.Duration"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("duration'P1.5'", "Edm.Duration"));
        // Forms the grammar does admit.
        assertEquals("duration'PT1H30M'", ODataLiteral.format("duration'PT1H30M'", "Edm.Duration"));
        assertEquals("duration'PT1S'", ODataLiteral.format("duration'PT1S'", "Edm.Duration"));
        assertEquals("duration'P1D'", ODataLiteral.format("duration'P1D'", "Edm.Duration"));
    }

    /**
     * {@code decimalValue = [ SIGN ] 1*DIGIT [ "." 1*DIGIT ] [ "e" [ SIGN ] 1*DIGIT ] / nanInfinity}
     * and {@code doubleValue = decimalValue}, {@code singleValue = decimalValue}.
     *
     * <p>One production covers all three types, so one validator must too. Digits are
     * required on both sides of the decimal point and before it; the exponent is optional
     * but legal.
     */
    @Test
    void allFloatingTypesShareOneDecimalValueGrammar() {
        for (String edmType : new String[]{"Edm.Decimal", "Edm.Double", "Edm.Single"}) {
            assertEquals("1e3", ODataLiteral.format("1e3", edmType), edmType);
            assertEquals("1E+3", ODataLiteral.format("1E+3", edmType), edmType);
            assertEquals("1.25", ODataLiteral.format("1.25", edmType), edmType);
            assertEquals("1", ODataLiteral.format("1", edmType), edmType);
            assertThrows(IllegalArgumentException.class,
                    () -> ODataLiteral.format("1.", edmType), edmType);
            assertThrows(IllegalArgumentException.class,
                    () -> ODataLiteral.format(".5", edmType), edmType);
            assertThrows(IllegalArgumentException.class,
                    () -> ODataLiteral.format("1.2.3", edmType), edmType);
        }
    }

    /**
     * {@code nanInfinity = 'NaN' / '-INF' / 'INF'}.
     *
     * <p>{@code Edm.Decimal} has no infinity or NaN representation, so they must be
     * rejected there while the floating types emit the bare keyword.
     */
    @Test
    void nanInfinityAppliesOnlyToTheFloatingTypes() {
        assertEquals("INF", ODataLiteral.format(Double.POSITIVE_INFINITY, "Edm.Double"));
        assertEquals("-INF", ODataLiteral.format(Double.NEGATIVE_INFINITY, "Edm.Double"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format(Double.POSITIVE_INFINITY, "Edm.Decimal"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("INF", "Edm.Decimal"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("NaN", "Edm.Decimal"));
    }

    /**
     * {@code year = [ "-" ] ( "0" 3DIGIT / oneToNine 3*DIGIT )} — exactly four digits, with
     * an optional leading minus. There is no 5-or-more-digit year and no leading plus.
     *
     * <p>Both the {@code String} path (which validates) and the {@code java.time} path
     * (which renders a type's own {@code toString}) must agree: the same out-of-range value
     * cannot be accepted in one form and rejected in the other.
     */
    @Test
    void yearIsExactlyFourDigitsOnBothTheStringAndTypedPaths() {
        assertEquals("9999-01-01", ODataLiteral.format(LocalDate.of(9999, 1, 1), "Edm.Date"));
        assertEquals("0001-01-01", ODataLiteral.format(LocalDate.of(1, 1, 1), "Edm.Date"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("12345-01-01", "Edm.Date"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format(LocalDate.of(12345, 1, 1), "Edm.Date"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format(OffsetDateTime.parse("+12345-01-01T00:00:00Z"), "Edm.DateTimeOffset"));
    }

    /**
     * {@code second = zeroToFiftyNine / "60"} — 60 is a legal leap second and must not be
     * rejected as an out-of-range minute-derived value.
     */
    @Test
    void leapSecondIsAccepted() {
        assertEquals("23:59:60", ODataLiteral.format("23:59:60", "Edm.TimeOfDay"));
        assertEquals("2024-06-30T23:59:60Z",
                ODataLiteral.format("2024-06-30T23:59:60Z", "Edm.DateTimeOffset"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("23:59:61", "Edm.TimeOfDay"));
    }

    /**
     * {@code lineStringData = OPEN positionLiteral 1*( COMMA positionLiteral ) CLOSE} — the
     * {@code 1*} requires at least one additional position, so a single-position
     * LineString is not a line at all.
     */
    @Test
    void lineStringNeedsAtLeastTwoPositions() {
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("SRID=4326;LineString(1 2)", "Edm.GeographyLineString"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("SRID=4326;LineString()", "Edm.GeographyLineString"));
        assertEquals("geography'SRID=4326;LineString(1 2,3 4)'",
                ODataLiteral.format("SRID=4326;LineString(1 2,3 4)", "Edm.GeographyLineString"));
    }

    /**
     * {@code pointData = OPEN positionLiteral CLOSE} with
     * {@code positionLiteral = doubleValue SP doubleValue [ SP doubleValue ] [ SP doubleValue ]} —
     * two to four ordinates.
     */
    @Test
    void pointTakesTwoToFourOrdinates() {
        assertEquals("geography'SRID=4326;Point(1 2)'",
                ODataLiteral.format("SRID=4326;Point(1 2)", "Edm.GeographyPoint"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("SRID=4326;Point(1)", "Edm.GeographyPoint"));
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("SRID=4326;Point(1 2 3 4 5)", "Edm.GeographyPoint"));
    }

    /**
     * {@code dateTimeOffsetValue = year "-" month "-" day "T" timeOfDayValue ( "Z" / SIGN hour ":" minute )}
     * — the offset is hour:minute only. A Java {@code OffsetDateTime} may carry a
     * seconds-precision offset; rendering it must normalize the INSTANT (to UTC) rather
     * than truncating the offset, which would silently shift the value.
     */
    @Test
    void dateTimeOffsetWithSecondsPrecisionOffsetIsNormalizedToUtc() {
        OffsetDateTime east = OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0,
                java.time.ZoneOffset.ofHoursMinutesSeconds(0, 53, 28));
        assertEquals("2020-01-02T02:10:37Z", ODataLiteral.format(east, "Edm.DateTimeOffset"),
                "truncating +00:53:28 to +00:53 would move the instant by 28 seconds");
        OffsetDateTime west = OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0,
                java.time.ZoneOffset.ofHoursMinutesSeconds(0, -53, -28));
        assertEquals("2020-01-02T03:57:33Z", ODataLiteral.format(west, "Edm.DateTimeOffset"));
    }

    /**
     * {@code durationValue = [ SIGN ] "P" …} with {@code SIGN = "+" / "%2B" / "-"} in BOTH
     * the v4.0 and v4.01 ABNF — a leading {@code "+"} is legal and must stay accepted.
     * (A review round quoted {@code [ "-" ]} and proposed rejecting it; that quote does
     * not exist in either published ABNF, so this test pins the real production.)
     */
    @Test
    void plusSignedDurationsAreLegal() {
        assertEquals("duration'+P1D'", ODataLiteral.format("+P1D", "Edm.Duration"));
        assertEquals("duration'-P1D'", ODataLiteral.format("-P1D", "Edm.Duration"));
        assertEquals("duration'P1D'", ODataLiteral.format("P1D", "Edm.Duration"));
    }

    /**
     * {@code decimalValue = [ SIGN ] 1*DIGIT [ "." 1*DIGIT ] … / nanInfinity} and
     * {@code positionLiteral = doubleValue …}; WKT ordinates are {@code decimalValue}, so
     * digits are required on both sides of the decimal point, and {@code nanInfinity}
     * admits {@code NaN} / {@code -INF} / {@code INF} but never {@code +INF}.
     */
    @Test
    void geoOrdinatesUseTheDecimalValueGrammar() {
        for (String illegal : new String[]{"5. 3", ".5 3", "+INF 3", "+NaN 3"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ODataLiteral.format("SRID=4326;Point(" + illegal + ")", "Edm.GeographyPoint"),
                    illegal);
        }
        for (String legal : new String[]{"5 3", "5.0 3", "-5.25 3", "1e2 3", "INF 3", "-INF 3", "NaN 3"}) {
            assertEquals("geography'SRID=4326;Point(" + legal + ")'",
                    ODataLiteral.format("SRID=4326;Point(" + legal + ")", "Edm.GeographyPoint"), legal);
        }
    }

    /**
     * Ring closure compares positions numerically, so an infinite ordinate (legal via
     * {@code nanInfinity}) must not be fed to {@code BigDecimal} — that threw a raw
     * NumberFormatException instead of validating the ring.
     */
    @Test
    void closedRingsWithInfiniteOrdinatesCompareAsDoubles() {
        assertEquals("geography'SRID=4326;Polygon((INF 1,2 2,1 1,INF 1))'",
                ODataLiteral.format("SRID=4326;Polygon((INF 1,2 2,1 1,INF 1))", "Edm.GeographyPolygon"));
        assertEquals("geography'SRID=4326;Polygon((NaN 1,2 2,1 1,NaN 1))'",
                ODataLiteral.format("SRID=4326;Polygon((NaN 1,2 2,1 1,NaN 1))", "Edm.GeographyPolygon"));
    }

    /**
     * {@code sridLiteral = "SRID" EQ 1*5DIGIT SEMI} — required for every geography literal,
     * optional for geometry.
     */
    @Test
    void sridIsRequiredForGeographyAndOptionalForGeometry() {
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format("Point(1 2)", "Edm.GeographyPoint"));
        assertEquals("geometry'Point(1 2)'",
                ODataLiteral.format("Point(1 2)", "Edm.GeometryPoint"));
    }

    /**
     * An unrecognised Edm type must never reach a raw {@code String.valueOf}. Every other
     * branch of the renderer either validates its input or quotes and escapes it; the
     * fallback did neither, so an arbitrary object's {@code toString()} was emitted bare
     * into a filter, key predicate or function parameter.
     */
    @Test
    void unknownEdmTypeNeverEmitsAnUnescapedToString() {
        Object hostile = new Object() {
            @Override
            public String toString() {
                return "1 or 1=1";
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> ODataLiteral.format(hostile, "Ns.Whatever"));
        assertThrows(IllegalArgumentException.class,
                () -> ContextPath.formatTypedValue(hostile, "Ns.Whatever"));
        // Strings and enums still have their documented renderings.
        assertEquals("'x'", ODataLiteral.format("x", "Ns.Whatever"));
    }

    /**
     * {@code string = SQUOTE *( SQUOTE-in-string / pchar-no-SQUOTE ) SQUOTE} — a quote inside a
     * literal is doubled, so no value may terminate the literal early.
     */
    @Test
    void stringLiteralsDoubleEmbeddedQuotes() {
        assertEquals("'O''Brien'", ODataLiteral.format("O'Brien", "Edm.String"));
        assertEquals("''''", ODataLiteral.format("'", "Edm.String"));
    }
}
