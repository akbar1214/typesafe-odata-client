package io.github.akbarhusain.odata.runtime.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H5: ContextPath.encodeKeyValue misses '/' and '+'.
 * People('a/b') splits path; a+b ambiguous.
 */
class ContextPathEncodeKeyValueSlashPlusTest {

    private static final String BASE = "https://services.odata.org/V4/TripPinService";

    @Test
    void slashInKeyIsPercentEncoded() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "a/b");
        String url = path.toUrl();
        assertTrue(url.contains("%2F") || url.contains("%2f"),
                "H5: '/' in key value must be %2F, got: " + url);
        assertFalse(url.contains("People('a/b')"),
                "H5: literal slash splits path: " + url);
        assertDoesNotThrow(() -> java.net.URI.create(url), "URL must be URI-safe: " + url);
    }

    @Test
    void plusInKeyIsPercentEncoded() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "a+b");
        String url = path.toUrl();
        assertTrue(url.contains("%2B") || url.contains("%2b"),
                "H5: '+' in key value must be %2B, got: " + url);
        assertFalse(url.equals(BASE + "/People('a+b')"), "literal plus must be encoded: " + url);
    }

    @Test
    void slashAndPlusCombined() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "a/b+c");
        String url = path.toUrl();
        assertTrue(url.contains("%2F") || url.contains("%2f"), "slash encoded: " + url);
        assertTrue(url.contains("%2B") || url.contains("%2b"), "plus encoded: " + url);
    }

    @Test
    void existingEncodingsStillWork() {
        ContextPath path = new ContextPath(BASE)
                .addSegment("People")
                .addKey("UserName", "O'Brien & Co? #1 100%");
        String url = path.toUrl();
        assertTrue(url.contains("O''Brien"), "single quote doubling: " + url);
        assertTrue(url.contains("%26") || url.contains("%26"), "ampersand: " + url);
        assertTrue(url.contains("%20"), "space: " + url);
    }
}
