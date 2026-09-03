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
 * SchemaInfo registry correctness:
 * 1. The same QUALIFIED name declared by more than one kind (entity Foo + complex Foo +
 *    enum Foo in one namespace — tolerated by the lenient parser) must FAIL generation
 *    loudly: the registry would emit three classes.put("NS.Foo", ...) with one key and
 *    the HashMap silently keeps the last put, misrouting polymorphic deserialization.
 * 2. The LEGAL contested case — same SIMPLE name from different schemas mapped to one
 *    output package (NS1.Foo entity + NS2.Foo complex) — must resolve references
 *    fully-qualified, never double-import, and the whole output must COMPILE
 *    (lesson 120: content assertions stay green while the compile breaks).
 */
class SchemaInfoCrossKindTest {

    @Test
    void sameQualifiedKeyAcrossKindsFailsLoudly(@TempDir Path tmp) {
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

        Path out = tmp.resolve("out");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new Generator(out, Map.of(), "com.example")
                        .generate(new CsdlModel(List.of(schema), List.of())),
                "three classes.put(\"NS.Foo\", ...) would silently keep the last — fail loudly instead");
        assertTrue(ex.getMessage().contains("NS.Foo"),
                "the ambiguous qualified name must be named: " + ex.getMessage());
    }

    @Test
    void crossSchemaSameSimpleNameResolvesAndCompiles(@TempDir Path tmp) throws Exception {
        CsdlModel.EntityTypeModel entityFoo = new CsdlModel.EntityTypeModel("Foo", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel complexFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel ns1 = new CsdlModel.SchemaModel("NS1", null,
                List.of(entityFoo), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel ns2 = new CsdlModel.SchemaModel("NS2", null,
                List.of(), List.of(complexFoo), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        // BOTH schemas map to the SAME base package — the realistic contested case
        new Generator(out, Map.of("NS1", "com.example", "NS2", "com.example"), "com.example")
                .generate(new CsdlModel(List.of(ns1, ns2), List.of()));

        String code = Files.readString(out.resolve("com/example/schema/SchemaInfo.java"));
        long fooImports = code.lines()
                .filter(l -> l.startsWith("import ") && l.endsWith(".Foo;"))
                .count();
        assertEquals(0, fooImports,
                "contested simple name Foo must be referenced fully-qualified, never imported:\n" + code);
        assertTrue(code.contains("com.example.entity.Foo.class")
                        && code.contains("com.example.complex.Foo.class"),
                "contested registry references must be fully-qualified:\n" + code);

        String errors = CompilationHarness.compileAll(out);
        assertNull(errors, "legal cross-schema same-name registry must compile. Errors:\n" + errors);
    }
}
