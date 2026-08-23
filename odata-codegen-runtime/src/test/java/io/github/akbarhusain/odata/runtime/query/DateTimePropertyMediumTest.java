package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M6: DateTimeProperty.formatTime nano no zero-pad
 */
class DateTimePropertyMediumTest {

    static class Dummy {}

    @Test
    void m6_nanoOnePad() {
        DateTimeProperty<Dummy> prop = new DateTimeProperty<>("Time", Dummy.class);
        // nano = 1 => should be 10:15:30.000000001 (9 digits), not 10:15:30.1
        String expr = prop.equalTo(LocalTime.of(10, 15, 30, 1)).toODataExpression();
        assertTrue(expr.contains("10:15:30.000000001"),
                "M6: nano=1 should be zero-padded to 9 digits, got: " + expr);
        assertFalse(expr.contains("10:15:30.1,") || expr.endsWith("10:15:30.1"),
                "M6: should not be .1 : " + expr);
    }

    @Test
    void m6_nanoMillionPad() {
        DateTimeProperty<Dummy> prop = new DateTimeProperty<>("Time", Dummy.class);
        // nano = 1_000_000 => 001000000 (millis = 1)
        String expr = prop.equalTo(LocalTime.of(10, 15, 30, 1_000_000)).toODataExpression();
        assertTrue(expr.contains("10:15:30.001"),
                "M6: nano 1_000_000 should be .001..., got: " + expr);
        // Should be zero padded, not .1000000
        assertFalse(expr.contains("10:15:30.1000000"),
                "M6: 1_000_000 should not be .1000000, got: " + expr);
    }

    @Test
    void m6_noNanosNoDot() {
        DateTimeProperty<Dummy> prop = new DateTimeProperty<>("Time", Dummy.class);
        String expr = prop.equalTo(LocalTime.of(10, 15, 30, 0)).toODataExpression();
        assertTrue(expr.contains("10:15:30"), "should contain time: " + expr);
        assertFalse(expr.contains("10:15:30."), "no nano should not have dot: " + expr);
    }

    @Test
    void m6_maxNano() {
        DateTimeProperty<Dummy> prop = new DateTimeProperty<>("Time", Dummy.class);
        String expr = prop.equalTo(LocalTime.of(10, 15, 30, 999_999_999)).toODataExpression();
        assertTrue(expr.contains("10:15:30.999999999"), "max nano: " + expr);
    }
}
