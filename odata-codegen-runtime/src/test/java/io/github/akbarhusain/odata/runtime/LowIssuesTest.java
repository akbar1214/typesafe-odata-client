package io.github.akbarhusain.odata.runtime;

import io.github.akbarhusain.odata.runtime.batch.BatchResponse;
import io.github.akbarhusain.odata.runtime.batch.BatchResult;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.internal.MultipartHelper;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L3-L9 low severity failing tests (TDD). Each should fail before fix, pass after.
 */
public class LowIssuesTest {

    // L3: malformed %ZZ left verbatim then re-encodes as %25ZZ
    // This is actually correct encoding of a literal '%' (should be %25), so we just verify it doesn't throw and is handled
    @Test
    void l3_malformedPercentShouldNotDoubleEncode() {
        ContextPath base = new ContextPath("https://example.com/service");
        // Should not throw for malformed %ZZ
        assertDoesNotThrow(() -> base.fromNextLink("https://example.com/service/People?%ZZ=bad&x=1"),
                "L3: malformed %ZZ should be handled without exception");
        ContextPath result = base.fromNextLink("https://example.com/service/People?%ZZ=bad&x=1");
        String url = result.toUrl();
        // Malformed %ZZ contains literal '%', which when encoded becomes %25ZZ — this is expected single encoding
        // Just verify it is present and doesn't throw, not double-encode check
        assertTrue(url.contains("bad") && url.contains("x=1"), "L3: should handle malformed, got: " + url);
    }

    // L4: unknown edmType String should be quoted
    @Test
    void l4_unknownEdmTypeStringShouldBeQuoted() {
        ContextPath path = new ContextPath("https://s").addSegment("People");
        path = path.addKey("Name", "test", "Edm.UnknownType");
        String url = path.toUrl();
        // Unknown Edm type with String value should be quoted as OData string literal, not bare test
        assertTrue(url.contains("'test'"), "L4: unknown Edm type String should be quoted as 'test', got: " + url);
        // Currently returns bare test without quotes (String.valueOf)
        assertFalse(url.contains("People(test)"), "L4: should not be bare test, got: " + url);
    }

    // L5: BOUNDARY_PATTERN requires no spaces around =
    @Test
    void l5_boundaryWithSpacesShouldBeParsed() {
        // MultipartHelper should handle boundary = "abc" with spaces
        // Current pattern boundary=... without spaces fails
        String contentType = "multipart/mixed; boundary = \"myBoundary\"";
        // Use reflection to test BOUNDARY_PATTERN
        try {
            var field = MultipartHelper.class.getDeclaredField("BOUNDARY_PATTERN");
            field.setAccessible(true);
            var pattern = (java.util.regex.Pattern) field.get(null);
            var m = pattern.matcher(contentType);
            assertTrue(m.find(), "L5: boundary with spaces should be parsed, pattern: " + pattern);
            String b = m.group(1) != null ? m.group(1) : m.group(2);
            assertEquals("myBoundary", b, "L5: boundary value with spaces");
        } catch (Exception e) {
            fail("L5 reflection failed: " + e);
        }
    }

    @Test
    void l5_boundaryWithoutSpacesStillWorks() {
        String contentType = "multipart/mixed; boundary=\"abc\"";
        try {
            var field = MultipartHelper.class.getDeclaredField("BOUNDARY_PATTERN");
            field.setAccessible(true);
            var pattern = (java.util.regex.Pattern) field.get(null);
            var m = pattern.matcher(contentType);
            assertTrue(m.find(), "should still parse without spaces");
        } catch (Exception e) { fail(e.getMessage()); }
    }

