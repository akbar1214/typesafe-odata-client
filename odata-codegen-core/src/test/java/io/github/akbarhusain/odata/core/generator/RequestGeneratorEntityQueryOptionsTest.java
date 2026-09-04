package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-shaping options on ENTITY requests — the request objects returned by keyed
 * accessors (decision 95) like {@code client.containers(id)}. $select/$expand are the
 * query options valid on a single-entity GET (filter/top/skip/orderby are
 * collection-only), and nested NavQuery expands render {@code Folders($expand=Abc)}
 * without dropping to raw strings.
 */
class RequestGeneratorEntityQueryOptionsTest {

    private String generatePersonRequest() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            var model = new StaxCsdlParser().parse(is);
            var schema = model.schemas().get(0);
            var person = schema.entityTypes().stream()
                    .filter(e -> e.name().equals("Person")).findFirst().orElseThrow();
            return new RequestGenerator("com.example.trippin", java.util.Map.of(),
                    "com.example.trippin", model.schemas()).generateEntityRequest(person, schema);
        }
    }

    @Test
    void entityRequestEmitsTypedExpandOverloads() throws Exception {
        String code = generatePersonRequest();
        assertTrue(code.contains(
                "@SafeVarargs\n    public final PersonEntityRequest expand(Expandable<? super Person>... expandables)"),
                "one constant expand over the sealed Expandable set: " + code);
        assertTrue(code.contains(
                "public final PersonEntityRequest expand(java.util.function.Function<Person.Selector, ? extends Expandable<? super Person>> query)"),
                "lambda expand renders nested options like Folders($expand=Abc)");
        assertTrue(code.contains("for (var e : expandables) next.expands.add(e.toODataExpand());"));
        assertTrue(code.contains("return expand(query.apply(s));"),
                "lambda expand delegates to the constant form");
    }

    @Test
    void entityRequestEmitsTypedSelect() throws Exception {
        String code = generatePersonRequest();
        assertTrue(code.contains(
                "@SafeVarargs\n    public final PersonEntityRequest select(PropertyExpression<? super Person, ?>... properties)"));
        assertTrue(code.contains("is not a selectable property "),
                "select() validates property paths exactly like the collection request");
    }

    @Test
    void entityRequestGetAppliesQueryOptions() throws Exception {
        String code = generatePersonRequest();
        assertTrue(code.contains("public ContextPath buildContext() {\n        ContextPath ctx = contextPath;"));
        assertTrue(code.contains("ctx = ctx.addQuery(\"$select\", String.join(\",\", selects));"));
        assertTrue(code.contains("ctx = ctx.addQuery(\"$expand\", String.join(\",\", expands));"));
        assertTrue(code.contains(
                "EntityOperations.executeAndGetEntity(context, buildContext(), Person.class"),
                "get() carries $select/$expand (was the bare contextPath)");
        assertFalse(code.contains("addQuery(\"$filter\""),
                "filter/top/skip/orderby stay collection-only");
    }
}
