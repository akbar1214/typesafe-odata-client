package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch B6): only equalTo/notEqualTo route null to isNull/isNotNull.
 * Every other operator must reject null explicitly instead of NPE-ing inside
 * escape()/format (no parameter name) or silently emitting "gt null" /
 * "contains(col,null)" that services reject.
 */
class QueryNullRejectionTest {

    private final StringProperty<Object> name = new StringProperty<>("Name", Object.class);
    private final NumberProperty<Object, Integer> age =
            new NumberProperty<>("Age", Object.class, "Edm.Int32");
    private final DateTimeProperty<Object> created =
            new DateTimeProperty<>("Created", Object.class);
    private final CollectionProperty<Object, String, CollectionProperty.FilterableElement<String>> tags =
            new CollectionProperty<>("Tags", Object.class, String.class,
                    CollectionProperty.FilterableElement::new);

    @Test
    void stringOperatorsRejectNull() {
        assertThrows(IllegalArgumentException.class, () -> name.contains(null));
        assertThrows(IllegalArgumentException.class, () -> name.startsWith(null));
        assertThrows(IllegalArgumentException.class, () -> name.endsWith(null));
        assertThrows(IllegalArgumentException.class, () -> name.matchesPattern(null));
        assertThrows(IllegalArgumentException.class, () -> name.concat((String) null));
        assertThrows(IllegalArgumentException.class, () -> name.concat((StringProperty<Object>) null));
        assertThrows(IllegalArgumentException.class, () -> name.indexOf(null));
        assertThrows(IllegalArgumentException.class, () -> name.greaterThan(null));
        assertThrows(IllegalArgumentException.class, () -> name.greaterThanOrEqualTo(null));
        assertThrows(IllegalArgumentException.class, () -> name.lessThan(null));
        assertThrows(IllegalArgumentException.class, () -> name.lessThanOrEqualTo(null));
    }

    @Test
    void numberOperatorsRejectNull() {
        assertThrows(IllegalArgumentException.class, () -> age.greaterThan(null));
        assertThrows(IllegalArgumentException.class, () -> age.greaterThanOrEqualTo(null));
        assertThrows(IllegalArgumentException.class, () -> age.lessThan(null));
        assertThrows(IllegalArgumentException.class, () -> age.lessThanOrEqualTo(null));
        assertThrows(IllegalArgumentException.class, () -> age.add(null));
        assertThrows(IllegalArgumentException.class, () -> age.subtract(null));
        assertThrows(IllegalArgumentException.class, () -> age.multiply(null));
        assertThrows(IllegalArgumentException.class, () -> age.divide(null));
        assertThrows(IllegalArgumentException.class, () -> age.modulo(null));
    }

    @Test
    void dateTimeOrderingOperatorsRejectNull() {
        assertThrows(IllegalArgumentException.class, () -> created.greaterThan(null));
        assertThrows(IllegalArgumentException.class, () -> created.greaterThanOrEqualTo(null));
        assertThrows(IllegalArgumentException.class, () -> created.lessThan(null));
        assertThrows(IllegalArgumentException.class, () -> created.lessThanOrEqualTo(null));
    }

    @Test
    void collectionContainsNullThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tags.contains(null),
                "contains(null) is not expressible — fail fast instead of emitting contains(col,null)");
        assertNotNull(ex.getMessage());
    }
}
