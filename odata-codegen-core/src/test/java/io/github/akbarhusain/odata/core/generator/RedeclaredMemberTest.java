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
 * RED (TDD, Batch A2): {@code allProps = inherited + own} keeps BOTH copies when
 * a derived type redeclares a base member — the collision check deliberately
 * tolerates exact same-name redeclaration, so duplicates flow into the
 * Filterable inner class, Builder (duplicate fields + methods) and with*().
 * Own declaration must win exactly once.
 */
class RedeclaredMemberTest {

    private CsdlModel redeclareModel() {
        CsdlModel.EntityTypeModel base = new CsdlModel.EntityTypeModel("Base", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Items",
                        "Collection(NS.Other)", null, false, false, List.of(), List.of())));
        CsdlModel.EntityTypeModel other = new CsdlModel.EntityTypeModel("Other", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        // Derived redeclares Label and the Items nav with COMPATIBLE shapes —
        // they merge own-wins (conflicting shapes fail loudly, see below)
        CsdlModel.EntityTypeModel derived = new CsdlModel.EntityTypeModel("Derived", "NS.Base",
                false, false, false,
                List.of(),
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Items",
                        "Collection(NS.Other)", null, false, false, List.of(), List.of())));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(base, other, derived), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        return new CsdlModel(List.of(schema), List.of());
    }

    @Test
    void redeclaredPropertyEmittedOnce(@TempDir Path tmp) throws Exception {
        CsdlModel model = redeclareModel();
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.EntityTypeModel derived = schema.entityTypes().get(2);

        String code = new EntityGenerator("com.example", Map.of(), null, List.of(schema), true)
                .generate(derived, schema);

        // Pre-fix the allocation sees both copies and needlessly suffixes the single
        // own-only constant (LABEL_2), while Filterable still duplicates it.
        // Post-fix (own-wins dedupe before allocation) the name is LABEL, once.
        assertFalse(code.contains("LABEL_2"),
                "no dedupe suffix may remain once the redeclared member is merged:\n" + code);
        assertFalse(code.contains("ITEMS_2"),
                "no dedupe suffix may remain for the redeclared nav:\n" + code);
        assertTrue(code.contains("public static final StringProperty<Derived> LABEL ="),
                "the single constant uses the compatible redeclaration:\n" + code);
        assertEquals(1, countOccurrences(code, "public Derived withLabel("),
                "redeclared property must produce exactly one with* method:\n" + code);
        assertEquals(1, countOccurrences(code, "public Derived withItems("),
                "redeclared nav must produce exactly one nav-with method:\n" + code);
    }

    @Test
    void conflictingPropertyRedeclarationFailsLoudly() {
        CsdlModel.EntityTypeModel base = new CsdlModel.EntityTypeModel("Base", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        // Narrowed type: the subclass getter cannot override the base getter, so the
        // output could never compile — fail naming the type and the member
        CsdlModel.EntityTypeModel derived = new CsdlModel.EntityTypeModel("Derived", "NS.Base",
                false, false, false,
                List.of(),
                List.of(new CsdlModel.PropertyModel("Label", "Edm.Int32", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(base, derived), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example", Map.of(), null, List.of(schema), true)
                        .generate(derived, schema),
                "conflicting redeclaration must fail loudly, not emit uncompilable code");
        assertTrue(ex.getMessage().contains("Derived") && ex.getMessage().contains("Label"),
                "the failure must name the type and the member: " + ex.getMessage());
    }

    @Test
    void midChainConflictFailsLoudly() {
        // Base1(Label: String) <- Base2(Label: Int32) <- Derived: the merged
        // inherited list hides the Base1/Base2 pair, so a leaf-only check never
        // fires. The chain must be checked pairwise at every level.
        CsdlModel.EntityTypeModel base1 = new CsdlModel.EntityTypeModel("Base1", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel base2 = new CsdlModel.EntityTypeModel("Base2", "NS.Base1",
                false, false, false,
                List.of(),
                List.of(new CsdlModel.PropertyModel("Label", "Edm.Int32", true, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel derived = new CsdlModel.EntityTypeModel("Derived", "NS.Base2",
                false, false, false,
                List.of(),
                List.of(),
                List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(base1, base2, derived), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example", Map.of(), null, List.of(schema), true)
                        .generate(derived, schema),
                "mid-chain conflicting redeclare must fail loudly, not merge silently");
        assertTrue(ex.getMessage().contains("Label")
                        && ex.getMessage().contains("Edm.String")
                        && ex.getMessage().contains("Edm.Int32"),
                "the failure must name the member and both types: " + ex.getMessage());
    }

    @Test
    void midChainConflictFailsLoudlyForComplexTypes() {
        CsdlModel.ComplexTypeModel base1 = new CsdlModel.ComplexTypeModel("Base1", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel base2 = new CsdlModel.ComplexTypeModel("Base2", "NS.Base1",
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.Int32", true, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel derived = new CsdlModel.ComplexTypeModel("Derived", "NS.Base2",
                false, false, List.of(), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(), List.of(base1, base2, derived), List.of(),
                List.of(), List.of(), List.of(), List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ComplexTypeGenerator("com.example", Map.of(), null, List.of(schema), true)
                        .generate(derived, schema),
                "mid-chain conflicting redeclare must fail loudly for complex types too");
        assertTrue(ex.getMessage().contains("Label"),
                "the failure must name the member: " + ex.getMessage());
    }

    @Test
    void conflictingNavRedeclarationFailsLoudly() {
        CsdlModel.EntityTypeModel other = new CsdlModel.EntityTypeModel("Other", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel base = new CsdlModel.EntityTypeModel("Base", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Items",
                        "Collection(NS.Other)", null, false, false, List.of(), List.of())));
        // Collection-ness changed: request methods cannot overload — fail loudly
        CsdlModel.EntityTypeModel derived = new CsdlModel.EntityTypeModel("Derived", "NS.Base",
                false, false, false,
                List.of(),
                List.of(),
                List.of(new CsdlModel.NavigationPropertyModel("Items",
                        "NS.Other", null, false, false, List.of(), List.of())));
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(base, other, derived), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new EntityGenerator("com.example", Map.of(), null, List.of(schema), true)
                        .generate(derived, schema),
                "conflicting nav redeclaration must fail loudly, not emit uncompilable code");
        assertTrue(ex.getMessage().contains("Derived") && ex.getMessage().contains("Items"),
                "the failure must name the type and the member: " + ex.getMessage());
    }

    @Test
    void redeclaredHierarchyCompiles(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of(), "com.example")
                .withGenerateWithMethods(true)
                .generate(redeclareModel());

        String errors = CompilationHarness.compileAll(out);
        assertNull(errors, "hierarchy with redeclared members must compile. Errors:\n" + errors);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }
}
