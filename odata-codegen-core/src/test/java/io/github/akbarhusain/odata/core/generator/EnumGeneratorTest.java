package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M6: generated enums must sanitize hostile member names and support flag enums.
 */
class EnumGeneratorTest {

    @Test
    void hostileMemberNamesAreSanitized() throws Exception {
        CsdlModel.EnumTypeModel enumType = new CsdlModel.EnumTypeModel("BadEnum", "Edm.Int32", false,
                java.util.List.of(
                        new CsdlModel.EnumMemberModel("class", 1L),
                        new CsdlModel.EnumMemberModel("2FA", 2L),
                        new CsdlModel.EnumMemberModel("goodMember", 3L)));

        String code = new EnumGenerator("com.example.reserved").generate(enumType);

        assertTrue(code.contains("CLASS(1L)"),
                "member 'class' must be sanitized to a valid enum constant (CLASS)");
        assertTrue(code.contains("_2FA(2L)"),
                "member '2FA' must not produce an invalid identifier");
        assertTrue(code.contains("goodMember(3L)"),
                "valid identifiers must be kept verbatim to preserve existing generated API");
        assertFalse(code.contains("class(1L)"),
                "raw member name 'class' must not appear as an enum constant");
    }

    @Test
    void flagsEnumGetsFromFlagsMethod() throws Exception {
        CsdlModel.EnumTypeModel flags = new CsdlModel.EnumTypeModel("FlagEnum", "Edm.Int32", true,
                java.util.List.of(
                        new CsdlModel.EnumMemberModel("Read", 1L),
                        new CsdlModel.EnumMemberModel("Write", 2L),
                        new CsdlModel.EnumMemberModel("Execute", 4L)));

        String code = new EnumGenerator("com.example.reserved").generate(flags);

        assertTrue(code.contains("public static java.util.Set<FlagEnum> fromFlags(long value)"),
                "IsFlags enums should expose fromFlags() returning the set of set members");
        assertTrue(code.contains("(value & v.value) == v.value"),
                "fromFlags must select members whose bits are fully set");
    }

    @Test
    void nonFlagsEnumHasNoFromFlagsMethod() throws Exception {
        CsdlModel.EnumTypeModel plain = new CsdlModel.EnumTypeModel("PlainEnum", "Edm.Int32", false,
                java.util.List.of(new CsdlModel.EnumMemberModel("A", 1L)));

        String code = new EnumGenerator("com.example.reserved").generate(plain);

        assertFalse(code.contains("fromFlags"),
                "non-flags enums should not generate fromFlags()");
    }
}
