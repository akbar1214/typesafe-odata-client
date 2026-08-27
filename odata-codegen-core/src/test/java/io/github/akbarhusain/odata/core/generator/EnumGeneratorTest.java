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

        assertTrue(code.contains("CLASS(1L, \"class\")"),
                "member 'class' must be sanitized to a valid enum constant (CLASS), "
                        + "keeping the CSDL name as its wire name");
        assertTrue(code.contains("_2FA(2L, \"2FA\")"),
                "member '2FA' must not produce an invalid identifier");
        assertTrue(code.contains("goodMember(3L, \"goodMember\")"),
                "valid identifiers must be kept verbatim to preserve existing generated API");
        assertFalse(code.contains("class(1L"),
                "raw member name 'class' must not appear as an enum constant");
    }

    @Test
    void enumsCarryWireNamesForUrlLiterals() throws Exception {
        // M5: sanitized members must render their CSDL wire name in URL literals
        // (function parameters, key predicates, filters) — the constants therefore
        // record the member name and expose it via the runtime ODataEnumValue interface
        CsdlModel.EnumTypeModel e = new CsdlModel.EnumTypeModel("Color", "Edm.Int32", false,
                java.util.List.of(new CsdlModel.EnumMemberModel("Red", 0L),
                        new CsdlModel.EnumMemberModel("A-B", 1L)));

        String code = new EnumGenerator("com.example.reserved").generate(e);

        assertTrue(code.contains("implements io.github.akbarhusain.odata.runtime.entity.ODataEnumValue"),
                "generated enums expose their wire name to the runtime literal formatters: " + code);
        assertTrue(code.contains("Red(0L, \"Red\")"));
        assertTrue(code.contains("A_B(1L, \"A-B\")"),
                "the wire name is the original CSDL member name, not the sanitized constant");
        assertTrue(code.contains("public String wireName()"));
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
