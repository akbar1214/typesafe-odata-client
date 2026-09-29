package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedStateDefensiveCopyTest {

    @Test
    void entityBuilderAndWithMethodsSnapshotChangedCollections(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel target = entity("Target", List.of(), List.of());
        CsdlModel.EntityTypeModel entity = entity("Holder", List.of(
                new CsdlModel.PropertyModel("Tags", "Collection(Edm.String)", true, null, List.of())),
                List.of(new CsdlModel.NavigationPropertyModel("Links", "Collection(NS.Target)", null,
                        false, true, List.of(), List.of())));
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").withGenerateWithMethods(true)
                .generate(model(entity, target));
        String code = Files.readString(out.resolve("com/example/entity/Holder.java"));
        assertTrue(code.contains("this.tags = value == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(value));"), code);
        assertTrue(code.contains("e.tags = this.tags == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(this.tags));"), code);
        assertTrue(code.contains("this.links = value == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(value));"), code);
        assertTrue(code.contains("e.links = this.links == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(this.links));"), code);
        assertTrue(code.contains("return Set.copyOf(changedFields);"), code);
        assertNull(CompilationHarness.compileAll(out));
    }

    @Test
    void generatedBuilderDoesNotExposeMutableCallerState(@TempDir Path tempDir) throws Exception {
        CsdlModel.EntityTypeModel target = entity("Target", List.of(), List.of());
        CsdlModel.EntityTypeModel entity = entity("Holder", List.of(
                new CsdlModel.PropertyModel("Tags", "Collection(Edm.String)", true, null, List.of())), List.of());
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").withGenerateWithMethods(true)
                .generate(model(entity, target));
        assertNull(CompilationHarness.compileAll(out));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{out.resolve(".classes").toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> holderClass = Class.forName("com.example.entity.Holder", true, loader);
            Object builder = holderClass.getMethod("builder").invoke(null);
            List<String> tags = new java.util.ArrayList<>(List.of("one"));
            builder.getClass().getMethod("tags", List.class).invoke(builder, tags);
            builder.getClass().getMethod("id", Integer.class).invoke(builder, 1);
            Object value = builder.getClass().getMethod("build").invoke(builder);
            tags.add("two");
            assertEquals(List.of("one"), value.getClass().getMethod("getTags").invoke(value));
            @SuppressWarnings("unchecked")
            java.util.Set<String> changed = (java.util.Set<String>) value.getClass()
                    .getMethod("getChangedFields").invoke(value);
            assertThrows(UnsupportedOperationException.class, () -> changed.add("other"));
        }
    }

    @Test
    void complexBuilderSnapshotsChangedCollections(@TempDir Path tempDir) throws Exception {
        CsdlModel.ComplexTypeModel complex = new CsdlModel.ComplexTypeModel("Holder", null, false, false,
                List.of(new CsdlModel.PropertyModel("Tags", "Collection(Edm.String)", true, null, List.of())), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null, List.of(), List.of(complex), List.of(),
                List.of(), List.of(), List.of(), List.of());
        Path out = tempDir.resolve("out");
        new Generator(out, Map.of(), "com.example").withGenerateWithMethods(true)
                .generate(new CsdlModel(List.of(schema), List.of()));
        String code = Files.readString(out.resolve("com/example/complex/Holder.java"));
        assertTrue(code.contains("this.tags = value == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(value));"), code);
        assertTrue(code.contains("e.tags = this.tags == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(this.tags));"), code);
        assertTrue(code.contains("e.tags = value == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(value));"), code);
        assertNull(CompilationHarness.compileAll(out));
    }

    private static CsdlModel.EntityTypeModel entity(String name, List<CsdlModel.PropertyModel> properties,
                                                       List<CsdlModel.NavigationPropertyModel> navs) {
        return new CsdlModel.EntityTypeModel(name, null, false, false, false,
                List.of(new CsdlModel.KeyModel(List.of("Id"))),
                concat(properties, new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of())), navs);
    }

    private static List<CsdlModel.PropertyModel> concat(List<CsdlModel.PropertyModel> properties,
                                                         CsdlModel.PropertyModel id) {
        java.util.ArrayList<CsdlModel.PropertyModel> result = new java.util.ArrayList<>(properties);
        result.add(id);
        return result;
    }

    private static CsdlModel model(CsdlModel.EntityTypeModel... entities) {
        return new CsdlModel(List.of(new CsdlModel.SchemaModel("NS", null, List.of(entities), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of())), List.of());
    }
}
