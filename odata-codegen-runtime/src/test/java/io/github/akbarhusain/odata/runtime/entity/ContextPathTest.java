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
}
