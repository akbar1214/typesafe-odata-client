package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OData ABNF v4.01: {@code functionParameters = OPEN [ functionParameter *(
 * COMMA functionParameter ) ] CLOSE} and {@code functionParameter = parameterName EQ
 * ( parameterAlias / primitiveLiteral )}.
 *
 * <p>{@link OperationPath#segment} validated the parameter NAME — rejecting the characters
 * that would break the URL structure — and then copied the whole {@code name=value} pair
 * into the output verbatim. A value is not a free-form string: neither {@code )} nor the
 * bare word {@code or} is part of any {@code primitiveLiteral} production, so a value
 * carrying them produced a request target that is not in the OData grammar at all. For a
 * mutating action that is worse than a 400 — the service sees a truncated argument list.
 */
class OperationPathParameterTest {

    @Test
    void parameterValuesMayNotBreakOutOfTheArgumentList() {
        // ")" would close the parameter list early, leaving trailing text outside it.
        assertThrows(IllegalArgumentException.class,
                () -> OperationPath.segment("Reset", "x=1) or (1 eq 1"));
        assertThrows(IllegalArgumentException.class,
                () -> OperationPath.segment("Reset", "x=1)"));
        assertThrows(IllegalArgumentException.class,
                () -> OperationPath.segment("F", "x='a')%20or%20('a'='a"));
    }

    @Test
    void parameterValuesMayNotInjectAdditionalQueryStructure() {
        // A second "=" or a "&" starts a new name=value / query option.
        assertThrows(IllegalArgumentException.class,
                () -> OperationPath.segment("Reset", "x=1&$filter=1 eq 1"));
        assertThrows(IllegalArgumentException.class,
                () -> OperationPath.segment("Reset", "x=1?y=2"));
    }

    @Test
    void wellFormedPairsAreStillAccepted() {
        assertEquals("GetNearestAirport(lat=1,lon=2)",
                OperationPath.segment("GetNearestAirport", "lat=1", "lon=2"));
        assertEquals("Search(name='O''Brien')",
                OperationPath.segment("Search", "name='O''Brien'"));
        assertEquals("Reset(x=@p0)", OperationPath.segment("Reset", "x=@p0"));
        assertEquals("Find()", OperationPath.segment("Find"));
    }

    /**
     * pchar includes the sub-delims, so {@code ( ) , ; = *} are legal INSIDE a quoted
     * string. The generated layer builds every value with {@code ODataLiteral.parameter},
     * so these are what a function taking a free-text or geography parameter actually
     * emits. A flat character blacklist over the value rejects all of them, which breaks
     * the invocation for legitimate input.
     */
    @Test
    void pcharCharactersInsideAQuotedLiteralAreLegal() {
        // The renderer percent-encodes the spaces; that is encodePathValue's contract and
        // is not what this test is about. What matters is that the comma and parentheses
        // survive instead of being rejected.
        String value = OperationPath.parameter("Doe, John (Jr.)", "Edm.String");
        assertTrue(value.contains(",") && value.contains("(Jr.)"), value);
        assertEquals("Search(name=" + value + ")",
                OperationPath.segment("Search", "name=" + value));
        assertEquals("F(p='a=b')",
                OperationPath.segment("F", "p=" + OperationPath.parameter("a=b", "Edm.String")));
        assertEquals("F(p='a;b*c')",
                OperationPath.segment("F", "p=" + OperationPath.parameter("a;b*c", "Edm.String")));
    }

    /**
     * A geography literal is a quoted string wrapping a WKT body, so its {@code = ( ) ;}
     * are all inside the quotes and must survive too.
     */
    @Test
    void geographyLiteralKeepsItsSridAndParentheses() {
        String value = OperationPath.parameter("SRID=4326;Point(1 2)", "Edm.GeographyPoint");
        assertEquals("F(p=" + value + ")", OperationPath.segment("F", "p=" + value));
        assertTrue(value.startsWith("geography'SRID=4326;Point(1 2)'"), value);
    }

    /** An unterminated literal is a caller bug, not something to send. */
    @Test
    void unterminatedStringLiteralIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationPath.segment("Search", "name='unterminated"));
    }

    /**
     * The pair is a convenience for the generated layer, which always composes it as
     * {@code name + "=" + value}. Splitting on the first "=" and validating the VALUE
     * separately is what closes the breakout; a pair with no value at all is still a
     * caller error.
     */
    @Test
    void pairsMustCarryAValue() {
        assertThrows(IllegalArgumentException.class, () -> OperationPath.segment("Reset", "x="));
        assertThrows(IllegalArgumentException.class, () -> OperationPath.segment("Reset", "=1"));
        assertThrows(IllegalArgumentException.class, () -> OperationPath.segment("Reset", "x"));
    }
}
