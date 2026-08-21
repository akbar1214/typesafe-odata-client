package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8: any/all alias x shadowing in nested any
 * inner any(x: ...) reuses x, shadowing outer
 */
class CollectionPropertyMediumTest {

    static class Person {}
    static class Tag { public String name; }

    // Minimal Filterable for test that mimics generated one with x/ prefix
    static class TagFilterable {
        public final StringProperty<Tag> NAME = new StringProperty<>("x/Name", Tag.class);
        public final CollectionProperty<Tag, String, CollectionProperty.FilterableElement<String>> NAMES =
                new CollectionProperty<>("x/Names", Tag.class, String.class, CollectionProperty.FilterableElement::new);
    }

    @Test
    void m8_nestedAnyShouldNotShadowAlias() {
        CollectionProperty<Person, Tag, TagFilterable> tags =
                new CollectionProperty<>("Tags", Person.class, Tag.class, TagFilterable::new);

        // Nested any: Tags/any(x: x/Name eq 'a' and x/Names/any(x: ...)) -> inner x shadows outer x
        // After fix, aliases should be unique like x and x1
        String expr = tags.any(outer -> outer.NAME.equalTo("a")
                .and(outer.NAMES.any(inner -> inner.stringField("Value").equalTo("b"))))
                .toODataExpression();

        // M8: currently both use x: Tags/any(x: x/Name eq 'a' and x/Names/any(x: x/Value eq 'b'))
        // Inner alias should be different (e.g., x1)
        assertTrue(expr.contains("x1:") || expr.contains("x0:") || expr.contains("y:") || expr.contains("x_"),
                "M8: nested any should use unique aliases (e.g., x and x1), got: " + expr);
        // Also ensure not both are exactly "any(x:" twice with same alias
        int first = expr.indexOf("any(x:");
        int second = expr.indexOf("any(x", first + 1);
        // After fix, second should be any(x1: or similar, not any(x:
        if (first != -1 && second != -1) {
            String secondAlias = expr.substring(second, Math.min(expr.length(), second + 8));
            assertFalse(secondAlias.startsWith("any(x:"),
                    "M8: inner any should not reuse alias x, got: " + expr);
        }
    }

    @Test
    void m8_singleAnyStillWorks() {
        CollectionProperty<Person, Tag, TagFilterable> tags =
                new CollectionProperty<>("Tags", Person.class, Tag.class, TagFilterable::new);
        String expr = tags.any(t -> t.NAME.equalTo("a")).toODataExpression();
        assertEquals("Tags/any(x: x/Name eq 'a')", expr, "single any should use x");
    }
}
