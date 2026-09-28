package io.github.akbarhusain.odata.runtime.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpHeadersTest {

    @Test
    void prohibitedRequestHeadersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.requireRequestName("Content-Length"));
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.requireRequestName("Host"));
        assertEquals("X-Custom", HttpHeaders.requireRequestName("X-Custom"));
    }

    @Test
    void requestHeaderValueMustNotBeEmptyButResponseValueMay() {
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.requireRequestValue("X-Test", ""));
        assertEquals("", HttpHeaders.requireValue("Content-Length", ""));
    }

    @Test
    void canonicalAndCaseInsensitiveLookup() {
        Map<String, List<String>> source = new LinkedHashMap<>();
        source.put("content-TYPE", new ArrayList<>(List.of("application/json")));
        Map<String, List<String>> copy = HttpHeaders.immutableCopy(source);
        assertTrue(copy.containsKey("Content-Type"));
        assertTrue(copy.containsKey("Content-Type".toLowerCase()));
        assertEquals(List.of("application/json"), copy.get("CONTENT-TYPE"));
    }

    @Test
    void conflictingResponseContentTypesAreRejected() {
        Map<String, List<String>> source = new LinkedHashMap<>();
        source.put("Content-Type", new ArrayList<>(List.of("application/json")));
        source.put("content-type", new ArrayList<>(List.of("application/xml")));
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.immutableResponseCopy(source));
    }

    @Test
    void statusPseudoHeaderAllowedOnlyOnResponses() {
        Map<String, List<String>> source = new LinkedHashMap<>();
        source.put(":status", new ArrayList<>(List.of("200")));
        assertEquals(List.of("200"), HttpHeaders.immutableResponseCopy(source).get(":status"));
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.immutableCopy(source));
    }
}
