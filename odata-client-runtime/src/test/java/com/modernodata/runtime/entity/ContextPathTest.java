package com.modernodata.runtime.entity;

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
}
