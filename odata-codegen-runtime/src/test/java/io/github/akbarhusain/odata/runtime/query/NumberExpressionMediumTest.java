package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7: NumberExpression.divide div vs divby by value type
 * Dividing Edm.Double property by Integer 2 should be divby (floating), not div
 */
class NumberExpressionMediumTest {

    static class Dummy {}

    @Test
    @SuppressWarnings("unchecked")
    void m7_doublePropertyDivByIntegerShouldBeDivby() {
        // Simulate Edm.Double property: NumberProperty<Double> with Double type and Edm.Double
        NumberProperty<Dummy, Double> doubleProp = new NumberProperty<>("Price", Dummy.class, "Edm.Double");
        // Currently picks div because value Integer, but property is Double => should be divby
        // Use raw to bypass generic type check and pass Integer to Double property
        String expr = ((NumberProperty) doubleProp).divide(Integer.valueOf(2)).toODataExpression();
        assertTrue(expr.contains("divby"),
                "M7: Double property / Integer should be divby (floating), got: " + expr);
        assertFalse(expr.contains(" div "),
                "M7: should not be truncating div, got: " + expr);
    }

    @Test
    void m7_integerPropertyDivByIntegerShouldBeDiv() {
        NumberProperty<Dummy, Integer> intProp = new NumberProperty<>("Count", Dummy.class, "Edm.Int32");
        String expr = intProp.divide(2).toODataExpression();
        assertTrue(expr.contains(" div "),
                "M7: Integer property / Integer should be div, got: " + expr);
    }

    @Test
    @SuppressWarnings("unchecked")
    void m7_integerPropertyDivByDoubleShouldBeDivby() {
        NumberProperty<Dummy, Integer> intProp = new NumberProperty<>("Count", Dummy.class, "Edm.Int32");
        // Even int property divided by double value should be divby (via raw)
        String expr = ((NumberProperty) intProp).divide(Double.valueOf(2.5)).toODataExpression();
        assertTrue(expr.contains("divby"),
                "M7: Int property / Double value should be divby, got: " + expr);
    }

    @Test
    void m7_expressionDivByCheck() {
        NumberExpression<Integer, Dummy> expr = new NumberExpression<>("Count", Dummy.class);
        // Raw NumberExpression without property type - falls back to value check
        String div = expr.divide(2).toODataExpression();
        assertTrue(div.contains(" div "), "raw int divide should be div: " + div);
        // Use raw for double on Integer expression
        String divby = ((NumberExpression) expr).divide(Double.valueOf(2.5)).toODataExpression();
        assertTrue(divby.contains("divby"), "raw double divide should be divby: " + divby);
    }
}
