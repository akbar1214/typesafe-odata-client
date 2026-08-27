package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Operation (function) parameter encoding: values are embedded in the invocation-path
 * fragment {@code Name(p1=v1,p2=v2)} using OData literal syntax, type-driven exactly
 * like key predicates (decision 52 / lesson 52). String values reuse the same escaping
 * rules as keys — inner quotes doubled, {@code & ? # % / + space} percent-encoded.
 */
class OperationPathTest {

    @Test
    void segmentWithNoParametersRendersEmptyParentheses() {
        assertEquals("ResetDataSource()", OperationPath.segment("ResetDataSource"));
    }

    @Test
    void segmentJoinsPairsWithCommasNoSpaces() {
        String s = OperationPath.segment("GetNearestAirport",
                "lat=47.61357", "lon=-122.19375");
        assertEquals("GetNearestAirport(lat=47.61357,lon=-122.19375)", s);
    }

    @Test
    void stringParameterIsQuotedWithInnerQuotesDoubled() {
        assertEquals("'O''Brien'", OperationPath.parameter("O'Brien", "Edm.String"));
    }

    @Test
    void stringParameterEncodesSpecialCharacters() {
        // same escaping contract as key predicates (ContextPath.encodeKeyValue):
        // & ? # % / + → percent-encoded, space → %20
        assertEquals("'a%26b%3Fc%23d%25e%2Ff%2Bg%20h'",
                OperationPath.parameter("a&b?c#d%e/f+g h", "Edm.String"));
    }

    @Test
    void numericParametersRenderBare() {
        assertEquals("47.6", OperationPath.parameter(47.6, "Edm.Double"));
        assertEquals("-122", OperationPath.parameter(-122, "Edm.Int32"));
        assertEquals("9223372036854775807", OperationPath.parameter(Long.MAX_VALUE, "Edm.Int64"));
        // plain integer form is a valid OData decimal literal
        assertEquals("10000000000000000000",
                OperationPath.parameter(new BigDecimal("10000000000000000000"), "Edm.Decimal"));
        assertTrue(OperationPath.parameter(true, "Edm.Boolean").equals("true"));
    }

    @Test
    void guidAndDateFamiliesRenderBareIso() {
        UUID guid = UUID.fromString("0c5a8d5a-6ab4-4e32-a4c7-53b0bd123456");
        assertEquals(guid.toString(), OperationPath.parameter(guid, "Edm.Guid"));
        assertEquals("2026-08-27", OperationPath.parameter(LocalDate.of(2026, 8, 27), "Edm.Date"));
        assertEquals("2026-08-27T10:15:30Z",
                OperationPath.parameter(OffsetDateTime.parse("2026-08-27T10:15:30Z"), "Edm.DateTimeOffset"));
    }

    @Test
    void timeOfDayAlwaysHasSeconds() {
        // LocalTime.toString drops zero seconds — invalid OData TimeOfDay literal (lesson 102)
        LocalTime t = LocalTime.of(10, 15, 0);
        assertEquals("10:15:00", OperationPath.parameter(t, "Edm.TimeOfDay"));
    }

    @Test
    void durationRendersPrefixedLiteral() {
        assertEquals("duration'PT1H30M'", OperationPath.parameter(java.time.Duration.ofMinutes(90), "Edm.Duration"));
    }

    @Test
    void nullParameterValueThrowsNamingTheProblem() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OperationPath.parameter(null, "Edm.Double"));
        assertTrue(ex.getMessage().toLowerCase().contains("null"));
    }

    // ---- Enum literals use the CSDL wire name, not the sanitized Java name (M5) ----

    /** A generated-style enum whose sanitized Java name differs from its CSDL name. */
    enum Renamed implements ODataEnumValue {
        A_B("A-B");

        private final String wire;
        Renamed(String wire) { this.wire = wire; }

        @Override
        public String wireName() { return wire; }
    }

    enum Plain { Male }

    @Test
    void enumParameterLiteralUsesCsdLWireName() {
        assertEquals("NS.E'A-B'", OperationPath.parameter(Renamed.A_B, "NS.E"),
                "sanitized members must render their CSDL wire name");
    }

    @Test
    void plainEnumLiteralStillUsesJavaName() {
        // enums compiled before the interface (or hand-written) keep the name() fallback
        assertEquals("NS.P'Male'", OperationPath.parameter(Plain.Male, "NS.P"));
    }

    // ---- Collection parameters ride parameter aliases: @p=['a','b'] ----

    @Test
    void collectionParameterRendersBracketedCommaJoinedLiterals() {
        assertEquals("['a','b']", OperationPath.collectionParameter(java.util.List.of("a", "b"), "Edm.String"));
        assertEquals("[1,2,3]", OperationPath.collectionParameter(java.util.List.of(1, 2, 3), "Edm.Int32"));
        assertEquals("[NS.P'Male']", OperationPath.collectionParameter(java.util.List.of(Plain.Male), "NS.P"),
                "enum elements format by the same rules as scalar parameters");
    }

    @Test
    void collectionParameterEmptyCollectionRendersEmptyArrayLiteral() {
        assertEquals("[]", OperationPath.collectionParameter(java.util.List.of(), "Edm.String"),
                "empty list is a meaningful value — distinct from omitting a nullable parameter");
    }

    @Test
    void collectionParameterNullListRejectedLikeScalarNull() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationPath.collectionParameter(null, "Edm.String"));
        assertThrows(IllegalArgumentException.class,
                () -> OperationPath.collectionParameter(java.util.Arrays.asList("a", null), "Edm.String"),
                "null ELEMENTS are also invalid literals");
    }
}
