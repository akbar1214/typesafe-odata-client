package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.query.ApplyExpression;
import io.github.akbarhusain.odata.runtime.query.Expandable;
import io.github.akbarhusain.odata.runtime.query.FilterExpression;
import io.github.akbarhusain.odata.runtime.query.OrderExpression;
import io.github.akbarhusain.odata.runtime.query.PropertyExpression;
import io.github.akbarhusain.odata.runtime.query.SelectableExpression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

class GeneratedRequestNullValidationTest {

    @Test
    void collectionRequestRejectsNullArgumentsAndElementsWithNamedMessages(@TempDir Path tempDir) throws Exception {
        try (RequestFixture fixture = request("com.example.collection.request.ItemCollectionRequest", tempDir)) {
            Object request = fixture.request();
            namedFailure(request, "filter", new Class<?>[]{FilterExpression.class}, "filter predicate must not be null");
            namedFailure(request, "filter", new Class<?>[]{Function.class}, "filter function must not be null");
            namedFailure(request, "filter", new Class<?>[]{Function.class},
                    new Function<Object, Object>() { public Object apply(Object selector) { return null; } },
                    "filter function must not return null");

            namedFailure(request, "select", new Class<?>[]{SelectableExpression[].class},
                    "select properties must not be null");
            namedFailure(request, "select", new Class<?>[]{SelectableExpression[].class},
                    (Object) new SelectableExpression<?>[]{null}, "select properties[0] must not be null");
            namedFailure(request, "select", new Class<?>[]{Function[].class},
                    "select selectors must not be null");
            namedFailure(request, "select", new Class<?>[]{Function[].class},
                    (Object) new Function<?, ?>[]{null}, "select selectors[0] must not be null");
            namedFailure(request, "select", new Class<?>[]{Function[].class},
                    (Object) new Function<?, ?>[]{new Function<Object, Object>() {
                        public Object apply(Object selector) { return null; }
                    }}, "select selectors[0] must not return null");

            namedFailure(request, "expand", new Class<?>[]{Expandable[].class},
                    "expand expandables must not be null");
            namedFailure(request, "expand", new Class<?>[]{Expandable[].class},
                    (Object) new Expandable<?>[]{null}, "expand expandables[0] must not be null");
            namedFailure(request, "expand", new Class<?>[]{Function.class},
                    "expand query must not be null");
            namedFailure(request, "expand", new Class<?>[]{Function.class},
                    new Function<Object, Object>() { public Object apply(Object selector) { return null; } },
                    "expand query must not return null");

            namedFailure(request, "orderBy", new Class<?>[]{OrderExpression[].class},
                    "orderBy expressions must not be null");
            namedFailure(request, "orderBy", new Class<?>[]{OrderExpression[].class},
                    (Object) new OrderExpression<?, ?>[]{null}, "orderBy expressions[0] must not be null");
            namedFailure(request, "orderBy", new Class<?>[]{Function[].class},
                    "orderBy expressions must not be null");
            namedFailure(request, "orderBy", new Class<?>[]{Function[].class},
                    (Object) new Function<?, ?>[]{null}, "orderBy expressions[0] must not be null");
            namedFailure(request, "orderBy", new Class<?>[]{Function[].class},
                    (Object) new Function<?, ?>[]{new Function<Object, Object>() {
                        public Object apply(Object selector) { return null; }
                    }}, "orderBy expressions[0] must not return null");

            namedFailure(request, "search", new Class<?>[]{String.class}, "search term must not be null");
            namedFailure(request, "apply", new Class<?>[]{ApplyExpression.class}, "apply expression must not be null");
            namedFailure(request, "apply", new Class<?>[]{String.class}, "apply raw must not be null");
        }
    }

    @Test
    void entityRequestRejectsNullSelectAndExpandArgumentsAndElementsWithNamedMessages(@TempDir Path tempDir) throws Exception {
        try (RequestFixture fixture = request("com.example.entity.request.ItemEntityRequest", tempDir)) {
            Object request = fixture.request();
            namedFailure(request, "select", new Class<?>[]{SelectableExpression[].class},
                    "select properties must not be null");
            namedFailure(request, "select", new Class<?>[]{SelectableExpression[].class},
                    (Object) new SelectableExpression<?>[]{null}, "select properties[0] must not be null");
            namedFailure(request, "select", new Class<?>[]{Function[].class},
                    "select selectors must not be null");
            namedFailure(request, "select", new Class<?>[]{Function[].class},
                    (Object) new Function<?, ?>[]{null}, "select selectors[0] must not be null");
            namedFailure(request, "select", new Class<?>[]{Function[].class},
                    (Object) new Function<?, ?>[]{new Function<Object, Object>() {
                        public Object apply(Object selector) { return null; }
                    }}, "select selectors[0] must not return null");

            namedFailure(request, "expand", new Class<?>[]{Expandable[].class},
                    "expand expandables must not be null");
            namedFailure(request, "expand", new Class<?>[]{Expandable[].class},
                    (Object) new Expandable<?>[]{null}, "expand expandables[0] must not be null");
            namedFailure(request, "expand", new Class<?>[]{Function.class},
                    "expand query must not be null");
            namedFailure(request, "expand", new Class<?>[]{Function.class},
                    new Function<Object, Object>() { public Object apply(Object selector) { return null; } },
                    "expand query must not return null");
        }
    }

    private static void namedFailure(Object request, String name, Class<?>[] parameterTypes,
                                     String expectedMessage) {
        namedFailure(request, name, parameterTypes, new Object[]{null}, expectedMessage);
    }

    private static void namedFailure(Object request, String name, Class<?>[] parameterTypes,
                                     Object argument, String expectedMessage) {
        namedFailure(request, name, parameterTypes, new Object[]{argument}, expectedMessage);
    }

    private static void namedFailure(Object request, String name, Class<?>[] parameterTypes,
                                     Object[] arguments, String expectedMessage) {
        Method method;
        try {
            method = request.getClass().getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        try {
            method.invoke(request, arguments);
            fail("Expected " + name + Arrays.toString(parameterTypes) + " to reject null input");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertInstanceOf(NullPointerException.class, cause);
            assertEquals(expectedMessage, cause.getMessage());
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static RequestFixture request(String className, Path tempDir) throws Exception {
        Path output = tempDir.resolve("generated");
        CsdlModel.EntityTypeModel entity = new CsdlModel.EntityTypeModel("Item", null,
                false, false, false, List.of(new CsdlModel.KeyModel(List.of("Id"))),
                List.of(new CsdlModel.PropertyModel("Id", "Edm.Int32", false, null, List.of()),
                        new CsdlModel.PropertyModel("Name", "Edm.String", false, null, List.of())), List.of());
        CsdlModel.SchemaModel schema = new CsdlModel.SchemaModel("NS", null,
                List.of(entity), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        new Generator(output, Map.of(), "com.example")
                .generate(new CsdlModel(List.of(schema), List.of()));
        assertNull(CompilationHarness.compileAll(output));

        URLClassLoader loader = new URLClassLoader(
                new URL[]{output.resolve(".classes").toUri().toURL()},
                GeneratedRequestNullValidationTest.class.getClassLoader());
        Class<?> type = Class.forName(className, true, loader);
        Context context = Context.builder().baseUrl("https://example.test/root").build();
        Object request = type.getConstructor(Context.class,
                io.github.akbarhusain.odata.runtime.entity.ContextPath.class)
                .newInstance(context, context.basePath().addSegment("Items"));
        return new RequestFixture(loader, request);
    }

    private record RequestFixture(URLClassLoader loader, Object request) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            loader.close();
        }
    }
}
