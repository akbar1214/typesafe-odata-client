package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateTimePropertyTest {

    private final DateTimeProperty<Object> created = new DateTimeProperty<>("Created", Object.class);

    @Test
    void equalTo() {
        FilterExpression<Object> expr = created.equalTo("2024-01-01T00:00:00Z");
        assertEquals("Created eq 2024-01-01T00:00:00Z", expr.toODataExpression());
    }

    @Test
    void equalToNullRoutesToIsNull() {
        FilterExpression<Object> expr = created.equalTo(null);
        assertEquals("Created eq null", expr.toODataExpression());
    }

    @Test
    void notEqualToNullRoutesToIsNotNull() {
        FilterExpression<Object> expr = created.notEqualTo(null);
        assertEquals("Created ne null", expr.toODataExpression());
    }

    @Test
    void year() {
        NumberExpression<Integer, Object> expr = created.year();
        assertEquals("year(Created)", expr.toODataExpression());
    }

    @Test
    void month() {
        NumberExpression<Integer, Object> expr = created.month();
        assertEquals("month(Created)", expr.toODataExpression());
    }

    @Test
    void day() {
        NumberExpression<Integer, Object> expr = created.day();
        assertEquals("day(Created)", expr.toODataExpression());
    }

    @Test
    void hour() {
        NumberExpression<Integer, Object> expr = created.hour();
        assertEquals("hour(Created)", expr.toODataExpression());
    }

    @Test
    void minute() {
        NumberExpression<Integer, Object> expr = created.minute();
        assertEquals("minute(Created)", expr.toODataExpression());
    }

    @Test
    void second() {
        NumberExpression<Integer, Object> expr = created.second();
        assertEquals("second(Created)", expr.toODataExpression());
    }

    @Test
    void date() {
        DateTimeProperty<Object> expr = created.date();
        assertEquals("date(Created)", expr.toODataExpression());
    }

    @Test
    void time() {
        DateTimeProperty<Object> expr = created.time();
        assertEquals("time(Created)", expr.toODataExpression());
    }

    @Test
    void m9InvalidStringLiteralThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> created.greaterThan("2024-01-01' or '1' eq '1"),
                "unvalidated raw strings allow $filter predicate injection");
        assertThrows(IllegalArgumentException.class,
                () -> created.equalTo("2024-01-01 10:00"),
                "a space-separated datetime is not an OData literal");
        assertThrows(IllegalArgumentException.class,
                () -> created.lessThan("P1D"),
                "bare ISO durations are not valid unquoted OData duration literals");
    }

    @Test
    void m9ValidLiteralFormsAccepted() {
        assertEquals("Created eq 2024-01-01", created.equalTo("2024-01-01").toODataExpression());
        assertEquals("Created ge 2024-01-01T10:00Z",
                created.greaterThanOrEqualTo("2024-01-01T10:00Z").toODataExpression());
        assertEquals("Created gt 10:15:30", created.greaterThan("10:15:30").toODataExpression());
        assertEquals("Created lt duration'PT2H'", created.lessThan("duration'PT2H'").toODataExpression());
    }

    @Test
    void m9TypedOverloadsFormatPerAbnf() {
        assertEquals("Created eq 2024-01-01",
                created.equalTo(java.time.LocalDate.of(2024, 1, 1)).toODataExpression());
        assertEquals("Created gt 2024-01-01T10:15:30Z",
                created.greaterThan(java.time.OffsetDateTime.parse("2024-01-01T10:15:30Z")).toODataExpression());
        assertEquals("Created eq 10:15:30",
                created.equalTo(java.time.LocalTime.of(10, 15, 30)).toODataExpression());
        assertEquals("Created eq 10:15:00",
                created.equalTo(java.time.LocalTime.of(10, 15)).toODataExpression(),
                "LocalTime with zero seconds must still render HH:mm:ss (toString omits them)");
        assertEquals("Created le duration'PT2H'",
                created.lessThanOrEqualTo(java.time.Duration.ofHours(2)).toODataExpression());
    }
}
