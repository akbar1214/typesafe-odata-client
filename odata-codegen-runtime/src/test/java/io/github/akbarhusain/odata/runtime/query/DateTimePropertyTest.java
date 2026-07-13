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
}
