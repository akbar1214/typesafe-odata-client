package io.github.akbarhusain.odata.runtime.http;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Spliterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L7: header nulls fail at the offending builder call (not later in build()), and header
 * insertion order survives. L5: CollectionPage's spliterator must not promise non-null
 * elements — JSON "value" arrays can contain null.
 */
class HttpRequestBuilderTest {

    @Test
    void l7NullHeaderValueFailsAtTheBuilderCall() {
        HttpRequest.Builder builder = HttpRequest.builder().url("https://example.com");
        assertThrows(NullPointerException.class, () -> builder.header("X-Null", null),
                "null values must fail at header(), not at build()");
        assertThrows(NullPointerException.class, () -> builder.header(null, "v"));
        assertThrows(NullPointerException.class,
                () -> builder.headers(Map.of("X-Null", List.of("a", null))));
    }

    @Test
    void l7HeaderInsertionOrderIsPreserved() {
        HttpRequest request = HttpRequest.builder()
                .url("https://example.com")
                .header("Z-Last", "1")
                .header("A-First", "2")
                .header("M-Middle", "3")
                .build();

        assertEquals(List.of("Z-Last", "A-First", "M-Middle"),
                List.copyOf(request.headers().keySet()),
                "header order must survive (Map.copyOf discards insertion order)");
    }

    @Test
    void l5CollectionPageSpliteratorDoesNotPromiseNonNull() {
        io.github.akbarhusain.odata.runtime.paging.CollectionPage<String> page =
                new io.github.akbarhusain.odata.runtime.paging.CollectionPage<>(
                        java.util.Arrays.asList("a", null, "c"), null);

        assertEquals(0, page.spliterator().characteristics() & Spliterator.NONNULL,
                "NONNULL would let stream implementations skip null handling for payloads "
                        + "that legally contain null elements");
        assertEquals(3, page.spliterator().estimateSize());
    }
}
