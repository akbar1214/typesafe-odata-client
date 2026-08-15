package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextPathTest {

    private static final String BASE = "https://services.odata.org/V4/TripPinService";

    @Test
    void singleQuoteInKeyValueIsDoubled() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "O'Brien");

        assertEquals(BASE + "/People('O''Brien')", path.toUrl());
    }

    @Test
    void ampersandInKeyValueIsPercentEncoded() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "A&B");

        assertEquals(BASE + "/People('A%26B')", path.toUrl());
    }

    @Test
    void questionMarkInKeyValueIsPercentEncoded() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "A?B");

        assertEquals(BASE + "/People('A%3FB')", path.toUrl());
    }

    @Test
    void hashInKeyValueIsPercentEncoded() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "A#B");

        assertEquals(BASE + "/People('A%23B')", path.toUrl());
    }

    @Test
    void percentInKeyValueIsPercentEncoded() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "100%");

        assertEquals(BASE + "/People('100%25')", path.toUrl());
    }

    @Test
    void compositeKeyWithSpecialCharsEncodesValues() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("OrderDetails")
                .addKey("OrderId", 1)
                .addKey("ProductName", "A&B?C");

        assertEquals(BASE + "/OrderDetails(OrderId=1,ProductName='A%26B%3FC')", path.toUrl());
    }

    @Test
    void addQueryOnEmptySegmentsCreatesTrailingQuerySegment() {
        ContextPath path = new ContextPath(BASE)
                .addQuery("$top", "5");

        assertEquals(BASE + "?$top=5", path.toUrl());
    }

    @Test
    void addQueryOnEmptySegmentsThenAddKeyPreservesQueryParam() {
        // addQuery() creates an empty-named segment with queries. addKey() must
        // preserve the existing queries when creating the updated segment (otherwise
        // the query param is silently dropped). Keys on a name-less segment are
        // not rendered in toUrl() (no name to attach key parentheses to).
        ContextPath path = new ContextPath(BASE)
                .addQuery("$filter", "Name eq 'test'")
                .addKey("Id", 1);

        String url = path.toUrl();
        assertTrue(url.contains("$filter=Name"), "URL should contain $filter query param: " + url);
    }

    @Test
    void addQueryOnEmptySegmentsThenAddQueryChainsCorrectly() {
        ContextPath path = new ContextPath(BASE)
                .addQuery("$top", "5")
                .addQuery("$skip", "10");

        String url = path.toUrl();
        assertTrue(url.contains("$top=5"), "URL should contain $top=5: " + url);
        assertTrue(url.contains("$skip=10"), "URL should contain $skip=10: " + url);
        // Both query params should appear
        assertEquals(BASE + "?$top=5&$skip=10", url);
    }

    @Test
    void spaceInKeyValueIsPercentEncoded() {
        // P0-2: space in key must be encoded as %20, otherwise URI.create throws
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "John Doe");

        assertEquals(BASE + "/People('John%20Doe')", path.toUrl());
    }

    @Test
    void spaceInKeyValueDoesNotCrashUriCreate() {
        // P0-2: the resulting URL must be safe for URI.create (no raw spaces)
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "John Doe");

        String url = path.toUrl();
        assertDoesNotThrow(() -> java.net.URI.create(url),
                "URL with space in key must not crash URI.create: " + url);
    }

    @Test
    void fromNextLinkAbsoluteUrlIsUsedAsBasePath() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        String next = "https://services.odata.org/V4/TripPinService/People?$skip=10&$top=10";
        ContextPath nextPath = path.fromNextLink(next);
        assertEquals(next, nextPath.toUrl());
    }

    @Test
    void fromNextLinkRelativeUrlResolvesAgainstBasePath() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink("/People?$skip=10");
        assertEquals(BASE + "/People?$skip=10", nextPath.toUrl());
    }

    @Test
    void fromNextLinkRelativeUrlWithoutLeadingSlashResolvesAgainstBasePath() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink("People?$skip=10");
        assertEquals(BASE + "/People?$skip=10", nextPath.toUrl());
    }

    @Test
    void fromNextLinkTrimsWhitespace() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink("  /People?$skip=10  ");
        assertEquals(BASE + "/People?$skip=10", nextPath.toUrl());
    }

    @Test
    void fromNextLinkWithQueryThenAddQueryProducesSingleQuestionMark() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink(BASE + "/People?$skiptoken=abc");
        ContextPath filtered = nextPath.addQuery("$filter", "Age gt 25");

        assertEquals(BASE + "/People?$skiptoken=abc&$filter=Age%20gt%2025", filtered.toUrl(),
                "chaining options onto a next page must not produce a double '?'");
    }

    @Test
    void fromNextLinkWithMultipleQueryParamsPreservesAllParams() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink(BASE + "/People?$skip=10&$top=5");

        assertEquals(BASE + "/People?$skip=10&$top=5", nextPath.toUrl());
    }

    @Test
    void fromNextLinkWithEncodedQueryValuesRoundTrips() {
        // %20 decodes and re-encodes to %20; %27 (' ) decodes and re-encodes to a literal
        // quote because encodeQueryParam restores OData-safe characters (see lesson 12).
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink(BASE + "/People?$filter=Name%20eq%20%27Bob%27");

        assertEquals(BASE + "/People?$filter=Name%20eq%20'Bob'", nextPath.toUrl(),
                "decoded query values must re-encode per the OData-safe character rules");
    }

    @Test
    void fromNextLinkPreservesPlusInQueryValues() {
        // H5: nextLink query strings are percent-encoded, NOT form-encoded. A literal '+'
        // inside a $skiptoken (common in Graph-style continuation tokens) must survive as
        // %2B, not be decoded as a space (URLDecoder) and corrupted into %20.
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink(BASE + "/People?$skiptoken=abc+def");

        assertEquals(BASE + "/People?$skiptoken=abc%2Bdef", nextPath.toUrl(),
                "literal '+' in nextLink values must round-trip as %2B");
    }

    @Test
    void fromNextLinkDecodesPercentEncodedPlusAndReencodesAsPercent2B() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink(BASE + "/People?$skiptoken=a%2Bb%3Dc");

        // %2B stays a literal plus (re-encoded as %2B); %3D ('=') is restored verbatim
        // like other OData-safe characters (see the round-trip test above).
        assertEquals(BASE + "/People?$skiptoken=a%2Bb=c", nextPath.toUrl(),
                "%2B (literal plus) must not become a space");
    }

    @Test
    void fromNextLinkRelativeWithQueryThenAddQueryProducesSingleQuestionMark() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink("People?$skiptoken=xyz");
        ContextPath filtered = nextPath.addQuery("$top", "5");

        assertEquals(BASE + "/People?$skiptoken=xyz&$top=5", filtered.toUrl());
    }

    @Test
    void fromNextLinkWithQueryThenAddCountSegmentKeepsSingleQuestionMark() {
        ContextPath path = new ContextPath(BASE).addSegment("People");
        ContextPath nextPath = path.fromNextLink(BASE + "/People?$skiptoken=abc");
        ContextPath countPath = nextPath.addCountSegment();

        assertEquals(BASE + "/People/$count?$skiptoken=abc", countPath.toUrl());
    }

    @Test
    void fromNextLinkRejectsNullOrEmpty() {
        ContextPath path = new ContextPath(BASE);
        assertThrows(IllegalArgumentException.class, () -> path.fromNextLink(null));
        assertThrows(IllegalArgumentException.class, () -> path.fromNextLink(""));
        assertThrows(IllegalArgumentException.class, () -> path.fromNextLink("   "));
    }

    @Test
    void addCountSegmentAppendsCountBeforeQueryParams() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addQuery("$filter", "Age gt 25");
        ContextPath countPath = path.addCountSegment();
        String url = countPath.toUrl();
        assertEquals(BASE + "/People/$count?$filter=Age%20gt%2025", url);
    }

    @Test
    void addCountSegmentOnEmptySegments() {
        ContextPath path = new ContextPath(BASE).addQuery("$filter", "Age gt 25");
        ContextPath countPath = path.addCountSegment();
        String url = countPath.toUrl();
        assertEquals(BASE + "/$count?$filter=Age%20gt%2025", url);
    }

    @Test
    void m11QueryRendersAfterAllSegmentsNotMidUrl() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addQuery("$skiptoken", "x")
                .addSegment("$ref");

        assertEquals(BASE + "/People/$ref?$skiptoken=x", path.toUrl(),
                "a segment appended after a query-bearing segment must not be swallowed by the query string");
    }

    @Test
    void m11QueriesFromMultipleSegmentsMergeIntoOneQueryString() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addQuery("$skiptoken", "x")
                .addSegment("Trips")
                .addQuery("$top", "5");

        assertEquals(BASE + "/People/Trips?$skiptoken=x&$top=5", path.toUrl(),
                "only one '?' is legal per URL; queries from all segments merge at the end");
    }
}
