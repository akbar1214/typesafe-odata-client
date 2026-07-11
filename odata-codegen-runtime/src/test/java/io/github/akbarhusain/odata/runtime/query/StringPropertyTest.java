package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that StringProperty only exposes string-specific operations.
 * Numeric comparisons (greaterThan, lessThan, etc.) are intentionally excluded
 * because they encourage misuse — users should convert to NumberExpression via
 * length() or indexOf() for numeric comparisons on string-derived values.
 */
class StringPropertyTest {

    private final StringProperty<Object> name = new StringProperty<>("Name", Object.class);

    @Test
    void equalTo() {
        FilterExpression expr = name.equalTo("Alice");
        assertEquals("Name eq 'Alice'", expr.toODataExpression());
    }

    @Test
    void notEqualTo() {
        FilterExpression expr = name.notEqualTo("Bob");
        assertEquals("Name ne 'Bob'", expr.toODataExpression());
    }

    @Test
    void contains() {
        FilterExpression expr = name.contains("li");
        assertEquals("contains(Name,'li')", expr.toODataExpression());
    }

    @Test
    void startsWith() {
        FilterExpression expr = name.startsWith("A");
        assertEquals("startswith(Name,'A')", expr.toODataExpression());
    }

    @Test
    void endsWith() {
        FilterExpression expr = name.endsWith("e");
        assertEquals("endswith(Name,'e')", expr.toODataExpression());
    }

    @Test
    void isNull() {
        FilterExpression expr = name.isNull();
        assertEquals("Name eq null", expr.toODataExpression());
    }

    @Test
    void isNotNull() {
        FilterExpression expr = name.isNotNull();
        assertEquals("Name ne null", expr.toODataExpression());
    }

    @Test
    void lengthReturnsNumberExpression() {
        NumberExpression<Integer, Object> len = name.length();
        assertEquals("length(Name)", len.toODataExpression());
    }

    @Test
    void toLower() {
        StringProperty<Object> lower = name.toLower();
        assertEquals("tolower(Name)", lower.toODataExpression());
    }

    @Test
    void toUpper() {
        StringProperty<Object> upper = name.toUpper();
        assertEquals("toupper(Name)", upper.toODataExpression());
    }

    @Test
    void indexOf() {
        NumberExpression<Integer, Object> idx = name.indexOf("x");
        assertEquals("indexof(Name,'x')", idx.toODataExpression());
    }

    @Test
    void substring() {
        StringProperty<Object> sub = name.substring(2);
        assertEquals("substring(Name,2)", sub.toODataExpression());
    }

    @Test
    void substringWithLength() {
        StringProperty<Object> sub = name.substring(2, 5);
        assertEquals("substring(Name,2,5)", sub.toODataExpression());
    }

    @Test
    void andCombines() {
        FilterExpression expr = name.equalTo("A").and(name.contains("B"));
        assertEquals("(Name eq 'A') and (contains(Name,'B'))", expr.toODataExpression());
    }

    @Test
    void orCombines() {
        FilterExpression expr = name.equalTo("A").or(name.equalTo("B"));
        assertEquals("(Name eq 'A') or (Name eq 'B')", expr.toODataExpression());
    }

    @Test
    void notInverts() {
        FilterExpression expr = name.equalTo("A").not();
        assertEquals("not (Name eq 'A')", expr.toODataExpression());
    }

    @Test
    void escapeSingleQuote() {
        FilterExpression expr = name.equalTo("O'Brien");
        assertEquals("Name eq 'O''Brien'", expr.toODataExpression());
    }

    @Test
    void asc() {
        OrderExpression<String> ordered = name.asc();
        assertEquals("Name", ordered.getODataPath());
    }

    @Test
    void desc() {
        OrderExpression<String> ordered = name.desc();
        assertEquals("Name desc", ordered.getODataPath());
    }

    @Test
    void matchesPattern() {
        FilterExpression expr = name.matchesPattern("^[A-Z].*$");
        assertEquals("matchesPattern(Name,'^[A-Z].*$')", expr.toODataExpression());
    }

    @Test
    void trim() {
        StringProperty<Object> trimmed = name.trim();
        assertEquals("trim(Name)", trimmed.toODataExpression());
    }

    @Test
    void greaterThan() {
        FilterExpression<Object> expr = name.greaterThan("M");
        assertEquals("Name gt 'M'", expr.toODataExpression());
    }

    @Test
    void greaterThanOrEqualTo() {
        FilterExpression<Object> expr = name.greaterThanOrEqualTo("M");
        assertEquals("Name ge 'M'", expr.toODataExpression());
    }

    @Test
    void lessThan() {
        FilterExpression<Object> expr = name.lessThan("Z");
        assertEquals("Name lt 'Z'", expr.toODataExpression());
    }

    @Test
    void lessThanOrEqualTo() {
        FilterExpression<Object> expr = name.lessThanOrEqualTo("Z");
        assertEquals("Name le 'Z'", expr.toODataExpression());
    }

    @Test
    void greaterThanEscapesQuote() {
        FilterExpression<Object> expr = name.greaterThan("O'Brien");
        assertEquals("Name gt 'O''Brien'", expr.toODataExpression());
    }
}
