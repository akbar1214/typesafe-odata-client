package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
