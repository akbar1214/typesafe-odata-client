package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnumPropertyTest {

    enum PersonGender { Male, Female }

    @Test
    void equalToContainsEqOperator() {
        EnumProperty<Object, PersonGender> prop =
                new EnumProperty<>("Gender", Object.class, PersonGender.class, "Test.PersonGender");
        String expr = prop.equalTo(PersonGender.Male).toODataExpression();
        assertEquals("Gender eq Test.PersonGender'Male'", expr);
    }

    @Test
    void notEqualToContainsNeOperator() {
        EnumProperty<Object, PersonGender> prop =
                new EnumProperty<>("Gender", Object.class, PersonGender.class, "Test.PersonGender");
        String expr = prop.notEqualTo(PersonGender.Female).toODataExpression();
        assertEquals("Gender ne Test.PersonGender'Female'", expr);
    }

    @Test
    void usesCsdTypeNameForEnumLiterals() {
        EnumProperty<Object, PersonGender> prop = new EnumProperty<>("Gender", Object.class, PersonGender.class, "TripPin.PersonGender");
        String expr = prop.equalTo(PersonGender.Male).toODataExpression();
        assertEquals("Gender eq TripPin.PersonGender'Male'", expr);
    }

    @Test
    void m10MissingTypeNameThrowsInsteadOfEmittingInvalidLiteral() {
        // M10: the simple-name fallback produced `PersonGender'Male'` — rejected by
        // strict services (needs the fully qualified `NS.PersonGender'Male'`). Fail
        // fast with a clear message instead.
        EnumProperty<Object, PersonGender> prop = new EnumProperty<>("Gender", Object.class, PersonGender.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> prop.equalTo(PersonGender.Male));
        assertTrue(ex.getMessage().contains("fully qualified"),
                "error must explain how to fix it: " + ex.getMessage());
    }

    @Test
    void m10HasOperatorRendersFlagsMembership() {
        EnumProperty<Object, PersonGender> prop =
                new EnumProperty<>("Gender", Object.class, PersonGender.class, "Test.PersonGender");
        assertEquals("Gender has Test.PersonGender'Male'",
                prop.has(PersonGender.Male).toODataExpression(),
                "flags enums need the 'has' membership operator");
    }

    @Test
    void equalToNullRoutesToIsNull() {
        EnumProperty<Object, PersonGender> prop = new EnumProperty<>("Gender", Object.class, PersonGender.class);
        String expr = prop.equalTo(null).toODataExpression();
        assertEquals("Gender eq null", expr);
    }

    @Test
    void notEqualToNullRoutesToIsNotNull() {
        EnumProperty<Object, PersonGender> prop = new EnumProperty<>("Gender", Object.class, PersonGender.class);
        String expr = prop.notEqualTo(null).toODataExpression();
        assertEquals("Gender ne null", expr);
    }

    // ---- M5: sanitized members render their CSDL wire name ----

    enum WireColor implements io.github.akbarhusain.odata.runtime.entity.ODataEnumValue {
        A_B("A-B");

        private final String wire;
        WireColor(String wire) { this.wire = wire; }

        @Override
        public String wireName() { return wire; }
    }

    @Test
    void sanitizedEnumLiteralUsesCsdLWireName() {
        EnumProperty<Object, WireColor> prop =
                new EnumProperty<>("Color", Object.class, WireColor.class, "Test.Color");
        assertEquals("Color eq Test.Color'A-B'", prop.equalTo(WireColor.A_B).toODataExpression(),
                "the literal must carry the CSDL member name, not the sanitized Java name");
    }

    @Test
    void plainEnumLiteralKeepsJavaName() {
        EnumProperty<Object, PersonGender> prop =
                new EnumProperty<>("Gender", Object.class, PersonGender.class, "Test.PersonGender");
        assertEquals("Gender eq Test.PersonGender'Male'", prop.equalTo(PersonGender.Male).toODataExpression());
    }
}
