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
 * Two entities with the SAME simple name in different schemas (split-merge metadata),
 * cross-schema inheritance, and per-schema output packages. The subtype cast constants
 * (decision 18a) reference subtypes by simple name — ambiguous imports and self-name
 * collisions must not be emitted; inherited/copy code must not touch root lifecycle
 * fields across package boundaries. Compile-or-it-didn't-happen (lesson 120).
 */
class CrossSchemaSimpleNameCompilationTest {

    @Test
    void sameSimpleNameAcrossSchemasWithCastConstantsCompiles(@TempDir Path tmp) throws Exception {
        CsdlModel.EntityTypeModel base = new CsdlModel.EntityTypeModel("Base", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of());
        // One.A — extends Base and carries a nav targeting Base: its cast constants see
        // BOTH One.A (self) and Two.A (foreign simple-name collision)
        CsdlModel.EntityTypeModel oneA = new CsdlModel.EntityTypeModel("A", "One.Base",
                false, false, false,
                List.of(),
                List.of(new CsdlModel.PropertyModel("Name", "Edm.String", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Children",
                        "Collection(One.Base)", null, false, false, List.of(), List.of())));
        // Two.A — same simple name, other schema/package, extends the cross-schema base,
        // with its own nav (navWith copy code lands in the foreign package) and an
        // open-type complex property (unmappedFields copy code)
        CsdlModel.EntityTypeModel twoA = new CsdlModel.EntityTypeModel("A", "One.Base",
                true, false, false,
                List.of(),
                List.of(new CsdlModel.PropertyModel("Title", "Edm.String", true, null, List.of()),
                        new CsdlModel.PropertyModel("Address", "Two.Addr", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Items",
                        "Collection(One.Base)", null, false, false, List.of(), List.of())));
        CsdlModel.ComplexTypeModel addr = new CsdlModel.ComplexTypeModel("Addr", null,
                true, false,
                List.of(new CsdlModel.PropertyModel("Street", "Edm.String", true, null, List.of())),
                List.of());
        // Holder navigates to BOTH same-named A's — nav imports must not collide
        CsdlModel.EntityTypeModel holder = new CsdlModel.EntityTypeModel("Holder", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("OneAs",
                        "Collection(One.A)", null, false, false, List.of(), List.of()),
                        new CsdlModel.NavigationPropertyModel("TwoAs",
                                "Collection(Two.A)", null, false, false, List.of(), List.of())));
        CsdlModel.ContainerModel container = new CsdlModel.ContainerModel("C",
                List.of(new CsdlModel.EntitySetModel("As1", "One.A", List.of(), List.of()),
                        new CsdlModel.EntitySetModel("As2", "Two.A", List.of(), List.of()),
                        new CsdlModel.EntitySetModel("Holders", "One.Holder", List.of(), List.of())),
                List.of(), List.of(), List.of());
        // Complex type navigating to BOTH same-named A's — complex-type nav imports and
        // Filterable collection-nav fields must resolve the contested name too
        CsdlModel.ComplexTypeModel info = new CsdlModel.ComplexTypeModel("Info", null,
                false, false,
                List.of(),
                List.of(new CsdlModel.NavigationPropertyModel("As1",
                        "Collection(One.A)", null, false, false, List.of(), List.of()),
                        new CsdlModel.NavigationPropertyModel("As2",
                                "Collection(Two.A)", null, false, false, List.of(), List.of())));
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(base, oneA, holder), List.of(info), List.of(), List.of(), List.of(), List.of(),
                List.of(container));
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(twoA), List.of(addr), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel model = new CsdlModel(List.of(one, two), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"))
                .withGenerateWithMethods(true)
                .generate(model);

        String oneASource = Files.readString(out.resolve("com/p1/entity/A.java"));
        // self-reference must not be imported; the foreign same-name subtype must be
        // referenced by its fully-qualified name, never imported
        assertFalse(oneASource.contains("import com.p1.entity.A;"),
                "self-import of the generated class is a compile error:\n" + oneASource);
        assertFalse(oneASource.contains("import com.p2.entity.A;"),
                "importing a foreign type with the same simple name as the generated class "
                        + "collides:\n" + oneASource);
        assertTrue(oneASource.contains("CHILDREN_AS_A_2 = CHILDREN.as(\"Two.A\", com.p2.entity.A.class, com.p2.entity.A.Selector::new)"),
                "foreign same-name subtype referenced by FQN:\n" + oneASource);

        // The generated entity in a DIFFERENT schema from its parent: with*/navWith copy
        // code in the foreign package touches the parent's protected lifecycle fields
        // (contextPath, etag, unmappedFields, changedFields) — legal per JLS 6.6.2
        // (subclass access via own-type references) and must compile
        String twoASource = Files.readString(out.resolve("com/p2/entity/A.java"));
        assertTrue(twoASource.contains("    public A withTitle(String value)"),
                "cross-package subtype gets with* copy code:\n" + twoASource);
        assertTrue(twoASource.contains("        e.contextPath = contextPath;\n")
                        && twoASource.contains("        e.etag = etag;\n"),
                "copy code references the parent's protected lifecycle fields:\n" + twoASource);
        assertTrue(twoASource.contains("EntityUtil.mergeChanged(changedFields"), twoASource);
        assertTrue(twoASource.contains("        e.items = value;"),
                "navWith copy code for the subtype's own nav:\n" + twoASource);

        // Holder navigates to both same-named A's: nav-target classes must not be
        // double-imported into ambiguous references
        String holderSource = Files.readString(out.resolve("com/p1/entity/Holder.java"));
        long aImports = holderSource.lines().filter(l -> l.startsWith("import ")
                && l.endsWith(".entity.A;")).count();
        assertTrue(aImports <= 1, "at most ONE import may claim the simple name A:\n"
                + holderSource);

        String containerSource = Files.readString(out.resolve("com/p1/container/C.java"));
        long aReqImports = containerSource.lines().filter(l -> l.startsWith("import ")
                && l.contains(".A")).count();
        assertTrue(aReqImports <= 2,
                "collection+entity request imports for A must not exceed one package each:\n"
                        + containerSource);

        // Complex types navigate to both same-named A's: nav imports AND Filterable
        // collection-nav fields must resolve the contested name (FQN), never ambiguous
        String infoSource = Files.readString(out.resolve("com/p1/complex/Info.java"));
        long infoAImports = infoSource.lines().filter(l -> l.startsWith("import ")
                && l.endsWith(".entity.A;")).count();
        assertTrue(infoAImports <= 1, "complex nav imports must not claim simple name A twice:\n"
                + infoSource);
        assertTrue(infoSource.contains("com.p2.entity.A"),
                "the contested Filterable/nav reference is fully qualified:\n" + infoSource);

        String errors = CompilationHarness.compileAll(out);
        assertNull(errors, "generated cross-schema same-name client must compile. Errors:\n"
                + errors);
    }
}
