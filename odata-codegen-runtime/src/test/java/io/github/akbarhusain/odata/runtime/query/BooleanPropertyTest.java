package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BooleanPropertyTest {

    private final BooleanProperty<Object> active = new BooleanProperty<>("Active", Object.class);

    @Test
    void equalToTrue() {
        FilterExpression<Object> expr = active.equalTo(true);
        assertEquals("Active eq true", expr.toODataExpression());
    }

    @Test
    void equalToBooleanTrue() {
        FilterExpression<Object> expr = active.equalTo(Boolean.TRUE);
        assertEquals("Active eq true", expr.toODataExpression());
    }

    @Test
    void equalToNullRoutesToIsNull() {
        FilterExpression<Object> expr = active.equalTo((Boolean) null);
        assertEquals("Active eq null", expr.toODataExpression());
    }

    @Test
    void notEqualToBooleanFalse() {
        FilterExpression<Object> expr = active.notEqualTo(Boolean.FALSE);
        assertEquals("Active ne false", expr.toODataExpression());
    }

    @Test
    void notEqualToNullRoutesToIsNotNull() {
        FilterExpression<Object> expr = active.notEqualTo((Boolean) null);
        assertEquals("Active ne null", expr.toODataExpression());
    }

    @Test
    void isTrue() {
        FilterExpression<Object> expr = active.isTrue();
        assertEquals("Active eq true", expr.toODataExpression());
    }

    @Test
    void isFalse() {
        FilterExpression<Object> expr = active.isFalse();
        assertEquals("Active eq false", expr.toODataExpression());
    }
}
