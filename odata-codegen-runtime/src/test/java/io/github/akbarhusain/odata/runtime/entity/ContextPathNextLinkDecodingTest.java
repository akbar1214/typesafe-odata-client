package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1: {@code decodePercent} decoded %HH escapes char-by-char, so multi-byte UTF-8
 * sequences (%C3%A9 = é) became mojibake (Ã©) when round-tripping nextLink query
 * values. Decoding must collect bytes and decode once as UTF-8.
 *
 * M2: {@code fromNextLink} treated "HTTP://…" as relative (case-sensitive scheme
 * check) while every other absolute-URL check uses regionMatches(true,…) — the
 * resolved URL was garbage for uppercase-scheme nextLinks.
 */
class ContextPathNextLinkDecodingTest {

    @Test
    void m1_multiByteUtf8QueryValueSurvivesRoundTrip() {
        // nextLink carrying a filter value with a non-ASCII character (é = %C3%A9)
        ContextPath path = new ContextPath("https://svc/root")
                .fromNextLink("https://svc/root/People?$filter=Name%20eq%20'Jos%C3%A9'");
        String url = path.toUrl();
        assertTrue(url.contains("Jos%C3%A9") || url.contains("José"),
                "multi-byte UTF-8 must survive the decode/re-encode cycle, got: " + url);
        assertFalse(url.contains("Ã©"),
                "byte-per-char decoding produces mojibake: " + url);
    }

    @Test
    void m1_malformedEscapeStillVerbatim() {
        ContextPath path = new ContextPath("https://svc/root")
                .fromNextLink("People?x=%ZZ%2");
        String url = path.toUrl();
        assertFalse(url.contains(" "), "no crash/garbling for malformed escapes: " + url);
    }

    @Test
    void m2_uppercaseSchemeIsAbsolute() {
        ContextPath path = new ContextPath("https://other/root")
                .fromNextLink("HTTP://services.odata.org/V4/TripPinService/People?$skiptoken=abc");
        String url = path.toUrl();
        // Scheme case is preserved verbatim (same policy as EntityOperations.isAbsoluteHttpUrl
        // passthrough); the point is that an uppercase-scheme nextLink is recognized as
        // ABSOLUTE instead of being resolved against basePath
        assertTrue(url.startsWith("HTTP://services.odata.org/V4/TripPinService/People"),
                "uppercase-scheme nextLink must stay absolute verbatim; got: " + url);
        assertFalse(url.startsWith("https://other/root"), "must not be resolved against basePath");
    }
}
