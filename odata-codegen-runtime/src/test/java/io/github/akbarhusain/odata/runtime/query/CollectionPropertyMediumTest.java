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
        public final CollectionProperty<Tag, String, CollectionProperty.FilterableElement<String>, ?> NAMES =
                new CollectionProperty<>("x/Names", Tag.class, String.class, CollectionProperty.FilterableElement::new);
    }

    @Test
    void m8_nestedAnyShouldNotShadowAlias() {
        CollectionProperty<Person, Tag, TagFilterable, ?> tags =
                new CollectionProperty<>("Tags", Person.class, Tag.class, TagFilterable::new);

        String expr = tags.any(outer -> outer.NAME.equalTo("a")
                .and(outer.NAMES.any(inner -> inner.stringField("Value").equalTo("b"))))
                .toODataExpression();

        assertEquals("Tags/any(x: (x/Name eq 'a') and (x/Names/any(x1: x1/Value eq 'b')))",
                expr,
                "M8: nested any must use unique aliases (outer x, inner x1) and rebind every reference");
    }

    @Test
    void m8_aliasRebindMustNotCorruptQuotedLiterals() {
        CollectionProperty<Person, Tag, TagFilterable, ?> tags =
                new CollectionProperty<>("Tags", Person.class, Tag.class, TagFilterable::new);

        // The inner predicate's literal 'x/value' contains "x/" — the alias rebinding at
        // depth 1 must leave quoted literals untouched
        String expr = tags.any(outer -> outer.NAME.equalTo("a")
                .and(outer.NAMES.any(inner -> inner.stringField("Value").equalTo("x/value"))))
                .toODataExpression();

        assertTrue(expr.contains("x1/Value eq 'x/value'"),
                "M8: quoted literal must survive alias rebinding verbatim, got: " + expr);
        assertFalse(expr.contains("'x1/value'"),
                "M8: quoted literal must not be rewritten, got: " + expr);
    }

    @Test
    void m8_aliasRebindMustNotMatchInsideLongerIdentifiers() {
        CollectionProperty<Person, Tag, TagFilterable, ?> tags =
                new CollectionProperty<>("Tags", Person.class, Tag.class, TagFilterable::new);

        // A property path like x/Max/Value contains "x/" inside "Max/" — only standalone
        // references preceded by a non-identifier character may be rebound
        String expr = tags.any(outer -> outer.NAME.equalTo("a")
                .and(outer.NAMES.any(inner -> inner.stringField("Max/Value").equalTo("b"))))
                .toODataExpression();

        assertTrue(expr.contains("x1/Max/Value eq 'b'"),
                "M8: rebinding must not touch 'Max/' inside a longer path, got: " + expr);
        assertFalse(expr.contains("Max1/"),
                "M8: longer identifiers containing the alias must stay intact, got: " + expr);
    }

    @Test
    void m8_singleAnyStillWorks() {
        CollectionProperty<Person, Tag, TagFilterable, ?> tags =
                new CollectionProperty<>("Tags", Person.class, Tag.class, TagFilterable::new);
        String expr = tags.any(t -> t.NAME.equalTo("a")).toODataExpression();
        assertEquals("Tags/any(x: x/Name eq 'a')", expr, "single any should use x");
    }
}
