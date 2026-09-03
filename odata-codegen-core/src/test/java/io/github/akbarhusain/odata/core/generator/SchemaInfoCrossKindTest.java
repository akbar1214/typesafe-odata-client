package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD): SchemaInfoGenerator imports entity Foo + complex Foo + enum Foo
 * (same simple name, different kinds) into one file — every unqualified
 * Foo.class is ambiguous and the generated registry does not compile.
 * Contested names must be referenced fully-qualified and never imported.
 */
class SchemaInfoCrossKindTest {

    private CsdlModel crossKindModel() {
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("Foo", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel complex = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.EnumTypeModel en = new CsdlModel.EnumTypeModel("Foo", "Edm.Int32", false,
                List.of(new CsdlModel.EnumMemberModel("Alpha", 0)));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(entity), List.of(complex), List.of(en),
                List.of(), List.of(), List.of(), List.of());
        return new CsdlModel(List.of(schema), List.of());
    }

    @Test
    void crossKindSameNameDoesNotDoubleImport(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.example").generate(crossKindModel());

        String code = Files.readString(out.resolve("com/example/schema/SchemaInfo.java"));

        long fooImports = code.lines()
                .filter(l -> l.startsWith("import ") && l.endsWith(".Foo;"))
                .count();
        assertTrue(fooImports <= 1,
                "at most ONE import may claim simple name Foo:\n" + code);

        // At least one contested reference must be fully-qualified (contains .entity./.complex./.enums.)
        assertTrue(code.contains("com.example.entity.Foo.class")
                        || code.contains("com.example.complex.Foo.class")
                        || code.contains("com.example.enums.Foo.class"),
                "contested SchemaInfo references must be fully-qualified:\n" + code);
    }
}
