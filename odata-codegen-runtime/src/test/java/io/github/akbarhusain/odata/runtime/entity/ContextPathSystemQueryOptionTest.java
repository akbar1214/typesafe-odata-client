package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OData v4.01 Part 2: URL Conventions §5.1: "The same system query option, irrespective
 * of casing or whether or not it is prefixed with a $, MUST NOT be specified more than once
 * for any resource."
 *
 * <p>Before this contract was enforced, {@code addQuery} appended blindly, so any
 * code path that re-applied an option over an already-present one emitted a
 * non-conformant URL, which in practice services answer inconsistently (400, or silently
 * honouring one of the two copies). Three reachable paths existed:
 * <ul>
 *   <li>chaining an option onto a {@code @odata.nextLink} that already carried it
 *       (the documented server-driven-paging flow);</li>
 *   <li>the generated {@code nextPage(nextLink).top(n)} / {@code .filter(..)} shape;</li>
 *   <li>adding a query after appending another segment, which places it on a
 *       DIFFERENT segment than the earlier copy.</li>
 * </ul>
 *
 * <p>Last-writer-wins is the correct resolution: it is what a caller re-applying an
 * option means, and it keeps custom (non-system) options repeatable, which the spec
 * does not forbid.
 */
class ContextPathSystemQueryOptionTest {

    @Test
    void reapplyingTopReplacesTheExistingValue() {
        ContextPath path = new ContextPath("https://svc/People").addQuery("$top", "5");
        assertEquals("https://svc/People?$top=10", path.addQuery("$top", "10").toUrl());
    }

    @Test
    void nextLinkCarryingTopIsNotDuplicatedWhenTopIsReapplied() {
        ContextPath page = new ContextPath("https://svc/People")
                .fromNextLink("https://svc/People?$skiptoken=abc&$top=5");
        assertEquals("https://svc/People?$skiptoken=abc&$top=10",
                page.addQuery("$top", "10").toUrl());
    }

    @Test
    void duplicateAcrossDifferentSegmentsIsCollapsed() {
        // The first $top lives on the nextLink's query segment; the second is added
        // after a $ref segment was appended, so it lands on a DIFFERENT segment.
        ContextPath path = new ContextPath("https://svc/People")
                .fromNextLink("https://svc/People?$top=5")
                .addSegment("$ref")
                .addQuery("$top", "10");
        assertEquals("https://svc/People/$ref?$top=10", path.toUrl());
    }

    @Test
    void systemOptionNamesAreMatchedCaseInsensitivelyAndWithoutTheDollarPrefix() {
        // OData 4.01 services treat system query option names case-insensitively and
        // accept them with or without the '$' prefix, so $top and $TOP are the SAME
        // option and must not both be emitted. The surviving spelling is the one the
        // caller last passed (verbatim passthrough), so assert on the VALUE, not the case.
        assertEquals("10", topValueOf(new ContextPath("https://svc/People")
                .addQuery("$top", "5").addQuery("$TOP", "10").toUrl()));
        assertEquals("10", topValueOf(new ContextPath("https://svc/People")
                .addQuery("$top", "5").addQuery("top", "10").toUrl()));
    }

    private static String topValueOf(String url) {
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            if (pair.equalsIgnoreCase("$top=10") || pair.equalsIgnoreCase("top=10")) {
                return "10";
            }
        }
        return query;
    }

    @Test
    void customQueryOptionsRemainRepeatable() {
        // The 'MUST NOT be specified more than once' rule is scoped to SYSTEM query
        // options; a custom option is a distinct query string entry and may repeat.
        assertEquals("https://svc/People?cust=a&cust=b",
                new ContextPath("https://svc/People").addQuery("cust", "a").addQuery("cust", "b").toUrl());
    }

    @Test
    void distinctSystemOptionsAllSurvive() {
        assertEquals("https://svc/People?$filter=Age%20gt%2025&$top=5&$skip=10",
                new ContextPath("https://svc/People")
                        .addQuery("$filter", "Age gt 25")
                        .addQuery("$top", "5")
                        .addQuery("$skip", "10")
                        .toUrl());
    }

    @Test
    void laterSegmentQueryStillRendersOnceAfterAllSegments() {
        // Regression guard for decision 49 (queries render after ALL segments):
        // de-duplication must not move a query back in front of a later segment.
        ContextPath path = new ContextPath("https://svc/People")
                .addQuery("$top", "5")
                .addSegment("Trips")
                .addQuery("$select", "Name");
        assertEquals("https://svc/People/Trips?$top=5&$select=Name", path.toUrl());
    }
}
