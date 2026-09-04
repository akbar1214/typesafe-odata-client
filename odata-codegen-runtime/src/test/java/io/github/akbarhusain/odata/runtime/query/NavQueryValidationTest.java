package io.github.akbarhusain.odata.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3: nav top()/skip()/count() accepted negative values and rendered
 * invalid OData ($top=-5). ApplyBuilder validates >= 0 (lesson 110) — the expansion
 * options must match.
 */
class NavQueryValidationTest {

    @Test
    void navTopRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> TestProps.NAME.top(-1));
    }

    @Test
    void navSkipRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> TestProps.NAME.skip(-1));
    }

    @Test
    void navQueryTopRejectsNegative() {
        NavQuery<Person, Trip, ?> q = TestProps.NAME.top(2);
        assertThrows(IllegalArgumentException.class, () -> q.top(-2));
        assertThrows(IllegalArgumentException.class, () -> q.skip(-2));
    }

    @Test
    void zeroIsAllowed() {
        assertEquals("$top=0", extractOption(TestProps.NAME.top(0).toODataExpand()));
        assertEquals("$skip=0", extractOption(TestProps.NAME.skip(0).toODataExpand()));
    }

    private static String extractOption(String expand) {
        int open = expand.indexOf('(');
        return expand.substring(open + 1, expand.length() - 1);
    }

    /** Minimal fixtures: Person has collection nav Trips of Trip. */
    static final class Person {}
    static final class Trip {}
    static final class TestProps {
        static final NavQuery<Person, Trip, ?> NAME = NavQuery.of("Trips");
    }
}
