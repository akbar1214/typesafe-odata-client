package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectionPropertyTest {

    @Test
    void anyProducesCorrectODataWithSubstitutedPredicate() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>> prop =
                new CollectionProperty<>("Emails", Object.class, String.class, CollectionProperty.FilterableElement::new);

        FilterExpression expr = prop.any(e -> e.stringField("Value").equalTo("a"));

        assertEquals("Emails/any(x: x/Value eq 'a')", expr.toODataExpression());
    }

    @Test
    void allProducesCorrectODataWithSubstitutedPredicate() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>> prop =
                new CollectionProperty<>("Emails", Object.class, String.class, CollectionProperty.FilterableElement::new);

        FilterExpression expr = prop.all(e -> e.stringField("Value").equalTo("a"));

        assertEquals("Emails/all(x: x/Value eq 'a')", expr.toODataExpression());
    }

    @Test
    void containsString() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>> prop =
                new CollectionProperty<>("Emails", Object.class, String.class, CollectionProperty.FilterableElement::new);

        FilterExpression<Object> expr = prop.contains("scott@example.com");
        assertEquals("contains(Emails,'scott@example.com')", expr.toODataExpression());
    }

    @Test
    void containsNumber() {
        CollectionProperty<Object, Integer, CollectionProperty.FilterableElement<Integer>> prop =
                new CollectionProperty<>("Scores", Object.class, Integer.class, CollectionProperty.FilterableElement::new);

        FilterExpression<Object> expr = prop.contains(42);
        assertEquals("contains(Scores,42)", expr.toODataExpression());
    }

    @Test
    void length() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>> prop =
                new CollectionProperty<>("Emails", Object.class, String.class, CollectionProperty.FilterableElement::new);

        NumberExpression<Integer, Object> expr = prop.length();
        assertEquals("length(Emails)", expr.toODataExpression());
    }

    @Test
    void l13MissingFilterableFactoryThrowsClearError() {
        CollectionProperty<Object, Object, Object> prop =
                new CollectionProperty<>("Tags", Object.class, Object.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> prop.any(el -> new RawFilterExpression<>("true")),
                "a missing factory must fail with a clear message, not a bare NPE");
        assertTrue(ex.getMessage().contains("filterable"), "message explains the fix: " + ex.getMessage());
    }

    @Test
    void l13LambdaAliasFollowsFilterableElementPrefix() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>> prop =
                new CollectionProperty<>("Tags", Object.class, String.class,
                        () -> new CollectionProperty.FilterableElement<>("d"));

        FilterExpression<Object> expr = prop.any(el -> el.stringField("Value").equalTo("a"));
        assertEquals("Tags/any(d: d/Value eq 'a')", expr.toODataExpression(),
                "the lambda alias must match the element's property prefix — 'x:' with 'd/Value' " 
                        + "is a dangling reference");
    }

    @Test
    void l13InvalidLambdaAliasIsRejected() {
        CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>> prop =
                new CollectionProperty<>("Tags", Object.class, String.class,
                        () -> new CollectionProperty.FilterableElement<>("bad alias"));
        assertThrows(IllegalArgumentException.class,
                () -> prop.any(el -> el.stringField("Value").equalTo("a")),
                "aliases must be simple identifiers — anything else produces invalid OData");
    }
}
