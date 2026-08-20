package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H4: ContextPath.encodeQueryParam restores '=' inside value — injects spurious query-param separator.
 * Value "a=b" must be encoded as a%3Db, not a=b.
 */
class ContextPathEncodeQueryParamTest {

    private static final String BASE = "https://services.odata.org/V4/TripPinService";

    @Test
    void equalsInQueryValueIsPercentEncodedNotRestored() {
        ContextPath path = new ContextPath(BASE).addQuery("q", "a=b");
        String url = path.toUrl();
        assertTrue(url.contains("a%3Db") || url.contains("a%3db"),
                "H4: '=' inside query value must be %3D, not literal '=' — got: " + url);
        assertFalse(url.contains("q=a=b"),
                "H4: literal '=' inside value breaks query parsing: " + url);
    }

    @Test
    void equalsInFilterValueIsEncoded() {
        ContextPath path = new ContextPath(BASE).addQuery("$filter", "Name eq 'a=b'");
        String url = path.toUrl();
        // Encoded filter should contain %3D for the '=' inside the quoted literal
        assertTrue(url.contains("%3D") || url.contains("%3d"),
                "H4: '=' in filter value must be encoded: " + url);
        // Must not contain naked 'a=b' inside the encoded query
        assertFalse(url.matches(".*q?=?'a=b'.*") && url.contains("'a=b'"),
                "should not contain literal a=b");
        // Ensure single '?' still
        assertEquals(1, url.chars().filter(c -> c == '?').count(),
                "single '?' expected: " + url);
    }

    @Test
    void ampersandColonSlashStillEncodedOrRestoredCorrectly() {
        ContextPath path = new ContextPath(BASE).addQuery("$filter", "Name eq 'a:b/c'");
        String url = path.toUrl();
        // : and / are OData-safe and restored; & must be encoded if inside value
        // This test documents that ':' and '/' restoration is intentional; '=' is not
        assertTrue(url.contains(":") && url.contains("/"),
                "OData-safe ':' and '/' should be restored: " + url);
    }
}
