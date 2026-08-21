package io.github.akbarhusain.odata.runtime.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M10: Duplicate OData-* headers via builder.header
 */
class JdkHttpTransportMediumTest {

    @Test
    void m10_duplicateODataHeadersDeduped() throws Exception {
        var transport = new JdkHttpTransport();
        var method = JdkHttpTransport.class.getDeclaredMethod("buildJdkRequest", HttpRequest.class);
        method.setAccessible(true);

        HttpRequest req = new HttpRequest(HttpMethod.GET, "http://example.com/People",
                Map.of("OData-MaxVersion", List.of("4.0"), "OData-Version", List.of("4.0")),
                null, java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(10));

        java.net.http.HttpRequest.Builder builder = (java.net.http.HttpRequest.Builder) method.invoke(transport, req);
        java.net.http.HttpRequest jdkReq = builder.build();

        var maxVersions = jdkReq.headers().allValues("OData-MaxVersion");
        assertEquals(1, maxVersions.size(),
                "M10: duplicate OData-MaxVersion should be deduped, got: " + maxVersions);
        assertEquals("4.0", maxVersions.get(0), "M10: should keep caller's value, got: " + maxVersions);

        var versions = jdkReq.headers().allValues("OData-Version");
        assertEquals(1, versions.size(), "M10: OData-Version deduped, got: " + versions);
    }

    @Test
    void m10_defaultHeadersWhenNotSupplied() throws Exception {
        var transport = new JdkHttpTransport();
        var method = JdkHttpTransport.class.getDeclaredMethod("buildJdkRequest", HttpRequest.class);
        method.setAccessible(true);

        HttpRequest req = new HttpRequest(HttpMethod.GET, "http://example.com/People",
                Map.of(), null, java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(10));

        java.net.http.HttpRequest.Builder builder = (java.net.http.HttpRequest.Builder) method.invoke(transport, req);
        java.net.http.HttpRequest jdkReq = builder.build();
        assertFalse(jdkReq.headers().allValues("OData-MaxVersion").isEmpty(), "default MaxVersion should be present");
        assertEquals("4.01", jdkReq.headers().allValues("OData-MaxVersion").get(0));
    }

    @Test
    void m10_caseInsensitiveDedup() throws Exception {
        var transport = new JdkHttpTransport();
        var method = JdkHttpTransport.class.getDeclaredMethod("buildJdkRequest", HttpRequest.class);
        method.setAccessible(true);

        HttpRequest req = new HttpRequest(HttpMethod.GET, "http://example.com/People",
                Map.of("odata-maxversion", List.of("4.0")),
                null, java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(10));

        java.net.http.HttpRequest.Builder builder = (java.net.http.HttpRequest.Builder) method.invoke(transport, req);
        java.net.http.HttpRequest jdkReq = builder.build();
        long count = jdkReq.headers().map().keySet().stream()
                .filter(k -> k.equalsIgnoreCase("OData-MaxVersion"))
                .count();
        assertEquals(1, count, "M10: case-insensitive dedup should have one header key, got: " + jdkReq.headers().map().keySet());
    }
}
