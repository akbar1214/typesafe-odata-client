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
}