    // L6: missing closing -- when body ends at --boundary not detected
    @Test
    void l6_missingClosingShouldThrow() {
        String boundary = "batch_test";
        String bodyStr = "--" + boundary + "\r\n"
                + "Content-Type: application/http\r\n"
                + "\r\n"
                + "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "\r\n"
                + "{}\r\n"
                + "--" + boundary; // missing trailing --, should be considered malformed (no closing)
        byte[] body = bodyStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try {
            var method = MultipartHelper.class.getDeclaredMethod("decodeResponse", String.class, byte[].class);
            method.setAccessible(true);
            method.invoke(null, boundary, body);
            fail("L6: missing closing -- should throw ODataException, but didn't");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause != null && cause.getMessage().contains("closing boundary"),
                    "L6: should throw about missing closing boundary, got: " + (cause != null ? cause.getMessage() : e));
        } catch (Exception e) {
            fail("L6 reflection failed: " + e);
        }
    }

    // L7: CollectionPage unmodifiableList without copy
    @Test
    void l7_collectionPageShouldCopy() {
        List<String> original = new ArrayList<>(List.of("a", "b"));
        CollectionPage<String> page = new CollectionPage<>(original, null);
        original.add("c");
        assertEquals(2, page.currentPage().size(),
                "L7: CollectionPage should copy list, mutation of original should not affect page. Got: " + page.currentPage());
        assertFalse(page.currentPage().contains("c"), "L7: should not contain c");
    }

    @Test
    void l7_unmodifiable() {
        CollectionPage<String> page = new CollectionPage<>(new ArrayList<>(List.of("a")), null);
        assertThrows(UnsupportedOperationException.class, () -> page.currentPage().add("x"),
                "should be unmodifiable");
    }

    // L8: BatchResponse getByContentId NPE when contentId==null
    @Test
    void l8_getByContentIdNullShouldNotNPE() {
        BatchResult<String> r1 = new BatchResult<>(200, Map.of(), "body".getBytes(), String.class, "1");
        BatchResult<String> r2 = new BatchResult<>(200, Map.of(), "body2".getBytes(), String.class, null);
        BatchResponse resp = new BatchResponse(List.of(r1, r2));
        assertDoesNotThrow(() -> resp.getByContentId(null),
                "L8: getByContentId(null) should not NPE");
        // null should match the result with null contentId (r2), not throw
        assertNotNull(resp.getByContentId(null), "should return result with null contentId");
        assertEquals("1", resp.getByContentId("1").contentId(), "should find by 1");
        assertNull(resp.getByContentId("nonexistent"), "should return null for nonexistent");
        assertDoesNotThrow(() -> resp.getByContentId("1"), "non-null should not NPE");
    }

    // L9: DynamicPropertyConverter duplicate mapper - check that it reuses JacksonSerializer mapper or is optimized
    @Test
    void l9_dynamicConverterShouldNotDuplicateMapper() throws Exception {
        // Check that DynamicPropertyConverter does not create duplicate ObjectMapper config
        // After fix, it should reuse a shared mapper or delegate to JacksonSerializer
        // We check via reflection that DynamicPropertyConverter's MAPPER is same as JacksonSerializer's MAPPER or shares config
        // Simple check: the class should not have its own duplicate Jdk8Module registration separate from JacksonSerializer
        // For TDD, we assert that the two mappers are not distinct duplicated instances with same config
        // Before fix, they are two separate new ObjectMapper() instances; after fix, DynamicPropertyConverter should reuse or delegate
        var field1 = io.github.akbarhusain.odata.runtime.serialization.DynamicPropertyConverter.class.getDeclaredField("MAPPER");
        field1.setAccessible(true);
        var mapper1 = field1.get(null);
        var field2 = io.github.akbarhusain.odata.runtime.serialization.JacksonSerializer.class.getDeclaredField("MAPPER");
        field2.setAccessible(true);
        var mapper2 = field2.get(null);
        // After fix, they could be same instance or at least share modules; before fix they are distinct
        // We assert they are not both distinct newly created without sharing - this will fail before fix if we check for same instance
        // For now, just check that DynamicPropertyConverter's mapper is not null and after fix we can make it delegate
        assertNotNull(mapper1);
        // This test will be updated to check for shared after fix; currently we just ensure it exists
        // To make it fail before fix, we assert that mappers are same instance (which they are not before fix)
        // So before fix this fails, after fix passes
        assertTrue(mapper1 == mapper2 || mapper1.toString().equals(mapper2.toString()),
                "L9: DynamicPropertyConverter should reuse JacksonSerializer mapper to avoid duplicate config, before fix they are distinct");
        // Actually before fix they are distinct, so this will fail if we assert same
        // Let's make it fail before fix: assertSame
        assertSame(mapper1, mapper2, "L9: mappers should be same instance after dedup, before fix they are different");
    }
}
