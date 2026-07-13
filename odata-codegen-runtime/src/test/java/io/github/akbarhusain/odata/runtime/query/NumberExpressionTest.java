package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-1: OData V4 does NOT allow d/f suffixes on numeric literals (OData V2 only).
 * All Decimal/Double/Float literals must be emitted as plain digits.
 */
class NumberExpressionTest {

    private final NumberExpression<Double, Object> price = new NumberExpression<>("Price", Object.class);
    private final NumberExpression<Float, Object> score = new NumberExpression<>("Score", Object.class);
    private final NumberExpression<Integer, Object> count = new NumberExpression<>("Count", Object.class);
    private final NumberExpression<Long, Object> id = new NumberExpression<>("Id", Object.class);

    @Test
    void doubleGreaterThanOmitsSuffix() {
        FilterExpression<Object> expr = price.greaterThan(1.5);
        assertEquals("Price gt 1.5", expr.toODataExpression(),
                "Double literal must NOT have 'd' suffix in OData V4");
    }

    @Test
    void doubleEqualToOmitsSuffix() {
        FilterExpression<Object> expr = price.equalTo(9.99);
        assertEquals("Price eq 9.99", expr.toODataExpression(),
                "Double literal must NOT have 'd' suffix in OData V4");
    }

    @Test
    void floatGreaterThanOmitsSuffix() {
        FilterExpression<Object> expr = score.greaterThan(3.5f);
        assertEquals("Score gt 3.5", expr.toODataExpression(),
                "Float literal must NOT have 'f' suffix in OData V4");
    }

    @Test
    void floatEqualToOmitsSuffix() {
        FilterExpression<Object> expr = score.equalTo(1.0f);
        assertEquals("Score eq 1.0", expr.toODataExpression(),
                "Float literal must NOT have 'f' suffix in OData V4");
    }

    @Test
    void integerLiteralUnchanged() {
        FilterExpression<Object> expr = count.greaterThan(5);
        assertEquals("Count gt 5", expr.toODataExpression());
    }

    @Test
    void longLiteralUnchanged() {
        FilterExpression<Object> expr = id.greaterThan(100L);
        assertEquals("Id gt 100", expr.toODataExpression());
    }

    @Test
    void doubleArithmeticOmitsSuffix() {
        NumberExpression<Double, Object> result = price.multiply(2.0);
        assertEquals("(Price mul 2.0)", result.toODataExpression(),
                "Double literal in arithmetic must NOT have 'd' suffix");
    }

    @Test
    void nullComparisonUnchanged() {
        FilterExpression<Object> expr = price.isNull();
        assertEquals("Price eq null", expr.toODataExpression());
    }

    @Test
    void equalToNullRoutesToIsNull() {
        FilterExpression<Object> expr = price.equalTo(null);
        assertEquals("Price eq null", expr.toODataExpression());
    }

    @Test
    void notEqualToNullRoutesToIsNotNull() {
        FilterExpression<Object> expr = price.notEqualTo(null);
        assertEquals("Price ne null", expr.toODataExpression());
    }

    @Test
    void negate() {
        NumberExpression<Double, Object> expr = price.negate();
        assertEquals("(-Price)", expr.toODataExpression());
    }
}
