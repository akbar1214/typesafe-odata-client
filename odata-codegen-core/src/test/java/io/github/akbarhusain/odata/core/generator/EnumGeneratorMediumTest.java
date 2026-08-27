package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel.EnumMemberModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.EnumTypeModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5: Enum member sanitization collision A-B -> A_B collides with verbatim A_B
 */
class EnumGeneratorMediumTest {

    @Test
    void m5_enumMemberCollisionDeduped() {
        EnumTypeModel enumType = new EnumTypeModel("MyEnum", "Edm.Int32", false, List.of(
                new EnumMemberModel("A-B", 0),
                new EnumMemberModel("A_B", 1),
                new EnumMemberModel("AB", 2)
        ));
        EnumGenerator gen = new EnumGenerator("com.test");
        String code = gen.generate(enumType);
        // M5: currently generates duplicate A_B(0), A_B(1) -> duplicate constant compile error
        // Expected: A_B, A_B_2, AB (or similar deduped)
        // Check no duplicate constant names
        assertTrue(code.contains("A_B(0L, \"A-B\")"), "should contain A_B with its wire name: " + code);
        // The second A_B should be deduped to A_B_2 or similar, not duplicate A_B(1L)
        long countAB = code.lines().filter(l -> l.trim().startsWith("A_B(")).count();
        assertEquals(1, countAB, "M5: should not have duplicate A_B constants, generated:\n" + code);
        assertTrue(code.contains("A_B_2") || code.contains("A_B2") || code.contains("A_B_3"),
                "M5: collided member should be deduped with suffix, got:\n" + code);
        // Also AB should still be present as distinct
        assertTrue(code.contains("AB(2L, \"AB\")"), "AB should remain: " + code);
    }

    @Test
    void m5_singleDashSanitized() {
        EnumTypeModel enumType = new EnumTypeModel("E", "Edm.Int32", false, List.of(
                new EnumMemberModel("A-B", 0),
                new EnumMemberModel("C", 1)
        ));
        EnumGenerator gen = new EnumGenerator("com.test");
        String code = gen.generate(enumType);
        assertTrue(code.contains("A_B(0L, \"A-B\")"), "A-B -> A_B with wire name: " + code);
        assertFalse(code.contains("AB(0L"), "should not be AB: " + code);
    }
}
