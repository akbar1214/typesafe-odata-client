package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the emitted selector-lambda overloads on generated request classes:
 * collection requests gain select / orderBy / expand / filter lambda forms, entity
 * requests keep select + expand only, and every lambda overload delegates to its
 * constant form (inheriting the transformation guard, parenthesization, and rendering).
 */
class RequestGeneratorLambdaTest {

    private static final String NAMESPACE = "Microsoft.OData.SampleService.Models.TripPin";

    private String generateCollectionRequest(String entityName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = RequestGeneratorLambdaTest.class
                .getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.EntityTypeModel type = schema.entityTypes().stream()
                    .filter(e -> e.name().equals(entityName))
                    .findFirst()
                    .orElseThrow();
            return new RequestGenerator("com.example.trippin").generateCollectionRequest(type, schema);
        }
    }

    private String generateEntityRequest(String entityName) throws Exception {
        StaxCsdlParser parser = new StaxCsdlParser();
        try (InputStream is = RequestGeneratorLambdaTest.class
                .getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = parser.parse(is);
            CsdlModel.SchemaModel schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals(NAMESPACE))
                    .findFirst()
                    .orElseThrow();
            CsdlModel.EntityTypeModel type = schema.entityTypes().stream()
                    .filter(e -> e.name().equals(entityName))
                    .findFirst()
                    .orElseThrow();
            return new RequestGenerator("com.example.trippin").generateEntityRequest(type, schema);
        }
    }

    @Test
    void collectionRequestEmitsSelectLambdaOverload() throws Exception {
        String code = generateCollectionRequest("Person");
        assertTrue(code.contains(
                "public final PersonCollectionRequest select(java.util.function.Function<Person.Selector, ? extends PropertyExpression<? super Person, ?>>... selectors)"),
                "collection request should emit the select lambda overload");
        assertTrue(code.contains("return select(resolved);"),
                "select lambda overload delegates to the constant form "
                        + "(inheriting the transformation guard)");
        assertTrue(code.contains("Person.Selector s = new Person.Selector();"),
                "one fresh Selector is applied to every lambda");
    }

    @Test
    void collectionRequestEmitsOrderByLambdaOverload() throws Exception {
        String code = generateCollectionRequest("Person");
        assertTrue(code.contains(
                "public final PersonCollectionRequest orderBy(java.util.function.Function<Person.Selector, ? extends OrderExpression<? super Person, ?>>... expressions)"),
                "collection request should emit the orderBy lambda overload");
        assertTrue(code.contains("return orderBy(resolved);"));
    }

    @Test
    void collectionRequestEmitsFilterLambdaOverload() throws Exception {
        String code = generateCollectionRequest("Person");
        assertTrue(code.contains(
                "public PersonCollectionRequest filter(java.util.function.Function<Person.Selector, ? extends FilterExpression<? super Person>> predicate)"),
                "collection request should emit the filter lambda overload");
        assertTrue(code.contains("return filter(predicate.apply(s));"),
                "filter lambda delegates to the constant form (parenthesization unchanged)");
    }

    @Test
    void collectionRequestEmitsExpandConstantAndLambdaForms() throws Exception {
        String code = generateCollectionRequest("Person");
        assertTrue(code.contains(
                "public final PersonCollectionRequest expand(Expandable<? super Person>... expandables)"),
                "one constant expand over the sealed Expandable set");
        assertTrue(code.contains(
                "public final PersonCollectionRequest expand(java.util.function.Function<Person.Selector, ? extends Expandable<? super Person>> query)"),
                "expand lambda overload enables full-depth chains");
        assertTrue(code.contains("return expand(query.apply(s));"),
                "expand lambda delegates to the constant form");
        // the two legacy NavProperty/NavQuery overloads are gone (name-clash-free collapse)
        assertFalse(code.contains("NavProperty"), "NavProperty is deleted from the runtime");
    }

    @Test
    void entityRequestEmitsSelectAndExpandLambdasOnly() throws Exception {
        String code = generateEntityRequest("Person");
        assertTrue(code.contains(
                "public final PersonEntityRequest select(java.util.function.Function<Person.Selector, ? extends PropertyExpression<? super Person, ?>>... selectors)"),
                "entity request should emit the select lambda overload");
        assertTrue(code.contains(
                "public final PersonEntityRequest expand(java.util.function.Function<Person.Selector, ? extends Expandable<? super Person>> query)"),
                "entity request should emit the expand lambda overload");
        assertFalse(code.contains("Function<Person.Selector, ? extends FilterExpression"),
                "filter stays collection-only");
        assertFalse(code.contains("Function<Person.Selector, ? extends OrderExpression"),
                "orderBy stays collection-only");
    }

    @Test
    void zeroArgBridgesAreEmittedForEveryConstantLambdaVarargsPair() throws Exception {
        // zero-arg calls matched ONE varargs overload before the lambda overloads existed;
        // with both present they match both and javac reports ambiguity — every restored
        // pair needs an explicit no-arg bridge. expand() was already ambiguous pre-change
        // (the twin NavProperty/NavQuery overloads), so it needs no bridge here.
        String collection = generateCollectionRequest("Person");
        assertTrue(collection.contains("public final PersonCollectionRequest select() {"),
                "zero-arg select() on collection requests: " + collection);
        assertTrue(collection.contains("return select(new PropertyExpression[0]);"));
        assertTrue(collection.contains("public final PersonCollectionRequest orderBy() {"),
                "zero-arg orderBy() on collection requests");
        assertTrue(collection.contains("return orderBy(new OrderExpression[0]);"));

        String entity = generateEntityRequest("Person");
        assertTrue(entity.contains("public final PersonEntityRequest select() {"),
                "zero-arg select() on entity requests: " + entity);
        assertTrue(entity.contains("return select(new PropertyExpression[0]);"));
    }
}
