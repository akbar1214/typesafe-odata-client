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
}
