package com.modernodata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectionPropertyTest {

    @Test
    void anyProducesCorrectODataWithSubstitutedPredicate() {
        CollectionProperty<Object, String> prop =
                new CollectionProperty<>("Emails", Object.class, String.class);

        FilterExpression expr = prop.any(e -> e.stringField("Value").equalTo("a"));

        assertEquals("Emails/any(x: x/Value eq 'a')", expr.toODataExpression());
    }

    @Test
    void allProducesCorrectODataWithSubstitutedPredicate() {
        CollectionProperty<Object, String> prop =
                new CollectionProperty<>("Emails", Object.class, String.class);

        FilterExpression expr = prop.all(e -> e.stringField("Value").equalTo("a"));

        assertEquals("Emails/all(x: x/Value eq 'a')", expr.toODataExpression());
    }
}
