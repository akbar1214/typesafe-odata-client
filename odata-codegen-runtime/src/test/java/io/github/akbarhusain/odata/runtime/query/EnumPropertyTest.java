package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnumPropertyTest {

    enum PersonGender { Male, Female }

    @Test
    void equalToContainsEqOperator() {
        EnumProperty<Object, PersonGender> prop = new EnumProperty<>("Gender", Object.class, PersonGender.class);
        String expr = prop.equalTo(PersonGender.Male).toODataExpression();
        assertEquals("Gender eq PersonGender'Male'", expr);
    }

    @Test
    void notEqualToContainsNeOperator() {
        EnumProperty<Object, PersonGender> prop = new EnumProperty<>("Gender", Object.class, PersonGender.class);
        String expr = prop.notEqualTo(PersonGender.Female).toODataExpression();
        assertEquals("Gender ne PersonGender'Female'", expr);
    }

    @Test
    void usesCsdTypeNameForEnumLiterals() {
        EnumProperty<Object, PersonGender> prop = new EnumProperty<>("Gender", Object.class, PersonGender.class, "TripPin.PersonGender");
        String expr = prop.equalTo(PersonGender.Male).toODataExpression();
        assertEquals("Gender eq TripPin.PersonGender'Male'", expr);
    }

    @Test
    void backwardCompatibilityWithoutTypeName() {
        EnumProperty<Object, PersonGender> prop = new EnumProperty<>("Gender", Object.class, PersonGender.class);
        String expr = prop.equalTo(PersonGender.Male).toODataExpression();
        assertEquals("Gender eq PersonGender'Male'", expr);
    }
}
