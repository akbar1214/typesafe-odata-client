package io.github.akbarhusain.odata.core.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTypeSerializationRegressionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void entityDefaultAndPartialSerializationRetainPresentNullDynamicProperties(@TempDir Path tempDir) throws Exception {
        Path output = generate(tempDir);
        assertNull(CompilationHarness.compileAll(output));

        try (URLClassLoader loader = loader(output)) {
            Class<?> entityClass = Class.forName("com.example.entity.OpenEntity", true, loader);
            JacksonSerializer serializer = new JacksonSerializer();

            Object full = entityClass.getConstructor().newInstance();
            full = entityClass.getMethod("withName", String.class).invoke(full, "updated");
            full = entityClass.getMethod("withTags", List.class).invoke(full, List.of());
            entityClass.getMethod("setEtag", String.class).invoke(full, "W/\"state\"");
            putDynamic(entityClass, full, "DynamicNull", null);
            putDynamic(entityClass, full, "DynamicValue", "kept");

            byte[] defaultBody = serialize(serializer, full, entityClass);
            assertEquals(expected("{\"Name\":\"updated\",\"DynamicNull\":null,\"DynamicValue\":\"kept\"}"), JSON.readTree(defaultBody));

            Object roundTripped = deserialize(serializer, defaultBody, entityClass);
            assertTrue((Boolean) entityClass.getMethod("hasDynamicProperty", String.class)
                    .invoke(roundTripped, "DynamicNull"));
            assertEquals(expected("{\"Name\":\"updated\",\"DynamicNull\":null,\"DynamicValue\":\"kept\"}"),
                    JSON.readTree(serialize(serializer, roundTripped, entityClass)));

            Object patch = entityClass.getConstructor().newInstance();
            patch = entityClass.getMethod("withName", String.class).invoke(patch, "updated");
            entityClass.getMethod("setEtag", String.class).invoke(patch, "W/\"state\"");
            putDynamic(entityClass, patch, "DynamicNull", null);
            putDynamic(entityClass, patch, "DynamicValue", "kept");

            @SuppressWarnings("unchecked")
            Set<String> changed = (Set<String>) entityClass.getMethod("getChangedFields").invoke(patch);
            assertEquals(Set.of("Name", "DynamicNull", "DynamicValue"), changed);
            assertEquals(expected("{\"Name\":\"updated\",\"DynamicNull\":null,\"DynamicValue\":\"kept\"}"),
                    JSON.readTree(serialize(serializer, patch, entityClass, changed)));
        }
    }

    @Test
    void complexOpenTypeDefaultSerializationRoundTripsPresentNullDynamicProperty(@TempDir Path tempDir) throws Exception {
        Path output = generate(tempDir);
        assertNull(CompilationHarness.compileAll(output));

        try (URLClassLoader loader = loader(output)) {
            Class<?> type = Class.forName("com.example.complex.OpenInfo", true, loader);
            Object value = type.getConstructor().newInstance();
            type.getMethod("setName", String.class).invoke(value, "updated");
            type.getMethod("setTags", List.class).invoke(value, List.of());
            type.getMethod("setDynamicProperty", String.class, Object.class).invoke(value, "DynamicNull", null);
            type.getMethod("setDynamicProperty", String.class, Object.class).invoke(value, "DynamicValue", "kept");

            JacksonSerializer serializer = new JacksonSerializer();
            byte[] body = serialize(serializer, value, type);
            assertEquals(expected("{\"Name\":\"updated\",\"DynamicNull\":null,\"DynamicValue\":\"kept\"}"), JSON.readTree(body));

            Object roundTripped = deserialize(serializer, body, type);
            assertTrue((Boolean) type.getMethod("hasDynamicProperty", String.class)
                    .invoke(roundTripped, "DynamicNull"));
            assertEquals(expected("{\"Name\":\"updated\",\"DynamicNull\":null,\"DynamicValue\":\"kept\"}"),
                    JSON.readTree(serialize(serializer, roundTripped, type)));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static byte[] serialize(JacksonSerializer serializer, Object value, Class<?> type) {
        return ((JacksonSerializer) serializer).serialize(value, (Class) type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static byte[] serialize(JacksonSerializer serializer, Object value, Class<?> type, Set<String> changed) {
        return ((JacksonSerializer) serializer).serialize(value, (Class) type, changed);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object deserialize(JacksonSerializer serializer, byte[] body, Class<?> type) {
        return ((JacksonSerializer) serializer).deserialize(body, (Class) type);
    }

    private static void putDynamic(Class<?> type, Object target, String name, Object value) throws Exception {
        type.getMethod("putDynamicProperty", String.class, Object.class).invoke(target, name, value);
    }

    private static JsonNode expected(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static URLClassLoader loader(Path output) throws Exception {
        return new URLClassLoader(new URL[]{output.resolve(".classes").toUri().toURL()},
                OpenTypeSerializationRegressionTest.class.getClassLoader());
    }

    private static Path generate(Path tempDir) throws Exception {
        List<CsdlModel.PropertyModel> properties = List.of(
                new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                new CsdlModel.PropertyModel("Name", "Edm.String", false, null, List.of()),
                new CsdlModel.PropertyModel("Note", "Edm.String", true, null, List.of()),
                new CsdlModel.PropertyModel("Tags", "Collection(Edm.String)", true, null, List.of()));
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("OpenEntity", null,
                true, false, false, List.of(new CsdlModel.KeyModel(List.of("Id"))), properties, List.of());
        CsdlModel.ComplexTypeModel complex = new CsdlModel.ComplexTypeModel("OpenInfo", null,
                true, false, properties, List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(entity), List.of(complex), List.of(), List.of(), List.of(), List.of(), List.of());
        Path output = tempDir.resolve("generated");
        new Generator(output, Map.of(), "com.example").withGenerateWithMethods(true)
                .generate(new CsdlModel(List.of(schema), List.of()));
        return output;
    }
}
