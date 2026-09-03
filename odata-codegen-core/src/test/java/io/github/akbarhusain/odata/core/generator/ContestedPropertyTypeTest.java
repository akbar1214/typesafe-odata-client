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
 * RED (TDD): property-typed references ignore per-file TypeRefs resolution.
 * Two schemas declare complex type "Foo" mapped to different output packages.
 * An entity with properties of BOTH types must reference them fully-qualified
 * (contested simple name) and never double-import — otherwise the generated
 * file has ambiguous/unresolvable references and does not compile.
 */
class ContestedPropertyTypeTest {

    private CsdlModel contestedModel() {
        CsdlModel.ComplexTypeModel oneFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel twoFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Code", "Edm.Int32", true, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel holder = new CsdlModel.EntityTypeModel("Holder", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("First", "One.Foo", true, null, List.of()),
                        new CsdlModel.PropertyModel("Second", "Two.Foo", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(holder), List.of(oneFoo), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(), List.of(twoFoo), List.of(), List.of(), List.of(), List.of(), List.of());
        return new CsdlModel(List.of(one, two), List.of());
    }

    @Test
    void contestedPropertyTypesAreFullyQualified(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"))
                .generate(contestedModel());

        String holderSource = Files.readString(out.resolve("com/p1/entity/Holder.java"));

        // Both Foo types are contested: no plain "Foo" field type may remain —
        // every reference must be fully-qualified (contains a dot).
        assertTrue(holderSource.contains("com.p1.complex.Foo")
                        && holderSource.contains("com.p2.complex.Foo"),
                "contested property types must be fully-qualified:\n" + holderSource);

        long fooImports = holderSource.lines()
                .filter(l -> l.startsWith("import ") && l.endsWith(".complex.Foo;"))
                .count();
        assertTrue(fooImports <= 1,
                "at most ONE import may claim simple name Foo, contested refs are FQN:\n" + holderSource);
    }

    @Test
    void contestedCollectionPropertyTypesAreFullyQualified(@TempDir Path tmp) throws Exception {
        CsdlModel.ComplexTypeModel oneFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Label", "Edm.String", true, null, List.of())),
                List.of());
        CsdlModel.ComplexTypeModel twoFoo = new CsdlModel.ComplexTypeModel("Foo", null,
                false, false,
                List.of(new CsdlModel.PropertyModel("Code", "Edm.Int32", true, null, List.of())),
                List.of());
        CsdlModel.EntityTypeModel holder = new CsdlModel.EntityTypeModel("Holder", null,
                false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(
                        new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Tags", "Collection(One.Foo)", true, null, List.of()),
                        new CsdlModel.PropertyModel("Others", "Collection(Two.Foo)", true, null, List.of())),
                List.of());
        CsdlModel.SchemaModel one = new CsdlModel.SchemaModel("One", null,
                List.of(holder), List.of(oneFoo), List.of(), List.of(), List.of(), List.of(), List.of());
        CsdlModel.SchemaModel two = new CsdlModel.SchemaModel("Two", null,
                List.of(), List.of(twoFoo), List.of(), List.of(), List.of(), List.of(), List.of());

        Path out = tmp.resolve("out");
        new Generator(out, Map.of("One", "com.p1", "Two", "com.p2"))
                .generate(new CsdlModel(List.of(one, two), List.of()));

        String holderSource = Files.readString(out.resolve("com/p1/entity/Holder.java"));
        assertTrue(holderSource.contains("List<com.p1.complex.Foo>")
                        && holderSource.contains("List<com.p2.complex.Foo>"),
                "contested collection element types must be fully-qualified:\n" + holderSource);
    }
}
