package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
